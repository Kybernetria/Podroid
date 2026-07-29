package com.excp.podroid.data.repository

import com.excp.podroid.vm.*
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class HostSupervisorRepositoryTest {
    @Test
    fun `v0 absence initializes explicit safe v2 record`() = runBlocking {
        val store = FakeAtomicStore()
        val state = repository(store).snapshot()
        assertEquals(2, state.schemaVersion)
        assertFalse(state.hostEnabled)
        assertEquals(VmDesiredState.STOPPED, state.desiredState)
        assertEquals(ReconciliationMetadata.safeDefaults(), state.reconciliation)
        assertEquals(state, HostSupervisorRecordCodec.decodeV2(store.raw!!))
    }

    @Test
    fun `explicit v1 migration preserves intent and transaction and adds bounded metadata`() = runBlocking {
        val original = HostSupervisorState.safeDefaults().copy(
            hostEnabled = true,
            desiredState = VmDesiredState.RUNNING,
            autostart = true,
            runtimeGeneration = 7,
            latestTransaction = LifecycleTransaction(
                4, LifecycleOperation.START, LifecycleOutcome.PENDING, 10, null, null, true,
            ),
        )
        val legacy = HostSupervisorRecordCodec.encodeV1ForMigration(original)
        val store = FakeAtomicStore(legacy)

        val migrated = repository(store).snapshot()

        assertEquals(2, migrated.schemaVersion)
        assertTrue(migrated.hostEnabled)
        assertTrue(migrated.autostart)
        assertEquals(7, migrated.runtimeGeneration)
        assertEquals(original.latestTransaction, migrated.latestTransaction)
        assertEquals(ReconciliationMetadata.safeDefaults(), migrated.reconciliation)
        assertTrue(store.raw!!.startsWith("schema=2\n"))
        assertEquals(1, store.commits)
    }

    @Test
    fun `legacy transaction layout without effect bit remains readable`() = runBlocking {
        val state = HostSupervisorState.safeDefaults().copy(
            latestTransaction = LifecycleTransaction(
                1, LifecycleOperation.START, LifecycleOutcome.PENDING, 1, null, null,
            ),
        )
        val legacy = HostSupervisorRecordCodec.encodeV1ForMigration(state)
            .substringBeforeLast("\ntx_effect_started=")
        val decoded = HostSupervisorRecordCodec.decodeV1(legacy)
        assertFalse(decoded.latestTransaction!!.effectStarted)
    }

    @Test
    fun `unknown future schema fails closed without overwriting evidence`() = runBlocking {
        val evidence = "schema=3\nopaque=future-evidence"
        val store = FakeAtomicStore(evidence)
        val failure = runCatching { repository(store).snapshot() }.exceptionOrNull()
        assertTrue(failure is HostSupervisorSchemaException)
        assertEquals(evidence, store.raw)
        assertEquals(0, store.commits)
    }

    @Test
    fun `corrupt v2 fails closed without normalizing evidence`() = runBlocking {
        val evidence = HostSupervisorRecordCodec.encode(HostSupervisorState.safeDefaults())
            .replace("desired_state=STOPPED", "desired_state=maybe")
        val store = FakeAtomicStore(evidence)
        val failure = runCatching { repository(store).snapshot() }.exceptionOrNull()
        assertTrue(failure is HostSupervisorCorruptionException)
        assertEquals(evidence, store.raw)
        assertEquals(0, store.commits)
    }

    @Test
    fun `DataStore waits are bounded before lifecycle effects`() = runBlocking {
        val blocked = object : AtomicHostSupervisorRecordStore {
            override suspend fun read(): String? = null
            override suspend fun update(transform: (String?) -> String): String {
                delay(1_000); error("unreachable")
            }
        }
        val failure = runCatching {
            HostSupervisorRepository(blocked, { 1L }, 10L).prepare(LifecycleOperation.START)
        }.exceptionOrNull()
        assertTrue(failure is TimeoutCancellationException)
    }

    @Test
    fun `pending crash evidence is interrupted before recovery transaction id is admitted`() = runBlocking {
        val clock = AtomicLong(100)
        val initial = HostSupervisorState.safeDefaults().copy(
            hostEnabled = true,
            desiredState = VmDesiredState.RUNNING,
            latestTransaction = LifecycleTransaction(
                8, LifecycleOperation.RESTART, LifecycleOutcome.PENDING, 90, null, null, true,
            ),
        )
        val repository = repository(FakeAtomicStore(HostSupervisorRecordCodec.encode(initial)), clock)

        val admission = repository.begin(ReconciliationTrigger.PROCESS_RESTART)
        val state = repository.snapshot()

        val execute = admission as ReconciliationAdmission.Execute
        assertEquals(8L, execute.interruptedTransactionId)
        assertEquals(9L, execute.token.expectedNextTransactionId)
        assertEquals(LifecycleOutcome.FAILED, state.latestTransaction!!.outcome)
        assertEquals(LifecycleErrorCode.PROCESS_DIED, state.latestTransaction!!.errorCode)
        assertEquals(ReconciliationOutcome.ATTEMPTING, state.reconciliation.lastOutcome)
    }

    @Test
    fun `durable exponential backoff gates attempts and successful lifecycle resets it`() = runBlocking {
        val clock = AtomicLong(1_000)
        val repository = enabledRunningRepository(clock)
        val first = repository.begin(ReconciliationTrigger.APP_COLD_START) as ReconciliationAdmission.Execute
        repository.finish(first.token, ReconciliationOutcome.FAILED, LifecycleErrorCode.IO)
        val failed = repository.snapshot()
        assertEquals(1, failed.reconciliation.consecutiveAttempts)
        assertEquals(6_000, failed.reconciliation.nextEligibleEpochMs)
        assertEquals(ReconciliationOutcome.BACKOFF,
            (repository.begin(ReconciliationTrigger.PROCESS_RESTART) as ReconciliationAdmission.Skip).outcome)

        clock.set(6_000)
        val second = repository.begin(ReconciliationTrigger.PROCESS_RESTART) as ReconciliationAdmission.Execute
        val start = repository.prepare(LifecycleOperation.START, second.token.expectedNextTransactionId)
        assertTrue(repository.claim(start))
        assertTrue(repository.succeed(start, runtimeStarted = true))
        val reset = repository.snapshot().reconciliation
        assertEquals(0, reset.consecutiveAttempts)
        assertEquals(0, reset.nextEligibleEpochMs)
    }

    @Test
    fun `attempt count is capped and exhausted without a sixth attempt`() = runBlocking {
        val clock = AtomicLong(1_000)
        val repository = enabledRunningRepository(clock)
        repeat(ReconciliationMetadata.MAX_ATTEMPTS) {
            val admission = repository.begin(ReconciliationTrigger.PROCESS_RESTART) as ReconciliationAdmission.Execute
            repository.finish(admission.token, ReconciliationOutcome.FAILED, LifecycleErrorCode.IO)
            clock.set(repository.snapshot().reconciliation.nextEligibleEpochMs)
        }
        val exhausted = repository.begin(ReconciliationTrigger.PROCESS_RESTART) as ReconciliationAdmission.Skip
        assertEquals(ReconciliationOutcome.EXHAUSTED, exhausted.outcome)
        assertEquals(ReconciliationMetadata.MAX_ATTEMPTS, repository.snapshot().reconciliation.consecutiveAttempts)
    }

    @Test
    fun `concurrent commands allocate monotonic unique ids atomically`() = runBlocking {
        val repository = repository(FakeAtomicStore())
        val ids = (1..100).map { index ->
            async(Dispatchers.Default) {
                repository.prepare(if (index % 2 == 0) LifecycleOperation.START else LifecycleOperation.STOP).id
            }
        }.awaitAll()
        assertEquals((1L..100L).toSet(), ids.toSet())
    }

    @Test
    fun `autostart setter is atomic and preserves other authority`() = runBlocking {
        val repository = enabledRunningRepository(AtomicLong(10))
        val before = repository.snapshot()
        val after = repository.setAutostart(true)
        assertTrue(after.autostart)
        assertEquals(before.desiredState, after.desiredState)
        assertEquals(before.latestTransaction, after.latestTransaction)
    }

    private fun enabledRunningRepository(clock: AtomicLong): HostSupervisorRepository {
        val state = HostSupervisorState.safeDefaults().copy(
            hostEnabled = true,
            desiredState = VmDesiredState.RUNNING,
        )
        return repository(FakeAtomicStore(HostSupervisorRecordCodec.encode(state)), clock)
    }

    private fun repository(
        store: FakeAtomicStore,
        clock: AtomicLong = AtomicLong(1_700_000_000_000L),
    ) = HostSupervisorRepository(store, clock::get)

    private class FakeAtomicStore(initial: String? = null) : AtomicHostSupervisorRecordStore {
        private val mutex = Mutex()
        var raw: String? = initial; private set
        var commits = 0; private set
        override suspend fun read(): String? = mutex.withLock { raw }
        override suspend fun update(transform: (String?) -> String): String = mutex.withLock {
            transform(raw).also { raw = it; commits++ }
        }
    }
}
