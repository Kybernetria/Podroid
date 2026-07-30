package com.excp.podroid.transport.tailscale

import com.excp.podroid.transport.api.HostTransportConfiguration
import com.excp.podroid.transport.api.HostTransportIdentity
import com.excp.podroid.transport.api.OpenHostTransportRequest
import com.excp.podroid.transport.api.ProviderAvailability
import com.excp.podroid.transport.api.TransportCapability
import com.excp.podroid.transport.api.TransportDeadline
import com.excp.podroid.transport.api.TransportProviderUnavailableException
import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibtailscaleContractsTest {
    @Test
    fun `current official pin reports required blockers and cannot open`() {
        val report = LibtailscaleSpikeProvider.capabilities()
        assertEquals(ProviderAvailability.UNAVAILABLE, report.availability)
        assertTrue(TransportCapability.TAILNET_LISTEN in report.supported)
        assertTrue(TransportCapability.TAILNET_DIAL in report.supported)
        assertTrue(TransportCapability.PER_NETWORK_SOCKET_BINDING in report.blockers)
        assertTrue(TransportCapability.PER_NETWORK_DNS in report.blockers)
        assertTrue(TransportCapability.DEFAULT_NETWORK_REBINDING in report.blockers)
        assertTrue(TransportCapability.DETERMINISTIC_CANCELLATION in report.blockers)
        assertTrue(TransportCapability.AUTHENTICATED_PEER_IDENTITY in report.blockers)

        val failure = runCatching {
            LibtailscaleSpikeProvider.open(
                OpenHostTransportRequest(
                    configuration = HostTransportConfiguration(
                        HostTransportIdentity("podroid-host"),
                        URI("https://control.example.test"),
                    ),
                    deadline = TransportDeadline(1),
                ),
            )
        }.exceptionOrNull()
        assertTrue(failure is TransportProviderUnavailableException)
    }

    @Test
    fun `one-use auth key copies input zeros owned bytes and rejects reuse`() {
        val caller = "tskey-example".toByteArray()
        val key = OneUseAuthKey.copyOf(caller)
        caller.fill('x'.code.toByte())
        lateinit var observed: ByteArray
        key.useBytes { observed = it }
        assertEquals("tskey-example", observed.toString(Charsets.UTF_8))

        key.close()

        assertTrue(observed.all { it == 0.toByte() })
        assertTrue(runCatching { key.useBytes { } }.isFailure)
        key.close()
    }

    @Test
    fun `raw loopback credentials enforce exact reviewed size`() {
        val address = com.excp.podroid.transport.api.TransportEndpoint("127.0.0.1", 41112)
        val credentials = RawLoopbackCredentials(address, ByteArray(32), ByteArray(32))
        assertEquals(32, credentials.proxyCredential.size)
        assertTrue(runCatching {
            RawLoopbackCredentials(address, ByteArray(31), ByteArray(32))
        }.isFailure)
    }
}
