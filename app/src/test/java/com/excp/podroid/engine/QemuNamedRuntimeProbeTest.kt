package com.excp.podroid.engine

import com.excp.podroid.vm.LifecycleErrorCode
import com.excp.podroid.vm.RuntimeProbeResult
import com.excp.podroid.vm.VmPaths
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class QemuNamedRuntimeProbeTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `live owner without a working QMP endpoint is uncertain`() = runBlocking {
        val fixture = fixture()
        fixture.publishOwner()

        val result = fixture.probe().probe()

        assertUncertain(result, LifecycleErrorCode.RUNTIME_OWNERSHIP)
    }

    @Test fun `PID reuse proves recorded process dead and permits stale classification`() = runBlocking {
        val fixture = fixture()
        fixture.publishOwner()
        fixture.reader.observation = ProcessIdentityObservation.Alive(QemuProcessIdentity(PID, START + 1))

        assertTrue(fixture.probe().probe() is RuntimeProbeResult.StaleEndpoints)
    }

    @Test fun `dead owner permits checked endpoint and owner cleanup`() = runBlocking {
        val fixture = fixture()
        fixture.publishOwner()
        fixture.reader.observation = ProcessIdentityObservation.Dead
        fifo(fixture.paths.terminalSocket)
        val stale = fixture.probe().probe() as RuntimeProbeResult.StaleEndpoints

        fixture.store.cleanup(stale.evidence as QemuStaleRuntimeEvidence)

        assertFalse(exists(fixture.paths.terminalSocket))
        assertFalse(exists(fixture.paths.qemuOwnerRecord))
    }

    @Test fun `missing or corrupt owner makes refused endpoint uncertain`() = runBlocking {
        for (corrupt in listOf(false, true)) {
            val fixture = fixture()
            if (corrupt) fixture.paths.qemuOwnerRecord.writeText("not-an-owner")
            fifo(fixture.paths.qmpSocket)
            val failure = QmpEndpointFailure(false, IOException("refused"))

            val result = fixture.probe(Result.failure(failure)).probe()

            assertUncertain(
                result,
                if (corrupt) LifecycleErrorCode.SECURITY else LifecycleErrorCode.RUNTIME_OWNERSHIP,
            )
            assertTrue(exists(fixture.paths.qmpSocket))
        }
    }

    @Test fun `refusal becomes stale only with exact dead owner proof`() = runBlocking {
        val fixture = fixture()
        fixture.publishOwner()
        fixture.reader.observation = ProcessIdentityObservation.Dead
        fifo(fixture.paths.qmpSocket)

        val result = fixture.probe(
            Result.failure(QmpEndpointFailure(false, IOException("refused"))),
        ).probe()

        assertTrue(result is RuntimeProbeResult.StaleEndpoints)
    }

    @Test fun `established EOF malformed and timeout stay uncertain despite dead owner`() = runBlocking {
        val failures = listOf(
            QmpEndpointFailure(true, IOException("EOF before greeting")) to LifecycleErrorCode.RUNTIME_OWNERSHIP,
            QmpEndpointFailure(true, IOException("malformed QMP")) to LifecycleErrorCode.RUNTIME_OWNERSHIP,
            QmpEndpointFailure(true, SocketTimeoutException("timed out")) to LifecycleErrorCode.PROBE_TIMEOUT,
        )
        for ((failure, code) in failures) {
            val fixture = fixture()
            fixture.publishOwner()
            fixture.reader.observation = ProcessIdentityObservation.Dead
            fifo(fixture.paths.qmpSocket)

            assertUncertain(fixture.probe(Result.failure(failure)).probe(), code)
        }
    }

    @Test fun `socket replacement after dead proof blocks all cleanup`() = runBlocking {
        val fixture = fixture()
        fixture.publishOwner()
        fixture.reader.observation = ProcessIdentityObservation.Dead
        fifo(fixture.paths.terminalSocket)
        val stale = fixture.probe().probe() as RuntimeProbeResult.StaleEndpoints
        Files.delete(fixture.paths.terminalSocket.toPath())
        fifo(fixture.paths.terminalSocket)

        val failure = runCatching {
            fixture.store.cleanup(stale.evidence as QemuStaleRuntimeEvidence)
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertTrue(exists(fixture.paths.terminalSocket))
        assertTrue(exists(fixture.paths.qemuOwnerRecord))
    }

    @Test fun `owner replacement after dead proof blocks endpoint deletion`() = runBlocking {
        val fixture = fixture()
        fixture.publishOwner()
        fixture.reader.observation = ProcessIdentityObservation.Dead
        fifo(fixture.paths.terminalSocket)
        val stale = fixture.probe().probe() as RuntimeProbeResult.StaleEndpoints
        val ownerText = fixture.paths.qemuOwnerRecord.readText()
        Files.delete(fixture.paths.qemuOwnerRecord.toPath())
        fixture.paths.qemuOwnerRecord.writeText(ownerText)

        val failure = runCatching {
            fixture.store.cleanup(stale.evidence as QemuStaleRuntimeEvidence)
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertTrue(exists(fixture.paths.terminalSocket))
        assertTrue(exists(fixture.paths.qemuOwnerRecord))
    }

    @Test fun `owner is removed only by cleanup after confirmed reap`() {
        val fixture = fixture()
        val published = fixture.publishOwner()
        fifo(fixture.paths.hostSocket)
        assertTrue(exists(fixture.paths.qemuOwnerRecord))

        fixture.store.cleanupAfterConfirmedReap(published.owner, published)

        assertFalse(exists(fixture.paths.hostSocket))
        assertFalse(exists(fixture.paths.qemuOwnerRecord))
    }

    private fun assertUncertain(result: RuntimeProbeResult, code: LifecycleErrorCode) {
        assertTrue(result is RuntimeProbeResult.Uncertain)
        result as RuntimeProbeResult.Uncertain
        assertEquals(code, result.errorCode)
        assertTrue(result.runtimeMayBeLive)
    }

    private fun fixture(): Fixture {
        val root = temporary.newFolder()
        val paths = VmPaths.default(root)
        assertTrue(paths.instanceDirectory.mkdirs())
        val reader = MutableReader(
            ProcessIdentityObservation.Alive(QemuProcessIdentity(PID, START)),
        )
        return Fixture(paths, reader, QemuRuntimeOwnerStore(paths, reader))
    }

    private data class Fixture(
        val paths: VmPaths,
        val reader: MutableReader,
        val store: QemuRuntimeOwnerStore,
    ) {
        fun publishOwner(): PublishedQemuOwner =
            store.publish(store.capture(PID, GENERATION))

        fun probe(query: Result<String> = Result.failure(IOException("unused"))) =
            QemuNamedRuntimeProbe(
                paths.qmpSocket,
                store,
                listOf(
                    paths.serialSocket,
                    paths.terminalSocket,
                    paths.controlSocket,
                    paths.hostSocket,
                    paths.qmpSocket,
                ),
                qmp = FakeQmp(query),
            )
    }

    private class MutableReader(var observation: ProcessIdentityObservation) : ProcessIdentityReader {
        override fun observe(pid: Long) = observation
    }

    private class FakeQmp(private val query: Result<String>) : QemuRuntimeQmp {
        override suspend fun queryStatus() = query
        override suspend fun quit(): Result<Unit> = Result.success(Unit)
    }

    private fun fifo(file: File) {
        val process = ProcessBuilder("mkfifo", file.absolutePath).start()
        assumeTrue(process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0)
    }

    private fun exists(file: File) = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    private companion object {
        const val PID = 42L
        const val START = 100L
        const val GENERATION = 7L
    }
}
