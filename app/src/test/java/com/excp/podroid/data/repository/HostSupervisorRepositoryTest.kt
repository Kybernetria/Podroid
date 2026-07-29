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
    fun `v0 absence initializes explicit safe v3 record`() = runBlocking {
        val store = FakeAtomicStore()
        val state = repository(store).snapshot()
        assertEquals(3, state.schemaVersion)
        assertFalse(state.hostEnabled)
        assertEquals(VmDesiredState.STOPPED, state.desiredState)
        assertEquals(ReconciliationMetadata.safeDefaults(), state.reconciliation)
        assertEquals(state, HostSupervisorRecordCodec.decodeV3(store.raw!!))
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

        assertEquals(3, migrated.schemaVersion)
        assertTrue(migrated.hostEnabled)
        assertTrue(migrated.autostart)
        assertEquals(7, migrated.runtimeGeneration)
        assertEquals(original.latestTransaction, migrated.latestTransaction)
        assertEquals(ReconciliationMetadata.safeDefaults(), migrated.reconciliation)
        assertTrue(migrated.runtimeMayBeLive)
        assertEquals(1L, migrated.runtimeEvidenceVersion)
        assertTrue(store.raw!!.startsWith("schema=3\n"))
        assertEquals(1, store.commits)
    }

    @Test
    fun `explicit v2 migration preserves reconciliation and initializes versioned possible-live evidence`() = runBlocking {
        val original = HostSupervisorState.safeDefaults().copy(
            hostEnabled = true,
            desiredState = VmDesiredState.RUNNING,
            reconciliation = ReconciliationMetadata(
                1, 6_000, ReconciliationTrigger.PROCESS_RESTART,
                ReconciliationOutcome.FAILED, LifecycleErrorCode.IO,
            ),
        )
        val store = FakeAtomicStore(HostSupervisorRecordCodec.encodeV2ForMigration(original))

        val migrated = repository(store).snapshot()

        assertEquals(3, migrated.schemaVersion)
        assertEquals(original.reconciliation, migrated.reconciliation)
        assertFalse(migrated.runtimeMayBeLive)
        assertEquals(0L, migrated.runtimeEvidenceVersion)
        assertTrue(store.raw!!.startsWith("schema=3\n"))
        assertEquals(1, store.commits)
    }

    @Test
    fun `legacy migrations conservatively derive runtime evidence from lifecycle matrix`() {
        data class MigrationCase(
            val name: String,
            val transaction: LifecycleTransaction,
            val runtimeMayBeLive: Boolean,
        )

        val cases = listOf(
            MigrationCase(
                "effect-started start",
                transaction(LifecycleOperation.START, LifecycleOutcome.PENDING, effectStarted = true),
                true,
            ),
            MigrationCase(
                "successful start",
                transaction(LifecycleOperation.START, LifecycleOutcome.SUCCEEDED),
                true,
            ),
            MigrationCase(
                "effect-started restart",
                transaction(LifecycleOperation.RESTART, LifecycleOutcome.PENDING, effectStarted = true),
                true,
            ),
            MigrationCase(
                "successful restart",
                transaction(LifecycleOperation.RESTART, LifecycleOutcome.SUCCEEDED),
                true,
            ),
            MigrationCase(
                "successful recover",
                transaction(LifecycleOperation.RECOVER, LifecycleOutcome.SUCCEEDED),
                true,
            ),
            MigrationCase(
                "failed stop",
                transaction(LifecycleOperation.STOP, LifecycleOutcome.FAILED),
                true,
            ),
            MigrationCase(
                "failed force stop",
                transaction(LifecycleOperation.FORCE_STOP, LifecycleOutcome.FAILED),
                true,
            ),
            MigrationCase(
                "unclaimed pending stop",
                transaction(LifecycleOperation.STOP, LifecycleOutcome.PENDING),
                true,
            ),
            MigrationCase(
                "successful stop",
                transaction(LifecycleOperation.STOP, LifecycleOutcome.SUCCEEDED),
                false,
            ),
            MigrationCase(
                "successful force stop",
                transaction(LifecycleOperation.FORCE_STOP, LifecycleOutcome.SUCCEEDED),
                false,
            ),
            MigrationCase(
                "effect-started setup",
                transaction(LifecycleOperation.SETUP, LifecycleOutcome.PENDING, effectStarted = true),
                false,
            ),
            MigrationCase(
                "successful remove",
                transaction(LifecycleOperation.REMOVE, LifecycleOutcome.SUCCEEDED),
                false,
            ),
        )
        val decoders = listOf<Pair<String, (HostSupervisorState) -> HostSupervisorState>>(
            "v1" to { state -> HostSupervisorRecordCodec.decodeV1(
                HostSupervisorRecordCodec.encodeV1ForMigration(state),
            ) },
            "v2" to { state -> HostSupervisorRecordCodec.decodeV2(
                HostSupervisorRecordCodec.encodeV2ForMigration(state),
            ) },
        )

        for ((schema, decode) in decoders) {
            for (case in cases) {
                val migrated = decode(HostSupervisorState.safeDefaults().copy(
                    latestTransaction = case.transaction,
                ))
                assertEquals("$schema ${case.name}", case.runtimeMayBeLive, migrated.runtimeMayBeLive)
                assertEquals(
                    "$schema ${case.name} evidence version",
                    if (case.runtimeMayBeLive) 1L else 0L,
                    migrated.runtimeEvidenceVersion,
                )
            }
        }
    }

    @Test
    fun `v2 attempting and interrupted reconciliation migrate as possible-live evidence`() {
        val reconciliationCases = listOf(
            ReconciliationMetadata(
                1, 0, ReconciliationTrigger.PROCESS_RESTART,
                ReconciliationOutcome.ATTEMPTING, null,
            ) to transaction(LifecycleOperation.RECOVER, LifecycleOutcome.SUCCEEDED),
            ReconciliationMetadata(
                1, 0, ReconciliationTrigger.PROCESS_RESTART,
                ReconciliationOutcome.INTERRUPTED, LifecycleErrorCode.PROCESS_DIED,
            ) to null,
        )

        for ((reconciliation, latestTransaction) in reconciliationCases) {
            val migrated = HostSupervisorRecordCodec.decodeV2(
                HostSupervisorRecordCodec.encodeV2ForMigration(
                    HostSupervisorState.safeDefaults().copy(
                        desiredState = VmDesiredState.RUNNING,
                        latestTransaction = latestTransaction,
                        reconciliation = reconciliation,
                    ),
                ),
            )
            assertTrue(reconciliation.lastOutcome.name, migrated.runtimeMayBeLive)
            assertEquals(reconciliation.lastOutcome.name, 1L, migrated.runtimeEvidenceVersion)
        }
    }

    @Test
    fun `v2 definitive successful stops override interrupted reconciliation evidence`() {
        val reconciliationCases = listOf(
            ReconciliationMetadata(
                1, 0, ReconciliationTrigger.PROCESS_RESTART,
                ReconciliationOutcome.ATTEMPTING, null,
            ),
            ReconciliationMetadata(
                1, 0, ReconciliationTrigger.PROCESS_RESTART,
                ReconciliationOutcome.INTERRUPTED, LifecycleErrorCode.PROCESS_DIED,
            ),
        )
        for (operation in listOf(LifecycleOperation.STOP, LifecycleOperation.FORCE_STOP)) {
            for (reconciliation in reconciliationCases) {
                val legacy = HostSupervisorState.safeDefaults().copy(
                    latestTransaction = transaction(operation, LifecycleOutcome.SUCCEEDED),
                    reconciliation = reconciliation,
                )

                val migrated = HostSupervisorRecordCodec.decodeV2(
                    HostSupervisorRecordCodec.encodeV2ForMigration(legacy),
                )

                assertFalse("$operation ${reconciliation.lastOutcome}", migrated.runtimeMayBeLive)
                assertEquals("$operation evidence version", 0L, migrated.runtimeEvidenceVersion)
            }
        }
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
        val evidence = "schema=4\nopaque=future-evidence"
        val store = FakeAtomicStore(evidence)
        val failure = runCatching { repository(store).snapshot() }.exceptionOrNull()
        assertTrue(failure is HostSupervisorSchemaException)
        assertEquals(evidence, store.raw)
        assertEquals(0, store.commits)
    }

    @Test
    fun `corrupt v3 fails closed without normalizing evidence`() = runBlocking {
        val evidence = HostSupervisorRecordCodec.encode(HostSupervisorState.safeDefaults())
            .replace("desired_state=STOPPED", "desired_state=maybe")
        val store = FakeAtomicStore(evidence)
        val failure = runCatching { repository(store).snapshot() }.exceptionOrNull()
        assertTrue(failure is HostSupervisorCorruptionException)
        assertEquals(evidence, store.raw)
        assertEquals(0, store.commits)
    }

    @Test
    fun `invalid reconciliation cross field combinations fail closed without rewrite`() = runBlocking {
        val base = HostSupervisorRecordCodec.encode(HostSupervisorState.safeDefaults())
        val invalidRecords = listOf(
            base.replace("reconcile_attempts=0", "reconcile_attempts=1"),
            base.replace("runtime_may_be_live=0", "runtime_may_be_live=1"),
            base.replace("reconcile_last_outcome=NEVER_RUN", "reconcile_last_outcome=ATTEMPTING"),
            base.replace("reconcile_last_outcome=NEVER_RUN", "reconcile_last_outcome=FAILED"),
            base.replace("reconcile_last_outcome=NEVER_RUN", "reconcile_last_outcome=BACKOFF"),
            base.replace("reconcile_last_outcome=NEVER_RUN", "reconcile_last_outcome=EXHAUSTED"),
        )
        for (evidence in invalidRecords) {
            val store = FakeAtomicStore(evidence)
            val failure = runCatching { repository(store).snapshot() }.exceptionOrNull()
            assertTrue(failure is HostSupervisorCorruptionException)
            assertEquals(evidence, store.raw)
            assertEquals(0, store.commits)
        }
    }

    @Test
    fun `possible-live failure is sticky against delayed superseded completion`() = runBlocking {
        val repository = enabledRunningRepository(AtomicLong(1_000))
        val attempt = repository.begin(ReconciliationTrigger.PROCESS_RESTART) as ReconciliationAdmission.Execute

        val failed = repository.finish(
            attempt.token,
            ReconciliationOutcome.FAILED,
            LifecycleErrorCode.RUNTIME_OWNERSHIP,
            runtimeMayBeLive = true,
        )
        val delayed = repository.finish(attempt.token, ReconciliationOutcome.SUPERSEDED)

        assertTrue(failed.runtimeMayBeLive)
        assertEquals(1L, failed.runtimeEvidenceVersion)
        assertTrue(delayed.runtimeMayBeLive)
        assertEquals(1L, delayed.runtimeEvidenceVersion)
        assertEquals(ReconciliationOutcome.FAILED, delayed.reconciliation.lastOutcome)
    }

    @Test
    fun `authoritative successful reconciliation clears versioned possible-live evidence`() = runBlocking {
        val clock = AtomicLong(1_000)
        val initial = HostSupervisorState.safeDefaults().copy(
            hostEnabled = true,
            desiredState = VmDesiredState.RUNNING,
            runtimeMayBeLive = true,
            runtimeEvidenceVersion = 4,
        )
        val repository = repository(FakeAtomicStore(HostSupervisorRecordCodec.encode(initial)), clock)
        val attempt = repository.begin(ReconciliationTrigger.PROCESS_RESTART) as ReconciliationAdmission.Execute

        val cleared = repository.finish(
            attempt.token,
            ReconciliationOutcome.SUCCEEDED,
            authoritativeRuntimeAbsence = true,
        )

        assertFalse(cleared.runtimeMayBeLive)
        assertEquals(5L, cleared.runtimeEvidenceVersion)
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
    fun `interrupted claimed stop becomes sticky cleanup evidence despite desired stopped`() = runBlocking {
        val clock = AtomicLong(100)
        val initial = HostSupervisorState.safeDefaults().copy(
            hostEnabled = true,
            desiredState = VmDesiredState.STOPPED,
            latestTransaction = LifecycleTransaction(
                8, LifecycleOperation.STOP, LifecycleOutcome.PENDING, 90, null, null, true,
            ),
        )
        val repository = repository(FakeAtomicStore(HostSupervisorRecordCodec.encode(initial)), clock)

        val admission = repository.begin(ReconciliationTrigger.PROCESS_RESTART)
        val state = repository.snapshot()

        assertTrue(admission is ReconciliationAdmission.Execute)
        assertTrue(state.runtimeMayBeLive)
        assertEquals(1L, state.runtimeEvidenceVersion)
        assertEquals(ReconciliationOutcome.ATTEMPTING, state.reconciliation.lastOutcome)
    }

    @Test
    fun `interrupted unclaimed force stop still requires fixed-runtime cleanup`() = runBlocking {
        val initial = HostSupervisorState.safeDefaults().copy(
            hostEnabled = true,
            desiredState = VmDesiredState.STOPPED,
            latestTransaction = LifecycleTransaction(
                8, LifecycleOperation.FORCE_STOP, LifecycleOutcome.PENDING, 90, null, null,
            ),
        )
        val repository = repository(
            FakeAtomicStore(HostSupervisorRecordCodec.encode(initial)),
            AtomicLong(100),
        )

        val admission = repository.begin(ReconciliationTrigger.PROCESS_RESTART)

        assertTrue(admission is ReconciliationAdmission.Execute)
        assertTrue(repository.snapshot().runtimeMayBeLive)
    }

    @Test
    fun `interrupted reconciliation after completed recover marks runtime possible live`() = runBlocking {
        val initial = HostSupervisorState.safeDefaults().copy(
            hostEnabled = true,
            desiredState = VmDesiredState.RUNNING,
            runtimeGeneration = 1,
            latestTransaction = LifecycleTransaction(
                9, LifecycleOperation.RECOVER, LifecycleOutcome.SUCCEEDED,
                90, 95, null, true,
            ),
            reconciliation = ReconciliationMetadata(
                1, 0, ReconciliationTrigger.PROCESS_RESTART,
                ReconciliationOutcome.ATTEMPTING, null,
            ),
        )
        val repository = repository(
            FakeAtomicStore(HostSupervisorRecordCodec.encode(initial)),
            AtomicLong(100),
        )

        repository.begin(ReconciliationTrigger.PROCESS_RESTART)

        val state = repository.snapshot()
        assertTrue(state.runtimeMayBeLive)
        assertEquals(1L, state.runtimeEvidenceVersion)
    }

    @Test
    fun `durable exponential backoff survives recovery lifecycle until matching finish`() = runBlocking {
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
        val start = repository.prepare(LifecycleOperation.RECOVER, second.token.expectedNextTransactionId)
        assertTrue(repository.claim(start))
        assertTrue(repository.succeed(start, runtimeStarted = true))
        val beforeFinish = repository.snapshot().reconciliation
        assertEquals(2, beforeFinish.consecutiveAttempts)
        assertEquals(ReconciliationOutcome.ATTEMPTING, beforeFinish.lastOutcome)

        repository.finish(second.token, ReconciliationOutcome.SUCCEEDED)
        val reset = repository.snapshot().reconciliation
        assertEquals(0, reset.consecutiveAttempts)
        assertEquals(0, reset.nextEligibleEpochMs)
        assertEquals(ReconciliationOutcome.SUCCEEDED, reset.lastOutcome)
    }

    @Test
    fun `explicit user start success resets an existing backoff budget`() = runBlocking {
        val clock = AtomicLong(1_000)
        val repository = enabledRunningRepository(clock)
        val attempt = repository.begin(ReconciliationTrigger.PROCESS_RESTART) as ReconciliationAdmission.Execute
        repository.finish(attempt.token, ReconciliationOutcome.FAILED, LifecycleErrorCode.IO)

        val start = repository.prepare(LifecycleOperation.START)
        assertTrue(repository.claim(start))
        assertTrue(repository.succeed(start, runtimeStarted = true))

        assertEquals(ReconciliationMetadata.safeDefaults(), repository.snapshot().reconciliation)
    }

    @Test
    fun `crash after each recovery runtime start keeps attempts and eventually exhausts`() = runBlocking {
        val repository = enabledRunningRepository(AtomicLong(1_000))
        repeat(ReconciliationMetadata.MAX_ATTEMPTS) { index ->
            val admission = repository.begin(ReconciliationTrigger.PROCESS_RESTART) as ReconciliationAdmission.Execute
            assertEquals(index + 1, admission.token.attempt)
            val recovery = repository.prepare(
                LifecycleOperation.RECOVER,
                admission.token.expectedNextTransactionId,
            )
            assertTrue(repository.claim(recovery))
            assertTrue(repository.succeed(recovery, runtimeStarted = true))
            assertEquals(index + 1, repository.snapshot().reconciliation.consecutiveAttempts)
            // Simulate process death before finishReconciliation(SUCCEEDED).
        }
        val exhausted = repository.begin(ReconciliationTrigger.PROCESS_RESTART) as ReconciliationAdmission.Skip
        assertEquals(ReconciliationOutcome.EXHAUSTED, exhausted.outcome)
        assertEquals(ReconciliationMetadata.MAX_ATTEMPTS, repository.snapshot().reconciliation.consecutiveAttempts)
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
    fun `explicit stop failure sticks possible-live until a later definitive stop succeeds`() = runBlocking {
        val repository = enabledRunningRepository(AtomicLong(1_000))
        val failedStop = repository.prepare(LifecycleOperation.STOP)
        assertTrue(repository.claim(failedStop))
        assertTrue(repository.fail(failedStop, LifecycleErrorCode.RUNTIME_OWNERSHIP))
        val failed = repository.snapshot()
        assertTrue(failed.runtimeMayBeLive)
        assertEquals(1L, failed.runtimeEvidenceVersion)

        val retry = repository.prepare(LifecycleOperation.FORCE_STOP)
        assertTrue(repository.claim(retry))
        assertTrue(repository.succeed(retry))
        val stopped = repository.snapshot()
        assertFalse(stopped.runtimeMayBeLive)
        assertEquals(2L, stopped.runtimeEvidenceVersion)
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

    private fun transaction(
        operation: LifecycleOperation,
        outcome: LifecycleOutcome,
        effectStarted: Boolean = outcome != LifecycleOutcome.PENDING,
    ): LifecycleTransaction = LifecycleTransaction(
        id = 1,
        operation = operation,
        outcome = outcome,
        requestedAtEpochMs = 1,
        completedAtEpochMs = if (outcome == LifecycleOutcome.PENDING) null else 2,
        errorCode = if (outcome == LifecycleOutcome.FAILED) LifecycleErrorCode.IO else null,
        effectStarted = effectStarted,
    )

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
