/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.engine.avf

import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Process-wide admission gate for AVF smoke attempts. */
internal class AvfSmokeAttemptGate {
    private val active = AtomicBoolean(false)

    fun tryAcquire(): Lease? =
        if (active.compareAndSet(false, true)) Lease(active) else null

    class Lease internal constructor(private val active: AtomicBoolean) {
        private val lock = Any()
        private var registeredTasks = 0
        private var registrationFinished = false

        fun registerTask() = synchronized(lock) {
            check(!registrationFinished) { "AVF smoke task registration already finished" }
            registeredTasks += 1
        }

        fun taskFinished() = synchronized(lock) {
            check(registeredTasks > 0) { "AVF smoke task accounting underflow" }
            registeredTasks -= 1
            releaseIfFinishedLocked()
        }

        fun finishRegistration() = synchronized(lock) {
            registrationFinished = true
            releaseIfFinishedLocked()
        }

        private fun releaseIfFinishedLocked() {
            if (registrationFinished && registeredTasks == 0) {
                check(active.compareAndSet(true, false)) { "AVF smoke gate released twice" }
            }
        }
    }
}

/**
 * Hard caller-side deadline for reflective AVF smoke work.
 *
 * Vendor Binder/reflection may ignore interruption. The complete setup through
 * stop sequence therefore runs in one daemon [Future]. The caller only waits via
 * Future.get(timeout), requests cancellation best-effort, and never joins a
 * timed-out worker. A separately bounded daemon Future always attempts the
 * fixed-name delete after a create attempt or operation timeout.
 */
internal class AvfSmokeTestExecutor(
    private val operationTimeoutMs: Long = DEFAULT_OPERATION_TIMEOUT_MS,
    private val cleanupTimeoutMs: Long = DEFAULT_CLEANUP_TIMEOUT_MS,
    private val startupObservationDelayMs: Long = DEFAULT_STARTUP_OBSERVATION_DELAY_MS,
    private val attemptGate: AvfSmokeAttemptGate = sharedAttemptGate,
) {
    data class Result(
        val busy: Boolean,
        val timedOut: Boolean,
        val timeoutStage: String?,
        val failure: Throwable?,
        val stopFailure: Throwable?,
        val deleteAttempted: Boolean,
        val deleteFailure: Throwable?,
    )

    private data class OperationResult(
        val failure: Throwable?,
        val stopFailure: Throwable?,
    )

    private enum class Stage(val reportName: String) {
        SETUP("setup"),
        CREATE("create"),
        RUN("run"),
        WAIT("startup observation"),
        STOP("stop"),
        COMPLETE("complete"),
    }

    init {
        require(operationTimeoutMs > 0) { "operationTimeoutMs must be positive" }
        require(cleanupTimeoutMs > 0) { "cleanupTimeoutMs must be positive" }
        require(startupObservationDelayMs >= 0) {
            "startupObservationDelayMs must not be negative"
        }
    }

    fun <S : Any, T : Any> execute(
        setup: () -> S,
        create: (S) -> T,
        run: (T) -> Unit,
        stop: (T) -> Unit,
        deleteByFixedName: () -> Unit,
    ): Result {
        val lease = attemptGate.tryAcquire() ?: return Result(
            busy = true,
            timedOut = false,
            timeoutStage = null,
            failure = null,
            stopFailure = null,
            deleteAttempted = false,
            deleteFailure = null,
        )
        val stage = AtomicReference(Stage.SETUP)
        val createAttempted = AtomicBoolean(false)
        var timedOut = false
        var timeoutStage: String? = null
        var operationResult: OperationResult? = null
        var awaitFailure: Throwable? = null
        var deleteAttempted = false
        var deleteFailure: Throwable? = null
        var callerInterrupted: InterruptedException? = null

        try {
            val operationFuture = startDaemonFuture("avf-smoke-operation", lease) {
                var vm: T? = null
                var failure: Throwable? = null
                var stopFailure: Throwable? = null
                try {
                    val prepared = setup()
                    stage.set(Stage.CREATE)
                    createAttempted.set(true)
                    vm = create(prepared)
                    stage.set(Stage.RUN)
                    run(vm)
                    stage.set(Stage.WAIT)
                    if (startupObservationDelayMs > 0) {
                        Thread.sleep(startupObservationDelayMs)
                    }
                } catch (caught: Throwable) {
                    failure = caught
                } finally {
                    vm?.let { createdVm ->
                        stage.set(Stage.STOP)
                        try {
                            stop(createdVm)
                        } catch (caught: Throwable) {
                            stopFailure = caught
                        }
                    }
                    stage.set(Stage.COMPLETE)
                }
                OperationResult(failure, stopFailure)
            }

            try {
                operationResult = operationFuture.get(operationTimeoutMs, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                timedOut = true
                timeoutStage = stage.get().reportName
                operationFuture.cancel(true)
            } catch (failure: ExecutionException) {
                awaitFailure = failure.cause ?: failure
            } catch (interrupted: InterruptedException) {
                // InterruptedException clears the flag. Keep it clear while the
                // independently bounded delete is scheduled, then restore and
                // propagate only after cleanup registration is complete.
                timeoutStage = stage.get().reportName
                operationFuture.cancel(true)
                callerInterrupted = interrupted
            }

            deleteAttempted = createAttempted.get() || timedOut || callerInterrupted != null
            if (deleteAttempted) {
                val deleteFuture = startDaemonFuture("avf-smoke-delete", lease) {
                    deleteByFixedName()
                }
                try {
                    deleteFuture.get(cleanupTimeoutMs, TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    deleteFuture.cancel(true)
                    deleteFailure = IOException(
                        "AVF smoke named delete exceeded ${cleanupTimeoutMs}ms; " +
                            "cancellation was requested but vendor work may still be running",
                    )
                } catch (failure: ExecutionException) {
                    deleteFailure = failure.cause ?: failure
                } catch (interrupted: InterruptedException) {
                    deleteFuture.cancel(true)
                    if (callerInterrupted == null) callerInterrupted = interrupted
                }
            }
        } finally {
            lease.finishRegistration()
        }

        callerInterrupted?.let { interrupted ->
            Thread.currentThread().interrupt()
            throw interrupted
        }

        return Result(
            busy = false,
            timedOut = timedOut,
            timeoutStage = timeoutStage,
            failure = operationResult?.failure ?: awaitFailure,
            stopFailure = operationResult?.stopFailure,
            deleteAttempted = deleteAttempted,
            deleteFailure = deleteFailure,
        )
    }

    private fun <T> startDaemonFuture(
        threadNamePrefix: String,
        lease: AvfSmokeAttemptGate.Lease,
        action: () -> T,
    ): Future<T> {
        lease.registerTask()
        val task = FutureTask(Callable { action() })
        val thread = Thread({
            try {
                task.run()
            } finally {
                lease.taskFinished()
            }
        }, "$threadNamePrefix-${threadSequence.incrementAndGet()}").apply {
            isDaemon = true
        }
        try {
            thread.start()
        } catch (failure: Throwable) {
            lease.taskFinished()
            throw failure
        }
        return task
    }

    companion object {
        const val DEFAULT_OPERATION_TIMEOUT_MS = 10_000L
        const val DEFAULT_CLEANUP_TIMEOUT_MS = 2_000L
        const val DEFAULT_STARTUP_OBSERVATION_DELAY_MS = 1_500L

        private val sharedAttemptGate = AvfSmokeAttemptGate()
        private val threadSequence = AtomicInteger()
    }
}
