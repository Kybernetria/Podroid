/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.engine

/** Privacy policy shared by both backends and diagnostic export. */
internal object SensitiveConsolePolicy {
    const val OMITTED_MESSAGE =
        "(console capture omitted because advanced QEMU or kernel settings are configured)"

    fun persistedCaptureAllowed(qemuExtraArgs: String, kernelExtraCmdline: String): Boolean =
        qemuExtraArgs.isBlank() && kernelExtraCmdline.isBlank()

    fun consoleForExport(
        consoleText: String?,
        qemuExtraArgs: String,
        kernelExtraCmdline: String,
    ): String = when {
        !persistedCaptureAllowed(qemuExtraArgs, kernelExtraCmdline) -> "$OMITTED_MESSAGE\n"
        consoleText.isNullOrEmpty() -> "(no console.log — VM has not been started this session)\n"
        consoleText.endsWith("\n") -> consoleText
        else -> "$consoleText\n"
    }

    /** Last defensive pass over the complete report, including logcat/engine text. */
    fun redactConfiguredValues(
        report: String,
        qemuExtraArgs: String,
        kernelExtraCmdline: String,
    ): String {
        var redacted = report
        sequenceOf(qemuExtraArgs, kernelExtraCmdline)
            .filter { it.isNotBlank() }
            .distinct()
            .sortedByDescending { it.length }
            .forEach { redacted = redacted.replace(it, "[redacted advanced setting]") }
        return redacted
    }
}
