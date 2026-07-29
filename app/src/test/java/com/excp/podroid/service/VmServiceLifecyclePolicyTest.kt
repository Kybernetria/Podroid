package com.excp.podroid.service

import com.excp.podroid.vm.VmLifecycleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VmServiceLifecyclePolicyTest {
    @Test
    fun `error remains supervised until later cleanup completes`() {
        val incomplete = VmServiceLifecyclePolicy.decide(
            VmLifecycleState.ERROR,
            quiescent = false,
            busy = true,
        )
        assertTrue(incomplete.retainSupervision)
        assertFalse(incomplete.teardown)
        assertEquals(RuntimeChannelDirective.KEEP, incomplete.runtimeChannels)
        assertEquals(VmServiceNotification.CLEANUP_INCOMPLETE, incomplete.notification)

        val cleaned = VmServiceLifecyclePolicy.decide(
            VmLifecycleState.ERROR,
            quiescent = true,
            busy = false,
        )
        assertFalse(cleaned.retainSupervision)
        assertTrue(cleaned.teardown)
        assertEquals(RuntimeChannelDirective.STOP, cleaned.runtimeChannels)
    }

    @Test
    fun `cleanup completed before first observer emission tears down`() {
        val firstEmission = VmServiceLifecyclePolicy.decide(
            VmLifecycleState.STOPPED,
            quiescent = true,
            busy = false,
            pendingStartOwned = false,
        )

        assertTrue(firstEmission.teardown)
        assertFalse(firstEmission.retainSupervision)
    }

    @Test
    fun `prior terminal replay is suppressed only during owned pending start`() {
        val replayDuringStart = VmServiceLifecyclePolicy.decide(
            VmLifecycleState.STOPPED,
            quiescent = true,
            busy = false,
            pendingStartOwned = true,
        )
        assertFalse(replayDuringStart.teardown)
        assertTrue(replayDuringStart.retainSupervision)

        val immediatelyReevaluatedAfterAcceptance = VmServiceLifecyclePolicy.decide(
            VmLifecycleState.STOPPED,
            quiescent = true,
            busy = false,
            pendingStartOwned = false,
        )
        assertTrue(immediatelyReevaluatedAfterAcceptance.teardown)
    }

    @Test
    fun `ACTION_START always supervises busy runtime without duplicate launch`() {
        val start = VmServiceStartPolicy.decide(
            managerBusy = true,
            pendingStartOwned = false,
        )
        assertTrue(start.armSupervision)
        assertTrue(start.acquireWakeLock)
        assertFalse(start.launchNewGeneration)

        val busyRuntime = VmServiceLifecyclePolicy.decide(
            VmLifecycleState.STARTING,
            quiescent = false,
            busy = true,
        )

        assertTrue(busyRuntime.retainSupervision)
        assertFalse(busyRuntime.teardown)
        assertEquals(VmServiceNotification.STARTING, busyRuntime.notification)
    }

    @Test
    fun `persistent cleanup rejection never tears down or drops runtime channels`() {
        repeat(3) {
            val rejected = VmServiceLifecyclePolicy.decide(
                VmLifecycleState.ERROR,
                quiescent = false,
                busy = true,
            )
            assertFalse(rejected.teardown)
            assertEquals(RuntimeChannelDirective.KEEP, rejected.runtimeChannels)
        }
    }
}
