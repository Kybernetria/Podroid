import importlib.util
import tempfile
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("verify_profile_lifecycle_boundary.py")
SPEC = importlib.util.spec_from_file_location("profile_boundary", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(MODULE)


class ProfileLifecycleBoundaryVerifierTest(unittest.TestCase):
    def fixture(self, extra_path=None, extra_text=""):
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        runtime = root / "profiles/DownloadableProfileRuntime.kt"
        runtime.parent.mkdir(parents=True)
        runtime.write_text(
            "class DownloadableProfileRuntime { fun prepareEnvelopeUrl() = Unit }\n"
            "internal object DownloadedProfileLineageGuard\n",
            encoding="utf-8",
        )
        if extra_path:
            target = root / extra_path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(extra_text, encoding="utf-8")
        return temporary, root

    def test_minimal_boundary_passes(self):
        temporary, root = self.fixture()
        self.addCleanup(temporary.cleanup)
        self.assertEqual([], MODULE.verify(root))

    def test_ui_raw_store_access_fails(self):
        temporary, root = self.fixture(
            "ui/Bad.kt",
            "class Bad(private val store: ProfileLifecycleStore) { fun x() = store.rollback(1, policy) }",
        )
        self.addCleanup(temporary.cleanup)
        failures = MODULE.verify(root)
        self.assertTrue(any("ProfileLifecycleStore" in failure for failure in failures))
        self.assertTrue(any("raw profile lifecycle mutation" in failure for failure in failures))

    def test_service_cannot_obtain_lifecycle_authority(self):
        temporary, root = self.fixture(
            "service/BadService.kt",
            "class BadService(private val profiles: ProfileLifecycleOperations)",
        )
        self.addCleanup(temporary.cleanup)
        self.assertTrue(any("local profile lifecycle authority" in failure for failure in MODULE.verify(root)))

    def test_runtime_lifecycle_member_fails(self):
        temporary, root = self.fixture()
        self.addCleanup(temporary.cleanup)
        runtime = root / "profiles/DownloadableProfileRuntime.kt"
        runtime.write_text(
            "class DownloadableProfileRuntime { fun issueDataDeletionConfirmation() = Unit }\n"
            "internal object DownloadedProfileLineageGuard\n",
            encoding="utf-8",
        )
        self.assertTrue(MODULE.verify(root))


if __name__ == "__main__":
    unittest.main()
