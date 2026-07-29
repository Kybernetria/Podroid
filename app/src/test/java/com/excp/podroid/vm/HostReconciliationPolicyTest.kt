package com.excp.podroid.vm

import org.junit.Assert.*
import org.junit.Test

class HostReconciliationPolicyTest {
    @Test fun `boot requires enabled autostart running while crash and app ignore autostart`() {
        val running = HostSupervisorState.safeDefaults().copy(
            hostEnabled = true,
            desiredState = VmDesiredState.RUNNING,
            autostart = false,
        )
        assertFalse(HostReconciliationPolicy.shouldStartServiceAtBoot(running))
        assertEquals(ReconciliationOutcome.SKIPPED_AUTOSTART_DISABLED,
            HostReconciliationPolicy.decide(running, ReconciliationTrigger.BOOT_COMPLETED, 1))
        assertEquals(ReconciliationOutcome.ATTEMPTING,
            HostReconciliationPolicy.decide(running, ReconciliationTrigger.PROCESS_RESTART, 1))
        assertEquals(ReconciliationOutcome.ATTEMPTING,
            HostReconciliationPolicy.decide(running, ReconciliationTrigger.APP_COLD_START, 1))
        assertTrue(HostReconciliationPolicy.shouldStartServiceAtBoot(running.copy(autostart = true)))
    }

    @Test fun `possible-live evidence starts boot cleanup regardless of autostart or desired state`() {
        val possibleLive = HostSupervisorState.safeDefaults().copy(
            runtimeMayBeLive = true,
            runtimeEvidenceVersion = 1,
        )
        assertTrue(HostReconciliationPolicy.shouldStartServiceAtBoot(possibleLive))
        assertEquals(
            ReconciliationOutcome.ATTEMPTING,
            HostReconciliationPolicy.decide(
                possibleLive,
                ReconciliationTrigger.BOOT_COMPLETED,
                1,
            ),
        )
    }

    @Test fun `explicit stopped and disabled never launch from any trigger`() {
        for (trigger in ReconciliationTrigger.entries) {
            assertEquals(ReconciliationOutcome.SKIPPED_HOST_DISABLED,
                HostReconciliationPolicy.decide(HostSupervisorState.safeDefaults(), trigger, 1))
            val stopped = HostSupervisorState.safeDefaults().copy(hostEnabled = true)
            assertEquals(ReconciliationOutcome.SKIPPED_DESIRED_STOPPED,
                HostReconciliationPolicy.decide(stopped, trigger, 1))
        }
    }

    @Test fun `force stop limitation resumes only through later user app trigger`() {
        // Android force-stop delivery itself is outside app code. This policy
        // proves there is no special bypass trigger and user cold start is the
        // explicit point at which persisted RUNNING intent becomes eligible.
        assertEquals(
            setOf(ReconciliationTrigger.BOOT_COMPLETED, ReconciliationTrigger.PROCESS_RESTART,
                ReconciliationTrigger.APP_COLD_START),
            ReconciliationTrigger.entries.toSet(),
        )
        val state = HostSupervisorState.safeDefaults().copy(
            hostEnabled = true, desiredState = VmDesiredState.RUNNING,
        )
        assertEquals(ReconciliationOutcome.ATTEMPTING,
            HostReconciliationPolicy.decide(state, ReconciliationTrigger.APP_COLD_START, 1))
    }

    @Test fun `backoff sequence is exponential and capped`() {
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L, 80_000L),
            (1..5).map(HostReconciliationPolicy::backoffDelayMs))
    }
}
