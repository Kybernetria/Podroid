package com.excp.podroid.vm

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RuntimePreflightCoordinatorTest {
    private data object Evidence : StaleRuntimeEvidence
    @Test fun `live orphan is stopped awaited and stale sockets cleaned before return`() = runBlocking {
        val events = mutableListOf<String>()
        val qemu = FakeProbe(RuntimeBackend.QEMU, RuntimeProbeResult.Live(RuntimeBackend.QEMU), events)
        val avf = FakeProbe(RuntimeBackend.AVF, RuntimeProbeResult.Absent, events)
        val coordinator = RuntimePreflightCoordinator(qemu, avf) { events += "clean" }

        coordinator.prepareForLaunch()

        assertEquals(
            listOf("probe:QEMU", "stop:QEMU", "await:QEMU", "probe:QEMU", "probe:AVF", "clean"),
            events,
        )
    }

    @Test fun `stale sockets clean only after typed no-runtime result`() = runBlocking {
        var cleaned = 0
        val coordinator = RuntimePreflightCoordinator(
            FakeProbe(RuntimeBackend.QEMU, RuntimeProbeResult.StaleEndpoints(Evidence)),
            FakeProbe(RuntimeBackend.AVF, RuntimeProbeResult.Absent),
        ) { cleaned++ }
        coordinator.prepareForLaunch()
        assertEquals(1, cleaned)
    }

    @Test fun `cleanup identity failure remains possible live and blocks launch`() = runBlocking {
        val failure = runCatching {
            RuntimePreflightCoordinator(
                FakeProbe(RuntimeBackend.QEMU, RuntimeProbeResult.StaleEndpoints(Evidence)),
                FakeProbe(RuntimeBackend.AVF, RuntimeProbeResult.Absent),
            ) { throw java.io.IOException("endpoint replaced") }.prepareForLaunch()
        }.exceptionOrNull() as RuntimeProbeException
        assertEquals(LifecycleErrorCode.RUNTIME_OWNERSHIP, failure.stableCode)
        assertTrue(failure.runtimeMayBeLive)
    }

    @Test fun `probe timeout fails closed without stop cleanup or launch readiness`() = runBlocking {
        var cleaned = 0
        val qemu = FakeProbe(
            RuntimeBackend.QEMU,
            RuntimeProbeResult.Uncertain(
                LifecycleErrorCode.PROBE_TIMEOUT,
                runtimeMayBeLive = true,
            ),
        )
        val failure = runCatching {
            RuntimePreflightCoordinator(
                qemu,
                FakeProbe(RuntimeBackend.AVF, RuntimeProbeResult.Absent),
            ) { cleaned++ }.prepareForLaunch()
        }.exceptionOrNull()
        assertTrue(failure is RuntimeProbeException)
        assertEquals(LifecycleErrorCode.PROBE_TIMEOUT, (failure as RuntimeProbeException).stableCode)
        assertTrue(failure.runtimeMayBeLive)
        assertEquals(0, qemu.stopCalls)
        assertEquals(0, cleaned)
    }

    @Test fun `concurrent preflight serializes and does not stop duplicate runtime twice`() = runBlocking {
        val qemu = FakeProbe(
            RuntimeBackend.QEMU,
            RuntimeProbeResult.Live(RuntimeBackend.QEMU),
            delayMs = 20,
        )
        val coordinator = RuntimePreflightCoordinator(
            qemu,
            FakeProbe(RuntimeBackend.AVF, RuntimeProbeResult.Absent),
        ) {}
        listOf(async { coordinator.prepareForLaunch() }, async { coordinator.prepareForLaunch() }).awaitAll()
        assertEquals(1, qemu.stopCalls)
    }

    @Test fun `known live stop failure on either backend reports runtime may be live`() = runBlocking {
        for (backend in RuntimeBackend.entries) {
            val qemu = FakeProbe(RuntimeBackend.QEMU, RuntimeProbeResult.Absent)
            val avf = FakeProbe(RuntimeBackend.AVF, RuntimeProbeResult.Absent)
            val failing = if (backend == RuntimeBackend.QEMU) qemu else avf
            failing.result = RuntimeProbeResult.Live(backend)
            failing.stopResult = false
            val failure = runCatching {
                RuntimePreflightCoordinator(qemu, avf) {}.prepareForLaunch()
            }.exceptionOrNull() as RuntimeProbeException
            assertTrue("$backend", failure.runtimeMayBeLive)
            assertEquals(LifecycleErrorCode.RUNTIME_OWNERSHIP, failure.stableCode)
        }
    }

    @Test fun `failed bounded stop prevents cleanup`() = runBlocking {
        var cleaned = false
        val qemu = FakeProbe(RuntimeBackend.QEMU, RuntimeProbeResult.Live(RuntimeBackend.QEMU)).apply {
            stopResult = false
        }
        val failure = runCatching {
            RuntimePreflightCoordinator(
                qemu,
                FakeProbe(RuntimeBackend.AVF, RuntimeProbeResult.Absent),
            ) { cleaned = true }.prepareForLaunch()
        }.exceptionOrNull()
        assertTrue(failure is RuntimeProbeException)
        assertFalse(cleaned)
    }

    private class FakeProbe(
        override val backend: RuntimeBackend,
        var result: RuntimeProbeResult,
        private val events: MutableList<String> = mutableListOf(),
        private val delayMs: Long = 0,
    ) : NamedRuntimeProbe {
        var stopCalls = 0
        var stopResult = true
        override suspend fun probe(): RuntimeProbeResult {
            events += "probe:$backend"
            return result
        }
        override suspend fun stopLiveRuntime(): Boolean {
            events += "stop:$backend"
            stopCalls++
            if (delayMs > 0) delay(delayMs)
            if (stopResult) result = if (backend == RuntimeBackend.QEMU) {
                RuntimeProbeResult.StaleEndpoints(Evidence)
            } else RuntimeProbeResult.Absent
            return stopResult
        }
        override suspend fun awaitStopped(): Boolean {
            events += "await:$backend"
            return result == RuntimeProbeResult.Absent || result is RuntimeProbeResult.StaleEndpoints
        }
    }
}
