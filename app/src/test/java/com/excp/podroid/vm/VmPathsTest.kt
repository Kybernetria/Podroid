package com.excp.podroid.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VmPathsTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `default root is exact and every named path is confined`() {
        val filesDir = temporaryFolder.newFolder("files")
        val paths = VmPaths.default(filesDir)
        val expectedRoot = filesDir.toPath().resolve("instances/default").toAbsolutePath().normalize()

        assertEquals(expectedRoot.toFile(), paths.instanceDirectory)
        assertEquals(VmId.DEFAULT, paths.vmId)
        paths.namedFiles.forEach { (name, file) ->
            val normalized = file.toPath().toAbsolutePath().normalize()
            assertTrue("$name escaped the instance", normalized.startsWith(expectedRoot))
            assertNotEquals("$name resolved to the instance directory", expectedRoot, normalized)
        }
    }

    @Test
    fun `all named VM paths are unique`() {
        val paths = VmPaths.default(temporaryFolder.newFolder("files"))
        val normalized = paths.namedFiles.values.map { it.toPath().toAbsolutePath().normalize() }
        assertEquals(paths.namedFiles.keys.joinToString(), normalized.size, normalized.toSet().size)
    }

    @Test
    fun `qemu and avf working paths stay in the same default instance`() {
        val paths = VmPaths.default(temporaryFolder.newFolder("files"))
        assertEquals(paths.instanceDirectory, paths.qemuWorkingDirectory)
        assertEquals(paths.instanceDirectory, paths.avfWorkingDirectory)
        assertEquals(paths.instanceDirectory, paths.qemuDataDirectory)
        assertTrue(paths.qmpSocket.parentFile == paths.instanceDirectory)
        assertTrue(paths.storageImage.parentFile == paths.instanceDirectory)
        assertTrue(paths.consoleLog.parentFile == paths.instanceDirectory)
    }
}
