/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.excp.podroid.vm.HostReconciliationPolicy
import com.excp.podroid.vm.HostSupervisorState
import com.excp.podroid.vm.ReconciliationMetadata
import com.excp.podroid.vm.ReconciliationOutcome
import com.excp.podroid.vm.VmDesiredState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

internal sealed interface ReconciliationRetryDirective {
    data object NoChange : ReconciliationRetryDirective
    data object Cancel : ReconciliationRetryDirective
    data class Schedule(val nextEligibleEpochMs: Long) : ReconciliationRetryDirective {
        init { require(nextEligibleEpochMs in 1..ReconciliationMetadata.MAX_EPOCH_MS) }
    }

    companion object {
        fun from(result: HostReconciliationResult): ReconciliationRetryDirective = when (result.outcome) {
            ReconciliationOutcome.FAILED,
            ReconciliationOutcome.BACKOFF,
            -> if (result.nextEligibleEpochMs > 0) {
                Schedule(result.nextEligibleEpochMs)
            } else Cancel
            ReconciliationOutcome.SUCCEEDED,
            ReconciliationOutcome.EXHAUSTED,
            ReconciliationOutcome.SKIPPED_HOST_DISABLED,
            ReconciliationOutcome.SKIPPED_DESIRED_STOPPED,
            -> Cancel
            ReconciliationOutcome.SUPERSEDED,
            ReconciliationOutcome.SKIPPED_AUTOSTART_DISABLED,
            ReconciliationOutcome.ATTEMPTING,
            ReconciliationOutcome.INTERRUPTED,
            ReconciliationOutcome.NEVER_RUN,
            -> NoChange
        }

        fun fromPersistedState(state: HostSupervisorState): ReconciliationRetryDirective {
            val reconciliation = state.reconciliation
            val retryRequired = retryRequired(state)
            return if (retryRequired &&
                reconciliation.consecutiveAttempts in 1 until ReconciliationMetadata.MAX_ATTEMPTS &&
                reconciliation.nextEligibleEpochMs > 0 && reconciliation.lastOutcome in setOf(
                    ReconciliationOutcome.FAILED,
                    ReconciliationOutcome.BACKOFF,
                )
            ) {
                Schedule(reconciliation.nextEligibleEpochMs)
            } else Cancel
        }

        /** Rearms a consumed delivery when completion could not leave schedulable metadata. */
        fun afterReconciliationFailure(
            state: HostSupervisorState,
            nowEpochMs: Long,
        ): ReconciliationRetryDirective {
            require(nowEpochMs in 0..ReconciliationMetadata.MAX_EPOCH_MS)
            val persisted = fromPersistedState(state)
            if (persisted is Schedule || !retryRequired(state)) return persisted
            val reconciliation = state.reconciliation
            return if (reconciliation.lastOutcome == ReconciliationOutcome.ATTEMPTING &&
                reconciliation.consecutiveAttempts in 1 until ReconciliationMetadata.MAX_ATTEMPTS
            ) {
                val delayMs = HostReconciliationPolicy.backoffDelayMs(
                    reconciliation.consecutiveAttempts,
                )
                Schedule(
                    if (nowEpochMs > ReconciliationMetadata.MAX_EPOCH_MS - delayMs) {
                        ReconciliationMetadata.MAX_EPOCH_MS
                    } else {
                        nowEpochMs + delayMs
                    },
                )
            } else persisted
        }

        private fun retryRequired(state: HostSupervisorState): Boolean =
            state.runtimeMayBeLive ||
                (state.hostEnabled && state.desiredState == VmDesiredState.RUNNING)
    }
}

/** One explicit inexact allow-while-idle alarm; no exact-alarm permission is used. */
@Singleton
class ReconciliationRetryScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val alarmManager: AlarmManager
        get() = context.getSystemService(AlarmManager::class.java)

    internal fun apply(directive: ReconciliationRetryDirective) {
        when (directive) {
            ReconciliationRetryDirective.NoChange -> Unit
            ReconciliationRetryDirective.Cancel -> alarmManager.cancel(pendingIntent())
            is ReconciliationRetryDirective.Schedule -> alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                directive.nextEligibleEpochMs,
                pendingIntent(),
            )
        }
    }

    fun cancel() = apply(ReconciliationRetryDirective.Cancel)

    private fun pendingIntent(): PendingIntent = PendingIntent.getForegroundService(
        context,
        REQUEST_CODE,
        Intent(context, PodroidService::class.java).setAction(PodroidService.ACTION_RECONCILE_RETRY),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object { const val REQUEST_CODE = 11 }
}
