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
        val token = repository.begin(LifecycleOperation.START)
        val pending = repository.snapshot()

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
        val blockedStore = AtomicHostSupervisorRecordStore { _ ->
            delay(1_000)
            error("unreachable")
        }
        val repository = HostSupervisorRepository(blockedStore, { 1L }, datastoreTimeoutMs = 10L)

        val failure = runCatching { repository.begin(LifecycleOperation.START) }.exceptionOrNull()

        assertTrue(failure is TimeoutCancellationException)
    }

    @Test
    fun `crash point retains durable pending transaction before effect`() = runBlocking {
        val store = FakeAtomicStore()
        val repository = repository(store)

        repository.begin(LifecycleOperation.RESTART)
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
                repository.begin(if (index % 2 == 0) LifecycleOperation.START else LifecycleOperation.STOP).id
            }
        }.awaitAll()

        assertEquals((1L..100L).toSet(), ids.toSet())
        assertEquals(100L, repository.snapshot().latestTransaction?.id)
    }

    @Test
    fun `stale launch completion preserves newer transaction and advances generation monotonically`() = runBlocking {
        val repository = repository(FakeAtomicStore())
        val oldStart = repository.begin(LifecycleOperation.START)
        val newerStop = repository.begin(LifecycleOperation.STOP)

        repository.succeed(oldStart, runtimeStarted = true)
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
    fun `successful launches advance generation while non-launch outcomes preserve it`() = runBlocking {
        val repository = repository(FakeAtomicStore())
        val start = repository.begin(LifecycleOperation.START)
        repository.succeed(start, runtimeStarted = true)
        val first = repository.snapshot()
        val stop = repository.begin(LifecycleOperation.FORCE_STOP)
        repository.succeed(stop)
        val stopped = repository.snapshot()
        val restart = repository.begin(LifecycleOperation.RESTART)
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

        override suspend fun update(transform: (String?) -> String): String = mutex.withLock {
            val replacement = transform(raw)
            raw = replacement
            commits++
            replacement
        }
    }
}
