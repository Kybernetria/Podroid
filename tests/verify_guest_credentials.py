#!/usr/bin/env python3
"""Fail-closed source and optional SquashFS guest credential verification."""

from __future__ import annotations

import argparse
import os
import re
import selectors
import shutil
import signal
import stat
import struct
import subprocess
import sys
import tempfile
import time
import zipfile
from pathlib import Path
from typing import Callable

MAX_SOURCE_ENTRIES = 2_000
MAX_SOURCE_FILE_BYTES = 16 * 1024 * 1024
MAX_ARTIFACT_BYTES = 1024 * 1024 * 1024
MAX_APK_BYTES = MAX_ARTIFACT_BYTES + 512 * 1024 * 1024
MAX_APK_ENTRIES = 20_000
MAX_APK_CENTRAL_DIRECTORY_BYTES = 16 * 1024 * 1024
MAX_ARTIFACT_INODES = 100_000
MAX_ARTIFACT_EXPANDED_BYTES = 8 * 1024 * 1024 * 1024
MAX_METADATA_OUTPUT_BYTES = 64 * 1024
MAX_LISTING_OUTPUT_BYTES = 64 * 1024 * 1024
MAX_CAT_OUTPUT_BYTES = 1024 * 1024
TOOL_TIMEOUT_SECONDS = 60.0
CAT_TIMEOUT_SECONDS = 20.0
APK_STREAM_TIMEOUT_SECONDS = 120.0
APK_ROOTFS_ENTRY = "assets/alpine-rootfs.squashfs"
APK_ROOTFS_BASENAME = "alpine-rootfs.squashfs"
GENERATED_GUEST_ARTIFACTS = frozenset({
    "app/src/main/assets/alpine-rootfs.squashfs",
    "app/src/main/assets/vmlinuz-virt",
    "app/src/main/assets/initrd.img",
})
ZIP_EOCD_SIGNATURE = b"PK\x05\x06"
ZIP_EOCD_SIZE = 22
ZIP_MAX_COMMENT_BYTES = 65_535

SHA512_CRYPT = re.compile(rb"^\$6\$[./0-9A-Za-z]{1,16}\$[./0-9A-Za-z]{86}$")
SHA512_CRYPT_FRAGMENT = re.compile(rb"\$6\$[./0-9A-Za-z]{1,16}\$[./0-9A-Za-z]{86}")
# This bounded denylist exists only to reject retired or common defaults in built
# artifacts. It is verification policy, not current login guidance.
REJECTED_ROOT_PASSWORDS = (
    "podroid",  # Retired product-name-derived credential.
    "root",
    "password",
    "admin",
    "alpine",
    "changeme",
    "123456",
    "toor",
)
MAX_REJECTED_ROOT_PASSWORDS = 8
KEY_MATERIAL = re.compile(
    rb"-----BEGIN (?:OPENSSH |RSA |EC |DSA )?PRIVATE KEY-----|"
    rb"^(?:ssh-(?:rsa|dss|ed25519)|ecdsa-sha2-nistp[0-9]+)[ \t]+[A-Za-z0-9+/=]{40,}",
    re.MULTILINE,
)
CREDENTIAL_NAMES = re.compile(
    rb"^(?:authorized_keys2?|id_(?:rsa|dsa|ecdsa|ed25519)(?:\.pub)?|dropbear_[^/]*_host_key)$"
)
LISTING_LINE = re.compile(
    rb"^(?P<mode>[bcdlps-][rwxStTs-]{9})\s+\d+/\d+\s+(?P<size>\d+)\s+"
    rb"\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}\s+(?P<path>.+)$"
)


class VerificationError(RuntimeError):
    pass


def fail(message: str) -> None:
    raise VerificationError(message)


def read_regular_file(path: Path, limit: int = MAX_SOURCE_FILE_BYTES) -> bytes:
    try:
        descriptor = os.open(path, os.O_RDONLY | os.O_CLOEXEC | os.O_NOFOLLOW)
    except OSError as exc:
        fail(f"cannot open regular file without symlink traversal {path}: {exc}")
    try:
        metadata = os.fstat(descriptor)
        if not stat.S_ISREG(metadata.st_mode):
            fail(f"expected a regular file without symlink traversal: {path}")
        if metadata.st_size > limit:
            fail(f"file exceeds {limit} byte verification bound: {path}")
        chunks = bytearray()
        while len(chunks) <= limit:
            chunk = os.read(descriptor, min(65_536, limit + 1 - len(chunks)))
            if not chunk:
                break
            chunks.extend(chunk)
        if len(chunks) != metadata.st_size:
            fail(f"file changed or exceeded its bound while being verified: {path}")
        return bytes(chunks)
    except OSError as exc:
        fail(f"cannot read {path}: {exc}")
    finally:
        os.close(descriptor)


def source_entries(roots: list[Path]):
    pending = list(reversed(roots))
    count = 0
    while pending:
        directory = pending.pop()
        entries = []
        try:
            with os.scandir(directory) as iterator:
                for entry in iterator:
                    count += 1
                    if count > MAX_SOURCE_ENTRIES:
                        fail(f"credential source scan exceeds {MAX_SOURCE_ENTRIES} entries")
                    entries.append(entry)
        except OSError as exc:
            fail(f"cannot scan source directory {directory}: {exc}")
        # The collection is bounded before it is sorted or otherwise materialized.
        entries.sort(key=lambda item: os.fsencode(item.name))
        for entry in entries:
            try:
                metadata = entry.stat(follow_symlinks=False)
            except OSError as exc:
                fail(f"cannot inspect source entry {entry.path!r}: {exc}")
            path = Path(entry.path)
            if stat.S_ISDIR(metadata.st_mode):
                pending.append(path)
            else:
                yield path, metadata


def scan_packaged_sources(repo_root: Path) -> None:
    roots = [repo_root / "build-rootfs", repo_root / "app/src/main/assets"]
    for root in roots:
        if not root.is_dir():
            fail(f"missing credential source directory: {root.relative_to(repo_root)}")

    for path, metadata in source_entries(roots):
        name = os.fsencode(path.name)
        if CREDENTIAL_NAMES.fullmatch(name):
            fail(f"bundled SSH credential file: {path!r}")
        relative_path = path.relative_to(repo_root).as_posix()
        if relative_path in GENERATED_GUEST_ARTIFACTS:
            # Generated binary guest artifacts are not source text. The rootfs
            # receives explicit semantic inspection; kernel/initramfs inputs
            # are reproducibly built from the source trees scanned above.
            continue
        if not stat.S_ISREG(metadata.st_mode):
            continue
        if metadata.st_size > MAX_SOURCE_FILE_BYTES:
            fail(f"credential source file exceeds 16 MiB: {path!r}")
        data = read_regular_file(path)
        if KEY_MATERIAL.search(data):
            fail(f"bundled SSH key material in {path!r}")
        if SHA512_CRYPT_FRAGMENT.search(data):
            fail(f"fixed SHA-512 crypt hash in packageable source {path!r}")


def run_bounded(
    command: list[str],
    *,
    timeout_seconds: float,
    output_limit_bytes: int,
    pass_fds: tuple[int, ...] = (),
) -> tuple[bytes, bytes]:
    try:
        process = subprocess.Popen(
            command,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            start_new_session=True,
            pass_fds=pass_fds,
            env={**os.environ, "LC_ALL": "C"},
        )
    except OSError as exc:
        fail(f"failed to start {command[0]}: {exc}")

    assert process.stdout is not None and process.stderr is not None
    selector = selectors.DefaultSelector()
    streams = {process.stdout: bytearray(), process.stderr: bytearray()}
    for stream in streams:
        os.set_blocking(stream.fileno(), False)
        selector.register(stream, selectors.EVENT_READ)

    deadline = time.monotonic() + timeout_seconds
    total = 0
    try:
        while selector.get_map():
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                fail(f"command timed out after {timeout_seconds:g}s: {command[0]}")
            events = selector.select(min(remaining, 0.25))
            if not events and process.poll() is not None:
                events = [(key, selectors.EVENT_READ) for key in selector.get_map().values()]
            for key, _ in events:
                try:
                    chunk = os.read(key.fd, 65_536)
                except BlockingIOError:
                    continue
                if not chunk:
                    selector.unregister(key.fileobj)
                    continue
                total += len(chunk)
                if total > output_limit_bytes:
                    fail(f"command output exceeds {output_limit_bytes} byte bound: {command[0]}")
                streams[key.fileobj].extend(chunk)
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            fail(f"command timed out after {timeout_seconds:g}s: {command[0]}")
        try:
            return_code = process.wait(timeout=remaining)
        except subprocess.TimeoutExpired:
            fail(f"command timed out after {timeout_seconds:g}s: {command[0]}")
    except BaseException:
        if process.poll() is None:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
        process.wait()
        raise
    finally:
        selector.close()
        process.stdout.close()
        process.stderr.close()

    stdout = bytes(streams[process.stdout])
    stderr = bytes(streams[process.stderr])
    if return_code != 0:
        detail = stderr.decode("utf-8", "replace").strip()[:500]
        fail(f"command failed with exit {return_code}: {command[0]}{': ' + detail if detail else ''}")
    return stdout, stderr


def verify_hash_generator(repo_root: Path) -> None:
    generator = repo_root / "build-rootfs/generate-root-password-hash.sh"
    metadata = generator.lstat() if generator.exists() else None
    if metadata is None or not stat.S_ISREG(metadata.st_mode) or not metadata.st_mode & stat.S_IXUSR:
        fail("root hash generator must be a regular executable file")

    hashes = []
    for _ in range(2):
        stdout, stderr = run_bounded(
            [str(generator)], timeout_seconds=10.0, output_limit_bytes=4_096
        )
        if stderr:
            fail("root hash generator wrote unexpected stderr output")
        value = stdout.rstrip(b"\n")
        if b"\n" in value or not SHA512_CRYPT.fullmatch(value):
            fail("root hash generator did not emit one valid SHA-512 crypt hash")
        hashes.append(value)
    if hashes[0] == hashes[1]:
        fail("root hash generator produced duplicate hashes across two executions")


def active_config_lines(data: bytes) -> list[bytes]:
    return [line.strip() for line in data.splitlines() if line.strip() and not line.lstrip().startswith(b"#")]


def require_contains(data: bytes, value: bytes, message: str) -> None:
    if value not in data:
        fail(message)


def verify_shadow_build_flow(build_script: bytes) -> None:
    generator_command = b"ROOT_HASH=$(/work/generate-root-password-hash.sh)"
    shadow_command = b'sed -i "s|^root:[^:]*:|root:${ROOT_HASH}:|" "$ROOTFS/etc/shadow"'
    commands = active_config_lines(build_script)
    if commands.count(generator_command) != 1:
        fail("rootfs build must execute the root hash generator exactly once")
    if commands.count(shadow_command) != 1:
        fail("rootfs build must contain exactly one authoritative root shadow mutation")
    root_hash_commands = [line for line in commands if b"ROOT_HASH" in line]
    if root_hash_commands != [generator_command, shadow_command]:
        fail("rootfs build has a non-authoritative root hash flow")
    shadow_commands = [line for line in commands if b"$ROOTFS/etc/shadow" in line]
    if shadow_commands != [shadow_command]:
        fail("rootfs build has an additional shadow mutation")


def verify_source(repo_root: Path) -> None:
    expected_files = [
        "README.md",
        "CLAUDE.md",
        "app/src/main/res/values/strings.xml",
        "app/src/main/res/values-zh/strings.xml",
        "docs/baseline/README.md",
        "docs/baseline/INVENTORY.md",
        "docs/guide/getting-started.html",
        "docs/guide/networking.html",
        "docs/guide/settings.html",
        "docs/guide/packages.html",
        "docs/guide/use-cases.html",
        "build-rootfs/Dockerfile.rootfs",
        "build-rootfs/build-rootfs.sh",
        "build-rootfs/files/etc/conf.d/dropbear",
        "build-rootfs/files/usr/local/bin/podroid-login",
    ]
    source = {name: read_regular_file(repo_root / name) for name in expected_files}

    if active_config_lines(source["build-rootfs/files/etc/conf.d/dropbear"]) != [b'DROPBEAR_OPTS="-s"']:
        fail("Dropbear must be configured with password authentication disabled")
    verify_shadow_build_flow(source["build-rootfs/build-rootfs.sh"])
    if b"--allow-untrusted" in source["build-rootfs/build-rootfs.sh"]:
        fail("rootfs package installation bypasses Alpine signature verification")
    require_contains(
        source["build-rootfs/build-rootfs.sh"],
        b'--keys-dir "$ROOTFS/etc/apk/keys"',
        "rootfs package installation does not explicitly use Alpine signing keys",
    )
    require_contains(
        source["build-rootfs/Dockerfile.rootfs"],
        b"COPY generate-root-password-hash.sh /work/generate-root-password-hash.sh",
        "rootfs image does not copy the tested hash generator",
    )
    require_contains(
        source["build-rootfs/Dockerfile.rootfs"],
        b"cp /etc/apk/keys/*.pub /work/rootfs/etc/apk/keys/",
        "rootfs image does not explicitly seed Alpine package signing keys",
    )
    require_contains(
        source["build-rootfs/files/usr/local/bin/podroid-login"],
        b"exec /bin/login -f root",
        "app-owned guest console does not auto-login root",
    )
    require_contains(
        source["docs/baseline/README.md"] + source["docs/baseline/INVENTORY.md"],
        b"[REDACTED RETIRED CREDENTIAL]",
        "historical release-blocker record is not redacted",
    )
    use_cases = source["docs/guide/use-cases.html"]
    require_contains(use_cases, b"/root/.ssh/authorized_keys", "use-cases guide omits public-key provisioning")
    require_contains(use_cases.lower(), b"before connecting", "use-cases guide does not require provisioning before SSH")

    verify_hash_generator(repo_root)
    scan_packaged_sources(repo_root)


def unsquashfs_command(tool: str, operation: list[str], artifact: Path) -> list[str]:
    return [tool, "-processors", "1", "-strict-errors", "-no-progress", *operation, str(artifact)]


def artifact_cat(tool: str, artifact: Path, artifact_fd: int, guest_path: str) -> bytes:
    stdout, stderr = run_bounded(
        unsquashfs_command(tool, ["-cat"], artifact) + [guest_path],
        timeout_seconds=CAT_TIMEOUT_SECONDS,
        output_limit_bytes=MAX_CAT_OUTPUT_BYTES,
        pass_fds=(artifact_fd,),
    )
    if stderr:
        fail(f"unsquashfs reported unexpected diagnostics while reading {guest_path}")
    return stdout


def inspect_listing(listing: bytes, expected_inodes: int) -> None:
    lines = listing.splitlines()
    # Hard links can produce more directory entries than unique SquashFS inodes.
    # Keep a separate path bound and require at least the advertised inode count.
    if len(lines) < expected_inodes or len(lines) > 200_000:
        fail(f"artifact listing count {len(lines)} is inconsistent with {expected_inodes} inodes")
    expanded_bytes = 0
    for line in lines:
        match = LISTING_LINE.fullmatch(line)
        if not match:
            fail("artifact listing contains an unparseable filename or metadata line")
        expanded_bytes += int(match.group("size"))
        if expanded_bytes > MAX_ARTIFACT_EXPANDED_BYTES:
            fail("artifact expanded metadata exceeds 8 GiB bound")
        path = match.group("path")
        if match.group("mode").startswith(b"l"):
            path = path.split(b" -> ", 1)[0]
        basename = path.rsplit(b"/", 1)[-1]
        if CREDENTIAL_NAMES.fullmatch(basename):
            fail("artifact bundles an SSH authorized key, identity, or host private key")


def reject_known_root_password(root_hash: bytes) -> None:
    if len(REJECTED_ROOT_PASSWORDS) > MAX_REJECTED_ROOT_PASSWORDS:
        fail("rejected root password policy exceeds its configured bound")
    openssl = shutil.which("openssl")
    if openssl is None:
        fail("openssl is required for root password provenance verification")
    salt = root_hash.split(b"$")[2].decode("ascii")
    stdout, stderr = run_bounded(
        [openssl, "passwd", "-6", "-salt", salt, *REJECTED_ROOT_PASSWORDS],
        timeout_seconds=10.0,
        output_limit_bytes=4_096,
    )
    if stderr:
        fail("openssl reported unexpected diagnostics during root password verification")
    candidate_hashes = stdout.splitlines()
    if len(candidate_hashes) != len(REJECTED_ROOT_PASSWORDS):
        fail("openssl returned an unexpected root password verification result")
    if root_hash in candidate_hashes:
        fail("artifact root hash matches a retired or common default password")


def verify_shadow(shadow: bytes) -> None:
    root_hash = None
    for line in shadow.splitlines():
        fields = line.split(b":")
        if len(fields) != 9 or not fields[0]:
            fail("artifact contains a malformed shadow entry")
        if fields[0] == b"root":
            if root_hash is not None:
                fail("artifact contains duplicate root shadow entries")
            root_hash = fields[1]
        elif not fields[1].startswith((b"!", b"*")):
            fail("artifact contains a non-root account with a usable password hash")
    if root_hash is None or not SHA512_CRYPT.fullmatch(root_hash):
        fail("artifact root account does not have a valid SHA-512 crypt hash")
    reject_known_root_password(root_hash)


def verify_open_artifact(tool: str, artifact: Path, artifact_fd: int, artifact_size: int) -> None:
    summary, summary_errors = run_bounded(
        unsquashfs_command(tool, ["-s"], artifact),
        timeout_seconds=TOOL_TIMEOUT_SECONDS,
        output_limit_bytes=MAX_METADATA_OUTPUT_BYTES,
        pass_fds=(artifact_fd,),
    )
    if summary_errors:
        fail("unsquashfs reported unexpected diagnostics during artifact preflight")
    inode_match = re.search(rb"(?m)^Number of inodes (\d+)$", summary)
    size_match = re.search(rb"(?m)^Filesystem size (\d+) bytes ", summary)
    if inode_match is None or size_match is None:
        fail("unsquashfs summary omitted required bounded metadata")
    inode_count = int(inode_match.group(1))
    filesystem_bytes = int(size_match.group(1))
    if not 0 < inode_count <= MAX_ARTIFACT_INODES:
        fail(f"artifact inode count exceeds {MAX_ARTIFACT_INODES} bound")
    if not 0 < filesystem_bytes <= artifact_size:
        fail("artifact superblock reports an invalid compressed filesystem size")

    listing_limit = min(MAX_LISTING_OUTPUT_BYTES, inode_count * 4_096 + 4_096)
    listing, listing_errors = run_bounded(
        unsquashfs_command(tool, ["-lln"], artifact),
        timeout_seconds=TOOL_TIMEOUT_SECONDS,
        output_limit_bytes=listing_limit,
        pass_fds=(artifact_fd,),
    )
    if listing_errors:
        fail("unsquashfs reported unexpected diagnostics while listing artifact")
    inspect_listing(listing, inode_count)

    shadow = artifact_cat(tool, artifact, artifact_fd, "etc/shadow")
    dropbear = artifact_cat(tool, artifact, artifact_fd, "etc/conf.d/dropbear")
    issue = artifact_cat(tool, artifact, artifact_fd, "etc/issue")
    login = artifact_cat(tool, artifact, artifact_fd, "usr/local/bin/podroid-login")
    verify_shadow(shadow)
    if active_config_lines(dropbear) != [b'DROPBEAR_OPTS="-s"']:
        fail("artifact Dropbear password authentication is not disabled")
    require_contains(login, b"exec /bin/login -f root", "artifact guest console does not auto-login root")
    require_contains(issue, b"SSH: public-key authentication only", "artifact banner omits public-key-only SSH")


def verify_artifact(repo_root: Path, artifact: Path) -> None:
    del repo_root  # Kept explicit for a stable test seam and future policy inputs.
    try:
        artifact_fd = os.open(artifact, os.O_RDONLY | os.O_CLOEXEC | os.O_NOFOLLOW)
    except OSError as exc:
        fail(f"cannot open rootfs artifact without symlink traversal {artifact}: {exc}")
    try:
        metadata = os.fstat(artifact_fd)
        if not stat.S_ISREG(metadata.st_mode):
            fail("rootfs artifact must be a regular file, not a symlink or special file")
        if not 0 < metadata.st_size <= MAX_ARTIFACT_BYTES:
            fail("rootfs artifact is empty or exceeds 1 GiB compressed-size bound")

        tool = shutil.which("unsquashfs")
        if tool is None:
            fail("unsquashfs (squashfs-tools) is required for explicit artifact verification")

        # Every unsquashfs invocation receives the already-validated descriptor.
        # Path replacement after open therefore cannot change what is inspected.
        descriptor_path = Path(f"/proc/self/fd/{artifact_fd}")
        verify_open_artifact(tool, descriptor_path, artifact_fd, metadata.st_size)
    finally:
        os.close(artifact_fd)


def apk_entry_count(apk_file, apk_size: int) -> int:
    tail_size = min(apk_size, ZIP_EOCD_SIZE + ZIP_MAX_COMMENT_BYTES)
    apk_file.seek(apk_size - tail_size)
    tail = apk_file.read(tail_size)
    offset = tail.rfind(ZIP_EOCD_SIGNATURE)
    while offset >= 0:
        if offset + ZIP_EOCD_SIZE <= len(tail):
            fields = struct.unpack_from("<4s4H2IH", tail, offset)
            comment_size = fields[-1]
            if offset + ZIP_EOCD_SIZE + comment_size == len(tail):
                break
        offset = tail.rfind(ZIP_EOCD_SIGNATURE, 0, offset)
    if offset < 0:
        fail("APK is not a well-formed bounded ZIP archive")

    _, disk_number, central_disk, disk_entries, total_entries, central_size, central_offset, _ = fields
    if disk_number != 0 or central_disk != 0 or disk_entries != total_entries:
        fail("APK uses unsupported split ZIP metadata")
    if total_entries == 0xFFFF or central_size == 0xFFFFFFFF or central_offset == 0xFFFFFFFF:
        fail("APK uses unsupported ZIP64 metadata")
    if total_entries > MAX_APK_ENTRIES:
        fail(f"APK contains more than {MAX_APK_ENTRIES} declared entries")
    if central_size > MAX_APK_CENTRAL_DIRECTORY_BYTES:
        fail("APK central directory exceeds its 16 MiB bound")
    eocd_offset = apk_size - tail_size + offset
    if central_offset + central_size != eocd_offset:
        fail("APK central directory metadata is inconsistent")
    return total_entries


def rootfs_like_apk_name(name: str) -> bool:
    normalized_separators = name.replace("\\", "/").rstrip("/")
    return normalized_separators.rsplit("/", 1)[-1] == APK_ROOTFS_BASENAME


def copy_apk_rootfs(archive: zipfile.ZipFile, entry: zipfile.ZipInfo, destination: Path) -> None:
    if not 0 < entry.file_size <= MAX_ARTIFACT_BYTES:
        fail("packaged rootfs is empty or exceeds 1 GiB declared-size bound")
    if entry.compress_size > MAX_APK_BYTES:
        fail("packaged rootfs compressed size exceeds the APK bound")
    unix_mode = (entry.external_attr >> 16) & 0xFFFF
    unix_type = stat.S_IFMT(unix_mode)
    if entry.is_dir() or entry.flag_bits & 0x1 or unix_type not in (0, stat.S_IFREG):
        fail("packaged rootfs must be an unencrypted regular file entry")

    descriptor = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_CLOEXEC, 0o600)
    try:
        output = os.fdopen(descriptor, "wb", closefd=True)
    except BaseException:
        os.close(descriptor)
        raise

    actual_size = 0
    deadline = time.monotonic() + APK_STREAM_TIMEOUT_SECONDS
    try:
        with output, archive.open(entry, "r") as source:
            while True:
                if time.monotonic() >= deadline:
                    fail(f"packaged rootfs streaming timed out after {APK_STREAM_TIMEOUT_SECONDS:g}s")
                chunk = source.read(min(1024 * 1024, MAX_ARTIFACT_BYTES + 1 - actual_size))
                if time.monotonic() >= deadline:
                    fail(f"packaged rootfs streaming timed out after {APK_STREAM_TIMEOUT_SECONDS:g}s")
                if not chunk:
                    break
                actual_size += len(chunk)
                if actual_size > MAX_ARTIFACT_BYTES:
                    fail("packaged rootfs exceeds 1 GiB actual-size bound")
                output.write(chunk)
    except VerificationError:
        raise
    except (OSError, RuntimeError, NotImplementedError, zipfile.BadZipFile) as exc:
        fail(f"cannot safely stream packaged rootfs: {exc}")
    if actual_size != entry.file_size:
        fail("packaged rootfs actual size differs from ZIP metadata")


def verify_packaged_rootfs(
    apk: Path,
    require_rootfs: bool,
    verify_rootfs: Callable[[Path], object],
) -> bool:
    """Safely stream the exact APK rootfs entry and invoke its semantic verifier."""
    try:
        apk_fd = os.open(apk, os.O_RDONLY | os.O_CLOEXEC | os.O_NOFOLLOW)
    except OSError as exc:
        fail(f"cannot open APK without symlink traversal {apk}: {exc}")
    try:
        metadata = os.fstat(apk_fd)
        if not stat.S_ISREG(metadata.st_mode):
            fail("APK must be a regular file, not a symlink or special file")
        if not 0 < metadata.st_size <= MAX_APK_BYTES:
            fail("APK is empty or exceeds 1.5 GiB size bound")

        with tempfile.TemporaryDirectory(prefix="podroid-rootfs-verification-") as temporary:
            temporary_rootfs = Path(temporary) / APK_ROOTFS_BASENAME
            try:
                with os.fdopen(os.dup(apk_fd), "rb", closefd=True) as apk_file:
                    declared_entries = apk_entry_count(apk_file, metadata.st_size)
                    apk_file.seek(0)
                    with zipfile.ZipFile(apk_file, "r") as archive:
                        entries = archive.infolist()
                        if len(entries) != declared_entries:
                            fail("APK entry count differs from end-of-directory metadata")
                        seen_names = set()
                        rootfs_entry = None
                        for entry in entries:
                            if entry.orig_filename != entry.filename or "\x00" in entry.orig_filename:
                                fail("APK contains a NUL-confused entry name")
                            if entry.filename in seen_names:
                                fail(f"APK contains duplicate entry: {entry.filename!r}")
                            seen_names.add(entry.filename)
                            if rootfs_like_apk_name(entry.filename):
                                if entry.filename != APK_ROOTFS_ENTRY:
                                    fail(f"APK contains a path-confused rootfs entry: {entry.filename!r}")
                                if rootfs_entry is not None:
                                    fail("APK contains duplicate rootfs entries")
                                rootfs_entry = entry

                        if rootfs_entry is None:
                            if require_rootfs:
                                fail(f"APK is missing required {APK_ROOTFS_ENTRY}")
                            return False
                        copy_apk_rootfs(archive, rootfs_entry, temporary_rootfs)
            except VerificationError:
                raise
            except (OSError, RuntimeError, NotImplementedError, zipfile.BadZipFile) as exc:
                fail(f"malformed APK ZIP: {exc}")

            verify_rootfs(temporary_rootfs)
            return True
    finally:
        os.close(apk_fd)


def verify_apk(repo_root: Path, apk: Path, require_rootfs: bool) -> None:
    verify_packaged_rootfs(
        apk,
        require_rootfs,
        lambda rootfs: verify_artifact(repo_root, rootfs),
    )


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Verify guest credential sources, a SquashFS artifact, or its exact packaged APK entry."
    )
    parser.add_argument("artifact", nargs="?", type=Path, help="explicit SquashFS artifact to inspect")
    parser.add_argument("--apk", type=Path, help="APK whose exact packaged rootfs entry is inspected")
    parser.add_argument(
        "--require-rootfs",
        action="store_true",
        help="fail when --apk does not contain the expected rootfs entry",
    )
    args = parser.parse_args(argv)
    if args.artifact is not None and args.apk is not None:
        parser.error("artifact and --apk are mutually exclusive")
    if args.require_rootfs and args.apk is None:
        parser.error("--require-rootfs requires --apk")
    return args


def main(argv: list[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    repo_root = Path(__file__).resolve().parent.parent
    try:
        verify_source(repo_root)
        if args.artifact is not None:
            artifact = args.artifact if args.artifact.is_absolute() else repo_root / args.artifact
            verify_artifact(repo_root, artifact)
        elif args.apk is not None:
            apk = args.apk if args.apk.is_absolute() else repo_root / args.apk
            verify_apk(repo_root, apk, args.require_rootfs)
    except (VerificationError, OSError) as exc:
        print(f"guest credential verification failed: {exc}", file=sys.stderr)
        return 1
    suffix = " and explicit artifact" if args.artifact is not None else " and packaged APK" if args.apk else ""
    print(f"Guest credential source{suffix} verification passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
