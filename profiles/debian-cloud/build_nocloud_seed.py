#!/usr/bin/env python3
"""Build and inspect Podroid's deterministic, credential-free NoCloud CIDATA seed."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import struct
import tempfile

SECTOR_BYTES = 2048
MAX_SEED_BYTES = 16 * 1024 * 1024
VOLUME_ID = "CIDATA"
READINESS_MARKER = "PODROID_CLOUD_READY_V1"
REQUIRED_FILES = ("meta-data", "user-data", "vendor-data", "network-config")
REVIEW_MANIFEST = "reviewed-files.json"
MAX_SOURCE_BYTES = 64 * 1024
FIXED_RECORDING_TIME = (125, 1, 1, 0, 0, 0, 0)  # 2025-01-01 UTC, ISO year offset from 1900.
FORBIDDEN = (
    (re.compile(r"-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----", re.IGNORECASE), "SSH/private key"),
    (re.compile(r"(?i)\b(?:ssh_)?authorized[_ -]?keys?\b|\b(?:ssh-(?:rsa|dss|ed25519)|ecdsa-sha2-[^ ]+)\s+[A-Za-z0-9+/=]{16,}"), "authorized key"),
    (re.compile(r"(?i)\b(?:passwd|password|chpasswd|plain_text_passwd)\b"), "password field"),
    (re.compile(r"\$(?:1|2[aby]?|5|6|y)\$[A-Za-z0-9./$]+"), "password hash"),
    (re.compile(r"(?i)\b(?:token|access[_-]?token|refresh[_-]?token|api[_-]?key)\b"), "token field"),
    (re.compile(r"(?i)\bseedfrom\s*:|\bds=nocloud[^\s]*;s="), "remote NoCloud seed"),
    (re.compile(r"(?i)https?://"), "remote URL"),
)

class SeedError(ValueError):
    pass


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _read_regular(path: Path, max_bytes: int) -> bytes:
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        fd = os.open(path, flags)
    except OSError as failure:
        raise SeedError(f"cannot open regular source {path}: {failure}") from failure
    try:
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode):
            raise SeedError(f"source is not a regular file: {path}")
        if info.st_size < 1 or info.st_size > max_bytes:
            raise SeedError(f"source is outside the byte bound: {path}")
        chunks: list[bytes] = []
        remaining = max_bytes + 1
        while remaining:
            chunk = os.read(fd, min(remaining, 64 * 1024))
            if not chunk:
                break
            chunks.append(chunk)
            remaining -= len(chunk)
        data = b"".join(chunks)
        if len(data) > max_bytes:
            raise SeedError(f"source exceeds the byte bound: {path}")
        return data
    finally:
        os.close(fd)


def _validate_text(name: str, data: bytes) -> str:
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError as failure:
        raise SeedError(f"{name} is not UTF-8") from failure
    if "\r" in text or not text.endswith("\n") or "\x00" in text:
        raise SeedError(f"{name} must use LF text with one final newline and no NUL")
    for pattern, label in FORBIDDEN:
        if pattern.search(text):
            raise SeedError(f"{name} contains forbidden {label}")
    return text


def _validate_seed_policy(sources: dict[str, bytes]) -> None:
    if set(sources) != set(REQUIRED_FILES):
        raise SeedError("NoCloud policy requires the complete reviewed file set")
    user_data = sources["user-data"].decode()
    vendor_data = sources["vendor-data"].decode()
    if "users: []\n" not in user_data or "ssh_pwauth: false\n" not in user_data:
        raise SeedError("user-data must retain explicit credential-free defaults")
    if vendor_data.count(READINESS_MARKER) != 2:
        raise SeedError("vendor-data must contain the reviewed readiness marker exactly twice")


def load_reviewed_sources(source_dir: Path) -> dict[str, bytes]:
    if source_dir.is_symlink() or not source_dir.is_dir():
        raise SeedError("NoCloud source directory must be a real directory")
    actual = {entry.name for entry in source_dir.iterdir()}
    expected = set(REQUIRED_FILES) | {REVIEW_MANIFEST}
    if actual != expected:
        raise SeedError(f"NoCloud source set is not closed: expected {sorted(expected)}, got {sorted(actual)}")
    manifest_bytes = _read_regular(source_dir / REVIEW_MANIFEST, MAX_SOURCE_BYTES)
    try:
        manifest = json.loads(manifest_bytes)
    except (UnicodeDecodeError, json.JSONDecodeError) as failure:
        raise SeedError("reviewed-files.json is invalid") from failure
    if set(manifest) != {"version", "files", "readiness_marker"} or manifest["version"] != 1:
        raise SeedError("review manifest fields/version are unsupported")
    if manifest["readiness_marker"] != READINESS_MARKER:
        raise SeedError("review manifest readiness marker is unsupported")
    if not isinstance(manifest["files"], dict) or set(manifest["files"]) != set(REQUIRED_FILES):
        raise SeedError("review manifest file set is not closed")

    sources: dict[str, bytes] = {}
    for name in REQUIRED_FILES:
        data = _read_regular(source_dir / name, MAX_SOURCE_BYTES)
        _validate_text(name, data)
        expected_digest = manifest["files"].get(name)
        if not isinstance(expected_digest, str) or not re.fullmatch(r"[0-9a-f]{64}", expected_digest):
            raise SeedError(f"review digest for {name} is malformed")
        if _sha256(data) != expected_digest:
            raise SeedError(f"review digest mismatch for {name}")
        sources[name] = data
    _validate_seed_policy(sources)
    return sources


def _both16(value: int) -> bytes:
    return struct.pack("<H", value) + struct.pack(">H", value)


def _both32(value: int) -> bytes:
    return struct.pack("<I", value) + struct.pack(">I", value)


def _directory_record(extent: int, size: int, identifier: bytes, is_directory: bool) -> bytes:
    if not 1 <= len(identifier) <= 255:
        raise SeedError("ISO identifier length is invalid")
    record = bytearray()
    record.extend(b"\x00\x00")
    record.extend(struct.pack("<I", extent))
    record.extend(struct.pack(">I", extent))
    record.extend(struct.pack("<I", size))
    record.extend(struct.pack(">I", size))
    record.extend(bytes(FIXED_RECORDING_TIME))
    record.extend(bytes((2 if is_directory else 0, 0, 0)))
    record.extend(_both16(1))
    record.append(len(identifier))
    record.extend(identifier)
    if len(identifier) % 2 == 0:
        record.append(0)
    record[0] = len(record)
    return bytes(record)


def build_iso(sources: dict[str, bytes]) -> bytes:
    if tuple(sources) != REQUIRED_FILES:
        raise SeedError("source ordering/set is not canonical")
    path_l_sector = 18
    path_m_sector = 19
    root_sector = 20
    next_sector = 21
    extents: dict[str, int] = {}
    for name in REQUIRED_FILES:
        extents[name] = next_sector
        next_sector += (len(sources[name]) + SECTOR_BYTES - 1) // SECTOR_BYTES
    total_sectors = next_sector
    if total_sectors * SECTOR_BYTES > MAX_SEED_BYTES:
        raise SeedError("CIDATA image exceeds the byte bound")

    root_records = [
        _directory_record(root_sector, SECTOR_BYTES, b"\x00", True),
        _directory_record(root_sector, SECTOR_BYTES, b"\x01", True),
    ]
    root_records.extend(
        _directory_record(extents[name], len(sources[name]), name.encode("ascii"), False)
        for name in REQUIRED_FILES
    )
    root = b"".join(root_records)
    if len(root) > SECTOR_BYTES:
        raise SeedError("CIDATA root directory exceeds one sector")
    root += bytes(SECTOR_BYTES - len(root))

    little_path = bytes((1, 0)) + struct.pack("<I", root_sector) + struct.pack("<H", 1) + b"\x00\x00"
    big_path = bytes((1, 0)) + struct.pack(">I", root_sector) + struct.pack(">H", 1) + b"\x00\x00"
    pvd = bytearray(SECTOR_BYTES)
    pvd[0:7] = b"\x01CD001\x01"
    pvd[8:40] = b"PODROID".ljust(32, b" ")
    pvd[40:72] = VOLUME_ID.encode("ascii").ljust(32, b" ")
    pvd[80:88] = _both32(total_sectors)
    pvd[120:124] = _both16(1)
    pvd[124:128] = _both16(1)
    pvd[128:132] = _both16(SECTOR_BYTES)
    pvd[132:140] = _both32(len(little_path))
    pvd[140:144] = struct.pack("<I", path_l_sector)
    pvd[148:152] = struct.pack(">I", path_m_sector)
    pvd[156:190] = _directory_record(root_sector, SECTOR_BYTES, b"\x00", True)
    timestamp = b"2025010100000000\x00"
    pvd[813:830] = timestamp
    pvd[830:847] = timestamp
    pvd[847:864] = b"0" * 16 + b"\x00"
    pvd[864:881] = timestamp
    pvd[881] = 1
    terminator = bytearray(SECTOR_BYTES)
    terminator[0:7] = b"\xffCD001\x01"

    sectors = [bytes(SECTOR_BYTES) for _ in range(16)] + [bytes(pvd), bytes(terminator)]
    sectors += [little_path.ljust(SECTOR_BYTES, b"\x00"), big_path.ljust(SECTOR_BYTES, b"\x00"), root]
    for name in REQUIRED_FILES:
        data = sources[name]
        sectors.append(data.ljust(((len(data) + SECTOR_BYTES - 1) // SECTOR_BYTES) * SECTOR_BYTES, b"\x00"))
    image = b"".join(sectors)
    if len(image) != total_sectors * SECTOR_BYTES:
        raise SeedError("internal CIDATA layout mismatch")
    return image


def inspect_iso(image: bytes) -> dict[str, bytes]:
    if len(image) < 22 * SECTOR_BYTES or len(image) > MAX_SEED_BYTES or len(image) % SECTOR_BYTES:
        raise SeedError("CIDATA image is outside its canonical byte bounds")
    pvd = image[16 * SECTOR_BYTES:17 * SECTOR_BYTES]
    terminator = image[17 * SECTOR_BYTES:18 * SECTOR_BYTES]
    if pvd[:7] != b"\x01CD001\x01" or terminator[:7] != b"\xffCD001\x01":
        raise SeedError("CIDATA volume descriptors are invalid")
    if pvd[40:72].decode("ascii").rstrip(" ") != VOLUME_ID:
        raise SeedError("ISO volume label is not CIDATA")
    sectors_le, sectors_be = struct.unpack("<I", pvd[80:84])[0], struct.unpack(">I", pvd[84:88])[0]
    if sectors_le != sectors_be or sectors_le * SECTOR_BYTES != len(image):
        raise SeedError("CIDATA declared size is not canonical")
    root_record = pvd[156:190]
    root_extent = struct.unpack("<I", root_record[2:6])[0]
    root_size = struct.unpack("<I", root_record[10:14])[0]
    if root_size != SECTOR_BYTES or root_extent * SECTOR_BYTES + root_size > len(image):
        raise SeedError("CIDATA root directory is invalid")
    directory = image[root_extent * SECTOR_BYTES:root_extent * SECTOR_BYTES + root_size]
    files: dict[str, bytes] = {}
    offset = 0
    while offset < len(directory):
        length = directory[offset]
        if length == 0:
            if any(directory[offset:]):
                raise SeedError("CIDATA directory padding is nonzero")
            break
        if length < 34 or offset + length > len(directory):
            raise SeedError("CIDATA directory record is malformed")
        record = directory[offset:offset + length]
        name_length = record[32]
        if 33 + name_length > len(record):
            raise SeedError("CIDATA filename record is malformed")
        identifier = record[33:33 + name_length]
        if identifier not in (b"\x00", b"\x01"):
            try:
                name = identifier.decode("ascii")
            except UnicodeDecodeError as failure:
                raise SeedError("CIDATA filename is not ASCII") from failure
            if name not in REQUIRED_FILES or name in files or record[25] & 2:
                raise SeedError("CIDATA contains an unknown, duplicate, or directory entry")
            extent = struct.unpack("<I", record[2:6])[0]
            size = struct.unpack("<I", record[10:14])[0]
            start = extent * SECTOR_BYTES
            if size < 1 or start + size > len(image):
                raise SeedError("CIDATA file extent is invalid")
            files[name] = image[start:start + size]
        offset += length
    if set(files) != set(REQUIRED_FILES):
        raise SeedError("CIDATA reviewed file set is incomplete")
    ordered = {name: files[name] for name in REQUIRED_FILES}
    for name, data in ordered.items():
        _validate_text(name, data)
    _validate_seed_policy(ordered)
    if build_iso(ordered) != image:
        raise SeedError("CIDATA image is not the canonical deterministic encoding")
    return ordered


def _atomic_write(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as output:
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    except BaseException:
        try:
            os.unlink(temporary)
        except FileNotFoundError:
            pass
        raise


def command_build(args: argparse.Namespace) -> None:
    sources = load_reviewed_sources(args.source)
    image = build_iso(sources)
    inspect_iso(image)
    _atomic_write(args.output, image)
    print(json.dumps({"path": str(args.output), "sha256": _sha256(image), "size_bytes": len(image)}, sort_keys=True))


def command_inspect(args: argparse.Namespace) -> None:
    image = _read_regular(args.image, MAX_SEED_BYTES)
    files = inspect_iso(image)
    if args.source is not None and files != load_reviewed_sources(args.source):
        raise SeedError("CIDATA contents differ from the reviewed source directory")
    print(json.dumps({"path": str(args.image), "sha256": _sha256(image), "size_bytes": len(image)}, sort_keys=True))


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    sub = result.add_subparsers(dest="command", required=True)
    build = sub.add_parser("build")
    build.add_argument("--source", type=Path, default=Path(__file__).with_name("nocloud"))
    build.add_argument("--output", type=Path, required=True)
    build.set_defaults(run=command_build)
    inspect = sub.add_parser("inspect")
    inspect.add_argument("--image", type=Path, required=True)
    inspect.add_argument("--source", type=Path)
    inspect.set_defaults(run=command_inspect)
    return result


def main() -> int:
    args = parser().parse_args()
    try:
        args.run(args)
    except (OSError, SeedError) as failure:
        print(f"error: {failure}", file=os.sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
