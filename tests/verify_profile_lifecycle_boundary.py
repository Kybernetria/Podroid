#!/usr/bin/env python3
"""Fail-closed static checks for downloadable-profile lifecycle authority."""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PRODUCTION = ROOT / "app/src/main/java/com/excp/podroid"
STORE_ALLOWED = {
    "profiles/DownloadableProfileRuntime.kt",
    "vm/VmManager.kt",
    "engine/EngineModule.kt",
}
LIFECYCLE_AUTHORITY_ALLOWED = {
    "profiles/DownloadableProfileRuntime.kt",
    "profiles/ProfileSettingsWorkflow.kt",
    "vm/VmManager.kt",
    "engine/EngineModule.kt",
}
RAW_MUTATION_ALLOWED = {
    "profiles/DownloadableProfileRuntime.kt",  # manager-only adapter implementation
    "vm/VmManager.kt",
}


def verify(root: Path = PRODUCTION) -> list[str]:
    failures: list[str] = []
    for path in sorted(root.rglob("*.kt")):
        relative = path.relative_to(root).as_posix()
        text = path.read_text(encoding="utf-8")
        if "ProfileLifecycleStore" in text and relative not in STORE_ALLOWED:
            failures.append(f"{relative}: accesses manager-only ProfileLifecycleStore")
        if "ProfileLifecycleOperations" in text and relative not in LIFECYCLE_AUTHORITY_ALLOWED:
            failures.append(f"{relative}: accesses local profile lifecycle authority")
        raw_calls = re.findall(
            r"(?:repository|requireConfigured\(\)|profileLifecycleStore|store)\s*\.\s*"
            r"(?:activate|rollback|issueDataDeletionConfirmation|collectGarbage)\s*\(",
            text,
        )
        if raw_calls and relative not in RAW_MUTATION_ALLOWED:
            failures.append(f"{relative}: calls raw profile lifecycle mutation")
        if "ProfileRepository" in text and relative not in {
            "profiles/DownloadableProfileRuntime.kt",
            "profiles/ProfileRepository.kt",
            "profiles/ProfileBootArtifactSource.kt",  # read-only testable boot adapter
        }:
            failures.append(f"{relative}: accesses the raw profile repository")
        if "openConfiguredRepository" in text and relative != "profiles/DownloadableProfileRuntime.kt":
            failures.append(f"{relative}: opens the raw profile repository environment")

    runtime = (root / "profiles/DownloadableProfileRuntime.kt").read_text(encoding="utf-8")
    match = re.search(
        r"class DownloadableProfileRuntime\b(?P<body>.*?)(?=\ninternal object DownloadedProfileLineageGuard)",
        runtime,
        re.DOTALL,
    )
    if not match:
        failures.append("DownloadableProfileRuntime boundary could not be located")
    else:
        body = match.group("body")
        forbidden = [
            ": ProfileLifecycleStore",
            "override suspend fun install(",
            "override suspend fun rollback(",
            "fun issueDataDeletionConfirmation(",
            "fun collectGarbage(",
        ]
        for token in forbidden:
            if token in body:
                failures.append(f"DownloadableProfileRuntime exposes forbidden lifecycle member: {token}")
    return failures


def main() -> int:
    failures = verify()
    if failures:
        print("Profile lifecycle boundary verification failed:", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1
    print("Profile lifecycle boundary verification passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
