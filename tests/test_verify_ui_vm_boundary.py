import tempfile
import unittest
from pathlib import Path

from tests.verify_ui_vm_boundary import verify


class UiVmBoundaryVerifierTest(unittest.TestCase):
    def verify_source(self, source: str):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "Screen.kt"
            path.write_text(source, encoding="utf-8")
            return verify(Path(directory))

    def test_accepts_service_client(self):
        self.assertEqual([], self.verify_source(
            "import com.excp.podroid.service.VmServiceClient\nclass Screen(val client: VmServiceClient)"
        ))

    def test_rejects_engine_import(self):
        self.assertTrue(self.verify_source(
            "import com.excp.podroid.engine.VmEngine\nclass Screen"
        ))

    def test_rejects_static_service_lifecycle_and_paths(self):
        failures = self.verify_source(
            "fun bad() { PodroidService.start(context); vmPaths.storageImage.delete() }"
        )
        self.assertGreaterEqual(len(failures), 2)

    def test_rejects_backend_capabilities_and_application_data_reset(self):
        failures = self.verify_source(
            "fun bad(engine: VmEngine) { engine.qmpController; clearApplicationUserData() }"
        )
        self.assertGreaterEqual(len(failures), 2)

    def test_ignores_diagnostic_prose_and_comments(self):
        self.assertEqual([], self.verify_source(
            '// VmPaths storageImage instances/default\nval tag = "QemuEngine consoleLog"'
        ))


if __name__ == "__main__":
    unittest.main()
