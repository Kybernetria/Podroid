/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.engine.avf

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Bounded, injectable execution policy for the reflective AVF smoke test. */
internal class AvfSmokeTestExecutor(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val operationTimeoutMs: Long = DEFAULT_OPERATION_TIMEOUT_MS,
    private val cleanupTimeoutMs: Long = DEFAULT_CLEANUP_TIMEOUT_MS,
    private val startupObservationDelayMs: Long = DEFAULT_STARTUP_OBSERVATION_DELAY_MS,
) {
    data class Result(
        val timedOut: Boolean,
        val failure: Throwable?,
        val stopFailure: Throwable?,
        val deleteFailure: Throwable?,
    )

    /**
     * Creation/run execute interruptibly on [ioDispatcher]. Once [create]
     * returns, stop and delete are attempted in finally on success, failure,
     * deadline expiry, or caller cancellation. Each cleanup action has its own
     * deadline so a stuck stop cannot suppress delete.
     */
    suspend fun <T : Any> execute(
        create: () -> T,
        run: (T) -> Unit,
        stop: (T) -> Unit,
        delete: () -> Unit,
    ): Result {
        var vm: T? = null
        var failure: Throwable? = null
        var stopFailure: Throwable? = null
        var deleteFailure: Throwable? = null
        var timedOut = false

        try {
            val completed = withTimeoutOrNull(operationTimeoutMs) {
                try {
                    vm = runInterruptible(ioDispatcher) { create() }
                    runInterruptible(ioDispatcher) { run(checkNotNull(vm)) }
                    delay(startupObservationDelayMs)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (caught: Throwable) {
                    failure = caught
                }
                true
            }
            timedOut = completed == null
        } finally {
            vm?.let { createdVm ->
                stopFailure = runCleanup("stop") { stop(createdVm) }
                deleteFailure = runCleanup("delete") { delete() }
            }
        }

        return Result(timedOut, failure, stopFailure, deleteFailure)
    }

    private suspend fun runCleanup(name: String, action: () -> Unit): Throwable? =
        withContext(NonCancellable) {
            var failure: Throwable? = null
            val completed = withTimeoutOrNull(cleanupTimeoutMs) {
                try {
                    runInterruptible(ioDispatcher, action)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (caught: Throwable) {
                    failure = caught
                }
                true
            }
            if (completed == null) IOException("AVF smoke $name timed out") else failure
        }

    companion object {
        const val DEFAULT_OPERATION_TIMEOUT_MS = 10_000L
        const val DEFAULT_CLEANUP_TIMEOUT_MS = 2_000L
        const val DEFAULT_STARTUP_OBSERVATION_DELAY_MS = 1_500L
    }
}
