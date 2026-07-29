/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.vm

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class RuntimeBackend { QEMU, AVF }
internal interface StaleRuntimeEvidence
internal sealed interface RuntimeProbeResult {
    data object Absent : RuntimeProbeResult
    data class StaleEndpoints(val evidence: StaleRuntimeEvidence) : RuntimeProbeResult
    data class Live(val backend: RuntimeBackend) : RuntimeProbeResult
    data class Uncertain(
        val errorCode: LifecycleErrorCode,
        val runtimeMayBeLive: Boolean,
    ) : RuntimeProbeResult
}

/** A probe owns only one fixed per-instance runtime; it accepts no path or name. */
internal interface NamedRuntimeProbe {
    val backend: RuntimeBackend
    suspend fun probe(): RuntimeProbeResult
    suspend fun stopLiveRuntime(): Boolean
    suspend fun awaitStopped(): Boolean
}

internal fun interface StaleRuntimeEndpointCleaner {
    fun removeCheckedEndpoints(evidence: StaleRuntimeEvidence)
}

/**
 * One-active-VM preflight. A runtime from a dead Android process is never
 * adopted because ownership callbacks cannot be reconstructed safely.
 */
internal class RuntimePreflightCoordinator(
    private val qemu: NamedRuntimeProbe,
    private val avf: NamedRuntimeProbe,
    private val staleCleaner: StaleRuntimeEndpointCleaner,
) {
    private val mutex = Mutex()

    init {
        require(qemu.backend == RuntimeBackend.QEMU)
        require(avf.backend == RuntimeBackend.AVF)
    }

    suspend fun prepareForLaunch() = ensureAllFixedRuntimesStopped()

    /** Definitive fixed-name preflight shared by launch and explicit stop. */
    suspend fun ensureAllFixedRuntimesStopped() = mutex.withLock {
        var qemuNeedsCleanup: StaleRuntimeEvidence? = null
        for (probe in listOf(qemu, avf)) {
            when (val result = probe.probe()) {
                RuntimeProbeResult.Absent -> Unit
                is RuntimeProbeResult.StaleEndpoints -> {
                    if (probe.backend != RuntimeBackend.QEMU) {
                        throw IOException("Non-QEMU probe reported filesystem endpoints")
                    }
                    qemuNeedsCleanup = result.evidence
                }
                is RuntimeProbeResult.Uncertain -> throw RuntimeProbeException(
                    result.errorCode,
                    result.runtimeMayBeLive,
                )
                is RuntimeProbeResult.Live -> {
                    if (!probe.stopLiveRuntime() || !probe.awaitStopped()) {
                        throw RuntimeProbeException(
                            LifecycleErrorCode.RUNTIME_OWNERSHIP,
                            runtimeMayBeLive = true,
                        )
                    }
                    when (val stopped = probe.probe()) {
                        RuntimeProbeResult.Absent -> Unit
                        is RuntimeProbeResult.StaleEndpoints -> {
                            if (probe.backend != RuntimeBackend.QEMU) {
                                throw RuntimeProbeException(
                                    LifecycleErrorCode.RUNTIME_OWNERSHIP,
                                    runtimeMayBeLive = true,
                                )
                            }
                            qemuNeedsCleanup = stopped.evidence
                        }
                        else -> throw RuntimeProbeException(
                            LifecycleErrorCode.RUNTIME_OWNERSHIP,
                            runtimeMayBeLive = true,
                        )
                    }
                }
            }
        }
        qemuNeedsCleanup?.let { evidence ->
            try {
                staleCleaner.removeCheckedEndpoints(evidence)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: RuntimeProbeException) {
                throw failure
            } catch (failure: Throwable) {
                throw RuntimeProbeException(
                    LifecycleErrorCode.RUNTIME_OWNERSHIP,
                    runtimeMayBeLive = true,
                    cause = failure,
                )
            }
        }
    }
}

internal class RuntimeProbeException(
    val stableCode: LifecycleErrorCode,
    val runtimeMayBeLive: Boolean,
    cause: Throwable? = null,
) : IOException(
    "Runtime preflight failed with stable code ${stableCode.name}",
    cause,
)

/** Ticket #15 has not configured a management transport yet. */
internal interface HostTransportReconciler {
    suspend fun reconcile(vmId: VmId): TransportReconciliationResult
}
internal enum class TransportReconciliationResult { RECONCILED, NO_CONFIGURED_TRANSPORT }
internal object NoConfiguredHostTransportReconciler : HostTransportReconciler {
    override suspend fun reconcile(vmId: VmId) = TransportReconciliationResult.NO_CONFIGURED_TRANSPORT
}
