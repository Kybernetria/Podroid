package com.excp.podroid.service

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceCommandOrderTest {
    @Test
    fun `prepare commits before Intent dispatch and generation equals transaction order`() = runBlocking {
        val order = ServiceCommandOrder()
        val events = mutableListOf<String>()
        var durableId = 0L

        val admission = order.admitAndDispatch(
            latestDurableGeneration = { durableId },
            prepare = { generation ->
                events += "prepare:$generation"
                durableId = generation
                "token-$generation"
            },
            dispatch = { events += "dispatch:${it.generation}" },
        )

        assertEquals(1L, admission.generation)
        assertEquals("token-1", admission.prepared)
        assertEquals(listOf("prepare:1", "dispatch:1"), events)
    }

    @Test
    fun `startForegroundService throw abandons prepared token and rearms recovery`() = runBlocking {
        val order = ServiceCommandOrder()
        val events = mutableListOf<String>()
        var durableId = 1L

        val failure = runCatching {
            order.admitAndDispatch(
                latestDurableGeneration = { durableId },
                prepare = { generation ->
                    durableId = generation
                    "token-$generation"
                },
                abandon = { token, cause ->
                    events += "abandon:$token:${cause.javaClass.simpleName}"
                    events += "reconcile"
                },
                dispatch = { throw IOException("startForegroundService rejected") },
            )
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals(
            listOf("abandon:token-2:IOException", "reconcile"),
            events,
        )
        assertFalse(order.deliverAndExecute(1L, { true }) { error("stale effect") }.execute)
    }

    @Test
    fun `cancellation after prepare runs non cancellable abandonment`() = runBlocking {
        val order = ServiceCommandOrder()
        var abandoned = false

        val failure = runCatching {
            order.admitAndDispatch(
                latestDurableGeneration = { 0L },
                prepare = { "token-$it" },
                abandon = { _, cause ->
                    assertTrue(cause is CancellationException)
                    abandoned = true
                },
                dispatch = { throw CancellationException("caller cancelled") },
            )
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertTrue(abandoned)
    }

    @Test
    fun `later reservation blocks delayed older delivery`() = runBlocking {
        val order = ServiceCommandOrder()
        var durableId = 1L // prepared older Intent is delayed
        val newer = order.admitAndDispatch(
            latestDurableGeneration = { durableId },
            prepare = { generation -> durableId = generation; generation },
            dispatch = { },
        )

        assertEquals(2L, newer.generation)
        assertFalse(order.deliverAndExecute(1L, { true }) { error("stale effect") }.execute)
        assertTrue(order.deliverAndExecute(2L, { true }) { }.execute)
    }

    @Test
    fun `delivery cannot pass a newer prepare already admitted to ordering`() = runBlocking {
        val order = ServiceCommandOrder()
        var durableId = 1L
        val prepareEntered = CompletableDeferred<Unit>()
        val releasePrepare = CompletableDeferred<Unit>()
        val newer = async(Dispatchers.Default) {
            order.admitAndDispatch(
                latestDurableGeneration = { durableId },
                prepare = { generation ->
                    prepareEntered.complete(Unit)
                    releasePrepare.await()
                    durableId = generation
                    generation
                },
                dispatch = { },
            )
        }
        prepareEntered.await()
        val olderExecuted = CompletableDeferred<Boolean>()
        val older = async(Dispatchers.Default) {
            order.deliverAndExecute(1L, { true }) { olderExecuted.complete(true) }
        }

        assertFalse(olderExecuted.isCompleted)
        releasePrepare.complete(Unit)
        newer.await()
        assertFalse(older.await().execute)
    }

    @Test
    fun `duplicate prepared delivery executes exactly once`() = runBlocking {
        val order = ServiceCommandOrder()

        assertTrue(order.deliverAndExecute(42L, { true }) { }.execute)
        assertFalse(order.deliverAndExecute(42L, { true }) { error("duplicate effect") }.execute)
        assertFalse(order.deliverAndExecute(41L, { true }) { error("stale effect") }.execute)
    }

    @Test
    fun `recreated duplicate token is validated before coordinator effects`() = runBlocking {
        val order = ServiceCommandOrder()
        var effect = false

        val delivery = order.deliverAndExecute(42L, validatePrepared = { false }) {
            effect = true
        }

        assertFalse(delivery.execute)
        assertFalse(effect)
        var prepared = 0L
        order.admitAndDispatch(
            latestDurableGeneration = { 7L },
            prepare = { prepared = it; it },
            dispatch = { },
        )
        assertEquals(8L, prepared)
    }

    @Test
    fun `process recreation adopts durable generation before new prepare`() = runBlocking {
        val order = ServiceCommandOrder()
        var preparedGeneration = 0L

        order.admitAndDispatch(
            latestDurableGeneration = { 41L },
            prepare = { generation -> preparedGeneration = generation; generation },
            dispatch = { },
        )

        assertEquals(42L, preparedGeneration)
    }
}
