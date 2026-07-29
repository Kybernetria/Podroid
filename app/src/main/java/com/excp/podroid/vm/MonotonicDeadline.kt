/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.vm

import java.util.concurrent.TimeUnit

/** Utilities for bounded, process-local absolute deadlines based on [System.nanoTime]. */
internal object MonotonicDeadline {
    fun afterMillis(timeoutMs: Long, nanoTime: () -> Long = System::nanoTime): Long {
        require(timeoutMs > 0) { "timeoutMs must be positive" }
        return nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    }

    /**
     * Rejects expired deadlines and caps untrusted/far-future deadlines to this layer's maximum.
     * Subtraction is intentional: it preserves normal `nanoTime` wrap-around semantics for bounded
     * durations while treating ambiguous values more than half the Long range away as expired.
     */
    fun clamp(
        callerDeadlineNanos: Long,
        maximumTimeoutMs: Long,
        nanoTime: () -> Long = System::nanoTime,
    ): Long? {
        require(maximumTimeoutMs > 0) { "maximumTimeoutMs must be positive" }
        val now = nanoTime()
        val callerRemainingNanos = callerDeadlineNanos - now
        if (callerRemainingNanos <= 0L) return null
        val maximumNanos = TimeUnit.MILLISECONDS.toNanos(maximumTimeoutMs)
        return now + minOf(callerRemainingNanos, maximumNanos)
    }

    fun remainingNanos(
        deadlineNanos: Long,
        nanoTime: () -> Long = System::nanoTime,
    ): Long = (deadlineNanos - nanoTime()).coerceAtLeast(0L)
}
