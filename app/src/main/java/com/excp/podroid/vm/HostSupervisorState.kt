/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.vm

/** Persisted Host-supervisor intent. This ticket records intent but does not reconcile it. */
enum class VmDesiredState { RUNNING, STOPPED }
enum class HostWakePolicy { WHILE_VM_ACTIVE, NEVER }
enum class HostPowerPolicy { GRACEFUL_THEN_FORCE, FORCE }
enum class HostThermalPolicy { STOP_AT_CRITICAL, IGNORE }
enum class LifecycleOperation { SETUP, START, STOP, FORCE_STOP, RESTART, REMOVE }
enum class LifecycleOutcome { PENDING, SUCCEEDED, FAILED }

/** Stable, redacted failure classification. No exception text is persisted. */
enum class LifecycleErrorCode {
    CANCELLED,
    TIMEOUT,
    IO,
    INVALID_STATE,
    INVALID_ARGUMENT,
    SECURITY,
    UNKNOWN,
}

data class LifecycleTransaction(
    val id: Long,
    val operation: LifecycleOperation,
    val outcome: LifecycleOutcome,
    val requestedAtEpochMs: Long,
    val completedAtEpochMs: Long?,
    val errorCode: LifecycleErrorCode?,
) {
    init {
        require(id > 0) { "transaction id must be positive" }
        require(requestedAtEpochMs >= 0) { "requested timestamp must be non-negative" }
        when (outcome) {
            LifecycleOutcome.PENDING -> {
                require(completedAtEpochMs == null && errorCode == null) {
                    "pending transaction cannot contain a result"
                }
            }
            LifecycleOutcome.SUCCEEDED -> {
                require(completedAtEpochMs != null && completedAtEpochMs >= requestedAtEpochMs)
                require(errorCode == null) { "successful transaction cannot contain an error" }
            }
            LifecycleOutcome.FAILED -> {
                require(completedAtEpochMs != null && completedAtEpochMs >= requestedAtEpochMs)
                require(errorCode != null) { "failed transaction requires a stable error code" }
            }
        }
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
    val latestTransaction: LifecycleTransaction?,
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) { "unsupported Host supervisor schema" }
        require(runtimeGeneration >= 0) { "runtime generation must be non-negative" }
    }

    companion object {
        const val SCHEMA_VERSION = 1

        /** Fail-safe v1 initialization used only when the v0 record is absent. */
        fun safeDefaults() = HostSupervisorState(
            schemaVersion = SCHEMA_VERSION,
            hostEnabled = false,
            desiredState = VmDesiredState.STOPPED,
            autostart = false,
            wakePolicy = HostWakePolicy.WHILE_VM_ACTIVE,
            powerPolicy = HostPowerPolicy.GRACEFUL_THEN_FORCE,
            thermalPolicy = HostThermalPolicy.STOP_AT_CRITICAL,
            runtimeGeneration = 0,
            latestTransaction = null,
        )
    }
}

data class LifecycleTransactionToken internal constructor(
    val id: Long,
    val operation: LifecycleOperation,
    val baseRuntimeGeneration: Long,
)

/** Atomic persistence port owned by the lifecycle manager. */
internal interface HostSupervisorTransactions {
    suspend fun snapshot(): HostSupervisorState
    suspend fun begin(operation: LifecycleOperation): LifecycleTransactionToken
    suspend fun succeed(token: LifecycleTransactionToken, runtimeStarted: Boolean = false)
    suspend fun fail(token: LifecycleTransactionToken, errorCode: LifecycleErrorCode)
}
