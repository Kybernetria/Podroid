#!/usr/bin/env python3

import os
import shutil
import stat
import subprocess
import tempfile
import time
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
GUEST_FILES = REPO_ROOT / "build-rootfs/files"
ENROLL = "usr/local/bin/podroid-tailscale-enroll"
STATUS = "usr/local/bin/podroid-tailscale-status"
RECONNECT = "usr/local/bin/podroid-tailscale-reconnect"
COMMON = "usr/local/libexec/podroid-tailscale-common"
TEST_SECRET = "headscale-one-use-test-key"


class GuestTailscaleHelperTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name) / "guest"
        for relative in (ENROLL, STATUS, RECONNECT, COMMON):
            destination = self.root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(GUEST_FILES / relative, destination)
            destination.chmod(0o755)
        (self.root / "run").mkdir()
        (self.root / "var/lib/tailscale").mkdir(parents=True)
        fake = self.root / "usr/bin/tailscale"
        fake.parent.mkdir(parents=True, exist_ok=True)
        fake.write_text(
            """#!/bin/sh
set -eu
is_status=0
for arg in "$@"; do [ "$arg" != status ] || is_status=1; done
if [ "$is_status" -eq 1 ]; then
    if [ "${FAKE_STATUS_OVERSIZE:-0}" = 1 ]; then
        head -c 262145 /dev/zero | tr '\\000' x
    else
        printf '{"BackendState":"%s"}\\n' "${FAKE_BACKEND_STATE:-Stopped}"
    fi
    exit "${FAKE_STATUS_EXIT:-0}"
fi
{
    echo CALL
    for arg in "$@"; do printf '%s\\n' "$arg"; done
} >> "$FAKE_CALL_LOG"
for arg in "$@"; do
    case "$arg" in
        --auth-key=file:*)
            key_file=${arg#--auth-key=file:}
            [ -f "$key_file" ] || exit 90
            cat "$key_file" > "$FAKE_OBSERVED_KEY"
            ;;
        *headscale-one-use-test-key*) exit 91 ;;
    esac
done
if [ "${FAKE_SLEEP_UP:-0}" = 1 ]; then
    sleep 2
fi
if [ "${FAKE_FAIL_UP:-0}" = 1 ]; then
    printf '%s\\n' "$FAKE_SECRET"
    printf '%s\\n' "$FAKE_SECRET" >&2
    exit 23
fi
exit 0
"""
        )
        fake.chmod(0o755)
        fake_daemon = self.root / "usr/sbin/tailscaled"
        fake_daemon.parent.mkdir(parents=True, exist_ok=True)
        fake_daemon.write_text(
            """#!/bin/sh
set -eu
state=
for arg in "$@"; do
    case "$arg" in --state=*) state=${arg#--state=} ;; esac
done
[ -n "$state" ] || exit 2
mkdir -p "${state%/*}"
printf 'fake persistent node identity\\n' > "$state"
printf '%s\\n' "$@" >> "$FAKE_DAEMON_LOG"
"""
        )
        fake_daemon.chmod(0o755)
        self.call_log = self.root / "calls.log"
        self.daemon_log = self.root / "tailscaled.log"
        self.observed_key = self.root / "observed-key"
        self.base_env = {
            **os.environ,
            "PODROID_TAILSCALE_TEST_ROOT": str(self.root),
            "FAKE_CALL_LOG": str(self.call_log),
            "FAKE_OBSERVED_KEY": str(self.observed_key),
            "FAKE_DAEMON_LOG": str(self.daemon_log),
        }

    def tearDown(self):
        self.temporary.cleanup()

    def key_file(self, name="one-use.key", content=TEST_SECRET + "\n", mode=0o600):
        path = self.root / "run" / name
        path.write_text(content)
        path.chmod(mode)
        return path

    def enroll(self, url, hostname="podroid-guest", key=None, *extra, input_bytes=None, env=None):
        command = [
            str(self.root / ENROLL),
            "--login-server", url,
            "--hostname", hostname,
        ]
        if key is None:
            command.append("--auth-key-stdin")
        else:
            command.extend(("--auth-key-file", str(key)))
        command.extend(extra)
        return subprocess.run(
            command,
            input=input_bytes,
            capture_output=True,
            env={**self.base_env, **(env or {})},
            timeout=10,
        )

    def assert_no_staged_keys(self):
        self.assertEqual(list((self.root / "run").glob("podroid-tailscale-auth.*")), [])

    def test_invalid_urls_and_hostnames_fail_before_effect_and_delete_key(self):
        invalid_urls = (
            "http://headscale.example.test",
            "https://",
            "https://user@headscale.example.test",
            "https://headscale.example.test/path",
            "https://headscale.example.test?x=1",
            "https://headscale.example.test:0",
            "https://headscale.example.test:65536",
            "https://headscale.example.test\n.invalid",
            "https://" + "a" * 2049,
        )
        for index, url in enumerate(invalid_urls):
            with self.subTest(url=url):
                key = self.key_file(f"invalid-url-{index}.key")
                result = self.enroll(url, key=key)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(key.exists())
                self.assert_no_staged_keys()
        for index, hostname in enumerate(("", "-guest", "guest_1", "a" * 64 + ".test")):
            with self.subTest(hostname=hostname):
                key = self.key_file(f"invalid-host-{index}.key")
                result = self.enroll("https://headscale.example.test", hostname, key)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(key.exists())
        self.assertFalse(self.call_log.exists())

    def test_missing_client_still_cleans_one_use_key(self):
        (self.root / "usr/bin/tailscale").unlink()
        key = self.key_file("missing-client.key")
        result = self.enroll("https://headscale.example.test", key=key)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(key.exists())
        self.assert_no_staged_keys()

    def test_mode_0600_and_bounded_key_are_required_and_cleaned(self):
        for name, content, mode in (
            ("bad-mode", TEST_SECRET + "\n", 0o640),
            ("short", "short\n", 0o600),
            ("oversize", "a" * 513 + "\n", 0o600),
            ("whitespace", "not a valid key\n", 0o600),
            ("multiple-lines", "first-valid-key\nsecond-valid-key\n", 0o600),
        ):
            with self.subTest(name=name):
                key = self.key_file(name, content, mode)
                result = self.enroll("https://headscale.example.test", key=key)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(key.exists())
                self.assert_no_staged_keys()

    def test_key_uses_file_argv_is_never_printed_and_is_always_deleted(self):
        key = self.key_file()
        result = self.enroll("https://HEADSCALE.example.test/", "Guest-01", key)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(key.exists())
        self.assert_no_staged_keys()
        self.assertEqual(self.observed_key.read_text(), TEST_SECRET + "\n")
        argv = self.call_log.read_text()
        self.assertIn("--auth-key=file:", argv)
        self.assertIn("--ssh=false", argv)
        self.assertIn("--accept-routes=false", argv)
        self.assertIn("--advertise-exit-node=false", argv)
        self.assertNotIn(TEST_SECRET, argv)
        self.assertNotIn(TEST_SECRET.encode(), result.stdout + result.stderr)
        marker = (self.root / "var/lib/tailscale/podroid-enrollment").read_text()
        self.assertEqual(
            marker,
            "podroid-tailscale-enrollment-v1\nhttps://headscale.example.test\nguest-01\n",
        )
        self.assertNotIn(TEST_SECRET, marker)

    def test_stdin_is_bounded_staged_and_removed(self):
        result = self.enroll(
            "https://headscale.example.test",
            key=None,
            input_bytes=(TEST_SECRET + "\n").encode(),
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.observed_key.read_text(), TEST_SECRET + "\n")
        self.assert_no_staged_keys()

    def test_concurrent_same_server_enrollment_consumes_only_one_key(self):
        first_key = self.key_file("concurrent-first.key")
        second_key = self.key_file("concurrent-second.key", "concurrent-second-key\n")
        first = subprocess.Popen(
            [
                str(self.root / ENROLL),
                "--login-server", "https://headscale.example.test",
                "--hostname", "podroid-guest",
                "--auth-key-file", str(first_key),
            ],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            env={**self.base_env, "FAKE_SLEEP_UP": "1"},
        )
        for _ in range(100):
            if self.call_log.exists():
                break
            time.sleep(0.01)
        self.assertTrue(self.call_log.exists())
        second = self.enroll("https://headscale.example.test", key=second_key)
        first_output = first.communicate(timeout=5)
        self.assertEqual(first.returncode, 0, first_output[1])
        self.assertEqual(second.returncode, 0, second.stderr)
        self.assertFalse(first_key.exists())
        self.assertFalse(second_key.exists())
        self.assert_no_staged_keys()
        self.assertEqual(self.call_log.read_text().splitlines().count("CALL"), 1)
        self.assertEqual(self.observed_key.read_text(), TEST_SECRET + "\n")

    def test_signal_path_removes_input_and_staged_key(self):
        key = self.key_file("signal.key")
        process = subprocess.Popen(
            [
                str(self.root / ENROLL),
                "--login-server", "https://headscale.example.test",
                "--hostname", "podroid-guest",
                "--auth-key-file", str(key),
            ],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            env={**self.base_env, "FAKE_SLEEP_UP": "1"},
        )
        for _ in range(100):
            if self.call_log.exists():
                break
            time.sleep(0.01)
        self.assertTrue(self.call_log.exists())
        process.terminate()
        process.communicate(timeout=5)
        self.assertNotEqual(process.returncode, 0)
        self.assertFalse(key.exists())
        self.assert_no_staged_keys()

    def test_same_server_is_idempotent_without_reusing_second_key(self):
        first = self.key_file("first.key")
        self.assertEqual(self.enroll("https://headscale.example.test", key=first).returncode, 0)
        second = self.key_file("second.key", "different-one-use-key\n")
        result = self.enroll("https://HEADSCALE.example.test/", key=second)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(second.exists())
        self.assertEqual(self.call_log.read_text().splitlines().count("CALL"), 1)
        self.assertEqual(self.observed_key.read_text(), TEST_SECRET + "\n")

    def test_changed_server_fails_closed_until_explicit_reauth(self):
        self.assertEqual(
            self.enroll("https://one.example.test", key=self.key_file("first.key")).returncode,
            0,
        )
        refused_key = self.key_file("refused.key", "refused-one-use-key\n")
        refused = self.enroll("https://two.example.test", key=refused_key)
        self.assertNotEqual(refused.returncode, 0)
        self.assertFalse(refused_key.exists())
        self.assertEqual(self.call_log.read_text().splitlines().count("CALL"), 1)

        reauth_key = self.key_file("reauth.key", "reauth-one-use-key\n")
        reauth = self.enroll("https://two.example.test", "podroid-guest", reauth_key, "--reauth")
        self.assertEqual(reauth.returncode, 0, reauth.stderr)
        argv = self.call_log.read_text()
        self.assertEqual(argv.splitlines().count("CALL"), 2)
        self.assertIn("--force-reauth", argv)
        self.assertIn("https://two.example.test", (self.root / "var/lib/tailscale/podroid-enrollment").read_text())

    def test_dependency_output_cannot_leak_key_on_failure(self):
        key = self.key_file()
        result = self.enroll(
            "https://headscale.example.test",
            key=key,
            env={"FAKE_FAIL_UP": "1", "FAKE_SECRET": TEST_SECRET},
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn(TEST_SECRET.encode(), result.stdout + result.stderr)
        self.assertIn(b"tailscale enrollment failed", result.stderr)
        self.assertFalse(key.exists())
        self.assert_no_staged_keys()
        self.assertFalse((self.root / "var/lib/tailscale/podroid-enrollment").exists())

    def test_reboot_reconnect_is_bounded_idempotent_and_never_uses_auth_key(self):
        daemon_state = self.root / "var/lib/tailscale/tailscaled.state"
        subprocess.run(
            [str(self.root / "usr/sbin/tailscaled"), f"--state={daemon_state}"],
            check=True,
            env=self.base_env,
        )
        self.assertEqual(daemon_state.read_text(), "fake persistent node identity\n")
        self.assertEqual(
            self.enroll("https://headscale.example.test", key=self.key_file()).returncode,
            0,
        )
        before = self.call_log.read_text().splitlines().count("CALL")
        reconnect = subprocess.run(
            [str(self.root / RECONNECT)],
            capture_output=True,
            env={**self.base_env, "FAKE_BACKEND_STATE": "Stopped"},
            timeout=10,
        )
        self.assertEqual(reconnect.returncode, 0, reconnect.stderr)
        calls = self.call_log.read_text()
        self.assertEqual(calls.splitlines().count("CALL"), before + 1)
        reconnect_argv = calls.rsplit("CALL\n", 1)[1]
        self.assertNotIn("--auth-key", reconnect_argv)
        self.assertIn("--ssh=false", reconnect_argv)

        running = subprocess.run(
            [str(self.root / RECONNECT)],
            capture_output=True,
            env={**self.base_env, "FAKE_BACKEND_STATE": "Running"},
            timeout=10,
        )
        self.assertEqual(running.returncode, 0, running.stderr)
        self.assertEqual(self.call_log.read_text().splitlines().count("CALL"), before + 1)
        self.assertEqual(daemon_state.read_text(), "fake persistent node identity\n")
        self.assertIn("--state=", self.daemon_log.read_text())

    def test_status_output_is_bounded(self):
        result = subprocess.run(
            [str(self.root / STATUS)],
            capture_output=True,
            env={**self.base_env, "FAKE_STATUS_OVERSIZE": "1"},
            timeout=10,
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, b"")

    def test_openrc_and_docs_preserve_reboot_and_identity_separation(self):
        runlevels = (REPO_ROOT / "build-rootfs/runlevels.lock").read_text()
        self.assertIn("default tailscale /etc/init.d/tailscale\n", runlevels)
        self.assertIn(
            "default podroid-tailscale-reconnect /etc/init.d/podroid-tailscale-reconnect\n",
            runlevels,
        )
        ready = (GUEST_FILES / "etc/init.d/podroid-ready").read_text()
        self.assertNotIn("need tailscale", ready)
        docs = (
            (REPO_ROOT / "docs/adr/0007-linux-tailscaled-for-guest-workloads.md").read_text()
            + (REPO_ROOT / "tests/networking/README.md").read_text()
        )
        for phrase in ("separate node identities", "must never be reused", "/var/lib/tailscale"):
            self.assertIn(phrase, docs)


if __name__ == "__main__":
    unittest.main()
