#!/usr/bin/env python3

import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

REPO_ROOT = Path(__file__).resolve().parent.parent
INIT = REPO_ROOT / "init-podroid"


class InitPodroidRecoveryTest(unittest.TestCase):
    def recovery_function(self):
        source = INIT.read_text(encoding="utf-8")
        helper_start = source.index("BLANK_IMAGE_MARKER=")
        start = source.index("recover_persistent_ext4() {", helper_start)
        end = source.index("\n}\n\nif ! mount", start) + 2
        return source[helper_start:end]

    def run_recovery(self, fsck_status, filesystem_type="", blkid_status=2, blkid_output=None):
        with tempfile.TemporaryDirectory() as directory:
            fake_bin = Path(directory) / "bin"
            fake_bin.mkdir()
            marker = Path(directory) / "formatted"
            (fake_bin / "e2fsck").write_text(
                "#!/bin/sh\nexit ${FSCK_STATUS}\n", encoding="utf-8"
            )
            (fake_bin / "blkid").write_text(
                "#!/bin/sh\nprintf '%s\\n' \"${BLKID_OUTPUT}\"\nexit ${BLKID_STATUS}\n",
                encoding="utf-8",
            )
            (fake_bin / "mkfs.ext4").write_text(
                f"#!/bin/sh\ntouch '{marker}'\n", encoding="utf-8"
            )
            (fake_bin / "dd").write_text(
                "#!/bin/sh\n"
                "case \" $* \" in\n"
                "  *\" of=\"*) exit ${DD_WRITE_STATUS:-0} ;;\n"
                "esac\n"
                "printf '%s' \"${BLANK_MARKER}\"\n"
                "exit ${DD_READ_STATUS:-0}\n",
                encoding="utf-8",
            )
            for command in fake_bin.iterdir():
                command.chmod(0o755)
            environment = os.environ.copy()
            environment.update(
                {
                    "PATH": f"{fake_bin}:{environment['PATH']}",
                    "FSCK_STATUS": str(fsck_status),
                    "BLKID_STATUS": str(blkid_status),
                    "BLKID_OUTPUT": filesystem_type if blkid_output is None else blkid_output,
                    "BLANK_MARKER": os.environ.get("BLANK_MARKER", ""),
                    "DD_READ_STATUS": os.environ.get("DD_READ_STATUS", "0"),
                    "DD_WRITE_STATUS": os.environ.get("DD_WRITE_STATUS", "0"),
                }
            )
            result = subprocess.run(
                ["sh", "-c", f"{self.recovery_function()}\nrecover_persistent_ext4\n"],
                env=environment,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
            )
            return result, marker.exists()

    def test_repairable_fsck_statuses_never_format(self):
        for status in (1, 2):
            with self.subTest(status=status):
                result, was_formatted = self.run_recovery(status)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertFalse(was_formatted)

    def test_uncorrected_or_unknown_fsck_statuses_fail_without_formatting(self):
        for status in (4, 16, 32, 128):
            with self.subTest(status=status):
                result, was_formatted = self.run_recovery(status)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(was_formatted)

    def test_first_boot_requires_creation_marker_and_no_ext_signature(self):
        with mock.patch.dict(os.environ, {"BLANK_MARKER": "PODROID-BLANK-IMAGE-V1"}):
            result, was_formatted = self.run_recovery(8)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(was_formatted)

        result, was_formatted = self.run_recovery(8)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(was_formatted)

        with mock.patch.dict(os.environ, {"BLANK_MARKER": "PODROID-BLANK-IMAGE-V1"}):
            result, was_formatted = self.run_recovery(8, "TYPE=ext4", blkid_status=0)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(was_formatted)

    def test_blkid_failure_never_formats(self):
        result, was_formatted = self.run_recovery(8, blkid_status=1)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(was_formatted)

    def test_non_ext_filesystem_and_corrupt_probe_metadata_never_format(self):
        result, was_formatted = self.run_recovery(8, "TYPE=xfs", blkid_status=0)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(was_formatted)

        result, was_formatted = self.run_recovery(8, "garbage", blkid_status=2)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(was_formatted)


if __name__ == "__main__":
    unittest.main()
