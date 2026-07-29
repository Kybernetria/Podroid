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

    def assert_rejected(self, source: str):
        self.assertTrue(self.verify_source(source), source)

    def test_accepts_service_client_and_reviewed_dtos(self):
        self.assertEqual([], self.verify_source(
            """
            import com.excp.podroid.service.VmServiceClient
            import com.excp.podroid.service.VmUiState
            import com.excp.podroid.vm.ConsoleLogRequest
            class Screen(
                val client: VmServiceClient,
                val state: VmUiState,
                val request: ConsoleLogRequest,
            )
            """
        ))

    def test_rejects_boundary_import_bypasses(self):
        fixtures = (
            "import com.excp.podroid.engine.VmEngine\nclass Screen",
            "import com.excp.podroid.engine.*\nclass Screen",
            "import com.excp.podroid.service.PodroidService as Lifecycle\nclass Screen",
            "import com.excp.podroid.vm.VmManager\nclass Screen",
            "import com.excp.podroid.vm.VmPaths\nclass Screen",
            "import com.excp.podroid.vm.*\nclass Screen",
        )
        for source in fixtures:
            with self.subTest(source=source):
                self.assert_rejected(source)

    def test_rejects_plain_and_fully_qualified_service_manager_path_references(self):
        fixtures = (
            "fun bad() = PodroidService.start(context)",
            "val service = com.excp.podroid.service.PodroidService::class.java",
            "val endpoint: com.excp.podroid.service.VmServiceEndpoint? = null",
            "val local: com.excp.podroid.service.LocalVmServiceEndpoint? = null",
            "fun bad(manager: VmManager) = manager.toString()",
            "val manager: com.excp.podroid.vm.VmManager? = null",
            "fun bad(paths: VmPaths) = paths.storageImage",
            "val paths = com.excp.podroid.vm.VmPaths(context)",
            "val engine = com.excp.podroid.engine.EngineHolder::class",
        )
        for source in fixtures:
            with self.subTest(source=source):
                self.assert_rejected(source)

    def test_rejects_backend_capability_type_variants(self):
        fixtures = (
            "fun bad(value: VmEngine) = value",
            "fun bad(value: QmpClient) = value",
            "fun bad(value: QmpController) = value",
            "fun bad(value: QemuBootMonitor) = value",
            "fun bad(value: QemuEngine) = value",
            "fun bad(value: AvfDiagnostics) = value",
            "fun bad(value: AvfEngine) = value",
        )
        for source in fixtures:
            with self.subTest(source=source):
                self.assert_rejected(source)

    def test_rejects_direct_service_intent_and_binding_apis(self):
        fixtures = (
            "context.bindService(intent, connection, 0)",
            "context.unbindService(connection)",
            "context.startForegroundService(intent)",
            "context.startService(intent)",
            "context.stopService(intent)",
            "PendingIntent.getService(context, 1, intent, 0)",
            "Intent(context, HiddenService::class.java)",
        )
        for source in fixtures:
            with self.subTest(source=source):
                self.assert_rejected(source)

    def test_rejects_boundary_reflection_and_raw_package_literals(self):
        fixtures = (
            'Class.forName("com.excp.podroid.engine.QemuEngine")',
            'context.classLoader.loadClass("com.excp.podroid.service.PodroidService")',
            'context.getClassLoader().loadClass(className)',
            'val hidden = "com.excp.podroid.vm." + "VmPaths"',
            'val hidden = "com.excp.podroid.engine.avf.AvfDiagnostics"',
            'import com.excp.podroid.`engine`.`hostbridge`.`HostRequestServer`',
            'val hidden = com.excp.podroid.`service`.`VmServiceEndpoint`::class',
        )
        for source in fixtures:
            with self.subTest(source=source):
                self.assert_rejected(source)

    def test_rejects_vm_path_literal_file_and_resolve_bypasses(self):
        fixtures = (
            'File(context.filesDir, "instances/default")',
            'java.io.File(context.filesDir, "storage.img")',
            'context.filesDir.resolve("instances").resolve("default")',
            'context.filesDir.resolve("console.log")',
            'root.resolveSibling("alpine-rootfs.squashfs")',
            'File(context.applicationInfo.dataDir, "instances")',
            'val root = context.filesDir; File(root, name)',
            'File(context.getFilesDir(), child)',
            'java.io.File(context.getDataDir(), "child").toPath()',
            'context.getNoBackupFilesDir().resolve("child")',
            'context.getFilesDir().toPath().resolve("child")',
            'val path = "/data/user/0/com.excp.podroid/files/instances/default"',
            'val socket = "qmp.sock"',
        )
        for source in fixtures:
            with self.subTest(source=source):
                self.assert_rejected(source)

    def test_allows_reviewed_ui_owned_files(self):
        self.assertEqual([], self.verify_source(
            """
            val log = File(context.filesDir, "log.txt")
            val font = File(context.cacheDir, "font.ttf")
            val theme = File(context.getExternalFilesDir(null), "colors")
            """
        ))

    def test_rejects_application_data_reset(self):
        self.assert_rejected("activityManager.clearApplicationUserData()")

    def test_ignores_diagnostic_prose_comments_and_non_path_log_tags(self):
        self.assertEqual([], self.verify_source(
            '// VmPaths storageImage instances/default PodroidService\n'
            'val tags = listOf("QemuEngine", "QmpClient", "AvfEngine")'
        ))


if __name__ == "__main__":
    unittest.main()
