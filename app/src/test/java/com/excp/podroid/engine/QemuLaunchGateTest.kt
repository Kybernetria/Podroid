package com.excp.podroid.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QemuLaunchGateTest {
    @Test
    fun `force during stalled preparation prevents later process launch`() = runBlocking {
        val gate = QemuLaunchGate()
        val generation = gate.begin()
        val preparationStalled = CompletableDeferred<Unit>()
        val releasePreparation = CompletableDeferred<Unit>()
        var launches = 0

        val start = async {
            preparationStalled.complete(Unit)
            releasePreparation.await()
            if (gate.mayLaunch(generation)) launches++
        }

        preparationStalled.await()
        assertEquals(generation, gate.requestStop())
        releasePreparation.complete(Unit)
        start.await()

        assertEquals(0, launches)
        assertFalse(gate.mayLaunch(generation))
        gate.complete(generation)
    }

    @Test
    fun `process assignment is committed only while generation remains launchable`() {
        val gate = QemuLaunchGate()
        val generation = gate.begin()
        var assigned = false

        gate.requestStop()

        assertFalse(gate.commitLaunch(generation) { assigned = true })
        assertFalse(assigned)
        gate.complete(generation)
        val replacement = gate.begin()
        assertTrue(gate.commitLaunch(replacement) { assigned = true })
        assertTrue(assigned)
    }
}
