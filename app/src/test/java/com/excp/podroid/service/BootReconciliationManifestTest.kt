package com.excp.podroid.service

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class BootReconciliationManifestTest {
    @Test fun `manifest uses only post unlock boot and non exported receiver`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android.permission.RECEIVE_BOOT_COMPLETED"))
        assertTrue(manifest.contains(".service.BootCompletedReceiver"))
        assertTrue(manifest.contains("android.intent.action.BOOT_COMPLETED"))
        assertFalse(manifest.contains("LOCKED_BOOT_COMPLETED"))
        val receiver = manifest.substringAfter(".service.BootCompletedReceiver").substringBefore("</receiver>")
        assertTrue(receiver.contains("android:exported=\"false\""))
    }
}
