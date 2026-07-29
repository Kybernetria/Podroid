/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.engine

/**
 * Generation-tagged terminal-session slot. This class intentionally has no
 * internal lock: an engine must call every method under the same monitor that
 * protects its cleanup, making cleanup and post-construction registration one
 * atomic ordering decision.
 */
internal class GenerationBoundTerminalSlot<T : Any> {
    data class Registration<T : Any>(
        val selected: T?,
        val rejected: T?,
    )

    private var session: T? = null
    private var generation: Long? = null

    fun current(): T? = session

    /**
     * Registers only into an empty slot for the current active, non-quiescent
     * generation. A duplicate or stale candidate is returned to its caller for
     * immediate termination.
     */
    fun register(
        candidate: T,
        candidateGeneration: Long,
        currentGeneration: Long?,
        active: Boolean,
        nonQuiescent: Boolean,
    ): Registration<T> {
        if (candidateGeneration != currentGeneration || !active || !nonQuiescent) {
            return Registration(selected = null, rejected = candidate)
        }
        if (session != null) return Registration(selected = session, rejected = candidate)
        session = candidate
        generation = candidateGeneration
        return Registration(selected = candidate, rejected = null)
    }

    /** Clears a dead cached session only when it belongs to the requested generation. */
    fun clearDead(candidateGeneration: Long, isRunning: (T) -> Boolean): T? {
        val existing = session ?: return null
        if (generation != candidateGeneration || isRunning(existing)) return null
        session = null
        generation = null
        return existing
    }

    /** Cleanup detaches the slot before the next generation can register. */
    fun clear(): T? = session.also {
        session = null
        generation = null
    }
}
