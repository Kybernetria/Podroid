#!/usr/bin/env python3

import importlib.util
import json
import os
import struct
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock

REPO_ROOT = Path(__file__).resolve().parent.parent
MODULE_PATH = REPO_ROOT / "build-tools/libtailscale_android.py"
SPEC = importlib.util.spec_from_file_location("libtailscale_android", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
verifier = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verifier)


def synthetic_elf(needed=("libc.so",), alignment=16_384, machine=183):
    data = bytearray(0x2000)
    data[:16] = b"\x7fELF\x02\x01\x01" + b"\0" * 9
    struct.pack_into("<HHIQQQIHHHHHH", data, 16, 3, machine, 1, 0, 64, 0, 0, 64, 56, 2, 0, 0, 0)
    struct.pack_into("<IIQQQQQQ", data, 64, 1, 5, 0, 0, 0, len(data), len(data), alignment)
    dynamic_offset = 0x1000
    strings_offset = 0x1100
    dynamic_entries = len(needed) + 3
    struct.pack_into(
        "<IIQQQQQQ",
        data,
        120,
        2,
        6,
        dynamic_offset,
        dynamic_offset,
        dynamic_offset,
        dynamic_entries * 16,
        dynamic_entries * 16,
        8,
    )
    strings = bytearray(b"\0")
    needed_offsets = []
    for name in needed:
        needed_offsets.append(len(strings))
        strings.extend(name.encode("ascii") + b"\0")
    cursor = dynamic_offset
    for offset in needed_offsets:
        struct.pack_into("<QQ", data, cursor, 1, offset)
        cursor += 16
    struct.pack_into("<QQ", data, cursor, 5, strings_offset)
    struct.pack_into("<QQ", data, cursor + 16, 10, len(strings))
    struct.pack_into("<QQ", data, cursor + 32, 0, 0)
    data[strings_offset : strings_offset + len(strings)] = strings
    return bytes(data)


def binary_manifest(strings):
    encoded = bytearray()
    offsets = []
    for value in strings:
        raw = value.encode("utf-8")
        assert len(value) < 0x80 and len(raw) < 0x80
        offsets.append(len(encoded))
        encoded.extend(bytes((len(value), len(raw))))
        encoded.extend(raw)
        encoded.append(0)
    while len(encoded) % 4:
        encoded.append(0)
    pool_header_size = 28
    strings_start = pool_header_size + 4 * len(strings)
    pool_size = strings_start + len(encoded)
    pool = bytearray(pool_size)
    struct.pack_into("<HHI", pool, 0, 1, pool_header_size, pool_size)
    struct.pack_into("<IIIII", pool, 8, len(strings), 0, 0x100, strings_start, 0)
    for index, offset in enumerate(offsets):
        struct.pack_into("<I", pool, pool_header_size + index * 4, offset)
    pool[strings_start:] = encoded
    xml = bytearray(8 + len(pool))
    struct.pack_into("<HHI", xml, 0, 3, 8, len(xml))
    xml[8:] = pool
    return bytes(xml)


class LibTailscaleAndroidVerifierTest(unittest.TestCase):
    def test_actual_reviewed_source_pin_is_clean(self):
        verifier.verify_pin(REPO_ROOT, require_toolchains=False)

    def test_go_mod_must_match_go_and_tailscale_module_pins(self):
        pin = verifier.load_pin(REPO_ROOT)
        good = "module github.com/tailscale/libtailscale\n\ngo 1.25.5\n\nrequire tailscale.com v1.94.1\n"
        verifier.validate_go_mod(pin, good)
        for bad in (good.replace("1.25.5", "1.25.4"), good.replace("v1.94.1", "v1.96.0")):
            with self.subTest(go_mod=bad), self.assertRaises(verifier.VerificationError):
                verifier.validate_go_mod(pin, bad)

    def test_dirty_official_source_is_rejected(self):
        pin = verifier.load_pin(REPO_ROOT)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "third_party/libtailscale"
            source.mkdir(parents=True)
            (source / "LICENSE").write_bytes((REPO_ROOT / "third_party/libtailscale/LICENSE").read_bytes())
            (source / "go.mod").write_bytes((REPO_ROOT / "third_party/libtailscale/go.mod").read_bytes())

            def dirty_runner(command, cwd, timeout_seconds, env):
                del timeout_seconds, env
                key = tuple(command)
                if key == ("git", "rev-parse", "HEAD"):
                    return pin["commit"]
                if key == ("git", "rev-parse", "HEAD^{tree}"):
                    return pin["tree"]
                if key[:3] == ("git", "ls-files", "--stage"):
                    return f"160000 {pin['commit']} 0\tthird_party/libtailscale"
                if key[:2] == ("git", "status"):
                    return " M tailscale.go"
                raise AssertionError((command, cwd))

            with self.assertRaisesRegex(verifier.VerificationError, "source is dirty"):
                verifier.verify_source_pin(root, pin, dirty_runner)

    def test_ignored_file_in_official_checkout_is_rejected(self):
        pin = verifier.load_pin(REPO_ROOT)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "third_party/libtailscale"
            source.mkdir(parents=True)
            (source / "LICENSE").write_bytes((REPO_ROOT / "third_party/libtailscale/LICENSE").read_bytes())
            (source / "go.mod").write_bytes((REPO_ROOT / "third_party/libtailscale/go.mod").read_bytes())

            def ignored_runner(command, cwd, timeout_seconds, env):
                del timeout_seconds, env
                key = tuple(command)
                if key == ("git", "rev-parse", "HEAD"):
                    return pin["commit"]
                if key == ("git", "rev-parse", "HEAD^{tree}"):
                    return pin["tree"]
                if key[:3] == ("git", "ls-files", "--stage"):
                    return f"160000 {pin['commit']} 0\tthird_party/libtailscale"
                if key[:2] == ("git", "status"):
                    return ""
                if key[:3] == ("git", "ls-files", "--others"):
                    return "injected.go"
                raise AssertionError((command, cwd))

            with self.assertRaisesRegex(verifier.VerificationError, "contains ignored files"):
                verifier.verify_source_pin(root, pin, ignored_runner)

    def test_license_hash_mismatch_is_rejected(self):
        pin = dict(verifier.load_pin(REPO_ROOT))
        pin["licenseSha256"] = "0" * 64
        with self.assertRaisesRegex(verifier.VerificationError, "LICENSE hash"):
            verifier.verify_source_pin(REPO_ROOT, pin)

    def test_elf_parser_proves_aarch64_alignment_and_needed_libraries(self):
        info = verifier.parse_elf(synthetic_elf(("libtailscale.so",)), "shim")
        self.assertEqual(183, info["machine"])
        self.assertEqual([16_384], info["loadAlignments"])
        self.assertEqual(["libtailscale.so"], info["dtNeeded"])
        with self.assertRaisesRegex(verifier.VerificationError, "AArch64"):
            verifier.parse_elf(synthetic_elf(machine=62), "wrong-arch")
        with self.assertRaisesRegex(verifier.VerificationError, "alignment"):
            verifier.parse_elf(synthetic_elf(alignment=4096), "wrong-alignment")

    def test_jni_shim_must_depend_on_official_library(self):
        pin = dict(verifier.load_pin(REPO_ROOT))
        official = synthetic_elf(tuple(pin["officialDtNeeded"]))
        good_shim = synthetic_elf(("libtailscale.so", "libc.so"))
        verifier.verify_artifact_contract(official, good_shim, pin)
        with self.assertRaisesRegex(verifier.VerificationError, "DT_NEEDED mismatch"):
            verifier.verify_artifact_contract(official, synthetic_elf(("libc.so",)), pin)

    def test_static_apk_verifier_checks_abi_provenance_and_manifest(self):
        pin = verifier.load_pin(REPO_ROOT)
        official = synthetic_elf(tuple(pin["officialDtNeeded"]))
        shim = synthetic_elf(tuple(pin["shimDtNeeded"]))
        provenance = {
            "schemaVersion": 1,
            "source": {
                key: pin[key]
                for key in (
                    "repository", "commit", "tree", "license", "licenseSha256", "tailscaleModule"
                )
            },
            "toolchain": {
                "goVersion": f"go{pin['goVersion']}",
                "androidNdkVersion": pin["androidNdkVersion"],
                "androidApi": pin["androidApi"],
                "clangVersion": pin["clangVersion"],
            },
            "packaging": {
                "buildType": "debug",
                "abi": "arm64-v8a",
                "releaseEnabled": False,
                "ptLoadAlignmentBytes": 16_384,
            },
            "artifacts": {
                "libtailscale.so": {
                    "sha256": verifier.sha256_bytes(official),
                    "dtNeeded": pin["officialDtNeeded"],
                },
                "libpodroid-tailscale-jni.so": {
                    "sha256": verifier.sha256_bytes(shim),
                    "dtNeeded": pin["shimDtNeeded"],
                },
            },
        }
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "app-debug.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("lib/arm64-v8a/libtailscale.so", official)
                archive.writestr("lib/arm64-v8a/libpodroid-tailscale-jni.so", shim)
                archive.writestr("assets/libtailscale-provenance.json", json.dumps(provenance))
                archive.writestr("AndroidManifest.xml", binary_manifest(("manifest",)))
            with mock.patch.object(verifier, "verify_pin", return_value=pin):
                verifier.verify_apk(REPO_ROOT, apk)

            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("lib/x86_64/libtailscale.so", official)
                archive.writestr("lib/x86_64/libpodroid-tailscale-jni.so", shim)
                archive.writestr("assets/libtailscale-provenance.json", json.dumps(provenance))
                archive.writestr("AndroidManifest.xml", binary_manifest(("manifest",)))
            with mock.patch.object(verifier, "verify_pin", return_value=pin):
                with self.assertRaisesRegex(verifier.VerificationError, "ABI set"):
                    verifier.verify_apk(REPO_ROOT, apk)

    def test_binary_manifest_rejects_vpn_service(self):
        verifier.verify_no_vpn_manifest(binary_manifest(("manifest", "com.excp.podroid")))
        with self.assertRaisesRegex(verifier.VerificationError, "VpnService"):
            verifier.verify_no_vpn_manifest(binary_manifest(("android.net.VpnService",)))
        with self.assertRaisesRegex(verifier.VerificationError, "VpnService"):
            verifier.verify_no_vpn_manifest(
                binary_manifest(("android.permission.BIND_VPN_SERVICE",))
            )

    def test_gradle_packaging_source_sets_are_debug_only(self):
        gradle = (REPO_ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn('selector().withBuildType("debug")', gradle)
        self.assertIn("addGeneratedSourceDirectory(buildDebugLibTailscale)", gradle)
        self.assertNotIn('withBuildType("release")', gradle)
        self.assertIn("verifyPackagedDebugLibTailscale", gradle)
        self.assertNotIn("verifyPackagedReleaseLibTailscale", gradle)

    def test_missing_pinned_clang_cxx_fails_closed(self):
        pin = verifier.load_pin(REPO_ROOT)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            go = root / "go"
            go.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
            go.chmod(0o755)
            ndk = root / "ndk"
            clang = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang"
            clang.parent.mkdir(parents=True)
            clang.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
            clang.chmod(0o755)
            (ndk / "source.properties").write_text(
                "Pkg.Revision = 28.2.13676358\n", encoding="utf-8"
            )

            def runner(command, cwd, timeout_seconds, env):
                del cwd, timeout_seconds, env
                if command[1:3] == ["env", "GOVERSION"]:
                    return "go1.25.5"
                return pin["clangVersion"] + "\nTarget: aarch64-unknown-linux-android26"

            environment = {
                "PODROID_GO": str(go),
                "PODROID_ANDROID_NDK_HOME": str(ndk),
            }
            with mock.patch.dict(os.environ, environment, clear=False):
                with self.assertRaisesRegex(verifier.VerificationError, "C\\+\\+ compiler"):
                    verifier.verify_toolchains(REPO_ROOT, pin, runner)

    def test_toolchain_mismatch_fails_closed(self):
        pin = verifier.load_pin(REPO_ROOT)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            go = root / "go"
            go.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
            go.chmod(0o755)
            ndk = root / "ndk"
            clang = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang"
            clang.parent.mkdir(parents=True)
            clang.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
            clang.chmod(0o755)
            (ndk / "source.properties").write_text(
                "Pkg.Revision = 28.2.13676358\n", encoding="utf-8"
            )

            def wrong_go_runner(command, cwd, timeout_seconds, env):
                del command, cwd, timeout_seconds, env
                return "go1.25.4"

            environment = {
                "PODROID_GO": str(go),
                "PODROID_ANDROID_NDK_HOME": str(ndk),
            }
            with mock.patch.dict(os.environ, environment, clear=False):
                with self.assertRaisesRegex(verifier.VerificationError, "Go toolchain mismatch"):
                    verifier.verify_toolchains(REPO_ROOT, pin, wrong_go_runner)


if __name__ == "__main__":
    unittest.main()
