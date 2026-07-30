package com.excp.podroid.engine

import com.excp.podroid.vm.VmBootArtifact
import com.excp.podroid.vm.VmBootArtifacts
import com.excp.podroid.vm.VmBootDigest
import com.excp.podroid.vm.VmBootGeneration
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QemuProfileCommandPolicyTest {
    @Test fun `bundled command still appends configured QEMU extras`() {
        val command = mutableListOf("qemu")
        appendValidatedQemuExtraArgs(
            command,
            VmConfig(qemuExtraArgs = "-cpu host  -nodefaults"),
        )
        assertEquals(listOf("qemu", "-cpu", "host", "-nodefaults"), command)
    }

    @Test fun `downloaded profile command rejects every nonblank QEMU extra before append`() {
        listOf("-kernel /tmp/unsigned", "   -drive file=override   ", "\t-nodefaults\n").forEach { extras ->
            val command = mutableListOf("qemu", "-kernel", "signed-kernel")
            val failure = runCatching {
                appendValidatedQemuExtraArgs(
                    command,
                    VmConfig(bootArtifacts = bootArtifacts(), qemuExtraArgs = extras),
                )
            }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException || failure is IllegalStateException)
            assertEquals(listOf("qemu", "-kernel", "signed-kernel"), command)
        }
    }

    @Test fun `concrete backend rechecks signed backend support after router claim`() {
        val artifacts = bootArtifacts(setOf("qemu"))
        artifacts.requireBackend("qemu")
        assertTrue(runCatching { artifacts.requireBackend("avf") }.exceptionOrNull() is java.io.IOException)
    }

    @Test fun `downloaded profile command accepts blank extras without changing command`() {
        val command = mutableListOf("qemu", "-kernel", "signed-kernel")
        appendValidatedQemuExtraArgs(
            command,
            VmConfig(bootArtifacts = bootArtifacts(), qemuExtraArgs = " \t\n"),
        )
        assertEquals(listOf("qemu", "-kernel", "signed-kernel"), command)
    }

    private fun bootArtifacts(supportedBackendIds: Set<String> = setOf("qemu", "avf")): VmBootArtifacts {
        val digest = VmBootDigest("a".repeat(64))
        fun artifact(name: String) = VmBootArtifact(File("/tmp/$name"), digest)
        return VmBootArtifacts(
            VmBootGeneration(1),
            digest,
            artifact("kernel"),
            artifact("initrd"),
            artifact("rootfs"),
            supportedBackendIds,
        )
    }
}
