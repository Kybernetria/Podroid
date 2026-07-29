/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.engine

/**
 * Serializes boot-stage mutation with cleanup invalidation. Engines arm one
 * token per run, invalidate it before cancelling console workers, and perform
 * all lifecycle-flow mutations inside [apply].
 */
internal class BootStageGenerationGate {
    private var activeGeneration: Long? = null

    @Synchronized
    fun arm(generation: Long) {
        activeGeneration = generation
    }

    @Synchronized
    fun invalidate(generation: Long) {
        if (activeGeneration == generation) activeGeneration = null
    }

    /** Invalidates and applies a terminal mutation as one lifecycle boundary. */
    @Synchronized
    fun invalidateAndApply(generation: Long, mutation: () -> Unit): Boolean {
        if (activeGeneration != generation) return false
        activeGeneration = null
        mutation()
        return true
    }

    /** Applies any run-owned mutation atomically against [invalidate]. */
    @Synchronized
    fun applyCurrent(generation: Long, mutation: () -> Unit): Boolean {
        if (activeGeneration != generation) return false
        mutation()
        return true
    }

    @Synchronized
    fun apply(
        generation: Long,
        isStarting: () -> Boolean,
        isQuiescent: () -> Boolean,
        mutation: () -> Unit,
    ): Boolean {
        if (activeGeneration != generation || !isStarting() || isQuiescent()) return false
        mutation()
        return true
    }
}
