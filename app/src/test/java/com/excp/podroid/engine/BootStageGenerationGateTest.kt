package com.excp.podroid.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BootStageGenerationGateTest {
    @Test
    fun `QEMU buffered Ready released after cleanup invalidation cannot transition Running`() = runBlocking {
        assertBufferedReadyIsRejectedAfterCleanup(generation = 11L)
    }

    @Test
    fun `AVF buffered Ready released after cleanup invalidation cannot transition Running`() = runBlocking {
        assertBufferedReadyIsRejectedAfterCleanup(generation = 29L)
    }

    @Test
    fun `cleanup terminal mutation wins atomically over an in-flight Ready callback`() = runBlocking {
        val gate = BootStageGenerationGate()
        gate.arm(41L)
        var state: VmState = VmState.Starting
        val readyInsideGate = CompletableDeferred<Unit>()
        val releaseReady = CompletableDeferred<Unit>()

        val ready = async(Dispatchers.Default) {
            gate.apply(41L, { state is VmState.Starting }, { false }) {
                readyInsideGate.complete(Unit)
                runBlocking { releaseReady.await() }
                state = VmState.Running
            }
        }
        readyInsideGate.await()
        val cleanup = async(Dispatchers.Default) {
            gate.invalidateAndApply(41L) { state = VmState.Stopped }
        }
        releaseReady.complete(Unit)

        ready.await()
        cleanup.await()
        assertEquals(VmState.Stopped, state)
    }

    @Test
    fun `only current nonquiescent Starting generation may mutate stage`() {
        val gate = BootStageGenerationGate()
        gate.arm(3L)
        var mutations = 0

        assertFalse(gate.apply(2L, { true }, { false }) { mutations++ })
        assertFalse(gate.apply(3L, { false }, { false }) { mutations++ })
        assertFalse(gate.apply(3L, { true }, { true }) { mutations++ })
        assertEquals(0, mutations)
    }

    private suspend fun assertBufferedReadyIsRejectedAfterCleanup(generation: Long) {
        val gate = BootStageGenerationGate()
        gate.arm(generation)
        var state: VmState = VmState.Starting
        var stage = "Booting kernel..."
        var quiescent = false
        var capturedConsole = ""
        val workerBufferedReady = CompletableDeferred<Unit>()
        val releaseWorker = CompletableDeferred<Unit>()
        val detector = BootStageDetector { detected ->
            gate.apply(
                generation,
                isStarting = { state is VmState.Starting },
                isQuiescent = { quiescent },
            ) {
                stage = detected
                if (detected == "Ready") state = VmState.Running
            }
        }

        val worker = kotlinx.coroutines.coroutineScope {
            async {
                workerBufferedReady.complete(Unit)
                releaseWorker.await()
                val bytes = "SENTINEL_STALE_CONSOLE\nReady!\n".toByteArray()
                gate.applyCurrent(generation) {
                    capturedConsole += String(bytes)
                }
                detector.feed(bytes, bytes.size)
            }.also {
                workerBufferedReady.await()
                // Cleanup invalidates and commits terminal state atomically,
                // before worker cancellation and quiescence publication.
                gate.invalidateAndApply(generation) { state = VmState.Stopped }
                quiescent = true
                releaseWorker.complete(Unit)
            }
        }
        worker.await()

        assertEquals(VmState.Stopped, state)
        assertEquals("Booting kernel...", stage)
        assertEquals("", capturedConsole)
    }
}
