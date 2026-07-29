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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
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

/** Bounded backend-neutral observation mirrored across the local service boundary. */
data class VmObservation(
    val vmId: VmId,
    val lifecycle: VmLifecycleState,
    val backendId: String,
    val errorMessage: String? = null,
    val bootStage: String = "",
    val stopping: Boolean = false,
    val runningSinceMs: Long? = null,
)

data class VmRuntimeMetrics(
    val storageAllocatedBytes: Long,
    val emulatorRssMb: Long?,
    val emulatorPid: Int?,
)

data class VmDiagnosticsRequest(val maxChars: Int) {
    init {
        require(maxChars in 1..MAX_CHARS) { "maxChars must be in 1..$MAX_CHARS" }
    }

    companion object { const val MAX_CHARS = 64 * 1024 }
}

data class VmDiagnostics(val text: String, val truncated: Boolean)

/**
 * Default-instance manager boundary. Every call includes and validates [VmId].
 * It exposes DTOs only; backend objects, paths, and arbitrary command strings
 * remain below the manager/service boundary.
 */
interface VmManager {
    fun lifecycle(vmId: VmId): StateFlow<VmLifecycleState>
    fun observation(vmId: VmId): StateFlow<VmObservation>
    /** True only after all backend resources for the active generation are released. */
    fun quiescent(vmId: VmId): StateFlow<Boolean>
    /** Manager-owned launch work or a non-quiescent backend generation is in progress. */
    fun busy(vmId: VmId): StateFlow<Boolean>
    suspend fun list(vmId: VmId): List<VmSummary>
    suspend fun status(vmId: VmId): VmStatus
    suspend fun supervisorState(vmId: VmId): HostSupervisorState
    suspend fun setAutostart(vmId: VmId, enabled: Boolean): HostSupervisorState =
        throw UnsupportedOperationException("autostart mutation unavailable")
    suspend fun beginReconciliation(
        vmId: VmId,
        trigger: ReconciliationTrigger,
    ): ReconciliationAdmission = throw UnsupportedOperationException("reconciliation unavailable")
    /** Stops and proves absence of every fixed backend identity without changing desired state. */
    suspend fun ensureFixedRuntimesStopped(vmId: VmId): Unit =
        throw UnsupportedOperationException("runtime reconciliation unavailable")
    suspend fun finishReconciliation(
        vmId: VmId,
        token: ReconciliationAttemptToken,
        outcome: ReconciliationOutcome,
        errorCode: LifecycleErrorCode? = null,
        runtimeMayBeLive: Boolean = false,
        authoritativeRuntimeAbsence: Boolean = false,
    ): HostSupervisorState = throw UnsupportedOperationException("reconciliation unavailable")
    /** Persists desired state and one PENDING command before service dispatch. */
    suspend fun prepareLifecycleCommand(
        vmId: VmId,
        operation: LifecycleOperation,
        expectedCommandGeneration: Long? = null,
    ): LifecycleTransactionToken
    /** Durably claims id + closed operation before service coordinator effects. */
    suspend fun acceptPrepared(
        vmId: VmId,
        command: LifecycleTransactionToken,
    ): Boolean
    /**
     * Fences a fast service-coordinator admission against newer durable commands.
     * [admission] must not perform backend or persistence work.
     */
    suspend fun authorizeServiceDispatch(
        vmId: VmId,
        command: LifecycleTransactionToken,
        admission: () -> Unit,
    ): Boolean
    /** Executes a command successfully claimed by [acceptPrepared]. */
    suspend fun executeAccepted(
        vmId: VmId,
        command: LifecycleTransactionToken,
    ): Boolean
    /** Explicitly fails a claimed command that cannot safely reach its effect. */
    suspend fun failAccepted(
        vmId: VmId,
        command: LifecycleTransactionToken,
        errorCode: LifecycleErrorCode,
    ): Boolean
    /** Convenience path that claims then executes. */
    suspend fun executePrepared(
        vmId: VmId,
        command: LifecycleTransactionToken,
    ): Boolean
    suspend fun ensureInstalled(vmId: VmId)
    suspend fun start(vmId: VmId)
    suspend fun stop(vmId: VmId)
    suspend fun forceStop(vmId: VmId)
    suspend fun restart(vmId: VmId)
    suspend fun remove(vmId: VmId, policy: VmRemovePolicy)
    suspend fun readConsoleLog(vmId: VmId, request: ConsoleLogRequest): ConsoleLog
    suspend fun executeQmp(vmId: VmId, operation: VmQmpOperation): VmQmpResult
    suspend fun discoverSshEndpoint(vmId: VmId): SshEndpointDiscovery
    suspend fun runtimeMetrics(vmId: VmId): VmRuntimeMetrics
    suspend fun diagnostics(vmId: VmId, request: VmDiagnosticsRequest): VmDiagnostics
}

internal data class VmLaunchPlan(
    val portForwards: List<PortForwardRule>,
    val config: VmConfig,
)

internal interface ManagedVmRuntime {
    val vmId: VmId
    val state: StateFlow<VmState>
    val quiescent: StateFlow<Boolean>
    val bootStage: StateFlow<String>
    val stopping: StateFlow<Boolean>
    val backendId: String
    val runningSinceMs: Long?
    val qmpAvailable: Boolean
    fun emulatorRssMb(): Long?
    fun emulatorPid(): Int?
    fun diagnosticsReport(): String
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
    fun storageAllocatedBytes(vmId: VmId): Long
    fun redactPrivatePaths(text: String): String
}

/** Production implementation; dependencies are narrow so manager policy is JVM-testable. */
class DefaultVmManager internal constructor(
    private val runtime: ManagedVmRuntime,
    private val installer: VmInstaller,
    private val configuration: VmConfigurationSource,
    private val files: VmFiles,
    private val supervisor: HostSupervisorTransactions,
    private val scope: CoroutineScope,
    private val startAcceptanceTimeoutMs: Long = 5_000L,
    private val guestShutdownTimeoutMs: Long = 5_000L,
    private val backendStopTimeoutMs: Long = 15_000L,
    private val forceStopTimeoutMs: Long = 7_000L,
    private val qmpTimeoutMs: Long = 5_000L,
    private val runtimePreflight: RuntimePreflightCoordinator,
    /** Deterministic test seams around command admission and backend task dispatch. */
    private val beforeFinalLaunchAuthorization: suspend (LifecycleTransactionToken) -> Unit = {},
    private val beforeFinalStopAuthorization: suspend (LifecycleTransactionToken) -> Unit = {},
    private val beforeCoordinatedStop: suspend () -> Unit = {},
) : VmManager {
    private val lifecycleMutex = Mutex()
    private val stopTaskMutex = Mutex()
    private val launchCompletionMutex = Mutex()
    /** Serializes durable command authority with just-in-time irreversible effects. */
    private val commandAuthorityMutex = Mutex()
    private val commandClaimMutex = Mutex()
    private val claimedCommands = mutableMapOf<LifecycleTransactionToken, CommandClaimState>()
    @Volatile private var stopTask: Deferred<Unit>? = null
    @Volatile private var stopForceSignal: CompletableDeferred<Unit>? = null
    @Volatile private var startTask: Deferred<Unit>? = null
    private var installationEnsured = false
    private val launchPending = MutableStateFlow(false)

    private val lifecycleFlow: StateFlow<VmLifecycleState> = runtime.state
        .combine(launchPending) { state, pending -> effectiveLifecycle(state, pending) }
        .stateIn(scope, SharingStarted.Eagerly, effectiveLifecycle(runtime.state.value, false))
    private val observationFlow: StateFlow<VmObservation> = combine(
        runtime.state,
        runtime.bootStage,
        runtime.stopping,
        launchPending,
    ) { state, stage, stopping, pending ->
        VmObservation(
            vmId = VmId.DEFAULT,
            lifecycle = effectiveLifecycle(state, pending),
            backendId = runtime.backendId.take(MAX_BACKEND_ID_CHARS),
            errorMessage = (state as? VmState.Error)?.message
                ?.let(files::redactPrivatePaths)
                ?.take(MAX_OBSERVATION_TEXT_CHARS),
            bootStage = stage.take(MAX_OBSERVATION_TEXT_CHARS),
            stopping = stopping,
            runningSinceMs = runtime.runningSinceMs,
        )
    }.stateIn(
        scope,
        SharingStarted.Eagerly,
        VmObservation(
            vmId = VmId.DEFAULT,
            lifecycle = effectiveLifecycle(runtime.state.value, false),
            backendId = runtime.backendId.take(MAX_BACKEND_ID_CHARS),
            errorMessage = (runtime.state.value as? VmState.Error)?.message
                ?.let(files::redactPrivatePaths)
                ?.take(MAX_OBSERVATION_TEXT_CHARS),
            bootStage = runtime.bootStage.value.take(MAX_OBSERVATION_TEXT_CHARS),
            stopping = runtime.stopping.value,
            runningSinceMs = runtime.runningSinceMs,
        ),
    )
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

    override fun observation(vmId: VmId): StateFlow<VmObservation> {
        requireDefault(vmId)
        return observationFlow
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
            backendId = runtime.backendId.take(MAX_BACKEND_ID_CHARS),
            errorMessage = (state as? VmState.Error)?.message
                ?.let(files::redactPrivatePaths)
                ?.take(MAX_OBSERVATION_TEXT_CHARS),
        )
    }

    override suspend fun supervisorState(vmId: VmId): HostSupervisorState {
        requireDefault(vmId)
        return supervisor.snapshot()
    }

    override suspend fun setAutostart(vmId: VmId, enabled: Boolean): HostSupervisorState {
        requireDefault(vmId)
        return commandAuthorityMutex.withLock { supervisor.setAutostart(enabled) }
    }

    override suspend fun beginReconciliation(
        vmId: VmId,
        trigger: ReconciliationTrigger,
    ): ReconciliationAdmission {
        requireDefault(vmId)
        return commandAuthorityMutex.withLock { supervisor.begin(trigger) }
    }

    override suspend fun ensureFixedRuntimesStopped(vmId: VmId) {
        requireDefault(vmId)
        awaitInitial(vmId)
        lifecycleMutex.withLock {
            check(runtime.quiescent.value) {
                "In-process runtime must be quiescent before fixed-runtime cleanup"
            }
            runtimePreflight.ensureAllFixedRuntimesStopped()
        }
    }

    override suspend fun finishReconciliation(
        vmId: VmId,
        token: ReconciliationAttemptToken,
        outcome: ReconciliationOutcome,
        errorCode: LifecycleErrorCode?,
        runtimeMayBeLive: Boolean,
        authoritativeRuntimeAbsence: Boolean,
    ): HostSupervisorState {
        requireDefault(vmId)
        return commandAuthorityMutex.withLock {
            supervisor.finish(
                token, outcome, errorCode, runtimeMayBeLive, authoritativeRuntimeAbsence,
            )
        }
    }

    override suspend fun prepareLifecycleCommand(
        vmId: VmId,
        operation: LifecycleOperation,
        expectedCommandGeneration: Long?,
    ): LifecycleTransactionToken {
        requireDefault(vmId)
        require(operation != LifecycleOperation.REMOVE) {
            "Prepared service commands cannot carry a remove policy"
        }
        return prepareCommand(operation, expectedCommandGeneration).also { prepared ->
            commandClaimMutex.withLock {
                claimedCommands.entries.removeAll { (token, state) ->
                    token.id < prepared.id && state == CommandClaimState.READY
                }
            }
        }
    }

    override suspend fun acceptPrepared(
        vmId: VmId,
        command: LifecycleTransactionToken,
    ): Boolean = commandClaimMutex.withLock {
        requireDefault(vmId)
        if (claimedCommands.keys.any { it.id == command.id } ||
            claimedCommands.size >= MAX_CLAIMED_COMMANDS
        ) {
            return@withLock false
        }
        if (!supervisor.claim(command)) return@withLock false
        claimedCommands[command] = CommandClaimState.READY
        true
    }

    override suspend fun authorizeServiceDispatch(
        vmId: VmId,
        command: LifecycleTransactionToken,
        admission: () -> Unit,
    ): Boolean = commandAuthorityMutex.withLock {
        requireDefault(vmId)
        commandClaimMutex.withLock claim@{
            if (claimedCommands[command] != CommandClaimState.READY) return@claim false
            if (!supervisor.isCurrent(command)) return@claim false
            admission()
            true
        }
    }

    override suspend fun executeAccepted(
        vmId: VmId,
        command: LifecycleTransactionToken,
    ): Boolean {
        requireDefault(vmId)
        require(command.operation != LifecycleOperation.REMOVE) {
            "Prepared service commands cannot execute removal without its policy"
        }
        val mayExecute = commandClaimMutex.withLock {
            if (claimedCommands[command] != CommandClaimState.READY) return@withLock false
            claimedCommands[command] = CommandClaimState.EXECUTING
            true
        }
        if (!mayExecute) return false
        return try {
            if (!supervisor.isCurrent(command)) return false
            val executeTransaction: suspend () -> Boolean = {
                preparedTransaction(command) {
                    when (command.operation) {
                        LifecycleOperation.SETUP -> {
                            ensureInstalledEffect(vmId, command)
                            false
                        }
                        LifecycleOperation.START, LifecycleOperation.RECOVER -> startEffect(vmId, command)
                        LifecycleOperation.STOP -> {
                            stopEffect(vmId, command, force = false)
                            false
                        }
                        LifecycleOperation.FORCE_STOP -> {
                            stopEffect(vmId, command, force = true)
                            false
                        }
                        LifecycleOperation.RESTART -> restartEffect(vmId, command)
                        LifecycleOperation.REMOVE -> error("validated above")
                    }
                }
            }
            if (command.operation == LifecycleOperation.START ||
                command.operation == LifecycleOperation.RECOVER ||
                command.operation == LifecycleOperation.RESTART
            ) {
                // Serialize launch acceptance together with its generation
                // commit, so superseded launches cannot complete out of order.
                launchCompletionMutex.withLock { executeTransaction() }
            } else {
                executeTransaction()
            }
        } finally {
            val completionUncertain = runCatching { supervisor.isCurrent(command) }
                .getOrDefault(true)
            commandClaimMutex.withLock {
                if (completionUncertain) {
                    claimedCommands[command] = CommandClaimState.COMPLETION_UNCERTAIN
                } else {
                    claimedCommands.remove(command)
                }
            }
        }
    }

    override suspend fun failAccepted(
        vmId: VmId,
        command: LifecycleTransactionToken,
        errorCode: LifecycleErrorCode,
    ): Boolean {
        requireDefault(vmId)
        val mayFail = commandClaimMutex.withLock {
            if (claimedCommands[command] != CommandClaimState.READY) return@withLock false
            claimedCommands[command] = CommandClaimState.EXECUTING
            true
        }
        if (!mayFail) return false
        return try {
            withContext(NonCancellable) { supervisor.fail(command, errorCode) }
        } finally {
            val completionUncertain = runCatching { supervisor.isCurrent(command) }
                .getOrDefault(true)
            commandClaimMutex.withLock {
                if (completionUncertain) {
                    claimedCommands[command] = CommandClaimState.COMPLETION_UNCERTAIN
                } else {
                    claimedCommands.remove(command)
                }
            }
        }
    }

    override suspend fun executePrepared(
        vmId: VmId,
        command: LifecycleTransactionToken,
    ): Boolean {
        if (!acceptPrepared(vmId, command)) return false
        return executeAccepted(vmId, command)
    }

    override suspend fun ensureInstalled(vmId: VmId) = prepareAndExecute(
        vmId,
        LifecycleOperation.SETUP,
    )

    override suspend fun start(vmId: VmId) = prepareAndExecute(vmId, LifecycleOperation.START)

    override suspend fun stop(vmId: VmId) = prepareAndExecute(vmId, LifecycleOperation.STOP)

    override suspend fun forceStop(vmId: VmId) =
        prepareAndExecute(vmId, LifecycleOperation.FORCE_STOP)

    override suspend fun restart(vmId: VmId) = prepareAndExecute(vmId, LifecycleOperation.RESTART)

    override suspend fun remove(vmId: VmId, policy: VmRemovePolicy) = lifecycleTransaction(
        vmId = vmId,
        operation = LifecycleOperation.REMOVE,
    ) { command ->
        awaitInitial(vmId)
        lifecycleMutex.withLock {
            check(runtime.quiescent.value) { "Cannot remove VM while backend cleanup is incomplete" }
            installer.withExclusiveTree(vmId) {
                withCurrentCommand(command) {
                    // Removal may destroy the authenticated control endpoints,
                    // so prove both fixed backend identities absent first while
                    // the lifecycle, asset tree, and durable command are fenced.
                    runtimePreflight.ensureAllFixedRuntimesStopped()
                    files.remove(vmId, policy)
                    installationEnsured = false
                }
            }
        }
        false
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

    override suspend fun runtimeMetrics(vmId: VmId): VmRuntimeMetrics = withTree(vmId) {
        VmRuntimeMetrics(
            storageAllocatedBytes = files.storageAllocatedBytes(vmId),
            emulatorRssMb = runtime.emulatorRssMb(),
            emulatorPid = runtime.emulatorPid(),
        )
    }

    override suspend fun diagnostics(vmId: VmId, request: VmDiagnosticsRequest): VmDiagnostics {
        awaitInitial(vmId)
        val raw = files.redactPrivatePaths(runtime.diagnosticsReport())
        return VmDiagnostics(raw.takeLast(request.maxChars), raw.length > request.maxChars)
    }

    private suspend fun prepareAndExecute(vmId: VmId, operation: LifecycleOperation) {
        val command = prepareLifecycleCommand(vmId, operation)
        executePrepared(vmId, command)
    }

    private suspend fun lifecycleTransaction(
        vmId: VmId,
        operation: LifecycleOperation,
        effect: suspend (LifecycleTransactionToken) -> Boolean,
    ) {
        requireDefault(vmId)
        val command = prepareCommand(operation)
        if (!supervisor.claim(command)) return
        preparedTransaction(command) { effect(command) }
    }

    /** Every durable authority change shares the gate used by final effect fences. */
    private suspend fun prepareCommand(
        operation: LifecycleOperation,
        expectedId: Long? = null,
    ): LifecycleTransactionToken = commandAuthorityMutex.withLock {
        supervisor.prepare(operation, expectedId)
    }

    /**
     * Completes only the same still-current PENDING command. Completion writes
     * are non-cancellable; a failed write deliberately leaves crash evidence.
     */
    private suspend fun preparedTransaction(
        command: LifecycleTransactionToken,
        effect: suspend () -> Boolean,
    ): Boolean {
        val runtimeStarted = try {
            effect()
        } catch (failure: Throwable) {
            val errorCode = classifyLifecycleFailure(failure)
            withContext(NonCancellable) {
                runCatching { supervisor.fail(command, errorCode) }
                    .onFailure(failure::addSuppressed)
            }
            throw failure
        }
        return withContext(NonCancellable) { supervisor.succeed(command, runtimeStarted) }
    }

    private suspend fun ensureInstalledEffect(
        vmId: VmId,
        command: LifecycleTransactionToken,
    ) {
        awaitInitial(vmId)
        lifecycleMutex.withLock {
            check(runtime.quiescent.value) { "Cannot install while VM cleanup is incomplete" }
            installer.withExclusiveTree(vmId) { lease ->
                withCurrentCommand(command) { ensureInstalledLocked(vmId, lease) }
            }
        }
    }

    /** Returns true only when this command accepted a new backend generation. */
    private suspend fun startEffect(
        vmId: VmId,
        command: LifecycleTransactionToken,
    ): Boolean {
        awaitInitial(vmId)
        // An older STOP may already own cleanup even while the runtime still
        // reports Running. Join it before deciding that this newer START is a
        // duplicate; the final launch fence below still rejects a newer STOP.
        awaitStoppingRuntimeBeforeStart()
        return lifecycleMutex.withLock {
            if (isRuntimeActive()) return@withLock false
            check(!busyFlow.value) { "Cannot start while previous VM work or cleanup is incomplete" }
            // Probe both fixed backend identities under the manager's one-VM
            // lifecycle authority before touching launch files or accepting a
            // new generation.
            runtimePreflight.prepareForLaunch()
            installer.withExclusiveTree(vmId) { lease ->
                if (!withCurrentCommand(command) { ensureInstalledLocked(vmId, lease) }) {
                    return@withExclusiveTree false
                }
                val plan = launchPlan(vmId)
                // Keep prepare excluded only until this generation is accepted,
                // never for configuration assembly or the backend's lifetime.
                beforeFinalLaunchAuthorization(command)
                withCurrentCommand(command) { startLocked(plan) }
            }
        }
    }

    private suspend fun stopEffect(
        vmId: VmId,
        command: LifecycleTransactionToken,
        force: Boolean,
    ): Boolean {
        awaitInitial(vmId)
        beforeFinalStopAuthorization(command)
        val admittedTask = commandAuthorityMutex.withLock {
            if (!supervisor.isCurrent(command)) return@withLock null
            // Establish or join the backend stop while command preparation is
            // excluded. Quiescence is deliberately awaited after releasing
            // authority so a newer START can durably supersede this command.
            requestStop(force)
        } ?: return false
        awaitStopTask(admittedTask)
        return true
    }

    /** One durable RESTART remains pending across the stop-to-start handoff. */
    private suspend fun restartEffect(
        vmId: VmId,
        command: LifecycleTransactionToken,
    ): Boolean {
        awaitInitial(vmId)
        // Duplicate direct convenience calls during an already accepted
        // replacement do not create another replacement generation.
        if (runtime.state.value is VmState.Starting && !runtime.quiescent.value) return false
        if (!stopEffect(vmId, command, force = false)) return false
        lifecycleMutex.withLock {
            check(runtime.quiescent.value) { "Cannot restart while VM cleanup is incomplete" }
            runtimePreflight.prepareForLaunch()
            return installer.withExclusiveTree(vmId) { lease ->
                // Shutdown is deliberately outside the authority gate. Fence
                // installation and launch separately so a newer STOP can become
                // authoritative between them and prevent replacement launch.
                if (!withCurrentCommand(command) { ensureInstalledLocked(vmId, lease) }) {
                    return@withExclusiveTree false
                }
                val plan = launchPlan(vmId)
                beforeFinalLaunchAuthorization(command)
                withCurrentCommand(command) { startLocked(plan) }
            }
        }
    }

    private suspend fun awaitStoppingRuntimeBeforeStart() {
        stopTask?.takeIf { it.isActive }?.let { awaitStopTask(it) }
        if (runtime.stopping.value && !runtime.quiescent.value) {
            val stopped = withTimeoutOrNull(stopCompletionTimeoutMs()) {
                while (!runtime.quiescent.value) delay(STOP_POLL_MS)
                true
            } == true
            if (!stopped) throw IOException("VM stop did not complete within the bounded deadline")
        }
    }

    private suspend fun awaitStopTask(task: Deferred<Unit>) {
        val stopped = withTimeoutOrNull(stopCompletionTimeoutMs()) {
            task.await()
            true
        } == true
        if (!stopped) throw IOException("VM stop did not complete within the bounded deadline")
    }

    private fun stopCompletionTimeoutMs(): Long = listOf(
        startAcceptanceTimeoutMs,
        qmpTimeoutMs,
        guestShutdownTimeoutMs,
        backendStopTimeoutMs,
        forceStopTimeoutMs,
    ).fold(0L) { total, timeout ->
        if (Long.MAX_VALUE - total < timeout) Long.MAX_VALUE else total + timeout
    }

    /**
     * Atomically revalidates the durable token against command preparation and
     * keeps newer authority out only until this bounded effect is accepted.
     */
    private suspend fun withCurrentCommand(
        command: LifecycleTransactionToken,
        effect: suspend () -> Unit,
    ): Boolean = commandAuthorityMutex.withLock {
        if (!supervisor.isCurrent(command)) return@withLock false
        effect()
        true
    }

    private fun classifyLifecycleFailure(failure: Throwable): LifecycleErrorCode = when (failure) {
        is RuntimeProbeException -> failure.stableCode
        is TimeoutCancellationException -> LifecycleErrorCode.TIMEOUT
        is CancellationException -> LifecycleErrorCode.CANCELLED
        is IOException -> LifecycleErrorCode.IO
        is IllegalStateException -> LifecycleErrorCode.INVALID_STATE
        is IllegalArgumentException -> LifecycleErrorCode.INVALID_ARGUMENT
        is SecurityException -> LifecycleErrorCode.SECURITY
        else -> LifecycleErrorCode.UNKNOWN
    }

    private suspend fun ensureInstalledLocked(vmId: VmId, lease: VmAssetTreeLease) {
        if (installationEnsured && files.isInstalled(vmId)) return
        lease.install(vmId)
        check(files.isInstalled(vmId)) { "VM installer completed without a valid installation" }
        installationEnsured = true
    }

    private suspend fun launchPlan(vmId: VmId): VmLaunchPlan =
        configuration.launchPlan(vmId).also { plan ->
            require(plan.config.vmId == vmId) { "Launch plan VM id mismatch" }
        }

    private suspend fun startLocked(plan: VmLaunchPlan) {
        launchPending.value = true
        try {
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
        val task = scope.async(start = CoroutineStart.LAZY) {
            beforeCoordinatedStop()
            lifecycleMutex.withLock {
                coordinatedStopLocked(forceSignal)
                check(runtime.quiescent.value) { "In-process runtime is not quiescent after stop" }
                // Explicit STOP/FORCE_STOP owns all fixed runtime identities, not
                // only the backend object reconstructed in this process.
                runtimePreflight.ensureAllFixedRuntimesStopped()
                Unit
            }
        }
        // Publish ownership before dispatch so a superseding START cannot miss
        // a task that has begun but has not yet reached its backend signal.
        stopTask = task
        task.start()
        task
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
    private enum class CommandClaimState { READY, EXECUTING, COMPLETION_UNCERTAIN }

    companion object {
        const val SSH_HOST_PORT = 9922
        const val SSH_HOST = "127.0.0.1"
        private const val STOP_POLL_MS = 10L
        private const val MAX_OBSERVATION_TEXT_CHARS = 512
        private const val MAX_BACKEND_ID_CHARS = 32
        private const val MAX_CLAIMED_COMMANDS = 16

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
    override val bootStage: StateFlow<String> get() = engine.bootStage
    override val stopping: StateFlow<Boolean> get() = engine.stopping
    override val backendId: String get() = engine.backendId
    override val runningSinceMs: Long? get() = engine.runningSinceMs
    override val qmpAvailable: Boolean get() = engine.qmpController != null
    override fun emulatorRssMb(): Long? = engine.emulatorRssMb()
    override fun emulatorPid(): Int? = engine.emulatorPid()
    override fun diagnosticsReport(): String = engine.diagnosticsReport()

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

    override fun redactPrivatePaths(text: String): String = text
        .replace(paths.instanceDirectory.absolutePath, "[default VM]")
        .replace(paths.filesDirectory.absolutePath, "[app files]")

    override fun storageAllocatedBytes(vmId: VmId): Long {
        requireVm(vmId)
        if (!instanceExistsSafely()) return 0L
        val storage = paths.storageImage.toPath().toAbsolutePath().normalize()
        if (!exists(storage)) return 0L
        if (!regular(storage)) throw IOException("Persistent storage is not a regular no-follow file")
        return try {
            android.system.Os.stat(storage.toString()).st_blocks * 512L
        } catch (_: Exception) {
            Files.size(storage)
        }
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
