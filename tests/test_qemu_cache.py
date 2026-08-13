#!/usr/bin/env python3

import importlib.util
import struct
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
MODULE_PATH = REPO_ROOT / "build-tools/verify_qemu_cache.py"
SPEC = importlib.util.spec_from_file_location("verify_qemu_cache", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
verifier = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verifier)


class QemuCacheVerifierTest(unittest.TestCase):
    version = "11.0.0"

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        root = Path(self.directory.name)
        self.jni = root / "jni"
        self.qemu = root / "qemu"
        self.jni.mkdir()
        (self.qemu / "keymaps").mkdir(parents=True)
        for name in verifier.EXPECTED_NATIVE:
            marker = (
                f"QEMU emulator version {self.version}".encode()
                if name == "libqemu-system-aarch64.so" else b"native"
            )
            self._write_elf(self.jni / name, marker)
        (self.qemu / "efi-virtio.rom").write_bytes(b"firmware")
        for name in verifier.EXPECTED_KEYMAPS:
            (self.qemu / "keymaps" / name).write_bytes(b"keymap")
        verifier.write_manifest(self.jni, self.qemu, self.version)

    def tearDown(self):
        self.directory.cleanup()

    @staticmethod
    def _write_elf(path, suffix, alignment=16_384, machine=183):
        data = bytearray(256)
        data[:16] = b"\x7fELF\x02\x01\x01" + b"\0" * 9
        struct.pack_into("<HHIQQQIHHHHHH", data, 16, 3, machine, 1, 0, 64, 0, 0, 64, 56, 1, 0, 0, 0)
        struct.pack_into("<IIQQQQQQ", data, 64, 1, 0, 0, 0, 0, len(data), len(data), alignment)
        path.write_bytes(data + suffix)

    def test_complete_cache_passes(self):
        verifier.validate_cache(self.jni, self.qemu, self.version)

    def test_corrupt_metadata_is_rejected(self):
        (self.qemu / verifier.MANIFEST_NAME).write_text("{broken", encoding="utf-8")
        with self.assertRaises(verifier.CacheError):
            verifier.validate_cache(self.jni, self.qemu, self.version)

    def test_stale_qemu_version_is_rejected(self):
        binary = self.jni / "libqemu-system-aarch64.so"
        binary.write_bytes(binary.read_bytes().replace(self.version.encode(), b"10.2.0"))
        with self.assertRaises(verifier.CacheError):
            verifier.validate_cache(self.jni, self.qemu, self.version)

    def test_wrong_elf_machine_is_rejected(self):
        binary = self.jni / "libslirp.so"
        self._write_elf(binary, b"native", machine=62)
        with self.assertRaisesRegex(verifier.CacheError, "AArch64"):
            verifier.validate_cache(self.jni, self.qemu, self.version)

    def test_misaligned_native_artifact_is_rejected(self):
        binary = self.jni / "libslirp.so"
        data = bytearray(binary.read_bytes())
        struct.pack_into("<Q", data, 64 + 48, 4096)
        binary.write_bytes(data)
        with self.assertRaises(verifier.CacheError):
            verifier.validate_cache(self.jni, self.qemu, self.version)

    def test_incomplete_keymap_set_is_rejected(self):
        (self.qemu / "keymaps" / verifier.EXPECTED_KEYMAPS[0]).unlink()
        with self.assertRaises(verifier.CacheError):
            verifier.validate_cache(self.jni, self.qemu, self.version)

    def test_wrong_file_type_is_rejected(self):
        target = self.jni / "libslirp.so"
        target.unlink()
        target.symlink_to(self.jni / "libqemu-system-aarch64.so")
        with self.assertRaises(verifier.CacheError):
            verifier.validate_cache(self.jni, self.qemu, self.version)


if __name__ == "__main__":
    unittest.main()
