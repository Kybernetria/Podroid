package com.excp.podroid.vm

import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VmFileSafetyTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `stale tmp cleanup is iterative and removes files at the depth bound`() {
        val root = temporaryFolder.newFolder("cleanup")
        var directory = root
        repeat(8) { depth ->
            directory = directory.resolve("d$depth").apply { mkdir() }
        }
        val stale = directory.resolve("asset.tmp").apply { writeText("partial") }

        StaleTmpFileCleaner(maxDepth = 8, maxEntries = 20).clean(root)

        assertFalse(stale.exists())
    }

    @Test
    fun `stale tmp cleanup fails closed beyond depth bound`() {
        val root = temporaryFolder.newFolder("deep-cleanup")
        var directory = root
        repeat(4) { depth ->
            directory = directory.resolve("d$depth").apply { mkdir() }
        }
        val stale = directory.resolve("asset.tmp").apply { writeText("partial") }

        expectIOException { StaleTmpFileCleaner(maxDepth = 3, maxEntries = 20).clean(root) }

        assertTrue(stale.exists())
    }

    @Test
    fun `stale tmp cleanup fails closed beyond entry bound before deleting later files`() {
        val root = temporaryFolder.newFolder("wide-cleanup")
        repeat(4) { root.resolve("kept-$it").writeText("data") }
        val stale = root.resolve("z-last.tmp").apply { writeText("partial") }

        expectIOException { StaleTmpFileCleaner(maxDepth = 3, maxEntries = 2).clean(root) }

        assertTrue(stale.exists())
    }

    @Test
    fun `stale tmp cleanup rejects symlinks without following them`() {
        val root = temporaryFolder.newFolder("symlink-cleanup")
        val outside = temporaryFolder.newFile("outside.tmp").apply { writeText("outside") }
        Files.createSymbolicLink(root.toPath().resolve("escape.tmp"), outside.toPath())

        expectIOException { StaleTmpFileCleaner().clean(root) }

        assertEquals("outside", outside.readText())
        assertTrue(Files.isSymbolicLink(root.toPath().resolve("escape.tmp")))
    }

    @Test
    fun `stale tmp cleanup rejects fifo special files where supported`() {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
        val root = temporaryFolder.newFolder("special-cleanup")
        val fifo = root.resolve("hostile.tmp")
        val process = ProcessBuilder("mkfifo", fifo.absolutePath).start()
        assumeTrue(process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0)

        expectIOException { StaleTmpFileCleaner().clean(root) }

        assertTrue(Files.exists(fifo.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `physical validation rejects symlinked instance ancestor`() {
        val filesDir = temporaryFolder.newFolder("files")
        val outside = temporaryFolder.newFolder("outside")
        Files.createSymbolicLink(filesDir.toPath().resolve("instances"), outside.toPath())
        val security = VmPathSecurity(VmPaths.default(filesDir))

        expectIOException { security.prepareExtractionLayout() }

        assertTrue(outside.listFiles().isNullOrEmpty())
    }

    @Test
    fun `launch validation rejects nested physical symlink`() {
        val filesDir = temporaryFolder.newFolder("launch-files")
        val paths = VmPaths.default(filesDir)
        val security = VmPathSecurity(paths)
        security.prepareExtractionLayout()
        paths.kernel.writeText("kernel")
        paths.initrd.writeText("initrd")
        paths.rootfs.writeText("rootfs")
        paths.qemuKeymapsDirectory.mkdir()
        val outside = temporaryFolder.newFile("outside-map")
        Files.createSymbolicLink(paths.qemuKeymapsDirectory.toPath().resolve("escape"), outside.toPath())

        expectIOException { security.validateForLaunch() }
    }

    @Test
    fun `atomic writer rejects a pre-existing tmp symlink`() {
        val filesDir = temporaryFolder.newFolder("atomic-files")
        val paths = VmPaths.default(filesDir)
        val security = VmPathSecurity(paths)
        security.prepareExtractionLayout()
        val outside = temporaryFolder.newFile("outside-target").apply { writeText("outside") }
        Files.createSymbolicLink(
            paths.kernel.toPath().resolveSibling(paths.kernel.name + StaleTmpFileCleaner.TMP_SUFFIX),
            outside.toPath(),
        )

        expectIOException {
            VmAtomicFile.write(paths.kernel, security) { it.write("kernel".toByteArray()) }
        }

        assertEquals("outside", outside.readText())
        assertFalse(paths.kernel.exists())
    }

    private fun expectIOException(block: () -> Unit) {
        try {
            block()
            fail("Expected IOException")
        } catch (_: IOException) {
            // expected
        }
    }
}
