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
import subprocess
import sys
import time
from pathlib import Path

MAX_SOURCE_ENTRIES = 2_000
MAX_SOURCE_FILE_BYTES = 16 * 1024 * 1024
MAX_ARTIFACT_BYTES = 1024 * 1024 * 1024
MAX_ARTIFACT_INODES = 100_000
MAX_ARTIFACT_EXPANDED_BYTES = 8 * 1024 * 1024 * 1024
MAX_METADATA_OUTPUT_BYTES = 64 * 1024
MAX_LISTING_OUTPUT_BYTES = 64 * 1024 * 1024
MAX_CAT_OUTPUT_BYTES = 1024 * 1024
TOOL_TIMEOUT_SECONDS = 60.0
CAT_TIMEOUT_SECONDS = 20.0

SHA512_CRYPT = re.compile(rb"^\$6\$[./0-9A-Za-z]{1,16}\$[./0-9A-Za-z]{86}$")
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
        try:
            entries = list(os.scandir(directory))
        except OSError as exc:
            fail(f"cannot scan source directory {directory}: {exc}")
        entries.sort(key=lambda item: os.fsencode(item.name))
        for entry in entries:
            count += 1
            if count > MAX_SOURCE_ENTRIES:
                fail(f"credential source scan exceeds {MAX_SOURCE_ENTRIES} entries")
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
        if path.name == "alpine-rootfs.squashfs":
            continue  # Artifacts are inspected only when explicitly requested.
        if not stat.S_ISREG(metadata.st_mode):
            continue
        if metadata.st_size > MAX_SOURCE_FILE_BYTES:
            fail(f"credential source file exceeds 16 MiB: {path!r}")
        data = read_regular_file(path)
        if KEY_MATERIAL.search(data):
            fail(f"bundled SSH key material in {path!r}")


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
    require_contains(
        source["build-rootfs/build-rootfs.sh"],
        b"ROOT_HASH=$(/work/generate-root-password-hash.sh)",
        "rootfs build does not execute the root hash generator",
    )
    require_contains(
        source["build-rootfs/build-rootfs.sh"],
        b'root:${ROOT_HASH}:',
        "rootfs build does not write the generated hash to shadow",
    )
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


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Verify guest credential sources and, when explicitly supplied, a rootfs artifact."
    )
    parser.add_argument("artifact", nargs="?", type=Path, help="explicit SquashFS artifact to inspect")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    repo_root = Path(__file__).resolve().parent.parent
    try:
        verify_source(repo_root)
        if args.artifact is not None:
            artifact = args.artifact if args.artifact.is_absolute() else repo_root / args.artifact
            verify_artifact(repo_root, artifact)
    except (VerificationError, OSError) as exc:
        print(f"guest credential verification failed: {exc}", file=sys.stderr)
        return 1
    suffix = " and explicit artifact" if args.artifact is not None else ""
    print(f"Guest credential source{suffix} verification passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
