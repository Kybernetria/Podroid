package com.excp.podroid.engine.avf

import com.excp.podroid.vm.VmBootArtifacts
import com.excp.podroid.vm.VmPathSecurity
import com.excp.podroid.vm.VmPaths
import java.io.FileOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AvfRawKernelCacheTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `raw cache rebuilds by signed digest despite unchanged source mtime and survives restart`() {
        val paths = VmPaths.default(temporaryFolder.newFolder("files"))
        val security = VmPathSecurity(paths)
        security.prepareExtractionLayout()
        val source = temporaryFolder.newFile("profile-kernel.gz")
        val fixedMtime = 1_600_000_000_000L

        writeGzip(source, "first-raw-kernel")
        assertTrue(source.setLastModified(fixedMtime))
        val firstDigest = VmBootArtifacts.digest(source)
        val first = AvfRawKernelCache.prepare(
            source, firstDigest, paths.rawKernel, paths.rawKernelDigestStamp, security,
        )
        assertEquals("first-raw-kernel", first.readText())
        assertTrue(paths.rawKernelDigestStamp.readText().startsWith("v1:${firstDigest.value}:"))

        writeGzip(source, "second-raw-kernel")
        assertTrue(source.setLastModified(fixedMtime))
        val secondDigest = VmBootArtifacts.digest(source)
        val second = AvfRawKernelCache.prepare(
            source, secondDigest, paths.rawKernel, paths.rawKernelDigestStamp, security,
        )
        assertEquals("second-raw-kernel", second.readText())
        assertTrue(paths.rawKernelDigestStamp.readText().startsWith("v1:${secondDigest.value}:"))

        paths.rawKernel.writeText("corrupt-but-stamp-is-unchanged")
        val repaired = AvfRawKernelCache.prepare(
            source, secondDigest, paths.rawKernel, paths.rawKernelDigestStamp, security,
        )
        assertEquals("second-raw-kernel", repaired.readText())

        // A later process has no in-memory cache state; the durable digest stamp remains sufficient.
        val restarted = AvfRawKernelCache.prepare(
            source, secondDigest, paths.rawKernel, paths.rawKernelDigestStamp, VmPathSecurity(paths),
        )
        assertEquals(second, restarted)
        assertEquals("second-raw-kernel", restarted.readText())
    }

    private fun writeGzip(destination: java.io.File, contents: String) {
        FileOutputStream(destination, false).use { file ->
            GZIPOutputStream(file).use { gzip -> gzip.write(contents.toByteArray()) }
        }
    }
}
