/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.transport.tailscale

import com.excp.podroid.transport.api.HostTransportConfiguration
import com.excp.podroid.transport.state.AtomicHostTransportStateStore
import com.excp.podroid.transport.state.HostTransportFailure
import com.excp.podroid.transport.state.HostTransportPersistentState
import com.excp.podroid.transport.state.HostTransportPhase

/** The factory owns cleanup of any partial start when it throws. */
internal fun interface HostTransportRuntimeFactory {
    fun start(configuration: HostTransportConfiguration, generation: Long): OwnedHostTransportRuntime
}

/** Close must be idempotent; the supervisor alone owns the returned runtime. */
internal fun interface OwnedHostTransportRuntime : AutoCloseable {
    override fun close()
}

internal enum class HostTransportReconciliationResult {
    NO_CHANGE,
    STARTED,
    STOPPED,
    RECOVERED_AND_STARTED,
    RECOVERED_AND_STOPPED,
}

internal class HostTransportOwnershipException(message: String) : IllegalStateException(message)

/** Recovery is allowed only after the caller proves a persisted owner cannot still be live. */
internal fun interface AbandonedHostTransportOwnerProbe {
    fun isDefinitelyInactive(ownerProcess: String): Boolean
}

/**
 * Process-local lifecycle owner over an atomic durable generation record. It is
 * not composed with PodroidService or any VM/management effect path.
 */
internal class HostTransportLifecycleSupervisor(
    private val store: AtomicHostTransportStateStore,
    private val processInstance: String,
    private val configuration: HostTransportConfiguration,
    private val runtimeFactory: HostTransportRuntimeFactory,
    private val abandonedOwnerProbe: AbandonedHostTransportOwnerProbe,
) : AutoCloseable {
    private data class LocalRuntime(val generation: Long, val runtime: OwnedHostTransportRuntime)
    private var owned: LocalRuntime? = null
    private var closed = false

    init { require(processInstance.matches(HostTransportPersistentState.PROCESS_TOKEN)) }

    @Synchronized
    fun requestEnabled(enabled: Boolean): HostTransportPersistentState {
        check(!closed) { "Host transport supervisor is closed" }
        return store.update { current ->
            if (current.desiredEnabled == enabled) current else current.copy(
                desiredEnabled = enabled,
                desiredGeneration = Math.addExact(current.desiredGeneration, 1L),
            )
        }
    }

    @Synchronized
    fun reconcile(): HostTransportReconciliationResult {
        check(!closed) { "Host transport supervisor is closed" }
        var recovered = false
        var current = store.read()
        if (current.ownerProcess != null && current.ownerProcess != processInstance) {
            val previousOwner = current.ownerProcess
            if (!abandonedOwnerProbe.isDefinitelyInactive(previousOwner)) {
                throw HostTransportOwnershipException(
                    "persisted Host transport owner is not proven inactive",
                )
            }
            val stale = current
            current = store.update { latest ->
                if (latest == stale) latest.copy(
                    phase = HostTransportPhase.STOPPED,
                    ownerProcess = null,
                    ownerGeneration = null,
                    lastFailure = null,
                ) else latest
            }
            recovered = current.ownerProcess == null
        }

        val local = owned
        if (current.ownerProcess == processInstance && local == null) {
            store.update { latest ->
                if (latest.ownerProcess == processInstance) latest.copy(
                    phase = HostTransportPhase.RECOVERY_REQUIRED,
                    lastFailure = HostTransportFailure.OWNERSHIP_CONFLICT,
                ) else latest
            }
            throw HostTransportOwnershipException(
                "current process has durable Host transport ownership without a runtime capability",
            )
        }
        if (local != null && (current.ownerProcess != processInstance ||
                current.ownerGeneration != local.generation)
        ) {
            throw HostTransportOwnershipException("local Host transport capability lost durable ownership")
        }

        if (!current.desiredEnabled) {
            val stopped = if (local != null) stopOwned(current) else store.update { latest ->
                if (!latest.desiredEnabled && latest.ownerProcess == null) latest.copy(
                    appliedGeneration = latest.desiredGeneration,
                    phase = HostTransportPhase.STOPPED,
                    lastFailure = null,
                ) else latest
            }
            check(!stopped.desiredEnabled || stopped.ownerProcess == null)
            return if (recovered) HostTransportReconciliationResult.RECOVERED_AND_STOPPED
            else if (local != null || current.appliedGeneration != current.desiredGeneration ||
                current.phase != HostTransportPhase.STOPPED
            ) HostTransportReconciliationResult.STOPPED
            else HostTransportReconciliationResult.NO_CHANGE
        }

        if (local != null && current.phase == HostTransportPhase.RUNNING &&
            local.generation == current.desiredGeneration &&
            current.appliedGeneration == current.desiredGeneration
        ) {
            return if (recovered) HostTransportReconciliationResult.RECOVERED_AND_STARTED
            else HostTransportReconciliationResult.NO_CHANGE
        }

        if (local != null) {
            current = stopOwned(current)
            if (!current.desiredEnabled) {
                return if (recovered) HostTransportReconciliationResult.RECOVERED_AND_STOPPED
                else HostTransportReconciliationResult.STOPPED
            }
        }

        startCurrentGeneration()
        return if (recovered) HostTransportReconciliationResult.RECOVERED_AND_STARTED
        else HostTransportReconciliationResult.STARTED
    }

    private fun startCurrentGeneration(): HostTransportPersistentState {
        val claim = store.update { current ->
            if (!current.desiredEnabled || current.ownerProcess != null) {
                throw HostTransportOwnershipException("Host transport start generation is no longer eligible")
            }
            current.copy(
                phase = HostTransportPhase.STARTING,
                ownerProcess = processInstance,
                ownerGeneration = current.desiredGeneration,
                lastFailure = null,
            )
        }
        val generation = requireNotNull(claim.ownerGeneration)
        val runtime = try {
            runtimeFactory.start(configuration, generation)
        } catch (failure: Exception) {
            try {
                failStartClaim(generation)
            } catch (recordFailure: Exception) {
                failure.addSuppressed(recordFailure)
            }
            throw failure
        }
        return try {
            val running = store.update { current ->
                if (current.ownerProcess != processInstance || current.ownerGeneration != generation ||
                    current.phase != HostTransportPhase.STARTING
                ) throw HostTransportOwnershipException("Host transport start claim was superseded")
                current.copy(
                    appliedGeneration = generation,
                    phase = HostTransportPhase.RUNNING,
                    lastFailure = null,
                )
            }
            owned = LocalRuntime(generation, runtime)
            running
        } catch (failure: Exception) {
            val closeFailure = try {
                runtime.close()
                null
            } catch (closeFailure: Exception) {
                closeFailure
            }
            if (closeFailure == null) {
                try {
                    failStartClaim(generation)
                } catch (recordFailure: Exception) {
                    failure.addSuppressed(recordFailure)
                }
            } else {
                owned = LocalRuntime(generation, runtime)
                try {
                    store.update { current ->
                        if (current.ownerProcess == processInstance && current.ownerGeneration == generation) {
                            current.copy(
                                phase = HostTransportPhase.RECOVERY_REQUIRED,
                                lastFailure = HostTransportFailure.CLOSE_FAILED,
                            )
                        } else current
                    }
                } catch (recordFailure: Exception) {
                    closeFailure.addSuppressed(recordFailure)
                }
                failure.addSuppressed(closeFailure)
            }
            throw failure
        }
    }

    private fun failStartClaim(generation: Long) {
        store.update { current ->
            if (current.ownerProcess == processInstance && current.ownerGeneration == generation) {
                current.copy(
                    phase = HostTransportPhase.FAILED,
                    ownerProcess = null,
                    ownerGeneration = null,
                    lastFailure = HostTransportFailure.START_FAILED,
                )
            } else current
        }
    }

    private fun stopOwned(expected: HostTransportPersistentState): HostTransportPersistentState {
        val local = owned ?: return expected
        val stopping = store.update { current ->
            if (current.ownerProcess != processInstance || current.ownerGeneration != local.generation) {
                throw HostTransportOwnershipException("Host transport close does not own the durable generation")
            }
            current.copy(phase = HostTransportPhase.STOPPING, lastFailure = null)
        }
        try {
            local.runtime.close()
        } catch (failure: Exception) {
            store.update { current ->
                if (current.ownerProcess == processInstance && current.ownerGeneration == local.generation) {
                    current.copy(
                        phase = HostTransportPhase.RECOVERY_REQUIRED,
                        lastFailure = HostTransportFailure.CLOSE_FAILED,
                    )
                } else current
            }
            throw failure
        }
        owned = null
        return store.update { current ->
            if (current.ownerProcess != processInstance || current.ownerGeneration != local.generation) {
                throw HostTransportOwnershipException("Host transport close completion was superseded")
            }
            current.copy(
                appliedGeneration = if (!current.desiredEnabled) current.desiredGeneration
                    else stopping.appliedGeneration,
                phase = HostTransportPhase.STOPPED,
                ownerProcess = null,
                ownerGeneration = null,
                lastFailure = null,
            )
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        val local = owned
        if (local != null) stopOwned(store.read())
        closed = true
    }
}
