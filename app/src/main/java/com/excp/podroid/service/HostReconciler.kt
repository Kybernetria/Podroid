/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.service

import com.excp.podroid.vm.*
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex

internal object ReconciliationServiceTriggerPolicy {
    fun fromAction(action: String?): ReconciliationTrigger? = when (action) {
        PodroidService.ACTION_RECONCILE_BOOT -> ReconciliationTrigger.BOOT_COMPLETED
        PodroidService.ACTION_RECONCILE_APP -> ReconciliationTrigger.APP_COLD_START
        null -> ReconciliationTrigger.PROCESS_RESTART
        else -> null
    }
}

internal enum class ReconciliationServiceDisposition { NO_ACTION, SUPERVISE_RUNTIME }
internal data class HostReconciliationResult(
    val outcome: ReconciliationOutcome,
    val disposition: ReconciliationServiceDisposition,
)

/** Process-local serialization around the durable pure policy/state machine. */
@Singleton
class HostReconciler @Inject internal constructor(
    private val manager: VmManager,
    private val transport: HostTransportReconciler,
) {
    private val mutex = Mutex()

    internal suspend fun reconcile(trigger: ReconciliationTrigger): HostReconciliationResult {
        if (!mutex.tryLock()) {
            return HostReconciliationResult(
                ReconciliationOutcome.SUPERSEDED,
                if (manager.quiescent(VmId.DEFAULT).value) {
                    ReconciliationServiceDisposition.NO_ACTION
                } else ReconciliationServiceDisposition.SUPERVISE_RUNTIME,
            )
        }
        try {
            val admission = manager.beginReconciliation(VmId.DEFAULT, trigger)
            if (admission is ReconciliationAdmission.Skip) {
                return HostReconciliationResult(
                    admission.outcome,
                    currentDisposition(),
                )
            }
            val execution = admission as ReconciliationAdmission.Execute
            val token = execution.token
            return try {
                val lifecycle = manager.lifecycle(VmId.DEFAULT).value
                val alreadyOwned = lifecycle == VmLifecycleState.RUNNING ||
                    lifecycle == VmLifecycleState.STARTING ||
                    !manager.quiescent(VmId.DEFAULT).value
                if (!alreadyOwned) {
                    val command = manager.prepareLifecycleCommand(
                        VmId.DEFAULT,
                        LifecycleOperation.START,
                        token.expectedNextTransactionId,
                    )
                    if (!manager.executePrepared(VmId.DEFAULT, command)) {
                        manager.finishReconciliation(
                            VmId.DEFAULT, token, ReconciliationOutcome.SUPERSEDED,
                        )
                        return HostReconciliationResult(
                            ReconciliationOutcome.SUPERSEDED,
                            currentDisposition(),
                        )
                    }
                }
                transport.reconcile(VmId.DEFAULT)
                manager.finishReconciliation(
                    VmId.DEFAULT, token, ReconciliationOutcome.SUCCEEDED,
                )
                HostReconciliationResult(
                    ReconciliationOutcome.SUCCEEDED,
                    ReconciliationServiceDisposition.SUPERVISE_RUNTIME,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: StaleLifecycleCommandException) {
                manager.finishReconciliation(
                    VmId.DEFAULT, token, ReconciliationOutcome.SUPERSEDED,
                )
                HostReconciliationResult(
                    ReconciliationOutcome.SUPERSEDED,
                    currentDisposition(),
                )
            } catch (failure: Throwable) {
                val code = stableCode(failure)
                manager.finishReconciliation(
                    VmId.DEFAULT, token, ReconciliationOutcome.FAILED, code,
                )
                HostReconciliationResult(
                    ReconciliationOutcome.FAILED,
                    currentDisposition(),
                )
            }
        } finally {
            mutex.unlock()
        }
    }

    private fun currentDisposition(): ReconciliationServiceDisposition =
        if (!manager.quiescent(VmId.DEFAULT).value) {
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
