package com.excp.podroid.data.repository

import com.excp.podroid.vm.HostSupervisorState
import com.excp.podroid.vm.LifecycleErrorCode
import com.excp.podroid.vm.LifecycleOperation
import com.excp.podroid.vm.LifecycleOutcome
import com.excp.podroid.vm.VmDesiredState
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostSupervisorRepositoryTest {
    @Test
    fun `v0 absence initializes one explicit safe v1 record`() = runBlocking {
        val store = FakeAtomicStore()
        val repository = repository(store)

        val state = repository.snapshot()

        assertEquals(HostSupervisorState.SCHEMA_VERSION, state.schemaVersion)
        assertFalse(state.hostEnabled)
        assertEquals(VmDesiredState.STOPPED, state.desiredState)
        assertFalse(state.autostart)
        assertEquals(0L, state.runtimeGeneration)
        assertEquals(null, state.latestTransaction)
        assertNotNull(store.raw)
        assertEquals(state, HostSupervisorRecordCodec.decodeV1(store.raw!!))
    }

    @Test
    fun `strict v1 codec round trips pending and terminal records`() = runBlocking {
        val store = FakeAtomicStore()
        val repository = repository(store)
        val token = repository.prepare(LifecycleOperation.START)
        val pending = repository.snapshot()
        assertTrue(repository.claim(token))

        assertEquals(pending, HostSupervisorRecordCodec.decodeV1(HostSupervisorRecordCodec.encode(pending)))
        assertEquals(LifecycleOutcome.PENDING, pending.latestTransaction?.outcome)
        assertEquals(VmDesiredState.RUNNING, pending.desiredState)

        repository.fail(token, LifecycleErrorCode.IO)
        val failed = repository.snapshot()
        assertEquals(failed, HostSupervisorRecordCodec.decodeV1(HostSupervisorRecordCodec.encode(failed)))
        assertEquals(LifecycleOutcome.FAILED, failed.latestTransaction?.outcome)
        assertEquals(LifecycleErrorCode.IO, failed.latestTransaction?.errorCode)
    }

    @Test
    fun `initial schema v1 transaction layout remains readable`() {
        val current = HostSupervisorState.safeDefaults().copy(
            latestTransaction = com.excp.podroid.vm.LifecycleTransaction(
                id = 1L,
                operation = LifecycleOperation.START,
                outcome = LifecycleOutcome.PENDING,
                requestedAtEpochMs = 1L,
                completedAtEpochMs = null,
                errorCode = null,
            ),
        )
        val legacy = HostSupervisorRecordCodec.encode(current)
            .substringBeforeLast("\ntx_effect_started=")

        val decoded = HostSupervisorRecordCodec.decodeV1(legacy)

        assertEquals(current, decoded)
        assertFalse(decoded.latestTransaction?.effectStarted == true)
    }

    @Test
    fun `unknown future schema fails closed without overwriting evidence`() = runBlocking {
        val evidence = "schema=2\nopaque=future-evidence"
        val store = FakeAtomicStore(evidence)
        val failure = runCatching { repository(store).snapshot() }.exceptionOrNull()

        assertTrue(failure is HostSupervisorSchemaException)
        assertEquals(evidence, store.raw)
        assertEquals(0, store.commits)
    }

    @Test
    fun `corrupt v1 fails closed without normalizing evidence`() = runBlocking {
        val valid = HostSupervisorRecordCodec.encode(HostSupervisorState.safeDefaults())
        val evidence = valid.replace("desired_state=STOPPED", "desired_state=maybe")
        val store = FakeAtomicStore(evidence)
        val failure = runCatching { repository(store).snapshot() }.exceptionOrNull()

        assertTrue(failure is HostSupervisorCorruptionException)
        assertEquals(evidence, store.raw)
        assertEquals(0, store.commits)
    }

    @Test
    fun `DataStore waits are bounded before lifecycle effects`() = runBlocking {
        val blockedStore = object : AtomicHostSupervisorRecordStore {
            override suspend fun read(): String? = null
            override suspend fun update(transform: (String?) -> String): String {
                delay(1_000)
                error("unreachable")
            }
        }
        val repository = HostSupervisorRepository(blockedStore, { 1L }, datastoreTimeoutMs = 10L)

        val failure = runCatching { repository.prepare(LifecycleOperation.START) }.exceptionOrNull()

        assertTrue(failure is TimeoutCancellationException)
    }

    @Test
    fun `crash point retains durable pending transaction before effect`() = runBlocking {
        val store = FakeAtomicStore()
        val repository = repository(store)

        repository.prepare(LifecycleOperation.RESTART)
        val recovered = repository(store).snapshot()

        assertEquals(VmDesiredState.RUNNING, recovered.desiredState)
        assertEquals(LifecycleOperation.RESTART, recovered.latestTransaction?.operation)
        assertEquals(LifecycleOutcome.PENDING, recovered.latestTransaction?.outcome)
        assertEquals(null, recovered.latestTransaction?.completedAtEpochMs)
    }

    @Test
    fun `concurrent commands allocate monotonic unique ids atomically`() = runBlocking {
        val store = FakeAtomicStore()
        val repository = repository(store)

        val ids = (1..100).map { index ->
            async(Dispatchers.Default) {
                repository.prepare(if (index % 2 == 0) LifecycleOperation.START else LifecycleOperation.STOP).id
            }
        }.awaitAll()

        assertEquals((1L..100L).toSet(), ids.toSet())
        assertEquals(100L, repository.snapshot().latestTransaction?.id)
    }

    @Test
    fun `accepted launch completion preserves newer transaction and accounts once`() = runBlocking {
        val repository = repository(FakeAtomicStore())
        val oldStart = repository.prepare(LifecycleOperation.START)
        assertTrue(repository.claim(oldStart))
        val newerStop = repository.prepare(LifecycleOperation.STOP)
        assertTrue(repository.claim(newerStop))

        assertFalse(repository.succeed(oldStart, runtimeStarted = true))
        val pendingStop = repository.snapshot()
        assertEquals(1L, pendingStop.runtimeGeneration)
        assertEquals(newerStop.id, pendingStop.latestTransaction?.id)
        assertEquals(LifecycleOutcome.PENDING, pendingStop.latestTransaction?.outcome)

        repository.succeed(newerStop)
        val completedAt = repository.snapshot().latestTransaction?.completedAtEpochMs
        repository.fail(newerStop, LifecycleErrorCode.UNKNOWN)
        val afterDuplicate = repository.snapshot()

        assertEquals(1L, afterDuplicate.runtimeGeneration)
        assertEquals(LifecycleOutcome.SUCCEEDED, afterDuplicate.latestTransaction?.outcome)
        assertEquals(completedAt, afterDuplicate.latestTransaction?.completedAtEpochMs)
        assertEquals(null, afterDuplicate.latestTransaction?.errorCode)
    }

    @Test
    fun `explicit stale generation is rejected before store mutation`() = runBlocking {
        val store = FakeAtomicStore()
        val repository = repository(store)
        repository.prepare(LifecycleOperation.START, expectedId = 1L)
        val commitsBeforeStale = store.commits

        val failure = runCatching {
            repository.prepare(LifecycleOperation.STOP, expectedId = 1L)
        }.exceptionOrNull()

        assertTrue(failure is com.excp.podroid.vm.StaleLifecycleCommandException)
        assertEquals(commitsBeforeStale, store.commits)
        assertEquals(LifecycleOperation.START, repository.snapshot().latestTransaction?.operation)
    }

    @Test
    fun `recreated token validates by id and closed operation and duplicates are stale`() = runBlocking {
        val repository = repository(FakeAtomicStore())
        val prepared = repository.prepare(LifecycleOperation.RESTART)
        val restored = com.excp.podroid.vm.LifecycleTransactionToken.restore(
            prepared.id,
            LifecycleOperation.RESTART,
            prepared.baseRuntimeGeneration,
        )

        assertTrue(repository.claim(restored))
        val recreated = repository(FakeAtomicStore(
            HostSupervisorRecordCodec.encode(repository.snapshot()),
        ))
        assertTrue(recreated.isCurrent(restored))
        assertFalse(recreated.claim(restored))
        assertFalse(repository.claim(
            com.excp.podroid.vm.LifecycleTransactionToken.restore(
                prepared.id,
                LifecycleOperation.START,
                prepared.baseRuntimeGeneration,
            ),
        ))
        assertFalse(repository.claim(
            com.excp.podroid.vm.LifecycleTransactionToken.restore(
                prepared.id,
                LifecycleOperation.RESTART,
                prepared.baseRuntimeGeneration + 1L,
            ),
        ))
        assertTrue(repository.succeed(restored, runtimeStarted = true))
        assertFalse(repository.isCurrent(restored))
        assertFalse(repository.claim(restored))
        assertFalse(repository.succeed(restored, runtimeStarted = true))
        assertEquals(1L, repository.snapshot().runtimeGeneration)
    }

    @Test
    fun `successful launches advance generation while non-launch outcomes preserve it`() = runBlocking {
        val repository = repository(FakeAtomicStore())
        val start = repository.prepare(LifecycleOperation.START)
        assertTrue(repository.claim(start))
        repository.succeed(start, runtimeStarted = true)
        val first = repository.snapshot()
        val stop = repository.prepare(LifecycleOperation.FORCE_STOP)
        assertTrue(repository.claim(stop))
        repository.succeed(stop)
        val stopped = repository.snapshot()
        val restart = repository.prepare(LifecycleOperation.RESTART)
        assertTrue(repository.claim(restart))
        repository.succeed(restart, runtimeStarted = true)

        assertEquals(1L, first.runtimeGeneration)
        assertEquals(1L, stopped.runtimeGeneration)
        assertEquals(2L, repository.snapshot().runtimeGeneration)
        assertEquals(VmDesiredState.RUNNING, repository.snapshot().desiredState)
    }

    private fun repository(store: FakeAtomicStore): HostSupervisorRepository {
        val clock = AtomicLong(1_700_000_000_000L)
        return HostSupervisorRepository(store, clock::getAndIncrement)
    }

    private class FakeAtomicStore(initial: String? = null) : AtomicHostSupervisorRecordStore {
        private val mutex = Mutex()
        var raw: String? = initial
            private set
        var commits: Int = 0
            private set

        override suspend fun read(): String? = mutex.withLock { raw }

        override suspend fun update(transform: (String?) -> String): String = mutex.withLock {
            val replacement = transform(raw)
            raw = replacement
            commits++
            replacement
        }
    }
}
