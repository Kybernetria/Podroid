package com.excp.podroid.engine

import com.excp.podroid.engine.avf.avfBootFiles
import com.excp.podroid.vm.VmBootArtifact
import com.excp.podroid.vm.VmBootArtifacts
import com.excp.podroid.vm.VmBootGeneration
import com.excp.podroid.vm.VmPaths
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VmBootArtifactsLaunchTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `qemu command and avf config select the same active profile generation`() {
        val filesDir = temporaryFolder.newFolder("files")
        val profileDir = temporaryFolder.newFolder("profile")
        val paths = VmPaths.default(filesDir)
        val artifacts = artifacts(profileDir, "active")
        val config = VmConfig(bootArtifacts = artifacts)

        val qemu = qemuBootFiles(config, paths)
        val avf = avfBootFiles(config, paths)

        assertEquals(artifacts.kernel.file, qemu.kernel)
        assertEquals(artifacts.initrd.file, qemu.initrd)
        assertEquals(artifacts.rootfs.file, qemu.rootfs)
        assertEquals(qemu, avf)
        assertEquals(artifacts.kernel.sha256, avf.kernelDigest)
    }

    @Test
    fun `both backends preserve exact bundled fixed path fallback`() {
        val paths = VmPaths.default(temporaryFolder.newFolder("fallback"))
        val config = VmConfig(bootArtifacts = null)

        val qemu = qemuBootFiles(config, paths)
        val avf = avfBootFiles(config, paths)

        assertEquals(paths.kernel, qemu.kernel)
        assertEquals(paths.initrd, qemu.initrd)
        assertEquals(paths.rootfs, qemu.rootfs)
        assertNull(qemu.kernelDigest)
        assertEquals(qemu, avf)
    }

    @Test(expected = IOException::class)
    fun `configured generation fails closed when a selected file changes`() {
        val profileDir = temporaryFolder.newFolder("invalid")
        val artifacts = artifacts(profileDir, "validated")
        artifacts.rootfs.file.writeText("changed-after-resolution")

        artifacts.validateFiles()
    }

    private fun artifacts(directory: java.io.File, seed: String): VmBootArtifacts {
        fun artifact(name: String): VmBootArtifact {
            val file = directory.resolve(name).absoluteFile.apply { writeText("$seed-$name") }
            return VmBootArtifact(file, VmBootArtifacts.digest(file))
        }
        return VmBootArtifacts(
            generation = VmBootGeneration(17),
            manifestSha256 = com.excp.podroid.vm.VmBootDigest("a".repeat(64)),
            kernel = artifact("kernel"),
            initrd = artifact("initrd"),
            rootfs = artifact("rootfs"),
        )
    }
}
