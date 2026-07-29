#!/usr/bin/env python3

import importlib.util
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("verify_minimal_guest.py")
SPEC = importlib.util.spec_from_file_location("verify_minimal_guest", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
verifier = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verifier)
REPO_ROOT = Path(__file__).resolve().parent.parent


class MinimalGuestVerifierTest(unittest.TestCase):
    def test_actual_sources_preserve_minimal_boot_contract(self):
        verifier.verify_source(REPO_ROOT)

    def test_explicit_package_manifest_rejects_additions_and_malformed_input(self):
        valid = ("\n".join(verifier.EXPECTED_EXPLICIT_PACKAGES) + "\n").encode()
        self.assertEqual(verifier.parse_explicit_packages(valid), verifier.EXPECTED_EXPLICIT_PACKAGES)
        for data in (valid + b"podman\n", valid + b"\n", valid.replace(b"openrc", b"OpenRC")):
            with self.subTest(data=data), self.assertRaises(verifier.VerificationError):
                verifier.parse_explicit_packages(data)

    def test_resolved_package_closure_rejects_removed_feature_families(self):
        required = tuple(sorted(verifier.REQUIRED_RESOLVED_PACKAGES))
        verifier.verify_package_closure(required)
        for forbidden in (
            "podman",
            "docker-openrc",
            "lxc",
            "tigervnc",
            "pulseaudio-utils",
            "font-misc-misc",
            "libx11",
            "xfce4-session",
        ):
            with self.subTest(package=forbidden), self.assertRaisesRegex(
                verifier.VerificationError, "forbidden package"
            ):
                verifier.verify_package_closure(tuple(sorted((*required, forbidden))))

    def test_package_database_bounds_and_record_shape_are_enforced(self):
        database = b"P:alpine-base\nV:1\n\nP:openrc\nV:1\n"
        self.assertEqual(verifier.parse_installed_packages(database), ("alpine-base", "openrc"))
        with self.assertRaisesRegex(verifier.VerificationError, "malformed package record"):
            verifier.parse_installed_packages(b"V:1\n")
        with self.assertRaisesRegex(verifier.VerificationError, "2 MiB bound"):
            verifier.parse_installed_packages(b"x" * (verifier.MAX_PACKAGE_DATABASE_BYTES + 1))

    def test_listing_rejects_forbidden_or_root_confused_paths(self):
        good = b"\n".join(
            (
                b"drwxr-xr-x 0/0 0 2026-01-01 00:00 squashfs-root",
                b"-rw-r--r-- 0/0 1 2026-01-01 00:00 squashfs-root/etc/issue",
            )
        )
        paths = verifier.listing_paths(good, 2)
        self.assertIn("etc/issue", paths)
        confused = good.replace(b"squashfs-root/etc/issue", b"other-root/etc/issue")
        with self.assertRaisesRegex(verifier.VerificationError, "escapes"):
            verifier.listing_paths(confused, 2)

    def test_migration_31_is_idempotent_and_preserves_user_data(self):
        migration = REPO_ROOT / "build-rootfs/files/etc/podroid/migrations/31.sh"
        stale_paths = (
            "etc/runlevels/default/podroid-x11",
            "etc/runlevels/default/docker",
            "etc/init.d/podroid-x11",
            "etc/profile.d/podroid-x11.sh",
            "etc/containers/storage.conf",
            "usr/local/bin/podroid-backup",
            "usr/local/bin/podroid-update-stats",
            "usr/share/podroid/logo.png",
        )
        preserved_paths = (
            "mnt/persist/containers/image-data",
            "mnt/persist/docker/volume-data",
            "mnt/persist/lxc/rootfs-data",
            "var/lib/containers/user-data",
            "home/user/document",
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for relative in (*stale_paths, *preserved_paths):
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("keep only when user data")
            environment = {**os.environ, "PODROID_MIGRATION_ROOT": str(root)}
            for _ in range(2):
                subprocess.run(["sh", str(migration)], check=True, env=environment)
            for relative in stale_paths:
                self.assertFalse((root / relative).exists(), relative)
            for relative in preserved_paths:
                self.assertEqual((root / relative).read_text(), "keep only when user data")

    def test_artifact_symlink_is_rejected_before_tool_execution(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            target = root / "target"
            target.write_bytes(b"not squashfs")
            artifact = root / "artifact"
            artifact.symlink_to(target)
            with self.assertRaisesRegex(verifier.VerificationError, "without symlink traversal"):
                verifier.verify_artifact(artifact)


if __name__ == "__main__":
    unittest.main()
