#!/usr/bin/env python3
"""Fail closed when Android UI crosses the local VM Binder boundary."""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

FORBIDDEN_ENGINE_IMPORT = re.compile(r"^\s*import\s+com\.excp\.podroid\.engine(?:\.|$)", re.MULTILINE)
FORBIDDEN_PATTERNS = {
    "static PodroidService lifecycle call": re.compile(r"\bPodroidService\.(?:start|stop|restart)\s*\("),
    "direct VmManager dependency": re.compile(r"\bVmManager\b"),
    "direct VmPaths dependency": re.compile(r"\bVmPaths\b|\bvmPaths\b"),
    "direct VM state-file access": re.compile(r"\b(?:storageImage|consoleLog|instanceDirectory)\b|instances/default"),
    "direct backend capability": re.compile(r"\b(?:VmEngine|QmpClient|QemuEngine|AvfEngine|qmpController)\b"),
    "application-data reset bypass": re.compile(r"\bclearApplicationUserData\s*\("),
}


def verify(root: Path) -> list[str]:
    failures: list[str] = []
    for path in sorted(root.rglob("*.kt")):
        text = path.read_text(encoding="utf-8")
        if FORBIDDEN_ENGINE_IMPORT.search(text):
            failures.append(f"{path}: UI must not import engine packages")
        # Remove comments and strings before token checks to avoid diagnostic
        # prose/log tags becoming false positives. Kotlin raw strings are
        # included; this is deliberately conservative rather than a full parser.
        code = re.sub(r'/\*.*?\*/|//[^\n]*|""".*?"""|"(?:\\.|[^"\\])*"', "", text, flags=re.S)
        for label, pattern in FORBIDDEN_PATTERNS.items():
            if pattern.search(code):
                failures.append(f"{path}: {label}")
    return failures


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", nargs="?", default="app/src/main/java/com/excp/podroid/ui")
    args = parser.parse_args()
    failures = verify(Path(args.root))
    if failures:
        print("UI VM boundary verification failed:", file=sys.stderr)
        for failure in failures:
            print(f"  - {failure}", file=sys.stderr)
        return 1
    print("UI VM boundary verification passed")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
