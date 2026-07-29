/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Opaque proof that one exact backend lifecycle generation owns the route. */
internal interface EngineClaim<out T : Any> {
    val engine: T
}

internal sealed interface SelectionPublication {
    data object Published : SelectionPublication
    data object Retry : SelectionPublication
    class ClaimActive internal constructor(internal val generation: Long) : SelectionPublication
}

/**
 * Atomically coordinates backend publication with a VM-lifetime engine claim.
 *
 * The mutex is held only for selection/claim bookkeeping. A claim remains until
 * backend cleanup completes, but the mutex is never held for that lifetime.
 */
internal class EngineClaimRouter<T : Any>(initial: T) {
    private class ClaimToken<T : Any>(
        override val engine: T,
        val generation: Long,
    ) : EngineClaim<T>

    private val gate = Mutex()
    @Volatile private var selected: T = initial
    private var claimed: ClaimToken<T>? = null
    private var nextGeneration = 0L
    private val _claimReleasedGeneration = MutableStateFlow(0L)
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
    ): SelectionPublication = gate.withLock {
        claimed?.let { return@withLock SelectionPublication.ClaimActive(it.generation) }
        if (selected !== expected || !canPublish(expected)) return@withLock SelectionPublication.Retry
        selected = next
        _routed.value = next
        SelectionPublication.Published
    }

    /** Publish cold-start selection without replacing a claimed generation. */
    suspend fun publishInitial(next: T): Boolean = gate.withLock {
        if (claimed != null) return@withLock false
        selected = next
        _routed.value = next
        true
    }

    /** Atomically bind a monotonically unique lifecycle generation to the selected backend. */
    suspend fun claimSelected(canClaim: (T) -> Boolean): EngineClaim<T> = gate.withLock {
        check(claimed == null) { "A VM lifecycle generation is already claimed" }
        check(nextGeneration < Long.MAX_VALUE) { "Engine claim generation exhausted" }
        val engine = selected
        check(canClaim(engine)) { "Selected VM backend cleanup is incomplete" }
        ClaimToken(engine, ++nextGeneration).also {
            claimed = it
            _routed.value = engine
        }
    }

    /** Release only the exact opaque token returned for this generation. */
    suspend fun releaseClaim(claim: EngineClaim<T>): Boolean = gate.withLock {
        val active = claimed
        if (active !== claim) return@withLock false
        claimed = null
        _routed.value = selected
        _claimReleasedGeneration.value = active.generation
        true
    }

    /** Wait for the explicit release of the claim that blocked a selection publication. */
    suspend fun awaitClaimReleased(blocked: SelectionPublication.ClaimActive) {
        _claimReleasedGeneration.first { it >= blocked.generation }
    }
}
