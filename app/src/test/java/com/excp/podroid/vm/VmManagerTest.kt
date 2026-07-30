package com.excp.podroid.vm

import com.excp.podroid.data.repository.AtomicHostSupervisorRecordStore
import com.excp.podroid.data.repository.HostSupervisorRepository
import com.excp.podroid.engine.ExactValueStateFlow
import com.excp.podroid.engine.VmConfig
import com.excp.podroid.engine.VmState
import com.excp.podroid.profiles.ActivationState
import com.excp.podroid.profiles.DataDeletionConfirmation
import com.excp.podroid.profiles.GuestDataPolicy
import com.excp.podroid.profiles.PreparedProfileCandidate
import com.excp.podroid.profiles.ProfileGeneration
import com.excp.podroid.profiles.ProfileId
import com.excp.podroid.profiles.ProfileBackend
import com.excp.podroid.profiles.ProfileLifecycleStore
import com.excp.podroid.profiles.Sha256Digest
import com.excp.podroid.profiles.SigningKeyId
import com.excp.podroid.profiles.TrustEpoch
import com.excp.podroid.service.ServiceLaunchCoordinator
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VmManagerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val scopes = mutableListOf<CoroutineScope>()

    @After fun tearDown() = scopes.forEach { it.cancel() }

    @Test
    fun `list and status map the default runtime without exposing engine state`() = runBlocking {
        val runtime = FakeRuntime().apply { state.value = VmState.Error("boom") }
        val files = FakeFiles(installed = true)
        val manager = manager(runtime = runtime, files = files)

        assertEquals(
            listOf(VmSummary(VmId.DEFAULT, true, VmLifecycleState.ERROR)),
            manager.list(VmId.DEFAULT),
        )
        assertEquals(VmLifecycleState.ERROR, manager.lifecycle(VmId.DEFAULT).value)
        assertEquals("boom", manager.status(VmId.DEFAULT).errorMessage)
        assertEquals("qemu", manager.status(VmId.DEFAULT).backendId)
    }

    @Test
    fun `profile activation shares lifecycle serialization and asset lease with launch`() = runBlocking {
        val runtime = FakeRuntime()
        val activationEntered = CompletableDeferred<Unit>()
        val releaseActivation = CompletableDeferred<Unit>()
        var leaseHeld = false
        val installer = object : TestInstaller() {
            override suspend fun install(vmId: VmId) = Unit
            override suspend fun <T> withExclusiveTree(
                vmId: VmId,
                action: suspend (VmAssetTreeLease) -> T,
            ): T {
                check(!leaseHeld)
                leaseHeld = true
                return try { super.withExclusiveTree(vmId, action) } finally { leaseHeld = false }
            }
        }
        val profileStore = FakeProfileLifecycleStore(
            onInstall = {
                assertTrue(leaseHeld)
                activationEntered.complete(Unit)
                releaseActivation.await()
            },
        )
        val manager = manager(
            runtime = runtime,
            installer = installer,
            profileLifecycleStore = profileStore,
        )

        val activation = async(start = CoroutineStart.UNDISPATCHED) {
            manager.activateProfile(profileCandidate(), GuestDataPolicy.PRESERVE_DATA)
        }
        activationEntered.await()
        val launch = async { manager.start(VmId.DEFAULT) }
        delay(30)
        assertEquals(0, runtime.startCalls)

        releaseActivation.complete(Unit)
        activation.await()
        launch.await()
        assertEquals(1, profileStore.installCalls)
        assertEquals(1, runtime.startCalls)
    }

    @Test
    fun `any QEMU-only prepared profile is rejected on AVF before lifecycle store effects`() = runBlocking {
        val store = FakeProfileLifecycleStore(supportedBackends = setOf(ProfileBackend.QEMU))
        val manager = manager(runtime = FakeRuntime("avf"), profileLifecycleStore = store)

        try {
            manager.activateProfile(profileCandidate(), GuestDataPolicy.DELETE_DATA)
            fail("Expected backend contract rejection")
        } catch (_: com.excp.podroid.profiles.ProfileActivationException) {
            Unit
        }

        assertEquals(0, store.installCalls)
    }

    @Test
    fun `backend selection claim awaits cold resolution before contract inspection`() = runBlocking {
        val runtime = FakeRuntime("qemu").apply {
            coldResolvedBackendId = "avf"
        }
        val store = FakeProfileLifecycleStore(supportedBackends = setOf(ProfileBackend.AVF))
        val manager = manager(runtime = runtime, profileLifecycleStore = store)

        val result = manager.activateProfile(profileCandidate(), GuestDataPolicy.PRESERVE_DATA)

        assertEquals(profileCandidate(), result.active)
        assertEquals("avf", runtime.backendId)
        assertEquals(1, store.installCalls)
    }

    @Test
    fun `QEMU to AVF swap waits until profile activation releases backend selection claim`() = runBlocking {
        val runtime = FakeRuntime("qemu")
        val effectEntered = CompletableDeferred<Unit>()
        val releaseEffect = CompletableDeferred<Unit>()
        val store = FakeProfileLifecycleStore(
            supportedBackends = setOf(ProfileBackend.QEMU),
            onInstall = {
                effectEntered.complete(Unit)
                releaseEffect.await()
            },
        )
        val manager = manager(runtime = runtime, profileLifecycleStore = store)

        val activation = async { manager.activateProfile(profileCandidate(), GuestDataPolicy.PRESERVE_DATA) }
        effectEntered.await()
        val swap = async { runtime.swapBackend("avf") }
        delay(30)
        assertFalse(swap.isCompleted)
        assertEquals("qemu", runtime.backendId)

        releaseEffect.complete(Unit)
        activation.await()
        swap.await()
        assertEquals("avf", runtime.backendId)
    }

    @Test
    fun `profile rollback uses stopped-runtime preflight and the same lifecycle store`() = runBlocking {
        val candidate = profileCandidate()
        val qemu = CountingProbe(RuntimeBackend.QEMU, RuntimeProbeResult.Absent)
        val avf = CountingProbe(RuntimeBackend.AVF, RuntimeProbeResult.Absent)
        var leaseHeld = false
        val installer = object : TestInstaller() {
            override suspend fun install(vmId: VmId) = Unit
            override suspend fun <T> withExclusiveTree(
                vmId: VmId,
                action: suspend (VmAssetTreeLease) -> T,
            ): T {
                leaseHeld = true
                return try { super.withExclusiveTree(vmId, action) } finally { leaseHeld = false }
            }
        }
        val store = FakeProfileLifecycleStore(
            onRollback = { assertTrue(leaseHeld) },
            rollbackResult = ActivationState(4, candidate, null),
        )
        val manager = manager(
            installer = installer,
            profileLifecycleStore = store,
            runtimePreflight = RuntimePreflightCoordinator(qemu, avf) {},
        )

        val result = manager.rollbackProfile(3, GuestDataPolicy.PRESERVE_DATA)

        assertEquals(4, result.activationSequence)
        assertEquals(1, store.rollbackCalls)
        assertEquals(1, qemu.probeCalls)
        assertEquals(1, avf.probeCalls)
    }

    @Test
    fun `profile activation rejects active runtime and failed fixed-runtime preflight`() = runBlocking {
        val activeRuntime = FakeRuntime().apply {
            state.value = VmState.Running
            quiescent.value = false
        }
        val activeStore = FakeProfileLifecycleStore()
        val activeManager = manager(runtime = activeRuntime, profileLifecycleStore = activeStore)
        try {
            activeManager.activateProfile(profileCandidate(), GuestDataPolicy.PRESERVE_DATA)
            fail("Expected active runtime rejection")
        } catch (_: IllegalStateException) {
            Unit
        }
        assertEquals(0, activeStore.installCalls)

        val uncertain = CountingProbe(
            RuntimeBackend.QEMU,
            RuntimeProbeResult.Uncertain(LifecycleErrorCode.RUNTIME_OWNERSHIP, true),
        )
        val stoppedStore = FakeProfileLifecycleStore()
        val stoppedManager = manager(
            profileLifecycleStore = stoppedStore,
            runtimePreflight = RuntimePreflightCoordinator(
                uncertain,
                CountingProbe(RuntimeBackend.AVF, RuntimeProbeResult.Absent),
            ) {},
        )
        try {
            stoppedManager.activateProfile(profileCandidate(), GuestDataPolicy.PRESERVE_DATA)
            fail("Expected fixed-runtime preflight rejection")
        } catch (_: RuntimeProbeException) {
            Unit
        }
        assertEquals(0, stoppedStore.installCalls)
    }

    @Test
    fun `setup enables Host and commits a bounded successful transaction`() = runBlocking {
        val supervisor = FakeSupervisor()
        val manager = manager(supervisor = supervisor)

        manager.ensureInstalled(VmId.DEFAULT)

        val state = manager.supervisorState(VmId.DEFAULT)
        assertTrue(state.hostEnabled)
        assertEquals(VmDesiredState.STOPPED, state.desiredState)
        assertEquals(LifecycleOperation.SETUP, state.latestTransaction?.operation)
        assertEquals(LifecycleOutcome.SUCCEEDED, state.latestTransaction?.outcome)
    }

    @Test
    fun `pending desired state is durable before backend start effect`() = runBlocking {
        val supervisor = FakeSupervisor()
        val runtime = FakeRuntime().apply {
            onStart = {
                val duringEffect = supervisor.snapshot()
                assertEquals(VmDesiredState.RUNNING, duringEffect.desiredState)
                assertEquals(LifecycleOutcome.PENDING, duringEffect.latestTransaction?.outcome)
                state.value = VmState.Running
            }
        }

        manager(runtime = runtime, supervisor = supervisor).start(VmId.DEFAULT)

        assertEquals(LifecycleOutcome.SUCCEEDED, supervisor.snapshot().latestTransaction?.outcome)
    }

    @Test
    fun `start and stop persist desired state around effects and generation is monotonic`() = runBlocking {
        val supervisor = FakeSupervisor()
        val runtime = FakeRuntime()
        val manager = manager(runtime = runtime, supervisor = supervisor)

        manager.start(VmId.DEFAULT)
        val running = manager.supervisorState(VmId.DEFAULT)
        assertEquals(VmDesiredState.RUNNING, running.desiredState)
        assertEquals(1L, running.runtimeGeneration)
        assertEquals(LifecycleOutcome.SUCCEEDED, running.latestTransaction?.outcome)

        manager.stop(VmId.DEFAULT)
        val stopped = manager.supervisorState(VmId.DEFAULT)
        assertEquals(VmDesiredState.STOPPED, stopped.desiredState)
        assertEquals(1L, stopped.runtimeGeneration)
        assertEquals(LifecycleOperation.STOP, stopped.latestTransaction?.operation)
        assertEquals(LifecycleOutcome.SUCCEEDED, stopped.latestTransaction?.outcome)
    }

    @Test
    fun `lifecycle failure persists only a stable redacted code`() = runBlocking {
        val supervisor = FakeSupervisor()
        val runtime = FakeRuntime().apply { onStart = { throw IOException("secret /private/path") } }
        val manager = manager(runtime = runtime, supervisor = supervisor)

        runCatching { manager.start(VmId.DEFAULT) }

        val transaction = manager.supervisorState(VmId.DEFAULT).latestTransaction
        assertEquals(LifecycleOutcome.FAILED, transaction?.outcome)
        assertEquals(LifecycleErrorCode.IO, transaction?.errorCode)
    }

    @Test
    fun `invalid configured boot artifacts block launch before runtime start`() = runBlocking {
        val runtime = FakeRuntime()
        val configuration = FakeConfiguration(
            launchFailure = IOException("configured active profile is invalid"),
        )
        val manager = manager(runtime = runtime, configuration = configuration)

        val failure = runCatching { manager.start(VmId.DEFAULT) }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals(0, runtime.startCalls)
        assertTrue(runtime.quiescent.value)
        assertEquals(
            LifecycleOutcome.FAILED,
            manager.supervisorState(VmId.DEFAULT).latestTransaction?.outcome,
        )
    }

    @Test
    fun `concurrent ensure installed is serialized and duplicate is idempotent`() = runBlocking {
        val files = FakeFiles(installed = false)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val active = AtomicInteger()
        var maxActive = 0
        var calls = 0
        val installer = object : TestInstaller() {
            override suspend fun install(vmId: VmId) {
                calls++
                maxActive = maxOf(maxActive, active.incrementAndGet())
                entered.complete(Unit)
                release.await()
                files.installed = true
                active.decrementAndGet()
            }
        }
        val manager = manager(files = files, installer = installer)

        val first = async(Dispatchers.Default) { manager.ensureInstalled(VmId.DEFAULT) }
        entered.await()
        val duplicate = async(Dispatchers.Default) { manager.ensureInstalled(VmId.DEFAULT) }
        delay(30)
        assertEquals(1, calls)
        release.complete(Unit)
        first.await()
        duplicate.await()

        assertEquals(1, calls)
        assertEquals(1, maxActive)
    }

    @Test
    fun `installation fails closed while active`() = runBlocking {
        val runtime = FakeRuntime().apply {
            state.value = VmState.Running
            quiescent.value = false
        }
        var installCalls = 0
        val manager = manager(
            runtime = runtime,
            installer = object : TestInstaller() {
                override suspend fun install(vmId: VmId) { installCalls++ }
            },
        )

        expectFailure<IllegalStateException> {
            runBlocking { manager.ensureInstalled(VmId.DEFAULT) }
        }
        assertEquals(0, installCalls)
    }

    @Test
    fun `duplicate starts launch at most one active VM`() = runBlocking {
        val runtime = FakeRuntime()
        val startEntered = CompletableDeferred<Unit>()
        val releaseStart = CompletableDeferred<Unit>()
        runtime.onStart = {
            runtime.state.value = VmState.Starting
            startEntered.complete(Unit)
            releaseStart.await()
            runtime.state.value = VmState.Running
        }
        val manager = manager(runtime = runtime)

        val first = async(Dispatchers.Default) { manager.start(VmId.DEFAULT) }
        startEntered.await()
        first.await()
        manager.start(VmId.DEFAULT)
        releaseStart.complete(Unit)

        assertEquals(1, runtime.startCalls)
        assertTrue(runtime.maxConcurrentStarts <= 1)
    }

    @Test
    fun `start fails when backend returns without becoming active`() {
        val runtime = FakeRuntime().apply {
            onStart = { state.value = VmState.Idle; quiescent.value = true }
        }
        val manager = manager(runtime = runtime)

        expectFailure<IllegalStateException> {
            runBlocking { manager.start(VmId.DEFAULT) }
        }
        runBlocking {
            repeat(20) {
                if (manager.lifecycle(VmId.DEFAULT).value == VmLifecycleState.IDLE) return@runBlocking
                delay(5)
            }
        }
        assertEquals(VmLifecycleState.IDLE, manager.lifecycle(VmId.DEFAULT).value)
    }

    @Test
    fun `explicit stop preflights every fixed runtime even when in process runtime is idle`() = runBlocking {
        val qemu = CountingProbe(RuntimeBackend.QEMU, RuntimeProbeResult.Absent)
        val avf = CountingProbe(RuntimeBackend.AVF, RuntimeProbeResult.Absent)
        val manager = manager(runtimePreflight = RuntimePreflightCoordinator(qemu, avf) {})

        manager.stop(VmId.DEFAULT)

        assertEquals(1, qemu.probeCalls)
        assertEquals(1, avf.probeCalls)
    }

    @Test
    fun `explicit force stop fails closed when fixed runtime ownership is uncertain`() = runBlocking {
        val qemu = CountingProbe(
            RuntimeBackend.QEMU,
            RuntimeProbeResult.Uncertain(
                LifecycleErrorCode.RUNTIME_OWNERSHIP,
                runtimeMayBeLive = true,
            ),
        )
        val avf = CountingProbe(RuntimeBackend.AVF, RuntimeProbeResult.Absent)
        val manager = manager(runtimePreflight = RuntimePreflightCoordinator(qemu, avf) {})

        val failure = runCatching { manager.forceStop(VmId.DEFAULT) }.exceptionOrNull()

        assertTrue(failure is RuntimeProbeException)
        assertEquals(1, qemu.probeCalls)
        assertEquals(0, avf.probeCalls)
        assertEquals(LifecycleOutcome.FAILED,
            manager.supervisorState(VmId.DEFAULT).latestTransaction?.outcome)
    }

    @Test
    fun `graceful QEMU stop uses typed powerdown before bounded inherited escalation`() = runBlocking {
        val runtime = FakeRuntime().apply {
            state.value = VmState.Running
            quiescent.value = false
            qmpAvailableValue = true
            onStop = { state.value = VmState.Stopped; quiescent.value = true }
        }
        val manager = manager(runtime = runtime, guestTimeoutMs = 20)

        manager.stop(VmId.DEFAULT)

        assertEquals(1, runtime.powerdownCalls)
        assertEquals(1, runtime.stopCalls)
        assertEquals(0, runtime.forceStopCalls)
        assertEquals(VmState.Stopped, runtime.state.value)
    }

    @Test
    fun `graceful powerdown completion avoids process stop escalation`() = runBlocking {
        val runtime = FakeRuntime().apply {
            state.value = VmState.Running
            quiescent.value = false
            qmpAvailableValue = true
            onPowerdown = { state.value = VmState.Stopped; quiescent.value = true; Result.success(Unit) }
        }
        val manager = manager(runtime = runtime)

        manager.stop(VmId.DEFAULT)

        assertEquals(1, runtime.powerdownCalls)
        assertEquals(0, runtime.stopCalls)
        assertEquals(0, runtime.forceStopCalls)
    }

    @Test
    fun `restart stops then starts under the lifecycle policy`() = runBlocking {
        val runtime = FakeRuntime().apply {
            state.value = VmState.Running
            quiescent.value = false
        }
        val manager = manager(runtime = runtime)

        manager.restart(VmId.DEFAULT)

        assertEquals(1, runtime.stopCalls)
        assertEquals(1, runtime.startCalls)
        assertEquals(VmState.Running, runtime.state.value)
    }

    @Test
    fun `restart stays one pending token across shutdown and completes replacement once`() = runBlocking {
        val supervisor = FakeSupervisor()
        val stopped = CompletableDeferred<Unit>()
        val releaseReplacement = CompletableDeferred<Unit>()
        val runtime = FakeRuntime().apply {
            state.value = VmState.Running
            quiescent.value = false
            onStop = {
                state.value = VmState.Stopped
                quiescent.value = true
                stopped.complete(Unit)
            }
            onStart = {
                releaseReplacement.await()
                state.value = VmState.Running
            }
        }
        val manager = manager(runtime = runtime, supervisor = supervisor)
        val restart = async(Dispatchers.Default) { manager.restart(VmId.DEFAULT) }

        stopped.await()
        val betweenPhases = manager.supervisorState(VmId.DEFAULT)
        assertEquals(VmDesiredState.RUNNING, betweenPhases.desiredState)
        assertEquals(LifecycleOperation.RESTART, betweenPhases.latestTransaction?.operation)
        assertEquals(LifecycleOutcome.PENDING, betweenPhases.latestTransaction?.outcome)
        val transactionId = betweenPhases.latestTransaction?.id

        releaseReplacement.complete(Unit)
        restart.await()
        val restarted = manager.supervisorState(VmId.DEFAULT)
        assertEquals(transactionId, restarted.latestTransaction?.id)
        assertEquals(LifecycleOutcome.SUCCEEDED, restarted.latestTransaction?.outcome)
        assertEquals(1L, restarted.runtimeGeneration)

        supervisor.succeed(
            LifecycleTransactionToken.restore(transactionId!!, LifecycleOperation.RESTART, 0L),
            runtimeStarted = true,
        )
        assertEquals(1L, manager.supervisorState(VmId.DEFAULT).runtimeGeneration)
    }

    @Test
    fun `newer stop prepared first makes restart stale at final launch gate`() = runBlocking {
        val supervisor = FakeSupervisor()
        val handoffPaused = CompletableDeferred<Unit>()
        val releaseHandoff = CompletableDeferred<Unit>()
        val stopPrepareEntered = CompletableDeferred<Unit>()
        val releaseStopPrepare = CompletableDeferred<Unit>()
        supervisor.onPrepare = { operation ->
            if (operation == LifecycleOperation.STOP) {
                stopPrepareEntered.complete(Unit)
                releaseStopPrepare.await()
            }
        }
        val runtime = FakeRuntime().apply {
            state.value = VmState.Running
            quiescent.value = false
        }
        val manager = manager(
            runtime = runtime,
            supervisor = supervisor,
            beforeFinalLaunchAuthorization = { command ->
                if (command.operation == LifecycleOperation.RESTART) {
                    handoffPaused.complete(Unit)
                    releaseHandoff.await()
                }
            },
        )
        val restart = async(Dispatchers.Default) {
            runCatching { manager.restart(VmId.DEFAULT) }
        }
        handoffPaused.await()

        val newerStop = async(Dispatchers.Default) {
            manager.prepareLifecycleCommand(VmId.DEFAULT, LifecycleOperation.STOP)
        }
        stopPrepareEntered.await() // STOP owns the authority gate inside prepare.
        releaseHandoff.complete(Unit) // Restart now waits for that same final gate.
        assertEquals(0, runtime.startCalls)

        releaseStopPrepare.complete(Unit)
        val stop = newerStop.await()
        val restartFailure = restart.await().exceptionOrNull()

        assertTrue(restartFailure is StaleLifecycleCommandException)
        assertEquals(LifecycleOperation.STOP, stop.operation)
        assertEquals(0, runtime.startCalls)
        assertEquals(VmDesiredState.STOPPED, supervisor.snapshot().desiredState)
        assertEquals(LifecycleOutcome.PENDING, supervisor.snapshot().latestTransaction?.outcome)
    }

    @Test
    fun `launch authorization excludes stop prepare until acceptance then direct restart is superseded`() = runBlocking {
        val supervisor = FakeSupervisor()
        val handoffReached = CompletableDeferred<Unit>()
        val launchEntered = CompletableDeferred<Unit>()
        val allowAcceptance = CompletableDeferred<Unit>()
        val releaseRuntimeStart = CompletableDeferred<Unit>()
        val stopPrepareEntered = CompletableDeferred<Unit>()
        supervisor.onPrepare = { operation ->
            if (operation == LifecycleOperation.STOP) stopPrepareEntered.complete(Unit)
        }
        val runtime = FakeRuntime().apply {
            state.value = VmState.Running
            quiescent.value = false
            onStart = {
                launchEntered.complete(Unit)
                allowAcceptance.await()
                state.value = VmState.Starting
                releaseRuntimeStart.await()
            }
        }
        val manager = manager(
            runtime = runtime,
            supervisor = supervisor,
            beforeFinalLaunchAuthorization = { command ->
                if (command.operation == LifecycleOperation.RESTART) handoffReached.complete(Unit)
            },
        )
        val restart = async(Dispatchers.Default) {
            runCatching { manager.restart(VmId.DEFAULT) }
        }
        handoffReached.await()
        launchEntered.await()

        // Unconfined enters prepare synchronously until the held authority gate
        // suspends it, making the exclusion assertion scheduler-independent.
        val newerStop = async(Dispatchers.Unconfined) {
            manager.prepareLifecycleCommand(VmId.DEFAULT, LifecycleOperation.STOP)
        }
        assertFalse("STOP prepare must wait while launch acceptance owns authority", stopPrepareEntered.isCompleted)

        allowAcceptance.complete(Unit)
        stopPrepareEntered.await()
        val stop = newerStop.await()
        assertEquals(LifecycleOperation.STOP, stop.operation)
        assertEquals(1, runtime.startCalls)

        assertTrue(manager.executePrepared(VmId.DEFAULT, stop))
        releaseRuntimeStart.complete(Unit)
        assertTrue(restart.await().exceptionOrNull() is StaleLifecycleCommandException)
        assertEquals(2, runtime.stopCalls) // Restart shutdown plus the newer STOP.
        assertTrue(runtime.quiescent.value)
    }

    @Test
    fun `duplicate restart while replacement is starting is idempotent`() = runBlocking {
        val runtime = FakeRuntime().apply {
            state.value = VmState.Starting
            quiescent.value = false
        }
        val manager = manager(runtime = runtime)

        manager.restart(VmId.DEFAULT)

        assertEquals(0, runtime.stopCalls)
        assertEquals(0, runtime.startCalls)
    }

    @Test
    fun `stale prepared stop cannot override a newer restart`() = runBlocking {
        val supervisor = FakeSupervisor()
        val runtime = FakeRuntime().apply {
            state.value = VmState.Running
            quiescent.value = false
        }
        val manager = manager(runtime = runtime, supervisor = supervisor)
        val delayedStop = manager.prepareLifecycleCommand(
            VmId.DEFAULT,
            LifecycleOperation.STOP,
            expectedCommandGeneration = 1L,
        )
        val newerRestart = manager.prepareLifecycleCommand(
            VmId.DEFAULT,
            LifecycleOperation.RESTART,
            expectedCommandGeneration = 2L,
        )

        assertFalse(manager.executePrepared(VmId.DEFAULT, delayedStop))
        assertEquals(0, runtime.stopCalls)
        assertTrue(manager.executePrepared(VmId.DEFAULT, newerRestart))

        val state = manager.supervisorState(VmId.DEFAULT)
        assertEquals(newerRestart.id, state.latestTransaction?.id)
        assertEquals(LifecycleOutcome.SUCCEEDED, state.latestTransaction?.outcome)
        assertEquals(1, runtime.stopCalls)
        assertEquals(1, runtime.startCalls)
        assertEquals(1L, state.runtimeGeneration)
    }

    @Test
    fun `newer start prepared before final stop admission prevents backend stop`() = runBlocking {
        val supervisor = FakeSupervisor()
        val stopAtFence = CompletableDeferred<Unit>()
        val releaseStopFence = CompletableDeferred<Unit>()
        val runtime = FakeRuntime().apply {
            state.value = VmState.Running
            quiescent.value = false
        }
        val manager = manager(
            runtime = runtime,
            supervisor = supervisor,
            beforeFinalStopAuthorization = { command ->
                if (command.operation == LifecycleOperation.STOP) {
                    stopAtFence.complete(Unit)
                    releaseStopFence.await()
                }
            },
        )
        val stop = manager.prepareLifecycleCommand(VmId.DEFAULT, LifecycleOperation.STOP, 1L)
        val stopExecution = async(Dispatchers.Default) {
            manager.executePrepared(VmId.DEFAULT, stop)
        }
        stopAtFence.await()

        val newerStart = manager.prepareLifecycleCommand(
            VmId.DEFAULT,
            LifecycleOperation.START,
            2L,
        )
        releaseStopFence.complete(Unit)

        assertFalse(stopExecution.await())
        assertEquals(0, runtime.stopCalls)
        assertEquals(0, runtime.forceStopCalls)
        assertEquals(newerStart.id, supervisor.snapshot().latestTransaction?.id)
        assertEquals(VmDesiredState.RUNNING, supervisor.snapshot().desiredState)
    }

    @Test
    fun `newer start prepared before final force admission prevents backend force`() = runBlocking {
        val supervisor = FakeSupervisor()
        val forceAtFence = CompletableDeferred<Unit>()
        val releaseForceFence = CompletableDeferred<Unit>()
        val runtime = FakeRuntime().apply {
            state.value = VmState.Running
            quiescent.value = false
        }
        val manager = manager(
            runtime = runtime,
            supervisor = supervisor,
            beforeFinalStopAuthorization = { command ->
                if (command.operation == LifecycleOperation.FORCE_STOP) {
                    forceAtFence.complete(Unit)
                    releaseForceFence.await()
                }
            },
        )
        val force = manager.prepareLifecycleCommand(VmId.DEFAULT, LifecycleOperation.FORCE_STOP, 1L)
        val forceExecution = async(Dispatchers.Default) {
            manager.executePrepared(VmId.DEFAULT, force)
        }
        forceAtFence.await()

        manager.prepareLifecycleCommand(VmId.DEFAULT, LifecycleOperation.START, 2L)
        releaseForceFence.complete(Unit)

        assertFalse(forceExecution.await())
        assertEquals(0, runtime.forceStopCalls)
        assertEquals(0, runtime.stopCalls)
    }

    @Test
    fun `newer start after admitted stop waits without holding authority then launches once`() = runBlocking {
        val supervisor = FakeSupervisor()
        val stopTaskEstablished = CompletableDeferred<Unit>()
        val releaseStopTask = CompletableDeferred<Unit>()
        val runtime = FakeRuntime().apply {
            state.value = VmState.Running
            quiescent.value = false
        }
        val manager = manager(
            runtime = runtime,
            supervisor = supervisor,
            beforeCoordinatedStop = {
                stopTaskEstablished.complete(Unit)
                releaseStopTask.await()
            },
        )
        val stop = manager.prepareLifecycleCommand(VmId.DEFAULT, LifecycleOperation.STOP, 1L)
        val stopExecution = async(Dispatchers.Default) {
            manager.executePrepared(VmId.DEFAULT, stop)
        }
        stopTaskEstablished.await()

        // Preparation must complete after task admission but before that task
        // can issue runtime.stop(): command authority is not held for cleanup.
        val newerStart = manager.prepareLifecycleCommand(
            VmId.DEFAULT,
            LifecycleOperation.START,
            2L,
        )
        val startExecution = async(Dispatchers.Unconfined) {
            manager.executePrepared(VmId.DEFAULT, newerStart)
        }
        assertFalse(startExecution.isCompleted)
        assertEquals(0, runtime.stopCalls)
        assertEquals(0, runtime.startCalls)

        releaseStopTask.complete(Unit)
        stopExecution.await()
        assertTrue(startExecution.await())

        assertEquals(1, runtime.stopCalls)
        assertEquals(1, runtime.startCalls)
        assertEquals(1L, supervisor.snapshot().runtimeGeneration)
        assertEquals(newerStart.id, supervisor.snapshot().latestTransaction?.id)
    }

    @Test
    fun `newer start after admitted force waits then launches one generation`() = runBlocking {
        val supervisor = FakeSupervisor()
        val forceIssued = CompletableDeferred<Unit>()
        val runtime = FakeRuntime().apply {
            state.value = VmState.Running
            quiescent.value = false
            onForceStop = { forceIssued.complete(Unit) }
        }
        val manager = manager(runtime = runtime, supervisor = supervisor)
        val force = manager.prepareLifecycleCommand(VmId.DEFAULT, LifecycleOperation.FORCE_STOP, 1L)
        val forceExecution = async(Dispatchers.Default) {
            manager.executePrepared(VmId.DEFAULT, force)
        }
        forceIssued.await()

        val newerStart = manager.prepareLifecycleCommand(
            VmId.DEFAULT,
            LifecycleOperation.START,
            2L,
        )
        val startExecution = async(Dispatchers.Unconfined) {
            manager.executePrepared(VmId.DEFAULT, newerStart)
        }
        assertFalse(startExecution.isCompleted)

        runtime.state.value = VmState.Stopped
        runtime.quiescent.value = true
        forceExecution.await()
        assertTrue(startExecution.await())

        assertEquals(1, runtime.forceStopCalls)
        assertEquals(0, runtime.stopCalls)
        assertEquals(1, runtime.startCalls)
        assertEquals(1L, supervisor.snapshot().runtimeGeneration)
    }

    @Test
    fun `newer start superseding admitted restart shutdown launches replacement once`() = runBlocking {
        val supervisor = FakeSupervisor()
        val stopIssued = CompletableDeferred<Unit>()
        val runtime = FakeRuntime().apply {
            state.value = VmState.Running
            quiescent.value = false
            onStop = { stopIssued.complete(Unit) }
        }
        val manager = manager(runtime = runtime, supervisor = supervisor)
        val restart = manager.prepareLifecycleCommand(VmId.DEFAULT, LifecycleOperation.RESTART, 1L)
        val restartExecution = async(Dispatchers.Default) {
            manager.executePrepared(VmId.DEFAULT, restart)
        }
        stopIssued.await()

        val newerStart = manager.prepareLifecycleCommand(
            VmId.DEFAULT,
            LifecycleOperation.START,
            2L,
        )
        val startExecution = async(Dispatchers.Unconfined) {
            manager.executePrepared(VmId.DEFAULT, newerStart)
        }
        assertFalse(startExecution.isCompleted)

        runtime.state.value = VmState.Stopped
        runtime.quiescent.value = true
        assertFalse(restartExecution.await())
        assertTrue(startExecution.await())

        assertEquals(1, runtime.stopCalls)
        assertEquals(1, runtime.startCalls)
        assertEquals(1L, supervisor.snapshot().runtimeGeneration)
        assertEquals(newerStart.id, supervisor.snapshot().latestTransaction?.id)
    }

    @Test
    fun `queued start authority is durable before later execution`() = runBlocking {
        val supervisor = FakeSupervisor()
        val manager = manager(supervisor = supervisor)

        val command = manager.prepareLifecycleCommand(
            VmId.DEFAULT,
            LifecycleOperation.START,
            expectedCommandGeneration = 1L,
        )
        val recovered = manager.supervisorState(VmId.DEFAULT)

        assertEquals(command.id, recovered.latestTransaction?.id)
        assertEquals(VmDesiredState.RUNNING, recovered.desiredState)
        assertEquals(LifecycleOutcome.PENDING, recovered.latestTransaction?.outcome)
    }

    @Test
    fun `delayed guest STOP superseded by force stop and queued start has zero coordinator effects`() =
        runBlocking {
            assertDelayedGuestDispatchIsFenced(LifecycleOperation.STOP)
        }

    @Test
    fun `delayed guest RESTART superseded by force stop and queued start has zero coordinator effects`() =
        runBlocking {
            assertDelayedGuestDispatchIsFenced(LifecycleOperation.RESTART)
        }

    @Test
    fun `service dispatch authority is held through synchronous coordinator admission`() = runBlocking {
        val supervisor = FakeSupervisor()
        val manager = manager(supervisor = supervisor)
        val stop = manager.prepareLifecycleCommand(VmId.DEFAULT, LifecycleOperation.STOP, 1L)
        assertTrue(manager.acceptPrepared(VmId.DEFAULT, stop))
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val events = mutableListOf<String>()

        val authorization = async(Dispatchers.Default) {
            manager.authorizeServiceDispatch(VmId.DEFAULT, stop) {
                events += "callback:start"
                callbackEntered.countDown()
                check(releaseCallback.await(1, TimeUnit.SECONDS))
                events += "callback:end"
            }
        }
        assertTrue(callbackEntered.await(1, TimeUnit.SECONDS))
        val newerPreparation = async(
            context = Dispatchers.Default,
            start = CoroutineStart.UNDISPATCHED,
        ) {
            manager.prepareLifecycleCommand(VmId.DEFAULT, LifecycleOperation.START, 2L).also {
                events += "prepare:newer"
            }
        }

        assertFalse(newerPreparation.isCompleted)
        releaseCallback.countDown()
        assertTrue(authorization.await())
        newerPreparation.await()

        assertEquals(listOf("callback:start", "callback:end", "prepare:newer"), events)
    }

    @Test
    fun `accepted execution starts only after service dispatch authority is released`() = runBlocking {
        val manager = manager()
        val start = manager.prepareLifecycleCommand(VmId.DEFAULT, LifecycleOperation.START, 1L)
        assertTrue(manager.acceptPrepared(VmId.DEFAULT, start))
        var coordinatorAdmitted = false

        assertTrue(manager.authorizeServiceDispatch(VmId.DEFAULT, start) {
            coordinatorAdmitted = true
        })
        assertTrue(coordinatorAdmitted)
        assertTrue(withTimeout(1_000L) {
            manager.executeAccepted(VmId.DEFAULT, start)
        })
    }

    @Test
    fun `stop token is pending before backend stop effect`() = runBlocking {
        val supervisor = FakeSupervisor()
        val runtime = FakeRuntime().apply {
            state.value = VmState.Running
            quiescent.value = false
            onStop = {
                val pending = runBlocking { supervisor.snapshot() }.latestTransaction
                assertEquals(LifecycleOperation.STOP, pending?.operation)
                assertEquals(LifecycleOutcome.PENDING, pending?.outcome)
                state.value = VmState.Stopped
                quiescent.value = true
            }
        }

        manager(runtime = runtime, supervisor = supervisor).stop(VmId.DEFAULT)

        assertEquals(LifecycleOutcome.SUCCEEDED, supervisor.snapshot().latestTransaction?.outcome)
    }

    @Test
    fun `concurrent duplicate prepared restart executes replacement once`() = runBlocking {
        val supervisor = FakeSupervisor()
        val replacementEntered = CompletableDeferred<Unit>()
        val releaseReplacement = CompletableDeferred<Unit>()
        val runtime = FakeRuntime().apply {
            state.value = VmState.Running
            quiescent.value = false
            onStart = {
                replacementEntered.complete(Unit)
                releaseReplacement.await()
                state.value = VmState.Running
            }
        }
        val manager = manager(runtime = runtime, supervisor = supervisor)
        val command = manager.prepareLifecycleCommand(
            VmId.DEFAULT,
            LifecycleOperation.RESTART,
            expectedCommandGeneration = 1L,
        )
        val first = async(Dispatchers.Default) {
            manager.executePrepared(VmId.DEFAULT, command)
        }
        replacementEntered.await()

        assertFalse(manager.executePrepared(VmId.DEFAULT, command))
        releaseReplacement.complete(Unit)
        assertTrue(first.await())

        assertEquals(1, runtime.stopCalls)
        assertEquals(1, runtime.startCalls)
        assertEquals(1L, manager.supervisorState(VmId.DEFAULT).runtimeGeneration)
    }

    @Test
    fun `superseded accepted launch and replacement each increment generation once`() = runBlocking {
        val supervisor = FakeSupervisor()
        val oldLaunchEntered = CompletableDeferred<Unit>()
        val releaseOldLaunch = CompletableDeferred<Unit>()
        val runtime = FakeRuntime().apply {
            onStart = {
                oldLaunchEntered.complete(Unit)
                releaseOldLaunch.await()
                state.value = VmState.Running
            }
        }
        val manager = manager(runtime = runtime, supervisor = supervisor)
        val oldStart = manager.prepareLifecycleCommand(
            VmId.DEFAULT,
            LifecycleOperation.START,
            expectedCommandGeneration = 1L,
        )
        val oldExecution = async(Dispatchers.Default) {
            manager.executePrepared(VmId.DEFAULT, oldStart)
        }
        oldLaunchEntered.await()
        val restartPreparation = async(Dispatchers.Unconfined) {
            manager.prepareLifecycleCommand(
                VmId.DEFAULT,
                LifecycleOperation.RESTART,
                expectedCommandGeneration = 2L,
            )
        }
        assertFalse("new command waits for old launch acceptance", restartPreparation.isCompleted)

        releaseOldLaunch.complete(Unit)
        val restart = restartPreparation.await()
        oldExecution.await()
        assertTrue(manager.executePrepared(VmId.DEFAULT, restart))

        assertEquals(2, runtime.startCalls)
        assertEquals(2L, manager.supervisorState(VmId.DEFAULT).runtimeGeneration)
    }

    @Test
    fun `force stop is distinct and duplicate force stop is idempotent`() = runBlocking {
        val runtime = FakeRuntime().apply {
            state.value = VmState.Running
            quiescent.value = false
        }
        val manager = manager(runtime = runtime)

        manager.forceStop(VmId.DEFAULT)
        manager.forceStop(VmId.DEFAULT)

        assertEquals(1, runtime.forceStopCalls)
        assertEquals(0, runtime.stopCalls)
    }

    @Test
    fun `remove proves both fixed runtimes absent before deleting`() = runBlocking {
        val events = mutableListOf<String>()
        val qemu = CountingProbe(RuntimeBackend.QEMU, RuntimeProbeResult.Absent) {
            events += "qemu-probe"
        }
        val avf = CountingProbe(RuntimeBackend.AVF, RuntimeProbeResult.Absent) {
            events += "avf-probe"
        }
        val files = FakeFiles(onRemove = { events += "remove" })
        val supervisor = hostSupervisor()
        val manager = manager(
            files = files,
            supervisor = supervisor,
            runtimePreflight = RuntimePreflightCoordinator(qemu, avf) {},
        )

        manager.remove(VmId.DEFAULT, VmRemovePolicy.DELETE_DATA)

        assertEquals(listOf("qemu-probe", "avf-probe", "remove"), events)
        val removed = manager.supervisorState(VmId.DEFAULT)
        assertEquals(LifecycleOutcome.SUCCEEDED, removed.latestTransaction?.outcome)
        assertFalse(removed.runtimeMayBeLive)
        assertEquals(2L, removed.runtimeEvidenceVersion)
        assertFalse(
            manager.beginReconciliation(VmId.DEFAULT, ReconciliationTrigger.PROCESS_RESTART) ==
                ReconciliationAdmission.Skip(ReconciliationOutcome.SUPERSEDED),
        )
    }

    @Test
    fun `reconciliation before remove claim skips without interrupting local command`() = runBlocking {
        val beforeClaim = CompletableDeferred<Unit>()
        val releaseClaim = CompletableDeferred<Unit>()
        val files = FakeFiles()
        val supervisor = hostSupervisor()
        val manager = manager(
            files = files,
            supervisor = supervisor,
            beforeDirectCommandClaim = { command ->
                if (command.operation == LifecycleOperation.REMOVE) {
                    beforeClaim.complete(Unit)
                    releaseClaim.await()
                }
            },
        )
        val remove = async(Dispatchers.Default) {
            manager.remove(VmId.DEFAULT, VmRemovePolicy.DELETE_DATA)
        }
        beforeClaim.await()
        val pending = supervisor.snapshot()

        val admission = manager.beginReconciliation(
            VmId.DEFAULT,
            ReconciliationTrigger.PROCESS_RESTART,
        )

        assertEquals(
            ReconciliationAdmission.Skip(ReconciliationOutcome.SUPERSEDED),
            admission,
        )
        assertEquals(pending, supervisor.snapshot())
        assertEquals(LifecycleOutcome.PENDING, pending.latestTransaction?.outcome)
        assertFalse(pending.latestTransaction?.effectStarted == true)
        assertTrue(files.removePolicies.isEmpty())

        releaseClaim.complete(Unit)
        remove.await()
        assertEquals(listOf(VmRemovePolicy.DELETE_DATA), files.removePolicies)
        assertEquals(LifecycleOutcome.SUCCEEDED, supervisor.snapshot().latestTransaction?.outcome)
    }

    @Test
    fun `reconciliation after remove claim skips without interrupting local command`() = runBlocking {
        val beforeEffect = CompletableDeferred<Unit>()
        val releaseEffect = CompletableDeferred<Unit>()
        val files = FakeFiles()
        val supervisor = hostSupervisor()
        val manager = manager(
            files = files,
            supervisor = supervisor,
            beforeDirectCommandEffect = { command ->
                if (command.operation == LifecycleOperation.REMOVE) {
                    beforeEffect.complete(Unit)
                    releaseEffect.await()
                }
            },
        )
        val remove = async(Dispatchers.Default) {
            manager.remove(VmId.DEFAULT, VmRemovePolicy.DELETE_DATA)
        }
        beforeEffect.await()
        val claimed = supervisor.snapshot()

        val admission = manager.beginReconciliation(
            VmId.DEFAULT,
            ReconciliationTrigger.PROCESS_RESTART,
        )

        assertEquals(
            ReconciliationAdmission.Skip(ReconciliationOutcome.SUPERSEDED),
            admission,
        )
        assertEquals(claimed, supervisor.snapshot())
        assertEquals(LifecycleOutcome.PENDING, claimed.latestTransaction?.outcome)
        assertTrue(claimed.latestTransaction?.effectStarted == true)
        assertTrue(files.removePolicies.isEmpty())

        releaseEffect.complete(Unit)
        remove.await()
        assertEquals(listOf(VmRemovePolicy.DELETE_DATA), files.removePolicies)
        assertEquals(LifecycleOutcome.SUCCEEDED, supervisor.snapshot().latestTransaction?.outcome)
    }

    @Test
    fun `ready service command blocks reconciliation without datastore mutation`() = runBlocking {
        val supervisor = hostSupervisor()
        val manager = manager(supervisor = supervisor)
        val command = manager.prepareLifecycleCommand(VmId.DEFAULT, LifecycleOperation.START)
        assertTrue(manager.acceptPrepared(VmId.DEFAULT, command))
        val ready = supervisor.snapshot()

        val admission = manager.beginReconciliation(
            VmId.DEFAULT,
            ReconciliationTrigger.PROCESS_RESTART,
        )

        assertEquals(
            ReconciliationAdmission.Skip(ReconciliationOutcome.SUPERSEDED),
            admission,
        )
        assertEquals(ready, supervisor.snapshot())
        assertTrue(ready.latestTransaction?.effectStarted == true)
    }

    @Test
    fun `process recreation interrupts pending command after local registry is lost`() = runBlocking {
        val supervisor = hostSupervisor()
        val originalManager = manager(supervisor = supervisor)
        originalManager.ensureInstalled(VmId.DEFAULT)
        val pending = originalManager.prepareLifecycleCommand(
            VmId.DEFAULT,
            LifecycleOperation.START,
        )
        val recreatedManager = manager(supervisor = supervisor)

        val admission = recreatedManager.beginReconciliation(
            VmId.DEFAULT,
            ReconciliationTrigger.PROCESS_RESTART,
        )

        assertTrue(admission is ReconciliationAdmission.Execute)
        val interrupted = supervisor.snapshot().latestTransaction
        assertEquals(pending.id, interrupted?.id)
        assertEquals(LifecycleOutcome.FAILED, interrupted?.outcome)
        assertEquals(LifecycleErrorCode.PROCESS_DIED, interrupted?.errorCode)
    }

    @Test
    fun `abandoned prepared command releases local ownership and admits reconciliation`() = runBlocking {
        val supervisor = hostSupervisor()
        val manager = manager(supervisor = supervisor)
        manager.ensureInstalled(VmId.DEFAULT)
        val command = manager.prepareLifecycleCommand(
            VmId.DEFAULT,
            LifecycleOperation.START,
        )

        assertTrue(
            manager.abandonPrepared(
                VmId.DEFAULT,
                command,
                LifecycleErrorCode.INVALID_STATE,
            ),
        )
        val failed = supervisor.snapshot().latestTransaction!!
        assertEquals(LifecycleOutcome.FAILED, failed.outcome)
        assertEquals(LifecycleErrorCode.INVALID_STATE, failed.errorCode)
        assertFalse(failed.effectStarted)
        assertTrue(
            manager.beginReconciliation(VmId.DEFAULT, ReconciliationTrigger.PROCESS_RESTART) is
                ReconciliationAdmission.Execute,
        )
    }

    @Test
    fun `stale manager abandon leaves newer local token authoritative`() = runBlocking {
        val supervisor = hostSupervisor()
        val manager = manager(supervisor = supervisor)
        val stale = manager.prepareLifecycleCommand(VmId.DEFAULT, LifecycleOperation.STOP)
        val newer = manager.prepareLifecycleCommand(VmId.DEFAULT, LifecycleOperation.START)

        assertFalse(
            manager.abandonPrepared(VmId.DEFAULT, stale, LifecycleErrorCode.CANCELLED),
        )
        assertEquals(newer.id, supervisor.snapshot().latestTransaction?.id)
        assertEquals(
            ReconciliationAdmission.Skip(ReconciliationOutcome.SUPERSEDED),
            manager.beginReconciliation(VmId.DEFAULT, ReconciliationTrigger.PROCESS_RESTART),
        )
        assertTrue(
            manager.abandonPrepared(VmId.DEFAULT, newer, LifecycleErrorCode.CANCELLED),
        )
    }

    @Test
    fun `completion write failure releases owner and same process reconciliation is admitted`() = runBlocking {
        val store = FailNextUpdateStore()
        val supervisor = HostSupervisorRepository(store, currentTimeMillis = { 1_000L })
        val runtime = FakeRuntime()
        val manager = manager(runtime = runtime, supervisor = supervisor)
        manager.ensureInstalled(VmId.DEFAULT)
        runtime.onStart = {
            runtime.state.value = VmState.Running
            store.failure = IOException("transient completion write")
        }
        val command = manager.prepareLifecycleCommand(VmId.DEFAULT, LifecycleOperation.START)
        assertTrue(manager.acceptPrepared(VmId.DEFAULT, command))

        val completionFailure = runCatching {
            manager.executeAccepted(VmId.DEFAULT, command)
        }.exceptionOrNull()

        assertTrue(completionFailure is IOException)
        val uncertain = supervisor.snapshot().latestTransaction!!
        assertEquals(LifecycleOutcome.PENDING, uncertain.outcome)
        assertTrue(uncertain.effectStarted)
        assertEquals(1, runtime.startCalls)
        assertTrue(
            manager.beginReconciliation(VmId.DEFAULT, ReconciliationTrigger.PROCESS_RESTART) is
                ReconciliationAdmission.Execute,
        )
        val interrupted = supervisor.snapshot().latestTransaction!!
        assertEquals(LifecycleOutcome.FAILED, interrupted.outcome)
        assertEquals(LifecycleErrorCode.PROCESS_DIED, interrupted.errorCode)
        assertTrue(supervisor.snapshot().runtimeMayBeLive)
    }

    @Test
    fun `direct remove throws when superseded before claim`() = runBlocking {
        val beforeClaim = CompletableDeferred<Unit>()
        val releaseClaim = CompletableDeferred<Unit>()
        val files = FakeFiles()
        val supervisor = hostSupervisor()
        val manager = manager(
            files = files,
            supervisor = supervisor,
            beforeDirectCommandClaim = { command ->
                if (command.operation == LifecycleOperation.REMOVE) {
                    beforeClaim.complete(Unit)
                    releaseClaim.await()
                }
            },
        )
        val remove = async(Dispatchers.Default) {
            runCatching { manager.remove(VmId.DEFAULT, VmRemovePolicy.DELETE_DATA) }
        }
        beforeClaim.await()
        manager.prepareLifecycleCommand(VmId.DEFAULT, LifecycleOperation.STOP)
        releaseClaim.complete(Unit)

        assertTrue(remove.await().exceptionOrNull() is StaleLifecycleCommandException)
        assertTrue(files.removePolicies.isEmpty())
    }

    @Test
    fun `superseded direct remove throws instead of reporting success`() = runBlocking {
        val beforeEffect = CompletableDeferred<Unit>()
        val releaseEffect = CompletableDeferred<Unit>()
        val files = FakeFiles()
        val supervisor = hostSupervisor()
        val manager = manager(
            files = files,
            supervisor = supervisor,
            beforeDirectCommandEffect = { command ->
                if (command.operation == LifecycleOperation.REMOVE) {
                    beforeEffect.complete(Unit)
                    releaseEffect.await()
                }
            },
        )
        val remove = async(Dispatchers.Default) {
            runCatching { manager.remove(VmId.DEFAULT, VmRemovePolicy.DELETE_DATA) }
        }
        beforeEffect.await()
        manager.prepareLifecycleCommand(VmId.DEFAULT, LifecycleOperation.STOP)
        releaseEffect.complete(Unit)

        assertTrue(remove.await().exceptionOrNull() is StaleLifecycleCommandException)
        assertTrue(files.removePolicies.isEmpty())
        assertEquals(LifecycleOperation.STOP, supervisor.snapshot().latestTransaction?.operation)
        assertEquals(LifecycleOutcome.PENDING, supervisor.snapshot().latestTransaction?.outcome)
    }

    @Test
    fun `remove uncertainty fails closed and retains failed transaction for cleanup`() = runBlocking {
        val qemu = CountingProbe(
            RuntimeBackend.QEMU,
            RuntimeProbeResult.Uncertain(
                LifecycleErrorCode.RUNTIME_OWNERSHIP,
                runtimeMayBeLive = true,
            ),
        )
        val avf = CountingProbe(RuntimeBackend.AVF, RuntimeProbeResult.Absent)
        val files = FakeFiles()
        val supervisor = hostSupervisor()
        val manager = manager(
            files = files,
            supervisor = supervisor,
            runtimePreflight = RuntimePreflightCoordinator(qemu, avf) {},
        )

        val failure = runCatching {
            manager.remove(VmId.DEFAULT, VmRemovePolicy.DELETE_DATA)
        }.exceptionOrNull()

        assertTrue(failure is RuntimeProbeException)
        assertTrue(files.removePolicies.isEmpty())
        assertEquals(1, qemu.probeCalls)
        assertEquals(0, avf.probeCalls)
        val failed = manager.supervisorState(VmId.DEFAULT)
        assertEquals(LifecycleOutcome.FAILED, failed.latestTransaction?.outcome)
        assertEquals(VmDesiredState.STOPPED, failed.desiredState)
        assertTrue(failed.runtimeMayBeLive)
        assertEquals(1L, failed.runtimeEvidenceVersion)
        assertTrue(
            manager.beginReconciliation(VmId.DEFAULT, ReconciliationTrigger.PROCESS_RESTART) is
                ReconciliationAdmission.Execute,
        )
    }

    @Test
    fun `remove deletion failure retains evidence and admits cleanup retry`() = runBlocking {
        val files = FakeFiles(onRemove = { throw IOException("delete failed") })
        val supervisor = hostSupervisor()
        val manager = manager(files = files, supervisor = supervisor)

        val failure = runCatching {
            manager.remove(VmId.DEFAULT, VmRemovePolicy.DELETE_DATA)
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        val failed = manager.supervisorState(VmId.DEFAULT)
        assertEquals(LifecycleOutcome.FAILED, failed.latestTransaction?.outcome)
        assertTrue(failed.runtimeMayBeLive)
        assertEquals(1L, failed.runtimeEvidenceVersion)
        assertTrue(
            manager.beginReconciliation(VmId.DEFAULT, ReconciliationTrigger.PROCESS_RESTART) is
                ReconciliationAdmission.Execute,
        )
    }

    @Test
    fun `remove fails while active and forwards only explicit data policy`() = runBlocking {
        val runtime = FakeRuntime().apply {
            state.value = VmState.Running
            quiescent.value = false
        }
        val files = FakeFiles()
        val profileStore = FakeProfileLifecycleStore()
        val manager = manager(runtime = runtime, files = files, profileLifecycleStore = profileStore)

        expectFailure<IllegalStateException> {
            runBlocking { manager.remove(VmId.DEFAULT, VmRemovePolicy.PRESERVE_DATA) }
        }
        assertTrue(files.removePolicies.isEmpty())

        runtime.state.value = VmState.Stopped
        runtime.quiescent.value = true
        manager.remove(VmId.DEFAULT, VmRemovePolicy.PRESERVE_DATA)
        manager.remove(VmId.DEFAULT, VmRemovePolicy.DELETE_DATA)
        assertEquals(
            listOf(VmRemovePolicy.PRESERVE_DATA, VmRemovePolicy.DELETE_DATA),
            files.removePolicies,
        )
        assertEquals(1, profileStore.clearForRemovalCalls)
    }

    @Test
    fun `delete data removal clears profile boot authority before file deletion`() = runBlocking {
        val events = mutableListOf<String>()
        val profileStore = FakeProfileLifecycleStore(onClearForRemoval = { events += "profile-clear" })
        val files = FakeFiles(onRemove = {
            assertEquals(listOf("profile-clear"), events)
            events += "files-delete"
        })
        val manager = manager(files = files, profileLifecycleStore = profileStore)

        manager.remove(VmId.DEFAULT, VmRemovePolicy.DELETE_DATA)

        assertEquals(listOf("profile-clear", "files-delete"), events)
        assertEquals(1, profileStore.clearForRemovalCalls)
    }

    @Test
    fun `typed QMP allowlist maps status and version and rejects unavailable backend`() = runBlocking {
        val runtime = FakeRuntime().apply {
            state.value = VmState.Running
            quiescent.value = false
            qmpAvailableValue = true
        }
        val manager = manager(runtime = runtime)

        assertEquals(VmQmpResult.Status("running"), manager.executeQmp(VmId.DEFAULT, VmQmpOperation.QueryStatus))
        assertEquals(VmQmpResult.Version(9, 2, 1), manager.executeQmp(VmId.DEFAULT, VmQmpOperation.QueryVersion))
        assertEquals(listOf(VmQmpOperation.QueryStatus, VmQmpOperation.QueryVersion), runtime.qmpOperations)

        runtime.qmpAvailableValue = false
        expectFailure<IllegalStateException> {
            runBlocking { manager.executeQmp(VmId.DEFAULT, VmQmpOperation.QueryStatus) }
        }
    }

    @Test
    fun `SSH discovery reports enabled and reachable only at the fixed endpoint`() = runBlocking {
        val runtime = FakeRuntime()
        val configuration = FakeConfiguration(ssh = true)
        val manager = manager(runtime = runtime, configuration = configuration)

        val stopped = manager.discoverSshEndpoint(VmId.DEFAULT)
        assertTrue(stopped.enabled)
        assertFalse(stopped.reachable)
        assertNull(stopped.endpoint)

        runtime.state.value = VmState.Running
        runtime.quiescent.value = false
        val running = manager.discoverSshEndpoint(VmId.DEFAULT)
        assertEquals(SshEndpoint("127.0.0.1", 9922), running.endpoint)
        assertTrue(running.reachable)

        configuration.ssh = false
        val disabled = manager.discoverSshEndpoint(VmId.DEFAULT)
        assertFalse(disabled.enabled)
        assertFalse(disabled.reachable)
        assertNull(disabled.endpoint)
    }

    @Test
    fun `busy and quiescence expose launch ownership through terminal cleanup`() = runBlocking {
        val runtime = FakeRuntime()
        val startEntered = CompletableDeferred<Unit>()
        val releaseStart = CompletableDeferred<Unit>()
        runtime.onStart = {
            runtime.state.value = VmState.Starting
            startEntered.complete(Unit)
            releaseStart.await()
        }
        val manager = manager(runtime = runtime)

        val start = async(Dispatchers.Default) { manager.start(VmId.DEFAULT) }
        startEntered.await()
        start.await()
        assertTrue(manager.busy(VmId.DEFAULT).value)
        assertFalse(manager.quiescent(VmId.DEFAULT).value)

        runtime.state.value = VmState.Error("cleanup rejected")
        assertTrue(manager.busy(VmId.DEFAULT).value)
        assertFalse(manager.quiescent(VmId.DEFAULT).value)

        runtime.quiescent.value = true
        releaseStart.complete(Unit)
        repeat(20) {
            if (!manager.busy(VmId.DEFAULT).value) return@repeat
            delay(5)
        }
        assertFalse(manager.busy(VmId.DEFAULT).value)
        assertTrue(manager.quiescent(VmId.DEFAULT).value)
    }

    @Test
    fun `force intent precedes cancellation and joins manager owned start task`() = runBlocking {
        val runtime = FakeRuntime()
        val startEntered = CompletableDeferred<Unit>()
        val startFinished = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        runtime.onStart = {
            runtime.state.value = VmState.Starting
            startEntered.complete(Unit)
            try {
                CompletableDeferred<Unit>().await()
            } finally {
                events += "start-finished"
                startFinished.complete(Unit)
            }
        }
        runtime.onForceStop = {
            events += "force"
            runtime.state.value = VmState.Stopped
            runtime.quiescent.value = true
        }
        val manager = manager(runtime = runtime)

        val start = async(Dispatchers.Default) { manager.start(VmId.DEFAULT) }
        startEntered.await()
        start.await()
        manager.forceStop(VmId.DEFAULT)

        startFinished.await()
        assertEquals(listOf("force", "start-finished"), events)
        assertTrue(manager.quiescent(VmId.DEFAULT).value)
    }

    @Test
    fun `caller cancellation during acceptance invalidates and joins manager runtime task`() = runBlocking {
        val runtime = FakeRuntime()
        val startEntered = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        runtime.onStart = {
            startEntered.complete(Unit)
            try {
                CompletableDeferred<Unit>().await()
            } finally {
                events += "runtime-finished"
            }
        }
        runtime.onForceStop = {
            events += "force"
            runtime.state.value = VmState.Stopped
            runtime.quiescent.value = true
        }
        val manager = manager(runtime = runtime)

        val start = async(Dispatchers.Default) { manager.start(VmId.DEFAULT) }
        startEntered.await()
        start.cancel()
        start.join()
        manager.stop(VmId.DEFAULT)

        assertEquals(listOf("force", "runtime-finished"), events)
        assertEquals(1, runtime.forceStopCalls)
        assertTrue(manager.quiescent(VmId.DEFAULT).value)
        assertFalse(manager.busy(VmId.DEFAULT).value)
    }

    @Test
    fun `stale routed true cannot release stop install or remove guards`() = runBlocking {
        val runtime = DelayedQuiescenceRuntime().apply {
            delegate.state.value = VmState.Running
            exactQuiescent.value = false
            collectedQuiescent.value = true
            delegate.onStop = { }
            delegate.onForceStop = { }
        }
        val files = FakeFiles()
        var installs = 0
        val manager = manager(
            runtime = runtime,
            files = files,
            installer = object : TestInstaller() {
                override suspend fun install(vmId: VmId) { installs++ }
            },
        )

        val stop = async(Dispatchers.Default) { manager.stop(VmId.DEFAULT) }
        while (runtime.delegate.stopCalls == 0) delay(5)
        delay(20)
        assertFalse("stale collected true must not complete stop", stop.isCompleted)

        runtime.exactQuiescent.value = true
        stop.await()
        assertEquals(1, runtime.delegate.stopCalls)
        assertEquals(0, runtime.delegate.forceStopCalls)

        runtime.exactQuiescent.value = false
        assertTrue(runCatching { manager.ensureInstalled(VmId.DEFAULT) }.exceptionOrNull() is IllegalStateException)
        assertTrue(runCatching {
            manager.remove(VmId.DEFAULT, VmRemovePolicy.PRESERVE_DATA)
        }.exceptionOrNull() is IllegalStateException)
        assertEquals(0, installs)
        assertTrue(files.removePolicies.isEmpty())
    }

    @Test
    fun `error is not safe and destructive operations reject until cleanup completes`() = runBlocking {
        val runtime = FakeRuntime().apply {
            state.value = VmState.Error("stop rejected")
            quiescent.value = false
        }
        val files = FakeFiles()
        var installs = 0
        val manager = manager(
            runtime = runtime,
            files = files,
            installer = object : TestInstaller() {
                override suspend fun install(vmId: VmId) { installs++; files.installed = true }
            },
        )

        assertTrue(runCatching { manager.start(VmId.DEFAULT) }.exceptionOrNull() is IllegalStateException)
        assertTrue(runCatching { manager.ensureInstalled(VmId.DEFAULT) }.exceptionOrNull() is IllegalStateException)
        assertTrue(runCatching {
            manager.remove(VmId.DEFAULT, VmRemovePolicy.PRESERVE_DATA)
        }.exceptionOrNull() is IllegalStateException)
        assertEquals(0, installs)
        assertTrue(files.removePolicies.isEmpty())

        runtime.quiescent.value = true
        manager.remove(VmId.DEFAULT, VmRemovePolicy.PRESERVE_DATA)
        assertEquals(listOf(VmRemovePolicy.PRESERVE_DATA), files.removePolicies)
    }

    @Test
    fun `force stop preempts graceful wait and both callers share one operation`() = runBlocking {
        val runtime = FakeRuntime().apply {
            state.value = VmState.Running
            quiescent.value = false
            qmpAvailableValue = true
        }
        val manager = manager(runtime = runtime, guestTimeoutMs = 1_000)

        val graceful = async(Dispatchers.Default) {
            runCatching { manager.stop(VmId.DEFAULT) }
        }
        while (runtime.powerdownCalls == 0) delay(5)
        val forced = async(Dispatchers.Default) { manager.forceStop(VmId.DEFAULT) }
        forced.await()
        assertTrue(graceful.await().exceptionOrNull() is StaleLifecycleCommandException)

        assertEquals(1, runtime.powerdownCalls)
        assertEquals(0, runtime.stopCalls)
        assertEquals(1, runtime.forceStopCalls)
        assertTrue(runtime.quiescent.value)
    }

    @Test
    fun `rejected force remains non-quiescent and a later retry can clean up`() = runBlocking {
        val runtime = FakeRuntime().apply {
            state.value = VmState.Running
            quiescent.value = false
            onForceStop = { state.value = VmState.Error("AVF rejected") }
        }
        val manager = manager(runtime = runtime)

        assertTrue(runCatching { manager.forceStop(VmId.DEFAULT) }.exceptionOrNull() is IllegalStateException)
        assertFalse(runtime.quiescent.value)
        runtime.onForceStop = { runtime.quiescent.value = true }
        manager.forceStop(VmId.DEFAULT)

        assertEquals(2, runtime.forceStopCalls)
        assertTrue(runtime.quiescent.value)
    }

    @Test
    fun `initial extraction and exclusive lease order removal after queued install`() = runBlocking {
        val files = FakeFiles(installed = false)
        val initial = CompletableDeferred<Unit>()
        val installEntered = CompletableDeferred<Unit>()
        val releaseInstall = CompletableDeferred<Unit>()
        val treeMutex = kotlinx.coroutines.sync.Mutex()
        val events = mutableListOf<String>()
        val installer = object : VmInstaller {
            override suspend fun awaitInitial(vmId: VmId) { initial.await() }
            override suspend fun <T> withExclusiveTree(
                vmId: VmId,
                action: suspend (VmAssetTreeLease) -> T,
            ): T = treeMutex.withLock {
                events += "lease"
                action(object : VmAssetTreeLease {
                    override suspend fun install(vmId: VmId) {
                        events += "install"
                        installEntered.complete(Unit)
                        releaseInstall.await()
                        files.installed = true
                    }
                })
            }
        }
        val manager = manager(files = files, installer = installer)

        val install = async(Dispatchers.Default) { manager.ensureInstalled(VmId.DEFAULT) }
        delay(30)
        assertTrue(events.isEmpty())
        initial.complete(Unit)
        installEntered.await()
        val remove = async(Dispatchers.Default) {
            manager.remove(VmId.DEFAULT, VmRemovePolicy.PRESERVE_DATA)
            events += "remove"
        }
        delay(30)
        assertTrue(files.removePolicies.isEmpty())
        releaseInstall.complete(Unit)
        install.await()
        remove.await()

        assertEquals(listOf("lease", "install", "lease", "remove"), events)
    }

    @Test
    fun `console request rejects excessive caller bounds`() {
        expectFailure<IllegalArgumentException> { ConsoleLogRequest(ConsoleLogRequest.MAX_BYTES + 1, 1) }
        expectFailure<IllegalArgumentException> { ConsoleLogRequest(1, ConsoleLogRequest.MAX_LINES + 1) }
    }

    @Test
    fun `console reads are byte and line bounded tails`() {
        val filesDir = temporaryFolder.newFolder("console-files")
        val paths = createInstalledPaths(filesDir)
        paths.consoleLog.writeText((1..20).joinToString("\n") { "line-$it" })
        val store = VmPathFiles(paths)

        val result = store.readConsole(VmId.DEFAULT, ConsoleLogRequest(maxBytes = 64, maxLines = 3))

        assertTrue(result.bytesRead <= 64)
        assertTrue(result.lineCount <= 3)
        assertEquals("line-18\nline-19\nline-20", result.text)
        assertTrue(result.truncated)
    }

    @Test
    fun `preserve data rejects a non-regular storage image before deletion`() {
        val filesDir = temporaryFolder.newFolder("bad-storage-files")
        val paths = createInstalledPaths(filesDir)
        assertTrue(paths.storageImage.mkdir())
        paths.storageImage.resolve("payload").writeText("persistent")
        val store = VmPathFiles(paths)

        expectFailure<IOException> { store.remove(VmId.DEFAULT, VmRemovePolicy.PRESERVE_DATA) }

        assertTrue(paths.kernel.exists())
        assertEquals("persistent", paths.storageImage.resolve("payload").readText())
    }

    @Test
    fun `removal allows a system symlink ancestor above a real filesDir`() {
        val realSystemRoot = temporaryFolder.newFolder("real-remove-system")
        val aliasRoot = temporaryFolder.root.toPath().resolve("remove-system-alias")
        Files.createSymbolicLink(aliasRoot, realSystemRoot.toPath())
        val filesDir = aliasRoot.resolve("app/files").toFile().apply { mkdirs() }
        val paths = createInstalledPaths(filesDir)
        paths.storageImage.writeText("persistent")

        VmPathFiles(paths).remove(VmId.DEFAULT, VmRemovePolicy.PRESERVE_DATA)

        assertEquals("persistent", paths.storageImage.readText())
        assertFalse(paths.kernel.exists())
    }

    @Test
    fun `remove preserves storage unless delete data is explicit and never follows symlinks`() {
        val filesDir = temporaryFolder.newFolder("remove-files")
        val paths = createInstalledPaths(filesDir)
        paths.storageImage.writeText("persistent")
        val store = VmPathFiles(paths)

        store.remove(VmId.DEFAULT, VmRemovePolicy.PRESERVE_DATA)
        assertEquals("persistent", paths.storageImage.readText())
        assertFalse(paths.kernel.exists())

        paths.kernel.writeText("kernel")
        val outside = temporaryFolder.newFile("outside").apply { writeText("safe") }
        Files.createSymbolicLink(paths.instanceDirectory.toPath().resolve("hostile"), outside.toPath())
        expectFailure<IOException> { store.remove(VmId.DEFAULT, VmRemovePolicy.DELETE_DATA) }
        assertEquals("safe", outside.readText())
        assertTrue(paths.storageImage.exists())
    }

    private fun createInstalledPaths(filesDir: java.io.File): VmPaths {
        val paths = VmPaths.default(filesDir)
        assertTrue(paths.instancesDirectory.mkdirs())
        assertTrue(paths.instanceDirectory.mkdir())
        paths.kernel.writeText("kernel")
        paths.initrd.writeText("initrd")
        paths.rootfs.writeText("rootfs")
        return paths
    }

    private suspend fun assertDelayedGuestDispatchIsFenced(
        delayedOperation: LifecycleOperation,
    ) {
        val supervisor = FakeSupervisor()
        val manager = manager(supervisor = supervisor)
        val coordinator = ServiceLaunchCoordinator<String>()
        coordinator.beginLaunch("existing-launch")

        val delayedGuest = manager.prepareLifecycleCommand(
            VmId.DEFAULT,
            delayedOperation,
            1L,
        )
        assertTrue(manager.acceptPrepared(VmId.DEFAULT, delayedGuest))

        val forceStop = manager.prepareLifecycleCommand(
            VmId.DEFAULT,
            LifecycleOperation.FORCE_STOP,
            2L,
        )
        assertTrue(manager.acceptPrepared(VmId.DEFAULT, forceStop))
        lateinit var admittedForceStop: ServiceLaunchCoordinator.Stop<String>
        val cancelledLaunches = mutableListOf<String>()
        assertTrue(manager.authorizeServiceDispatch(VmId.DEFAULT, forceStop) {
            admittedForceStop = coordinator.beginStop()
            admittedForceStop.launchOwner?.let(cancelledLaunches::add)
        })

        val newerStart = manager.prepareLifecycleCommand(
            VmId.DEFAULT,
            LifecycleOperation.START,
            3L,
        )
        assertTrue(manager.acceptPrepared(VmId.DEFAULT, newerStart))
        var queuedCommand: LifecycleTransactionToken? = null
        assertTrue(manager.authorizeServiceDispatch(VmId.DEFAULT, newerStart) {
            queuedCommand = newerStart
            assertTrue(coordinator.queueStartDuringStop())
        })

        var staleCoordinatorEffects = 0
        assertFalse(manager.authorizeServiceDispatch(VmId.DEFAULT, delayedGuest) {
            staleCoordinatorEffects++
            when (delayedOperation) {
                LifecycleOperation.STOP -> {
                    queuedCommand = null
                    coordinator.beginStop()
                }
                LifecycleOperation.RESTART -> {
                    val staleStop = coordinator.beginStop()
                    if (!staleStop.shouldExecute) {
                        queuedCommand = delayedGuest
                        coordinator.queueStartDuringStop()
                    }
                }
                else -> error("unexpected delayed operation")
            }
        })

        assertEquals(0, staleCoordinatorEffects)
        assertEquals(listOf("existing-launch"), cancelledLaunches)
        assertEquals(newerStart, queuedCommand)
        var replacement: ServiceLaunchCoordinator.Launch<String>? = null
        assertTrue(manager.authorizeServiceDispatch(VmId.DEFAULT, newerStart) {
            replacement = requireNotNull(
                coordinator.completeStop(admittedForceStop.generation, "newer-start"),
            ).launch
        })
        assertEquals("newer-start", requireNotNull(replacement).owner)

        val state = manager.supervisorState(VmId.DEFAULT)
        assertEquals(newerStart.id, state.latestTransaction?.id)
        assertEquals(LifecycleOperation.START, state.latestTransaction?.operation)
        assertEquals(LifecycleOutcome.PENDING, state.latestTransaction?.outcome)
        assertEquals(VmDesiredState.RUNNING, state.desiredState)
    }

    private fun hostSupervisor() = HostSupervisorRepository(
        InMemoryHostSupervisorStore(),
        currentTimeMillis = { 1_000L },
    )

    private fun manager(
        runtime: ManagedVmRuntime = FakeRuntime(),
        files: FakeFiles = FakeFiles(),
        installer: VmInstaller = object : TestInstaller() {
            override suspend fun install(vmId: VmId) { files.installed = true }
        },
        configuration: FakeConfiguration = FakeConfiguration(),
        supervisor: HostSupervisorTransactions = FakeSupervisor(),
        guestTimeoutMs: Long = 50,
        beforeFinalLaunchAuthorization: suspend (LifecycleTransactionToken) -> Unit = {},
        beforeFinalStopAuthorization: suspend (LifecycleTransactionToken) -> Unit = {},
        beforeCoordinatedStop: suspend () -> Unit = {},
        beforeDirectCommandClaim: suspend (LifecycleTransactionToken) -> Unit = {},
        beforeDirectCommandEffect: suspend (LifecycleTransactionToken) -> Unit = {},
        runtimePreflight: RuntimePreflightCoordinator? = null,
        profileLifecycleStore: ProfileLifecycleStore = FakeProfileLifecycleStore(),
    ): DefaultVmManager {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also(scopes::add)
        return DefaultVmManager(
            runtime = runtime,
            installer = installer,
            configuration = configuration,
            files = files,
            supervisor = supervisor,
            scope = scope,
            startAcceptanceTimeoutMs = 500,
            guestShutdownTimeoutMs = guestTimeoutMs,
            backendStopTimeoutMs = 100,
            forceStopTimeoutMs = 100,
            qmpTimeoutMs = 100,
            runtimePreflight = runtimePreflight ?: RuntimePreflightCoordinator(
                CountingProbe(RuntimeBackend.QEMU, RuntimeProbeResult.Absent),
                CountingProbe(RuntimeBackend.AVF, RuntimeProbeResult.Absent),
            ) {},
            profileLifecycleStore = profileLifecycleStore,
            beforeFinalLaunchAuthorization = beforeFinalLaunchAuthorization,
            beforeFinalStopAuthorization = beforeFinalStopAuthorization,
            beforeCoordinatedStop = beforeCoordinatedStop,
            beforeDirectCommandClaim = beforeDirectCommandClaim,
            beforeDirectCommandEffect = beforeDirectCommandEffect,
        )
    }

    private class FailNextUpdateStore : AtomicHostSupervisorRecordStore {
        private val mutex = kotlinx.coroutines.sync.Mutex()
        private var raw: String? = null
        var failure: Throwable? = null
        override suspend fun read(): String? = mutex.withLock { raw }
        override suspend fun update(transform: (String?) -> String): String = mutex.withLock {
            failure?.let {
                failure = null
                throw it
            }
            transform(raw).also { raw = it }
        }
    }

    private class InMemoryHostSupervisorStore : AtomicHostSupervisorRecordStore {
        private val mutex = kotlinx.coroutines.sync.Mutex()
        private var raw: String? = null
        override suspend fun read(): String? = mutex.withLock { raw }
        override suspend fun update(transform: (String?) -> String): String = mutex.withLock {
            transform(raw).also { raw = it }
        }
    }

    private abstract class TestInstaller : VmInstaller {
        override suspend fun awaitInitial(vmId: VmId) = Unit
        abstract suspend fun install(vmId: VmId)
        override suspend fun <T> withExclusiveTree(
            vmId: VmId,
            action: suspend (VmAssetTreeLease) -> T,
        ): T = action(object : VmAssetTreeLease {
            override suspend fun install(vmId: VmId) = this@TestInstaller.install(vmId)
        })
    }

    private class FakeSupervisor : HostSupervisorTransactions {
        private val mutex = kotlinx.coroutines.sync.Mutex()
        private var state = HostSupervisorState.safeDefaults()
        var onPrepare: suspend (LifecycleOperation) -> Unit = {}

        override suspend fun snapshot(): HostSupervisorState = mutex.withLock { state }

        override suspend fun prepare(
            operation: LifecycleOperation,
            expectedId: Long?,
        ): LifecycleTransactionToken = mutex.withLock {
            onPrepare(operation)
            val id = (state.latestTransaction?.id ?: 0L) + 1L
            if (expectedId != null && expectedId != id) {
                throw StaleLifecycleCommandException("stale")
            }
            val token = LifecycleTransactionToken(id, operation, state.runtimeGeneration)
            state = state.copy(
                hostEnabled = state.hostEnabled || operation == LifecycleOperation.SETUP,
                desiredState = when (operation) {
                    LifecycleOperation.START, LifecycleOperation.RECOVER, LifecycleOperation.RESTART ->
                        VmDesiredState.RUNNING
                    LifecycleOperation.STOP, LifecycleOperation.FORCE_STOP, LifecycleOperation.REMOVE ->
                        VmDesiredState.STOPPED
                    LifecycleOperation.SETUP -> state.desiredState
                },
                latestTransaction = LifecycleTransaction(
                    id, operation, LifecycleOutcome.PENDING, id, null, null,
                ),
            )
            token
        }

        override suspend fun claim(token: LifecycleTransactionToken): Boolean = mutex.withLock {
            val transaction = state.latestTransaction ?: return@withLock false
            if (transaction.id != token.id || transaction.operation != token.operation ||
                transaction.outcome != LifecycleOutcome.PENDING || transaction.effectStarted
            ) return@withLock false
            state = state.copy(latestTransaction = transaction.copy(effectStarted = true))
            true
        }

        override suspend fun abandon(
            token: LifecycleTransactionToken,
            errorCode: LifecycleErrorCode,
        ): Boolean = mutex.withLock {
            val transaction = state.latestTransaction ?: return@withLock false
            if (transaction.id != token.id || transaction.operation != token.operation ||
                transaction.outcome != LifecycleOutcome.PENDING || transaction.effectStarted
            ) return@withLock false
            state = state.copy(latestTransaction = transaction.copy(
                outcome = LifecycleOutcome.FAILED,
                completedAtEpochMs = transaction.requestedAtEpochMs,
                errorCode = errorCode,
            ))
            true
        }

        override suspend fun isCurrent(token: LifecycleTransactionToken): Boolean = mutex.withLock {
            state.latestTransaction?.let {
                it.id == token.id && it.operation == token.operation &&
                    it.outcome == LifecycleOutcome.PENDING && it.effectStarted
            } == true
        }

        override suspend fun succeed(
            token: LifecycleTransactionToken,
            runtimeStarted: Boolean,
        ): Boolean = mutex.withLock {
            finish(token, LifecycleOutcome.SUCCEEDED, null, runtimeStarted)
        }

        override suspend fun fail(
            token: LifecycleTransactionToken,
            errorCode: LifecycleErrorCode,
        ): Boolean = mutex.withLock {
            finish(token, LifecycleOutcome.FAILED, errorCode, false)
        }

        private fun finish(
            token: LifecycleTransactionToken,
            outcome: LifecycleOutcome,
            errorCode: LifecycleErrorCode?,
            runtimeStarted: Boolean,
        ): Boolean {
            val latest = state.latestTransaction ?: return false
            if (latest.id != token.id || latest.operation != token.operation ||
                latest.outcome != LifecycleOutcome.PENDING) {
                if (runtimeStarted && outcome == LifecycleOutcome.SUCCEEDED) {
                    state = state.copy(
                        runtimeGeneration = maxOf(
                            state.runtimeGeneration,
                            token.baseRuntimeGeneration + 1L,
                        ),
                    )
                }
                return false
            }
            state = state.copy(
                runtimeGeneration = if (runtimeStarted) {
                    state.runtimeGeneration + 1L
                } else state.runtimeGeneration,
                latestTransaction = latest.copy(
                    outcome = outcome,
                    completedAtEpochMs = latest.requestedAtEpochMs,
                    errorCode = errorCode,
                ),
            )
            return true
        }
    }

    private fun profileCandidate() = PreparedProfileCandidate(
        profileId = ProfileId("default"),
        generation = ProfileGeneration(1),
        manifestSha256 = Sha256Digest("a".repeat(64)),
        signingKeyId = SigningKeyId("release-1"),
        signingKeyFingerprint = Sha256Digest("b".repeat(64)),
        trustEpoch = TrustEpoch(1),
    )

    private class FakeProfileLifecycleStore(
        private val onInstall: suspend () -> Unit = {},
        private val onRollback: suspend () -> Unit = {},
        private val onClearForRemoval: suspend () -> Unit = {},
        private val rollbackResult: ActivationState? = null,
        private val supportedBackends: Set<ProfileBackend> = ProfileBackend.entries.toSet(),
    ) : ProfileLifecycleStore {
        var installCalls = 0
        var rollbackCalls = 0
        var clearForRemovalCalls = 0
        override suspend fun candidateSupportedBackends(
            candidate: PreparedProfileCandidate,
        ): Set<ProfileBackend> = supportedBackends
        override suspend fun rollbackSupportedBackends(
            expectedActivationSequence: Long,
        ): Set<ProfileBackend> = supportedBackends
        override suspend fun issueDataDeletionConfirmation(
            candidate: PreparedProfileCandidate,
        ): DataDeletionConfirmation = throw UnsupportedOperationException()

        override suspend fun install(
            candidate: PreparedProfileCandidate,
            dataPolicy: GuestDataPolicy,
            deletionConfirmation: DataDeletionConfirmation?,
        ): ActivationState {
            installCalls++
            onInstall()
            return ActivationState(1, candidate, null)
        }

        override suspend fun rollback(
            expectedActivationSequence: Long,
            dataPolicy: GuestDataPolicy,
        ): ActivationState {
            rollbackCalls++
            onRollback()
            return rollbackResult ?: throw UnsupportedOperationException()
        }

        override suspend fun clearForVmRemoval(dataPolicy: GuestDataPolicy) {
            if (dataPolicy == GuestDataPolicy.DELETE_DATA) {
                clearForRemovalCalls++
                onClearForRemoval()
            }
        }
    }

    private class CountingProbe(
        override val backend: RuntimeBackend,
        var result: RuntimeProbeResult,
        private val onProbe: () -> Unit = {},
    ) : NamedRuntimeProbe {
        var probeCalls = 0
        var stopCalls = 0
        override suspend fun probe(): RuntimeProbeResult {
            probeCalls++
            onProbe()
            return result
        }
        override suspend fun stopLiveRuntime(): Boolean { stopCalls++; return true }
        override suspend fun awaitStopped(): Boolean = true
    }

    private class FakeRuntime(initialBackendId: String = "qemu") : ManagedVmRuntime {
        @Volatile private var selectedBackendId = initialBackendId
        private val backendSelectionMutex = kotlinx.coroutines.sync.Mutex()
        var beforeBackendClaim: suspend () -> Unit = {}
        var coldResolvedBackendId: String? = null
        override val backendId: String get() = selectedBackendId
        suspend fun swapBackend(next: String) = backendSelectionMutex.withLock {
            selectedBackendId = next
        }
        override suspend fun <T> withBackendSelectionClaim(
            action: suspend (backendId: String) -> T,
        ): T = backendSelectionMutex.withLock {
            beforeBackendClaim()
            coldResolvedBackendId?.let { selectedBackendId = it; coldResolvedBackendId = null }
            action(selectedBackendId)
        }
        override val vmId = VmId.DEFAULT
        override val state = MutableStateFlow<VmState>(VmState.Idle)
        override val quiescent = MutableStateFlow(true)
        override val bootStage = MutableStateFlow("")
        override val stopping = MutableStateFlow(false)
        override val runningSinceMs: Long? = null
        override fun emulatorRssMb(): Long? = null
        override fun emulatorPid(): Int? = null
        override fun diagnosticsReport(): String = ""
        var qmpAvailableValue = false
        override val qmpAvailable: Boolean get() = qmpAvailableValue
        var startCalls = 0
        var stopCalls = 0
        var forceStopCalls = 0
        var powerdownCalls = 0
        var concurrentStarts = 0
        var maxConcurrentStarts = 0
        val qmpOperations = mutableListOf<VmQmpOperation>()
        var onStart: suspend () -> Unit = { state.value = VmState.Running }
        var onStop: () -> Unit = { state.value = VmState.Stopped; quiescent.value = true }
        var onPowerdown: suspend () -> Result<Unit> = { Result.success(Unit) }
        var onForceStop: () -> Unit = {
            state.value = VmState.Stopped
            quiescent.value = true
        }

        override suspend fun start(plan: VmLaunchPlan) {
            quiescent.value = false
            startCalls++
            concurrentStarts++
            maxConcurrentStarts = maxOf(maxConcurrentStarts, concurrentStarts)
            try { onStart() } finally { concurrentStarts-- }
        }
        override fun stop() { stopCalls++; onStop() }
        override fun forceStop() {
            forceStopCalls++
            onForceStop()
        }
        override suspend fun systemPowerdown(): Result<Unit> { powerdownCalls++; return onPowerdown() }
        override suspend fun executeQmp(operation: VmQmpOperation): Result<VmQmpResult> {
            qmpOperations.add(operation)
            return Result.success(when (operation) {
                VmQmpOperation.QueryStatus -> VmQmpResult.Status("running")
                VmQmpOperation.QueryVersion -> VmQmpResult.Version(9, 2, 1)
            })
        }
    }

    private class DelayedQuiescenceRuntime(
        val delegate: FakeRuntime = FakeRuntime(),
    ) : ManagedVmRuntime by delegate {
        val exactQuiescent = MutableStateFlow(true)
        val collectedQuiescent = MutableStateFlow(true)
        override val quiescent: StateFlow<Boolean> = ExactValueStateFlow(collectedQuiescent) {
            exactQuiescent.value
        }
    }

    private class FakeFiles(
        var installed: Boolean = true,
        private val onRemove: (VmRemovePolicy) -> Unit = {},
    ) : VmFiles {
        val removePolicies = mutableListOf<VmRemovePolicy>()
        override fun isInstalled(vmId: VmId) = installed
        override fun remove(vmId: VmId, policy: VmRemovePolicy) {
            onRemove(policy)
            removePolicies.add(policy)
        }
        override fun readConsole(vmId: VmId, request: ConsoleLogRequest) = ConsoleLog("", 0, 0, false)
        override fun storageAllocatedBytes(vmId: VmId): Long = 0L
        override fun redactPrivatePaths(text: String): String = text
    }

    private class FakeConfiguration(
        var ssh: Boolean = false,
        var launchFailure: IOException? = null,
    ) : VmConfigurationSource {
        override suspend fun launchPlan(vmId: VmId): VmLaunchPlan {
            launchFailure?.let { throw it }
            return VmLaunchPlan(emptyList(), VmConfig(vmId = vmId))
        }

        override suspend fun sshEnabled(vmId: VmId) = ssh
    }

    private inline fun <reified T : Throwable> expectFailure(block: () -> Unit) {
        try {
            block()
            fail("Expected ${T::class.java.simpleName}")
        } catch (failure: Throwable) {
            if (failure !is T) throw failure
        }
    }
}
