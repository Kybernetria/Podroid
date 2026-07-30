import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
PROFILE = ROOT / "profiles" / "debian-cloud"


def load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


seed_tool = load_module("podroid_seed_tool", PROFILE / "build_nocloud_seed.py")
upstream_tool = load_module("podroid_upstream_tool", PROFILE / "verify_upstream.py")


class NoCloudSeedToolTest(unittest.TestCase):
    def test_seed_is_deterministic_closed_cidata_and_matches_reviewed_sources(self):
        source = PROFILE / "nocloud"
        first = seed_tool.build_iso(seed_tool.load_reviewed_sources(source))
        second = seed_tool.build_iso(seed_tool.load_reviewed_sources(source))
        self.assertEqual(first, second)
        self.assertEqual(b"\x01CD001\x01", first[16 * 2048:16 * 2048 + 7])
        self.assertEqual("CIDATA", first[16 * 2048 + 40:16 * 2048 + 72].decode().rstrip())
        inspected = seed_tool.inspect_iso(first)
        self.assertEqual(set(seed_tool.REQUIRED_FILES), set(inspected))
        self.assertEqual(2, inspected["vendor-data"].decode().count(seed_tool.READINESS_MARKER))
        self.assertLessEqual(len(first), seed_tool.MAX_SEED_BYTES)

    def test_cli_build_and_inspect_are_reproducible(self):
        with tempfile.TemporaryDirectory() as temporary:
            one = Path(temporary) / "one.iso"
            two = Path(temporary) / "two.iso"
            for output in (one, two):
                completed = subprocess.run(
                    ["python3", str(PROFILE / "build_nocloud_seed.py"), "build", "--output", str(output)],
                    cwd=ROOT, text=True, capture_output=True,
                )
                self.assertEqual(0, completed.returncode, completed.stderr)
            self.assertEqual(one.read_bytes(), two.read_bytes())
            inspected = subprocess.run(
                ["python3", str(PROFILE / "build_nocloud_seed.py"), "inspect", "--image", str(one),
                 "--source", str(PROFILE / "nocloud")],
                cwd=ROOT, text=True, capture_output=True,
            )
            self.assertEqual(0, inspected.returncode, inspected.stderr)
            facts = json.loads(inspected.stdout)
            self.assertEqual(len(one.read_bytes()), facts["size_bytes"])
            self.assertEqual(hashlib.sha256(one.read_bytes()).hexdigest(), facts["sha256"])

    def test_sources_forbid_credentials_tokens_keys_and_remote_seed(self):
        forbidden = (
            "password: example\n",
            "passwd: '$6$hash'\n",
            "ssh_authorized_keys:\n  - ssh-ed25519 AAAA\n",
            "token: secret\n",
            "seedfrom: https://unreviewed.example/\n",
            "-----BEGIN OPENSSH PRIVATE KEY-----\n",
        )
        for content in forbidden:
            with self.subTest(content=content.splitlines()[0]), tempfile.TemporaryDirectory() as temporary:
                copied = Path(temporary) / "nocloud"
                shutil.copytree(PROFILE / "nocloud", copied)
                target = copied / "meta-data"
                target.write_text(target.read_text() + content)
                manifest_path = copied / "reviewed-files.json"
                manifest = json.loads(manifest_path.read_text())
                manifest["files"]["meta-data"] = hashlib.sha256(target.read_bytes()).hexdigest()
                manifest_path.write_text(json.dumps(manifest) + "\n")
                with self.assertRaises(seed_tool.SeedError):
                    seed_tool.load_reviewed_sources(copied)

    def test_source_set_review_hash_and_iso_bytes_fail_closed(self):
        sources = seed_tool.load_reviewed_sources(PROFILE / "nocloud")
        non_default = dict(sources)
        non_default["user-data"] = sources["user-data"].replace(b"users: []\n", b"users:\n  - default\n")
        with self.assertRaises(seed_tool.SeedError):
            seed_tool.inspect_iso(seed_tool.build_iso(non_default))

        with tempfile.TemporaryDirectory() as temporary:
            copied = Path(temporary) / "nocloud"
            shutil.copytree(PROFILE / "nocloud", copied)
            (copied / "extra").write_text("extra\n")
            with self.assertRaises(seed_tool.SeedError):
                seed_tool.load_reviewed_sources(copied)
        image = bytearray(seed_tool.build_iso(seed_tool.load_reviewed_sources(PROFILE / "nocloud")))
        image[-1] ^= 1
        with self.assertRaises(seed_tool.SeedError):
            seed_tool.inspect_iso(bytes(image))


class DebianUpstreamVerifierTest(unittest.TestCase):
    def test_official_lock_and_downloaded_sha512sums_are_exact(self):
        lock = upstream_tool.load_lock(PROFILE / "upstream-lock.json")
        upstream_tool.verify_metadata(PROFILE / "upstream" / "SHA512SUMS", lock)
        self.assertEqual(upstream_tool.EXPECTED_SHA512, lock["image"]["publisher_sha512"])
        self.assertIsNone(lock["image"]["downloaded_sha256"])
        self.assertIsNone(lock["image"]["downloaded_size_bytes"])
        self.assertIsNone(lock["publisher_metadata"]["detached_signature_url"])

    def test_supplied_image_is_streamed_and_verified_or_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            image = Path(temporary) / "image.raw"
            image.write_bytes(b"reviewed-image-bytes")
            digest = hashlib.sha512(image.read_bytes()).hexdigest()
            lock = {"image": {"publisher_sha512": digest}}
            facts = upstream_tool.verify_image(image, lock)
            self.assertEqual(len(image.read_bytes()), facts["size_bytes"])
            self.assertEqual(hashlib.sha256(image.read_bytes()).hexdigest(), facts["sha256"])
            image.write_bytes(b"changed")
            with self.assertRaises(upstream_tool.ProvenanceError):
                upstream_tool.verify_image(image, lock)

    def test_default_cli_verifies_vendored_metadata_without_claiming_image(self):
        completed = subprocess.run(
            ["python3", str(PROFILE / "verify_upstream.py")],
            cwd=ROOT, text=True, capture_output=True,
        )
        self.assertEqual(0, completed.returncode, completed.stderr)
        result = json.loads(completed.stdout)
        self.assertTrue(result["metadata_pin_verified"])
        self.assertFalse(result["publisher_signature_verified"])
        self.assertFalse(result["image_verified"])
        self.assertIsNone(result["image_facts"])

    def test_unapproved_https_redirect_origin_fails_closed(self):
        handler = upstream_tool.LockedRedirectHandler({"https://cloud.debian.org:443"})
        request = type("Request", (), {})()
        with self.assertRaises(upstream_tool.ProvenanceError):
            handler.redirect_request(
                request, None, 302, "Found", {},
                "https://unreviewed-mirror.example/image.raw",
            )


class ProfileV2SchemaTest(unittest.TestCase):
    def test_schema_is_closed_and_matches_the_four_role_contract(self):
        schema = json.loads((ROOT / "profiles" / "schemas" / "profile-payload-v2.schema.json").read_text())
        self.assertFalse(schema["additionalProperties"])
        self.assertEqual(2, schema["properties"]["version"]["const"])
        self.assertEqual([{"const": "qemu"}], schema["properties"]["supported_backends"]["prefixItems"])
        self.assertEqual("PODROID_CLOUD_READY_V1", schema["properties"]["readiness_marker"]["const"])
        role_formats = {
            definition["properties"]["role"]["const"]: definition["properties"]["format"]["const"]
            for name, definition in schema["$defs"].items()
            if name in {"cloudDisk", "uefiCode", "uefiVars", "nocloudSeed"}
        }
        self.assertEqual({
            "cloud-disk": "raw",
            "uefi-code": "raw-pflash",
            "uefi-vars-template": "raw-pflash",
            "nocloud-seed": "iso9660-cidata",
        }, role_formats)


if __name__ == "__main__":
    unittest.main()
