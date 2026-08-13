package com.excp.podroid.vm

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BlankStorageImageMarkerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `new image is marked before guest formatting`() {
        val image = File(temporaryFolder.root, "storage.img")

        BlankStorageImageMarker.create(image, 4096)

        assertEquals(4096L, image.length())
        val marker = ByteArray(BlankStorageImageMarker.VALUE.length)
        image.inputStream().use { assertEquals(marker.size, it.read(marker)) }
        assertArrayEquals(BlankStorageImageMarker.VALUE.toByteArray(Charsets.US_ASCII), marker)
    }

    @Test
    fun `marker is not present on an existing unmarked image`() {
        val image = File(temporaryFolder.root, "storage.img").apply {
            writeBytes(ByteArray(4096))
        }

        val marker = ByteArray(BlankStorageImageMarker.VALUE.length)
        image.inputStream().use { assertEquals(marker.size, it.read(marker)) }
        assertFalse(marker.contentEquals(BlankStorageImageMarker.VALUE.toByteArray(Charsets.US_ASCII)))
        assertTrue(image.length() >= BlankStorageImageMarker.VALUE.length)
    }
}
