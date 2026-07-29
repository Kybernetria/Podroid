/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.vm

import com.excp.podroid.data.repository.PortForwardRule
import com.excp.podroid.engine.QmpClient
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    val backendId: String
    val qmpAvailable: Boolean
    suspend fun start(plan: VmLaunchPlan)
    fun stop()
    fun forceStop()
    suspend fun systemPowerdown(): Result<Unit>
    suspend fun executeQmp(operation: VmQmpOperation): Result<VmQmpResult>
}

internal interface VmInstaller {
    suspend fun install(vmId: VmId)
    suspend fun awaitIdle(vmId: VmId) = Unit
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
    private val backendStopTimeoutMs: Long = 10_000L,
    private val forceStopTimeoutMs: Long = 2_000L,
    private val qmpTimeoutMs: Long = 5_000L,
) : VmManager {
    private val lifecycleMutex = Mutex()
    @Volatile private var startTask: Deferred<Unit>? = null
    private var installationEnsured = false
    private val launchPending = MutableStateFlow(false)

    private val lifecycleFlow: StateFlow<VmLifecycleState> = runtime.state
        .combine(launchPending) { state, pending -> effectiveLifecycle(state, pending) }
        .stateIn(scope, SharingStarted.Eagerly, effectiveLifecycle(runtime.state.value, false))

    init {
        require(runtime.vmId == VmId.DEFAULT) { "Only the default VM runtime is supported" }
        require(startAcceptanceTimeoutMs > 0 && guestShutdownTimeoutMs > 0)
        require(backendStopTimeoutMs > 0 && forceStopTimeoutMs > 0 && qmpTimeoutMs > 0)
    }

    override fun lifecycle(vmId: VmId): StateFlow<VmLifecycleState> {
        requireDefault(vmId)
        return lifecycleFlow
    }

    override suspend fun list(vmId: VmId): List<VmSummary> {
        requireDefault(vmId)
        return listOf(VmSummary(vmId, files.isInstalled(vmId), effectiveLifecycle(runtime.state.value, launchPending.value)))
    }

    override suspend fun status(vmId: VmId): VmStatus {
        requireDefault(vmId)
        val state = runtime.state.value
        return VmStatus(
            vmId = vmId,
            installed = files.isInstalled(vmId),
            lifecycle = effectiveLifecycle(state, launchPending.value),
            backendId = runtime.backendId,
            errorMessage = (state as? VmState.Error)?.message,
        )
    }

    override suspend fun ensureInstalled(vmId: VmId) = lifecycleMutex.withLock {
        requireDefault(vmId)
        check(!isActive()) { "Cannot install while VM '${vmId.serialized}' is active" }
        ensureInstalledLocked(vmId)
    }

    override suspend fun start(vmId: VmId) = lifecycleMutex.withLock {
        requireDefault(vmId)
        if (isActive()) return@withLock
        ensureInstalledLocked(vmId)
        startLocked(vmId)
    }

    override suspend fun stop(vmId: VmId) = lifecycleMutex.withLock {
        requireDefault(vmId)
        if (!isActive()) return@withLock
        gracefulStopLocked()
    }

    override suspend fun forceStop(vmId: VmId) = lifecycleMutex.withLock {
        requireDefault(vmId)
        if (!isActive()) return@withLock
        runtime.forceStop()
        check(awaitTerminal(forceStopTimeoutMs)) { "VM did not reach a terminal state after force stop" }
    }

    override suspend fun restart(vmId: VmId) = lifecycleMutex.withLock {
        requireDefault(vmId)
        // A duplicate restart delivered while the replacement is still starting
        // is already satisfied by that in-flight replacement.
        if (runtime.state.value is VmState.Starting) return@withLock
        if (isActive()) gracefulStopLocked()
        ensureInstalledLocked(vmId)
        startLocked(vmId)
    }

    override suspend fun remove(vmId: VmId, policy: VmRemovePolicy) = lifecycleMutex.withLock {
        requireDefault(vmId)
        check(!isActive()) { "Cannot remove VM '${vmId.serialized}' while active" }
        installer.awaitIdle(vmId)
        files.remove(vmId, policy)
        installationEnsured = false
    }

    override suspend fun readConsoleLog(vmId: VmId, request: ConsoleLogRequest): ConsoleLog {
        requireDefault(vmId)
        return files.readConsole(vmId, request)
    }

    override suspend fun executeQmp(vmId: VmId, operation: VmQmpOperation): VmQmpResult {
        requireDefault(vmId)
        check(runtime.state.value is VmState.Running) { "QMP requires a running VM" }
        check(runtime.qmpAvailable) { "QMP is unavailable for backend '${runtime.backendId}'" }
        return withTimeoutOrNull(qmpTimeoutMs) { runtime.executeQmp(operation).getOrThrow() }
            ?: throw IOException("QMP operation timed out")
    }

    override suspend fun discoverSshEndpoint(vmId: VmId): SshEndpointDiscovery {
        requireDefault(vmId)
        val enabled = configuration.sshEnabled(vmId)
        val reachable = enabled && runtime.state.value is VmState.Running
        return SshEndpointDiscovery(
            enabled = enabled,
            reachable = reachable,
            endpoint = if (reachable) SshEndpoint(SSH_HOST, SSH_HOST_PORT) else null,
        )
    }

    private suspend fun ensureInstalledLocked(vmId: VmId) {
        if (installationEnsured && files.isInstalled(vmId)) return
        // Always cross the installer seam once per manager lifetime so an app
        // upgrade cannot launch old assets while initial extraction is in flight.
        installer.install(vmId)
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
                task.cancel()
                runtime.forceStop()
                throw IOException("VM start was not accepted within ${startAcceptanceTimeoutMs}ms")
            }
            if (task.isCompleted) task.await()
            val error = runtime.state.value as? VmState.Error
            if (error != null) {
                // A backend that reports Error must not leave its launch task
                // unbounded. Give normal cleanup the acceptance budget, then
                // cancel the manager-owned task before returning the failure.
                if (!task.isCompleted) {
                    withTimeoutOrNull(startAcceptanceTimeoutMs) { task.join() }
                    if (!task.isCompleted) task.cancel()
                }
                throw IOException("VM start failed: ${error.message}")
            }
            check(isRuntimeActive()) { "VM backend returned before reaching an active state" }
        } finally {
            launchPending.value = false
        }
    }

    private suspend fun gracefulStopLocked() {
        val qmpRequested = if (runtime.state.value is VmState.Running && runtime.qmpAvailable) {
            withTimeoutOrNull(qmpTimeoutMs) { runtime.systemPowerdown().isSuccess } == true
        } else {
            false
        }
        if (qmpRequested && awaitTerminal(guestShutdownTimeoutMs)) return

        // Preserve the inherited backend stop path: QEMU sends SIGTERM and has
        // its own bounded escalation; AVF performs its bounded guest sync first.
        runtime.stop()
        if (awaitTerminal(backendStopTimeoutMs)) return

        // QEMU is a real immediate hard kill. AVF intentionally maps this back to
        // its safe framework stop because no distinct hard-kill API is available.
        runtime.forceStop()
        check(awaitTerminal(forceStopTimeoutMs)) { "VM did not stop within the bounded escalation window" }
    }

    private suspend fun awaitTerminal(timeoutMs: Long): Boolean {
        if (!isRuntimeActive()) return true
        return withTimeoutOrNull(timeoutMs) {
            while (isRuntimeActive()) delay(10L)
            true
        } == true
    }

    private fun isActive(): Boolean = launchPending.value || isRuntimeActive() || startTask?.isActive == true

    private fun isRuntimeActive(): Boolean = when (runtime.state.value) {
        is VmState.Starting, is VmState.Running -> true
        else -> false
    }

    private fun requireDefault(vmId: VmId) {
        require(vmId == VmId.DEFAULT) { "Only the default VM is supported" }
    }

    companion object {
        const val SSH_HOST_PORT = 9922
        const val SSH_HOST = "127.0.0.1"

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
    override val backendId: String get() = engine.backendId
    override val qmpAvailable: Boolean get() = engine.qmpClient != null

    override suspend fun start(plan: VmLaunchPlan) = engine.start(plan.portForwards, plan.config)
    override fun stop() = engine.stop()
    override fun forceStop() = engine.forceStop()
    override suspend fun systemPowerdown(): Result<Unit> =
        engine.qmpClient?.systemPowerdown() ?: Result.failure(UnsupportedOperationException("QMP unavailable"))

    override suspend fun executeQmp(operation: VmQmpOperation): Result<VmQmpResult> {
        val qmp: QmpClient = engine.qmpClient
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

/** Bounded NOFOLLOW installation/removal/log access over the authoritative VmPaths. */
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
        val storage = paths.storageImage.toPath().toAbsolutePath().normalize()
        if (exists(storage) && !regular(storage)) {
            throw IOException("Persistent storage is not a regular no-follow file")
        }
        val entries = scanForRemoval()
        for (entry in entries) {
            if (policy == VmRemovePolicy.PRESERVE_DATA && entry == storage) continue
            Files.delete(entry)
        }
        if (policy == VmRemovePolicy.DELETE_DATA || !exists(storage)) Files.delete(instanceRoot)
        VmPathSecurity.forceDirectory(paths.instancesDirectory.toPath())
    }

    override fun readConsole(vmId: VmId, request: ConsoleLogRequest): ConsoleLog {
        requireVm(vmId)
        if (!instanceExistsSafely()) return ConsoleLog("", 0, 0, false)
        val log = paths.consoleLog.toPath().toAbsolutePath().normalize()
        if (!exists(log)) return ConsoleLog("", 0, 0, false)
        if (!regular(log)) throw IOException("Console log is not a regular no-follow file")

        val length = Files.size(log)
        val bytesToRead = minOf(length, request.maxBytes.toLong()).toInt()
        val bytes = ByteArray(bytesToRead)
        FileChannel.open(log, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            channel.position(length - bytesToRead)
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) throw IOException("Console log changed during bounded read")
            }
        }
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

    private fun scanForRemoval(): List<Path> {
        var count = 0
        val result = ArrayList<Path>()
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
                    result.add(entry)
                }
            }
        }
        return result.sortedByDescending { it.nameCount }
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
