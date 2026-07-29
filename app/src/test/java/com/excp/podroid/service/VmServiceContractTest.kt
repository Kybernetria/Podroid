package com.excp.podroid.service

import com.excp.podroid.engine.avf.AvfSmokeAttemptGate
import com.excp.podroid.engine.avf.AvfSmokeTestExecutor
import com.excp.podroid.vm.ConsoleLog
import com.excp.podroid.vm.ConsoleLogRequest
import com.excp.podroid.vm.HostSupervisorState
import com.excp.podroid.vm.LifecycleErrorCode
import com.excp.podroid.vm.LifecycleOperation
import com.excp.podroid.vm.LifecycleTransactionToken
import com.excp.podroid.vm.MonotonicDeadline
import com.excp.podroid.vm.SshEndpointDiscovery
import com.excp.podroid.vm.VmDiagnostics
import com.excp.podroid.vm.VmDiagnosticsRequest
import com.excp.podroid.vm.VmId
import com.excp.podroid.vm.VmLifecycleState
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VmServiceContractTest {
    @Test
    fun `caller UID mismatch is rejected before manager access`() = runBlocking {
        var managerCalls = 0
        val endpoint = endpoint(
            manager = FakeManager(onCall = { managerCalls++ }),
            verifier = CallerUidVerifier.sameUid(1000) { 2000 },
        )

        val failure = runCatching { endpoint.list() }.exceptionOrNull()
        assertTrue(failure is SecurityException)
        assertEquals(0, managerCalls)
    }

    @Test
    fun `start dispatches foreground command and stop intent remains lifecycle owned`() = runBlocking {
        val events = mutableListOf<String>()
        val endpoint = endpoint(lifecycle = object : VmServiceLifecycleCommands {
            override suspend fun startForeground() { events += "foreground-start" }
            override suspend fun stop(force: Boolean) { events += if (force) "force-stop" else "graceful-stop" }
            override suspend fun restart() { events += "foreground-restart" }
        })

        endpoint.start()
        endpoint.gracefulStop()
        endpoint.forceStop()
        endpoint.restart()

        assertEquals(
            listOf("foreground-start", "graceful-stop", "force-stop", "foreground-restart"),
            events,
        )
    }

    @Test
    fun `manager DTO operations delegate with explicit default identity and remove policy`() = runBlocking {
        val manager = FakeManager()
        val endpoint = endpoint(manager = manager)

        endpoint.list()
        endpoint.status()
        endpoint.supervisorState()
        endpoint.ensureInstalled()
        endpoint.remove(VmRemovePolicy.DELETE_DATA)
        endpoint.readConsoleLog(ConsoleLogRequest(32, 2))
        endpoint.executeQmp(VmQmpOperation.QueryStatus)
        endpoint.discoverSshEndpoint()
        endpoint.runtimeMetrics()
        endpoint.diagnostics(VmDiagnosticsRequest(32))

        assertEquals(
            listOf("list", "status", "supervisor", "install", "remove:DELETE_DATA", "log", "qmp", "ssh", "metrics", "diagnostics"),
            manager.calls,
        )
        assertTrue(manager.vmIds.all { it == VmId.DEFAULT })
    }

    @Test
    fun `Binder smoke path bounds forever pending readiness and releases command admission`() = runBlocking {
        val manager = FakeManager()
        val neverReady = object : VmServiceAuxiliaryCapabilities by FakeAuxiliary {
            override suspend fun runBackendSmokeTest(deadlineNanos: Long): String = awaitCancellation()
        }
        val endpoint = endpoint(
            manager = manager,
            auxiliary = neverReady,
            backendSmokeTotalDeadlineMs = 50,
        )
        val startedNanos = System.nanoTime()

        val result = endpoint.runBackendSmokeTest(deadline(50))
        endpoint.status()
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)

        assertTrue(result.contains("exceeded total 50ms Binder deadline"))
        assertEquals(listOf("status"), manager.calls)
        assertTrue("elapsed=${elapsedMs}ms", elapsedMs < 500)
    }

    @Test
    fun `client smoke deadline includes command queue and timed out smoke never runs`() = runBlocking {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + Dispatchers.Default)
        val smokeCalls = AtomicInteger()
        val state = VmBindingStateMachine(scope, observation(VmLifecycleState.IDLE), 500)
        state.connected(FakeEndpoint(observation(VmLifecycleState.IDLE)) {
            smokeCalls.incrementAndGet()
            "smoke ran"
        })
        val precedingEntered = CompletableDeferred<Unit>()
        val releasePreceding = CompletableDeferred<Unit>()
        val preceding = launch(Dispatchers.Default) {
            state.command {
                precedingEntered.complete(Unit)
                releasePreceding.await()
            }
        }
        precedingEntered.await()
        val startedNanos = System.nanoTime()

        val result = try {
            withTimeout(500) {
                val deadlineNanos = deadline(50)
                state.commandUntil(
                    deadlineNanos = deadlineNanos,
                    timeoutResult = backendSmokeDeadlineResult(50),
                ) { it.runBackendSmokeTest(deadlineNanos) }
            }
        } finally {
            releasePreceding.complete(Unit)
            preceding.join()
        }
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)
        state.command { Unit }

        assertTrue(result.contains("exceeded total 50ms Binder deadline"))
        assertTrue("elapsed=${elapsedMs}ms", elapsedMs < 500)
        assertEquals(0, smokeCalls.get())
        scopeJob.cancel()
    }

    @Test
    fun `client remaining smoke budget bounds a longer nested endpoint deadline`() = runBlocking {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + Dispatchers.Default)
        val smokeStarted = CompletableDeferred<Unit>()
        val smokeCancelled = CompletableDeferred<Unit>()
        val smoke = object : VmServiceAuxiliaryCapabilities by FakeAuxiliary {
            override suspend fun runBackendSmokeTest(deadlineNanos: Long): String = try {
                smokeStarted.complete(Unit)
                awaitCancellation()
            } finally {
                smokeCancelled.complete(Unit)
            }
        }
        val state = VmBindingStateMachine(scope, observation(VmLifecycleState.IDLE), 500)
        state.connected(endpoint(auxiliary = smoke, backendSmokeTotalDeadlineMs = 500))
        val startedNanos = System.nanoTime()

        val deadlineNanos = deadline(50)
        val result = state.commandUntil(
            deadlineNanos = deadlineNanos,
            timeoutResult = backendSmokeDeadlineResult(50),
        ) { it.runBackendSmokeTest(deadlineNanos) }
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)
        withTimeout(200) { smokeCancelled.await() }

        assertTrue(smokeStarted.isCompleted)
        assertTrue(result.contains("exceeded total 50ms Binder deadline"))
        assertTrue("elapsed=${elapsedMs}ms", elapsedMs < 500)
        scopeJob.cancel()
    }

    @Test
    fun `endpoint smoke deadline includes command queue and timed out smoke never runs`() = runBlocking {
        val manager = FakeManager()
        val smokeCalls = AtomicInteger()
        val smoke = object : VmServiceAuxiliaryCapabilities by FakeAuxiliary {
            override suspend fun runBackendSmokeTest(deadlineNanos: Long): String {
                smokeCalls.incrementAndGet()
                return "smoke ran"
            }
        }
        val precedingEntered = CompletableDeferred<Unit>()
        val releasePreceding = CompletableDeferred<Unit>()
        val holdingManager = object : VmManager by manager {
            override suspend fun ensureInstalled(vmId: VmId) {
                precedingEntered.complete(Unit)
                releasePreceding.await()
            }
        }
        val endpoint = endpoint(
            manager = holdingManager,
            auxiliary = smoke,
            backendSmokeTotalDeadlineMs = 50,
        )
        val preceding = launch(Dispatchers.Default) { endpoint.ensureInstalled() }
        precedingEntered.await()
        val startedNanos = System.nanoTime()

        val result = try {
            withTimeout(500) { endpoint.runBackendSmokeTest(deadline(50)) }
        } finally {
            releasePreceding.complete(Unit)
            preceding.join()
        }
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)
        endpoint.ensureInstalled()

        assertTrue(result.contains("exceeded total 50ms Binder deadline"))
        assertTrue("elapsed=${elapsedMs}ms", elapsedMs < 500)
        assertEquals(0, smokeCalls.get())
    }

    @Test
    fun `endpoint rejects expired deadline and clamps far future deadline`() = runBlocking {
        val nowNanos = AtomicLong(1_000_000L)
        val smokeCalls = AtomicInteger()
        val receivedDeadline = AtomicLong()
        val smoke = object : VmServiceAuxiliaryCapabilities by FakeAuxiliary {
            override suspend fun runBackendSmokeTest(deadlineNanos: Long): String {
                smokeCalls.incrementAndGet()
                receivedDeadline.set(deadlineNanos)
                return "smoke ran"
            }
        }
        val endpoint = endpoint(
            auxiliary = smoke,
            backendSmokeTotalDeadlineMs = 50,
            nanoTime = nowNanos::get,
        )

        val expired = endpoint.runBackendSmokeTest(nowNanos.get())
        val accepted = endpoint.runBackendSmokeTest(Long.MAX_VALUE)

        assertTrue(expired.contains("deadline"))
        assertEquals("smoke ran", accepted)
        assertEquals(1, smokeCalls.get())
        assertEquals(
            nowNanos.get() + TimeUnit.MILLISECONDS.toNanos(50),
            receivedDeadline.get(),
        )
    }

    @Test
    fun `one deadline survives both queues and cancellation insensitive backend work`() = runBlocking {
        val nowNanos = AtomicLong(1_000_000L)
        val releaseBackend = AtomicBoolean(false)
        val backendStarted = CountDownLatch(1)
        val backendCleanupFinished = CountDownLatch(1)
        val attemptGate = AvfSmokeAttemptGate()
        val smoke = object : VmServiceAuxiliaryCapabilities by FakeAuxiliary {
            override suspend fun runBackendSmokeTest(deadlineNanos: Long): String =
                withContext(Dispatchers.IO) {
                    val execution = AvfSmokeTestExecutor(
                        operationTimeoutMs = 1_000,
                        cleanupTimeoutMs = 500,
                        startupObservationDelayMs = 0,
                        attemptGate = attemptGate,
                        nanoTime = nowNanos::get,
                    ).execute(
                        deadlineNanos = deadlineNanos,
                        setup = { Unit },
                        create = {
                            backendStarted.countDown()
                            nowNanos.set(deadlineNanos)
                            blockIgnoringInterrupts(releaseBackend)
                            Any()
                        },
                        run = { },
                        stop = { },
                        deleteByFixedName = { backendCleanupFinished.countDown() },
                    )
                    if (execution.timedOut) "backend deadline" else "backend returned"
                }
        }
        val endpointQueueEntered = CompletableDeferred<Unit>()
        val releaseEndpointQueue = CompletableDeferred<Unit>()
        val manager = object : VmManager by FakeManager() {
            override suspend fun ensureInstalled(vmId: VmId) {
                endpointQueueEntered.complete(Unit)
                releaseEndpointQueue.await()
            }
        }
        val endpoint = endpoint(
            manager = manager,
            auxiliary = smoke,
            backendSmokeTotalDeadlineMs = 1_000,
            nanoTime = nowNanos::get,
        )
        val scopeJob = SupervisorJob()
        val state = VmBindingStateMachine(
            CoroutineScope(scopeJob + Dispatchers.Default),
            observation(VmLifecycleState.IDLE),
            connectionTimeoutMs = 500,
            nanoTime = nowNanos::get,
        )
        state.connected(endpoint)

        val endpointPreceding = launch(Dispatchers.Default) { endpoint.ensureInstalled() }
        endpointQueueEntered.await()
        val clientQueueEntered = CompletableDeferred<Unit>()
        val releaseClientQueue = CompletableDeferred<Unit>()
        val clientPreceding = launch(Dispatchers.Default) {
            state.command {
                clientQueueEntered.complete(Unit)
                releaseClientQueue.await()
            }
        }
        clientQueueEntered.await()
        val deadlineNanos = MonotonicDeadline.afterMillis(200, nowNanos::get)
        val startedNanos = System.nanoTime()
        val publicCall = async(Dispatchers.Default) {
            state.commandUntil(deadlineNanos, backendSmokeDeadlineResult(200)) {
                it.runBackendSmokeTest(deadlineNanos)
            }
        }

        nowNanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(80))
        releaseClientQueue.complete(Unit)
        clientPreceding.join()
        nowNanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(80))
        releaseEndpointQueue.complete(Unit)
        endpointPreceding.join()
        assertTrue(backendStarted.await(1, TimeUnit.SECONDS))

        val result = try {
            val completed = withTimeout(500) { publicCall.await() }
            assertTrue(
                AvfSmokeTestExecutor(
                    operationTimeoutMs = 100,
                    cleanupTimeoutMs = 100,
                    startupObservationDelayMs = 0,
                    attemptGate = attemptGate,
                    nanoTime = nowNanos::get,
                ).execute(
                    deadlineNanos = nowNanos.get() + TimeUnit.SECONDS.toNanos(1),
                    setup = { Unit },
                    create = { Any() },
                    run = { },
                    stop = { },
                    deleteByFixedName = { },
                ).busy,
            )
            completed
        } finally {
            releaseBackend.set(true)
            assertTrue(backendCleanupFinished.await(1, TimeUnit.SECONDS))
            scopeJob.cancel()
        }
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)

        assertTrue(result.contains("deadline"))
        assertTrue("elapsed=${elapsedMs}ms", elapsedMs < 500)
    }

    @Test
    fun `binding loss keeps last DTO and rebind mirrors fresh state without stop`() = runBlocking {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + Dispatchers.Default)
        val initial = observation(VmLifecycleState.IDLE)
        val state = VmBindingStateMachine(scope, initial, connectionTimeoutMs = 500)
        val first = FakeEndpoint(observation(VmLifecycleState.RUNNING))
        state.connected(first)
        delay(30)
        assertEquals(VmLifecycleState.RUNNING, state.observation.value.lifecycle)

        state.disconnected(first)
        assertEquals(VmLifecycleState.RUNNING, state.observation.value.lifecycle)
        assertEquals(0, first.stopCalls)

        val second = FakeEndpoint(observation(VmLifecycleState.ERROR, "rebound"))
        state.connecting()
        state.connected(second)
        delay(30)
        assertEquals(VmLifecycleState.ERROR, state.observation.value.lifecycle)
        assertEquals("rebound", state.observation.value.errorMessage)
        assertEquals(VmBindingState.CONNECTED, state.bindingState.value)
        scopeJob.cancel()
    }

    @Test
    fun `client state machine serializes concurrent commands`() = runBlocking {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + Dispatchers.Default)
        val state = VmBindingStateMachine(scope, observation(VmLifecycleState.IDLE), 500)
        val endpoint = FakeEndpoint(observation(VmLifecycleState.IDLE))
        state.connected(endpoint)
        val events = mutableListOf<String>()

        listOf(1, 2, 3).map { id ->
            launch(Dispatchers.Default) {
                state.command {
                    synchronized(events) { events += "start:$id" }
                    delay(20)
                    synchronized(events) { events += "end:$id" }
                }
            }
        }.joinAll()

        assertEquals(6, events.size)
        assertTrue(events.chunked(2).all { it[0].substringAfter(':') == it[1].substringAfter(':') })
        scopeJob.cancel()
    }

    private fun endpoint(
        manager: VmManager = FakeManager(),
        verifier: CallerUidVerifier = CallerUidVerifier { },
        lifecycle: VmServiceLifecycleCommands = NoopLifecycle,
        auxiliary: VmServiceAuxiliaryCapabilities = FakeAuxiliary,
        backendSmokeTotalDeadlineMs: Long = 15_000,
        nanoTime: () -> Long = System::nanoTime,
    ) = LocalVmServiceEndpoint(
        manager,
        lifecycle,
        auxiliary,
        verifier,
        backendSmokeTotalDeadlineMs,
        nanoTime,
    )

    private class FakeManager(
        private val onCall: () -> Unit = {},
    ) : VmManager {
        val calls = mutableListOf<String>()
        val vmIds = mutableListOf<VmId>()
        private val lifecycle = MutableStateFlow(VmLifecycleState.IDLE)
        private val observation = MutableStateFlow(observation(VmLifecycleState.IDLE))
        private val boolean = MutableStateFlow(true)
        private fun called(name: String, vmId: VmId) { onCall(); calls += name; vmIds += vmId }
        override fun lifecycle(vmId: VmId) = lifecycle
        override fun observation(vmId: VmId) = observation
        override fun quiescent(vmId: VmId) = boolean
        override fun busy(vmId: VmId) = boolean
        override suspend fun list(vmId: VmId) = listOf(VmSummary(vmId, true, VmLifecycleState.IDLE)).also { called("list", vmId) }
        override suspend fun status(vmId: VmId) = VmStatus(vmId, true, VmLifecycleState.IDLE, "qemu").also { called("status", vmId) }
        override suspend fun supervisorState(vmId: VmId) =
            HostSupervisorState.safeDefaults().also { called("supervisor", vmId) }
        override suspend fun prepareLifecycleCommand(
            vmId: VmId,
            operation: LifecycleOperation,
            expectedCommandGeneration: Long?,
        ): LifecycleTransactionToken = LifecycleTransactionToken.restore(
            expectedCommandGeneration ?: 1L,
            operation,
            0L,
        ).also { called("prepare:$operation", vmId) }
        override suspend fun acceptPrepared(
            vmId: VmId,
            command: LifecycleTransactionToken,
        ): Boolean = true.also { called("accept:${command.operation}", vmId) }
        override suspend fun authorizeServiceDispatch(
            vmId: VmId,
            command: LifecycleTransactionToken,
            admission: () -> Unit,
        ): Boolean = true.also {
            called("authorizeServiceDispatch:${command.operation}", vmId)
            admission()
        }
        override suspend fun executeAccepted(
            vmId: VmId,
            command: LifecycleTransactionToken,
        ): Boolean = true.also { called("executeAccepted:${command.operation}", vmId) }
        override suspend fun failAccepted(
            vmId: VmId,
            command: LifecycleTransactionToken,
            errorCode: LifecycleErrorCode,
        ): Boolean = true.also { called("failAccepted:$errorCode", vmId) }
        override suspend fun executePrepared(
            vmId: VmId,
            command: LifecycleTransactionToken,
        ): Boolean = true.also { called("execute:${command.operation}", vmId) }
        override suspend fun ensureInstalled(vmId: VmId) { called("install", vmId) }
        override suspend fun start(vmId: VmId) { called("start", vmId) }
        override suspend fun stop(vmId: VmId) { called("stop", vmId) }
        override suspend fun forceStop(vmId: VmId) { called("forceStop", vmId) }
        override suspend fun restart(vmId: VmId) { called("restart", vmId) }
        override suspend fun remove(vmId: VmId, policy: VmRemovePolicy) { called("remove:$policy", vmId) }
        override suspend fun readConsoleLog(vmId: VmId, request: ConsoleLogRequest) = ConsoleLog("", 0, 0, false).also { called("log", vmId) }
        override suspend fun executeQmp(vmId: VmId, operation: VmQmpOperation) = VmQmpResult.Status("running").also { called("qmp", vmId) }
        override suspend fun discoverSshEndpoint(vmId: VmId) = SshEndpointDiscovery(false, false, null).also { called("ssh", vmId) }
        override suspend fun runtimeMetrics(vmId: VmId) = VmRuntimeMetrics(0, null, null).also { called("metrics", vmId) }
        override suspend fun diagnostics(vmId: VmId, request: VmDiagnosticsRequest) = VmDiagnostics("", false).also { called("diagnostics", vmId) }
    }

    private class FakeEndpoint(
        initial: VmObservation,
        private val smokeTest: suspend (Long) -> String = { "" },
    ) : VmServiceEndpoint {
        override val observation = MutableStateFlow(initial)
        override val headlessMode = MutableStateFlow(false)
        var stopCalls = 0
        override suspend fun list() = emptyList<VmSummary>()
        override suspend fun status() = VmStatus(VmId.DEFAULT, true, VmLifecycleState.IDLE, "fake")
        override suspend fun supervisorState() = HostSupervisorState.safeDefaults()
        override suspend fun ensureInstalled() = Unit
        override suspend fun start() = Unit
        override suspend fun gracefulStop() { stopCalls++ }
        override suspend fun forceStop() { stopCalls++ }
        override suspend fun restart() = Unit
        override suspend fun remove(policy: VmRemovePolicy) = Unit
        override suspend fun readConsoleLog(request: ConsoleLogRequest) = ConsoleLog("", 0, 0, false)
        override suspend fun executeQmp(operation: VmQmpOperation) = VmQmpResult.Status("running")
        override suspend fun discoverSshEndpoint() = SshEndpointDiscovery(false, false, null)
        override suspend fun runtimeMetrics() = VmRuntimeMetrics(0, null, null)
        override suspend fun diagnostics(request: VmDiagnosticsRequest) = VmDiagnostics("", false)
        override suspend fun backendProbe() = FakeAuxiliary.backendProbe()
        override suspend fun runBackendSmokeTest(deadlineNanos: Long) = smokeTest(deadlineNanos)
        override suspend fun setHeadlessMode(active: Boolean) = Unit
        override fun createTerminalSession(client: TerminalSessionClient): TerminalSession = error("not used")
        override fun releaseTerminalClient(client: TerminalSessionClient) = Unit
    }

    private object NoopLifecycle : VmServiceLifecycleCommands {
        override suspend fun startForeground() = Unit
        override suspend fun stop(force: Boolean) = Unit
        override suspend fun restart() = Unit
    }

    private object FakeAuxiliary : VmServiceAuxiliaryCapabilities {
        override val headlessMode: StateFlow<Boolean> = MutableStateFlow(false)
        override fun backendProbe() = VmBackendProbe(false, false, false, false, false, false, false, 0, "n/a", "fake")
        override suspend fun runBackendSmokeTest(deadlineNanos: Long) = ""
        override fun setHeadlessMode(active: Boolean) = Unit
        override fun createTerminalSession(client: TerminalSessionClient): TerminalSession = error("not used")
        override fun releaseTerminalClient(client: TerminalSessionClient) = Unit
    }

    private fun blockIgnoringInterrupts(release: AtomicBoolean) {
        while (!release.get()) {
            try {
                Thread.sleep(5)
            } catch (_: InterruptedException) {
                // Simulates a vendor Binder/reflection call that ignores interrupt.
            }
        }
    }

    companion object {
        private fun deadline(timeoutMs: Long): Long =
            MonotonicDeadline.afterMillis(timeoutMs)

        private fun observation(lifecycle: VmLifecycleState, error: String? = null) =
            VmObservation(VmId.DEFAULT, lifecycle, "fake", errorMessage = error)
    }
}
