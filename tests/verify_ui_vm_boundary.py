#!/usr/bin/env python3
"""Fail closed when Android UI crosses the local VM Binder boundary."""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

# UI may depend on the Binder client and immutable/request DTOs only. Adding a
# boundary import requires an explicit review here rather than silently widening
# access to an entire implementation package.
ALLOWED_BOUNDARY_IMPORTS = {
    "com.excp.podroid.service.VmBackendProbe",
    "com.excp.podroid.service.VmBindingState",
    "com.excp.podroid.service.VmServiceClient",
    "com.excp.podroid.service.VmUiState",
    "com.excp.podroid.vm.ConsoleLogRequest",
    "com.excp.podroid.vm.EngineSelection",
    "com.excp.podroid.vm.SensitiveConsolePolicy",
    "com.excp.podroid.vm.VmDiagnosticsRequest",
    "com.excp.podroid.vm.VmFailureAdvice",
    "com.excp.podroid.vm.VmRemovePolicy",
    "com.excp.podroid.vm.vmFailureAdvice",
}
BOUNDARY_IMPORT_PREFIXES = (
    "com.excp.podroid.engine",
    "com.excp.podroid.service",
    "com.excp.podroid.vm",
)
IMPORT = re.compile(
    r"^\s*import\s+(com\.excp\.podroid\.(?:engine|service|vm)(?:\.(?:[A-Za-z_]\w*|\*))+)(?:\s+as\s+\w+)?\s*$",
    re.MULTILINE,
)
BOUNDARY_REFERENCE = re.compile(
    r"\bcom\.excp\.podroid\.(?:engine(?:\.[A-Za-z_]\w*)*|(?:service|vm)\.[A-Za-z_]\w*)"
)

# These patterns inspect executable symbols with comments and strings removed.
FORBIDDEN_SYMBOLS = {
    "service implementation reference": re.compile(r"\bPodroidService\b"),
    "direct VmManager dependency": re.compile(r"\bVmManager\b"),
    "direct VmPaths dependency": re.compile(r"\b(?:VmPaths|vmPaths)\b"),
    "fully qualified engine reference": re.compile(r"\bcom\.excp\.podroid\.engine(?:\.[A-Za-z_]\w*)+"),
    "direct engine capability": re.compile(r"\bVmEngine\b"),
    "backend capability type": re.compile(r"\b(?:Qmp|Qemu|Avf)[A-Za-z0-9_]*\b"),
    "direct service lifecycle API": re.compile(
        r"\b(?:bindService|unbindService|startForegroundService|startService|stopService)\s*\("
        r"|\bPendingIntent\s*\.\s*getService\s*\("
        r"|\bIntent\s*\([^\n;]*\b[A-Za-z_]\w*Service\s*::\s*class\.java"
    ),
    "direct VM state member": re.compile(
        r"\b(?:storageImage|consoleLog|instanceDirectory|rawKernel|qmpSocket|terminalSocket)\b"
    ),
    "application-data reset bypass": re.compile(r"\bclearApplicationUserData\s*\("),
    "boundary class reflection": re.compile(
        r"\bClass\s*\.\s*forName\s*\(|(?:\bclassLoader\b|\bgetClassLoader\s*\(\s*\))"
        r"\s*\.\s*loadClass\s*\(|\.\s*loadClass\s*\("
    ),
}

# Raw package literals are executable boundary references too. Inspect them
# before strings are stripped so reflection cannot hide implementation classes.
FORBIDDEN_RAW_LITERALS = {
    "boundary package string literal": re.compile(
        r"[\"'][^\"']*\bcom\.excp\.podroid\.(?:engine|service|vm)"
        r"(?:\.[A-Za-z_]\w*)*\.?[^\"']*[\"']"
    ),
}

# These patterns intentionally retain string literals. Otherwise constructions
# such as filesDir.resolve("instances").resolve("default") evade the check.
FORBIDDEN_PATHS = {
    "internal app-data path construction": re.compile(
        r"\b(?:filesDir|dataDir|noBackupFilesDir)\b|"
        r"\b(?:getFilesDir|getDataDir|getNoBackupFilesDir|getDir)\s*\(|"
        r"/data/(?:data|user)/"
    ),
    "path resolve bypass": re.compile(r"\bresolve(?:Sibling)?\s*\("),
    "VM path literal": re.compile(
        r"[\"'][^\"']*(?:instances(?:[/\\]default)?|default[/\\](?:storage|console)|storage\.img|"
        r"console\.log|alpine-rootfs\.squashfs|vmlinuz-virt|initrd\.img|"
        r"(?:qmp|terminal|ctrl|serial|host)\.sock)[^\"']*[\"']"
    ),
    "embedded default instance path": re.compile(r"instances\s*/\s*default"),
}

# The one reviewed UI-owned app-data file. It is a diagnostic export, not VM
# state; all VM log content reaches it through VmServiceClient DTOs.
ALLOWED_PATH_EXPRESSIONS = (
    re.compile(r"\bFile\s*\(\s*context\.filesDir\s*,\s*[\"']log\.txt[\"']\s*\)"),
)
ALLOWED_PATH_EXPRESSIONS_BY_SUFFIX = {
    # Reviewed UI asset extraction; assetPath is selected from this theme
    # module's fixed font asset names and never from VM identity or state.
    "theme/PodroidTokens.kt": (
        re.compile(r"\bFile\s*\(\s*context\.filesDir\s*,\s*assetPath\s*\)"),
    ),
}


def _without_comments(text: str) -> str:
    return re.sub(r"/\*.*?\*/|//[^\n]*", "", text, flags=re.S)


def _without_comments_or_strings(text: str) -> str:
    return re.sub(
        r'/\*.*?\*/|//[^\n]*|""".*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'',
        "",
        text,
        flags=re.S,
    )


def verify(root: Path) -> list[str]:
    failures: list[str] = []
    for path in sorted(root.rglob("*.kt")):
        text = path.read_text(encoding="utf-8")
        uncommented = _without_comments(text)
        # Kotlin permits backtick-escaped identifiers in imports and qualified
        # references. Normalize ordinary escaped identifiers before matching so
        # they cannot bypass the boundary package checks.
        normalized = re.sub(r"`([A-Za-z_]\w*)`", r"\1", uncommented)
        for imported in IMPORT.findall(normalized):
            if imported.startswith(BOUNDARY_IMPORT_PREFIXES) and imported not in ALLOWED_BOUNDARY_IMPORTS:
                failures.append(f"{path}: boundary import is not allowlisted: {imported}")

        for label, pattern in FORBIDDEN_RAW_LITERALS.items():
            if pattern.search(normalized):
                failures.append(f"{path}: {label}")

        symbols = _without_comments_or_strings(normalized)
        for reference in BOUNDARY_REFERENCE.findall(symbols):
            if reference.startswith("com.excp.podroid.engine") or reference not in ALLOWED_BOUNDARY_IMPORTS:
                failures.append(f"{path}: boundary reference is not allowlisted: {reference}")
        for label, pattern in FORBIDDEN_SYMBOLS.items():
            if pattern.search(symbols):
                failures.append(f"{path}: {label}")

        paths = normalized
        for allowed in ALLOWED_PATH_EXPRESSIONS:
            paths = allowed.sub("", paths)
        normalized_path = path.as_posix()
        for suffix, expressions in ALLOWED_PATH_EXPRESSIONS_BY_SUFFIX.items():
            if normalized_path.endswith(suffix):
                for allowed in expressions:
                    paths = allowed.sub("", paths)
        for label, pattern in FORBIDDEN_PATHS.items():
            if pattern.search(paths):
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
