package com.excp.podroid.vm

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RuntimePreflightCoordinatorTest {
    @Test fun `live orphan is stopped awaited and stale sockets cleaned before return`() = runBlocking {
        val events = mutableListOf<String>()
        val qemu = FakeProbe(RuntimeBackend.QEMU, RuntimeProbeResult.Live(RuntimeBackend.QEMU), events)
        val avf = FakeProbe(RuntimeBackend.AVF, RuntimeProbeResult.Absent, events)
        val coordinator = RuntimePreflightCoordinator(qemu, avf) { events += "clean" }

        coordinator.prepareForLaunch()

        assertEquals(listOf("probe:QEMU", "stop:QEMU", "await:QEMU", "probe:AVF", "clean"), events)
    }

    @Test fun `stale sockets clean only after typed no-runtime result`() = runBlocking {
        var cleaned = 0
        val coordinator = RuntimePreflightCoordinator(
            FakeProbe(RuntimeBackend.QEMU, RuntimeProbeResult.StaleEndpoints),
            FakeProbe(RuntimeBackend.AVF, RuntimeProbeResult.Absent),
        ) { cleaned++ }
        coordinator.prepareForLaunch()
        assertEquals(1, cleaned)
    }

    @Test fun `probe timeout fails closed without stop cleanup or launch readiness`() = runBlocking {
        var cleaned = 0
        val qemu = FakeProbe(
            RuntimeBackend.QEMU,
            RuntimeProbeResult.Uncertain(LifecycleErrorCode.PROBE_TIMEOUT),
        )
        val failure = runCatching {
            RuntimePreflightCoordinator(
                qemu,
                FakeProbe(RuntimeBackend.AVF, RuntimeProbeResult.Absent),
            ) { cleaned++ }.prepareForLaunch()
        }.exceptionOrNull()
        assertTrue(failure is RuntimeProbeException)
        assertEquals(LifecycleErrorCode.PROBE_TIMEOUT, (failure as RuntimeProbeException).stableCode)
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
        private var result: RuntimeProbeResult,
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
            if (stopResult) result = RuntimeProbeResult.Absent
            return stopResult
        }
        override suspend fun awaitStopped(): Boolean {
            events += "await:$backend"
            return result == RuntimeProbeResult.Absent
        }
    }
}
