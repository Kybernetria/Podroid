/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.vm

import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Moves the pre-ticket-#6 VM layout from filesDir into the default instance.
 *
 * Each entry is renamed on the same filesystem. A process interruption can
 * therefore leave only whole entries on either side; the next run accepts an
 * already-moved destination when its legacy source is absent and continues.
 * Conflicting source+destination entries and every symbolic link fail closed.
 */
class LegacyVmFilesMigration(
    filesDirectory: java.io.File,
    private val paths: VmPaths,
) {
    private val filesRoot = filesDirectory.toPath().toAbsolutePath().normalize()
    private val instancesRoot = paths.instancesDirectory.toPath().toAbsolutePath().normalize()
    private val instanceRoot = paths.instanceDirectory.toPath().toAbsolutePath().normalize()

    @Synchronized
    @Throws(IOException::class)
    fun migrate() {
        require(paths.vmId == VmId.DEFAULT) { "Legacy migration is defined only for the default VM" }
        require(instanceRoot == filesRoot.resolve(VmPaths.INSTANCES_DIRECTORY).resolve("default")) {
            "Unexpected default instance path"
        }

        verifyExistingPath(filesRoot, expectedDirectory = true)
        verifyDestinationAncestors()

        val plans = LEGACY_ENTRIES.map { entry ->
            val source = confined(filesRoot.resolve(entry.name), filesRoot)
            val destination = confined(instanceRoot.resolve(entry.name), instanceRoot)
            MovePlan(entry, source, destination)
        }

        // Validate the complete decision before the first rename. This makes a
        // collision or symlink deterministic and prevents avoidable partial work.
        for (plan in plans) preflight(plan)

        createDirectorySafely(instancesRoot)
        createDirectorySafely(instanceRoot)

        for (plan in plans) {
            if (!existsNoFollow(plan.source)) continue
            if (existsNoFollow(plan.destination)) {
                throw IOException("Refusing to overwrite existing VM path: ${plan.destination}")
            }
            try {
                // Source and destination are inside filesDir, so ATOMIC_MOVE is
                // available on Android's app filesystem. It leaves a complete
                // entry on one side if the process is interrupted.
                Files.move(plan.source, plan.destination, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: FileAlreadyExistsException) {
                throw IOException("Refusing to overwrite existing VM path: ${plan.destination}", e)
            } catch (e: IOException) {
                throw IOException("Failed to migrate ${plan.source.fileName}: ${e.message}", e)
            }
        }
    }

    private fun verifyDestinationAncestors() {
        if (existsNoFollow(instancesRoot)) verifyExistingPath(instancesRoot, expectedDirectory = true)
        if (existsNoFollow(instanceRoot)) {
            verifyExistingPath(instanceRoot, expectedDirectory = true)
            scanWithoutSymlinks(instanceRoot)
        }
    }

    private fun preflight(plan: MovePlan) {
        val sourceExists = existsNoFollow(plan.source)
        val destinationExists = existsNoFollow(plan.destination)
        if (sourceExists) verifyEntry(plan.source, plan.entry)
        if (destinationExists) verifyEntry(plan.destination, plan.entry)
        if (sourceExists && destinationExists) {
            throw IOException(
                "Legacy VM path collision: ${plan.source.fileName} exists at source and destination"
            )
        }
    }

    private fun verifyEntry(path: Path, entry: LegacyEntry) {
        if (Files.isSymbolicLink(path)) throw IOException("Symbolic link rejected: $path")
        val attributes = Files.readAttributes(
            path,
            java.nio.file.attribute.BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        when (entry.kind) {
            EntryKind.DIRECTORY -> {
                if (!attributes.isDirectory) throw IOException("Expected directory at $path")
                scanWithoutSymlinks(path)
            }
            EntryKind.REGULAR_FILE ->
                if (!attributes.isRegularFile) throw IOException("Expected regular file at $path")
            EntryKind.SOCKET_OR_FILE ->
                if (!attributes.isRegularFile && !attributes.isOther) {
                    throw IOException("Expected socket or regular file at $path")
                }
        }
    }

    private fun scanWithoutSymlinks(root: Path) {
        var entries = 0
        Files.walk(root).use { stream ->
            val iterator = stream.iterator()
            while (iterator.hasNext()) {
                val path = iterator.next()
                entries++
                if (entries > MAX_SCANNED_ENTRIES) {
                    throw IOException("VM path tree exceeds migration safety bound")
                }
                if (Files.isSymbolicLink(path)) throw IOException("Symbolic link rejected: $path")
            }
        }
    }

    private fun createDirectorySafely(path: Path) {
        if (existsNoFollow(path)) {
            verifyExistingPath(path, expectedDirectory = true)
            return
        }
        val parent = path.parent
        if (parent != null && !existsNoFollow(parent)) createDirectorySafely(parent)
        try {
            Files.createDirectory(path)
        } catch (e: FileAlreadyExistsException) {
            // A concurrent creator is acceptable only if it created the exact
            // non-symlink directory expected here.
            verifyExistingPath(path, expectedDirectory = true)
        }
        verifyExistingPath(path, expectedDirectory = true)
    }

    private fun verifyExistingPath(path: Path, expectedDirectory: Boolean) {
        if (Files.isSymbolicLink(path)) throw IOException("Symbolic link rejected: $path")
        if (expectedDirectory && !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("Expected directory at $path")
        }
    }

    private fun confined(candidate: Path, root: Path): Path {
        val normalized = candidate.toAbsolutePath().normalize()
        if (!normalized.startsWith(root) || normalized == root) {
            throw IOException("VM migration path escapes its root: $normalized")
        }
        return normalized
    }

    private fun existsNoFollow(path: Path): Boolean = Files.exists(path, LinkOption.NOFOLLOW_LINKS)

    private data class MovePlan(
        val entry: LegacyEntry,
        val source: Path,
        val destination: Path,
    )

    private data class LegacyEntry(val name: String, val kind: EntryKind)
    private enum class EntryKind { DIRECTORY, REGULAR_FILE, SOCKET_OR_FILE }

    companion object {
        private const val MAX_SCANNED_ENTRIES = 10_000

        /** Exact files previously read or written directly below filesDir. */
        private val LEGACY_ENTRIES = listOf(
            LegacyEntry("storage.img", EntryKind.REGULAR_FILE),
            LegacyEntry("vmlinuz-virt", EntryKind.REGULAR_FILE),
            LegacyEntry("vmlinuz-virt.raw", EntryKind.REGULAR_FILE),
            LegacyEntry("initrd.img", EntryKind.REGULAR_FILE),
            LegacyEntry("alpine-rootfs.squashfs", EntryKind.REGULAR_FILE),
            LegacyEntry(".assets_stamp", EntryKind.REGULAR_FILE),
            LegacyEntry("efi-virtio.rom", EntryKind.REGULAR_FILE),
            LegacyEntry("keymaps", EntryKind.DIRECTORY),
            // Accepted for compatibility with builds that retained the asset's
            // top-level directory instead of flattening qemu/* into filesDir.
            LegacyEntry("qemu", EntryKind.DIRECTORY),
            LegacyEntry("serial.sock", EntryKind.SOCKET_OR_FILE),
            LegacyEntry("terminal.sock", EntryKind.SOCKET_OR_FILE),
            LegacyEntry("ctrl.sock", EntryKind.SOCKET_OR_FILE),
            LegacyEntry("host.sock", EntryKind.SOCKET_OR_FILE),
            LegacyEntry("qmp.sock", EntryKind.SOCKET_OR_FILE),
            LegacyEntry("avf-terminal.sock", EntryKind.SOCKET_OR_FILE),
            LegacyEntry("avf-ctrl.sock", EntryKind.SOCKET_OR_FILE),
            LegacyEntry("console.log", EntryKind.REGULAR_FILE),
            LegacyEntry("vmlinuz-virt.tmp", EntryKind.REGULAR_FILE),
            LegacyEntry("initrd.img.tmp", EntryKind.REGULAR_FILE),
            LegacyEntry("alpine-rootfs.squashfs.tmp", EntryKind.REGULAR_FILE),
            LegacyEntry("efi-virtio.rom.tmp", EntryKind.REGULAR_FILE),
        )
    }
}
