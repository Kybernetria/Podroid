#!/usr/bin/env python3
"""Fail-closed source and SquashFS verification for the minimal Alpine guest."""

from __future__ import annotations

import argparse
import os
import re
import shutil
import stat
import sys
from pathlib import Path

try:
    import verify_guest_credentials as bounded
except ModuleNotFoundError:  # Imported as tests.verify_minimal_guest by unittest.
    from tests import verify_guest_credentials as bounded

MAX_EXPLICIT_PACKAGES = 32
MAX_RESOLVED_PACKAGES = 128
MAX_PACKAGE_DATABASE_BYTES = 2 * 1024 * 1024
MAX_MIGRATION_INDEX_BYTES = 4096
MAX_RUNLEVELS_LOCK_BYTES = 4096
MINIROOTFS_SHA256 = "9250667a8affac8f1e98086392f80f43f086626701e9bce33398eb9b6c0bd64c"
ALPINE_IMAGE_DIGEST = "sha256:fd791d74b68913cbb027c6546007b3f0d3bc45125f797758156952bc2d6daf40"
EXPECTED_EXPLICIT_PACKAGES = (
    "alpine-base",
    "openrc",
    "busybox-openrc",
    "iproute2",
    "dropbear",
    "dropbear-openrc",
    "ca-certificates",
)
EXPECTED_RESOLVED_PACKAGES = (
    "alpine-base", "alpine-baselayout", "alpine-baselayout-data", "alpine-conf",
    "alpine-keys", "alpine-release", "apk-tools", "bridge", "busybox",
    "busybox-binsh", "busybox-mdev-openrc", "busybox-openrc", "busybox-suid",
    "ca-certificates", "ca-certificates-bundle", "dropbear", "dropbear-openrc",
    "ifupdown-ng", "ifupdown-ng-iproute2", "iproute2", "iproute2-minimal",
    "iproute2-ss", "iproute2-tc", "libapk", "libcap2", "libcrypto3", "libelf",
    "libmnl", "libssl3", "libxtables", "mdev-conf", "musl", "musl-utils",
    "openrc", "openrc-user", "scanelf", "skalibs-libs", "ssl_client",
    "utmps-libs", "zlib", "zstd-libs",
)
REQUIRED_SERVICES = (
    "podroid-migrate",
    "podroid-bootstrap",
    "podroid-network",
    "podroid-resize",
    "dropbear",
    "podroid-vsock",
    "podroid-downloads",
    "podroid-hostd",
    "podroid-ready",
)
EXPECTED_RUNLEVELS = {
    "sysinit": {},
    "boot": {},
    "default": {service: f"/etc/init.d/{service}" for service in REQUIRED_SERVICES},
    "shutdown": {},
}
FORBIDDEN_SOURCE_PATHS = (
    "build-rootfs/files/etc/init.d/podroid-x11",
    "build-rootfs/files/etc/profile.d/podroid-x11.sh",
    "build-rootfs/files/etc/containers/storage.conf",
    "build-rootfs/files/usr/local/bin/podroid-backup",
    "build-rootfs/files/usr/local/bin/podroid-update-stats",
    "build-rootfs/files/usr/share/podroid/logo.png",
)
FORBIDDEN_ARTIFACT_PATHS = (
    "etc/init.d/podroid-x11",
    "etc/profile.d/podroid-x11.sh",
    "etc/containers/storage.conf",
    "usr/local/bin/podroid-backup",
    "usr/local/bin/podroid-update-stats",
    "usr/share/podroid/logo.png",
    "usr/bin/Xvnc",
    "usr/bin/podman",
    "usr/bin/docker",
    "usr/bin/lxc-start",
    "usr/bin/pulseaudio",
)
REQUIRED_ARTIFACT_PATHS = (
    "sbin/init",
    "sbin/openrc",
    "sbin/apk",
    "sbin/ip",
    "sbin/mkswap",
    "sbin/swapon",
    "usr/sbin/dropbear",
    "usr/bin/pgrep",
    "etc/ssl/certs/ca-certificates.crt",
    "etc/inittab",
    "etc/conf.d/dropbear",
    "etc/podroid/forwards.conf",
    "etc/podroid/system-version",
    "etc/podroid/migrations/index",
    "etc/podroid/migrations/31.sh",
    "usr/local/bin/podroid-getty",
    "usr/local/bin/podroid-login",
    "usr/local/bin/podroid-resize",
    "usr/local/bin/podroid-hostd",
    "usr/local/bin/podroid-vsock-agent",
    "usr/local/bin/podroid-overlay-normalize",
    "usr/local/bin/podroid-migrate-safe",
    "usr/local/bin/podroid-migrate-runner",
    "usr/local/bin/podroid-notify",
    "usr/local/bin/podroid-forward",
)
PACKAGE_NAME = re.compile(r"^[a-z0-9][a-z0-9+_.-]{0,127}$")

VerificationError = bounded.VerificationError
fail = bounded.fail


def parse_explicit_packages(data: bytes) -> tuple[str, ...]:
    if len(data) > 4096:
        fail("minimal package manifest exceeds 4096-byte source bound")
    try:
        text = data.decode("ascii")
    except UnicodeDecodeError:
        fail("minimal package manifest is not ASCII")
    lines = text.splitlines()
    if not lines or len(lines) > MAX_EXPLICIT_PACKAGES:
        fail(f"minimal package manifest must contain 1..{MAX_EXPLICIT_PACKAGES} entries")
    if any(not PACKAGE_NAME.fullmatch(line) for line in lines):
        fail("minimal package manifest contains a blank or malformed package name")
    if len(set(lines)) != len(lines):
        fail("minimal package manifest contains duplicate package names")
    packages = tuple(lines)
    if packages != EXPECTED_EXPLICIT_PACKAGES:
        fail("minimal package manifest differs from the reviewed explicit package set")
    return packages


def parse_resolved_package_lock(data: bytes) -> tuple[str, ...]:
    if len(data) > 16 * 1024:
        fail("resolved package lock exceeds 16 KiB")
    packages = parse_installed_packages(b"\n\n".join(b"P:" + line for line in data.splitlines()))
    if packages != EXPECTED_RESOLVED_PACKAGES:
        fail("resolved package lock differs from the reviewed 41-package artifact")
    return packages


def active_fields(data: bytes) -> list[list[bytes]]:
    return [line.split() for line in bounded.active_config_lines(data)]


def require_bytes(data: bytes, expected: bytes, message: str) -> None:
    if expected not in data:
        fail(message)


def parse_migration_index(data: bytes) -> tuple[int, ...]:
    if not data or len(data) > MAX_MIGRATION_INDEX_BYTES:
        fail("migration index is empty or exceeds 4096 bytes")
    entries: list[int] = []
    for line in data.splitlines():
        if not re.fullmatch(rb"[1-9][0-9]{0,8}", line):
            fail("migration index contains a malformed entry")
        value = int(line)
        if entries and value <= entries[-1]:
            fail("migration index contains duplicate or out-of-order entries")
        entries.append(value)
        if len(entries) > 256:
            fail("migration index exceeds 256 entries")
    return tuple(entries)


def parse_runlevels_lock(data: bytes) -> dict[str, dict[str, str]]:
    if not data or len(data) > MAX_RUNLEVELS_LOCK_BYTES:
        fail("runlevel lock is empty or exceeds 4096 bytes")
    try:
        lines = data.decode("ascii").splitlines()
    except UnicodeDecodeError:
        fail("runlevel lock is not ASCII")
    runlevels: dict[str, dict[str, str]] = {}
    empty_declarations: set[str] = set()
    for line in lines:
        if not line or line.startswith("#"):
            continue
        fields = line.split(" ")
        if len(fields) != 3 or any(not field for field in fields):
            fail("runlevel lock contains a malformed entry")
        runlevel, entry, target = fields
        if runlevel not in EXPECTED_RUNLEVELS:
            fail(f"runlevel lock declares unknown runlevel: {runlevel}")
        entries = runlevels.setdefault(runlevel, {})
        if entry == "-" and target == "-":
            if entries or runlevel in empty_declarations:
                fail(f"runlevel lock has a duplicate empty declaration: {runlevel}")
            empty_declarations.add(runlevel)
            continue
        if runlevel in empty_declarations:
            fail(f"runlevel lock mixes empty and populated declarations: {runlevel}")
        if not PACKAGE_NAME.fullmatch(entry) or target != f"/etc/init.d/{entry}":
            fail("runlevel lock contains an invalid entry or symlink target")
        if entry in entries:
            fail(f"runlevel lock contains a duplicate entry: {runlevel}/{entry}")
        entries[entry] = target
    if set(runlevels) != set(EXPECTED_RUNLEVELS) or runlevels != EXPECTED_RUNLEVELS:
        fail("runlevel lock differs from the exact reviewed inittab runlevels")
    return runlevels


def verify_boot_dependency_policy(
    bootstrap: bytes,
    network: bytes,
    ready: bytes,
) -> None:
    """Require the complete migration-to-Ready OpenRC dependency chain."""
    require_bytes(
        bootstrap,
        b"need localmount podroid-migrate",
        "podroid-bootstrap does not need successful podroid-migrate",
    )
    require_bytes(
        bootstrap,
        b"before podroid-network",
        "podroid-bootstrap is not ordered before networking",
    )
    require_bytes(
        network,
        b"need podroid-bootstrap",
        "podroid-network does not need successful bootstrap",
    )
    require_bytes(
        ready,
        b"need podroid-network",
        "podroid-ready does not need successful networking",
    )


def verify_migration_policy(
    migration: bytes,
    migrate_service: bytes,
    runner: bytes,
    helper_source: bytes,
    index: bytes,
) -> None:
    if parse_migration_index(index) != (31,):
        fail("minimal guest migration index must contain only version 31")
    active_migration = b"\n".join(bounded.active_config_lines(migration))
    for sensitive in (b"/mnt/persist/", b"/var/lib/", b"rm ", b"find "):
        if sensitive in active_migration:
            fail("migration 31 may mutate reviewed system paths only")
    require_bytes(active_migration, b"podroid-migrate-safe", "migration 31 does not use the no-follow helper")
    require_bytes(active_migration, b"apply-31", "migration 31 does not select its bounded helper operation")

    for token in (b"openat(", b"unlinkat(", b"renameat(", b"O_NOFOLLOW", b"O_EXCL", b"getrandom("):
        require_bytes(helper_source, token, f"migration helper omits {token!r}")
    if b'"/mnt/persist' in helper_source or b'"/var/lib' in helper_source:
        fail("migration helper must not name persistent workload data")
    for obsolete_path in (
        b"/etc/runlevels/default/podroid-x11",
        b"/etc/runlevels/default/docker",
        b"/etc/runlevels/default/lxc",
        b"/etc/init.d/podroid-x11",
        b"/etc/profile.d/podroid-x11.sh",
        b"/etc/containers/storage.conf",
        b"/usr/local/bin/podroid-backup",
        b"/usr/local/bin/podroid-update-stats",
        b"/usr/share/podroid/logo.png",
    ):
        require_bytes(helper_source, obsolete_path, f"migration helper omits stale path {obsolete_path.decode()}")

    require_bytes(migrate_service, b"/mnt/lower/usr/local/bin/podroid-migrate-runner", "OpenRC does not invoke the immutable runner")
    for forbidden in (b"*.sh", b"sort ", b"mktemp", b"/run/podroid-migrations"):
        if forbidden in runner:
            fail(f"migration runner uses forbidden enumeration/list mechanism: {forbidden!r}")
    for token in (
        b'max_migrations=256', b'max_index_bytes=4096', b'done < "$index_file"',
        b'[ "$version" -gt "$previous" ]', b'[ ! -L "$script" ]',
        b'write-applied "$persist_root" "$current"',
    ):
        require_bytes(runner, token, f"migration runner omits fail-closed policy token {token!r}")


def verify_source(repo_root: Path) -> None:
    manifest_path = repo_root / "build-rootfs/minimal-packages.txt"
    packages = parse_explicit_packages(bounded.read_regular_file(manifest_path, 4096))
    resolved_lock = parse_resolved_package_lock(
        bounded.read_regular_file(repo_root / "build-rootfs/resolved-packages.lock", 16 * 1024)
    )

    for relative in FORBIDDEN_SOURCE_PATHS:
        try:
            (repo_root / relative).lstat()
        except FileNotFoundError:
            continue
        except OSError as exc:
            fail(f"cannot inspect forbidden minimal-guest source path {relative}: {exc}")
        fail(f"obsolete minimal-guest source path still exists: {relative}")

    build_script = bounded.read_regular_file(repo_root / "build-rootfs/build-rootfs.sh")
    dockerfile = bounded.read_regular_file(repo_root / "build-rootfs/Dockerfile.rootfs")
    gradle = bounded.read_regular_file(repo_root / "app/build.gradle.kts")
    forwards = bounded.read_regular_file(repo_root / "build-rootfs/files/etc/podroid/forwards.conf")
    migration = bounded.read_regular_file(
        repo_root / "build-rootfs/files/etc/podroid/migrations/31.sh"
    )
    migrate_service = bounded.read_regular_file(
        repo_root / "build-rootfs/files/etc/init.d/podroid-migrate"
    )
    migrate_runner = bounded.read_regular_file(
        repo_root / "build-rootfs/files/usr/local/bin/podroid-migrate-runner"
    )
    migrate_helper = bounded.read_regular_file(
        repo_root / "build-rootfs/migrate-safe/podroid-migrate-safe.c"
    )
    podroid_service = bounded.read_regular_file(
        repo_root / "app/src/main/java/com/excp/podroid/service/PodroidService.kt"
    )
    port_forward_repository = bounded.read_regular_file(
        repo_root / "app/src/main/java/com/excp/podroid/data/repository/PortForwardRepository.kt"
    )
    migration_index = bounded.read_regular_file(
        repo_root / "build-rootfs/files/etc/podroid/migrations/index",
        MAX_MIGRATION_INDEX_BYTES,
    )
    runlevels_lock = bounded.read_regular_file(
        repo_root / "build-rootfs/runlevels.lock", MAX_RUNLEVELS_LOCK_BYTES
    )
    initramfs_init = bounded.read_regular_file(repo_root / "init-podroid")
    normalizer_source = bounded.read_regular_file(
        repo_root / "build-rootfs/overlay-normalize/podroid-overlay-normalize.c"
    )
    normalizer_tests = bounded.read_regular_file(
        repo_root / "build-rootfs/overlay-normalize/test_normalize.sh"
    )
    build_all = bounded.read_regular_file(repo_root / "build-all.sh")

    require_bytes(build_script, b'MINIMAL_PACKAGES=/work/minimal-packages.txt', "rootfs build does not consume the reviewed package manifest")
    require_bytes(build_script, b'--initdb add "$@"', "rootfs build does not pass only parsed manifest entries to apk")
    require_bytes(dockerfile, b"COPY minimal-packages.txt /work/minimal-packages.txt", "rootfs Dockerfile does not copy the reviewed package manifest")
    require_bytes(dockerfile, b"COPY resolved-packages.lock /work/resolved-packages.lock", "rootfs Dockerfile does not copy the resolved package lock")
    require_bytes(dockerfile, b"COPY runlevels.lock /work/runlevels.lock", "rootfs Dockerfile does not copy the reviewed runlevel lock")
    require_bytes(build_script, b'resolved package closure differs from reviewed lock', "rootfs build does not enforce the resolved package lock")
    require_bytes(dockerfile, MINIROOTFS_SHA256.encode(), "minirootfs SHA-256 is not pinned")
    require_bytes(dockerfile, b"sha256sum -c -", "minirootfs download is not checksum verified")
    require_bytes(dockerfile, b"--connect-timeout 30 --max-time 300 --retry 2", "minirootfs download is not deadline bounded")
    for overrideable_pin in (b"ARG ALPINE_RELEASE", b"ARG ALPINE_ARCH", b"ARG ALPINE_MINIROOTFS_SHA256"):
        if overrideable_pin in dockerfile:
            fail("minirootfs release, architecture, and checksum pins must not be build-argument overrideable")
    if dockerfile.count(ALPINE_IMAGE_DIGEST.encode()) != 2:
        fail("both Alpine build stages must use the pinned multi-arch image digest")
    for obsolete in (b"podroid-x11", b"podroid-backup", b"podroid-update-stats", b"storage.conf", b"logo.png"):
        if obsolete in build_script:
            fail(f"rootfs build still references obsolete source: {obsolete.decode()}")

    version_codes = re.findall(rb"(?m)^\s*versionCode\s*=\s*(\d+)\s*$", gradle)
    if version_codes != [b"31"]:
        fail("Android versionCode must be exactly 31 for the minimal guest migration")

    parse_runlevels_lock(runlevels_lock)
    require_bytes(build_script, b'rm -rf "$ROOTFS/etc/runlevels"', "rootfs build does not remove package-provided runlevels")
    require_bytes(build_script, b'done < "$RUNLEVELS_LOCK"', "rootfs build does not reconstruct runlevels from the reviewed lock")

    if active_fields(forwards) != [[b"9100", b"ctl"]]:
        fail("minimal guest must seed only the AVF control forward on port 9100")
    for forbidden_host_policy in (b"X11Constants.VNC_PORT", b"X11Constants.AUDIO_PORT", b"RESERVED_HOST_PORTS"):
        if forbidden_host_policy in podroid_service or forbidden_host_policy in port_forward_repository:
            fail("Android host still injects or reserves a display/audio listener")

    verify_migration_policy(migration, migrate_service, migrate_runner, migrate_helper, migration_index)
    bootstrap_service = bounded.read_regular_file(
        repo_root / "build-rootfs/files/etc/init.d/podroid-bootstrap"
    )
    network_service = bounded.read_regular_file(
        repo_root / "build-rootfs/files/etc/init.d/podroid-network"
    )
    ready_service = bounded.read_regular_file(
        repo_root / "build-rootfs/files/etc/init.d/podroid-ready"
    )
    verify_boot_dependency_policy(bootstrap_service, network_service, ready_service)

    required_service_content = {
        "podroid-bootstrap": (b"Loading kernel modules...", b"mount --make-rshared /", b"/dev/net/tun", b"cgroup2", b"zram0", b"downloads /mnt/downloads"),
        "podroid-network": (b"podroid\\.backend=avf", b"udhcpc", b"10.0.2.15/24", b"10.0.2.2", b"Network found"),
        "podroid-ready": (b"Starting SSH...", b"Almost ready...", b"Ready!"),
        "podroid-downloads": (b"downloads-9p", b"podroid\\.backend=avf", b"kill -0"),
        "podroid-hostd": (b"/usr/local/bin/podroid-hostd",),
        "podroid-vsock": (b"/usr/local/bin/podroid-vsock-agent",),
    }
    for service, needles in required_service_content.items():
        data = bounded.read_regular_file(repo_root / f"build-rootfs/files/etc/init.d/{service}")
        for needle in needles:
            require_bytes(data, needle, f"{service} omits required boot contract token {needle!r}")

    inittab = bounded.read_regular_file(repo_root / "build-rootfs/files/etc/inittab")
    dropbear = bounded.read_regular_file(repo_root / "build-rootfs/files/etc/conf.d/dropbear")
    executed_runlevels = tuple(
        match.decode()
        for match in re.findall(rb"/sbin/openrc (sysinit|boot|default|shutdown)", inittab)
    )
    if executed_runlevels != ("sysinit", "boot", "default", "shutdown"):
        fail("inittab must execute exactly the four reviewed OpenRC runlevels")
    require_bytes(inittab, b"/sbin/openrc default", "OpenRC default runlevel is not PID 1 boot flow")
    require_bytes(inittab, b"hvc0::respawn:/usr/local/bin/podroid-getty hvc0", "app-owned hvc0 getty is missing")
    if bounded.active_config_lines(dropbear) != [b'DROPBEAR_OPTS="-s"']:
        fail("Dropbear must remain public-key-only")

    normalizer_command = b"/mnt/lower/usr/local/bin/podroid-overlay-normalize /mnt/persist"
    require_bytes(initramfs_init, normalizer_command, "initramfs does not invoke the immutable normalizer on the persistent root")
    normalizer_line = next(
        (line for line in initramfs_init.splitlines() if normalizer_command in line), b""
    )
    if b"|| true" in normalizer_line or b": > /mnt/persist/.podroid/normalized" in initramfs_init:
        fail("initramfs still ignores normalization failure or creates the marker itself")
    require_bytes(initramfs_init, b"FATAL: overlay normalization failed", "initramfs does not stop before stacking a failed normalization")
    for token in (
        b"openat(", b"O_NOFOLLOW", b"fstatat(", b"AT_SYMLINK_NOFOLLOW",
        b"lgetxattr(", b"ENODATA", b"lremovexattr(", b"unlinkat(", b"fsync(",
        b"AT_REMOVEDIR", b"SYS_renameat2", b"RENAME_NOREPLACE", b"close_checked(",
        b"podroid-overlay-normalize-v1\\n", b"remove_legacy_marker(",
    ):
        require_bytes(normalizer_source, token, f"overlay normalizer omits fail-closed token {token!r}")
    for case in (
        b"hostile-directory", b"hostile-marker", b"path-length",
        b"PODROID_NORMALIZE_FAIL", b"after-marker", b"legacy_payload in empty old",
        b"publish-prepare,rollback-unlink", b"publish-prepare,rollback-sync",
        b"publication-sync-safe",
    ):
        require_bytes(normalizer_tests, case, f"overlay normalizer tests omit regression case {case!r}")
    require_bytes(
        build_all,
        b'build-rootfs/overlay-normalize/test_normalize.sh',
        "standard rootfs verification does not execute overlay normalizer regressions",
    )
    for verifier in (b"tests/verify_guest_credentials.py", b"tests/verify_minimal_guest.py"):
        require_bytes(build_all, verifier, f"standard APK build omits exact verifier {verifier!r}")
    if build_all.count(b"--require-rootfs") < 2:
        fail("standard APK build does not require the packaged rootfs in both exact verifiers")

    print(
        f"minimal guest source verification passed: {len(packages)} explicit packages, "
        f"{len(resolved_lock)} locked resolved packages"
    )


def parse_installed_packages(database: bytes) -> tuple[str, ...]:
    if not database or len(database) > MAX_PACKAGE_DATABASE_BYTES:
        fail("artifact apk database is empty or exceeds its 2 MiB bound")
    packages: list[str] = []
    for record in database.split(b"\n\n"):
        if not record.strip():
            continue
        names = [line[2:] for line in record.splitlines() if line.startswith(b"P:")]
        if len(names) != 1:
            fail("artifact apk database contains a malformed package record")
        try:
            name = names[0].decode("ascii")
        except UnicodeDecodeError:
            fail("artifact apk database contains a non-ASCII package name")
        if not PACKAGE_NAME.fullmatch(name):
            fail("artifact apk database contains an invalid package name")
        packages.append(name)
        if len(packages) > MAX_RESOLVED_PACKAGES:
            fail(f"artifact package closure exceeds {MAX_RESOLVED_PACKAGES} packages")
    if not packages or len(set(packages)) != len(packages):
        fail("artifact apk database has no packages or duplicate package records")
    return tuple(sorted(packages))


def verify_package_closure(packages: tuple[str, ...]) -> None:
    if packages != EXPECTED_RESOLVED_PACKAGES:
        missing = sorted(set(EXPECTED_RESOLVED_PACKAGES).difference(packages))
        unexpected = sorted(set(packages).difference(EXPECTED_RESOLVED_PACKAGES))
        detail = []
        if missing:
            detail.append("missing=" + ",".join(missing))
        if unexpected:
            detail.append("unexpected=" + ",".join(unexpected))
        fail("artifact package closure differs from exact reviewed lock" +
             (": " + "; ".join(detail) if detail else ""))


def listing_paths(listing: bytes, inode_count: int) -> dict[str, tuple[bytes, str | None]]:
    bounded.inspect_listing(listing, inode_count)
    paths: dict[str, tuple[bytes, str | None]] = {}
    for line in listing.splitlines():
        match = bounded.LISTING_LINE.fullmatch(line)
        assert match is not None
        raw_entry = match.group("path")
        raw_path, separator, raw_target = raw_entry.partition(b" -> ")
        try:
            path = raw_path.decode("utf-8")
            target = raw_target.decode("utf-8") if separator else None
        except UnicodeDecodeError:
            fail("artifact listing contains a non-UTF-8 path or symlink target")
        if path == "squashfs-root":
            relative = ""
        elif path.startswith("squashfs-root/"):
            relative = path[len("squashfs-root/") :]
        else:
            fail("artifact listing path escapes the expected SquashFS root")
        if relative:
            components = relative.split("/")
            if any(component in ("", ".", "..") for component in components):
                fail("artifact listing contains an empty or traversal path component")
        if relative in paths:
            fail(f"artifact listing contains duplicate path: {relative}")
        paths[relative] = (match.group("mode"), target)
    return paths


def verify_open_artifact(tool: str, artifact: Path, artifact_fd: int, artifact_size: int) -> tuple[int, tuple[str, ...]]:
    summary, summary_errors = bounded.run_bounded(
        bounded.unsquashfs_command(tool, ["-s"], artifact),
        timeout_seconds=bounded.TOOL_TIMEOUT_SECONDS,
        output_limit_bytes=bounded.MAX_METADATA_OUTPUT_BYTES,
        pass_fds=(artifact_fd,),
    )
    if summary_errors:
        fail("unsquashfs reported unexpected diagnostics during minimal artifact preflight")
    inode_match = re.search(rb"(?m)^Number of inodes (\d+)$", summary)
    size_match = re.search(rb"(?m)^Filesystem size (\d+) bytes ", summary)
    if inode_match is None or size_match is None:
        fail("unsquashfs summary omitted required bounded metadata")
    inode_count = int(inode_match.group(1))
    filesystem_bytes = int(size_match.group(1))
    if not 0 < inode_count <= bounded.MAX_ARTIFACT_INODES:
        fail("minimal artifact inode count exceeds its bound")
    if not 0 < filesystem_bytes <= artifact_size:
        fail("minimal artifact reports an invalid compressed filesystem size")

    listing_limit = min(bounded.MAX_LISTING_OUTPUT_BYTES, inode_count * 4096 + 4096)
    listing, listing_errors = bounded.run_bounded(
        bounded.unsquashfs_command(tool, ["-lln"], artifact),
        timeout_seconds=bounded.TOOL_TIMEOUT_SECONDS,
        output_limit_bytes=listing_limit,
        pass_fds=(artifact_fd,),
    )
    if listing_errors:
        fail("unsquashfs reported unexpected diagnostics while listing minimal artifact")
    paths = listing_paths(listing, inode_count)

    required_paths = set(REQUIRED_ARTIFACT_PATHS)
    required_paths.update(f"etc/init.d/{service}" for service in REQUIRED_SERVICES)
    required_paths.update(f"etc/runlevels/default/{service}" for service in REQUIRED_SERVICES)
    missing_paths = sorted(required_paths.difference(paths))
    if missing_paths:
        fail(f"minimal artifact omits required paths: {', '.join(missing_paths)}")
    present_forbidden = sorted(set(FORBIDDEN_ARTIFACT_PATHS).intersection(paths))
    if present_forbidden:
        fail(f"minimal artifact contains forbidden paths: {', '.join(present_forbidden)}")

    runlevel_directories = {
        path.removeprefix("etc/runlevels/")
        for path in paths
        if path.startswith("etc/runlevels/")
        and path != "etc/runlevels/"
        and "/" not in path.removeprefix("etc/runlevels/")
    }
    if runlevel_directories != set(EXPECTED_RUNLEVELS):
        fail("minimal artifact contains an unknown or missing runlevel directory")
    for runlevel, expected_entries in EXPECTED_RUNLEVELS.items():
        directory_mode, directory_target = paths[f"etc/runlevels/{runlevel}"]
        if not directory_mode.startswith(b"d") or directory_target is not None:
            fail(f"minimal artifact runlevel is not a real directory: {runlevel}")
        prefix = f"etc/runlevels/{runlevel}/"
        artifact_entries = {
            path.removeprefix(prefix)
            for path in paths
            if path.startswith(prefix) and "/" not in path.removeprefix(prefix)
        }
        if artifact_entries != set(expected_entries):
            fail(f"minimal artifact {runlevel} runlevel differs from the exact reviewed lock")
        for entry, expected_target in expected_entries.items():
            runlevel_path = prefix + entry
            mode, target = paths[runlevel_path]
            if not mode.startswith(b"l") or target != expected_target:
                fail(f"minimal artifact has an invalid runlevel link: {runlevel_path}")
    executable_paths = {
        *(f"etc/init.d/{service}" for service in REQUIRED_SERVICES),
        "etc/podroid/migrations/31.sh",
        "sbin/openrc",
        "sbin/apk",
        "sbin/ip",
        "usr/sbin/dropbear",
        "usr/local/bin/podroid-getty",
        "usr/local/bin/podroid-login",
        "usr/local/bin/podroid-resize",
        "usr/local/bin/podroid-hostd",
        "usr/local/bin/podroid-vsock-agent",
        "usr/local/bin/podroid-overlay-normalize",
        "usr/local/bin/podroid-migrate-safe",
        "usr/local/bin/podroid-migrate-runner",
    }
    for executable_path in executable_paths:
        mode, target = paths[executable_path]
        if not mode.startswith(b"-") or mode[3:4] != b"x" or target is not None:
            fail(f"minimal artifact required executable has invalid type/mode: {executable_path}")
    for cli in ("notify", "forward"):
        mode, target = paths[f"usr/local/bin/podroid-{cli}"]
        if not mode.startswith(b"l") or target != "podroid-hostd":
            fail(f"minimal artifact host CLI has an invalid symlink: podroid-{cli}")
    init_mode, init_target = paths["sbin/init"]
    if not init_mode.startswith(b"l") or init_target != "/bin/busybox":
        fail("minimal artifact /sbin/init is not the expected BusyBox PID 1 link")

    database = bounded.artifact_cat(tool, artifact, artifact_fd, "lib/apk/db/installed")
    if len(database) > MAX_PACKAGE_DATABASE_BYTES:
        fail("artifact apk database exceeds its 2 MiB bound")
    packages = parse_installed_packages(database)
    verify_package_closure(packages)

    system_version = bounded.artifact_cat(tool, artifact, artifact_fd, "etc/podroid/system-version")
    forwards = bounded.artifact_cat(tool, artifact, artifact_fd, "etc/podroid/forwards.conf")
    bootstrap = bounded.artifact_cat(tool, artifact, artifact_fd, "etc/init.d/podroid-bootstrap")
    network = bounded.artifact_cat(tool, artifact, artifact_fd, "etc/init.d/podroid-network")
    ready = bounded.artifact_cat(tool, artifact, artifact_fd, "etc/init.d/podroid-ready")
    downloads = bounded.artifact_cat(tool, artifact, artifact_fd, "etc/init.d/podroid-downloads")
    migrate = bounded.artifact_cat(tool, artifact, artifact_fd, "etc/init.d/podroid-migrate")
    migration_31 = bounded.artifact_cat(tool, artifact, artifact_fd, "etc/podroid/migrations/31.sh")
    migration_index = bounded.artifact_cat(tool, artifact, artifact_fd, "etc/podroid/migrations/index")
    migrate_runner = bounded.artifact_cat(tool, artifact, artifact_fd, "usr/local/bin/podroid-migrate-runner")
    migrate_helper = bounded.artifact_cat(tool, artifact, artifact_fd, "usr/local/bin/podroid-migrate-safe")
    if system_version != b"31\n":
        fail("minimal artifact system-version is not 31")
    if active_fields(forwards) != [[b"9100", b"ctl"]]:
        fail("minimal artifact seeds a forbidden display/audio/default forward")
    verify_boot_dependency_policy(bootstrap, network, ready)
    for data, needle in (
        (bootstrap, b"/dev/net/tun"),
        (bootstrap, b"cgroup2"),
        (network, b"udhcpc"),
        (network, b"10.0.2.15/24"),
        (ready, b"Starting SSH..."),
        (ready, b"Almost ready..."),
        (ready, b"Ready!"),
        (downloads, b"downloads-9p"),
        (downloads, b"kill -0"),
    ):
        require_bytes(data, needle, f"minimal artifact omits required boot token {needle!r}")
    # The helper is an ELF in the artifact, so source-level syscall policy was
    # checked above; artifact verification pins its executable presence while
    # runner/index policy remains inspectable here.
    if parse_migration_index(migration_index) != (31,):
        fail("minimal artifact migration index is invalid")
    require_bytes(migrate, b"/mnt/lower/usr/local/bin/podroid-migrate-runner", "artifact OpenRC migration service is mutable-path based")
    require_bytes(migrate_runner, b'done < "$index_file"', "artifact migration runner omits bounded index consumption")
    if not migrate_helper.startswith(b"\x7fELF"):
        fail("minimal artifact migration helper is not a static guest ELF")
    return filesystem_bytes, packages


def verify_artifact(artifact: Path) -> tuple[int, tuple[str, ...]]:
    try:
        artifact_fd = os.open(artifact, os.O_RDONLY | os.O_CLOEXEC | os.O_NOFOLLOW)
    except OSError as exc:
        fail(f"cannot open minimal rootfs artifact without symlink traversal {artifact}: {exc}")
    try:
        metadata = os.fstat(artifact_fd)
        if not stat.S_ISREG(metadata.st_mode):
            fail("minimal rootfs artifact must be a regular file")
        if not 0 < metadata.st_size <= bounded.MAX_ARTIFACT_BYTES:
            fail("minimal rootfs artifact is empty or exceeds 1 GiB")
        tool = shutil.which("unsquashfs")
        if tool is None:
            fail("unsquashfs is required for minimal rootfs verification")
        descriptor_path = Path(f"/proc/self/fd/{artifact_fd}")
        return verify_open_artifact(tool, descriptor_path, artifact_fd, metadata.st_size)
    finally:
        os.close(artifact_fd)


def verify_apk(apk: Path, require_rootfs: bool) -> bool:
    return bounded.verify_packaged_rootfs(apk, require_rootfs, verify_artifact)


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifact", nargs="?", type=Path)
    parser.add_argument("--apk", type=Path, help="APK whose exact rootfs entry is verified")
    parser.add_argument("--require-rootfs", action="store_true")
    args = parser.parse_args(argv)
    if args.artifact is not None and args.apk is not None:
        parser.error("artifact and --apk are mutually exclusive")
    if args.require_rootfs and args.apk is None:
        parser.error("--require-rootfs requires --apk")
    return args


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    repo_root = Path(__file__).resolve().parent.parent
    try:
        verify_source(repo_root)
        if args.artifact is not None:
            filesystem_bytes, packages = verify_artifact(args.artifact)
            print(
                f"minimal guest artifact verification passed: {len(packages)} resolved packages, "
                f"{filesystem_bytes} SquashFS bytes"
            )
            print("resolved packages: " + " ".join(packages))
        elif args.apk is not None:
            present = verify_apk(args.apk, args.require_rootfs)
            print("minimal guest packaged APK verification passed" +
                  ("" if present else ": rootfs entry absent (allowed for debug)"))
    except VerificationError as exc:
        print(f"minimal guest verification failed: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
