package com.excp.podroid.vm

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Marker written into a newly-created raw storage image before the guest can
 * inspect it. The initramfs consumes this marker only when deciding whether a
 * failed filesystem probe may be formatted.
 */
object BlankStorageImageMarker {
    const val VALUE = "PODROID-BLANK-IMAGE-V1"
    private val bytes = VALUE.toByteArray(Charsets.US_ASCII)

    @Throws(IOException::class)
    fun create(file: File, lengthBytes: Long) {
        require(lengthBytes >= bytes.size) { "storage image is smaller than its blank marker" }
        RandomAccessFile(file, "rw").use { image ->
            image.setLength(lengthBytes)
            image.seek(0)
            image.write(bytes)
            image.fd.sync()
        }
    }
}
