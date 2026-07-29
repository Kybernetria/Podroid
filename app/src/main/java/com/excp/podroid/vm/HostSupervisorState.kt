/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.vm

/** Persisted Host-supervisor intent and bounded reconciliation evidence. */
enum class VmDesiredState { RUNNING, STOPPED }
enum class HostWakePolicy { WHILE_VM_ACTIVE, NEVER }
enum class HostPowerPolicy { GRACEFUL_THEN_FORCE, FORCE }
enum class HostThermalPolicy { STOP_AT_CRITICAL, IGNORE }
enum class LifecycleOperation { SETUP, START, RECOVER, STOP, FORCE_STOP, RESTART, REMOVE }
enum class LifecycleOutcome { PENDING, SUCCEEDED, FAILED }

enum class ReconciliationTrigger { BOOT_COMPLETED, PROCESS_RESTART, APP_COLD_START }
enum class ReconciliationOutcome {
    NEVER_RUN,
    ATTEMPTING,
    SUCCEEDED,
    INTERRUPTED,
    SKIPPED_HOST_DISABLED,
    SKIPPED_AUTOSTART_DISABLED,
    SKIPPED_DESIRED_STOPPED,
    BACKOFF,
    EXHAUSTED,
    SUPERSEDED,
    FAILED,
}

/** Stable, redacted failure classification. No exception text is persisted. */
enum class LifecycleErrorCode {
    CANCELLED,
    TIMEOUT,
    IO,
    INVALID_STATE,
    INVALID_ARGUMENT,
    SECURITY,
    PROCESS_DIED,
    PROBE_TIMEOUT,
    RUNTIME_OWNERSHIP,
    UNKNOWN,
}

data class LifecycleTransaction(
    val id: Long,
    val operation: LifecycleOperation,
    val outcome: LifecycleOutcome,
    val requestedAtEpochMs: Long,
    val completedAtEpochMs: Long?,
    val errorCode: LifecycleErrorCode?,
    val effectStarted: Boolean = false,
) {
    init {
        require(id > 0) { "transaction id must be positive" }
        require(requestedAtEpochMs >= 0) { "requested timestamp must be non-negative" }
        when (outcome) {
            LifecycleOutcome.PENDING -> require(completedAtEpochMs == null && errorCode == null) {
                "pending transaction cannot contain a result"
            }
            LifecycleOutcome.SUCCEEDED -> {
                require(effectStarted) { "successful transaction requires an effect claim" }
                require(completedAtEpochMs != null && completedAtEpochMs >= requestedAtEpochMs)
                require(errorCode == null) { "successful transaction cannot contain an error" }
            }
            LifecycleOutcome.FAILED -> {
                // PROCESS_DIED may resolve evidence for a command that died before
                // its effect claim. Other failures still require a claimed effect.
                require(effectStarted || errorCode == LifecycleErrorCode.PROCESS_DIED) {
                    "failed transaction requires an effect claim or process-death evidence"
                }
                require(completedAtEpochMs != null && completedAtEpochMs >= requestedAtEpochMs)
                require(errorCode != null) { "failed transaction requires a stable error code" }
            }
        }
    }
}

data class ReconciliationMetadata(
    val consecutiveAttempts: Int,
    val nextEligibleEpochMs: Long,
    val lastTrigger: ReconciliationTrigger?,
    val lastOutcome: ReconciliationOutcome,
    val lastErrorCode: LifecycleErrorCode?,
) {
    init {
        require(consecutiveAttempts in 0..MAX_ATTEMPTS) { "reconciliation attempt count is out of bounds" }
        require(nextEligibleEpochMs in 0..MAX_EPOCH_MS) {
            "next reconciliation timestamp is outside the supported wall-clock range"
        }
        when (lastOutcome) {
            ReconciliationOutcome.NEVER_RUN -> require(
                consecutiveAttempts == 0 && nextEligibleEpochMs == 0L &&
                    lastTrigger == null && lastErrorCode == null,
            ) { "never-run reconciliation metadata must be empty" }
            ReconciliationOutcome.ATTEMPTING -> require(
                consecutiveAttempts in 1..MAX_ATTEMPTS && nextEligibleEpochMs == 0L &&
                    lastTrigger != null && lastErrorCode == null,
            ) { "attempting reconciliation metadata is inconsistent" }
            ReconciliationOutcome.FAILED -> require(
                consecutiveAttempts in 1 until MAX_ATTEMPTS && nextEligibleEpochMs > 0L &&
                    lastTrigger != null && lastErrorCode != null,
            ) { "failed reconciliation metadata is inconsistent" }
            ReconciliationOutcome.BACKOFF -> require(
                consecutiveAttempts in 1 until MAX_ATTEMPTS && nextEligibleEpochMs > 0L &&
                    lastTrigger != null && lastErrorCode != null,
            ) { "backoff reconciliation metadata is inconsistent" }
            ReconciliationOutcome.EXHAUSTED -> require(
                consecutiveAttempts == MAX_ATTEMPTS && nextEligibleEpochMs == 0L &&
                    lastTrigger != null && lastErrorCode != null,
            ) { "exhausted reconciliation metadata is inconsistent" }
            ReconciliationOutcome.SUCCEEDED -> require(
                consecutiveAttempts == 0 && nextEligibleEpochMs == 0L &&
                    lastTrigger != null && lastErrorCode == null,
            ) { "successful reconciliation metadata is inconsistent" }
            ReconciliationOutcome.SUPERSEDED -> require(
                consecutiveAttempts in 1..MAX_ATTEMPTS && nextEligibleEpochMs == 0L &&
                    lastTrigger != null && lastErrorCode == null,
            ) { "superseded reconciliation metadata is inconsistent" }
            ReconciliationOutcome.INTERRUPTED -> require(
                lastTrigger != null && lastErrorCode == LifecycleErrorCode.PROCESS_DIED,
            ) { "interrupted reconciliation metadata requires process-death evidence" }
            ReconciliationOutcome.SKIPPED_HOST_DISABLED,
            ReconciliationOutcome.SKIPPED_AUTOSTART_DISABLED,
            ReconciliationOutcome.SKIPPED_DESIRED_STOPPED,
            -> require(lastTrigger != null && lastErrorCode == null) {
                "skipped reconciliation metadata is inconsistent"
            }
        }
    }

    companion object {
        const val MAX_ATTEMPTS = 5
        const val MAX_EPOCH_MS = 253_402_300_799_999L // 9999-12-31T23:59:59.999Z
        fun safeDefaults() = ReconciliationMetadata(
            consecutiveAttempts = 0,
            nextEligibleEpochMs = 0,
            lastTrigger = null,
            lastOutcome = ReconciliationOutcome.NEVER_RUN,
            lastErrorCode = null,
        )
    }
}

/** Bounded read-only DTO exposed through VmManager and the same-UID local Binder. */
data class HostSupervisorState(
    val schemaVersion: Int,
    val hostEnabled: Boolean,
    val desiredState: VmDesiredState,
    val autostart: Boolean,
    val wakePolicy: HostWakePolicy,
    val powerPolicy: HostPowerPolicy,
    val thermalPolicy: HostThermalPolicy,
    val runtimeGeneration: Long,
    val runtimeMayBeLive: Boolean,
    val runtimeEvidenceVersion: Long,
    val latestTransaction: LifecycleTransaction?,
    val reconciliation: ReconciliationMetadata,
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) { "unsupported Host supervisor schema" }
        require(runtimeGeneration >= 0) { "runtime generation must be non-negative" }
        require(runtimeEvidenceVersion >= 0) { "runtime evidence version must be non-negative" }
        require(!runtimeMayBeLive || runtimeEvidenceVersion > 0) {
            "possible-live evidence requires a positive version"
        }
        if (latestTransaction?.outcome == LifecycleOutcome.SUCCEEDED &&
            latestTransaction.operation in setOf(LifecycleOperation.STOP, LifecycleOperation.FORCE_STOP)
        ) {
            require(!runtimeMayBeLive) { "successful explicit stop requires definitive runtime absence" }
        }
    }

    companion object {
        const val SCHEMA_VERSION = 3

        /** Fail-safe initialization used only when the v0 record is absent. */
        fun safeDefaults() = HostSupervisorState(
            schemaVersion = SCHEMA_VERSION,
            hostEnabled = false,
            desiredState = VmDesiredState.STOPPED,
            autostart = false,
            wakePolicy = HostWakePolicy.WHILE_VM_ACTIVE,
            powerPolicy = HostPowerPolicy.GRACEFUL_THEN_FORCE,
            thermalPolicy = HostThermalPolicy.STOP_AT_CRITICAL,
            runtimeGeneration = 0,
            runtimeMayBeLive = false,
            runtimeEvidenceVersion = 0,
            latestTransaction = null,
            reconciliation = ReconciliationMetadata.safeDefaults(),
        )
    }
}

/**
 * Bounded durable command capability. The id is also the service-command
 * generation, so command admission and persisted transaction order cannot drift.
 */
data class LifecycleTransactionToken internal constructor(
    val id: Long,
    val operation: LifecycleOperation,
    val baseRuntimeGeneration: Long,
) {
    init {
        require(id > 0) { "transaction id must be positive" }
        require(baseRuntimeGeneration >= 0) { "base runtime generation must be non-negative" }
    }

    companion object {
        fun restore(id: Long, operation: LifecycleOperation, baseRuntimeGeneration: Long) =
            LifecycleTransactionToken(id, operation, baseRuntimeGeneration)
    }
}

class StaleLifecycleCommandException(message: String) : IllegalStateException(message)

/** Atomic persistence port owned by the lifecycle manager. */
internal interface HostSupervisorTransactions : HostReconciliationStore {
    suspend fun snapshot(): HostSupervisorState
    suspend fun prepare(operation: LifecycleOperation, expectedId: Long? = null): LifecycleTransactionToken
    suspend fun claim(token: LifecycleTransactionToken): Boolean
    suspend fun isCurrent(token: LifecycleTransactionToken): Boolean
    suspend fun succeed(token: LifecycleTransactionToken, runtimeStarted: Boolean = false): Boolean
    suspend fun fail(token: LifecycleTransactionToken, errorCode: LifecycleErrorCode): Boolean
    suspend fun setAutostart(enabled: Boolean): HostSupervisorState =
        throw UnsupportedOperationException("autostart mutation unavailable")
}
