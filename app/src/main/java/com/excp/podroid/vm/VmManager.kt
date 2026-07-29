/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.vm

import com.excp.podroid.data.repository.PortForwardRule
import com.excp.podroid.engine.QmpController
import com.excp.podroid.engine.VmConfig
import com.excp.podroid.engine.VmEngine
import com.excp.podroid.engine.VmState
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The intentionally small public lifecycle state exposed above backend engines. */
enum class VmLifecycleState { IDLE, STARTING, RUNNING, STOPPED, ERROR }

data class VmStatus(
    val vmId: VmId,
    val installed: Boolean,
    val lifecycle: VmLifecycleState,
    val backendId: String,
    val errorMessage: String? = null,
)

data class VmSummary(val vmId: VmId, val installed: Boolean, val lifecycle: VmLifecycleState)

enum class VmRemovePolicy {
    /** Remove boot/runtime installation files while retaining storage.img. */
    PRESERVE_DATA,
    /** Explicitly remove the installation and persistent storage image. */
    DELETE_DATA,
}

data class ConsoleLogRequest(val maxBytes: Int, val maxLines: Int) {
    init {
        require(maxBytes in 1..MAX_BYTES) { "maxBytes must be in 1..$MAX_BYTES" }
        require(maxLines in 1..MAX_LINES) { "maxLines must be in 1..$MAX_LINES" }
    }

    companion object {
        const val MAX_BYTES = 64 * 1024
        const val MAX_LINES = 1_000
    }
}

data class ConsoleLog(
    val text: String,
    val bytesRead: Int,
    val lineCount: Int,
    val truncated: Boolean,
)

/** Closed QMP allowlist. No manager API accepts a command name or JSON arguments. */
sealed interface VmQmpOperation {
    data object QueryStatus : VmQmpOperation
    data object QueryVersion : VmQmpOperation
}

sealed interface VmQmpResult {
    data class Status(val value: String) : VmQmpResult
    data class Version(val major: Int, val minor: Int, val micro: Int) : VmQmpResult
}

data class SshEndpoint(val host: String, val port: Int)

data class SshEndpointDiscovery(
    val enabled: Boolean,
    val reachable: Boolean,
    val endpoint: SshEndpoint?,
)

/**
 * Default-instance manager boundary. Every call includes and validates [VmId];
 * the contract deliberately has no Binder, desired-state, or reconciliation API.
 */
interface VmManager {
    fun lifecycle(vmId: VmId): StateFlow<VmLifecycleState>
    /** True only after all backend resources for the active generation are released. */
    fun quiescent(vmId: VmId): StateFlow<Boolean>
    /** Manager-owned launch work or a non-quiescent backend generation is in progress. */
    fun busy(vmId: VmId): StateFlow<Boolean>
    suspend fun list(vmId: VmId): List<VmSummary>
    suspend fun status(vmId: VmId): VmStatus
    suspend fun ensureInstalled(vmId: VmId)
    suspend fun start(vmId: VmId)
    suspend fun stop(vmId: VmId)
    suspend fun forceStop(vmId: VmId)
    suspend fun restart(vmId: VmId)
    suspend fun remove(vmId: VmId, policy: VmRemovePolicy)
    suspend fun readConsoleLog(vmId: VmId, request: ConsoleLogRequest): ConsoleLog
    suspend fun executeQmp(vmId: VmId, operation: VmQmpOperation): VmQmpResult
    suspend fun discoverSshEndpoint(vmId: VmId): SshEndpointDiscovery
}

internal data class VmLaunchPlan(
    val portForwards: List<PortForwardRule>,
    val config: VmConfig,
)

internal interface ManagedVmRuntime {
    val vmId: VmId
    val state: StateFlow<VmState>
    val quiescent: StateFlow<Boolean>
    val backendId: String
    val qmpAvailable: Boolean
    suspend fun start(plan: VmLaunchPlan)
    fun stop()
    fun forceStop()
    suspend fun systemPowerdown(): Result<Unit>
    suspend fun executeQmp(operation: VmQmpOperation): Result<VmQmpResult>
}

internal interface VmAssetTreeLease {
    suspend fun install(vmId: VmId)
}

internal interface VmInstaller {
    /** One authoritative initial extraction result; failure is sticky and fail-closed. */
    suspend fun awaitInitial(vmId: VmId)

    /** Exclusive application-wide lease shared by extraction, launch reads, and removal. */
    suspend fun <T> withExclusiveTree(
        vmId: VmId,
        action: suspend (VmAssetTreeLease) -> T,
    ): T
}

internal interface VmConfigurationSource {
    suspend fun launchPlan(vmId: VmId): VmLaunchPlan
    suspend fun sshEnabled(vmId: VmId): Boolean
}

internal interface VmFiles {
    fun isInstalled(vmId: VmId): Boolean
    fun remove(vmId: VmId, policy: VmRemovePolicy)
    fun readConsole(vmId: VmId, request: ConsoleLogRequest): ConsoleLog
}

/** Production implementation; dependencies are narrow so manager policy is JVM-testable. */
class DefaultVmManager internal constructor(
    private val runtime: ManagedVmRuntime,
    private val installer: VmInstaller,
    private val configuration: VmConfigurationSource,
    private val files: VmFiles,
    private val scope: CoroutineScope,
    private val startAcceptanceTimeoutMs: Long = 5_000L,
    private val guestShutdownTimeoutMs: Long = 5_000L,
    private val backendStopTimeoutMs: Long = 15_000L,
    private val forceStopTimeoutMs: Long = 7_000L,
    private val qmpTimeoutMs: Long = 5_000L,
) : VmManager {
    private val lifecycleMutex = Mutex()
    private val stopTaskMutex = Mutex()
    @Volatile private var stopTask: Deferred<Unit>? = null
    @Volatile private var stopForceSignal: CompletableDeferred<Unit>? = null
    @Volatile private var startTask: Deferred<Unit>? = null
    private var installationEnsured = false
    private val launchPending = MutableStateFlow(false)

    private val lifecycleFlow: StateFlow<VmLifecycleState> = runtime.state
        .combine(launchPending) { state, pending -> effectiveLifecycle(state, pending) }
        .stateIn(scope, SharingStarted.Eagerly, effectiveLifecycle(runtime.state.value, false))
    private val busyUpdates: StateFlow<Boolean> = runtime.quiescent
        .combine(launchPending) { quiescent, pending -> pending || !quiescent }
        .stateIn(scope, SharingStarted.Eagerly, launchPending.value || !runtime.quiescent.value)
    private val busyFlow: StateFlow<Boolean> = object : StateFlow<Boolean> by busyUpdates {
        // combine/stateIn propagation is asynchronous; imperative duplicate/stop
        // decisions require an exact current snapshot.
        override val value: Boolean
            get() = launchPending.value || !runtime.quiescent.value
    }

    init {
        require(runtime.vmId == VmId.DEFAULT) { "Only the default VM runtime is supported" }
        require(startAcceptanceTimeoutMs > 0 && guestShutdownTimeoutMs > 0)
        require(backendStopTimeoutMs > 0 && forceStopTimeoutMs > 0 && qmpTimeoutMs > 0)
    }

    override fun lifecycle(vmId: VmId): StateFlow<VmLifecycleState> {
        requireDefault(vmId)
        return lifecycleFlow
    }

    override fun quiescent(vmId: VmId): StateFlow<Boolean> {
        requireDefault(vmId)
        return runtime.quiescent
    }

    override fun busy(vmId: VmId): StateFlow<Boolean> {
        requireDefault(vmId)
        return busyFlow
    }

    override suspend fun list(vmId: VmId): List<VmSummary> = withTree(vmId) {
        listOf(VmSummary(vmId, files.isInstalled(vmId), effectiveLifecycle(runtime.state.value, launchPending.value)))
    }

    override suspend fun status(vmId: VmId): VmStatus = withTree(vmId) {
        val state = runtime.state.value
        VmStatus(
            vmId = vmId,
            installed = files.isInstalled(vmId),
            lifecycle = effectiveLifecycle(state, launchPending.value),
            backendId = runtime.backendId,
            errorMessage = (state as? VmState.Error)?.message,
        )
    }

    override suspend fun ensureInstalled(vmId: VmId) {
        awaitInitial(vmId)
        lifecycleMutex.withLock {
            check(runtime.quiescent.value) { "Cannot install while VM cleanup is incomplete" }
            installer.withExclusiveTree(vmId) { lease -> ensureInstalledLocked(vmId, lease) }
        }
    }

    override suspend fun start(vmId: VmId) {
        awaitInitial(vmId)
        lifecycleMutex.withLock {
            check(!busyFlow.value) { "Cannot start while previous VM work or cleanup is incomplete" }
            installer.withExclusiveTree(vmId) { lease ->
                ensureInstalledLocked(vmId, lease)
                startLocked(vmId)
            }
        }
    }

    override suspend fun stop(vmId: VmId) {
        awaitInitial(vmId)
        requestStop(force = false).await()
    }

    override suspend fun forceStop(vmId: VmId) {
        awaitInitial(vmId)
        requestStop(force = true).await()
    }

    override suspend fun restart(vmId: VmId) {
        awaitInitial(vmId)
        // Duplicate delivery while the replacement boot is already in progress
        // is satisfied by that in-flight replacement; do not stop it again.
        if (busyFlow.value && runtime.state.value is VmState.Starting) return
        requestStop(force = false).await()
        lifecycleMutex.withLock {
            check(runtime.quiescent.value) { "Cannot restart while VM cleanup is incomplete" }
            installer.withExclusiveTree(vmId) { lease ->
                ensureInstalledLocked(vmId, lease)
                startLocked(vmId)
            }
        }
    }

    override suspend fun remove(vmId: VmId, policy: VmRemovePolicy) {
        awaitInitial(vmId)
        lifecycleMutex.withLock {
            check(runtime.quiescent.value) { "Cannot remove VM while backend cleanup is incomplete" }
            installer.withExclusiveTree(vmId) {
                files.remove(vmId, policy)
                installationEnsured = false
            }
        }
    }

    override suspend fun readConsoleLog(vmId: VmId, request: ConsoleLogRequest): ConsoleLog =
        withTree(vmId) { files.readConsole(vmId, request) }

    override suspend fun executeQmp(vmId: VmId, operation: VmQmpOperation): VmQmpResult {
        awaitInitial(vmId)
        check(runtime.state.value is VmState.Running) { "QMP requires a running VM" }
        check(runtime.qmpAvailable) { "QMP is unavailable for backend '${runtime.backendId}'" }
        return withTimeoutOrNull(qmpTimeoutMs) { runtime.executeQmp(operation).getOrThrow() }
            ?: throw IOException("QMP operation timed out")
    }

    override suspend fun discoverSshEndpoint(vmId: VmId): SshEndpointDiscovery {
        awaitInitial(vmId)
        val enabled = configuration.sshEnabled(vmId)
        val reachable = enabled && runtime.state.value is VmState.Running && !runtime.quiescent.value
        return SshEndpointDiscovery(
            enabled = enabled,
            reachable = reachable,
            endpoint = if (reachable) SshEndpoint(SSH_HOST, SSH_HOST_PORT) else null,
        )
    }

    private suspend fun ensureInstalledLocked(vmId: VmId, lease: VmAssetTreeLease) {
        if (installationEnsured && files.isInstalled(vmId)) return
        lease.install(vmId)
        check(files.isInstalled(vmId)) { "VM installer completed without a valid installation" }
        installationEnsured = true
    }

    private suspend fun startLocked(vmId: VmId) {
        launchPending.value = true
        try {
            val plan = configuration.launchPlan(vmId)
            require(plan.config.vmId == vmId) { "Launch plan VM id mismatch" }
            val task = scope.async { runtime.start(plan) }
            startTask = task

            val accepted = withTimeoutOrNull(startAcceptanceTimeoutMs) {
                while (!isRuntimeActive() && runtime.state.value !is VmState.Error && !task.isCompleted) {
                    delay(10L)
                }
                true
            } ?: false
            if (!accepted) {
                // Make force intent authoritative before cancelling the owned
                // start task, so a backend stalled before process assignment can
                // observe the stop generation and must not launch later.
                forceCleanupWithin(forceStopTimeoutMs)
                // Keep the start deadline bounded, but give backend cleanup and
                // owned-task joining one shared documented force window.
                throw IOException("VM start was not accepted within ${startAcceptanceTimeoutMs}ms")
            }
            if (task.isCompleted) task.await()
            val error = runtime.state.value as? VmState.Error
            if (error != null) {
                // Error does not imply cleanup. Keep a still-active start task
                // owned and routable so stop/force retry can complete the same
                // generation; cancellation is reserved for an authoritative
                // force request.
                throw IOException("VM start failed: ${error.message}")
            }
            check(isRuntimeActive()) { "VM backend returned before reaching an active state" }
        } catch (cancelled: CancellationException) {
            // The service cancels its exact caller Job when Stop invalidates this
            // generation. The runtime task belongs to the manager scope, so it
            // must be force-invalidated, cancelled, and joined explicitly or it
            // could accept a backend after the caller released lifecycleMutex.
            val cleaned = withContext(NonCancellable) {
                forceCleanupWithin(forceStopTimeoutMs)
            }
            if (!cleaned) {
                cancelled.addSuppressed(
                    IllegalStateException("Cancelled VM launch cleanup exceeded the force-stop deadline")
                )
            }
            throw cancelled
        } finally {
            launchPending.value = false
        }
    }

    private suspend fun requestStop(force: Boolean): Deferred<Unit> = stopTaskMutex.withLock {
        val existing = stopTask
        if (existing != null && existing.isActive) {
            if (force) stopForceSignal?.complete(Unit)
            return@withLock existing
        }
        val forceSignal = CompletableDeferred<Unit>()
        if (force) forceSignal.complete(Unit)
        stopForceSignal = forceSignal
        scope.async {
            lifecycleMutex.withLock { coordinatedStopLocked(forceSignal) }
        }.also { stopTask = it }
    }

    private suspend fun coordinatedStopLocked(forceSignal: CompletableDeferred<Unit>) {
        if (runtime.quiescent.value) return
        if (forceSignal.isCompleted) {
            forceAndAwaitQuiescence()
            return
        }

        val powerdown = if (runtime.state.value is VmState.Running && runtime.qmpAvailable) {
            val task = scope.async { runtime.systemPowerdown().isSuccess }
            val completed = withTimeoutOrNull(qmpTimeoutMs) {
                while (!task.isCompleted && !forceSignal.isCompleted) delay(STOP_POLL_MS)
                !forceSignal.isCompleted
            } == true
            if (!completed) task.cancel()
            if (forceSignal.isCompleted) {
                forceAndAwaitQuiescence()
                return
            }
            if (task.isCompleted) runCatching { task.await() }.getOrDefault(false) else false
        } else {
            false
        }
        if (powerdown) {
            when (awaitQuiescenceOrForce(guestShutdownTimeoutMs, forceSignal)) {
                StopWait.QUIESCENT -> return
                StopWait.FORCE -> { forceAndAwaitQuiescence(); return }
                StopWait.TIMEOUT -> Unit
            }
        }

        runtime.stop()
        when (awaitQuiescenceOrForce(backendStopTimeoutMs, forceSignal)) {
            StopWait.QUIESCENT -> return
            StopWait.FORCE -> { forceAndAwaitQuiescence(); return }
            StopWait.TIMEOUT -> Unit
        }
        forceAndAwaitQuiescence()
    }

    private suspend fun forceAndAwaitQuiescence() {
        // Stop intent must be visible to the backend generation before its
        // manager-owned start coroutine is cancelled.
        check(forceCleanupWithin(forceStopTimeoutMs)) {
            "VM backend cleanup did not complete within the force-stop deadline"
        }
    }

    private suspend fun forceCleanupWithin(timeoutMs: Long): Boolean {
        runtime.forceStop()
        val task = startTask
        if (task?.isActive == true) task.cancel()
        return withTimeoutOrNull(timeoutMs) {
            task?.join()
            // Collection can briefly replay a flattened router cache. Poll the
            // StateFlow's exact imperative value so stale true never completes
            // a stop while the concrete backend still owns resources.
            while (!runtime.quiescent.value) delay(STOP_POLL_MS)
            true
        } == true
    }

    private suspend fun awaitQuiescenceOrForce(
        timeoutMs: Long,
        forceSignal: CompletableDeferred<Unit>,
    ): StopWait {
        if (runtime.quiescent.value) return StopWait.QUIESCENT
        return withTimeoutOrNull(timeoutMs) {
            while (!runtime.quiescent.value && !forceSignal.isCompleted) delay(STOP_POLL_MS)
            if (runtime.quiescent.value) StopWait.QUIESCENT else StopWait.FORCE
        } ?: StopWait.TIMEOUT
    }

    private suspend fun awaitInitial(vmId: VmId) {
        requireDefault(vmId)
        installer.awaitInitial(vmId)
    }

    private suspend fun <T> withTree(vmId: VmId, action: suspend () -> T): T {
        awaitInitial(vmId)
        return installer.withExclusiveTree(vmId) { action() }
    }

    private fun isRuntimeActive(): Boolean = when (runtime.state.value) {
        is VmState.Starting, is VmState.Running -> true
        else -> false
    }

    private fun requireDefault(vmId: VmId) {
        require(vmId == VmId.DEFAULT) { "Only the default VM is supported" }
    }

    private enum class StopWait { QUIESCENT, FORCE, TIMEOUT }

    companion object {
        const val SSH_HOST_PORT = 9922
        const val SSH_HOST = "127.0.0.1"
        private const val STOP_POLL_MS = 10L

        internal fun mapLifecycle(state: VmState): VmLifecycleState = when (state) {
            is VmState.Idle -> VmLifecycleState.IDLE
            is VmState.Starting -> VmLifecycleState.STARTING
            is VmState.Running -> VmLifecycleState.RUNNING
            is VmState.Stopped -> VmLifecycleState.STOPPED
            is VmState.Error -> VmLifecycleState.ERROR
        }

        private fun effectiveLifecycle(state: VmState, launchPending: Boolean): VmLifecycleState =
            if (launchPending && state !is VmState.Starting && state !is VmState.Running) {
                VmLifecycleState.STARTING
            } else {
                mapLifecycle(state)
            }
    }
}

/** Adapter is the sole manager-side owner of inherited engine/QMP details. */
internal class EngineManagedVmRuntime(private val engine: VmEngine) : ManagedVmRuntime {
    override val vmId: VmId get() = engine.vmId
    override val state: StateFlow<VmState> get() = engine.state
    override val quiescent: StateFlow<Boolean> get() = engine.quiescent
    override val backendId: String get() = engine.backendId
    override val qmpAvailable: Boolean get() = engine.qmpController != null

    override suspend fun start(plan: VmLaunchPlan) = engine.start(plan.portForwards, plan.config)
    override fun stop() = engine.stop()
    override fun forceStop() = engine.forceStop()
    override suspend fun systemPowerdown(): Result<Unit> =
        engine.qmpController?.systemPowerdown() ?: Result.failure(UnsupportedOperationException("QMP unavailable"))

    override suspend fun executeQmp(operation: VmQmpOperation): Result<VmQmpResult> {
        val qmp: QmpController = engine.qmpController
            ?: return Result.failure(UnsupportedOperationException("QMP unavailable"))
        return when (operation) {
            VmQmpOperation.QueryStatus -> qmp.queryStatus().mapCatching {
                require(it.length <= MAX_QMP_STATUS_CHARS) { "QMP status exceeds typed result bound" }
                VmQmpResult.Status(it)
            }
            VmQmpOperation.QueryVersion -> qmp.queryVersion().map {
                VmQmpResult.Version(it.first, it.second, it.third)
            }
        }
    }

    companion object {
        private const val MAX_QMP_STATUS_CHARS = 64
    }
}

/**
 * Bounded NOFOLLOW installation/removal/log access over authoritative VmPaths.
 * Manager calls hold the application asset-tree lease, and descriptor/path
 * identity is revalidated around reads/deletes. A same-UID arbitrary-code
 * compromise is explicitly outside this file-manager boundary: such code
 * already has direct read/write access to the complete app-private filesDir.
 */
internal class VmPathFiles(private val paths: VmPaths) : VmFiles {
    private val instanceRoot = paths.instanceDirectory.toPath().toAbsolutePath().normalize()
    private val runtimeEndpoints = setOf(
        paths.serialSocket, paths.terminalSocket, paths.controlSocket, paths.hostSocket,
        paths.qmpSocket, paths.avfTerminalSocket, paths.avfControlSocket,
    ).mapTo(mutableSetOf()) { it.toPath().toAbsolutePath().normalize() }

    override fun isInstalled(vmId: VmId): Boolean {
        requireVm(vmId)
        if (!instanceExistsSafely()) return false
        val requiredFilesPresent = listOf(paths.kernel, paths.initrd, paths.rootfs, paths.qemuEfiRom).all {
            val path = it.toPath().toAbsolutePath().normalize()
            exists(path) && regular(path)
        }
        val keymaps = paths.qemuKeymapsDirectory.toPath().toAbsolutePath().normalize()
        return requiredFilesPresent && directory(keymaps)
    }

    override fun remove(vmId: VmId, policy: VmRemovePolicy) {
        requireVm(vmId)
        if (!instanceExistsSafely()) return
        val rootIdentity = attrs(instanceRoot)
        val storage = paths.storageImage.toPath().toAbsolutePath().normalize()
        if (exists(storage) && !regular(storage)) {
            throw IOException("Persistent storage is not a regular no-follow file")
        }
        val entries = scanForRemoval()
        for (entry in entries) {
            if (policy == VmRemovePolicy.PRESERVE_DATA && entry.path == storage) continue
            revalidateForDeletion(entry)
            Files.delete(entry.path)
        }
        if (policy == VmRemovePolicy.DELETE_DATA || !exists(storage)) {
            requireSameIdentity(instanceRoot, rootIdentity, attrs(instanceRoot))
            Files.newDirectoryStream(instanceRoot).use { stream ->
                if (stream.iterator().hasNext()) throw IOException("VM root changed during removal")
            }
            Files.delete(instanceRoot)
        }
        VmPathSecurity.forceDirectory(paths.instancesDirectory.toPath())
    }

    override fun readConsole(vmId: VmId, request: ConsoleLogRequest): ConsoleLog {
        requireVm(vmId)
        if (!instanceExistsSafely()) return ConsoleLog("", 0, 0, false)
        val log = paths.consoleLog.toPath().toAbsolutePath().normalize()
        if (!exists(log)) return ConsoleLog("", 0, 0, false)
        if (!regular(log)) throw IOException("Console log is not a regular no-follow file")

        val beforeOpen = attrs(log)
        val bytes: ByteArray
        val length: Long
        FileChannel.open(log, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val afterOpen = attrs(log)
            requireSameIdentity(log, beforeOpen, afterOpen)
            // channel.size() is descriptor-relative: it proves the bounded read
            // below uses the same open file that was identity-checked at the path.
            length = channel.size()
            val bytesToRead = minOf(length, request.maxBytes.toLong()).toInt()
            bytes = ByteArray(bytesToRead)
            channel.position(length - bytesToRead)
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) throw IOException("Console log changed during bounded read")
            }
            requireSameIdentity(log, afterOpen, attrs(log))
        }
        val bytesToRead = bytes.size
        var text = bytes.toString(Charsets.UTF_8)
        var truncated = length > bytesToRead
        if (length > bytesToRead) {
            val firstNewline = text.indexOf('\n')
            if (firstNewline >= 0) text = text.substring(firstNewline + 1)
        }
        val lines = if (text.isEmpty()) emptyList() else text.split('\n')
        if (lines.size > request.maxLines) {
            text = lines.takeLast(request.maxLines).joinToString("\n")
            truncated = true
        }
        // Malformed UTF-8 replacement can expand the decoded representation;
        // cap the returned UTF-8 bytes again without reading more from disk.
        val encoded = text.toByteArray(Charsets.UTF_8)
        if (encoded.size > request.maxBytes) {
            text = encoded.takeLast(request.maxBytes).toByteArray().toString(Charsets.UTF_8)
            truncated = true
        }
        val lineCount = if (text.isEmpty()) 0 else text.count { it == '\n' } + 1
        return ConsoleLog(text, text.toByteArray(Charsets.UTF_8).size, lineCount, truncated)
    }

    private data class RemovalEntry(val path: Path, val attributes: BasicFileAttributes)

    private fun scanForRemoval(): List<RemovalEntry> {
        var count = 0
        val result = ArrayList<RemovalEntry>()
        val pending = ArrayDeque<Pair<Path, Int>>()
        pending.add(instanceRoot to 0)
        while (pending.isNotEmpty()) {
            val (directory, depth) = pending.removeFirst()
            Files.newDirectoryStream(directory).use { stream ->
                for (raw in stream) {
                    count++
                    if (count > MAX_ENTRIES) throw IOException("VM removal entry bound exceeded")
                    val entry = raw.toAbsolutePath().normalize()
                    if (!entry.startsWith(instanceRoot) || entry == instanceRoot) {
                        throw IOException("VM removal path escaped instance root")
                    }
                    val attrs = attrs(entry)
                    when {
                        attrs.isSymbolicLink -> throw IOException("Symbolic link rejected during VM removal: $entry")
                        attrs.isDirectory -> {
                            if (depth >= MAX_DEPTH) throw IOException("VM removal depth bound exceeded")
                            pending.add(entry to depth + 1)
                        }
                        attrs.isRegularFile -> Unit
                        entry in runtimeEndpoints -> Unit
                        else -> throw IOException("Special file rejected during VM removal: $entry")
                    }
                    result.add(RemovalEntry(entry, attrs))
                }
            }
        }
        return result.sortedByDescending { it.path.nameCount }
    }

    private fun revalidateForDeletion(entry: RemovalEntry) {
        val current = attrs(entry.path)
        requireSameIdentity(entry.path, entry.attributes, current)
        if (current.isRegularFile) {
            FileChannel.open(entry.path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                requireSameIdentity(entry.path, current, attrs(entry.path))
                if (channel.size() != current.size()) {
                    throw IOException("VM removal file changed after descriptor open: ${entry.path}")
                }
            }
        } else if (current.isDirectory) {
            Files.newDirectoryStream(entry.path).use { stream ->
                if (stream.iterator().hasNext()) throw IOException("VM removal directory changed after scan: ${entry.path}")
            }
        }
    }

    private fun requireSameIdentity(
        path: Path,
        expected: BasicFileAttributes,
        actual: BasicFileAttributes,
    ) {
        val expectedKey = expected.fileKey()
        val actualKey = actual.fileKey()
        val same = if (expectedKey != null && actualKey != null) {
            expectedKey == actualKey
        } else {
            expected.creationTime() == actual.creationTime() &&
                expected.isDirectory == actual.isDirectory &&
                expected.isRegularFile == actual.isRegularFile &&
                expected.isOther == actual.isOther
        }
        if (!same) throw IOException("VM path identity changed during operation: $path")
    }

    private fun instanceExistsSafely(): Boolean {
        requireFilesRootHierarchy()
        val instances = paths.instancesDirectory.toPath().toAbsolutePath().normalize()
        if (!exists(instances)) return false
        if (!directory(instances)) throw IOException("Unsafe VM instances directory")
        if (!exists(instanceRoot)) return false
        requireSafeHierarchy()
        return true
    }

    private fun requireFilesRootHierarchy() {
        val filesRoot = paths.filesDirectory.toPath().toAbsolutePath().normalize()
        var current = filesRoot.root ?: throw IOException("filesDir is not absolute")
        for (segment in filesRoot) {
            current = current.resolve(segment)
            if (!directory(current)) throw IOException("Unsafe filesDir hierarchy: $current")
        }
    }

    private fun requireSafeHierarchy() {
        requireFilesRootHierarchy()
        val filesRoot = paths.filesDirectory.toPath().toAbsolutePath().normalize()
        val instances = paths.instancesDirectory.toPath().toAbsolutePath().normalize()
        if (!directory(instances) || !directory(instanceRoot)) {
            throw IOException("VM instance hierarchy is not made of real directories")
        }
        val expected = filesRoot.toRealPath().resolve(VmPaths.INSTANCES_DIRECTORY)
            .resolve(paths.vmId.serialized).normalize()
        if (instanceRoot.toRealPath() != expected) throw IOException("VM instance escaped filesDir")
    }

    private fun requireVm(vmId: VmId) {
        require(vmId == paths.vmId && vmId == VmId.DEFAULT) { "Only the default VM is supported" }
    }

    private fun attrs(path: Path): BasicFileAttributes = Files.readAttributes(
        path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS,
    )
    private fun exists(path: Path): Boolean = Files.exists(path, LinkOption.NOFOLLOW_LINKS)
    private fun regular(path: Path): Boolean {
        val value = attrs(path)
        return !value.isSymbolicLink && value.isRegularFile
    }
    private fun directory(path: Path): Boolean {
        if (!exists(path)) return false
        val value = attrs(path)
        return !value.isSymbolicLink && value.isDirectory
    }

    companion object {
        private const val MAX_DEPTH = 32
        private const val MAX_ENTRIES = 20_000
    }
}
