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

    private enum class Mode { IDLE, LAUNCHING, STOPPING }

    private var nextGeneration = 0L
    private var mode = Mode.IDLE
    private var launch: Launch<T>? = null
    private var stopGeneration: Long? = null
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
    fun beginStop(): Stop<T> {
        if (mode == Mode.STOPPING) {
            return Stop(checkNotNull(stopGeneration), null, shouldExecute = false)
        }
        val invalidatedOwner = launch?.owner
        launch = null
        val generation = ++nextGeneration
        stopGeneration = generation
        mode = Mode.STOPPING
        // Keep ownership asserted until manager.stop and the bounded launch join
        // finish, so a replayed terminal state cannot tear down their scope.
        _ownershipActive.value = true
        return Stop(generation, invalidatedOwner, shouldExecute = true)
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

    @Synchronized
    fun completeStop(generation: Long): Boolean {
        if (mode != Mode.STOPPING || stopGeneration != generation) return false
        stopGeneration = null
        mode = Mode.IDLE
        _ownershipActive.value = false
        return true
    }
}
