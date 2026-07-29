/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Synchronous service-owned launch policy. The owner is opaque so the state
 * machine is deterministic in tests; PodroidService stores the exact lazy Job.
 */
internal class ServiceLaunchCoordinator<T : Any> {
    data class Launch<T : Any>(val generation: Long, val owner: T)
    data class Stop<T : Any>(
        val generation: Long,
        val launchOwner: T?,
        val shouldExecute: Boolean,
    )
    data class StopCompletion<T : Any>(val launch: Launch<T>?)

    private enum class Mode { IDLE, LAUNCHING, STOPPING }

    private var nextGeneration = 0L
    private var mode = Mode.IDLE
    private var launch: Launch<T>? = null
    private var stopGeneration: Long? = null
    private var startQueuedDuringStop = false
    private val _ownershipActive = MutableStateFlow(false)

    /** True while a launch or its authoritative stop is service-owned. */
    val ownershipActive: StateFlow<Boolean> = _ownershipActive.asStateFlow()

    @Synchronized
    fun beginLaunch(owner: T): Launch<T>? {
        if (mode != Mode.IDLE) return null
        return Launch(++nextGeneration, owner).also {
            launch = it
            mode = Mode.LAUNCHING
            _ownershipActive.value = true
        }
    }

    /** Invalidates any launch generation and returns its exact owner once. */
    @Synchronized
    fun beginStop(): Stop<T> = beginStopLocked(queueStart = false)

    /** Atomically makes Stop authoritative while retaining one restart intent. */
    @Synchronized
    fun beginRestart(): Stop<T> = beginStopLocked(queueStart = true)

    private fun beginStopLocked(queueStart: Boolean): Stop<T> {
        if (mode == Mode.STOPPING) {
            // A newer explicit Stop cancels a retained Restart/Start intent;
            // another Restart retains it idempotently.
            startQueuedDuringStop = queueStart
            return Stop(checkNotNull(stopGeneration), null, shouldExecute = false)
        }
        val invalidatedOwner = launch?.owner
        launch = null
        val generation = ++nextGeneration
        stopGeneration = generation
        startQueuedDuringStop = queueStart
        mode = Mode.STOPPING
        // Keep ownership asserted until manager.stop and the bounded launch join
        // finish, so a replayed terminal state cannot tear down their scope.
        _ownershipActive.value = true
        return Stop(generation, invalidatedOwner, shouldExecute = true)
    }

    /** Retains one idempotent ACTION_START intent only while Stop owns the generation. */
    @Synchronized
    fun queueStartDuringStop(): Boolean {
        if (mode != Mode.STOPPING) return false
        startQueuedDuringStop = true
        return true
    }

    /** A stale completion cannot clear a stop or a newer launch generation. */
    @Synchronized
    fun completeLaunch(generation: Long): Boolean {
        val current = launch
        if (mode != Mode.LAUNCHING || current?.generation != generation) return false
        launch = null
        mode = Mode.IDLE
        _ownershipActive.value = false
        return true
    }

    /**
     * Completes the exact stop and atomically hands a retained start intent to a
     * fresh launch generation. [restartOwner] is ignored when no start is queued.
     * A stale stop completion cannot consume the queued intent.
     */
    @Synchronized
    fun completeStop(generation: Long, restartOwner: T): StopCompletion<T>? {
        if (mode != Mode.STOPPING || stopGeneration != generation) return null
        stopGeneration = null
        return if (startQueuedDuringStop) {
            startQueuedDuringStop = false
            val fresh = Launch(++nextGeneration, restartOwner)
            launch = fresh
            mode = Mode.LAUNCHING
            // Deliberately no false ownership edge between Stop and restart.
            _ownershipActive.value = true
            StopCompletion(fresh)
        } else {
            mode = Mode.IDLE
            _ownershipActive.value = false
            StopCompletion(null)
        }
    }
}
