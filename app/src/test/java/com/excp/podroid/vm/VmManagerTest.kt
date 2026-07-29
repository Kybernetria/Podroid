package com.excp.podroid.vm

import com.excp.podroid.engine.VmConfig
import com.excp.podroid.engine.VmState
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
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
        assertTrue(runCatching { manager.start(VmId.DEFAULT) }.exceptionOrNull() is IllegalStateException)
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
    fun `remove fails while active and forwards only explicit data policy`() = runBlocking {
        val runtime = FakeRuntime().apply {
            state.value = VmState.Running
            quiescent.value = false
        }
        val files = FakeFiles()
        val manager = manager(runtime = runtime, files = files)

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

        val graceful = async(Dispatchers.Default) { manager.stop(VmId.DEFAULT) }
        while (runtime.powerdownCalls == 0) delay(5)
        val forced = async(Dispatchers.Default) { manager.forceStop(VmId.DEFAULT) }
        forced.await()
        graceful.await()

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

    private fun manager(
        runtime: FakeRuntime = FakeRuntime(),
        files: FakeFiles = FakeFiles(),
        installer: VmInstaller = object : TestInstaller() {
            override suspend fun install(vmId: VmId) { files.installed = true }
        },
        configuration: FakeConfiguration = FakeConfiguration(),
        guestTimeoutMs: Long = 50,
    ): DefaultVmManager {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also(scopes::add)
        return DefaultVmManager(
            runtime = runtime,
            installer = installer,
            configuration = configuration,
            files = files,
            scope = scope,
            startAcceptanceTimeoutMs = 500,
            guestShutdownTimeoutMs = guestTimeoutMs,
            backendStopTimeoutMs = 100,
            forceStopTimeoutMs = 100,
            qmpTimeoutMs = 100,
        )
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

    private class FakeRuntime : ManagedVmRuntime {
        override val vmId = VmId.DEFAULT
        override val state = MutableStateFlow<VmState>(VmState.Idle)
        override val quiescent = MutableStateFlow(true)
        override val backendId = "qemu"
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

    private class FakeFiles(var installed: Boolean = true) : VmFiles {
        val removePolicies = mutableListOf<VmRemovePolicy>()
        override fun isInstalled(vmId: VmId) = installed
        override fun remove(vmId: VmId, policy: VmRemovePolicy) { removePolicies.add(policy) }
        override fun readConsole(vmId: VmId, request: ConsoleLogRequest) = ConsoleLog("", 0, 0, false)
    }

    private class FakeConfiguration(var ssh: Boolean = false) : VmConfigurationSource {
        override suspend fun launchPlan(vmId: VmId) = VmLaunchPlan(emptyList(), VmConfig(vmId = vmId))
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
