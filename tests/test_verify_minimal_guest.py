#!/usr/bin/env python3

import importlib.util
import shutil
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

    def test_resolved_package_closure_requires_exact_reviewed_lock(self):
        expected = verifier.EXPECTED_RESOLVED_PACKAGES
        verifier.verify_package_closure(expected)
        for changed in (expected[:-1], tuple(sorted((*expected, "podman")))):
            with self.subTest(changed=changed), self.assertRaisesRegex(
                verifier.VerificationError, "exact reviewed lock"
            ):
                verifier.verify_package_closure(changed)

    def test_resolved_package_lock_matches_successful_44_package_arm64_artifact(self):
        data = (REPO_ROOT / "build-rootfs/resolved-packages.lock").read_bytes()
        rows = verifier.parse_resolved_package_lock(data)
        self.assertEqual(tuple(row[0] for row in rows), verifier.EXPECTED_RESOLVED_PACKAGES)
        self.assertEqual(len(rows), 44)
        self.assertEqual(tuple(row for row in rows if row[0].startswith("tailscale")), verifier.TAILSCALE_PROVENANCE)
        with self.assertRaisesRegex(verifier.VerificationError, "version/origin/commit"):
            verifier.parse_resolved_package_lock(data.replace(b"1.90.9-r6", b"1.90.9-r5", 1))

    def test_runlevel_lock_requires_all_and_only_inittab_runlevels(self):
        data = (REPO_ROOT / "build-rootfs/runlevels.lock").read_bytes()
        self.assertEqual(verifier.parse_runlevels_lock(data), verifier.EXPECTED_RUNLEVELS)
        for changed in (
            data + b"rescue - -\n",
            data.replace(b"/etc/init.d/dropbear", b"/tmp/dropbear"),
            data.replace(b"shutdown - -\n", b""),
            data + b"boot mystery /etc/init.d/mystery\n",
        ):
            with self.subTest(changed=changed), self.assertRaises(verifier.VerificationError):
                verifier.parse_runlevels_lock(changed)

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
        for hostile in (b"squashfs-root/../outside", b"squashfs-root/etc/./issue", b"squashfs-root/etc//issue"):
            with self.subTest(hostile=hostile), self.assertRaisesRegex(
                verifier.VerificationError, "traversal path component"
            ):
                verifier.listing_paths(good.replace(b"squashfs-root/etc/issue", hostile), 2)

    def build_migration_helper(self, destination: Path) -> Path:
        compiler = shutil.which("cc")
        self.assertIsNotNone(compiler, "cc is required for migration helper regressions")
        source = REPO_ROOT / "build-rootfs/migrate-safe/podroid-migrate-safe.c"
        subprocess.run(
            [compiler, "-O2", "-Wall", "-Wextra", "-Werror", "-o", str(destination), str(source)],
            check=True,
        )
        return destination

    def test_migration_31_is_idempotent_and_preserves_user_data(self):
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
            base = Path(temporary)
            root = base / "root"
            helper = self.build_migration_helper(base / "podroid-migrate-safe")
            for relative in (*stale_paths, *preserved_paths):
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("keep only when user data")
            forwards = root / "etc/podroid/forwards.conf"
            forwards.parent.mkdir(parents=True, exist_ok=True)
            forwards.write_text("9100 ctl\n5900 tcp 127.0.0.1 5900\n4713 tcp 127.0.0.1 4713\n")
            for _ in range(2):
                subprocess.run([str(helper), "apply-31", str(root)], check=True)
            for relative in stale_paths:
                self.assertFalse((root / relative).exists(), relative)
            for relative in preserved_paths:
                self.assertEqual((root / relative).read_text(), "keep only when user data")
            self.assertEqual(forwards.read_text(), "9100 ctl\n")

    def test_migration_31_rejects_hostile_parent_symlink_without_touching_target(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            root = base / "root"
            outside = base / "outside"
            (root / "etc").mkdir(parents=True)
            outside.mkdir()
            victim = outside / "podroid-x11"
            victim.write_text("outside")
            (root / "etc/init.d").symlink_to(outside)
            helper = self.build_migration_helper(base / "podroid-migrate-safe")
            result = subprocess.run([str(helper), "apply-31", str(root)], capture_output=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(victim.read_text(), "outside")

    def test_migration_31_rejects_hostile_test_root_symlink(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            actual = base / "actual"
            (actual / "etc/podroid").mkdir(parents=True)
            (actual / "etc/podroid/forwards.conf").write_text("5900 tcp x 5900\n")
            hostile_root = base / "root"
            hostile_root.symlink_to(actual, target_is_directory=True)
            helper = self.build_migration_helper(base / "podroid-migrate-safe")
            result = subprocess.run([str(helper), "apply-31", str(hostile_root)], capture_output=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual((actual / "etc/podroid/forwards.conf").read_text(), "5900 tcp x 5900\n")

    def test_immutable_runner_applies_indexed_migration_and_commits_marker(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            lower = base / "lower"
            persist = base / "persist"
            target = base / "target"
            migrations = lower / "etc/podroid/migrations"
            binaries = lower / "usr/local/bin"
            migrations.mkdir(parents=True)
            binaries.mkdir(parents=True)
            persist.mkdir()
            stale = target / "etc/init.d/podroid-x11"
            stale.parent.mkdir(parents=True)
            stale.write_text("obsolete")
            forwards = target / "etc/podroid/forwards.conf"
            forwards.parent.mkdir(parents=True)
            forwards.write_text("9100 ctl\n5900 tcp 127.0.0.1 5900\n4713 tcp 127.0.0.1 4713\n")
            (lower / "etc/podroid/system-version").write_text("31\n")
            shutil.copy2(REPO_ROOT / "build-rootfs/files/etc/podroid/migrations/index", migrations / "index")
            shutil.copy2(REPO_ROOT / "build-rootfs/files/etc/podroid/migrations/31.sh", migrations / "31.sh")
            self.build_migration_helper(binaries / "podroid-migrate-safe")
            runner = REPO_ROOT / "build-rootfs/files/usr/local/bin/podroid-migrate-runner"
            subprocess.run(["sh", str(runner), str(lower), str(persist), str(target)], check=True)
            self.assertFalse(stale.exists())
            self.assertEqual(forwards.read_text(), "9100 ctl\n")
            marker = persist / ".podroid/applied-version"
            self.assertEqual(marker.read_text(), "31\n")
            marker.write_text("031\n")
            malformed = subprocess.run(
                ["sh", str(runner), str(lower), str(persist), str(target)],
                capture_output=True,
            )
            self.assertNotEqual(malformed.returncode, 0)
            self.assertEqual(marker.read_text(), "031\n")

    def test_failed_migration_cannot_advance_marker_or_reach_ready_dependency(self):
        bootstrap_path = REPO_ROOT / "build-rootfs/files/etc/init.d/podroid-bootstrap"
        network_path = REPO_ROOT / "build-rootfs/files/etc/init.d/podroid-network"
        ready_path = REPO_ROOT / "build-rootfs/files/etc/init.d/podroid-ready"
        bootstrap = bootstrap_path.read_bytes()
        network = network_path.read_bytes()
        ready = ready_path.read_bytes()
        verifier.verify_boot_dependency_policy(bootstrap, network, ready)
        with self.assertRaisesRegex(verifier.VerificationError, "successful podroid-migrate"):
            verifier.verify_boot_dependency_policy(
                bootstrap.replace(b"need localmount podroid-migrate", b"need localmount"),
                network,
                ready,
            )

        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            lower = base / "lower"
            persist = base / "persist"
            target = base / "target"
            migrations = lower / "etc/podroid/migrations"
            binaries = lower / "usr/local/bin"
            migrations.mkdir(parents=True)
            binaries.mkdir(parents=True)
            persist.mkdir()
            target.mkdir()
            (lower / "etc/podroid/system-version").write_text("31\n")
            (migrations / "index").write_text("31\n")
            (migrations / "31.sh").write_text("#!/bin/sh\nexit 23\n")
            self.build_migration_helper(binaries / "podroid-migrate-safe")
            runner = REPO_ROOT / "build-rootfs/files/usr/local/bin/podroid-migrate-runner"
            result = subprocess.run(
                ["sh", str(runner), str(lower), str(persist), str(target)],
                capture_output=True,
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse((persist / ".podroid/applied-version").exists())
            self.assertIn(b"migration 31 failed", result.stderr)

    def test_migration_index_rejects_malformed_duplicate_and_out_of_order_entries(self):
        for index in (b"031\n", b"31\n31\n", b"32\n31\n", b"31 extra\n"):
            with self.subTest(index=index), tempfile.TemporaryDirectory() as temporary:
                base = Path(temporary)
                lower = base / "lower"
                persist = base / "persist"
                target = base / "target"
                migrations = lower / "etc/podroid/migrations"
                binaries = lower / "usr/local/bin"
                migrations.mkdir(parents=True)
                binaries.mkdir(parents=True)
                persist.mkdir()
                (target / "etc/podroid").mkdir(parents=True)
                (target / "etc/podroid/forwards.conf").write_text("9100 ctl\n")
                (lower / "etc/podroid/system-version").write_text("31\n")
                (migrations / "index").write_bytes(index)
                for version in (31, 32):
                    (migrations / f"{version}.sh").write_text("#!/bin/sh\nexit 0\n")
                self.build_migration_helper(binaries / "podroid-migrate-safe")
                runner = REPO_ROOT / "build-rootfs/files/usr/local/bin/podroid-migrate-runner"
                result = subprocess.run(["sh", str(runner), str(lower), str(persist), str(target)], capture_output=True)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse((persist / ".podroid/applied-version").exists())

    def test_applied_marker_directory_symlink_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            persist = base / "persist"
            outside = base / "outside"
            persist.mkdir()
            outside.mkdir()
            (persist / ".podroid").symlink_to(outside, target_is_directory=True)
            helper = self.build_migration_helper(base / "podroid-migrate-safe")
            result = subprocess.run([str(helper), "write-applied", str(persist), "31"], capture_output=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse((outside / "applied-version").exists())

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
