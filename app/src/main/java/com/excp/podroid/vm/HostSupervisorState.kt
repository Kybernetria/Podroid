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
    val effectStarted: Boolean = false,
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
                require(effectStarted) { "successful transaction requires an effect claim" }
                require(completedAtEpochMs != null && completedAtEpochMs >= requestedAtEpochMs)
                require(errorCode == null) { "successful transaction cannot contain an error" }
            }
            LifecycleOutcome.FAILED -> {
                require(effectStarted) { "failed transaction requires an effect claim" }
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
        /** Reconstructs a token carried through bounded primitive Intent extras. */
        fun restore(
            id: Long,
            operation: LifecycleOperation,
            baseRuntimeGeneration: Long,
        ) = LifecycleTransactionToken(id, operation, baseRuntimeGeneration)
    }
}

class StaleLifecycleCommandException(message: String) : IllegalStateException(message)

/** Atomic persistence port owned by the lifecycle manager. */
internal interface HostSupervisorTransactions {
    suspend fun snapshot(): HostSupervisorState

    /**
     * Persists desired state and exactly one PENDING command. When supplied,
     * [expectedId] must be the next transaction id or no mutation is committed.
     */
    suspend fun prepare(
        operation: LifecycleOperation,
        expectedId: Long? = null,
    ): LifecycleTransactionToken

    /** Atomically claims id + closed operation while keeping outcome PENDING. */
    suspend fun claim(token: LifecycleTransactionToken): Boolean
    /** Re-loads the same PENDING token, including an already-started effect. */
    suspend fun isCurrent(token: LifecycleTransactionToken): Boolean
    suspend fun succeed(token: LifecycleTransactionToken, runtimeStarted: Boolean = false): Boolean
    suspend fun fail(token: LifecycleTransactionToken, errorCode: LifecycleErrorCode): Boolean
}
