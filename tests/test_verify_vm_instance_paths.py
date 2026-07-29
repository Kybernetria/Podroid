#!/usr/bin/env python3

import importlib.util
import tempfile
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("verify_vm_instance_paths.py")
SPEC = importlib.util.spec_from_file_location("verify_vm_instance_paths", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
verifier = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verifier)
REPO_ROOT = Path(__file__).resolve().parent.parent


class VmInstancePathVerifierTest(unittest.TestCase):
    def test_actual_active_sources_use_instance_paths(self):
        verifier.verify_repository(REPO_ROOT)

    def test_active_operational_paths_at_files_root_are_rejected(self):
        bad_lines = (
            'adb shell run-as "$pkg" cat files/console.log',
            'rm -f files/storage.img',
            'socket=files/qmp.sock',
            'kernel=files/vmlinuz-virt',
        )
        for bad_line in bad_lines:
            with self.subTest(bad_line=bad_line), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                (root / "operator.sh").write_text(bad_line + "\n", encoding="utf-8")
                with self.assertRaisesRegex(verifier.VerificationError, "root-level VM paths"):
                    verifier.verify_repository(root)

    def test_kotlin_files_directory_constructions_are_rejected(self):
        bad_expressions = (
            'filesDir.resolve("storage.img")',
            'filesDirectory.toPath().resolve("console.log")',
            'File(context.filesDir, "terminal.sock")',
            'File(context.filesDir.absolutePath, "host.sock")',
            'filesDir.absoluteFile.resolve("ctrl.sock")',
            'Paths.get(filesDir.absolutePath, "qmp.sock")',
            'filesDir.path + "/initrd.img"',
            '"${filesDir}/alpine-rootfs.squashfs"',
        )
        for expression in bad_expressions:
            with self.subTest(expression=expression), tempfile.TemporaryDirectory() as directory:
                source = Path(directory) / "app/src/main/java/example/Bad.kt"
                source.parent.mkdir(parents=True)
                source.write_text(f"val bad = {expression}\n", encoding="utf-8")
                with self.assertRaisesRegex(verifier.VerificationError, "root-level VM paths"):
                    verifier.verify_repository(Path(directory))

    def test_kotlin_sources_under_main_kotlin_are_checked(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "app/src/main/kotlin/example/Bad.kt"
            source.parent.mkdir(parents=True)
            source.write_text('val bad = filesDir.resolve("storage.img")\n', encoding="utf-8")
            with self.assertRaisesRegex(verifier.VerificationError, "root-level VM paths"):
                verifier.verify_repository(Path(directory))

    def test_default_instance_paths_are_accepted(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "operator.sh").write_text(
                "cat files/instances/default/console.log\n"
                "rm -f files/instances/default/storage.img\n",
                encoding="utf-8",
            )
            source = root / "app/src/main/java/example/Good.kt"
            source.parent.mkdir(parents=True)
            source.write_text(
                'val log = filesDir.resolve("instances").resolve("default").resolve("console.log")\n',
                encoding="utf-8",
            )
            verifier.verify_repository(root)

    def test_historical_baseline_docs_do_not_false_flag(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            historical = root / "docs/baseline/INVENTORY.md"
            historical.parent.mkdir(parents=True)
            historical.write_text(
                "Historical baseline: filesDir/qmp.sock and files/storage.img\n",
                encoding="utf-8",
            )
            verifier.verify_repository(root)

    def test_active_operator_docs_are_checked(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "CONTRIBUTING.md").write_text(
                "Run `adb shell run-as example cat files/console.log`.\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(verifier.VerificationError, "CONTRIBUTING.md"):
                verifier.verify_repository(root)


if __name__ == "__main__":
    unittest.main()
