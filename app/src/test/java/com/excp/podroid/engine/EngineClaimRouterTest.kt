package com.excp.podroid.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
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
        val claim = router.claimSelected { it.quiescent }
        claim.engine.quiescent = false
        claim.engine.starts++
        releaseSelection.complete(Unit)

        assertTrue(selection.await() is SelectionPublication.ClaimActive)
        assertSame(old, router.routed.value)
        router.routed.value.removals++
        assertEquals(1, old.starts)
        assertEquals(0, next.starts)
        assertEquals(1, old.removals)
        assertEquals(0, next.removals)

        old.quiescent = true
        assertTrue(router.releaseClaim(claim))
        assertSame(
            SelectionPublication.Published,
            router.publishSelection(old, next) { it.quiescent },
        )
        assertSame(next, router.routed.value)
    }

    @Test
    fun `same engine ABA stale release cannot clear newer generation`() = runBlocking {
        val engine = FakeEngine("same-singleton")
        val router = EngineClaimRouter(engine)

        val firstGeneration = router.claimSelected { true }
        assertTrue(router.releaseClaim(firstGeneration))
        val secondGeneration = router.claimSelected { true }

        assertFalse(router.releaseClaim(firstGeneration))
        assertSame(engine, router.routed.value)
        assertTrue(router.releaseClaim(secondGeneration))
    }

    @Test
    fun `swap paused between claim and start waits for start owner release`() = runBlocking {
        val old = FakeEngine("old")
        val next = FakeEngine("next")
        val router = EngineClaimRouter(old)
        val claim = router.claimSelected { it.quiescent }
        val publicationAttempted = CompletableDeferred<Unit>()

        val swap = async {
            val publication = router.publishSelection(old, next) { it.quiescent }
            assertTrue(publication is SelectionPublication.ClaimActive)
            publicationAttempted.complete(Unit)
            router.awaitClaimReleased(publication as SelectionPublication.ClaimActive)
            router.publishSelection(old, next) { it.quiescent }
        }

        publicationAttempted.await()
        yield()
        assertFalse("swap must not release a lifecycle claim", swap.isCompleted)
        assertSame(old, router.routed.value)

        // The owning start generation begins only after the swap has already
        // observed the otherwise-quiescent backend.
        claim.engine.quiescent = false
        claim.engine.starts++
        claim.engine.quiescent = true
        assertTrue(router.releaseClaim(claim))

        assertSame(SelectionPublication.Published, swap.await())
        assertSame(next, router.routed.value)
    }
}
