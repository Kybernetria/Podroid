package com.excp.podroid.profiles

import android.system.Os
import android.system.OsConstants
import java.io.FileDescriptor
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Makes directory-entry changes durable. Failure is authoritative and must never be ignored. */
fun interface DirectoryDurability {
    @Throws(IOException::class)
    fun force(directory: Path)
}

/** Pure Java implementation used explicitly by local unit tests. */
object FileChannelDirectoryDurability : DirectoryDurability {
    override fun force(directory: Path) {
        FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
    }
}

/** Android-specific implementation available to a later composition root. */
object AndroidDirectoryDurability : DirectoryDurability {
    // Linux O_DIRECTORY (not exposed by every Android SDK's OsConstants stubs).
    private const val O_DIRECTORY = 0x10000

    override fun force(directory: Path) {
        var descriptor: FileDescriptor? = null
        var failure: Throwable? = null
        try {
            descriptor = Os.open(
                directory.toString(),
                OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or O_DIRECTORY,
                0,
            )
            Os.fsync(descriptor)
        } catch (caught: Throwable) {
            failure = caught
            throw IOException("directory fsync failed for $directory", caught)
        } finally {
            descriptor?.let {
                try {
                    Os.close(it)
                } catch (closeFailure: Throwable) {
                    if (failure != null) failure.addSuppressed(closeFailure)
                    else throw IOException("directory close failed for $directory", closeFailure)
                }
            }
        }
    }
}
