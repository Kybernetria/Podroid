package com.excp.podroid.vm

import com.excp.podroid.engine.VmConfig
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** Signed SHA-256 identity used by backend-neutral VM boot inputs and manifests. */
@JvmInline
value class VmBootDigest(val value: String) {
    init {
        require(value.matches(LOWERCASE_SHA256)) {
            "VM boot digest must be exactly 64 lowercase hexadecimal characters"
        }
    }

    private companion object {
        val LOWERCASE_SHA256 = Regex("[0-9a-f]{64}")
    }
}

/** Monotonic profile generation selected for one complete launch. */
@JvmInline
value class VmBootGeneration(val value: Long) {
    init {
        require(value > 0) { "VM boot generation must be positive" }
    }
}

/** One immutable, digest-addressed boot input. */
data class VmBootArtifact(
    val file: File,
    val sha256: VmBootDigest,
) {
    init {
        require(file.isAbsolute) { "VM boot artifact path must be absolute" }
    }
}

/**
 * Distro-neutral, all-or-nothing boot artifact generation. Null at the [VmConfig] boundary means
 * the bundled legacy paths remain authoritative.
 */
data class VmBootArtifacts(
    val generation: VmBootGeneration,
    val manifestSha256: VmBootDigest,
    val kernel: VmBootArtifact,
    val initrd: VmBootArtifact,
    val rootfs: VmBootArtifact,
) {
    /** Revalidates every hostile file immediately before a backend consumes this generation. */
    @Throws(IOException::class)
    fun validateFiles() {
        listOf(kernel, initrd, rootfs).forEach { artifact ->
            val path = artifact.file.toPath().toAbsolutePath().normalize()
            val attributes = Files.readAttributes(
                path,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            if (attributes.isSymbolicLink || !attributes.isRegularFile) {
                throw IOException("VM boot artifact is not a regular file: $path")
            }
            if (attributes.size() !in 1..MAX_ARTIFACT_BYTES) {
                throw IOException("VM boot artifact is outside the supported byte bound: $path")
            }
            val actual = sha256(path.toFile(), MAX_ARTIFACT_BYTES)
            if (actual != artifact.sha256) {
                throw IOException("VM boot artifact digest mismatch: $path")
            }
        }
    }

    companion object {
        private const val MAX_ARTIFACT_BYTES = 4L * 1024 * 1024 * 1024
        private const val BUFFER_BYTES = 64 * 1024

        @Throws(IOException::class)
        internal fun digest(file: File): VmBootDigest = sha256(file, MAX_ARTIFACT_BYTES)

        private fun sha256(file: File, maxBytes: Long): VmBootDigest {
            val digest = MessageDigest.getInstance("SHA-256")
            FileChannel.open(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.allocate(BUFFER_BYTES)
                var totalBytes = 0L
                var zeroReads = 0
                while (true) {
                    buffer.clear()
                    val count = channel.read(buffer)
                    if (count < 0) break
                    if (count == 0) {
                        zeroReads++
                        if (zeroReads > 16) throw IOException("VM boot artifact validation made no progress")
                        continue
                    }
                    zeroReads = 0
                    totalBytes += count
                    if (totalBytes > maxBytes) throw IOException("VM boot artifact exceeds the supported byte bound")
                    digest.update(buffer.array(), 0, count)
                }
                if (totalBytes == 0L) throw IOException("VM boot artifact is empty")
            }
            return VmBootDigest(digest.digest().joinToString("") {
                (it.toInt() and 0xff).toString(16).padStart(2, '0')
            })
        }
    }
}

/** Effective paths consumed identically by both concrete backends. */
internal data class VmBootFiles(
    val kernel: File,
    val initrd: File,
    val rootfs: File,
    val kernelDigest: VmBootDigest?,
)

internal fun VmConfig.bootFiles(paths: VmPaths): VmBootFiles = bootArtifacts?.let { artifacts ->
    VmBootFiles(
        kernel = artifacts.kernel.file,
        initrd = artifacts.initrd.file,
        rootfs = artifacts.rootfs.file,
        kernelDigest = artifacts.kernel.sha256,
    )
} ?: VmBootFiles(
    kernel = paths.kernel,
    initrd = paths.initrd,
    rootfs = paths.rootfs,
    kernelDigest = null,
)
