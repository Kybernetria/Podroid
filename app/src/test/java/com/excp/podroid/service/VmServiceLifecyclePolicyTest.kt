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
