#!/usr/bin/env python3
"""Fail-closed validation and provenance for cached QEMU build artifacts."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import stat
import struct
from pathlib import Path

EXPECTED_NATIVE = (
    "libqemu-system-aarch64.so",
    "libslirp.so",
    "libpodroid-bridge.so",
    "libpodroid-launcher.so",
)
EXPECTED_KEYMAPS = (
    "ar", "bepo", "cz", "da", "de", "de-ch", "en-gb", "en-us", "es", "et",
    "fi", "fo", "fr", "fr-be", "fr-ca", "fr-ch", "hr", "hu", "is", "it",
    "ja", "lt", "lv", "mk", "nl", "no", "pl", "pt", "pt-br", "ru", "sl",
    "sv", "th", "tr",
)
MANIFEST_NAME = ".podroid-qemu-provenance.json"
MANIFEST_SCHEMA = 1
PT_LOAD = 1
EXPECTED_MACHINE = 183  # EM_AARCH64


class CacheError(RuntimeError):
    pass


def _regular(path: Path, description: str) -> None:
    try:
        info = path.lstat()
    except OSError as error:
        raise CacheError(f"missing {description}: {path}") from error
    if not stat.S_ISREG(info.st_mode):
        raise CacheError(f"{description} is not a regular file: {path}")
    if info.st_size <= 0:
        raise CacheError(f"{description} is empty: {path}")


def _directory(path: Path, description: str) -> None:
    try:
        info = path.lstat()
    except OSError as error:
        raise CacheError(f"missing {description}: {path}") from error
    if not stat.S_ISDIR(info.st_mode):
        raise CacheError(f"{description} is not a directory: {path}")


def _exact_children(directory: Path, expected: set[str], description: str) -> None:
    try:
        actual = {entry.name for entry in directory.iterdir()}
    except OSError as error:
        raise CacheError(f"cannot inspect {description}: {directory}") from error
    if actual != expected:
        raise CacheError(
            f"{description} artifact set mismatch: expected {sorted(expected)}, got {sorted(actual)}"
        )


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _verify_elf_alignment(path: Path) -> None:
    file_size = path.stat().st_size
    with path.open("rb") as stream:
        header = stream.read(64)
        if len(header) < 64 or header[:4] != b"\x7fELF" or header[4:6] != b"\x02\x01":
            raise CacheError(f"native artifact is not a little-endian ELF64 file: {path}")
        machine = struct.unpack_from("<H", header, 18)[0]
        if machine != EXPECTED_MACHINE:
            raise CacheError(f"native artifact is not AArch64 (e_machine={machine}): {path}")
        phoff = struct.unpack_from("<Q", header, 32)[0]
        phentsize, phnum = struct.unpack_from("<HH", header, 54)
        if phentsize < 56 or phnum == 0 or phnum > 256 or phoff + phentsize * phnum > file_size:
            raise CacheError(f"native artifact has an invalid program header table: {path}")
        load_count = 0
        for index in range(phnum):
            stream.seek(phoff + index * phentsize)
            program_header = stream.read(phentsize)
            if len(program_header) < 56:
                raise CacheError(f"native artifact has a truncated program header: {path}")
            p_type = struct.unpack_from("<I", program_header, 0)[0]
            if p_type != PT_LOAD:
                continue
            p_align = struct.unpack_from("<Q", program_header, 48)[0]
            if p_align < 16_384 or p_align % 16_384 != 0 or p_align & (p_align - 1):
                raise CacheError(f"native artifact is not 16KiB aligned: {path}")
            load_count += 1
        if load_count == 0:
            raise CacheError(f"native artifact has no PT_LOAD segments: {path}")


def _verify_qemu_version(binary: Path, qemu_version: str) -> None:
    # QEMU embeds this release string in its version implementation.  Checking
    # the binary, rather than trusting only the sidecar, prevents a cache made
    # for another QEMU release from being accepted after the pin changes.
    marker = f"QEMU emulator version {qemu_version}".encode("ascii")
    overlap = len(marker) - 1
    with binary.open("rb") as stream:
        previous = b""
        while True:
            chunk = stream.read(1024 * 1024)
            if not chunk:
                break
            if marker in previous + chunk:
                return
            previous = chunk[-overlap:] if overlap else b""
    raise CacheError(f"QEMU binary does not prove pinned version {qemu_version}")


def _load_manifest(path: Path) -> dict[str, object]:
    _regular(path, "QEMU provenance metadata")
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise CacheError(f"corrupt QEMU provenance metadata: {path}") from error
    if not isinstance(value, dict):
        raise CacheError("QEMU provenance metadata must be an object")
    return value


def validate_cache(jni_directory: Path, qemu_directory: Path, qemu_version: str) -> None:
    jni_directory = jni_directory.resolve()
    qemu_directory = qemu_directory.resolve()
    _directory(jni_directory, "QEMU native library directory")
    _directory(qemu_directory, "QEMU data directory")
    _exact_children(jni_directory, set(EXPECTED_NATIVE), "QEMU native")
    _exact_children(qemu_directory, {"efi-virtio.rom", "keymaps", MANIFEST_NAME}, "QEMU data")

    for name in EXPECTED_NATIVE:
        path = jni_directory / name
        _regular(path, "QEMU native artifact")
        _verify_elf_alignment(path)
    _regular(qemu_directory / "efi-virtio.rom", "QEMU firmware artifact")
    keymaps = qemu_directory / "keymaps"
    _directory(keymaps, "QEMU keymap directory")
    _exact_children(keymaps, set(EXPECTED_KEYMAPS), "QEMU keymap")
    for name in EXPECTED_KEYMAPS:
        _regular(keymaps / name, "QEMU keymap")

    _verify_qemu_version(jni_directory / "libqemu-system-aarch64.so", qemu_version)
    manifest = _load_manifest(qemu_directory / MANIFEST_NAME)
    expected_artifacts = {
        name: {"sha256": _sha256(jni_directory / name), "size": (jni_directory / name).stat().st_size}
        for name in EXPECTED_NATIVE
    }
    expected_artifacts["efi-virtio.rom"] = {
        "sha256": _sha256(qemu_directory / "efi-virtio.rom"),
        "size": (qemu_directory / "efi-virtio.rom").stat().st_size,
    }
    expected = {
        "schemaVersion": MANIFEST_SCHEMA,
        "qemuVersion": qemu_version,
        "provenance": {"builder": "Dockerfile", "target": "final", "buildArg": "QEMU_VERSION"},
        "nativeArtifacts": expected_artifacts,
        "keymaps": list(EXPECTED_KEYMAPS),
        "keymapArtifacts": {
            name: {"sha256": _sha256(keymaps / name), "size": (keymaps / name).stat().st_size}
            for name in EXPECTED_KEYMAPS
        },
    }
    if manifest != expected:
        raise CacheError("QEMU provenance metadata does not match the current artifact set or pin")


def write_manifest(jni_directory: Path, qemu_directory: Path, qemu_version: str) -> None:
    # Validate every property except the sidecar before publishing the new
    # sidecar.  This keeps a failed extraction from becoming a reusable cache.
    qemu_directory = qemu_directory.resolve()
    _directory(jni_directory.resolve(), "QEMU native library directory")
    _directory(qemu_directory, "QEMU data directory")
    _exact_children(jni_directory.resolve(), set(EXPECTED_NATIVE), "QEMU native")
    _exact_children(qemu_directory, {"efi-virtio.rom", "keymaps"}, "QEMU data")
    for name in EXPECTED_NATIVE:
        path = jni_directory / name
        _regular(path, "QEMU native artifact")
        _verify_elf_alignment(path)
    _regular(qemu_directory / "efi-virtio.rom", "QEMU firmware artifact")
    _verify_qemu_version(jni_directory / "libqemu-system-aarch64.so", qemu_version)
    keymaps = qemu_directory / "keymaps"
    _directory(keymaps, "QEMU keymap directory")
    _exact_children(keymaps, set(EXPECTED_KEYMAPS), "QEMU keymap")
    for name in EXPECTED_KEYMAPS:
        _regular(keymaps / name, "QEMU keymap")

    artifacts = {
        name: {"sha256": _sha256(jni_directory / name), "size": (jni_directory / name).stat().st_size}
        for name in EXPECTED_NATIVE
    }
    artifacts["efi-virtio.rom"] = {
        "sha256": _sha256(qemu_directory / "efi-virtio.rom"),
        "size": (qemu_directory / "efi-virtio.rom").stat().st_size,
    }
    manifest = {
        "schemaVersion": MANIFEST_SCHEMA,
        "qemuVersion": qemu_version,
        "provenance": {"builder": "Dockerfile", "target": "final", "buildArg": "QEMU_VERSION"},
        "nativeArtifacts": artifacts,
        "keymaps": list(EXPECTED_KEYMAPS),
        "keymapArtifacts": {
            name: {"sha256": _sha256(keymaps / name), "size": (keymaps / name).stat().st_size}
            for name in EXPECTED_KEYMAPS
        },
    }
    temporary = qemu_directory / f"{MANIFEST_NAME}.tmp"
    temporary.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    os.replace(temporary, qemu_directory / MANIFEST_NAME)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--jni-directory", type=Path, required=True)
    parser.add_argument("--qemu-directory", type=Path, required=True)
    parser.add_argument("--qemu-version", required=True)
    parser.add_argument("--write-manifest", action="store_true")
    args = parser.parse_args()
    try:
        if args.write_manifest:
            write_manifest(args.jni_directory, args.qemu_directory, args.qemu_version)
        else:
            validate_cache(args.jni_directory, args.qemu_directory, args.qemu_version)
    except (CacheError, OSError, UnicodeError) as error:
        print(f"QEMU cache verification failed: {error}")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
