package com.excp.podroid.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QemuProcessOwnerTest {
    private class FakeChild

    @Test
    fun `cancel after child creation before delivery commits then force reaps before quiescence`() =
        runBlocking {
            val events = mutableListOf<String>()
            val childCreated = CompletableDeferred<Unit>()
            val deliverChild = CompletableDeferred<Unit>()
            val reapStarted = CompletableDeferred<Unit>()
            val allowReap = CompletableDeferred<Unit>()
            var quiescent = false
            val owner = owner(events, reapStarted, allowReap)

            val lifecycle = async {
                try {
                    owner.createAndCommit {
                        events += "created"
                        childCreated.complete(Unit)
                        deliverChild.await()
                        FakeChild()
                    }
                    awaitCancellation()
                } finally {
                    owner.forceDestroyAndReapCommitted()
                    quiescent = true
                    events += "quiescent"
                }
            }

            childCreated.await()
            lifecycle.cancel()
            deliverChild.complete(Unit)
            reapStarted.await()

            assertFalse(quiescent)
            assertFalse("cancellation cleanup must wait for reap", lifecycle.isCompleted)
            assertEquals(listOf("created", "committed", "destroy", "reap-started"), events)

            allowReap.complete(Unit)
            lifecycle.join()
            assertTrue(lifecycle.isCancelled)
            assertTrue(quiescent)
            assertEquals(
                listOf("created", "committed", "destroy", "reap-started", "reaped", "quiescent"),
                events,
            )
        }

    @Test
    fun `child rejected by atomic commit is forcibly destroyed and reaped`() = runBlocking {
        val events = mutableListOf<String>()
        val owner = QemuProcessOwner<FakeChild, Int>(
            commit = { events += "rejected"; false },
            forceDestroy = { events += "destroy" },
            reap = { events += "reaped"; 0 },
        )

        val result = owner.createAndCommit { events += "created"; FakeChild() }

        assertEquals(null, result)
        assertEquals(listOf("created", "rejected", "destroy", "reaped"), events)
    }

    @Test
    fun `cancel after commit force reaps before quiescence`() = runBlocking {
        val events = mutableListOf<String>()
        val committed = CompletableDeferred<Unit>()
        val reapStarted = CompletableDeferred<Unit>()
        val allowReap = CompletableDeferred<Unit>()
        var quiescent = false
        val owner = QemuProcessOwner<FakeChild, Int>(
            commit = {
                events += "committed"
                committed.complete(Unit)
                true
            },
            forceDestroy = { events += "destroy" },
            reap = {
                events += "reap-started"
                reapStarted.complete(Unit)
                allowReap.await()
                events += "reaped"
                0
            },
        )

        val lifecycle = async {
            try {
                owner.createAndCommit { events += "created"; FakeChild() }
                awaitCancellation()
            } finally {
                owner.forceDestroyAndReapCommitted()
                quiescent = true
                events += "quiescent"
            }
        }

        committed.await()
        lifecycle.cancel()
        reapStarted.await()

        assertFalse(quiescent)
        assertFalse("committed child must be reaped before completion", lifecycle.isCompleted)
        allowReap.complete(Unit)
        lifecycle.join()

        assertTrue(quiescent)
        assertEquals(
            listOf("created", "committed", "destroy", "reap-started", "reaped", "quiescent"),
            events,
        )
    }

    private fun owner(
        events: MutableList<String>,
        reapStarted: CompletableDeferred<Unit>,
        allowReap: CompletableDeferred<Unit>,
    ) = QemuProcessOwner<FakeChild, Int>(
        commit = { events += "committed"; true },
        forceDestroy = { events += "destroy" },
        reap = {
            events += "reap-started"
            reapStarted.complete(Unit)
            allowReap.await()
            events += "reaped"
            0
        },
    )
}
