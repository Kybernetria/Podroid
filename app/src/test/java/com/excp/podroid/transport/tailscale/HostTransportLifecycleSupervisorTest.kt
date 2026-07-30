package com.excp.podroid.transport.tailscale

import com.excp.podroid.transport.api.HostTransportConfiguration
import com.excp.podroid.transport.api.HostTransportIdentity
import com.excp.podroid.transport.state.AtomicHostTransportStateStore
import com.excp.podroid.transport.state.HostTransportFailure
import com.excp.podroid.transport.state.HostTransportPersistentState
import com.excp.podroid.transport.state.HostTransportPhase
import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostTransportLifecycleSupervisorTest {
    private val configuration = HostTransportConfiguration(
        HostTransportIdentity("podroid-host"),
        URI("https://control.example.test"),
    )

    @Test
    fun `same desired generation and duplicate reconcile are idempotent`() {
        val store = FakeStore()
        val factory = FakeFactory()
        val supervisor = supervisor(store, "process-a", factory)

        val enabled = supervisor.requestEnabled(true)
        val duplicate = supervisor.requestEnabled(true)
        assertEquals(1, enabled.desiredGeneration)
        assertEquals(1, duplicate.desiredGeneration)
        assertEquals(HostTransportReconciliationResult.STARTED, supervisor.reconcile())
        assertEquals(HostTransportReconciliationResult.NO_CHANGE, supervisor.reconcile())
        assertEquals(1, factory.starts)
        assertEquals(HostTransportPhase.RUNNING, store.read().phase)
    }

    @Test
    fun `disable generation closes exact owned runtime once and reconciles stopped`() {
        val store = FakeStore()
        val factory = FakeFactory()
        val supervisor = supervisor(store, "process-a", factory)
        supervisor.requestEnabled(true)
        supervisor.reconcile()
        val runtime = factory.runtimes.single()

        supervisor.requestEnabled(false)
        assertEquals(HostTransportReconciliationResult.STOPPED, supervisor.reconcile())
        assertEquals(HostTransportReconciliationResult.NO_CHANGE, supervisor.reconcile())

        assertEquals(1, runtime.closes)
        assertFalse(store.read().desiredEnabled)
        assertEquals(store.read().desiredGeneration, store.read().appliedGeneration)
        assertEquals(HostTransportPhase.STOPPED, store.read().phase)
    }

    @Test
    fun `new process clears stale in-process owner and restarts desired generation`() {
        val persisted = HostTransportPersistentState.safeDefaults().copy(
            desiredEnabled = true,
            desiredGeneration = 3,
            appliedGeneration = 3,
            phase = HostTransportPhase.RUNNING,
            ownerProcess = "old-process",
            ownerGeneration = 3,
        )
        val store = FakeStore(persisted)
        val factory = FakeFactory()
        val supervisor = supervisor(store, "new-process", factory)

        assertEquals(
            HostTransportReconciliationResult.RECOVERED_AND_STARTED,
            supervisor.reconcile(),
        )
        assertEquals(1, factory.starts)
        assertEquals("new-process", store.read().ownerProcess)
        assertEquals(3L, store.read().ownerGeneration)
    }

    @Test
    fun `supervisor close releases only local ownership without changing desired intent`() {
        val store = FakeStore()
        val firstFactory = FakeFactory()
        val first = supervisor(store, "process-a", firstFactory)
        first.requestEnabled(true)
        first.reconcile()

        first.close()

        assertTrue(store.read().desiredEnabled)
        assertEquals(HostTransportPhase.STOPPED, store.read().phase)
        assertEquals(1, firstFactory.runtimes.single().closes)
        val secondFactory = FakeFactory()
        val second = supervisor(store, "process-b", secondFactory)
        assertEquals(HostTransportReconciliationResult.STARTED, second.reconcile())
        assertEquals(1, secondFactory.starts)
    }

    @Test
    fun `different owner is not cleared without explicit inactive proof`() {
        val persisted = HostTransportPersistentState.safeDefaults().copy(
            desiredEnabled = true,
            desiredGeneration = 1,
            appliedGeneration = 1,
            phase = HostTransportPhase.RUNNING,
            ownerProcess = "other-process",
            ownerGeneration = 1,
        )
        val store = FakeStore(persisted)
        val factory = FakeFactory()

        val failure = runCatching {
            supervisor(store, "process-a", factory, ownerDefinitelyInactive = false).reconcile()
        }.exceptionOrNull()

        assertTrue(failure is HostTransportOwnershipException)
        assertEquals(persisted, store.read())
        assertEquals(0, factory.starts)
    }

    @Test
    fun `same-process durable owner without runtime fails closed before start`() {
        val persisted = HostTransportPersistentState.safeDefaults().copy(
            desiredEnabled = true,
            desiredGeneration = 1,
            appliedGeneration = 1,
            phase = HostTransportPhase.RUNNING,
            ownerProcess = "process-a",
            ownerGeneration = 1,
        )
        val store = FakeStore(persisted)
        val factory = FakeFactory()

        val failure = runCatching { supervisor(store, "process-a", factory).reconcile() }.exceptionOrNull()

        assertTrue(failure is HostTransportOwnershipException)
        assertEquals(0, factory.starts)
        assertEquals(HostTransportPhase.RECOVERY_REQUIRED, store.read().phase)
        assertEquals(HostTransportFailure.OWNERSHIP_CONFLICT, store.read().lastFailure)
    }

    @Test
    fun `failed close keeps ownership evidence and retry closes idempotently`() {
        val store = FakeStore()
        val factory = FakeFactory(failFirstClose = true)
        val supervisor = supervisor(store, "process-a", factory)
        supervisor.requestEnabled(true)
        supervisor.reconcile()
        supervisor.requestEnabled(false)

        assertTrue(runCatching { supervisor.reconcile() }.isFailure)
        assertEquals(HostTransportPhase.RECOVERY_REQUIRED, store.read().phase)
        assertEquals("process-a", store.read().ownerProcess)
        assertEquals(HostTransportFailure.CLOSE_FAILED, store.read().lastFailure)

        assertEquals(HostTransportReconciliationResult.STOPPED, supervisor.reconcile())
        assertEquals(2, factory.runtimes.single().closes)
        assertEquals(HostTransportPhase.STOPPED, store.read().phase)
    }

    @Test
    fun `failed start clears ownership and records bounded classification`() {
        val store = FakeStore()
        val factory = FakeFactory(failStart = true)
        val supervisor = supervisor(store, "process-a", factory)
        supervisor.requestEnabled(true)

        assertTrue(runCatching { supervisor.reconcile() }.isFailure)
        val failed = store.read()
        assertEquals(HostTransportPhase.FAILED, failed.phase)
        assertEquals(HostTransportFailure.START_FAILED, failed.lastFailure)
        assertEquals(null, failed.ownerProcess)
    }

    @Test
    fun `failed publication and cleanup preserve recovery ownership for replay`() {
        val store = FakeStore(failRunningPublicationOnce = true)
        val factory = FakeFactory(failFirstClose = true)
        val supervisor = supervisor(store, "process-a", factory)
        supervisor.requestEnabled(true)

        assertTrue(runCatching { supervisor.reconcile() }.isFailure)
        assertEquals(HostTransportPhase.RECOVERY_REQUIRED, store.read().phase)
        assertEquals(HostTransportFailure.CLOSE_FAILED, store.read().lastFailure)
        assertEquals("process-a", store.read().ownerProcess)
        assertEquals(1, factory.runtimes.single().closes)

        assertEquals(HostTransportReconciliationResult.STARTED, supervisor.reconcile())
        assertEquals(2, factory.starts)
        assertEquals(2, factory.runtimes.first().closes)
        assertEquals(HostTransportPhase.RUNNING, store.read().phase)
    }

    private fun supervisor(
        store: FakeStore,
        process: String,
        factory: FakeFactory,
        ownerDefinitelyInactive: Boolean = true,
    ) = HostTransportLifecycleSupervisor(
        store,
        process,
        configuration,
        factory,
        AbandonedHostTransportOwnerProbe { ownerDefinitelyInactive },
    )

    private class FakeStore(
        initial: HostTransportPersistentState = HostTransportPersistentState.safeDefaults(),
        private var failRunningPublicationOnce: Boolean = false,
    ) : AtomicHostTransportStateStore {
        private var state = initial
        @Synchronized override fun read() = state
        @Synchronized override fun update(
            transform: (HostTransportPersistentState) -> HostTransportPersistentState,
        ): HostTransportPersistentState {
            val updated = transform(state)
            if (failRunningPublicationOnce && updated.phase == HostTransportPhase.RUNNING) {
                failRunningPublicationOnce = false
                error("running publication failed")
            }
            state = updated
            return updated
        }
    }

    private class FakeFactory(
        private val failStart: Boolean = false,
        private val failFirstClose: Boolean = false,
    ) : HostTransportRuntimeFactory {
        var starts = 0
        val runtimes = mutableListOf<FakeRuntime>()
        override fun start(
            configuration: HostTransportConfiguration,
            generation: Long,
        ): OwnedHostTransportRuntime {
            starts++
            if (failStart) error("start failed")
            return FakeRuntime(failFirstClose).also(runtimes::add)
        }
    }

    private class FakeRuntime(private val failFirstClose: Boolean) : OwnedHostTransportRuntime {
        var closes = 0
        override fun close() {
            closes++
            if (failFirstClose && closes == 1) error("close failed")
        }
    }
}
