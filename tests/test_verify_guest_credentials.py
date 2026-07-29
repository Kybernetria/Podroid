#!/usr/bin/env python3

import importlib.util
import os
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

MODULE_PATH = Path(__file__).with_name("verify_guest_credentials.py")
SPEC = importlib.util.spec_from_file_location("verify_guest_credentials", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
verifier = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verifier)
REPO_ROOT = Path(__file__).resolve().parent.parent
REAL_ROOTFS = REPO_ROOT / "app/src/main/assets/alpine-rootfs.squashfs"


def sha512_crypt(password: str, salt: str = "testsalt") -> bytes:
    result = subprocess.run(
        ["openssl", "passwd", "-6", "-salt", salt, password],
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if result.stderr:
        raise AssertionError(f"unexpected openssl diagnostics: {result.stderr!r}")
    return result.stdout.rstrip(b"\n")


def shadow_with(root_hash: bytes) -> bytes:
    return b"root:" + root_hash + b":20000:0:99999:7:::\nmessagebus:!:20000::::::\n"


class MockArtifact:
    def __init__(self):
        self.files = {
            "etc/shadow": shadow_with(sha512_crypt("deterministic-nondefault-test-secret")),
            "etc/conf.d/dropbear": b'# test\nDROPBEAR_OPTS="-s"\n',
            "etc/issue": b"SSH: public-key authentication only\n",
            "usr/local/bin/podroid-login": b"#!/bin/sh\nexec /bin/login -f root\n",
        }
        self.listing = b"\n".join(
            [
                b"drwxr-xr-x 0/0 0 2026-01-01 00:00 squashfs-root",
                b"drwxr-xr-x 0/0 0 2026-01-01 00:00 squashfs-root/etc",
                b"-rw-r----- 0/0 200 2026-01-01 00:00 squashfs-root/etc/shadow",
                b"-rw-r--r-- 0/0 23 2026-01-01 00:00 squashfs-root/etc/conf.d/dropbear",
                b"-rw-r--r-- 0/0 40 2026-01-01 00:00 squashfs-root/etc/issue",
                b"-rwxr-xr-x 0/0 40 2026-01-01 00:00 squashfs-root/usr/local/bin/podroid-login",
            ]
        )

    def run_bounded(self, command, **_kwargs):
        if len(command) > 1 and command[1] == "passwd":
            salt = command[command.index("-salt") + 1]
            hashes = [sha512_crypt(password, salt) for password in command[command.index(salt) + 1 :]]
            return b"\n".join(hashes) + b"\n", b""
        if "-s" in command:
            return b"Filesystem size 1024 bytes (1.00 Kbytes)\nNumber of inodes 6\n", b""
        if "-lln" in command:
            return self.listing, b""
        if "-cat" in command:
            guest_path = command[-1]
            if guest_path not in self.files:
                raise verifier.VerificationError(f"command failed while reading {guest_path}")
            return self.files[guest_path], b""
        raise AssertionError(f"unexpected command: {command!r}")


class GuestCredentialVerifierTest(unittest.TestCase):
    def test_actual_hash_generator_uses_runtime_entropy(self):
        verifier.verify_hash_generator(REPO_ROOT)

    def test_authoritative_shadow_flow_accepts_build_script(self):
        verifier.verify_shadow_build_flow((REPO_ROOT / "build-rootfs/build-rootfs.sh").read_bytes())

    def test_authoritative_shadow_flow_rejects_duplicate_or_alternate_mutation(self):
        generator = b"ROOT_HASH=$(/work/generate-root-password-hash.sh)\n"
        mutation = b'sed -i "s|^root:[^:]*:|root:${ROOT_HASH}:|" "$ROOTFS/etc/shadow"\n'
        cases = {
            "duplicate generator": generator + generator + mutation,
            "second shadow writer": generator + mutation + b'echo bad >> "$ROOTFS/etc/shadow"\n',
            "alternate hash source": b"ROOT_HASH=$(openssl passwd -6 fixed)\n" + mutation,
        }
        for name, script in cases.items():
            with self.subTest(name=name), self.assertRaises(verifier.VerificationError):
                verifier.verify_shadow_build_flow(script)

    def test_source_iteration_preserves_newline_filename(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            suspicious = root / "line\nbreak"
            suspicious.write_bytes(b"-----BEGIN OPENSSH PRIVATE KEY-----\n")
            entries = list(verifier.source_entries([root]))
            self.assertEqual(entries[0][0].name, "line\nbreak")
            with self.assertRaisesRegex(verifier.VerificationError, "bundled SSH key material"):
                for path, metadata in entries:
                    if stat.S_ISREG(metadata.st_mode) and verifier.KEY_MATERIAL.search(
                        verifier.read_regular_file(path)
                    ):
                        verifier.fail(f"bundled SSH key material in {path!r}")

    def test_source_entry_bound_is_enforced_while_scanning(self):
        class BoundedScandir:
            def __init__(self):
                self.index = 0

            def __enter__(self):
                return self

            def __exit__(self, *_args):
                return False

            def __iter__(self):
                return self

            def __next__(self):
                self.index += 1
                if self.index > 4:
                    raise AssertionError("scanner consumed entries beyond the configured bound")
                return mock.Mock(name=f"entry-{self.index}")

        scanner = BoundedScandir()
        with mock.patch.object(verifier, "MAX_SOURCE_ENTRIES", 3), mock.patch.object(
            verifier.os, "scandir", return_value=scanner
        ):
            with self.assertRaisesRegex(verifier.VerificationError, "exceeds 3 entries"):
                list(verifier.source_entries([Path("unused")]))
        self.assertEqual(scanner.index, 4)

    def test_source_scan_skips_rootfs_but_rejects_fixed_hash_source(self):
        with tempfile.TemporaryDirectory() as temporary:
            repo_root = Path(temporary)
            sources = repo_root / "build-rootfs"
            sources.mkdir()
            assets = repo_root / "app/src/main/assets"
            assets.mkdir(parents=True)
            (assets / "alpine-rootfs.squashfs").write_bytes(b"stale, not a squashfs")
            verifier.scan_packaged_sources(repo_root)
            (sources / "fixed-hash").write_bytes(sha512_crypt("not-a-default", "fixedsalt"))
            with self.assertRaisesRegex(verifier.VerificationError, "fixed SHA-512 crypt hash"):
                verifier.scan_packaged_sources(repo_root)

    def test_shadow_accepts_generated_nondefault_hash(self):
        verifier.verify_shadow(shadow_with(sha512_crypt("deterministic-nondefault-test-secret")))

    def test_shadow_rejects_malformed_duplicate_and_unlocked_accounts(self):
        good_hash = sha512_crypt("deterministic-nondefault-test-secret")
        cases = {
            "malformed": b"root:broken\n",
            "duplicate": shadow_with(good_hash) + b"root:" + good_hash + b":20000:0:99999:7:::\n",
            "unlocked non-root": b"root:" + good_hash + b":20000:0:99999:7:::\nuser:" + good_hash + b":20000:0:99999:7:::\n",
        }
        for name, shadow in cases.items():
            with self.subTest(name=name), self.assertRaises(verifier.VerificationError):
                verifier.verify_shadow(shadow)

    def test_shadow_rejects_retired_and_common_default_hashes(self):
        for password in ("podroid", "password"):
            with self.subTest(password_class="retired" if password == "podroid" else "common"):
                with self.assertRaisesRegex(verifier.VerificationError, "retired or common default"):
                    verifier.verify_shadow(shadow_with(sha512_crypt(password, "knownsalt")))

    def test_listing_accepts_bounded_output_and_rejects_bad_semantics(self):
        artifact = MockArtifact()
        verifier.inspect_listing(artifact.listing, 6)
        malformed = artifact.listing + b"\nunparseable"
        with self.assertRaisesRegex(verifier.VerificationError, "unparseable"):
            verifier.inspect_listing(malformed, 6)
        credential = artifact.listing.replace(b"etc/issue", b"root/.ssh/authorized_keys")
        with self.assertRaisesRegex(verifier.VerificationError, "SSH authorized key"):
            verifier.inspect_listing(credential, 6)

    def verify_mock_artifact(self, artifact_output: MockArtifact):
        with tempfile.TemporaryDirectory() as temporary:
            artifact = Path(temporary) / "rootfs.squashfs"
            artifact.write_bytes(b"mock artifact" * 100)
            with mock.patch.object(verifier.shutil, "which", return_value="/mock/tool"), mock.patch.object(
                verifier, "run_bounded", side_effect=artifact_output.run_bounded
            ):
                verifier.verify_artifact(Path(temporary), artifact)

    def test_mock_artifact_accepts_complete_secure_semantics(self):
        self.verify_mock_artifact(MockArtifact())

    def test_mock_artifact_rejects_wrong_dropbear_config(self):
        artifact = MockArtifact()
        artifact.files["etc/conf.d/dropbear"] = b'DROPBEAR_OPTS=""\n'
        with self.assertRaisesRegex(verifier.VerificationError, "password authentication is not disabled"):
            self.verify_mock_artifact(artifact)

    def test_mock_artifact_rejects_missing_required_file(self):
        artifact = MockArtifact()
        del artifact.files["etc/issue"]
        with self.assertRaisesRegex(verifier.VerificationError, "failed while reading etc/issue"):
            self.verify_mock_artifact(artifact)

    def test_missing_artifact_is_rejected_before_tool_execution(self):
        with tempfile.TemporaryDirectory() as temporary:
            missing = Path(temporary) / "missing.squashfs"
            with self.assertRaisesRegex(verifier.VerificationError, "cannot open rootfs artifact"):
                verifier.verify_artifact(Path(temporary), missing)

    def test_artifact_symlink_is_rejected_before_tool_execution(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            target = root / "target"
            target.write_bytes(b"not a squashfs")
            artifact = root / "artifact.squashfs"
            artifact.symlink_to(target)
            with self.assertRaisesRegex(verifier.VerificationError, "without symlink traversal"):
                verifier.verify_artifact(root, artifact)

    def test_subprocess_nonzero_exit_is_reported(self):
        with self.assertRaisesRegex(verifier.VerificationError, "exit 7.*deliberate"):
            verifier.run_bounded(
                ["python3", "-c", "import sys; print('deliberate', file=sys.stderr); sys.exit(7)"],
                timeout_seconds=5,
                output_limit_bytes=1024,
            )

    def test_subprocess_output_is_bounded(self):
        with self.assertRaisesRegex(verifier.VerificationError, "output exceeds"):
            verifier.run_bounded(
                ["python3", "-c", "import sys; sys.stdout.write('x' * 8192)"],
                timeout_seconds=5,
                output_limit_bytes=1024,
            )

    def test_subprocess_timeout_is_enforced(self):
        with self.assertRaisesRegex(verifier.VerificationError, "timed out"):
            verifier.run_bounded(
                ["python3", "-c", "import time; time.sleep(10)"],
                timeout_seconds=0.1,
                output_limit_bytes=1024,
            )

    @unittest.skipUnless(REAL_ROOTFS.is_file(), "full-build rootfs artifact is absent")
    def test_real_full_build_rootfs_artifact(self):
        verifier.verify_artifact(REPO_ROOT, REAL_ROOTFS)


if __name__ == "__main__":
    unittest.main()
