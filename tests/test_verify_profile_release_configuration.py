import importlib.util
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("verify_profile_release_configuration.py")
SPEC = importlib.util.spec_from_file_location("release_profile_config", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(MODULE)

VALID_KEY = "MCowBQYDK2VwAyEA" + "A" * 43 + "="


class ReleaseProfileConfigurationVerifierTest(unittest.TestCase):
    def test_complete_canonical_snapshot_passes(self):
        self.assertEqual([], MODULE.validate("release-1", VALID_KEY, "1", "https://profiles.example:443"))

    def test_absent_or_partial_snapshot_fails(self):
        self.assertTrue(MODULE.validate("", "", "", ""))
        self.assertTrue(MODULE.validate("release-1", VALID_KEY, "1", ""))

    def test_invalid_key_epoch_and_origin_fail(self):
        self.assertTrue(MODULE.validate("Upper", "AAAA", "01", "https://profiles.example"))
        self.assertTrue(MODULE.validate("release-1", VALID_KEY, "1", "https://PROFILES.example:443"))


if __name__ == "__main__":
    unittest.main()
