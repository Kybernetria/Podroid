/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.vm

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * Anchors app-controlled paths below filesDir without imposing policy on Android-managed ancestors.
 *
 * Android may expose credential-encrypted app storage through system aliases above filesDir. The
 * filesDir leaf and every relative app-controlled component remain NOFOLLOW directories, while the
 * canonical filesDir location is used as the physical confinement anchor.
 */
internal object AppPrivatePathSecurity {
    @Throws(IOException::class)
    fun realDirectoryAnchor(directory: Path, label: String): Path {
        val normalized = directory.toAbsolutePath().normalize()
        val attributes = attributesNoFollow(normalized)
        if (attributes.isSymbolicLink || !attributes.isDirectory) {
            throw IOException("Expected non-symlink $label directory at $normalized")
        }
        return normalized.toRealPath()
    }

    @Throws(IOException::class)
    fun requireDirectoryDescendant(
        filesDirectory: Path,
        directory: Path,
        label: String,
        allowFilesDirectory: Boolean = true,
    ): Path {
        val filesRoot = filesDirectory.toAbsolutePath().normalize()
        val normalized = directory.toAbsolutePath().normalize()
        if (!normalized.startsWith(filesRoot) || (!allowFilesDirectory && normalized == filesRoot)) {
            throw IOException("$label directory escapes filesDir: $normalized")
        }

        val realFilesRoot = realDirectoryAnchor(filesRoot, "filesDir")
        var current = filesRoot
        for (segment in filesRoot.relativize(normalized)) {
            current = current.resolve(segment)
            val attributes = attributesNoFollow(current)
            if (attributes.isSymbolicLink || !attributes.isDirectory) {
                throw IOException("Expected non-symlink $label directory at $current")
            }
        }

        val expected = realFilesRoot.resolve(filesRoot.relativize(normalized)).normalize()
        val actual = normalized.toRealPath()
        if (actual != expected) {
            throw IOException("$label directory escapes filesDir physically: $normalized -> $actual")
        }
        return actual
    }

    private fun attributesNoFollow(path: Path): BasicFileAttributes = Files.readAttributes(
        path,
        BasicFileAttributes::class.java,
        LinkOption.NOFOLLOW_LINKS,
    )
}
