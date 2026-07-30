package com.excp.podroid.engine.avf

import com.excp.podroid.vm.VmAtomicFile
import com.excp.podroid.vm.VmBootArtifacts
import com.excp.podroid.vm.VmBootDigest
import com.excp.podroid.vm.VmPathSecurity
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** Durable AVF raw-kernel cache keyed only by kernel content identity, never timestamps. */
internal object AvfRawKernelCache {
    private const val CACHE_VERSION = "v1"
    private const val MAX_RAW_KERNEL_BYTES = 4L * 1024 * 1024 * 1024
    private const val BUFFER_BYTES = 64 * 1024
    private const val MAX_STAMP_BYTES = 160

    @Synchronized
    @Throws(IOException::class)
    fun prepare(
        source: File,
        sourceDigest: VmBootDigest,
        raw: File,
        stamp: File,
        pathSecurity: VmPathSecurity,
    ): File {
        val magic = ByteArray(2)
        val magicBytes = source.inputStream().use { it.read(magic) }
        if (magicBytes < magic.size || magic[0] != 0x1f.toByte() || magic[1] != 0x8b.toByte()) {
            return source
        }

        pathSecurity.validateRegularFileDestination(raw)
        pathSecurity.validateRegularFileDestination(stamp)
        if (raw.exists() && validCache(raw, stamp, sourceDigest)) return raw

        // Invalidate first. A crash after replacing raw but before publishing its stamp must not
        // let that raw file be mistaken for the previous digest on a later process start.
        if (Files.exists(stamp.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            Files.delete(stamp.toPath())
            VmPathSecurity.forceDirectory(stamp.parentFile.toPath())
        }

        val rawDigest = MessageDigest.getInstance("SHA-256")
        var rawSizeBytes = 0L
        VmAtomicFile.write(raw, pathSecurity) { output ->
            GZIPInputStream(source.inputStream().buffered()).use { gzip ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val count = gzip.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    rawSizeBytes += count
                    if (rawSizeBytes > MAX_RAW_KERNEL_BYTES) {
                        throw IOException("decompressed AVF kernel exceeds the supported byte bound")
                    }
                    rawDigest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
                if (rawSizeBytes == 0L) throw IOException("decompressed AVF kernel is empty")
            }
        }
        val rawSha256 = rawDigest.digest().toLowerHex()
        val durableStamp = "$CACHE_VERSION:${sourceDigest.value}:$rawSha256:$rawSizeBytes"
        VmAtomicFile.write(stamp, pathSecurity) { output ->
            output.write(durableStamp.toByteArray(Charsets.US_ASCII))
        }
        return raw
    }

    private fun validCache(raw: File, stamp: File, sourceDigest: VmBootDigest): Boolean {
        val fields = readStamp(stamp)?.split(':') ?: return false
        if (fields.size != 4 || fields[0] != CACHE_VERSION || fields[1] != sourceDigest.value) return false
        val expectedRawDigest = runCatching { VmBootDigest(fields[2]) }.getOrNull() ?: return false
        val expectedRawSize = fields[3].toLongOrNull()?.takeIf { it in 1..MAX_RAW_KERNEL_BYTES } ?: return false
        if (raw.length() != expectedRawSize) return false
        return runCatching { VmBootArtifacts.digest(raw) }.getOrNull() == expectedRawDigest
    }

    private fun readStamp(stamp: File): String? {
        if (!Files.exists(stamp.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        return runCatching {
            FileChannel.open(stamp.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                val size = channel.size()
                if (size !in 1..MAX_STAMP_BYTES.toLong()) return null
                val bytes = ByteBuffer.allocate(size.toInt())
                while (bytes.hasRemaining()) {
                    if (channel.read(bytes) <= 0) return null
                }
                String(bytes.array(), Charsets.US_ASCII)
            }
        }.getOrNull()
    }

    private fun ByteArray.toLowerHex(): String = joinToString("") {
        (it.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}
