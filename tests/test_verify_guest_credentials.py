#!/usr/bin/env python3

import importlib.util
import os
import stat
import tempfile
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("verify_guest_credentials.py")
SPEC = importlib.util.spec_from_file_location("verify_guest_credentials", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
verifier = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verifier)


class GuestCredentialVerifierTest(unittest.TestCase):
    def test_actual_hash_generator_uses_runtime_entropy(self):
        repo_root = Path(__file__).resolve().parent.parent
        verifier.verify_hash_generator(repo_root)

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

    def test_source_scan_does_not_inspect_stale_rootfs_artifact(self):
        with tempfile.TemporaryDirectory() as temporary:
            repo_root = Path(temporary)
            (repo_root / "build-rootfs").mkdir()
            assets = repo_root / "app/src/main/assets"
            assets.mkdir(parents=True)
            (assets / "alpine-rootfs.squashfs").write_bytes(b"stale, not a squashfs")
            verifier.scan_packaged_sources(repo_root)

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


if __name__ == "__main__":
    unittest.main()
