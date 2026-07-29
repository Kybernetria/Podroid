/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Atomically coordinates backend publication with a VM-lifetime engine claim.
 *
 * The mutex is held only for selection/claim bookkeeping. A claim remains until
 * backend cleanup completes, but the mutex is never held for that lifetime.
 */
internal class EngineClaimRouter<T : Any>(initial: T) {
    private val gate = Mutex()
    @Volatile private var selected: T = initial
    private var claimed: T? = null
    private val _routed = MutableStateFlow(initial)

    val routed: StateFlow<T> = _routed.asStateFlow()
    val selectedSnapshot: T get() = selected

    /**
     * Publish [next] only if [expected] is still selected, no generation is
     * claimed, and the caller's quiescence predicate still holds under the gate.
     */
    suspend fun publishSelection(
        expected: T,
        next: T,
        canPublish: (T) -> Boolean,
    ): Boolean = gate.withLock {
        if (claimed != null || selected !== expected || !canPublish(expected)) return@withLock false
        selected = next
        _routed.value = next
        true
    }

    /** Publish cold-start selection without replacing a claimed generation. */
    suspend fun publishInitial(next: T): Boolean = gate.withLock {
        if (claimed != null) return@withLock false
        selected = next
        _routed.value = next
        true
    }

    /** Atomically bind a new lifecycle generation to the selected backend. */
    suspend fun claimSelected(canClaim: (T) -> Boolean): T = gate.withLock {
        check(claimed == null) { "A VM lifecycle generation is already claimed" }
        val engine = selected
        check(canClaim(engine)) { "Selected VM backend cleanup is incomplete" }
        claimed = engine
        _routed.value = engine
        engine
    }

    /** Release only the matching generation; a stale cleanup cannot release a newer claim. */
    suspend fun releaseClaim(engine: T): Boolean = gate.withLock {
        if (claimed !== engine) return@withLock false
        claimed = null
        _routed.value = selected
        true
    }
}
