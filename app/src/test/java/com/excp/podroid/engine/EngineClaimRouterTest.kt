package com.excp.podroid.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineClaimRouterTest {
    private class FakeEngine(val name: String) {
        var starts = 0
        var removals = 0
        var quiescent = true
    }

    @Test
    fun `selection paused after quiescence cannot replace a concurrent start claim`() = runBlocking {
        val old = FakeEngine("old")
        val next = FakeEngine("next")
        val router = EngineClaimRouter(old)
        val selectionPaused = CompletableDeferred<Unit>()
        val releaseSelection = CompletableDeferred<Unit>()

        val selection = async {
            val observed = router.selectedSnapshot
            assertTrue(observed.quiescent)
            selectionPaused.complete(Unit)
            releaseSelection.await()
            router.publishSelection(observed, next) { it.quiescent }
        }

        selectionPaused.await()
        val claimed = router.claimSelected { it.quiescent }
        claimed.quiescent = false
        claimed.starts++
        releaseSelection.complete(Unit)

        assertFalse(selection.await())
        assertSame(old, router.routed.value)
        router.routed.value.removals++
        assertEquals(1, old.starts)
        assertEquals(0, next.starts)
        assertEquals(1, old.removals)
        assertEquals(0, next.removals)

        old.quiescent = true
        assertTrue(router.releaseClaim(old))
        assertTrue(router.publishSelection(old, next) { it.quiescent })
        assertSame(next, router.routed.value)
    }

    @Test
    fun `stale cleanup cannot release a newer engine claim`() = runBlocking {
        val first = FakeEngine("first")
        val second = FakeEngine("second")
        val router = EngineClaimRouter(first)

        assertSame(first, router.claimSelected { true })
        assertTrue(router.releaseClaim(first))
        assertTrue(router.publishSelection(first, second) { true })
        assertSame(second, router.claimSelected { true })

        assertFalse(router.releaseClaim(first))
        assertSame(second, router.routed.value)
    }
}
