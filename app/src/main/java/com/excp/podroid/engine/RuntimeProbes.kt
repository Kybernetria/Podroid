/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.engine

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.excp.podroid.engine.avf.AvfReflect
import com.excp.podroid.vm.*
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal interface QemuRuntimeQmp {
    suspend fun queryStatus(): Result<String>
    suspend fun quit(): Result<Unit>
}

private class QemuRuntimeQmpClient(socketPath: String, timeoutMs: Long) : QemuRuntimeQmp {
    private val client = QmpClient(socketPath, timeoutMs)
    override suspend fun queryStatus() = client.queryStatus()
    override suspend fun quit() = client.quit()
}

internal class QemuNamedRuntimeProbe(
    private val qmpSocket: File,
    private val ownerStore: QemuRuntimeOwnerStore,
    private val fixedRuntimeEndpoints: List<File> = listOf(qmpSocket),
    private val timeoutMs: Long = QmpClient.SOCKET_TIMEOUT_MS,
    private val qmp: QemuRuntimeQmp = QemuRuntimeQmpClient(qmpSocket.absolutePath, timeoutMs),
) : NamedRuntimeProbe {
    override val backend = RuntimeBackend.QEMU
    @Volatile private var liveOwner: QemuRuntimeOwner? = null

    override suspend fun probe(): RuntimeProbeResult {
        val path = qmpSocket.toPath()
        val owner = ownerStore.inspect()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            val hasEvidence = fixedRuntimeEndpoints.any {
                Files.exists(it.toPath(), LinkOption.NOFOLLOW_LINKS)
            } || owner !is QemuOwnerInspection.Missing
            return if (hasEvidence) classifyDeadOwner(owner) else RuntimeProbeResult.Absent
        }
        val attributes = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: IOException) {
            return RuntimeProbeResult.Uncertain(LifecycleErrorCode.SECURITY, runtimeMayBeLive = true)
        }
        if (attributes.isSymbolicLink || attributes.isDirectory || attributes.isRegularFile) {
            return RuntimeProbeResult.Uncertain(LifecycleErrorCode.SECURITY, runtimeMayBeLive = true)
        }
        return qmp.queryStatus().fold(
            onSuccess = {
                liveOwner = (owner as? QemuOwnerInspection.Valid)?.owner
                RuntimeProbeResult.Live(RuntimeBackend.QEMU)
            },
            onFailure = { failure ->
                when {
                    failure.isTimeoutFailure() -> RuntimeProbeResult.Uncertain(
                        LifecycleErrorCode.PROBE_TIMEOUT,
                        runtimeMayBeLive = true,
                    )
                    failure.establishedQmpPeer() -> RuntimeProbeResult.Uncertain(
                        LifecycleErrorCode.RUNTIME_OWNERSHIP,
                        runtimeMayBeLive = true,
                    )
                    failure.refusedBeforeQmpPeer() -> classifyDeadOwner(owner)
                    else -> RuntimeProbeResult.Uncertain(
                        LifecycleErrorCode.RUNTIME_OWNERSHIP,
                        runtimeMayBeLive = true,
                    )
                }
            },
        )
    }

    override suspend fun stopLiveRuntime(): Boolean = qmp.quit().isSuccess

    override suspend fun awaitStopped(): Boolean {
        val owner = liveOwner ?: return false
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (ownerStore.exactProcessIsDead(owner)) return true
            delay(POLL_MS)
        }
        return false
    }

    private fun classifyDeadOwner(owner: QemuOwnerInspection): RuntimeProbeResult = when (owner) {
        QemuOwnerInspection.Missing -> RuntimeProbeResult.Uncertain(
            LifecycleErrorCode.RUNTIME_OWNERSHIP,
            runtimeMayBeLive = true,
        )
        is QemuOwnerInspection.Uncertain -> RuntimeProbeResult.Uncertain(
            owner.errorCode,
            runtimeMayBeLive = true,
        )
        is QemuOwnerInspection.Valid -> when (val proof = ownerStore.proveDead(owner)) {
            is DeadOwnerProof.Proven -> RuntimeProbeResult.StaleEndpoints(proof.evidence)
            DeadOwnerProof.ExactProcessAlive -> RuntimeProbeResult.Uncertain(
                LifecycleErrorCode.RUNTIME_OWNERSHIP,
                runtimeMayBeLive = true,
            )
            is DeadOwnerProof.Uncertain -> RuntimeProbeResult.Uncertain(
                proof.errorCode,
                runtimeMayBeLive = true,
            )
        }
    }

    private fun Throwable.establishedQmpPeer(): Boolean =
        causes().filterIsInstance<QmpEndpointFailure>().firstOrNull()?.connectionEstablished == true

    private fun Throwable.refusedBeforeQmpPeer(): Boolean =
        causes().filterIsInstance<QmpEndpointFailure>().firstOrNull()?.connectionEstablished == false

    private fun Throwable.isTimeoutFailure(): Boolean = causes().any { value ->
        value is java.net.SocketTimeoutException || value is TimeoutException ||
            value.message?.contains("deadline", ignoreCase = true) == true ||
            value.message?.contains("timed out", ignoreCase = true) == true
    }

    private fun Throwable.causes(): List<Throwable> {
        val values = mutableListOf<Throwable>()
        var current: Throwable? = this
        repeat(8) {
            val value = current ?: return values
            values += value
            current = value.cause
        }
        return values
    }

    private companion object { const val POLL_MS = 50L }
}

/** At most one potentially stuck vendor Binder thread is retained. */
private class BoundedBlockingCall(private val timeoutMs: Long) {
    private val active = AtomicBoolean(false)
    fun <T> run(block: () -> T): Result<T> {
        if (!active.compareAndSet(false, true)) {
            return Result.failure(TimeoutException("prior AVF operation is still pending"))
        }
        val executor = Executors.newSingleThreadExecutor(ThreadFactory { runnable ->
            Thread(runnable, "podroid-avf-runtime-probe").apply { isDaemon = true }
        })
        val future = executor.submit<T> {
            try { block() } finally { active.set(false) }
        }
        return try {
            Result.success(future.get(timeoutMs, TimeUnit.MILLISECONDS))
        } catch (failure: Throwable) {
            future.cancel(true)
            Result.failure(failure)
        } finally {
            executor.shutdownNow()
        }
    }
}

internal object AvfInspectionAvailabilityPolicy {
    fun classify(featurePresent: Boolean, inspectionPermissionGranted: Boolean): RuntimeProbeResult? =
        when {
            !featurePresent -> RuntimeProbeResult.Absent
            !inspectionPermissionGranted -> RuntimeProbeResult.Uncertain(
                LifecycleErrorCode.SECURITY,
                runtimeMayBeLive = true,
            )
            else -> null
        }
}

internal class AvfNamedRuntimeProbe(
    private val context: Context,
    timeoutMs: Long = PROBE_TIMEOUT_MS,
) : NamedRuntimeProbe {
    override val backend = RuntimeBackend.AVF
    private val blocking = BoundedBlockingCall(timeoutMs)
    @Volatile private var liveHandle: Any? = null

    override suspend fun probe(): RuntimeProbeResult = withContext(Dispatchers.IO) {
        val availability = try {
            AvfInspectionAvailabilityPolicy.classify(
                context.packageManager.hasSystemFeature("android.software.virtualization_framework"),
                ContextCompat.checkSelfPermission(context, "android.permission.MANAGE_VIRTUAL_MACHINE") ==
                    PackageManager.PERMISSION_GRANTED,
            )
        } catch (_: SecurityException) {
            RuntimeProbeResult.Uncertain(
                LifecycleErrorCode.SECURITY,
                runtimeMayBeLive = true,
            )
        }
        if (availability != null) return@withContext availability
        blocking.run {
            val manager = AvfReflect.manager(context)
            val vm = AvfReflect.get(manager, VM_NAME) ?: return@run null
            vm to AvfReflect.getStatus(vm)
        }.fold(
            onSuccess = { observed ->
                if (observed == null || observed.second == STATUS_STOPPED || observed.second == STATUS_DELETED) {
                    liveHandle = null
                    RuntimeProbeResult.Absent
                } else {
                    liveHandle = observed.first
                    RuntimeProbeResult.Live(RuntimeBackend.AVF)
                }
            },
            onFailure = { failure ->
                RuntimeProbeResult.Uncertain(
                    if (failure.hasCause<TimeoutException>()) LifecycleErrorCode.PROBE_TIMEOUT
                    else if (failure.hasCause<SecurityException>()) LifecycleErrorCode.SECURITY
                    else LifecycleErrorCode.RUNTIME_OWNERSHIP,
                    runtimeMayBeLive = true,
                )
            },
        )
    }

    override suspend fun stopLiveRuntime(): Boolean = withContext(Dispatchers.IO) {
        val handle = liveHandle ?: return@withContext true
        blocking.run { AvfReflect.stop(handle) }.isSuccess
    }

    override suspend fun awaitStopped(): Boolean = withContext(Dispatchers.IO) {
        val handle = liveHandle ?: return@withContext true
        blocking.run {
            val deadline = System.nanoTime() + PROBE_TIMEOUT_MS * 1_000_000L
            while (System.nanoTime() < deadline) {
                val status = AvfReflect.getStatus(handle)
                if (status == STATUS_STOPPED || status == STATUS_DELETED) return@run true
                Thread.sleep(POLL_MS)
            }
            false
        }.getOrDefault(false).also { if (it) liveHandle = null }
    }

    private companion object {
        const val VM_NAME = "podroid"
        const val STATUS_STOPPED = 0
        const val STATUS_DELETED = 2
        const val PROBE_TIMEOUT_MS = 5_000L
        const val POLL_MS = 50L
    }
}

private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean {
    var current: Throwable? = this
    repeat(8) {
        val value = current ?: return false
        if (value is T) return true
        current = value.cause
    }
    return false
}

@Singleton
class ProductionRuntimePreflight @Inject internal constructor(
    @ApplicationContext context: Context,
    paths: VmPaths,
) {
    private val qemuOwnerStore = QemuRuntimeOwnerStore(paths)
    internal val coordinator = RuntimePreflightCoordinator(
        qemu = QemuNamedRuntimeProbe(
            paths.qmpSocket,
            qemuOwnerStore,
            listOf(
                paths.serialSocket,
                paths.terminalSocket,
                paths.controlSocket,
                paths.hostSocket,
                paths.qmpSocket,
            ),
        ),
        avf = AvfNamedRuntimeProbe(context),
        staleCleaner = StaleRuntimeEndpointCleaner { evidence ->
            val qemuEvidence = evidence as? QemuStaleRuntimeEvidence
                ?: throw IOException("Unexpected runtime cleanup evidence")
            qemuOwnerStore.cleanup(qemuEvidence)
        },
    )
}
