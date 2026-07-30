/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.vm

import java.io.File
import java.nio.file.Path

/**
 * The single path source for one VM instance. Every persistent boot input and
 * runtime endpoint is confined below `filesDir/instances/<vmId>`.
 */
class VmPaths private constructor(
    val filesDirectory: File,
    val vmId: VmId,
) {
    val instancesDirectory: File = confined(filesDirectory.toPath().resolve(INSTANCES_DIRECTORY))
    val instanceDirectory: File = confined(instancesDirectory.toPath().resolve(vmId.serialized))

    val storageImage: File = child("storage.img")
    val uefiVars: File = child("uefi-vars.fd")
    val kernel: File = child("vmlinuz-virt")
    val rawKernel: File = child("vmlinuz-virt.raw")
    val rawKernelDigestStamp: File = child(".vmlinuz-virt.raw.digest")
    val initrd: File = child("initrd.img")
    val rootfs: File = child("alpine-rootfs.squashfs")
    val assetStamp: File = child(".assets_stamp")

    /** QEMU's `-L` data/search directory; contains efi-virtio.rom and keymaps/. */
    val qemuDataDirectory: File = instanceDirectory
    val qemuEfiRom: File = child("efi-virtio.rom")
    val qemuKeymapsDirectory: File = child("keymaps")

    val serialSocket: File = child("serial.sock")
    val terminalSocket: File = child("terminal.sock")
    val controlSocket: File = child("ctrl.sock")
    val hostSocket: File = child("host.sock")
    val qmpSocket: File = child("qmp.sock")
    val qemuOwnerRecord: File = child(".qemu-owner")
    val avfTerminalSocket: File = child("avf-terminal.sock")
    val avfControlSocket: File = child("avf-ctrl.sock")
    val consoleLog: File = child("console.log")

    val qemuWorkingDirectory: File = instanceDirectory
    val avfWorkingDirectory: File = instanceDirectory

    /** Exact named VM files, used by confinement/uniqueness regression tests. */
    internal val namedFiles: Map<String, File> = linkedMapOf(
        "storage" to storageImage,
        "uefiVars" to uefiVars,
        "kernel" to kernel,
        "rawKernel" to rawKernel,
        "rawKernelDigestStamp" to rawKernelDigestStamp,
        "initrd" to initrd,
        "rootfs" to rootfs,
        "assetStamp" to assetStamp,
        "qemuEfiRom" to qemuEfiRom,
        "qemuKeymaps" to qemuKeymapsDirectory,
        "serialSocket" to serialSocket,
        "terminalSocket" to terminalSocket,
        "controlSocket" to controlSocket,
        "hostSocket" to hostSocket,
        "qmpSocket" to qmpSocket,
        "qemuOwnerRecord" to qemuOwnerRecord,
        "avfTerminalSocket" to avfTerminalSocket,
        "avfControlSocket" to avfControlSocket,
        "consoleLog" to consoleLog,
    )

    private val normalizedFilesDirectory: Path = filesDirectory.toPath().toAbsolutePath().normalize()

    private fun child(name: String): File {
        require(name.isNotEmpty() && name != "." && name != ".." &&
            !name.contains('/') && !name.contains('\\')) {
            "VM path child must be one safe path segment"
        }
        return confined(instanceDirectory.toPath().resolve(name))
    }

    private fun confined(candidate: Path): File {
        val normalized = candidate.toAbsolutePath().normalize()
        require(normalized.startsWith(filesDirectory.toPath().toAbsolutePath().normalize())) {
            "VM path escapes filesDir: $normalized"
        }
        return normalized.toFile()
    }

    init {
        val normalizedInstance = instanceDirectory.toPath().toAbsolutePath().normalize()
        val expected = normalizedFilesDirectory
            .resolve(INSTANCES_DIRECTORY)
            .resolve(vmId.serialized)
            .normalize()
        require(normalizedInstance == expected && normalizedInstance != normalizedFilesDirectory) {
            "Invalid VM instance root"
        }
        require(namedFiles.values.all {
            it.toPath().toAbsolutePath().normalize().startsWith(normalizedInstance)
        }) { "A VM path escaped its instance root" }
    }

    companion object {
        const val INSTANCES_DIRECTORY = "instances"

        fun default(filesDirectory: File): VmPaths = of(filesDirectory, VmId.DEFAULT)

        fun of(filesDirectory: File, vmId: VmId): VmPaths {
            require(vmId == VmId.DEFAULT) { "Only the default VM is supported" }
            return VmPaths(filesDirectory.absoluteFile, vmId)
        }
    }
}
