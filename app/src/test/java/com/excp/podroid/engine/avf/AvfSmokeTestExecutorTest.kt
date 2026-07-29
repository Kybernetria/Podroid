package com.excp.podroid.engine.avf

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AvfSmokeTestExecutorTest {
    @Test
    fun `all uncontrolled operations run in daemon futures`() {
        val threads = mutableListOf<Pair<String, Boolean>>()
        val result = executor().execute(
            setup = { threads += currentThread(); Unit },
            create = { threads += currentThread(); Any() },
            run = { threads += currentThread() },
            stop = { threads += currentThread() },
            deleteByFixedName = { threads += currentThread() },
        )

        assertFalse(result.timedOut)
        assertEquals(5, threads.size)
        assertTrue(threads.all { (name, daemon) ->
            daemon && name.startsWith("avf-smoke-")
        })
    }

    @Test
    fun `caller deadline is hard when run ignores interruption`() {
        val release = AtomicBoolean(false)
        val deleteCalls = AtomicInteger()
        val startedNanos = System.nanoTime()
        val result = executor(operationTimeoutMs = 75, cleanupTimeoutMs = 200).execute(
            setup = { Unit },
            create = { Any() },
            run = { blockIgnoringInterrupts(release) },
            stop = { },
            deleteByFixedName = { deleteCalls.incrementAndGet() },
        )
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)
        release.set(true)

        assertTrue(result.timedOut)
        assertEquals("run", result.timeoutStage)
        assertEquals(1, deleteCalls.get())
        assertTrue("elapsed=${elapsedMs}ms", elapsedMs < 750)
    }

    @Test
    fun `create failure still attempts independent named delete`() {
        val stopCalls = AtomicInteger()
        val deleteCalls = AtomicInteger()
        val result = executor().execute(
            setup = { Unit },
            create = { error("create rejected") },
            run = { _: Any -> },
            stop = { _: Any -> stopCalls.incrementAndGet() },
            deleteByFixedName = { deleteCalls.incrementAndGet() },
        )

        assertFalse(result.timedOut)
        assertNotNull(result.failure)
        assertEquals("create rejected", result.failure?.message)
        assertEquals(0, stopCalls.get())
        assertTrue(result.deleteAttempted)
        assertEquals(1, deleteCalls.get())
    }

    @Test
    fun `create timeout returns while create ignores interrupt and still deletes by name`() {
        val release = AtomicBoolean(false)
        val deleteCalls = AtomicInteger()
        val startedNanos = System.nanoTime()
        val result = executor(operationTimeoutMs = 75, cleanupTimeoutMs = 200).execute(
            setup = { Unit },
            create = {
                blockIgnoringInterrupts(release)
                Any()
            },
            run = { },
            stop = { },
            deleteByFixedName = { deleteCalls.incrementAndGet() },
        )
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)
        release.set(true)

        assertTrue(result.timedOut)
        assertEquals("create", result.timeoutStage)
        assertTrue(result.deleteAttempted)
        assertEquals(1, deleteCalls.get())
        assertTrue("elapsed=${elapsedMs}ms", elapsedMs < 750)
    }

    @Test
    fun `stuck stop cannot suppress independent bounded delete`() {
        val releaseStop = AtomicBoolean(false)
        val deleteCalls = AtomicInteger()
        val startedNanos = System.nanoTime()
        val result = executor(operationTimeoutMs = 75, cleanupTimeoutMs = 200).execute(
            setup = { Unit },
            create = { Any() },
            run = { },
            stop = { blockIgnoringInterrupts(releaseStop) },
            deleteByFixedName = { deleteCalls.incrementAndGet() },
        )
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)
        releaseStop.set(true)

        assertTrue(result.timedOut)
        assertEquals("stop", result.timeoutStage)
        assertEquals(1, deleteCalls.get())
        assertTrue("elapsed=${elapsedMs}ms", elapsedMs < 750)
    }

    @Test
    fun `stuck attempt keeps gate active and later call fails fast`() {
        val gate = AvfSmokeAttemptGate()
        val releaseCreate = AtomicBoolean(false)
        val first = executor(
            operationTimeoutMs = 50,
            cleanupTimeoutMs = 100,
            gate = gate,
        ).execute(
            setup = { Unit },
            create = {
                blockIgnoringInterrupts(releaseCreate)
                Any()
            },
            run = { },
            stop = { },
            deleteByFixedName = { },
        )

        val secondStartedNanos = System.nanoTime()
        val second = executor(gate = gate).execute(
            setup = { Unit },
            create = { Any() },
            run = { },
            stop = { },
            deleteByFixedName = { },
        )
        val secondElapsedMs = TimeUnit.NANOSECONDS.toMillis(
            System.nanoTime() - secondStartedNanos,
        )
        releaseCreate.set(true)

        assertTrue(first.timedOut)
        assertTrue(second.busy)
        assertFalse(second.deleteAttempted)
        assertTrue("elapsed=${secondElapsedMs}ms", secondElapsedMs < 100)
    }

    @Test
    fun `interrupted caller still schedules named delete before propagating`() {
        val createEntered = CountDownLatch(1)
        val releaseCreate = AtomicBoolean(false)
        val deleteCalls = AtomicInteger()
        val callerFailure = AtomicReference<Throwable?>()
        val caller = Thread {
            try {
                executor(operationTimeoutMs = 5_000).execute(
                    setup = { Unit },
                    create = {
                        createEntered.countDown()
                        blockIgnoringInterrupts(releaseCreate)
                        Any()
                    },
                    run = { },
                    stop = { },
                    deleteByFixedName = { deleteCalls.incrementAndGet() },
                )
            } catch (failure: Throwable) {
                callerFailure.set(failure)
            }
        }
        caller.start()
        assertTrue(createEntered.await(1, TimeUnit.SECONDS))
        caller.interrupt()
        caller.join(1_000)
        releaseCreate.set(true)

        assertFalse(caller.isAlive)
        assertTrue(callerFailure.get() is InterruptedException)
        assertEquals(1, deleteCalls.get())
    }

    @Test
    fun `named delete timeout is bounded and reported without claiming termination`() {
        val releaseDelete = AtomicBoolean(false)
        val result = executor(operationTimeoutMs = 500, cleanupTimeoutMs = 50).execute(
            setup = { Unit },
            create = { Any() },
            run = { },
            stop = { },
            deleteByFixedName = { blockIgnoringInterrupts(releaseDelete) },
        )
        releaseDelete.set(true)

        assertFalse(result.timedOut)
        assertNotNull(result.deleteFailure)
        assertTrue(result.deleteFailure?.message?.contains("may still be running") == true)
    }

    private fun executor(
        operationTimeoutMs: Long = 1_000,
        cleanupTimeoutMs: Long = 500,
        gate: AvfSmokeAttemptGate = AvfSmokeAttemptGate(),
    ) = AvfSmokeTestExecutor(
        operationTimeoutMs = operationTimeoutMs,
        cleanupTimeoutMs = cleanupTimeoutMs,
        startupObservationDelayMs = 0,
        attemptGate = gate,
    )

    private fun currentThread(): Pair<String, Boolean> =
        Thread.currentThread().let { it.name to it.isDaemon }

    private fun blockIgnoringInterrupts(release: AtomicBoolean) {
        while (!release.get()) {
            try {
                Thread.sleep(10)
            } catch (_: InterruptedException) {
                // Simulates a vendor Binder/reflection call that ignores interrupt.
            }
        }
    }
}
