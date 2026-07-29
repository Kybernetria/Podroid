/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.service

import com.excp.podroid.vm.ConsoleLog
import com.excp.podroid.vm.ConsoleLogRequest
import com.excp.podroid.vm.HostSupervisorState
import com.excp.podroid.vm.MonotonicDeadline
import com.excp.podroid.vm.SshEndpointDiscovery
import com.excp.podroid.vm.VmDiagnostics
import com.excp.podroid.vm.VmDiagnosticsRequest
import com.excp.podroid.vm.VmId
import com.excp.podroid.vm.VmManager
import com.excp.podroid.vm.VmObservation
import com.excp.podroid.vm.VmQmpOperation
import com.excp.podroid.vm.VmQmpResult
import com.excp.podroid.vm.VmRemovePolicy
import com.excp.podroid.vm.VmRuntimeMetrics
import com.excp.podroid.vm.VmStatus
import com.excp.podroid.vm.VmSummary
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.nanoseconds

/** DTO returned by the service-owned AVF capability probe. */
sealed interface VmUiState {
    data object Idle : VmUiState
    data object Starting : VmUiState
    data object Running : VmUiState
    data object Stopped : VmUiState
    data class Error(val message: String) : VmUiState
}

fun VmObservation.toUiState(): VmUiState = when (lifecycle) {
    com.excp.podroid.vm.VmLifecycleState.IDLE -> VmUiState.Idle
    com.excp.podroid.vm.VmLifecycleState.STARTING -> VmUiState.Starting
    com.excp.podroid.vm.VmLifecycleState.RUNNING -> VmUiState.Running
    com.excp.podroid.vm.VmLifecycleState.STOPPED -> VmUiState.Stopped
    com.excp.podroid.vm.VmLifecycleState.ERROR -> VmUiState.Error(errorMessage ?: "VM error")
}

data class VmBackendProbe(
    val featureSupported: Boolean,
    val managePermissionGranted: Boolean,
    val customPermissionGranted: Boolean,
    val virtApexPresent: Boolean,
    val managerClassPresent: Boolean,
    val serviceReachable: Boolean,
    val customVmConfigSupported: Boolean,
    val capabilitiesRaw: Int,
    val capabilitiesDecoded: String,
    val activeBackend: String,
    val smokeTestResult: String? = null,
) {
    fun pretty(): String = buildString {
        appendLine("Active backend\n  $activeBackend\n")
        appendLine("Feature: virtualization_framework\n  supported = $featureSupported\n")
        appendLine("Permission: MANAGE_VIRTUAL_MACHINE\n  granted = $managePermissionGranted\n")
        appendLine("Permission: USE_CUSTOM_VIRTUAL_MACHINE\n  granted = $customPermissionGranted\n")
        appendLine("APEX /apex/com.android.virt\n  present = $virtApexPresent\n")
        appendLine("API VirtualMachineManager\n  class loadable = $managerClassPresent\n")
        appendLine("Service\n  reachable via system service = $serviceReachable\n")
        appendLine("Custom-VM API\n  builder present = $customVmConfigSupported\n")
        appendLine("Hypervisor capabilities\n  raw = $capabilitiesRaw ($capabilitiesDecoded)")
        smokeTestResult?.let { appendLine("\nSmoke test\n${it.prependIndent("  ")}") }
    }
}

/**
 * Same-process local-Binder contract. It intentionally contains no Parcelable,
 * filesystem path, engine object, QMP command string, or arbitrary guest command.
 */
interface VmServiceEndpoint {
    val observation: StateFlow<VmObservation>
    val headlessMode: StateFlow<Boolean>

    suspend fun list(): List<VmSummary>
    suspend fun status(): VmStatus
    suspend fun supervisorState(): HostSupervisorState
    suspend fun ensureInstalled()
    suspend fun start()
    suspend fun gracefulStop()
    suspend fun forceStop()
    suspend fun restart()
    suspend fun remove(policy: VmRemovePolicy)
    suspend fun readConsoleLog(request: ConsoleLogRequest): ConsoleLog
    suspend fun executeQmp(operation: VmQmpOperation): VmQmpResult
    suspend fun discoverSshEndpoint(): SshEndpointDiscovery
    suspend fun runtimeMetrics(): VmRuntimeMetrics
    suspend fun diagnostics(request: VmDiagnosticsRequest): VmDiagnostics
    suspend fun backendProbe(): VmBackendProbe
    /** [deadlineNanos] is an absolute process-local [System.nanoTime] deadline. */
    suspend fun runBackendSmokeTest(deadlineNanos: Long): String
    suspend fun setHeadlessMode(active: Boolean)
    fun createTerminalSession(client: TerminalSessionClient): TerminalSession
    fun releaseTerminalClient(client: TerminalSessionClient)
}

internal fun interface CallerUidVerifier {
    fun verify()

    companion object {
        fun sameUid(expectedUid: Int, callerUid: () -> Int): CallerUidVerifier = CallerUidVerifier {
            val actual = callerUid()
            if (actual != expectedUid) throw SecurityException("Rejected Binder caller UID $actual")
        }
    }
}

internal interface VmServiceLifecycleCommands {
    suspend fun startForeground()
    suspend fun stop(force: Boolean)
    suspend fun restart()
}

internal interface VmServiceAuxiliaryCapabilities {
    val headlessMode: StateFlow<Boolean>
    fun backendProbe(): VmBackendProbe
    suspend fun runBackendSmokeTest(deadlineNanos: Long): String
    fun setHeadlessMode(active: Boolean)
    fun createTerminalSession(client: TerminalSessionClient): TerminalSession
    fun releaseTerminalClient(client: TerminalSessionClient)
}

/** A cancellation-aware serialization gate whose bounded wait includes queue admission. */
internal class BoundedCommandGate(
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val mutex = Mutex()

    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock { block() }

    suspend fun <T> runUntil(
        deadlineNanos: Long,
        timeoutResult: T,
        block: suspend () -> T,
    ): T {
        val remainingNanos = MonotonicDeadline.remainingNanos(deadlineNanos, nanoTime)
        if (remainingNanos == 0L) return timeoutResult
        return withTimeoutOrNull(remainingNanos.nanoseconds) {
            mutex.withLock {
                if (MonotonicDeadline.remainingNanos(deadlineNanos, nanoTime) == 0L) {
                    timeoutResult
                } else {
                    block()
                }
            }
        } ?: timeoutResult
    }
}

internal const val DEFAULT_BACKEND_SMOKE_TOTAL_DEADLINE_MS = 15_000L

internal fun backendSmokeDeadlineResult(timeoutMs: Long): String =
    "FAILED: backend smoke readiness exceeded total ${timeoutMs}ms Binder deadline"

/** Policy implementation behind the Binder; mutation dispatch is serialized. */
internal class LocalVmServiceEndpoint(
    private val manager: VmManager,
    private val lifecycleCommands: VmServiceLifecycleCommands,
    private val auxiliary: VmServiceAuxiliaryCapabilities,
    private val caller: CallerUidVerifier,
    private val backendSmokeTotalDeadlineMs: Long = DEFAULT_BACKEND_SMOKE_TOTAL_DEADLINE_MS,
    private val nanoTime: () -> Long = System::nanoTime,
) : VmServiceEndpoint {
    private val commands = BoundedCommandGate(nanoTime)

    init {
        require(backendSmokeTotalDeadlineMs > 0) {
            "backendSmokeTotalDeadlineMs must be positive"
        }
    }

    override val observation: StateFlow<VmObservation>
        get() {
            caller.verify()
            return manager.observation(VmId.DEFAULT)
        }
    override val headlessMode: StateFlow<Boolean>
        get() {
            caller.verify()
            return auxiliary.headlessMode
        }

    override suspend fun list(): List<VmSummary> = checked { manager.list(VmId.DEFAULT) }
    override suspend fun status(): VmStatus = checked { manager.status(VmId.DEFAULT) }
    override suspend fun supervisorState(): HostSupervisorState =
        checked { manager.supervisorState(VmId.DEFAULT) }
    override suspend fun ensureInstalled() = command { manager.ensureInstalled(VmId.DEFAULT) }
    override suspend fun start() = command { lifecycleCommands.startForeground() }
    override suspend fun gracefulStop() = command { lifecycleCommands.stop(force = false) }
    override suspend fun forceStop() = command { lifecycleCommands.stop(force = true) }
    override suspend fun restart() = command { lifecycleCommands.restart() }
    override suspend fun remove(policy: VmRemovePolicy) = command { manager.remove(VmId.DEFAULT, policy) }
    override suspend fun readConsoleLog(request: ConsoleLogRequest): ConsoleLog =
        checked { manager.readConsoleLog(VmId.DEFAULT, request) }
    override suspend fun executeQmp(operation: VmQmpOperation): VmQmpResult =
        command { manager.executeQmp(VmId.DEFAULT, operation) }
    override suspend fun discoverSshEndpoint(): SshEndpointDiscovery =
        checked { manager.discoverSshEndpoint(VmId.DEFAULT) }
    override suspend fun runtimeMetrics(): VmRuntimeMetrics = checked { manager.runtimeMetrics(VmId.DEFAULT) }
    override suspend fun diagnostics(request: VmDiagnosticsRequest): VmDiagnostics =
        checked { manager.diagnostics(VmId.DEFAULT, request) }
    override suspend fun backendProbe(): VmBackendProbe = checked {
        auxiliary.backendProbe().let {
            it.copy(
                capabilitiesDecoded = it.capabilitiesDecoded.take(MAX_SHORT_TEXT_CHARS),
                activeBackend = it.activeBackend.take(MAX_BACKEND_ID_CHARS),
                smokeTestResult = it.smokeTestResult?.take(MAX_AUX_TEXT_CHARS),
            )
        }
    }
    override suspend fun runBackendSmokeTest(deadlineNanos: Long): String {
        caller.verify()
        val endpointDeadlineNanos = MonotonicDeadline.clamp(
            callerDeadlineNanos = deadlineNanos,
            maximumTimeoutMs = backendSmokeTotalDeadlineMs,
            nanoTime = nanoTime,
        ) ?: return backendSmokeDeadlineResult(backendSmokeTotalDeadlineMs)
        return commands.runUntil(
            deadlineNanos = endpointDeadlineNanos,
            timeoutResult = backendSmokeDeadlineResult(backendSmokeTotalDeadlineMs),
        ) {
            auxiliary.runBackendSmokeTest(endpointDeadlineNanos).take(MAX_AUX_TEXT_CHARS)
        }
    }
    override suspend fun setHeadlessMode(active: Boolean) = command { auxiliary.setHeadlessMode(active) }

    override fun createTerminalSession(client: TerminalSessionClient): TerminalSession {
        caller.verify()
        return auxiliary.createTerminalSession(client)
    }

    override fun releaseTerminalClient(client: TerminalSessionClient) {
        caller.verify()
        auxiliary.releaseTerminalClient(client)
    }

    private suspend fun <T> checked(block: suspend () -> T): T {
        caller.verify()
        return block()
    }

    private suspend fun <T> command(block: suspend () -> T): T {
        caller.verify()
        return commands.run(block)
    }

    companion object {
        private const val MAX_BACKEND_ID_CHARS = 32
        private const val MAX_SHORT_TEXT_CHARS = 256
        private const val MAX_AUX_TEXT_CHARS = 64 * 1024
    }
}

enum class VmBindingState { DISCONNECTED, CONNECTING, CONNECTED }

/**
 * Pure binding state machine used by the Android client and JVM fakes. A lost
 * binding drops only the endpoint capability; the last DTO remains mirrored and
 * no stop command is synthesized.
 */
internal class VmBindingStateMachine(
    private val scope: CoroutineScope,
    initialObservation: VmObservation,
    private val connectionTimeoutMs: Long = 5_000L,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val endpoint = MutableStateFlow<VmServiceEndpoint?>(null)
    private val _bindingState = MutableStateFlow(VmBindingState.DISCONNECTED)
    private val _observation = MutableStateFlow(initialObservation)
    private val _headlessMode = MutableStateFlow(false)
    private val commands = BoundedCommandGate(nanoTime)
    private var mirrorJob: Job? = null
    private var headlessMirrorJob: Job? = null

    val bindingState: StateFlow<VmBindingState> = _bindingState.asStateFlow()
    val observation: StateFlow<VmObservation> = _observation.asStateFlow()
    val headlessMode: StateFlow<Boolean> = _headlessMode.asStateFlow()

    @Synchronized
    fun connecting() {
        if (endpoint.value == null) _bindingState.value = VmBindingState.CONNECTING
    }

    @Synchronized
    fun connected(value: VmServiceEndpoint) {
        mirrorJob?.cancel()
        headlessMirrorJob?.cancel()
        endpoint.value = value
        _bindingState.value = VmBindingState.CONNECTED
        mirrorJob = scope.launch { value.observation.collect { _observation.value = it } }
        headlessMirrorJob = scope.launch { value.headlessMode.collect { _headlessMode.value = it } }
    }

    @Synchronized
    fun disconnected(value: VmServiceEndpoint? = null) {
        if (value != null && endpoint.value !== value) return
        mirrorJob?.cancel()
        headlessMirrorJob?.cancel()
        mirrorJob = null
        headlessMirrorJob = null
        endpoint.value = null
        _bindingState.value = VmBindingState.DISCONNECTED
    }

    fun connectedEndpointOrNull(): VmServiceEndpoint? = endpoint.value

    suspend fun <T> command(block: suspend (VmServiceEndpoint) -> T): T =
        commands.run { withConnectedEndpoint(null, block) }

    suspend fun <T> commandUntil(
        deadlineNanos: Long,
        timeoutResult: T,
        block: suspend (VmServiceEndpoint) -> T,
    ): T = commands.runUntil(deadlineNanos, timeoutResult) {
        withConnectedEndpoint(deadlineNanos, block)
    }

    private suspend fun <T> withConnectedEndpoint(
        deadlineNanos: Long?,
        block: suspend (VmServiceEndpoint) -> T,
    ): T {
        val connectionWaitNanos = deadlineNanos?.let {
            minOf(
                MonotonicDeadline.remainingNanos(it, nanoTime),
                TimeUnit.MILLISECONDS.toNanos(connectionTimeoutMs),
            )
        }
        if (connectionWaitNanos == 0L) throw IOException("VM service binding deadline exceeded")
        val connected = try {
            if (connectionWaitNanos == null) {
                withTimeout(connectionTimeoutMs) { endpoint.filterNotNull().first() }
            } else {
                withTimeout(connectionWaitNanos.nanoseconds) { endpoint.filterNotNull().first() }
            }
        } catch (timeout: TimeoutCancellationException) {
            throw IOException("VM service binding unavailable", timeout)
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
        return block(connected)
    }
}
