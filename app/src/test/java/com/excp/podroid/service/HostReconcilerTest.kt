package com.excp.podroid.service

import com.excp.podroid.data.repository.AtomicHostSupervisorRecordStore
import com.excp.podroid.data.repository.HostSupervisorRecordCodec
import com.excp.podroid.data.repository.HostSupervisorRepository
import com.excp.podroid.vm.*
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class HostReconcilerTest {
    @Test fun `service action mapping accepts only boot and sticky null restart`() {
        assertEquals(ReconciliationTrigger.BOOT_COMPLETED,
            ReconciliationServiceTriggerPolicy.fromAction(PodroidService.ACTION_RECONCILE_BOOT))
        assertEquals(ReconciliationTrigger.PROCESS_RESTART,
            ReconciliationServiceTriggerPolicy.fromAction(null))
        assertNull(ReconciliationServiceTriggerPolicy.fromAction("com.excp.podroid.action.RECONCILE_APP"))
        assertNull(ReconciliationServiceTriggerPolicy.fromAction("unexpected"))
    }

    @Test fun `boot skip does not prepare launch when autostart is false`() = runBlocking {
        val fixture = fixture(autostart = false)
        val result = fixture.reconciler.reconcile(ReconciliationTrigger.BOOT_COMPLETED)
        assertEquals(ReconciliationOutcome.SKIPPED_AUTOSTART_DISABLED, result.outcome)
        assertEquals(0, fixture.manager.prepareCalls)
        assertEquals(0, fixture.transport.calls)
    }

    @Test fun `app and process triggers restore through prepared manager start path despite autostart false`() = runBlocking {
        for (trigger in listOf(ReconciliationTrigger.APP_COLD_START, ReconciliationTrigger.PROCESS_RESTART)) {
            val fixture = fixture(autostart = false)
            val result = fixture.reconciler.reconcile(trigger)
            assertEquals(ReconciliationOutcome.SUCCEEDED, result.outcome)
            assertEquals(1, fixture.manager.prepareCalls)
            assertEquals(LifecycleOperation.RECOVER, fixture.manager.lastPreparedOperation)
            assertEquals(1, fixture.manager.executeCalls)
            assertEquals(1, fixture.manager.forwardRestoreCalls)
            assertEquals(1, fixture.transport.calls)
            assertEquals(ReconciliationServiceDisposition.SUPERVISE_RUNTIME, result.disposition)
        }
    }

    @Test fun `already owned live runtime is not launched twice and transport still reconciles`() = runBlocking {
        val fixture = fixture(autostart = true)
        fixture.manager.lifecycle.value = VmLifecycleState.RUNNING
        fixture.manager.quiescent.value = false

        val result = fixture.reconciler.reconcile(ReconciliationTrigger.PROCESS_RESTART)

        assertEquals(ReconciliationOutcome.SUCCEEDED, result.outcome)
        assertEquals(0, fixture.manager.prepareCalls)
        assertEquals(0, fixture.manager.executeCalls)
        assertEquals(1, fixture.transport.calls)
    }

    @Test fun `one reconciler runs at a time and concurrent duplicate cannot launch`() = runBlocking {
        val fixture = fixture(autostart = true)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.manager.executionGate = {
            entered.complete(Unit)
            release.await()
        }
        val first = async { fixture.reconciler.reconcile(ReconciliationTrigger.PROCESS_RESTART) }
        entered.await()
        val duplicate = async { fixture.reconciler.reconcile(ReconciliationTrigger.APP_COLD_START) }.await()
        release.complete(Unit)
        assertEquals(ReconciliationOutcome.SUPERSEDED, duplicate.outcome)
        assertEquals(ReconciliationOutcome.SUCCEEDED, first.await().outcome)
        assertEquals(1, fixture.manager.executeCalls)
    }

    @Test fun `newer explicit stop supersedes recovery before launch`() = runBlocking {
        val fixture = fixture(autostart = true)
        fixture.manager.beforeRecoveryPrepare = {
            fixture.repository.prepare(LifecycleOperation.STOP)
        }

        val result = fixture.reconciler.reconcile(ReconciliationTrigger.PROCESS_RESTART)

        assertEquals(ReconciliationOutcome.SUPERSEDED, result.outcome)
        assertEquals(0, fixture.manager.executeCalls)
        assertEquals(VmDesiredState.STOPPED, fixture.repository.snapshot().desiredState)
    }

    @Test fun `launch failure records stable redacted backoff outcome`() = runBlocking {
        val fixture = fixture(autostart = true)
        fixture.manager.failure = java.io.IOException("secret /private/path")
        val result = fixture.reconciler.reconcile(ReconciliationTrigger.PROCESS_RESTART)
        val state = fixture.repository.snapshot()
        assertEquals(ReconciliationOutcome.FAILED, result.outcome)
        assertEquals(LifecycleErrorCode.IO, state.reconciliation.lastErrorCode)
        assertTrue(state.reconciliation.nextEligibleEpochMs > fixture.clock.get())
        assertFalse(HostSupervisorRecordCodec.encode(state).contains("private"))
    }

    @Test fun `possible live orphan failure supervises despite quiescent in-process engine`() = runBlocking {
        val fixture = fixture(autostart = true)
        fixture.manager.failure = RuntimeProbeException(
            LifecycleErrorCode.RUNTIME_OWNERSHIP,
            runtimeMayBeLive = true,
        )
        fixture.manager.quiescent.value = true

        val result = fixture.reconciler.reconcile(ReconciliationTrigger.PROCESS_RESTART)

        assertEquals(ReconciliationOutcome.FAILED, result.outcome)
        assertEquals(ReconciliationServiceDisposition.SUPERVISE_RUNTIME, result.disposition)
        assertTrue(result.runtimeMayBeLive)

        val backoff = fixture.reconciler.reconcile(ReconciliationTrigger.PROCESS_RESTART)
        assertEquals(ReconciliationOutcome.BACKOFF, backoff.outcome)
        assertEquals(ReconciliationServiceDisposition.SUPERVISE_RUNTIME, backoff.disposition)
        assertTrue(backoff.runtimeMayBeLive)
        val retention = VmServiceLifecyclePolicy.decide(
            VmLifecycleState.IDLE,
            quiescent = true,
            busy = false,
            pendingStartOwned = backoff.runtimeMayBeLive,
        )
        assertFalse(retention.teardown)
    }

    @Test fun `explicit desired stopped never starts`() = runBlocking {
        val fixture = fixture(autostart = true, desired = VmDesiredState.STOPPED)
        val result = fixture.reconciler.reconcile(ReconciliationTrigger.APP_COLD_START)
        assertEquals(ReconciliationOutcome.SKIPPED_DESIRED_STOPPED, result.outcome)
        assertEquals(0, fixture.manager.prepareCalls)
    }

    private fun fixture(
        autostart: Boolean,
        desired: VmDesiredState = VmDesiredState.RUNNING,
    ): Fixture {
        val clock = AtomicLong(1_000)
        val state = HostSupervisorState.safeDefaults().copy(
            hostEnabled = true,
            desiredState = desired,
            autostart = autostart,
        )
        val repository = HostSupervisorRepository(
            FakeStore(HostSupervisorRecordCodec.encode(state)),
            clock::get,
        )
        val manager = FakeManager(repository)
        val transport = FakeTransport()
        return Fixture(clock, repository, manager, transport, HostReconciler(manager, transport))
    }

    private data class Fixture(
        val clock: AtomicLong,
        val repository: HostSupervisorRepository,
        val manager: FakeManager,
        val transport: FakeTransport,
        val reconciler: HostReconciler,
    )

    private class FakeTransport : HostTransportReconciler {
        var calls = 0
        override suspend fun reconcile(vmId: VmId): TransportReconciliationResult {
            calls++
            return TransportReconciliationResult.NO_CONFIGURED_TRANSPORT
        }
    }

    private class FakeManager(private val repository: HostSupervisorRepository) : VmManager {
        val lifecycle = MutableStateFlow(VmLifecycleState.IDLE)
        val quiescent = MutableStateFlow(true)
        var prepareCalls = 0
        var lastPreparedOperation: LifecycleOperation? = null
        var executeCalls = 0
        var forwardRestoreCalls = 0
        var executionGate: suspend () -> Unit = {}
        var beforeRecoveryPrepare: suspend () -> Unit = {}
        var failure: Throwable? = null

        override fun lifecycle(vmId: VmId) = lifecycle
        override fun quiescent(vmId: VmId) = quiescent
        override fun busy(vmId: VmId) = MutableStateFlow(!quiescent.value)
        override fun observation(vmId: VmId) = MutableStateFlow(
            VmObservation(vmId, lifecycle.value, "fake"),
        )
        override suspend fun supervisorState(vmId: VmId) = repository.snapshot()
        override suspend fun beginReconciliation(vmId: VmId, trigger: ReconciliationTrigger) =
            repository.begin(trigger)
        override suspend fun finishReconciliation(
            vmId: VmId,
            token: ReconciliationAttemptToken,
            outcome: ReconciliationOutcome,
            errorCode: LifecycleErrorCode?,
        ) = repository.finish(token, outcome, errorCode)
        override suspend fun prepareLifecycleCommand(
            vmId: VmId,
            operation: LifecycleOperation,
            expectedCommandGeneration: Long?,
        ): LifecycleTransactionToken {
            prepareCalls++
            lastPreparedOperation = operation
            val before = beforeRecoveryPrepare
            beforeRecoveryPrepare = {}
            before()
            return repository.prepare(operation, expectedCommandGeneration)
        }
        override suspend fun executePrepared(vmId: VmId, command: LifecycleTransactionToken): Boolean {
            executeCalls++
            executionGate()
            failure?.let { throw it }
            if (!repository.claim(command)) return false
            // Represents DefaultVmManager's existing launchPlan path, which
            // reads persisted + implicit forwards before runtime.start.
            forwardRestoreCalls++
            repository.succeed(command, runtimeStarted = true)
            lifecycle.value = VmLifecycleState.RUNNING
            quiescent.value = false
            return true
        }
        override suspend fun acceptPrepared(vmId: VmId, command: LifecycleTransactionToken) = repository.claim(command)
        override suspend fun authorizeServiceDispatch(vmId: VmId, command: LifecycleTransactionToken, admission: () -> Unit): Boolean {
            admission(); return true
        }
        override suspend fun executeAccepted(vmId: VmId, command: LifecycleTransactionToken) = executePrepared(vmId, command)
        override suspend fun failAccepted(vmId: VmId, command: LifecycleTransactionToken, errorCode: LifecycleErrorCode) = repository.fail(command, errorCode)
        override suspend fun list(vmId: VmId) = emptyList<VmSummary>()
        override suspend fun status(vmId: VmId) = VmStatus(vmId, true, lifecycle.value, "fake")
        override suspend fun ensureInstalled(vmId: VmId) = Unit
        override suspend fun start(vmId: VmId) = Unit
        override suspend fun stop(vmId: VmId) = Unit
        override suspend fun forceStop(vmId: VmId) = Unit
        override suspend fun restart(vmId: VmId) = Unit
        override suspend fun remove(vmId: VmId, policy: VmRemovePolicy) = Unit
        override suspend fun readConsoleLog(vmId: VmId, request: ConsoleLogRequest) = ConsoleLog("", 0, 0, false)
        override suspend fun executeQmp(vmId: VmId, operation: VmQmpOperation): VmQmpResult = VmQmpResult.Status("running")
        override suspend fun discoverSshEndpoint(vmId: VmId) = SshEndpointDiscovery(false, false, null)
        override suspend fun runtimeMetrics(vmId: VmId) = VmRuntimeMetrics(0, null, null)
        override suspend fun diagnostics(vmId: VmId, request: VmDiagnosticsRequest) = VmDiagnostics("", false)
    }

    private class FakeStore(initial: String) : AtomicHostSupervisorRecordStore {
        private val mutex = Mutex()
        private var raw: String? = initial
        override suspend fun read(): String? = mutex.withLock { raw }
        override suspend fun update(transform: (String?) -> String): String = mutex.withLock {
            transform(raw).also { raw = it }
        }
    }
}
