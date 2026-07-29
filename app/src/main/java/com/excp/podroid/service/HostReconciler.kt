/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.service

import com.excp.podroid.vm.*
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex

internal object ReconciliationServiceTriggerPolicy {
    fun fromAction(action: String?): ReconciliationTrigger? = when (action) {
        PodroidService.ACTION_RECONCILE_BOOT -> ReconciliationTrigger.BOOT_COMPLETED
        PodroidService.ACTION_RECONCILE_RETRY, null -> ReconciliationTrigger.PROCESS_RESTART
        else -> null
    }
}

internal enum class ReconciliationServiceDisposition { NO_ACTION, SUPERVISE_RUNTIME }
internal data class HostReconciliationResult(
    val outcome: ReconciliationOutcome,
    val disposition: ReconciliationServiceDisposition,
    val runtimeMayBeLive: Boolean,
    val runtimeEvidenceVersion: Long,
    val nextEligibleEpochMs: Long,
    val authoritativeRuntimeAbsence: Boolean = false,
    val completionVersion: Long,
)

internal data class RuntimeSupervisionSnapshot(val required: Boolean, val evidenceVersion: Long)

internal object ReconciliationCompletionVersionPolicy {
    fun accepts(appliedVersion: Long, completed: HostReconciliationResult): Boolean =
        completed.completionVersion >= appliedVersion
}

internal object RuntimeSupervisionVersionPolicy {
    fun apply(
        current: RuntimeSupervisionSnapshot,
        completed: HostReconciliationResult,
    ): RuntimeSupervisionSnapshot = when {
        completed.runtimeEvidenceVersion < current.evidenceVersion -> current
        completed.runtimeMayBeLive -> RuntimeSupervisionSnapshot(
            required = true,
            evidenceVersion = completed.runtimeEvidenceVersion,
        )
        completed.authoritativeRuntimeAbsence -> RuntimeSupervisionSnapshot(
            required = false,
            evidenceVersion = completed.runtimeEvidenceVersion,
        )
        else -> current
    }
}

/** Process-local serialization around the durable pure policy/state machine. */
@Singleton
class HostReconciler @Inject internal constructor(
    private val manager: VmManager,
    private val transport: HostTransportReconciler,
) {
    private val mutex = Mutex()
    private val completionVersion = AtomicLong()

    internal suspend fun reconcile(trigger: ReconciliationTrigger): HostReconciliationResult {
        if (!mutex.tryLock()) {
            return result(
                ReconciliationOutcome.SUPERSEDED,
                manager.supervisorState(VmId.DEFAULT),
            )
        }
        try {
            val admission = manager.beginReconciliation(VmId.DEFAULT, trigger)
            if (admission is ReconciliationAdmission.Skip) {
                return result(admission.outcome, manager.supervisorState(VmId.DEFAULT))
            }
            val execution = admission as ReconciliationAdmission.Execute
            val token = execution.token
            return try {
                val admittedState = manager.supervisorState(VmId.DEFAULT)
                val cleanupOnly = admittedState.runtimeMayBeLive && (
                    !admittedState.hostEnabled ||
                        admittedState.desiredState == VmDesiredState.STOPPED ||
                        (trigger == ReconciliationTrigger.BOOT_COMPLETED && !admittedState.autostart)
                    )
                if (cleanupOnly) {
                    manager.ensureFixedRuntimesStopped(VmId.DEFAULT)
                    val state = manager.finishReconciliation(
                        VmId.DEFAULT,
                        token,
                        ReconciliationOutcome.SUCCEEDED,
                        authoritativeRuntimeAbsence = true,
                    )
                    val completed = state.reconciliation.lastOutcome == ReconciliationOutcome.SUCCEEDED
                    return result(
                        if (completed) ReconciliationOutcome.SUCCEEDED else ReconciliationOutcome.SUPERSEDED,
                        state,
                        authoritativeRuntimeAbsence = completed,
                    )
                }
                val lifecycle = manager.lifecycle(VmId.DEFAULT).value
                val alreadyOwned = lifecycle == VmLifecycleState.RUNNING ||
                    lifecycle == VmLifecycleState.STARTING ||
                    !manager.quiescent(VmId.DEFAULT).value
                if (!alreadyOwned) {
                    val command = manager.prepareLifecycleCommand(
                        VmId.DEFAULT,
                        LifecycleOperation.RECOVER,
                        token.expectedNextTransactionId,
                    )
                    if (!manager.executePrepared(VmId.DEFAULT, command)) {
                        val state = manager.finishReconciliation(
                            VmId.DEFAULT, token, ReconciliationOutcome.SUPERSEDED,
                        )
                        return result(ReconciliationOutcome.SUPERSEDED, state)
                    }
                }
                transport.reconcile(VmId.DEFAULT)
                val state = manager.finishReconciliation(
                    VmId.DEFAULT,
                    token,
                    ReconciliationOutcome.SUCCEEDED,
                    authoritativeRuntimeAbsence = !alreadyOwned,
                )
                val completed = state.reconciliation.lastOutcome == ReconciliationOutcome.SUCCEEDED
                result(
                    if (completed) ReconciliationOutcome.SUCCEEDED else ReconciliationOutcome.SUPERSEDED,
                    state,
                    authoritativeRuntimeAbsence = completed && !alreadyOwned,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: StaleLifecycleCommandException) {
                val state = manager.finishReconciliation(
                    VmId.DEFAULT, token, ReconciliationOutcome.SUPERSEDED,
                )
                result(ReconciliationOutcome.SUPERSEDED, state)
            } catch (failure: Throwable) {
                val mayBeLive = (failure as? RuntimeProbeException)?.runtimeMayBeLive == true
                val state = manager.finishReconciliation(
                    VmId.DEFAULT,
                    token,
                    ReconciliationOutcome.FAILED,
                    stableCode(failure),
                    runtimeMayBeLive = mayBeLive,
                )
                result(state.reconciliation.lastOutcome, state)
            }
        } finally {
            mutex.unlock()
        }
    }

    private fun result(
        outcome: ReconciliationOutcome,
        state: HostSupervisorState,
        authoritativeRuntimeAbsence: Boolean = false,
    ) = HostReconciliationResult(
        outcome = outcome,
        disposition = currentDisposition(state.runtimeMayBeLive),
        runtimeMayBeLive = state.runtimeMayBeLive,
        runtimeEvidenceVersion = state.runtimeEvidenceVersion,
        nextEligibleEpochMs = state.reconciliation.nextEligibleEpochMs,
        authoritativeRuntimeAbsence = authoritativeRuntimeAbsence,
        completionVersion = completionVersion.incrementAndGet(),
    )

    private fun currentDisposition(runtimeMayBeLive: Boolean): ReconciliationServiceDisposition =
        if (runtimeMayBeLive || !manager.quiescent(VmId.DEFAULT).value) {
            ReconciliationServiceDisposition.SUPERVISE_RUNTIME
        } else ReconciliationServiceDisposition.NO_ACTION

    private fun stableCode(failure: Throwable): LifecycleErrorCode = when (failure) {
        is RuntimeProbeException -> failure.stableCode
        is TimeoutCancellationException -> LifecycleErrorCode.TIMEOUT
        is IOException -> LifecycleErrorCode.IO
        is SecurityException -> LifecycleErrorCode.SECURITY
        is IllegalArgumentException -> LifecycleErrorCode.INVALID_ARGUMENT
        is IllegalStateException -> LifecycleErrorCode.INVALID_STATE
        else -> LifecycleErrorCode.UNKNOWN
    }
}
