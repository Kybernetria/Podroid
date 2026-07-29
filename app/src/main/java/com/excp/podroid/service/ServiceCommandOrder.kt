/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.service

/**
 * Process-lifetime order for every service lifecycle command.
 *
 * Binder start/restart calls reserve before asking Android to deliver an Intent,
 * while Binder stop/force atomically reserve before initiating direct execution.
 * Legacy and notification Intents have no reservation and become
 * newest when Android delivers them. A delayed or duplicate reserved command
 * therefore cannot supersede a command that was ordered after it.
 */
internal class ServiceCommandOrder {
    data class Delivery(
        val generation: Long,
        val execute: Boolean,
        val newestGeneration: Long,
    )

    private var newestGeneration = 0L
    private var lastDeliveredGeneration = 0L

    /** Reserve an order position before enqueueing a start/restart Intent. */
    @Synchronized
    fun reserve(): Long = nextGenerationLocked()

    /**
     * Reserve and initiate a direct Binder command under the same short lock.
     * This preserves stop-then-start ordering even when a later Binder call can
     * reserve immediately after the stop call returns.
     */
    @Synchronized
    fun executeDirect(command: (generation: Long) -> Unit): Long {
        val generation = nextGenerationLocked()
        lastDeliveredGeneration = generation
        command(generation)
        return generation
    }

    /**
     * Claim and initiate a delivered command under the same short lock. A null
     * generation is a legacy/notification command and atomically receives the
     * newest position at delivery.
     */
    @Synchronized
    fun deliverAndExecute(reservedGeneration: Long?, command: () -> Unit): Delivery {
        val delivery = deliverLocked(reservedGeneration)
        if (delivery.execute) command()
        return delivery
    }

    @Synchronized
    fun deliver(reservedGeneration: Long?): Delivery = deliverLocked(reservedGeneration)

    private fun deliverLocked(reservedGeneration: Long?): Delivery {
        if (reservedGeneration == null) {
            val generation = nextGenerationLocked()
            lastDeliveredGeneration = generation
            return Delivery(generation, execute = true, newestGeneration)
        }
        if (reservedGeneration <= 0L ||
            reservedGeneration < newestGeneration ||
            reservedGeneration <= lastDeliveredGeneration
        ) {
            return Delivery(reservedGeneration, execute = false, newestGeneration)
        }

        // A process recreated to deliver an already-enqueued explicit Intent has
        // no in-memory reservation. Adopt that positive generation safely.
        if (reservedGeneration > newestGeneration) newestGeneration = reservedGeneration
        lastDeliveredGeneration = reservedGeneration
        return Delivery(reservedGeneration, execute = true, newestGeneration)
    }

    private fun nextGenerationLocked(): Long {
        check(newestGeneration < Long.MAX_VALUE) { "Service command generation exhausted" }
        newestGeneration += 1L
        return newestGeneration
    }
}
