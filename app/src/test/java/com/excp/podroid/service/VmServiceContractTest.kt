package com.excp.podroid.service

import com.excp.podroid.vm.ConsoleLog
import com.excp.podroid.vm.ConsoleLogRequest
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
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
            override fun startForeground() { events += "foreground-start" }
            override fun stop(force: Boolean) { events += if (force) "force-stop" else "graceful-stop" }
            override fun restart() { events += "foreground-restart" }
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
        endpoint.ensureInstalled()
        endpoint.remove(VmRemovePolicy.DELETE_DATA)
        endpoint.readConsoleLog(ConsoleLogRequest(32, 2))
        endpoint.executeQmp(VmQmpOperation.QueryStatus)
        endpoint.discoverSshEndpoint()
        endpoint.runtimeMetrics()
        endpoint.diagnostics(VmDiagnosticsRequest(32))

        assertEquals(
            listOf("list", "status", "install", "remove:DELETE_DATA", "log", "qmp", "ssh", "metrics", "diagnostics"),
            manager.calls,
        )
        assertTrue(manager.vmIds.all { it == VmId.DEFAULT })
    }

    @Test
    fun `Binder smoke path bounds forever pending readiness and releases command admission`() = runBlocking {
        val manager = FakeManager()
        val neverReady = object : VmServiceAuxiliaryCapabilities by FakeAuxiliary {
            override suspend fun runBackendSmokeTest(): String = awaitCancellation()
        }
        val endpoint = endpoint(
            manager = manager,
            auxiliary = neverReady,
            backendSmokeTotalDeadlineMs = 50,
        )
        val startedNanos = System.nanoTime()

        val result = endpoint.runBackendSmokeTest()
        endpoint.status()
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)

        assertTrue(result.contains("exceeded total 50ms Binder deadline"))
        assertEquals(listOf("status"), manager.calls)
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
    ) = LocalVmServiceEndpoint(
        manager,
        lifecycle,
        auxiliary,
        verifier,
        backendSmokeTotalDeadlineMs,
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

    private class FakeEndpoint(initial: VmObservation) : VmServiceEndpoint {
        override val observation = MutableStateFlow(initial)
        override val headlessMode = MutableStateFlow(false)
        var stopCalls = 0
        override suspend fun list() = emptyList<VmSummary>()
        override suspend fun status() = VmStatus(VmId.DEFAULT, true, VmLifecycleState.IDLE, "fake")
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
        override suspend fun runBackendSmokeTest() = ""
        override suspend fun setHeadlessMode(active: Boolean) = Unit
        override fun createTerminalSession(client: TerminalSessionClient): TerminalSession = error("not used")
        override fun releaseTerminalClient(client: TerminalSessionClient) = Unit
    }

    private object NoopLifecycle : VmServiceLifecycleCommands {
        override fun startForeground() = Unit
        override fun stop(force: Boolean) = Unit
        override fun restart() = Unit
    }

    private object FakeAuxiliary : VmServiceAuxiliaryCapabilities {
        override val headlessMode: StateFlow<Boolean> = MutableStateFlow(false)
        override fun backendProbe() = VmBackendProbe(false, false, false, false, false, false, false, 0, "n/a", "fake")
        override suspend fun runBackendSmokeTest() = ""
        override fun setHeadlessMode(active: Boolean) = Unit
        override fun createTerminalSession(client: TerminalSessionClient): TerminalSession = error("not used")
        override fun releaseTerminalClient(client: TerminalSessionClient) = Unit
    }

    companion object {
        private fun observation(lifecycle: VmLifecycleState, error: String? = null) =
            VmObservation(VmId.DEFAULT, lifecycle, "fake", errorMessage = error)
    }
}
