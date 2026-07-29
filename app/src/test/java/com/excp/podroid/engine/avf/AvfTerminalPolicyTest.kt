package com.excp.podroid.engine.avf

import com.excp.podroid.engine.VmState
import org.junit.Assert.assertEquals
import org.junit.Test

class AvfTerminalPolicyTest {
    @Test fun `real callback after rejected stop cleans retained Error handle`() {
        assertEquals(
            AvfTerminalPolicy.Decision.CLEANUP_RETAIN_ERROR,
            AvfTerminalPolicy.decide(7, 7, VmState.Error("stop rejected"), cleanupComplete = false),
        )
    }

    @Test fun `Error is ignored only after cleanup is complete`() {
        assertEquals(
            AvfTerminalPolicy.Decision.IGNORE,
            AvfTerminalPolicy.decide(7, 7, VmState.Error("stopped"), cleanupComplete = true),
        )
    }

    @Test fun `late callback from old VM cannot clean current resources`() {
        assertEquals(
            AvfTerminalPolicy.Decision.IGNORE,
            AvfTerminalPolicy.decide(6, 7, VmState.Running, cleanupComplete = false),
        )
    }
}
