#!/usr/bin/env python3
"""Build and verify Podroid's pinned debug-only Android libtailscale artifacts."""

from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path
from typing import Callable, Sequence

MAX_METADATA_BYTES = 64 * 1024
MAX_APK_BYTES = 512 * 1024 * 1024
MAX_APK_ENTRIES = 20_000
MAX_NATIVE_ENTRY_BYTES = 128 * 1024 * 1024
MAX_TOTAL_NATIVE_BYTES = 256 * 1024 * 1024
EXPECTED_ABI = "arm64-v8a"
EXPECTED_MACHINE = 183  # EM_AARCH64
PT_LOAD = 1
PT_DYNAMIC = 2
DT_NULL = 0
DT_NEEDED = 1
DT_STRTAB = 5
DT_STRSZ = 10
ANDROID_XML = 0x0003
STRING_POOL = 0x0001
UTF8_FLAG = 0x00000100


class VerificationError(RuntimeError):
    pass


Runner = Callable[[Sequence[str], Path, int, dict[str, str] | None], str]


def run_command(
    command: Sequence[str],
    cwd: Path,
    timeout_seconds: int = 30,
    env: dict[str, str] | None = None,
) -> str:
    try:
        result = subprocess.run(
            list(command),
            cwd=cwd,
            env=env,
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            timeout=timeout_seconds,
        )
    except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
        stderr = getattr(error, "stderr", "") or ""
        detail = stderr.strip()[-2_000:]
        raise VerificationError(
            f"command failed: {' '.join(command)}" + (f"\n{detail}" if detail else "")
        ) from error
    return result.stdout.strip()


def read_bounded(path: Path, maximum_bytes: int = MAX_METADATA_BYTES) -> bytes:
    with path.open("rb") as stream:
        data = stream.read(maximum_bytes + 1)
    if len(data) > maximum_bytes:
        raise VerificationError(f"file exceeds {maximum_bytes} byte bound: {path}")
    return data


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def load_pin(repo_root: Path) -> dict[str, object]:
    path = repo_root / "third_party/libtailscale-pin.json"
    try:
        pin = json.loads(read_bounded(path))
    except (OSError, json.JSONDecodeError) as error:
        raise VerificationError(f"invalid pin metadata: {path}: {error}") from error
    if not isinstance(pin, dict):
        raise VerificationError("pin metadata must be a JSON object")

    required = {
        "repository": str,
        "commit": str,
        "tree": str,
        "license": str,
        "licenseSha256": str,
        "goVersion": str,
        "tailscaleModule": str,
        "androidNdkVersion": str,
        "androidApi": int,
        "supportedAbi": list,
        "ptLoadAlignmentBytes": int,
        "debugPackaging": bool,
        "releasePackaging": bool,
        "clangVersion": str,
        "officialDtNeeded": list,
        "shimDtNeeded": list,
    }
    for key, expected_type in required.items():
        if key not in pin or not isinstance(pin[key], expected_type):
            raise VerificationError(f"pin field {key!r} must be {expected_type.__name__}")

    if not re.fullmatch(r"[0-9a-f]{40}", str(pin["commit"])):
        raise VerificationError("pin commit must be a full lowercase SHA-1")
    if not re.fullmatch(r"[0-9a-f]{40}", str(pin["tree"])):
        raise VerificationError("pin tree must be a full lowercase SHA-1")
    if not re.fullmatch(r"[0-9a-f]{64}", str(pin["licenseSha256"])):
        raise VerificationError("licenseSha256 must be a lowercase SHA-256")
    if pin["repository"] != "https://github.com/tailscale/libtailscale.git":
        raise VerificationError("unexpected libtailscale repository")
    if pin["license"] != "BSD-3-Clause":
        raise VerificationError("unexpected libtailscale license identifier")
    if pin["goVersion"] != "1.25.5":
        raise VerificationError("the Android build requires Go 1.25.5")
    if pin["tailscaleModule"] != "tailscale.com@v1.94.1":
        raise VerificationError("unexpected tailscale.com module pin")
    if pin["androidNdkVersion"] != "28.2.13676358" or pin["androidApi"] != 26:
        raise VerificationError("the Android build requires NDK 28.2.13676358 API 26")
    if pin["supportedAbi"] != [EXPECTED_ABI]:
        raise VerificationError("supportedAbi must contain only arm64-v8a")
    if pin["ptLoadAlignmentBytes"] != 16_384:
        raise VerificationError("PT_LOAD alignment must be 16384 bytes")
    if pin["debugPackaging"] is not True or pin["releasePackaging"] is not False:
        raise VerificationError("libtailscale packaging must be debug-only")
    if not str(pin["clangVersion"]).startswith("Android ("):
        raise VerificationError("unexpected pinned Android clang version")
    if pin["officialDtNeeded"] != ["liblog.so", "libdl.so", "libc.so"]:
        raise VerificationError("unexpected official libtailscale DT_NEEDED contract")
    if pin["shimDtNeeded"] != ["libtailscale.so", "libc.so"]:
        raise VerificationError("unexpected JNI shim DT_NEEDED contract")
    return pin


def validate_go_mod(pin: dict[str, object], go_mod_text: str) -> None:
    if not re.search(r"(?m)^module github\.com/tailscale/libtailscale\s*$", go_mod_text):
        raise VerificationError("unexpected libtailscale Go module path")
    if not re.search(rf"(?m)^go {re.escape(str(pin['goVersion']))}\s*$", go_mod_text):
        raise VerificationError("go.mod does not declare the pinned Go version")
    module_path, module_version = str(pin["tailscaleModule"]).split("@", 1)
    requirement = (
        rf"(?m)^\s*(?:require\s+)?{re.escape(module_path)}\s+"
        rf"{re.escape(module_version)}(?:\s|$)"
    )
    if not re.search(requirement, go_mod_text):
        raise VerificationError("go.mod does not require the pinned tailscale.com module")


def verify_source_pin(
    repo_root: Path,
    pin: dict[str, object],
    runner: Runner = run_command,
) -> None:
    source = repo_root / "third_party/libtailscale"
    if not source.is_dir():
        raise VerificationError("libtailscale submodule is not initialized")

    head = runner(["git", "rev-parse", "HEAD"], source, 30, None)
    tree = runner(["git", "rev-parse", "HEAD^{tree}"], source, 30, None)
    if head != pin["commit"]:
        raise VerificationError(f"submodule commit mismatch: expected {pin['commit']}, got {head}")
    if tree != pin["tree"]:
        raise VerificationError(f"submodule tree mismatch: expected {pin['tree']}, got {tree}")

    index_line = runner(
        ["git", "ls-files", "--stage", "--", "third_party/libtailscale"],
        repo_root,
        30,
        None,
    )
    expected_index = f"160000 {pin['commit']} 0\tthird_party/libtailscale"
    if index_line != expected_index:
        raise VerificationError("parent repository gitlink does not match the reviewed pin")

    status = runner(
        ["git", "status", "--porcelain=v1", "--untracked-files=all"], source, 30, None
    )
    if status:
        raise VerificationError("official libtailscale source is dirty; refusing to build")
    ignored = runner(
        ["git", "ls-files", "--others", "--ignored", "--exclude-standard"],
        source,
        30,
        None,
    )
    if ignored:
        raise VerificationError("official libtailscale checkout contains ignored files; refusing to build")

    license_bytes = read_bounded(source / "LICENSE")
    actual_license_hash = sha256_bytes(license_bytes)
    if actual_license_hash != pin["licenseSha256"]:
        raise VerificationError("libtailscale LICENSE hash does not match reviewed metadata")

    go_mod = read_bounded(source / "go.mod").decode("utf-8")
    validate_go_mod(pin, go_mod)


def resolve_executable(value: str) -> Path:
    if os.sep in value:
        path = Path(value).expanduser().resolve()
    else:
        located = shutil.which(value)
        if located is None:
            raise VerificationError(f"required executable is unavailable: {value}")
        path = Path(located).resolve()
    if not path.is_file() or not os.access(path, os.X_OK):
        raise VerificationError(f"required executable is not executable: {path}")
    return path


def resolve_ndk_home(pin: dict[str, object], repo_root: Path) -> Path:
    candidates: list[Path] = []
    for variable in ("PODROID_ANDROID_NDK_HOME", "ANDROID_NDK_HOME"):
        if os.environ.get(variable):
            candidates.append(Path(os.environ[variable]).expanduser())
    for variable in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        if os.environ.get(variable):
            candidates.append(Path(os.environ[variable]).expanduser() / "ndk" / str(pin["androidNdkVersion"]))
    local_properties = repo_root / "local.properties"
    if local_properties.is_file():
        for line in local_properties.read_text(encoding="utf-8").splitlines():
            if line.startswith("sdk.dir="):
                sdk_dir = line.split("=", 1)[1].replace("\\:", ":").replace("\\\\", "\\")
                candidates.append(Path(sdk_dir) / "ndk" / str(pin["androidNdkVersion"]))
    for candidate in candidates:
        resolved = candidate.resolve()
        if resolved.is_dir():
            return resolved
    raise VerificationError(
        "NDK 28.2.13676358 not found; set PODROID_ANDROID_NDK_HOME to its absolute directory"
    )


def verify_toolchains(
    repo_root: Path,
    pin: dict[str, object],
    runner: Runner = run_command,
) -> tuple[Path, Path, str]:
    go = resolve_executable(os.environ.get("PODROID_GO", "go"))
    go_env = os.environ.copy()
    go_env["GOTOOLCHAIN"] = "local"
    go_version = runner([str(go), "env", "GOVERSION"], repo_root, 30, go_env)
    if go_version != f"go{pin['goVersion']}":
        raise VerificationError(f"Go toolchain mismatch: expected go{pin['goVersion']}, got {go_version}")

    ndk_home = resolve_ndk_home(pin, repo_root)
    properties = read_bounded(ndk_home / "source.properties").decode("utf-8")
    revision = re.search(r"(?m)^Pkg\.Revision\s*=\s*(\S+)\s*$", properties)
    if revision is None or revision.group(1) != pin["androidNdkVersion"]:
        raise VerificationError("Android NDK source.properties revision mismatch")

    compiler_name = f"aarch64-linux-android{pin['androidApi']}-clang"
    clang = ndk_home / "toolchains/llvm/prebuilt/linux-x86_64/bin" / compiler_name
    if not clang.is_file() or not os.access(clang, os.X_OK):
        raise VerificationError(f"required NDK compiler is unavailable: {clang}")
    clang_version = runner([str(clang), "--version"], repo_root, 30, None)
    clang_cxx = Path(str(clang) + "++")
    if not clang_cxx.is_file() or not os.access(clang_cxx, os.X_OK):
        raise VerificationError(f"required NDK C++ compiler is unavailable: {clang_cxx}")
    clang_cxx_version = runner([str(clang_cxx), "--version"], repo_root, 30, None)
    target = f"Target: aarch64-unknown-linux-android{pin['androidApi']}"
    if target not in clang_version or target not in clang_cxx_version:
        raise VerificationError(f"NDK clang target mismatch; expected {target}")
    first_line = clang_version.splitlines()[0]
    if first_line != pin["clangVersion"] or clang_cxx_version.splitlines()[0] != first_line:
        raise VerificationError("NDK clang version does not match reviewed metadata")
    return go, clang, first_line


def verify_manifest_sources(repo_root: Path) -> None:
    manifests = tuple(repo_root.glob("**/src/*/AndroidManifest.xml"))
    if not manifests:
        raise VerificationError("no Android manifest sources found")
    for manifest in manifests:
        relative = manifest.relative_to(repo_root)
        if any(part in {"build", ".gradle"} for part in relative.parts):
            continue
        text = read_bounded(manifest, 2 * 1024 * 1024).decode("utf-8")
        if "VpnService" in text or "android.permission.BIND_VPN_SERVICE" in text:
            raise VerificationError(f"VpnService is forbidden in manifest source: {relative}")


def verify_pin(
    repo_root: Path,
    require_toolchains: bool,
    runner: Runner = run_command,
) -> dict[str, object]:
    root = repo_root.resolve()
    pin = load_pin(root)
    verify_source_pin(root, pin, runner)
    verify_manifest_sources(root)
    if require_toolchains:
        verify_toolchains(root, pin, runner)
    return pin


def parse_elf(data: bytes, label: str) -> dict[str, object]:
    if len(data) < 64 or data[:4] != b"\x7fELF":
        raise VerificationError(f"{label}: not an ELF file")
    if data[4] != 2 or data[5] != 1:
        raise VerificationError(f"{label}: expected ELF64 little-endian")
    e_type, machine = struct.unpack_from("<HH", data, 16)
    if e_type != 3:
        raise VerificationError(f"{label}: expected ET_DYN shared object")
    if machine != EXPECTED_MACHINE:
        raise VerificationError(f"{label}: expected AArch64, got e_machine={machine}")
    phoff = struct.unpack_from("<Q", data, 32)[0]
    phentsize, phnum = struct.unpack_from("<HH", data, 54)
    if phentsize < 56 or phnum == 0 or phnum > 256:
        raise VerificationError(f"{label}: invalid program header table")
    if phoff + phentsize * phnum > len(data):
        raise VerificationError(f"{label}: truncated program header table")

    loads: list[tuple[int, int, int, int]] = []
    dynamic: tuple[int, int] | None = None
    alignments: list[int] = []
    for index in range(phnum):
        offset = phoff + index * phentsize
        p_type, _flags, p_offset, p_vaddr, _paddr, p_filesz, _memsz, p_align = struct.unpack_from(
            "<IIQQQQQQ", data, offset
        )
        if p_offset + p_filesz > len(data):
            raise VerificationError(f"{label}: program segment exceeds file")
        if p_type == PT_LOAD:
            if p_align != 16_384:
                raise VerificationError(f"{label}: PT_LOAD alignment is {p_align}, expected 16384")
            if (p_vaddr - p_offset) % p_align != 0:
                raise VerificationError(f"{label}: incongruent PT_LOAD offset/address")
            loads.append((p_offset, p_vaddr, p_filesz, p_align))
            alignments.append(p_align)
        elif p_type == PT_DYNAMIC:
            dynamic = (p_offset, p_filesz)
    if not loads:
        raise VerificationError(f"{label}: no PT_LOAD segments")
    if dynamic is None:
        raise VerificationError(f"{label}: no PT_DYNAMIC segment")

    needed_offsets: list[int] = []
    strtab_vaddr: int | None = None
    strtab_size: int | None = None
    dyn_offset, dyn_size = dynamic
    if dyn_size % 16 != 0:
        raise VerificationError(f"{label}: invalid dynamic table size")
    for offset in range(dyn_offset, dyn_offset + dyn_size, 16):
        tag, value = struct.unpack_from("<QQ", data, offset)
        if tag == DT_NULL:
            break
        if tag == DT_NEEDED:
            needed_offsets.append(value)
        elif tag == DT_STRTAB:
            strtab_vaddr = value
        elif tag == DT_STRSZ:
            strtab_size = value
    if strtab_vaddr is None or strtab_size is None or strtab_size > 16 * 1024 * 1024:
        raise VerificationError(f"{label}: invalid dynamic string table")

    strtab_offset: int | None = None
    for p_offset, p_vaddr, p_filesz, _align in loads:
        if p_vaddr <= strtab_vaddr < p_vaddr + p_filesz:
            strtab_offset = p_offset + (strtab_vaddr - p_vaddr)
            break
    if strtab_offset is None or strtab_offset + strtab_size > len(data):
        raise VerificationError(f"{label}: dynamic string table is outside PT_LOAD")
    strings = data[strtab_offset : strtab_offset + strtab_size]
    needed: list[str] = []
    for offset in needed_offsets:
        if offset >= len(strings):
            raise VerificationError(f"{label}: DT_NEEDED offset outside string table")
        end = strings.find(b"\0", offset)
        if end < 0:
            raise VerificationError(f"{label}: unterminated DT_NEEDED entry")
        try:
            needed.append(strings[offset:end].decode("ascii"))
        except UnicodeDecodeError as error:
            raise VerificationError(f"{label}: non-ASCII DT_NEEDED entry") from error
    return {"machine": machine, "loadAlignments": alignments, "dtNeeded": needed}


def _read_length8(data: bytes, offset: int) -> tuple[int, int]:
    first = data[offset]
    if first & 0x80:
        return ((first & 0x7F) << 8) | data[offset + 1], offset + 2
    return first, offset + 1


def _read_length16(data: bytes, offset: int) -> tuple[int, int]:
    first = struct.unpack_from("<H", data, offset)[0]
    if first & 0x8000:
        second = struct.unpack_from("<H", data, offset + 2)[0]
        return ((first & 0x7FFF) << 16) | second, offset + 4
    return first, offset + 2


def android_manifest_strings(data: bytes) -> tuple[str, ...]:
    if len(data) < 8:
        raise VerificationError("AndroidManifest.xml is truncated")
    chunk_type, header_size, total_size = struct.unpack_from("<HHI", data, 0)
    if chunk_type != ANDROID_XML or header_size != 8 or total_size > len(data):
        raise VerificationError("AndroidManifest.xml is not valid binary XML")
    offset = header_size
    while offset + 8 <= total_size:
        child_type, child_header_size, child_size = struct.unpack_from("<HHI", data, offset)
        if child_size < child_header_size or child_size < 8 or offset + child_size > total_size:
            raise VerificationError("AndroidManifest.xml has an invalid chunk")
        if child_type == STRING_POOL:
            if child_header_size < 28:
                raise VerificationError("AndroidManifest.xml has an invalid string pool")
            string_count, _style_count, flags, strings_start, _styles_start = struct.unpack_from(
                "<IIIII", data, offset + 8
            )
            if string_count > 100_000 or child_header_size + string_count * 4 > child_size:
                raise VerificationError("AndroidManifest.xml string pool exceeds bounds")
            values: list[str] = []
            for index in range(string_count):
                relative = struct.unpack_from("<I", data, offset + child_header_size + index * 4)[0]
                cursor = offset + strings_start + relative
                if cursor >= offset + child_size:
                    raise VerificationError("AndroidManifest.xml string offset exceeds pool")
                chunk_end = offset + child_size
                if flags & UTF8_FLAG:
                    if cursor + 4 > chunk_end:
                        raise VerificationError("AndroidManifest.xml has a truncated UTF-8 length")
                    _utf16_length, cursor = _read_length8(data, cursor)
                    byte_length, cursor = _read_length8(data, cursor)
                    if cursor + byte_length + 1 > chunk_end or data[cursor + byte_length] != 0:
                        raise VerificationError("AndroidManifest.xml has a truncated UTF-8 string")
                    raw = data[cursor : cursor + byte_length]
                    values.append(raw.decode("utf-8"))
                else:
                    if cursor + 4 > chunk_end:
                        raise VerificationError("AndroidManifest.xml has a truncated UTF-16 length")
                    utf16_length, cursor = _read_length16(data, cursor)
                    byte_length = utf16_length * 2
                    if (
                        cursor + byte_length + 2 > chunk_end
                        or data[cursor + byte_length : cursor + byte_length + 2] != b"\0\0"
                    ):
                        raise VerificationError("AndroidManifest.xml has a truncated UTF-16 string")
                    raw = data[cursor : cursor + byte_length]
                    values.append(raw.decode("utf-16le"))
            return tuple(values)
        offset += child_size
    raise VerificationError("AndroidManifest.xml has no string pool")


def verify_no_vpn_manifest(data: bytes) -> None:
    forbidden = ("VpnService", "android.permission.BIND_VPN_SERVICE")
    values = android_manifest_strings(data)
    matches = sorted({value for value in values if any(item in value for item in forbidden)})
    if matches:
        raise VerificationError(f"final APK contains forbidden VpnService manifest strings: {matches}")


def verify_artifact_contract(
    official: bytes,
    shim: bytes,
    pin: dict[str, object],
) -> tuple[dict[str, object], dict[str, object]]:
    official_info = parse_elf(official, "libtailscale.so")
    shim_info = parse_elf(shim, "libpodroid-tailscale-jni.so")
    if official_info["dtNeeded"] != pin["officialDtNeeded"]:
        raise VerificationError(
            f"libtailscale.so DT_NEEDED mismatch: {official_info['dtNeeded']}"
        )
    if shim_info["dtNeeded"] != pin["shimDtNeeded"]:
        raise VerificationError(f"JNI shim DT_NEEDED mismatch: {shim_info['dtNeeded']}")
    return official_info, shim_info


def build_android(repo_root: Path, output_root: Path) -> None:
    root = repo_root.resolve()
    pin = verify_pin(root, require_toolchains=True)
    go, clang, clang_version = verify_toolchains(root, pin)
    source = root / "third_party/libtailscale"
    shim_source = root / "transport/tailscale-android/jni/podroid_tailscale_jni.c"
    if not shim_source.is_file():
        raise VerificationError(f"JNI shim source is missing: {shim_source}")

    output = output_root.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary: Path | None = Path(tempfile.mkdtemp(prefix=f".{output.name}.", dir=output.parent))
    try:
        native_dir = temporary / "jni" / EXPECTED_ABI
        assets_dir = temporary / "assets"
        native_dir.mkdir(parents=True)
        assets_dir.mkdir(parents=True)
        official_path = native_dir / "libtailscale.so"
        shim_path = native_dir / "libpodroid-tailscale-jni.so"

        build_env = os.environ.copy()
        build_env.update(
            {
                "GOOS": "android",
                "GOARCH": "arm64",
                "CGO_ENABLED": "1",
                "CC": str(clang),
                "CXX": str(clang) + "++",
                "CGO_CFLAGS": "-O2 -fPIC",
                "CGO_LDFLAGS": "-Wl,-z,max-page-size=16384",
                "GOTOOLCHAIN": "local",
                "GOFLAGS": "-mod=readonly",
                "GOPRIVATE": "",
                "GONOPROXY": "",
                "GONOSUMDB": "",
                "GOPROXY": "https://proxy.golang.org,direct",
                "GOSUMDB": "sum.golang.org",
            }
        )
        run_command(
            [
                str(go),
                "build",
                "-buildvcs=false",
                "-trimpath",
                "-buildmode=c-shared",
                "-ldflags=-buildid= -linkmode=external -extldflags=-Wl,-z,max-page-size=16384",
                "-o",
                str(official_path),
                ".",
            ],
            source,
            1_800,
            build_env,
        )
        generated_header = official_path.with_suffix(".h")
        if generated_header.exists():
            generated_header.unlink()

        run_command(
            [
                str(clang),
                "-shared",
                "-fPIC",
                "-O2",
                "-Wall",
                "-Wextra",
                "-Werror",
                "-Wl,--no-undefined",
                "-Wl,-z,max-page-size=16384",
                "-Wl,-soname,libpodroid-tailscale-jni.so",
                f"-I{source}",
                str(shim_source),
                f"-L{native_dir}",
                "-Wl,--no-as-needed",
                "-ltailscale",
                "-Wl,--as-needed",
                "-o",
                str(shim_path),
            ],
            root,
            120,
            None,
        )

        official_bytes = official_path.read_bytes()
        shim_bytes = shim_path.read_bytes()
        official_info, shim_info = verify_artifact_contract(official_bytes, shim_bytes, pin)
        provenance = {
            "schemaVersion": 1,
            "source": {
                "repository": pin["repository"],
                "commit": pin["commit"],
                "tree": pin["tree"],
                "license": pin["license"],
                "licenseSha256": pin["licenseSha256"],
                "tailscaleModule": pin["tailscaleModule"],
            },
            "toolchain": {
                "goVersion": f"go{pin['goVersion']}",
                "androidNdkVersion": pin["androidNdkVersion"],
                "androidApi": pin["androidApi"],
                "clangVersion": clang_version,
            },
            "packaging": {
                "buildType": "debug",
                "abi": EXPECTED_ABI,
                "releaseEnabled": False,
                "ptLoadAlignmentBytes": pin["ptLoadAlignmentBytes"],
            },
            "artifacts": {
                "libtailscale.so": {
                    "sha256": sha256_bytes(official_bytes),
                    "dtNeeded": official_info["dtNeeded"],
                },
                "libpodroid-tailscale-jni.so": {
                    "sha256": sha256_bytes(shim_bytes),
                    "dtNeeded": shim_info["dtNeeded"],
                },
            },
        }
        (assets_dir / "libtailscale-provenance.json").write_text(
            json.dumps(provenance, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )

        if output.exists():
            shutil.rmtree(output)
        os.replace(temporary, output)
        temporary = None
    finally:
        if temporary is not None and temporary.exists():
            shutil.rmtree(temporary, ignore_errors=True)


def read_zip_entry_bounded(archive: zipfile.ZipFile, name: str, maximum_bytes: int) -> bytes:
    try:
        info = archive.getinfo(name)
    except KeyError as error:
        raise VerificationError(f"APK entry is missing: {name}") from error
    if info.file_size > maximum_bytes:
        raise VerificationError(f"APK entry exceeds {maximum_bytes} byte bound: {name}")
    data = archive.read(info)
    if len(data) != info.file_size:
        raise VerificationError(f"APK entry size changed while reading: {name}")
    return data


def verify_provenance(
    provenance_data: bytes,
    pin: dict[str, object],
    artifacts: dict[str, bytes],
) -> None:
    try:
        provenance = json.loads(provenance_data)
    except json.JSONDecodeError as error:
        raise VerificationError("packaged libtailscale provenance is invalid JSON") from error
    expected_source = {
        "repository": pin["repository"],
        "commit": pin["commit"],
        "tree": pin["tree"],
        "license": pin["license"],
        "licenseSha256": pin["licenseSha256"],
        "tailscaleModule": pin["tailscaleModule"],
    }
    if provenance.get("schemaVersion") != 1 or provenance.get("source") != expected_source:
        raise VerificationError("packaged provenance source does not match the reviewed pin")
    toolchain = provenance.get("toolchain", {})
    if (
        toolchain.get("goVersion") != f"go{pin['goVersion']}"
        or toolchain.get("androidNdkVersion") != pin["androidNdkVersion"]
        or toolchain.get("androidApi") != pin["androidApi"]
        or toolchain.get("clangVersion") != pin["clangVersion"]
    ):
        raise VerificationError("packaged provenance toolchain does not match the required toolchain")
    packaging = provenance.get("packaging")
    if packaging != {
        "buildType": "debug",
        "abi": EXPECTED_ABI,
        "releaseEnabled": False,
        "ptLoadAlignmentBytes": pin["ptLoadAlignmentBytes"],
    }:
        raise VerificationError("packaged provenance does not declare debug-only arm64 packaging")
    records = provenance.get("artifacts", {})
    for name, data in artifacts.items():
        record = records.get(name, {})
        if record.get("sha256") != sha256_bytes(data):
            raise VerificationError(f"packaged provenance hash mismatch for {name}")
        info = parse_elf(data, name)
        if record.get("dtNeeded") != info["dtNeeded"]:
            raise VerificationError(f"packaged provenance DT_NEEDED mismatch for {name}")


def verify_apk(repo_root: Path, apk_path: Path) -> None:
    root = repo_root.resolve()
    pin = verify_pin(root, require_toolchains=False)
    apk = apk_path.resolve()
    if not apk.is_file():
        raise VerificationError(f"debug APK does not exist: {apk}")
    if apk.stat().st_size > MAX_APK_BYTES:
        raise VerificationError(f"APK exceeds {MAX_APK_BYTES} byte bound")
    try:
        with zipfile.ZipFile(apk) as archive:
            infos = archive.infolist()
            if len(infos) > MAX_APK_ENTRIES:
                raise VerificationError(f"APK exceeds {MAX_APK_ENTRIES} entry bound")
            duplicate_names = {name for name, count in Counter(info.filename for info in infos).items() if count > 1}
            if duplicate_names:
                raise VerificationError(f"APK contains duplicate entries: {sorted(duplicate_names)[:10]}")

            native_names = sorted(
                info.filename for info in infos if info.filename.startswith("lib/") and info.filename.endswith(".so")
            )
            if not native_names:
                raise VerificationError("APK contains no native libraries")
            abis = sorted({name.split("/", 2)[1] for name in native_names if name.count("/") == 2})
            if abis != [EXPECTED_ABI]:
                raise VerificationError(f"APK native ABI set is {abis}, expected only {EXPECTED_ABI}")

            malformed_native_names = [name for name in native_names if name.count("/") != 2]
            if malformed_native_names:
                raise VerificationError(f"APK contains malformed native paths: {malformed_native_names[:10]}")
            total_native_bytes = sum(archive.getinfo(name).file_size for name in native_names)
            if total_native_bytes > MAX_TOTAL_NATIVE_BYTES:
                raise VerificationError(
                    f"APK native entries exceed {MAX_TOTAL_NATIVE_BYTES} aggregate byte bound"
                )

            native_data: dict[str, bytes] = {}
            for name in native_names:
                data = read_zip_entry_bounded(archive, name, MAX_NATIVE_ENTRY_BYTES)
                parse_elf(data, name)
                native_data[name] = data

            official_name = f"lib/{EXPECTED_ABI}/libtailscale.so"
            shim_name = f"lib/{EXPECTED_ABI}/libpodroid-tailscale-jni.so"
            if official_name not in native_data or shim_name not in native_data:
                raise VerificationError("APK is missing generated libtailscale or JNI shim")
            official = native_data[official_name]
            shim = native_data[shim_name]
            verify_artifact_contract(official, shim, pin)

            provenance_data = read_zip_entry_bounded(
                archive, "assets/libtailscale-provenance.json", MAX_METADATA_BYTES
            )
            verify_provenance(
                provenance_data,
                pin,
                {"libtailscale.so": official, "libpodroid-tailscale-jni.so": shim},
            )
            manifest = read_zip_entry_bounded(archive, "AndroidManifest.xml", 4 * 1024 * 1024)
            verify_no_vpn_manifest(manifest)
    except zipfile.BadZipFile as error:
        raise VerificationError(f"invalid APK zip: {apk}") from error


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    for name in ("verify-pin", "build"):
        command = subparsers.add_parser(name)
        command.add_argument("--repo", type=Path, required=True)
        if name == "verify-pin":
            command.add_argument("--require-toolchains", action="store_true")
        else:
            command.add_argument("--output", type=Path, required=True)
    verify_apk_parser = subparsers.add_parser("verify-apk")
    verify_apk_parser.add_argument("--repo", type=Path, required=True)
    verify_apk_parser.add_argument("--apk", type=Path, required=True)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        if args.command == "verify-pin":
            verify_pin(args.repo, args.require_toolchains)
            print("libtailscale pin/provenance verification passed")
        elif args.command == "build":
            build_android(args.repo, args.output)
            print(f"generated debug libtailscale artifacts: {args.output}")
        else:
            verify_apk(args.repo, args.apk)
            print(f"libtailscale APK verification passed: {args.apk}")
    except (OSError, UnicodeDecodeError, VerificationError) as error:
        print(f"libtailscale verification failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
