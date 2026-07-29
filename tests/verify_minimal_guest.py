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
MAX_RESOLVED_PACKAGES = 512
MAX_PACKAGE_DATABASE_BYTES = 2 * 1024 * 1024
EXPECTED_EXPLICIT_PACKAGES = (
    "alpine-base",
    "openrc",
    "busybox-openrc",
    "iproute2",
    "dropbear",
    "dropbear-openrc",
    "ca-certificates",
)
REQUIRED_RESOLVED_PACKAGES = frozenset((*EXPECTED_EXPLICIT_PACKAGES, "apk-tools"))
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
    "bin/mktemp",
    "sbin/apk",
    "sbin/ip",
    "sbin/mkswap",
    "sbin/swapon",
    "usr/sbin/dropbear",
    "usr/bin/pgrep",
    "usr/bin/sort",
    "etc/ssl/certs/ca-certificates.crt",
    "etc/inittab",
    "etc/conf.d/dropbear",
    "etc/podroid/forwards.conf",
    "etc/podroid/system-version",
    "etc/podroid/migrations/31.sh",
    "usr/local/bin/podroid-getty",
    "usr/local/bin/podroid-login",
    "usr/local/bin/podroid-resize",
    "usr/local/bin/podroid-hostd",
    "usr/local/bin/podroid-vsock-agent",
    "usr/local/bin/podroid-overlay-normalize",
    "usr/local/bin/podroid-notify",
    "usr/local/bin/podroid-forward",
)
FORBIDDEN_PACKAGE_PATTERNS = tuple(
    re.compile(pattern)
    for pattern in (
        r"^(?:docker|podman|lxc)(?:$|-)",
        r"^(?:containerd|runc|crun|conmon|buildah|skopeo)$",
        r"^(?:fuse-overlayfs|slirp4netns|netavark|aardvark-dns|cni-plugins)$",
        r"^(?:tigervnc|x11vnc|tightvnc|pulseaudio)(?:$|-)",
        r"^(?:xorg|xserver|xf86|mesa|wayland)(?:$|-)",
        r"^libx(?:11|au|aw|cb|composite|cursor|damage|dmcp|ext|fixes|font|ft|inerama|kbfile|mu|pm|present|randr|render|res|scrnsaver|shmfence|t|v|xf86|xkb|xklavier)(?:$|-)",
        r"^(?:font-|fontconfig|freetype|ttf-|otf-)",
        r"^(?:xfce[0-9]*|gnome|kde|plasma|mate|lxde|lxqt|sway)(?:$|-)",
    )
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


def active_fields(data: bytes) -> list[list[bytes]]:
    return [line.split() for line in bounded.active_config_lines(data)]


def require_bytes(data: bytes, expected: bytes, message: str) -> None:
    if expected not in data:
        fail(message)


def verify_migration_policy(migration: bytes, migrate_service: bytes) -> None:
    active_migration = b"\n".join(bounded.active_config_lines(migration))
    for sensitive in (b"/mnt/persist/", b"/var/lib/", b"rm -r", b"find "):
        if sensitive in active_migration:
            fail("migration 31 may remove system paths only, never persistent/user data trees")
    if active_migration.count(b'rm -f -- "${root}${path}"') != 1:
        fail("migration 31 must use one idempotent, non-recursive removal operation")
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
        require_bytes(active_migration, obsolete_path, f"migration 31 omits stale path {obsolete_path.decode()}")
    if b"failed (continuing)" in migrate_service:
        fail("migration runner still advances after a failed migration")
    require_bytes(migrate_service, b"MAX_MIGRATIONS=256", "migration enumeration is not bounded")
    require_bytes(migrate_service, b'[ -n "$applied" ] || applied=0', "legacy installs without a marker skip migration 31")
    require_bytes(migrate_service, b'mktemp "${APPLIED_FILE}.tmp.XXXXXX"', "applied-version writes are vulnerable to stale temporary symlinks")
    require_bytes(migrate_service, b'return 1\n            fi', "migration failures are not propagated")


def verify_source(repo_root: Path) -> None:
    manifest_path = repo_root / "build-rootfs/minimal-packages.txt"
    packages = parse_explicit_packages(bounded.read_regular_file(manifest_path, 4096))

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

    require_bytes(build_script, b'MINIMAL_PACKAGES=/work/minimal-packages.txt', "rootfs build does not consume the reviewed package manifest")
    require_bytes(build_script, b'--initdb add "$@"', "rootfs build does not pass only parsed manifest entries to apk")
    require_bytes(dockerfile, b"COPY minimal-packages.txt /work/minimal-packages.txt", "rootfs Dockerfile does not copy the reviewed package manifest")
    for obsolete in (b"podroid-x11", b"podroid-backup", b"podroid-update-stats", b"storage.conf", b"logo.png"):
        if obsolete in build_script:
            fail(f"rootfs build still references obsolete source: {obsolete.decode()}")

    version_codes = re.findall(rb"(?m)^\s*versionCode\s*=\s*(\d+)\s*$", gradle)
    if version_codes != [b"31"]:
        fail("Android versionCode must be exactly 31 for the minimal guest migration")

    runlevel_line = next(
        (line for line in bounded.active_config_lines(build_script) if line.startswith(b"for svc in podroid-migrate ")),
        None,
    )
    if runlevel_line is None:
        fail("minimal OpenRC runlevel declaration is missing")
    declared_services = tuple(runlevel_line.removeprefix(b"for svc in ").removesuffix(b"; do").decode().split())
    if declared_services != REQUIRED_SERVICES:
        fail("minimal OpenRC runlevel differs from the required boot contract")

    if active_fields(forwards) != [[b"9100", b"ctl"]]:
        fail("minimal guest must seed only the AVF control forward on port 9100")

    verify_migration_policy(migration, migrate_service)

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
    require_bytes(inittab, b"/sbin/openrc default", "OpenRC default runlevel is not PID 1 boot flow")
    require_bytes(inittab, b"hvc0::respawn:/usr/local/bin/podroid-getty hvc0", "app-owned hvc0 getty is missing")
    if bounded.active_config_lines(dropbear) != [b'DROPBEAR_OPTS="-s"']:
        fail("Dropbear must remain public-key-only")

    print(f"minimal guest source verification passed: {len(packages)} explicit packages")


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
    missing = sorted(REQUIRED_RESOLVED_PACKAGES.difference(packages))
    if missing:
        fail(f"artifact package closure omits required packages: {', '.join(missing)}")
    for package in packages:
        if any(pattern.search(package) for pattern in FORBIDDEN_PACKAGE_PATTERNS):
            fail(f"artifact package closure contains forbidden package: {package}")


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

    for service in REQUIRED_SERVICES:
        runlevel_path = f"etc/runlevels/default/{service}"
        mode, target = paths[runlevel_path]
        if not mode.startswith(b"l") or target != f"/etc/init.d/{service}":
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
    if system_version != b"31\n":
        fail("minimal artifact system-version is not 31")
    if active_fields(forwards) != [[b"9100", b"ctl"]]:
        fail("minimal artifact seeds a forbidden display/audio/default forward")
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
    verify_migration_policy(migration_31, migrate)
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


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifact", nargs="?", type=Path)
    return parser.parse_args(argv)


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
    except VerificationError as exc:
        print(f"minimal guest verification failed: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
