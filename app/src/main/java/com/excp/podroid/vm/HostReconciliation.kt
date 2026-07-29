/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.vm

/** Pure trigger policy. Android delivery is deliberately outside this state machine. */
internal object HostReconciliationPolicy {
    fun decide(
        state: HostSupervisorState,
        trigger: ReconciliationTrigger,
        nowEpochMs: Long,
    ): ReconciliationOutcome = when {
        state.runtimeMayBeLive &&
            state.reconciliation.consecutiveAttempts >= ReconciliationMetadata.MAX_ATTEMPTS ->
            ReconciliationOutcome.EXHAUSTED
        state.runtimeMayBeLive && nowEpochMs < state.reconciliation.nextEligibleEpochMs ->
            ReconciliationOutcome.BACKOFF
        state.runtimeMayBeLive -> ReconciliationOutcome.ATTEMPTING
        !state.hostEnabled -> ReconciliationOutcome.SKIPPED_HOST_DISABLED
        state.desiredState != VmDesiredState.RUNNING -> ReconciliationOutcome.SKIPPED_DESIRED_STOPPED
        trigger == ReconciliationTrigger.BOOT_COMPLETED && !state.autostart ->
            ReconciliationOutcome.SKIPPED_AUTOSTART_DISABLED
        state.reconciliation.consecutiveAttempts >= ReconciliationMetadata.MAX_ATTEMPTS ->
            ReconciliationOutcome.EXHAUSTED
        nowEpochMs < state.reconciliation.nextEligibleEpochMs -> ReconciliationOutcome.BACKOFF
        else -> ReconciliationOutcome.ATTEMPTING
    }

    fun shouldStartServiceAtBoot(state: HostSupervisorState): Boolean =
        state.runtimeMayBeLive ||
            (state.hostEnabled && state.autostart && state.desiredState == VmDesiredState.RUNNING)

    fun backoffDelayMs(attempt: Int): Long {
        require(attempt in 1..ReconciliationMetadata.MAX_ATTEMPTS)
        val shift = (attempt - 1).coerceAtMost(20)
        return (BASE_BACKOFF_MS shl shift).coerceAtMost(MAX_BACKOFF_MS)
    }

    private const val BASE_BACKOFF_MS = 5_000L
    private const val MAX_BACKOFF_MS = 15 * 60_000L
}

data class ReconciliationAttemptToken(
    val trigger: ReconciliationTrigger,
    val attempt: Int,
    val expectedNextTransactionId: Long,
)

sealed interface ReconciliationAdmission {
    data class Execute(
        val token: ReconciliationAttemptToken,
        val interruptedTransactionId: Long?,
    ) : ReconciliationAdmission
    data class Skip(val outcome: ReconciliationOutcome) : ReconciliationAdmission
}

/** Atomic bounded metadata operations used by the one process-local reconciler. */
internal interface HostReconciliationStore {
    suspend fun begin(trigger: ReconciliationTrigger): ReconciliationAdmission =
        throw UnsupportedOperationException("reconciliation metadata unavailable")
    suspend fun finish(
        token: ReconciliationAttemptToken,
        outcome: ReconciliationOutcome,
        errorCode: LifecycleErrorCode? = null,
        runtimeMayBeLive: Boolean = false,
        authoritativeRuntimeAbsence: Boolean = false,
    ): HostSupervisorState =
        throw UnsupportedOperationException("reconciliation metadata unavailable")
}
