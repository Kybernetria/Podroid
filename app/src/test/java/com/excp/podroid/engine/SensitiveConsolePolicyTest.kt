package com.excp.podroid.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveConsolePolicyTest {
    @Test
    fun `advanced values disable persisted capture for both backend inputs`() {
        assertFalse(SensitiveConsolePolicy.persistedCaptureAllowed("--sentinel-qemu", ""))
        assertFalse(SensitiveConsolePolicy.persistedCaptureAllowed("", "sentinel-kernel=secret"))
        assertTrue(SensitiveConsolePolicy.persistedCaptureAllowed("  ", "\n"))
    }

    @Test
    fun `export omits captured sentinel and redacts configured values defensively`() {
        val qemuSentinel = "--password=SENTINEL_QEMU_SECRET"
        val kernelSentinel = "token=SENTINEL_KERNEL_SECRET"
        val capturedConsole = "boot $qemuSentinel $kernelSentinel\n"

        val consoleSection = SensitiveConsolePolicy.consoleForExport(
            capturedConsole,
            qemuSentinel,
            kernelSentinel,
        )
        val report = SensitiveConsolePolicy.redactConfiguredValues(
            "settings=$qemuSentinel\nlogcat=$kernelSentinel\n$consoleSection",
            qemuSentinel,
            kernelSentinel,
        )

        assertTrue(report.contains(SensitiveConsolePolicy.OMITTED_MESSAGE))
        assertTrue(report.contains("[redacted advanced setting]"))
        assertFalse(report.contains("SENTINEL_QEMU_SECRET"))
        assertFalse(report.contains("SENTINEL_KERNEL_SECRET"))
        assertFalse(report.contains("boot --password"))
    }
}
