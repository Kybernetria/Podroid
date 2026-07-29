package com.excp.podroid.engine.avf

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AvfSmokeTestExecutorTest {
    @Test
    fun `all blocking operations use injected IO dispatcher`() = runBlocking {
        val threads = mutableListOf<String>()
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "avf-smoke-io") }
            .asCoroutineDispatcher().use { dispatcher ->
                val result = AvfSmokeTestExecutor(
                    ioDispatcher = dispatcher,
                    operationTimeoutMs = 1_000,
                    cleanupTimeoutMs = 1_000,
                    startupObservationDelayMs = 0,
                ).execute(
                    create = { threads += Thread.currentThread().name; Any() },
                    run = { threads += Thread.currentThread().name },
                    stop = { threads += Thread.currentThread().name },
                    delete = { threads += Thread.currentThread().name },
                )

                assertFalse(result.timedOut)
                assertTrue(threads.isNotEmpty())
                assertTrue(threads.all { it.startsWith("avf-smoke-io") })
            }
    }

    @Test
    fun `deadline interrupts run and still attempts stop and delete`() = runBlocking {
        val interrupted = AtomicBoolean(false)
        val stopCalls = AtomicInteger()
        val deleteCalls = AtomicInteger()
        val result = AvfSmokeTestExecutor(
            operationTimeoutMs = 75,
            cleanupTimeoutMs = 500,
            startupObservationDelayMs = 0,
        ).execute(
            create = { Any() },
            run = {
                try {
                    Thread.sleep(5_000)
                } catch (failure: InterruptedException) {
                    interrupted.set(true)
                    throw failure
                }
            },
            stop = { stopCalls.incrementAndGet() },
            delete = { deleteCalls.incrementAndGet() },
        )

        assertTrue(result.timedOut)
        assertTrue(interrupted.get())
        assertEquals(1, stopCalls.get())
        assertEquals(1, deleteCalls.get())
    }

    @Test
    fun `run failure preserves failure and cleanup attempts`() = runBlocking {
        val stopCalls = AtomicInteger()
        val deleteCalls = AtomicInteger()
        val result = AvfSmokeTestExecutor(
            operationTimeoutMs = 1_000,
            cleanupTimeoutMs = 500,
            startupObservationDelayMs = 0,
        ).execute(
            create = { Any() },
            run = { error("run rejected") },
            stop = { stopCalls.incrementAndGet() },
            delete = { deleteCalls.incrementAndGet() },
        )

        assertNotNull(result.failure)
        assertEquals("run rejected", result.failure?.message)
        assertEquals(1, stopCalls.get())
        assertEquals(1, deleteCalls.get())
    }

    @Test
    fun `stop cleanup timeout cannot suppress bounded delete`() = runBlocking {
        val stopInterrupted = AtomicBoolean(false)
        val deleteCalls = AtomicInteger()
        val result = AvfSmokeTestExecutor(
            operationTimeoutMs = 1_000,
            cleanupTimeoutMs = 75,
            startupObservationDelayMs = 0,
        ).execute(
            create = { Any() },
            run = { },
            stop = {
                try {
                    Thread.sleep(5_000)
                } catch (failure: InterruptedException) {
                    stopInterrupted.set(true)
                    throw failure
                }
            },
            delete = { deleteCalls.incrementAndGet() },
        )

        assertNotNull(result.stopFailure)
        assertTrue(stopInterrupted.get())
        assertEquals(1, deleteCalls.get())
    }

    @Test
    fun `caller cancellation remains cancellation after cleanup`() = runBlocking {
        val entered = CountDownLatch(1)
        val stopCalls = AtomicInteger()
        val deleteCalls = AtomicInteger()
        val executor = AvfSmokeTestExecutor(
            operationTimeoutMs = 5_000,
            cleanupTimeoutMs = 500,
            startupObservationDelayMs = 0,
        )
        val job = launch(Dispatchers.Default) {
            executor.execute(
                create = { Any() },
                run = {
                    entered.countDown()
                    Thread.sleep(5_000)
                },
                stop = { stopCalls.incrementAndGet() },
                delete = { deleteCalls.incrementAndGet() },
            )
        }

        assertTrue(entered.await(1, TimeUnit.SECONDS))
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertEquals(1, stopCalls.get())
        assertEquals(1, deleteCalls.get())
    }
}
