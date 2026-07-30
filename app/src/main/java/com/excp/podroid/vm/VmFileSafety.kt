/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.vm

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.ArrayDeque

/** Physical, NOFOLLOW checks for the app-private default VM tree. */
class VmPathSecurity(private val paths: VmPaths) {
    private val filesRoot = paths.filesDirectory.toPath().toAbsolutePath().normalize()
    private val instancesRoot = paths.instancesDirectory.toPath().toAbsolutePath().normalize()
    private val instanceRoot = paths.instanceDirectory.toPath().toAbsolutePath().normalize()
    private val runtimeOwner = paths.qemuOwnerRecord.toPath().toAbsolutePath().normalize()
    private val runtimeEndpoints = setOf(
        paths.serialSocket,
        paths.terminalSocket,
        paths.controlSocket,
        paths.hostSocket,
        paths.qmpSocket,
        paths.avfTerminalSocket,
        paths.avfControlSocket,
    ).mapTo(mutableSetOf()) { it.toPath().toAbsolutePath().normalize() }

    /** Validates the hierarchy before cleanup/copy and creates only missing real directories. */
    @Throws(IOException::class)
    fun prepareExtractionLayout() {
        validateAbsoluteAncestors(filesRoot)
        createDirectoryNoFollow(instancesRoot)
        createDirectoryNoFollow(instanceRoot)
        validateHierarchy()
    }

    /** Strict validation after a probe/cleanup has established quiescence. */
    @Throws(IOException::class)
    fun validateForExtraction() {
        validateHierarchy()
        scanInstanceTree(allowRuntimeEndpoints = false)
    }

    /** Asset refresh may coexist with a probed-but-not-yet-reconciled runtime. */
    @Throws(IOException::class)
    fun validateForAssetRefresh() {
        validateHierarchy()
        scanInstanceTree(allowRuntimeEndpoints = true)
    }

    /** Revalidates all physical boot paths immediately before a backend launch. */
    @Throws(IOException::class)
    fun validateForLaunch() {
        validateHierarchy()
        scanInstanceTree(allowRuntimeEndpoints = true)
        for (required in listOf(paths.kernel, paths.initrd, paths.rootfs)) {
            requireRegularFile(required.toPath(), "required VM boot input")
        }
        for (optional in listOf(
            paths.storageImage,
            paths.uefiVars,
            paths.rawKernel,
            paths.rawKernelDigestStamp,
            paths.qemuEfiRom,
            paths.consoleLog,
        )) {
            val path = optional.toPath().toAbsolutePath().normalize()
            if (existsNoFollow(path)) requireRegularFile(path, "VM file")
        }
        val keymaps = paths.qemuKeymapsDirectory.toPath().toAbsolutePath().normalize()
        if (existsNoFollow(keymaps) && !Files.isDirectory(keymaps, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("Expected VM directory at $keymaps")
        }
    }

    /** Validates and creates a directory used by asset extraction. */
    @Throws(IOException::class)
    fun createExtractionDirectory(directory: File) {
        val path = confined(directory.toPath(), allowInstanceRoot = true)
        createDirectoryNoFollow(path)
        validateHierarchyTo(path)
    }

    /** Rejects symlink/special destinations and physically validates every parent directory. */
    @Throws(IOException::class)
    fun validateRegularFileDestination(destination: File) {
        val path = confined(destination.toPath(), allowInstanceRoot = false)
        val parent = path.parent ?: throw IOException("VM file has no parent: $path")
        validateHierarchyTo(parent)
        if (existsNoFollow(path)) requireRegularFile(path, "VM destination")
    }

    private fun validateHierarchy() {
        validateAbsoluteAncestors(filesRoot)
        validateHierarchyTo(instanceRoot)
        val realFilesRoot = filesRoot.toRealPath()
        val realInstanceRoot = instanceRoot.toRealPath()
        val expectedRealInstance = realFilesRoot
            .resolve(VmPaths.INSTANCES_DIRECTORY)
            .resolve(paths.vmId.serialized)
            .normalize()
        if (realInstanceRoot != expectedRealInstance) {
            throw IOException("VM instance is not physically confined to filesDir: $realInstanceRoot")
        }
    }

    private fun validateHierarchyTo(path: Path) {
        val normalized = confined(path, allowInstanceRoot = true)
        var current = filesRoot
        requireDirectory(current)
        val relative = filesRoot.relativize(normalized)
        for (segment in relative) {
            current = current.resolve(segment)
            requireDirectory(current)
        }
        val realRoot = filesRoot.toRealPath()
        val realPath = normalized.toRealPath()
        if (!realPath.startsWith(realRoot)) {
            throw IOException("VM path escapes filesDir physically: $normalized -> $realPath")
        }
    }

    private fun validateAbsoluteAncestors(path: Path) {
        var current = path.root ?: throw IOException("VM path is not absolute: $path")
        requireDirectory(current)
        for (segment in path) {
            current = current.resolve(segment)
            requireDirectory(current)
        }
    }

    private fun createDirectoryNoFollow(path: Path) {
        val normalized = path.toAbsolutePath().normalize()
        if (existsNoFollow(normalized)) {
            requireDirectory(normalized)
            return
        }
        val parent = normalized.parent ?: throw IOException("Directory has no parent: $normalized")
        if (!existsNoFollow(parent)) createDirectoryNoFollow(parent)
        requireDirectory(parent)
        try {
            Files.createDirectory(normalized)
            forceDirectory(parent)
        } catch (e: FileAlreadyExistsException) {
            requireDirectory(normalized)
        }
        requireDirectory(normalized)
    }

    private fun scanInstanceTree(allowRuntimeEndpoints: Boolean) {
        var entries = 0
        val pending = ArrayDeque<Pair<Path, Int>>()
        pending.add(instanceRoot to 0)
        while (pending.isNotEmpty()) {
            val (directory, depth) = pending.removeFirst()
            Files.newDirectoryStream(directory).use { stream ->
                for (entry in stream) {
                    entries++
                    if (entries > MAX_TREE_ENTRIES) {
                        throw IOException("VM path tree exceeds safety entry bound")
                    }
                    val normalized = confined(entry, allowInstanceRoot = false)
                    val attrs = attributesNoFollow(normalized)
                    when {
                        attrs.isSymbolicLink -> throw IOException("Symbolic link rejected: $normalized")
                        attrs.isDirectory -> {
                            if (depth >= MAX_TREE_DEPTH) {
                                throw IOException("VM path tree exceeds safety depth bound")
                            }
                            pending.add(normalized to depth + 1)
                        }
                        attrs.isRegularFile -> if (!allowRuntimeEndpoints && normalized == runtimeOwner) {
                            throw IOException("Live runtime ownership record rejected: $normalized")
                        }
                        allowRuntimeEndpoints && normalized in runtimeEndpoints -> Unit
                        else -> throw IOException("Special VM path rejected: $normalized")
                    }
                }
            }
        }
    }

    private fun requireDirectory(path: Path) {
        val attrs = attributesNoFollow(path)
        if (attrs.isSymbolicLink || !attrs.isDirectory) {
            throw IOException("Expected non-symlink directory at $path")
        }
    }

    private fun requireRegularFile(path: Path, description: String) {
        val attrs = attributesNoFollow(path)
        if (attrs.isSymbolicLink || !attrs.isRegularFile) {
            throw IOException("Expected regular $description at $path")
        }
    }

    private fun confined(candidate: Path, allowInstanceRoot: Boolean): Path {
        val normalized = candidate.toAbsolutePath().normalize()
        if (!normalized.startsWith(instanceRoot) || (!allowInstanceRoot && normalized == instanceRoot)) {
            throw IOException("VM path escapes instance root: $normalized")
        }
        return normalized
    }

    private fun attributesNoFollow(path: Path): BasicFileAttributes = Files.readAttributes(
        path,
        BasicFileAttributes::class.java,
        LinkOption.NOFOLLOW_LINKS,
    )

    private fun existsNoFollow(path: Path): Boolean = Files.exists(path, LinkOption.NOFOLLOW_LINKS)

    companion object {
        private const val MAX_TREE_DEPTH = 32
        private const val MAX_TREE_ENTRIES = 20_000

        internal fun forceDirectory(directory: Path) {
            try {
                FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
            } catch (_: IOException) {
                // Some Android/JVM filesystem providers do not permit opening a
                // directory as a FileChannel. Data files are still force()d;
                // directory durability is best-effort only on those providers.
            }
        }
    }
}

/** Iterative, bounded stale-temp cleanup that never follows links. */
internal class StaleTmpFileCleaner(
    private val maxDepth: Int = 32,
    private val maxEntries: Int = 20_000,
    allowedSpecialFiles: Set<File> = emptySet(),
) {
    private val allowedSpecialPaths = allowedSpecialFiles.mapTo(mutableSetOf()) {
        it.toPath().toAbsolutePath().normalize()
    }
    init {
        require(maxDepth >= 0)
        require(maxEntries > 0)
    }

    @Throws(IOException::class)
    fun clean(rootDirectory: File) {
        val root = rootDirectory.toPath().toAbsolutePath().normalize()
        val rootAttrs = attributesNoFollow(root)
        if (rootAttrs.isSymbolicLink || !rootAttrs.isDirectory) {
            throw IOException("Stale-temp root is not a real directory: $root")
        }

        var entries = 0
        val staleFiles = ArrayList<Path>()
        val pending = ArrayDeque<Pair<Path, Int>>()
        pending.add(root to 0)
        while (pending.isNotEmpty()) {
            val (directory, depth) = pending.removeFirst()
            Files.newDirectoryStream(directory).use { stream ->
                for (entry in stream) {
                    entries++
                    if (entries > maxEntries) throw IOException("Stale-temp cleanup entry bound exceeded")
                    val normalized = entry.toAbsolutePath().normalize()
                    if (!normalized.startsWith(root) || normalized == root) {
                        throw IOException("Stale-temp path escaped root: $normalized")
                    }
                    val attrs = attributesNoFollow(normalized)
                    when {
                        attrs.isSymbolicLink -> throw IOException("Symbolic link rejected during stale-temp cleanup: $normalized")
                        attrs.isDirectory -> {
                            if (depth >= maxDepth) throw IOException("Stale-temp cleanup depth bound exceeded")
                            pending.add(normalized to depth + 1)
                        }
                        attrs.isRegularFile -> if (normalized.fileName.toString().endsWith(TMP_SUFFIX)) {
                            staleFiles.add(normalized)
                        }
                        normalized in allowedSpecialPaths -> Unit
                        else -> throw IOException("Special file rejected during stale-temp cleanup: $normalized")
                    }
                }
            }
        }
        // Delete only after the complete bounded decision succeeds, so a late
        // symlink/special/bounds failure cannot leave a partial cleanup result.
        for (staleFile in staleFiles) {
            val attrs = attributesNoFollow(staleFile)
            if (attrs.isSymbolicLink || !attrs.isRegularFile) {
                throw IOException("Stale temp changed type before deletion: $staleFile")
            }
            Files.delete(staleFile)
        }
        if (staleFiles.isNotEmpty()) VmPathSecurity.forceDirectory(root)
    }

    private fun attributesNoFollow(path: Path): BasicFileAttributes = Files.readAttributes(
        path,
        BasicFileAttributes::class.java,
        LinkOption.NOFOLLOW_LINKS,
    )

    companion object {
        const val TMP_SUFFIX = ".tmp"
    }
}

/** Exclusive/no-follow temp creation followed by a durable atomic replacement. */
object VmAtomicFile {
    @Throws(IOException::class)
    fun write(
        destination: File,
        pathSecurity: VmPathSecurity,
        writer: (OutputStream) -> Unit,
    ) {
        pathSecurity.validateRegularFileDestination(destination)
        val destinationPath = destination.toPath().toAbsolutePath().normalize()
        val temporaryPath = destinationPath.resolveSibling(destinationPath.fileName.toString() + StaleTmpFileCleaner.TMP_SUFFIX)
        var created = false
        try {
            val options = setOf<OpenOption>(
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            )
            FileChannel.open(temporaryPath, options).use { channel ->
                created = true
                val output = Channels.newOutputStream(channel)
                writer(output)
                output.flush()
                channel.force(true)
            }
            pathSecurity.validateRegularFileDestination(destination)
            Files.move(
                temporaryPath,
                destinationPath,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            VmPathSecurity.forceDirectory(destinationPath.parent)
        } catch (t: Throwable) {
            if (created) runCatching { Files.deleteIfExists(temporaryPath) }
            throw t
        }
    }
}
