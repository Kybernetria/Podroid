package com.excp.podroid.engine.avf

import java.util.Collections
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
    fun `all uncontrolled operations and ordered cleanup run in one daemon`() {
        val threads = mutableListOf<Pair<String, Boolean>>()
        val result = executor().execute(
            setup = { threads += currentThread(); Unit },
            create = { threads += currentThread(); Any() },
            run = { threads += currentThread() },
            stop = { threads += currentThread() },
            deleteByFixedName = { threads += currentThread() },
        )

        assertFalse(result.timedOut)
        assertFalse(result.cleanupPending)
        assertEquals(5, threads.size)
        assertTrue(threads.all { (name, daemon) ->
            daemon && name.startsWith("avf-smoke-operation-")
        })
        assertEquals(1, threads.map { it.first }.distinct().size)
    }

    @Test
    fun `create failure still attempts ordered named delete`() {
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
        assertFalse(result.cleanupPending)
    }

    @Test
    fun `late create is stopped then deleted and admission opens only after delete exits`() {
        val gate = AvfSmokeAttemptGate()
        val releaseCreate = AtomicBoolean(false)
        val releaseDelete = AtomicBoolean(false)
        val deleteEntered = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val first = executor(operationTimeoutMs = 60, gate = gate).execute(
            setup = { Unit },
            create = {
                events += "create-enter"
                blockIgnoringInterrupts(releaseCreate)
                events += "create-exit"
                Any()
            },
            run = { events += "run" },
            stop = { events += "stop" },
            deleteByFixedName = {
                events += "delete-enter"
                deleteEntered.countDown()
                blockIgnoringInterrupts(releaseDelete)
                events += "delete-exit"
            },
        )

        assertTrue(first.timedOut)
        assertEquals("create", first.timeoutStage)
        assertTrue(first.cleanupPending)
        assertFalse(first.deleteAttempted)
        assertTrue(smokeAttempt(gate).busy)

        releaseCreate.set(true)
        assertTrue(deleteEntered.await(1, TimeUnit.SECONDS))
        assertEquals(listOf("create-enter", "create-exit", "run", "stop", "delete-enter"), events.toList())
        assertTrue(smokeAttempt(gate).busy)

        releaseDelete.set(true)
        assertTrue(awaitCondition { events.contains("delete-exit") })
        assertFalse(awaitAdmittedAttempt(gate).busy)
    }

    @Test
    fun `late stop finishes before fixed-name delete`() {
        val gate = AvfSmokeAttemptGate()
        val releaseStop = AtomicBoolean(false)
        val deleteCalls = AtomicInteger()
        val events = Collections.synchronizedList(mutableListOf<String>())
        val first = executor(operationTimeoutMs = 60, gate = gate).execute(
            setup = { Unit },
            create = { Any() },
            run = { events += "run" },
            stop = {
                events += "stop-enter"
                blockIgnoringInterrupts(releaseStop)
                events += "stop-exit"
            },
            deleteByFixedName = {
                events += "delete"
                deleteCalls.incrementAndGet()
            },
        )

        assertTrue(first.timedOut)
        assertEquals("stop", first.timeoutStage)
        assertTrue(first.cleanupPending)
        assertEquals(0, deleteCalls.get())
        assertTrue(smokeAttempt(gate).busy)

        releaseStop.set(true)
        assertTrue(awaitCondition { deleteCalls.get() == 1 })
        assertEquals(listOf("run", "stop-enter", "stop-exit", "delete"), events.toList())
        assertFalse(awaitAdmittedAttempt(gate).busy)
    }

    @Test
    fun `forever stuck operation remains reported pending and keeps admission closed`() {
        val gate = AvfSmokeAttemptGate()
        val releaseCreate = AtomicBoolean(false)
        val first = executor(operationTimeoutMs = 50, gate = gate).execute(
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
        val second = smokeAttempt(gate)
        val secondElapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - secondStartedNanos)

        assertTrue(first.timedOut)
        assertTrue(first.cleanupPending)
        assertTrue(second.busy)
        assertTrue(second.cleanupPending)
        assertFalse(second.deleteAttempted)
        assertTrue("elapsed=${secondElapsedMs}ms", secondElapsedMs < 100)

        releaseCreate.set(true)
        assertFalse(awaitAdmittedAttempt(gate).busy)
    }

    @Test
    fun `stuck named delete is bounded but remains pending and holds admission`() {
        val gate = AvfSmokeAttemptGate()
        val releaseDelete = AtomicBoolean(false)
        val result = executor(
            operationTimeoutMs = 500,
            cleanupTimeoutMs = 50,
            gate = gate,
        ).execute(
            setup = { Unit },
            create = { Any() },
            run = { },
            stop = { },
            deleteByFixedName = { blockIgnoringInterrupts(releaseDelete) },
        )

        assertFalse(result.timedOut)
        assertTrue(result.cleanupPending)
        assertTrue(result.deleteAttempted)
        assertNotNull(result.deleteFailure)
        assertTrue(result.deleteFailure?.message?.contains("cleanup remains pending") == true)
        assertTrue(smokeAttempt(gate).busy)

        releaseDelete.set(true)
        assertFalse(awaitAdmittedAttempt(gate).busy)
    }

    @Test
    fun `interrupted caller propagates while daemon retains ordered cleanup obligation`() {
        val gate = AvfSmokeAttemptGate()
        val createEntered = CountDownLatch(1)
        val releaseCreate = AtomicBoolean(false)
        val deleteCalls = AtomicInteger()
        val callerFailure = AtomicReference<Throwable?>()
        val caller = Thread {
            try {
                executor(operationTimeoutMs = 5_000, gate = gate).execute(
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

        assertFalse(caller.isAlive)
        assertTrue(callerFailure.get() is InterruptedException)
        assertEquals(0, deleteCalls.get())
        assertTrue(smokeAttempt(gate).busy)

        releaseCreate.set(true)
        assertTrue(awaitCondition { deleteCalls.get() == 1 })
        assertFalse(awaitAdmittedAttempt(gate).busy)
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

    private fun smokeAttempt(gate: AvfSmokeAttemptGate) = executor(gate = gate).execute(
        setup = { Unit },
        create = { Any() },
        run = { },
        stop = { },
        deleteByFixedName = { },
    )

    private fun awaitAdmittedAttempt(gate: AvfSmokeAttemptGate): AvfSmokeTestExecutor.Result {
        val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        do {
            val result = smokeAttempt(gate)
            if (!result.busy) return result
            Thread.sleep(10)
        } while (System.nanoTime() < deadlineNanos)
        error("AVF smoke admission did not reopen")
    }

    private fun awaitCondition(condition: () -> Boolean): Boolean {
        val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadlineNanos) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

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
