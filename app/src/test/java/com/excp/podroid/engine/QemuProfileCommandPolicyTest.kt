package com.excp.podroid.engine

import com.excp.podroid.vm.VmBootArtifact
import com.excp.podroid.vm.VmBootArtifacts
import com.excp.podroid.vm.VmBootDigest
import com.excp.podroid.vm.UefiNoCloudVmBootPlan
import com.excp.podroid.vm.VmBootGeneration
import com.excp.podroid.vm.VmGuestCapabilities
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

    @Test fun `UEFI NoCloud boot args are exact ordered raw and exclude direct kernel path`() {
        val digest = VmBootDigest("b".repeat(64))
        val plan = UefiNoCloudVmBootPlan(
            VmBootGeneration(2),
            digest,
            File("/fixed/storage.img"),
            VmBootArtifact(File("/cas/uefi-code"), digest),
            File("/fixed/uefi-vars.fd"),
            VmBootArtifact(File("/cas/cidata"), digest),
            "PODROID_CLOUD_READY_V1",
            VmGuestCapabilities(),
        )

        assertEquals(
            listOf(
                "-drive", "if=pflash,format=raw,readonly=on,file=/cas/uefi-code",
                "-drive", "if=pflash,format=raw,file=/fixed/uefi-vars.fd",
                "-object", "iothread,id=iothread0",
                "-device", "virtio-blk-pci,drive=drive1,num-queues=2,iothread=iothread0,bootindex=1",
                "-drive", "file=/fixed/storage.img,if=none,id=drive1,format=raw,cache=writeback,aio=threads,discard=unmap,detect-zeroes=unmap",
                "-object", "iothread,id=iothread1",
                "-device", "virtio-blk-pci,drive=drive2,num-queues=2,iothread=iothread1,bootindex=2",
                "-drive", "file=/cas/cidata,if=none,id=drive2,format=raw,readonly=on,cache=writeback,aio=threads",
            ),
            buildClosedCloudQemuBootArgs(plan, 2),
        )
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
