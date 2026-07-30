package com.excp.podroid.management

import com.excp.podroid.transport.api.ProviderAvailability
import com.excp.podroid.transport.api.ProviderCapabilityReport
import com.excp.podroid.transport.tailscale.LibtailscaleSpikeProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagementCompositionGateTest {
    @Test
    fun `current unavailable provider keeps management disabled with missing capabilities`() {
        val decision = ManagementCompositionGate.evaluate(
            LibtailscaleSpikeProvider.capabilities(),
            SshProviderCapabilityReport(false, emptySet()),
            ManagementPersistenceCapabilities(false, false, false),
        )
        assertTrue(CompositionBlocker.TRANSPORT_PROVIDER_UNAVAILABLE in decision.blockers)
        assertTrue(CompositionBlocker.TRANSPORT_CAPABILITIES_MISSING in decision.blockers)
        assertTrue(CompositionBlocker.SSH_PROVIDER_UNAVAILABLE in decision.blockers)
        assertTrue(CompositionBlocker.RUNTIME_COMPOSITION_NOT_IMPLEMENTED in decision.blockers)
    }

    @Test
    fun `even complete fake evidence cannot produce a listener runtime or effect capability`() {
        val transport = ProviderCapabilityReport(
            providerId = "complete-test-provider",
            availability = ProviderAvailability.AVAILABLE,
            supported = ManagementCompositionGate.REQUIRED_TRANSPORT_CAPABILITIES,
            blockers = emptySet(),
            detail = "test-only complete capability evidence",
        )
        val decision = ManagementCompositionGate.evaluate(
            transport,
            SshProviderCapabilityReport(true, ManagementCompositionGate.REQUIRED_SSH_CAPABILITIES),
            ManagementPersistenceCapabilities(true, true, true),
        )
        assertEquals(setOf(CompositionBlocker.RUNTIME_COMPOSITION_NOT_IMPLEMENTED), decision.blockers)
    }

    @Test
    fun `all limits are closed and exact`() {
        assertEquals("podroid-management", ManagementLimits.SSH_USERNAME)
        assertEquals("podroid-management-v1", ManagementLimits.EXEC_COMMAND)
        assertEquals(4_096, ManagementLimits.MAX_REQUEST_BYTES)
        assertEquals(16_384, ManagementLimits.MAX_RESPONSE_BYTES)
        assertEquals(4, ManagementLimits.FRAME_HEADER_BYTES)
        assertEquals(4, ManagementLimits.MAX_SESSIONS)
        assertEquals(2, ManagementLimits.MAX_CHANNELS_PER_CONNECTION)
        assertEquals(1, ManagementLimits.MAX_REQUESTS_PER_EXEC)
        assertEquals(24L * 60L * 60L, ManagementLimits.MAX_CERT_VALIDITY_SECONDS)
        assertEquals("vm/default/ssh", ManagementLimits.GUEST_VIRTUAL_HOST)
        assertEquals(22, ManagementLimits.GUEST_VIRTUAL_PORT)
        assertEquals("127.0.0.1", ManagementLimits.GUEST_LOOPBACK_HOST)
        assertEquals(9_922, ManagementLimits.GUEST_LOOPBACK_PORT)
        assertEquals(1_024, ManagementLimits.MAX_LEDGER_ENTRIES)
        assertEquals(4_096, ManagementLimits.MAX_AUDIT_RECORDS)
    }
}
