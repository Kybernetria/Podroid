/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.engine.avf

import com.excp.podroid.vm.MonotonicDeadline
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
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
 * Hard caller-side deadlines for reflective AVF smoke work.
 *
 * One daemon owns setup, create, run, stop, and the final fixed-name delete.
 * Vendor Binder/reflection may ignore interruption, so a timed-out caller never
 * joins that daemon. The admission lease remains held until the daemon and its
 * ordered final delete actually exit; a permanently stuck operation therefore
 * deliberately keeps later attempts closed.
 */
internal class AvfSmokeTestExecutor(
    private val operationTimeoutMs: Long = DEFAULT_OPERATION_TIMEOUT_MS,
    private val cleanupTimeoutMs: Long = DEFAULT_CLEANUP_TIMEOUT_MS,
    private val startupObservationDelayMs: Long = DEFAULT_STARTUP_OBSERVATION_DELAY_MS,
    private val attemptGate: AvfSmokeAttemptGate = sharedAttemptGate,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    data class Result(
        val busy: Boolean,
        val timedOut: Boolean,
        val timeoutStage: String?,
        val failure: Throwable?,
        val stopFailure: Throwable?,
        val deleteAttempted: Boolean,
        val deleteFailure: Throwable?,
        val cleanupPending: Boolean,
    )

    private data class OperationResult(
        val failure: Throwable?,
        val stopFailure: Throwable?,
        val deleteFailure: Throwable?,
    )

    private class OperationDeadlineReached(val stage: Stage) : IOException()

    private enum class Stage(val reportName: String) {
        SETUP("setup"),
        CREATE("create"),
        RUN("run"),
        WAIT("startup observation"),
        STOP("stop"),
        DELETE("named delete"),
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
        deadlineNanos: Long,
        setup: () -> S,
        create: (S) -> T,
        run: (T) -> Unit,
        stop: (T) -> Unit,
        deleteByFixedName: () -> Unit,
    ): Result {
        val executorDeadlineNanos = MonotonicDeadline.clamp(
            deadlineNanos,
            totalMaximumTimeoutMs(),
            nanoTime,
        ) ?: return expiredResult()
        val operationDeadlineNanos = MonotonicDeadline.clamp(
            executorDeadlineNanos,
            operationTimeoutMs,
            nanoTime,
        ) ?: return expiredResult()
        val lease = attemptGate.tryAcquire() ?: return Result(
            busy = true,
            timedOut = false,
            timeoutStage = null,
            failure = null,
            stopFailure = null,
            deleteAttempted = false,
            deleteFailure = null,
            cleanupPending = true,
        )
        val stage = AtomicReference(Stage.SETUP)
        val createAttempted = AtomicBoolean(false)
        val deleteAttempted = AtomicBoolean(false)
        val operationExited = CountDownLatch(1)
        val workerFinished = AtomicBoolean(false)
        val operationResult = AtomicReference<OperationResult?>()
        var timedOut = false
        var timeoutStage: String? = null
        var awaitFailure: Throwable? = null
        var cleanupWaitFailure: Throwable? = null
        var callerInterrupted: InterruptedException? = null
        var cleanupCompleted = false

        try {
            val operationFuture = startDaemonFuture("avf-smoke-operation", lease) {
                var vm: T? = null
                var failure: Throwable? = null
                var stopFailure: Throwable? = null
                var deleteFailure: Throwable? = null
                try {
                    requireOperationBudget(operationDeadlineNanos, stage.get())
                    val prepared = setup()
                    stage.set(Stage.CREATE)
                    requireOperationBudget(operationDeadlineNanos, Stage.CREATE)
                    createAttempted.set(true)
                    vm = create(prepared)
                    stage.set(Stage.RUN)
                    requireOperationBudget(operationDeadlineNanos, Stage.RUN)
                    run(vm)
                    stage.set(Stage.WAIT)
                    observeStartupWithin(operationDeadlineNanos)
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

                    // Publish operation/stop completion before final cleanup so
                    // the caller can apply its separate bounded cleanup wait.
                    operationResult.set(OperationResult(failure, stopFailure, null))
                    operationExited.countDown()

                    if (createAttempted.get()) {
                        stage.set(Stage.DELETE)
                        deleteAttempted.set(true)
                        try {
                            deleteByFixedName()
                        } catch (caught: Throwable) {
                            deleteFailure = caught
                        }
                    }
                    operationResult.set(OperationResult(failure, stopFailure, deleteFailure))
                    stage.set(Stage.COMPLETE)
                }
                OperationResult(failure, stopFailure, deleteFailure)
            }

            try {
                val operationWaitNanos = MonotonicDeadline.remainingNanos(
                    operationDeadlineNanos,
                    nanoTime,
                )
                if (operationWaitNanos == 0L ||
                    !operationExited.await(operationWaitNanos, TimeUnit.NANOSECONDS)
                ) {
                    timedOut = true
                    timeoutStage = stage.get().reportName
                    operationFuture.cancel(true)
                } else {
                    val completedBeforeCleanup = operationResult.get()
                    val deadlineFailure = completedBeforeCleanup?.failure as? OperationDeadlineReached
                    if (deadlineFailure != null) {
                        timedOut = true
                        timeoutStage = deadlineFailure.stage.reportName
                    }
                    val cleanupDeadlineNanos = MonotonicDeadline.clamp(
                        executorDeadlineNanos,
                        cleanupTimeoutMs,
                        nanoTime,
                    )
                    val cleanupWaitNanos = cleanupDeadlineNanos?.let {
                        MonotonicDeadline.remainingNanos(it, nanoTime)
                    } ?: 0L
                    if (cleanupWaitNanos == 0L) {
                        if (operationFuture.workerHasExited()) {
                            cleanupCompleted = true
                        } else {
                            operationFuture.cancel(true)
                            cleanupWaitFailure = cleanupDeadlineFailure()
                        }
                    } else {
                        try {
                            operationFuture.get(cleanupWaitNanos, TimeUnit.NANOSECONDS)
                            cleanupCompleted = true
                        } catch (_: TimeoutException) {
                            operationFuture.cancel(true)
                            cleanupWaitFailure = cleanupDeadlineFailure()
                        } catch (failure: ExecutionException) {
                            awaitFailure = failure.cause ?: failure
                        }
                    }
                }
            } catch (interrupted: InterruptedException) {
                timeoutStage = stage.get().reportName
                operationFuture.cancel(true)
                callerInterrupted = interrupted
            }

            // Future cancellation marks a Future done before an interrupt-ignoring
            // callable exits, so completion is tracked by the daemon wrapper.
            workerFinished.set(cleanupCompleted || operationFuture.workerHasExited())
        } finally {
            lease.finishRegistration()
        }

        callerInterrupted?.let { interrupted ->
            Thread.currentThread().interrupt()
            throw interrupted
        }

        val completed = operationResult.get()
        return Result(
            busy = false,
            timedOut = timedOut,
            timeoutStage = timeoutStage,
            failure = completed?.failure ?: awaitFailure,
            stopFailure = completed?.stopFailure,
            deleteAttempted = deleteAttempted.get(),
            deleteFailure = completed?.deleteFailure ?: cleanupWaitFailure,
            cleanupPending = !workerFinished.get(),
        )
    }

    private fun expiredResult() = Result(
        busy = false,
        timedOut = true,
        timeoutStage = "admission",
        failure = null,
        stopFailure = null,
        deleteAttempted = false,
        deleteFailure = null,
        cleanupPending = false,
    )

    private fun requireOperationBudget(deadlineNanos: Long, stage: Stage) {
        if (MonotonicDeadline.remainingNanos(deadlineNanos, nanoTime) == 0L) {
            throw OperationDeadlineReached(stage)
        }
    }

    private fun observeStartupWithin(deadlineNanos: Long) {
        if (startupObservationDelayMs == 0L) {
            requireOperationBudget(deadlineNanos, Stage.WAIT)
            return
        }
        val requestedNanos = TimeUnit.MILLISECONDS.toNanos(startupObservationDelayMs)
        val remainingNanos = MonotonicDeadline.remainingNanos(deadlineNanos, nanoTime)
        if (remainingNanos == 0L) throw OperationDeadlineReached(Stage.WAIT)
        val sleepNanos = minOf(requestedNanos, remainingNanos)
        TimeUnit.NANOSECONDS.sleep(sleepNanos)
        if (sleepNanos < requestedNanos) throw OperationDeadlineReached(Stage.WAIT)
        requireOperationBudget(deadlineNanos, Stage.WAIT)
    }

    private fun cleanupDeadlineFailure() = IOException(
        "AVF smoke final cleanup exceeded its remaining deadline; " +
            "cancellation was requested but cleanup remains pending",
    )

    private fun totalMaximumTimeoutMs(): Long =
        if (operationTimeoutMs > Long.MAX_VALUE - cleanupTimeoutMs) {
            Long.MAX_VALUE
        } else {
            operationTimeoutMs + cleanupTimeoutMs
        }

    private fun <T> startDaemonFuture(
        threadNamePrefix: String,
        lease: AvfSmokeAttemptGate.Lease,
        action: () -> T,
    ): TrackedFuture<T> {
        lease.registerTask()
        val workerExited = AtomicBoolean(false)
        val task = FutureTask(Callable { action() })
        val thread = Thread({
            try {
                task.run()
            } finally {
                workerExited.set(true)
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
        return TrackedFuture(task, workerExited)
    }

    private class TrackedFuture<T>(
        private val delegate: Future<T>,
        private val workerExited: AtomicBoolean,
    ) : Future<T> by delegate {
        fun workerHasExited(): Boolean = workerExited.get()
    }

    companion object {
        const val DEFAULT_OPERATION_TIMEOUT_MS = 10_000L
        const val DEFAULT_CLEANUP_TIMEOUT_MS = 2_000L
        const val DEFAULT_STARTUP_OBSERVATION_DELAY_MS = 1_500L

        private val sharedAttemptGate = AvfSmokeAttemptGate()
        private val threadSequence = AtomicInteger()
    }
}
