/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.engine

/**
 * Small generation policy that makes stop intent authoritative before QEMU is
 * assigned. Preparation may run without a lock; every launch boundary rechecks
 * the generation token.
 */
internal class QemuLaunchGate {
    private var nextGeneration = 0L
    private var activeGeneration: Long? = null
    private var stopRequested = false

    @Synchronized
    fun begin(): Long {
        check(activeGeneration == null) { "A QEMU generation is already active" }
        val generation = ++nextGeneration
        activeGeneration = generation
        stopRequested = false
        return generation
    }

    @Synchronized
    fun requestStop(): Long? {
        val generation = activeGeneration ?: return null
        stopRequested = true
        return generation
    }

    @Synchronized
    fun mayLaunch(generation: Long): Boolean =
        activeGeneration == generation && !stopRequested

    /** Atomically recheck stop intent and publish the newly created process. */
    @Synchronized
    fun commitLaunch(generation: Long, publish: () -> Unit): Boolean {
        if (activeGeneration != generation || stopRequested) return false
        publish()
        return true
    }

    @Synchronized
    fun complete(generation: Long) {
        if (activeGeneration == generation) {
            activeGeneration = null
            stopRequested = false
        }
    }
}
