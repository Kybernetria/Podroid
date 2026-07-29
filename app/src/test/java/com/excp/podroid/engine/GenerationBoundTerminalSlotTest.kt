package com.excp.podroid.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class GenerationBoundTerminalSlotTest {
    private class FakeSession {
        var finishes = 0
        fun finish() { finishes++ }
    }

    @Test
    fun `QEMU cleanup between construction and registration rejects stale session and next run registers`() {
        val slot = GenerationBoundTerminalSlot<FakeSession>()
        val first = FakeSession()
        assertSame(first, register(slot, first, 1, 1, active = true, nonQuiescent = true))

        // The main-thread candidate exists, then cleanup wins the engine monitor.
        val delayedCandidate = FakeSession()
        slot.clear()?.finish()
        assertNull(register(slot, delayedCandidate, 1, null, active = false, nonQuiescent = false))
        assertEquals(1, first.finishes)
        assertEquals(1, delayedCandidate.finishes)
        assertNull(slot.current())

        val nextRun = FakeSession()
        assertSame(nextRun, register(slot, nextRun, 2, 2, active = true, nonQuiescent = true))
        assertEquals(0, nextRun.finishes)
    }

    @Test
    fun `duplicate candidate is terminated and cannot replace live generation slot`() {
        val slot = GenerationBoundTerminalSlot<FakeSession>()
        val selected = FakeSession()
        val duplicate = FakeSession()

        assertSame(selected, register(slot, selected, 3, 3, active = true, nonQuiescent = true))
        assertSame(selected, register(slot, duplicate, 3, 3, active = true, nonQuiescent = true))
        assertSame(selected, slot.current())
        assertEquals(0, selected.finishes)
        assertEquals(1, duplicate.finishes)
    }

    @Test
    fun `AVF adaptive cleanup between construction and registration cannot suppress next generation`() {
        val slot = GenerationBoundTerminalSlot<FakeSession>()
        val first = FakeSession()
        assertSame(first, register(slot, first, 7, 7, active = true, nonQuiescent = true))

        // Adaptive cleanup retains public non-quiescence but invalidates the old
        // active generation before registration and empties its terminal slot.
        val delayedCandidate = FakeSession()
        slot.clear()?.finish()
        assertNull(register(slot, delayedCandidate, 7, null, active = false, nonQuiescent = true))
        assertEquals(1, first.finishes)
        assertEquals(1, delayedCandidate.finishes)

        val replacement = FakeSession()
        assertSame(replacement, register(slot, replacement, 8, 8, active = true, nonQuiescent = true))
        assertEquals(0, replacement.finishes)
    }

    private fun register(
        slot: GenerationBoundTerminalSlot<FakeSession>,
        candidate: FakeSession,
        candidateGeneration: Long,
        currentGeneration: Long?,
        active: Boolean,
        nonQuiescent: Boolean,
    ): FakeSession? = slot.register(
        candidate,
        candidateGeneration,
        currentGeneration,
        active,
        nonQuiescent,
    ).also { it.rejected?.finish() }.selected
}
