/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.vm

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.ArrayDeque

/**
 * Moves the pre-ticket-#6 VM layout from filesDir into the default instance.
 *
 * The migration is serialized across threads and app processes by an OS lock.
 * Each same-filesystem move uses Files.move without ATOMIC_MOVE: unlike Java's
 * ATOMIC_MOVE contract, this operation is specified to reject an existing
 * target. A process interruption can leave only whole entries on either side;
 * the next run accepts an already-moved destination when its source is absent.
 */
class LegacyVmFilesMigration internal constructor(
    filesDirectory: java.io.File,
    private val paths: VmPaths,
    private val beforeMoveForTest: ((Path, Path) -> Unit)?,
) {
    constructor(filesDirectory: java.io.File, paths: VmPaths) : this(filesDirectory, paths, null)

    private val filesRoot = filesDirectory.toPath().toAbsolutePath().normalize()
    private val instancesRoot = paths.instancesDirectory.toPath().toAbsolutePath().normalize()
    private val instanceRoot = paths.instanceDirectory.toPath().toAbsolutePath().normalize()
    private val lockPath = filesRoot.resolve(LOCK_FILE_NAME)

    @Throws(IOException::class)
    fun migrate() {
        require(paths.vmId == VmId.DEFAULT) { "Legacy migration is defined only for the default VM" }
        require(instanceRoot == filesRoot.resolve(VmPaths.INSTANCES_DIRECTORY).resolve("default")) {
            "Unexpected default instance path"
        }

        synchronized(PROCESS_LOCK) {
            verifyAbsoluteDirectoryAncestors(filesRoot)
            val options = setOf<OpenOption>(
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            )
            FileChannel.open(lockPath, options).use { channel ->
                channel.lock().use {
                    verifyRegularFile(lockPath)
                    migrateLocked()
                }
            }
        }
    }

    private fun migrateLocked() {
        verifyExistingPath(filesRoot, expectedDirectory = true)
        verifyDestinationAncestors()

        val plans = LEGACY_ENTRIES.map { entry ->
            val source = confined(filesRoot.resolve(entry.name), filesRoot)
            val destination = confined(instanceRoot.resolve(entry.name), instanceRoot)
            MovePlan(entry, source, destination)
        }

        // Validate the complete decision under the process lock before moving.
        for (plan in plans) preflight(plan)

        createDirectorySafely(instancesRoot)
        createDirectorySafely(instanceRoot)

        for (plan in plans) {
            if (!existsNoFollow(plan.source)) continue
            verifyEntry(plan.source, plan.entry, syncData = true)
            if (existsNoFollow(plan.destination)) {
                throw IOException("Refusing to overwrite existing VM path: ${plan.destination}")
            }
            beforeMoveForTest?.invoke(plan.source, plan.destination)
            try {
                // No options means no replacement by the Files.move contract.
                // Source and target are under one app filesDir filesystem.
                Files.move(plan.source, plan.destination)
            } catch (e: FileAlreadyExistsException) {
                throw IOException("Refusing to overwrite existing VM path: ${plan.destination}", e)
            } catch (e: IOException) {
                throw IOException("Failed to migrate ${plan.source.fileName}: ${e.message}", e)
            }
            verifyEntry(plan.destination, plan.entry, syncData = false)
            VmPathSecurity.forceDirectory(filesRoot)
            VmPathSecurity.forceDirectory(instanceRoot)
        }
    }

    private fun verifyDestinationAncestors() {
        if (existsNoFollow(instancesRoot)) verifyExistingPath(instancesRoot, expectedDirectory = true)
        if (existsNoFollow(instanceRoot)) {
            verifyExistingPath(instanceRoot, expectedDirectory = true)
            scanWithoutSymlinks(instanceRoot, syncData = false)
        }
    }

    private fun preflight(plan: MovePlan) {
        val sourceExists = existsNoFollow(plan.source)
        val destinationExists = existsNoFollow(plan.destination)
        if (sourceExists) verifyEntry(plan.source, plan.entry, syncData = false)
        if (destinationExists) verifyEntry(plan.destination, plan.entry, syncData = false)
        if (sourceExists && destinationExists) {
            throw IOException(
                "Legacy VM path collision: ${plan.source.fileName} exists at source and destination"
            )
        }
    }

    private fun verifyEntry(path: Path, entry: LegacyEntry, syncData: Boolean) {
        val attributes = attributesNoFollow(path)
        if (attributes.isSymbolicLink) throw IOException("Symbolic link rejected: $path")
        when (entry.kind) {
            EntryKind.DIRECTORY -> {
                if (!attributes.isDirectory) throw IOException("Expected directory at $path")
                scanWithoutSymlinks(path, syncData)
            }
            EntryKind.REGULAR_FILE -> {
                if (!attributes.isRegularFile) throw IOException("Expected regular file at $path")
                if (syncData) forceRegularFile(path)
            }
            EntryKind.SOCKET_OR_FILE ->
                if (!attributes.isRegularFile && !attributes.isOther) {
                    throw IOException("Expected socket or regular file at $path")
                } else if (syncData && attributes.isRegularFile) {
                    forceRegularFile(path)
                }
        }
    }

    private fun scanWithoutSymlinks(root: Path, syncData: Boolean) {
        var entries = 0
        val pending = ArrayDeque<Pair<Path, Int>>()
        pending.add(root to 0)
        while (pending.isNotEmpty()) {
            val (directory, depth) = pending.removeFirst()
            Files.newDirectoryStream(directory).use { stream ->
                for (path in stream) {
                    entries++
                    if (entries > MAX_SCANNED_ENTRIES) {
                        throw IOException("VM path tree exceeds migration entry bound")
                    }
                    val attrs = attributesNoFollow(path)
                    when {
                        attrs.isSymbolicLink -> throw IOException("Symbolic link rejected: $path")
                        attrs.isDirectory -> {
                            if (depth >= MAX_SCANNED_DEPTH) {
                                throw IOException("VM path tree exceeds migration depth bound")
                            }
                            pending.add(path to depth + 1)
                        }
                        attrs.isRegularFile && syncData -> forceRegularFile(path)
                    }
                }
            }
            if (syncData) VmPathSecurity.forceDirectory(directory)
        }
    }

    private fun createDirectorySafely(path: Path) {
        if (existsNoFollow(path)) {
            verifyExistingPath(path, expectedDirectory = true)
            return
        }
        val parent = path.parent ?: throw IOException("Directory has no parent: $path")
        if (!existsNoFollow(parent)) createDirectorySafely(parent)
        verifyExistingPath(parent, expectedDirectory = true)
        try {
            Files.createDirectory(path)
            VmPathSecurity.forceDirectory(parent)
        } catch (e: FileAlreadyExistsException) {
            verifyExistingPath(path, expectedDirectory = true)
        }
        verifyExistingPath(path, expectedDirectory = true)
    }

    private fun verifyAbsoluteDirectoryAncestors(path: Path) {
        var current = path.root ?: throw IOException("VM files path is not absolute: $path")
        verifyExistingPath(current, expectedDirectory = true)
        for (segment in path) {
            current = current.resolve(segment)
            verifyExistingPath(current, expectedDirectory = true)
        }
    }

    private fun verifyExistingPath(path: Path, expectedDirectory: Boolean) {
        val attrs = attributesNoFollow(path)
        if (attrs.isSymbolicLink) throw IOException("Symbolic link rejected: $path")
        if (expectedDirectory && !attrs.isDirectory) throw IOException("Expected directory at $path")
    }

    private fun verifyRegularFile(path: Path) {
        val attrs = attributesNoFollow(path)
        if (attrs.isSymbolicLink || !attrs.isRegularFile) {
            throw IOException("Expected regular lock file at $path")
        }
    }

    private fun forceRegularFile(path: Path) {
        FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) }
    }

    private fun attributesNoFollow(path: Path): BasicFileAttributes = Files.readAttributes(
        path,
        BasicFileAttributes::class.java,
        LinkOption.NOFOLLOW_LINKS,
    )

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
        private val PROCESS_LOCK = Any()
        private const val LOCK_FILE_NAME = ".vm-layout-migration.lock"
        private const val MAX_SCANNED_ENTRIES = 10_000
        private const val MAX_SCANNED_DEPTH = 32

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
