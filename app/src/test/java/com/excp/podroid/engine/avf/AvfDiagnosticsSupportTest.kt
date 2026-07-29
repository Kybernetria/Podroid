package com.excp.podroid.engine.avf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AvfDiagnosticsSupportTest {
    @Test fun `customVmConfigSupported is false when AVF classes absent`() {
        // JVM unit test: android.system.virtualmachine.* is not on the classpath.
        assertFalse(AvfDiagnostics.customVmConfigSupported())
    }

    @Test fun `smoke result is bounded`() {
        assertEquals(4 * 1024, AvfDiagnostics.boundSmokeTestResult("x".repeat(8 * 1024)).length)
    }
}
