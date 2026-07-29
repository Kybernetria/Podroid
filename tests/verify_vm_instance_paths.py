#!/usr/bin/env python3
"""Reject active Android VM paths that bypass files/instances/default."""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path
from typing import NamedTuple

MAX_SCANNED_FILES = 5_000
MAX_SOURCE_BYTES = 2 * 1024 * 1024
HISTORICAL_DOCS = Path("docs/baseline")
VM_FILE_NAME = (
    r"(?:storage\.img|console\.log|[A-Za-z0-9_-]+\.sock|"
    r"vmlinuz-virt(?:\.raw)?|initrd\.img|alpine-rootfs\.squashfs|"
    r"\.assets_stamp|efi-virtio\.rom|keymaps|qemu)"
)
FILES_ROOT = (
    r"(?:\bfilesDir\b|\bfilesDirectory\b|\bfilesRoot\b|getFilesDir\(\)|"
    r"(?:applicationContext|context)\.filesDir)"
)
FILES_ROOT_ACCESSORS = r"(?:(?:\.absoluteFile|\.canonicalFile)|(?:\.path|\.absolutePath)|\.toPath\(\))*"

FORBIDDEN_PATTERNS = (
    re.compile(rf"(?<![A-Za-z0-9_.-])files[\\/]+(?P<name>{VM_FILE_NAME})(?![A-Za-z0-9_.-])"),
    re.compile(rf"\bfilesDir[\\/]+(?P<name>{VM_FILE_NAME})(?![A-Za-z0-9_.-])"),
    re.compile(
        rf"{FILES_ROOT}{FILES_ROOT_ACCESSORS}\s*\.resolve\(\s*"
        rf"[\"'](?P<name>{VM_FILE_NAME})[\"']",
        re.DOTALL,
    ),
    re.compile(
        rf"(?:new\s+)?(?:java\.io\.)?File\(\s*{FILES_ROOT}{FILES_ROOT_ACCESSORS}\s*,\s*"
        rf"[\"'](?P<name>{VM_FILE_NAME})[\"']",
        re.DOTALL,
    ),
    re.compile(
        rf"(?:java\.nio\.file\.)?Paths\.get\(\s*{FILES_ROOT}{FILES_ROOT_ACCESSORS}\s*,\s*"
        rf"[\"'](?P<name>{VM_FILE_NAME})[\"']",
        re.DOTALL,
    ),
    re.compile(
        rf"{FILES_ROOT}(?:\.path|\.absolutePath)?\s*\+\s*"
        rf"[\"'][\\/]+(?P<name>{VM_FILE_NAME})(?![A-Za-z0-9_.-])",
        re.DOTALL,
    ),
    re.compile(
        rf"\$\{{?(?:filesDir|filesDirectory|filesRoot|(?:applicationContext|context)\.filesDir)"
        rf"(?:\.path|\.absolutePath)?\}}?[\\/]+(?P<name>{VM_FILE_NAME})(?![A-Za-z0-9_.-])"
    ),
)


class VerificationError(RuntimeError):
    pass


class Violation(NamedTuple):
    path: Path
    line: int
    match: str


def is_historical_doc(relative_path: Path) -> bool:
    return relative_path == HISTORICAL_DOCS or HISTORICAL_DOCS in relative_path.parents


def is_active_source(relative_path: Path) -> bool:
    if is_historical_doc(relative_path):
        return False
    if relative_path.suffix == ".kt":
        return relative_path.parts[:3] == ("app", "src", "main")
    if relative_path.suffix == ".kts":
        return True
    if relative_path.suffix in {".sh", ".bash"}:
        return True
    return relative_path.suffix in {".md", ".html"}


def discover_active_sources(repo_root: Path) -> tuple[Path, ...]:
    skipped_directories = {".git", ".gradle", ".idea", ".worktrees", "build"}
    sources: list[Path] = []
    for path in repo_root.rglob("*"):
        relative = path.relative_to(repo_root)
        if any(part in skipped_directories for part in relative.parts):
            continue
        if path.is_file() and is_active_source(relative):
            sources.append(relative)
            if len(sources) > MAX_SCANNED_FILES:
                raise VerificationError(f"active source scan exceeds {MAX_SCANNED_FILES} files")
    return tuple(sorted(sources))


def scan_text(relative_path: Path, text: str) -> tuple[Violation, ...]:
    violations: list[Violation] = []
    seen: set[tuple[int, int]] = set()
    for pattern in FORBIDDEN_PATTERNS:
        for match in pattern.finditer(text):
            location = (match.start(), match.end())
            if location in seen:
                continue
            seen.add(location)
            violations.append(
                Violation(
                    path=relative_path,
                    line=text.count("\n", 0, match.start()) + 1,
                    match=" ".join(match.group(0).split()),
                )
            )
    return tuple(violations)


def verify_repository(repo_root: Path) -> None:
    root = repo_root.resolve()
    violations: list[Violation] = []
    for relative_path in discover_active_sources(root):
        path = root / relative_path
        size = path.stat().st_size
        if size > MAX_SOURCE_BYTES:
            raise VerificationError(f"active source exceeds 2 MiB bound: {relative_path}")
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError as error:
            raise VerificationError(f"active source is not UTF-8: {relative_path}") from error
        violations.extend(scan_text(relative_path, text))
    if violations:
        details = "\n".join(
            f"  {violation.path}:{violation.line}: {violation.match}"
            for violation in sorted(violations, key=lambda item: (str(item.path), item.line, item.match))
        )
        raise VerificationError(
            "active root-level VM paths found; use files/instances/default or VmPaths:\n" + details
        )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "repo_root",
        nargs="?",
        type=Path,
        default=Path(__file__).resolve().parent.parent,
    )
    args = parser.parse_args(argv)
    try:
        verify_repository(args.repo_root)
    except (OSError, VerificationError) as error:
        print(f"VM instance path verification failed: {error}", file=sys.stderr)
        return 1
    print("VM instance path verification passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
