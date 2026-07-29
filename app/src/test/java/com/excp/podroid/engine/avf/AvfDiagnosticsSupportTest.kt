package com.excp.podroid.engine.avf

import com.excp.podroid.vm.MonotonicDeadline
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AvfDiagnosticsSupportTest {
    @Test fun `customVmConfigSupported is false when AVF classes absent`() {
        // JVM unit test: android.system.virtualmachine.* is not on the classpath.
        assertFalse(AvfDiagnostics.customVmConfigSupported())
    }

    @Test fun `smoke result is bounded`() {
        assertEquals(4 * 1024, AvfDiagnostics.boundSmokeTestResult("x".repeat(8 * 1024)).length)
    }

    @Test fun `readiness evaluates the propagated absolute deadline after waiting`() = runBlocking {
        val nowNanos = AtomicLong(0L)
        val deadlineNanos = TimeUnit.MILLISECONDS.toNanos(100)

        val ready = AvfDiagnostics.awaitSmokeReadiness(deadlineNanos, nowNanos::get) {
            nowNanos.set(deadlineNanos)
        }

        assertFalse(ready)
    }

    @Test fun `forever pending asset readiness is deadline bounded`() = runBlocking {
        val neverReady = CompletableDeferred<Unit>()
        val startedNanos = System.nanoTime()

        val ready = AvfDiagnostics.awaitSmokeReadiness(
            deadlineNanos = MonotonicDeadline.afterMillis(50),
        ) {
            neverReady.await()
        }
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)

        assertFalse(ready)
        assertTrue("elapsed=${elapsedMs}ms", elapsedMs < 500)
    }
}
