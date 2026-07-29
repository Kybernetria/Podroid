/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.service

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One suspend admission path for service lifecycle commands.
 *
 * Admission adopts the latest durable transaction id, reserves exactly its
 * successor, waits for that PENDING record to commit, and only then dispatches
 * an Intent or touches the in-memory execution coordinator. Delivered commands
 * use the same gate, so an older asynchronous delivery cannot start after a
 * newer generation has been reserved for persistence.
 */
internal class ServiceCommandOrder {
    data class Delivery(
        val generation: Long,
        val execute: Boolean,
        val newestGeneration: Long,
    )

    data class Admission<T : Any>(val generation: Long, val prepared: T)

    private val mutex = Mutex()
    private var newestGeneration = 0L
    private var lastDeliveredGeneration = 0L

    suspend fun <T : Any> admitAndDispatch(
        latestDurableGeneration: suspend () -> Long,
        prepare: suspend (generation: Long) -> T,
        dispatch: (Admission<T>) -> Unit,
    ): Admission<T> = mutex.withLock {
        val durableGeneration = latestDurableGeneration()
        adoptLocked(durableGeneration)
        check(newestGeneration == durableGeneration) {
            "In-memory command order is ahead of durable transaction order"
        }
        val generation = nextGenerationLocked()
        try {
            val admission = Admission(generation, prepare(generation))
            dispatch(admission)
            admission
        } catch (failure: Throwable) {
            // No concurrent admission can exist under this mutex. A failed
            // prepare did not commit this reserved generation, so roll it back.
            newestGeneration = durableGeneration
            throw failure
        }
    }

    suspend fun deliverAndExecute(
        reservedGeneration: Long,
        validatePrepared: suspend () -> Boolean,
        command: () -> Unit,
    ): Delivery = mutex.withLock {
        val delivery = deliverLocked(reservedGeneration, validatePrepared)
        if (delivery.execute) command()
        delivery
    }

    private suspend fun deliverLocked(
        reservedGeneration: Long,
        validatePrepared: suspend () -> Boolean,
    ): Delivery {
        if (reservedGeneration <= 0L ||
            reservedGeneration < newestGeneration ||
            reservedGeneration <= lastDeliveredGeneration ||
            !validatePrepared()
        ) {
            return Delivery(reservedGeneration, execute = false, newestGeneration)
        }

        // Process recreation can deliver an already-prepared explicit Intent,
        // but only after durable id + operation + PENDING validation.
        adoptLocked(reservedGeneration)
        lastDeliveredGeneration = reservedGeneration
        return Delivery(reservedGeneration, execute = true, newestGeneration)
    }

    private fun adoptLocked(generation: Long) {
        require(generation >= 0L) { "command generation must be non-negative" }
        if (generation > newestGeneration) newestGeneration = generation
    }

    private fun nextGenerationLocked(): Long {
        check(newestGeneration < Long.MAX_VALUE) { "Service command generation exhausted" }
        newestGeneration += 1L
        return newestGeneration
    }
}
