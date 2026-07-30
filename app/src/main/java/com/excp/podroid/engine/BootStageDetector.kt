/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.engine

/**
 * Engine-agnostic boot-progress detector. Both engines feed it raw guest
 * console bytes; detected stages are returned through [onStage]. Engine-owned
 * generation gates decide whether a stage may mutate lifecycle state. Each feed
 * scans the newly-appended region plus a short overlap carried from the previous
 * feed, so a marker that
 * straddles a read boundary — or sits early in a single oversized chunk — is
 * still caught (see the history of detectBootStage in PodroidQemu pre-refactor).
 * One-shot: stops scanning after the first "Ready!" to keep readiness idempotent.
 */
class BootStageDetector(
    private val readinessMarker: String = "Ready!",
    private val legacyStagesEnabled: Boolean = true,
    private val onStage: (String) -> Unit,
) {
    init {
        require(readinessMarker.isNotEmpty() && readinessMarker.length <= 128)
    }
    private val buf = StringBuilder()
    private val readinessLine = StringBuilder()
    private var readinessLineOverflow = false
    private val maxKeep = 4096

    val isReady: Boolean get() = ready

    /**
     * Overlap carried across feeds so a marker split between two reads is still
     * matched. (len(longest marker) - 1) chars of the previous feed are
     * re-scanned alongside the new bytes — enough to reconstruct any marker
     * that begins in the prior feed and ends in this one.
     */
    private val overlap = maxOf(MARKERS.maxOf { it.first.length }, readinessMarker.length) - 1

    /** Length of [buf] before the current feed appended — start of "new" text. */
    private var scannedLen = 0
    private var ready = false

    fun feed(bytes: ByteArray, len: Int) {
        if (ready) return
        if (!legacyStagesEnabled) {
            feedExactReadinessLines(bytes, len)
            return
        }
        // Latin-1 decode is byte-safe (1 byte → 1 char) and the ASCII subset
        // matches UTF-8 exactly, so our pure-ASCII markers still match.
        buf.append(String(bytes, 0, len, Charsets.ISO_8859_1))
        if (buf.length > maxKeep) {
            val dropped = buf.length - maxKeep
            buf.delete(0, dropped)
            scannedLen = (scannedLen - dropped).coerceAtLeast(0)
        }
        // Scan the new region plus an overlap into the previously-scanned text,
        // so a marker spanning the boundary is reconstructed. Scanning the
        // whole appended chunk (not a fixed 1024 tail) means a marker buried
        // early in one oversized read is no longer missed.
        val from = (scannedLen - overlap).coerceAtLeast(0)
        val tail = buf.substring(from)
        scannedLen = buf.length
        when {
            tail.contains(readinessMarker)          -> { ready = true; onStage("Ready") }
            tail.contains("Almost ready")           -> onStage("Almost ready...")
            tail.contains("Starting SSH")           -> onStage("Starting SSH...")
            tail.contains("Configuring containers") -> onStage("Configuring containers...")
            tail.contains("Network found")          -> onStage("Network found")
            tail.contains("Loading kernel modules") -> onStage("Loading kernel modules...")
            tail.contains("Mounting storage")       -> onStage("Mounting storage...")
            tail.contains("Booting kernel")         -> onStage("Booting kernel...")
        }
    }

    /** Cloud contracts are admitted only as one complete CR/LF-delimited ASCII line. */
    private fun feedExactReadinessLines(bytes: ByteArray, len: Int) {
        for (index in 0 until len) {
            val value = bytes[index].toInt() and 0xff
            if (value == '\r'.code || value == '\n'.code) {
                if (!readinessLineOverflow && readinessLine.toString() == readinessMarker) {
                    ready = true
                    onStage("Ready")
                    return
                }
                readinessLine.setLength(0)
                readinessLineOverflow = false
            } else if (!readinessLineOverflow) {
                if (readinessLine.length == readinessMarker.length) {
                    readinessLine.setLength(0)
                    readinessLineOverflow = true
                } else {
                    readinessLine.append(value.toChar())
                }
            }
        }
    }

    private companion object {
        /**
         * The exact substrings the `when` above scans for. Used ONLY to size
         * the cross-feed [overlap]; the `when` remains the authoritative
         * matcher. Keep this list in sync with the `when` search strings —
         * the longest one drives how much prior text is re-scanned.
         */
        val MARKERS = listOf(
            "Ready!" to "Ready",
            "Almost ready" to "Almost ready...",
            "Starting SSH" to "Starting SSH...",
            "Configuring containers" to "Configuring containers...",
            "Network found" to "Network found",
            "Loading kernel modules" to "Loading kernel modules...",
            "Mounting storage" to "Mounting storage...",
            "Booting kernel" to "Booting kernel...",
        )
    }
}
