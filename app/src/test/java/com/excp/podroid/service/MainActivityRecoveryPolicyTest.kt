package com.excp.podroid.service

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityRecoveryPolicyTest {
    @Test fun `exported launcher creation alone cannot dispatch reconciliation`() {
        val activity = File("src/main/java/com/excp/podroid/MainActivity.kt").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val declaration = manifest.substringAfter(".MainActivity").substringBefore("</activity>")

        assertTrue(declaration.contains("android:exported=\"true\""))
        assertFalse(activity.contains("startForegroundService"))
        assertFalse(activity.contains("reconciliationIntent"))
        assertFalse(activity.contains("ACTION_RECONCILE"))
    }
}
