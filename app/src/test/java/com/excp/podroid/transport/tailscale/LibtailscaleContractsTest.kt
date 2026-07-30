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
        assertTrue(TransportCapability.ANDROID_ABI_ARTIFACT in report.supported)
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
        val caller = "synthetic-one-use-value".toByteArray()
        val key = OneUseAuthKey.copyOf(caller)
        caller.fill('x'.code.toByte())
        lateinit var operationBytes: ByteArray
        var observed = ""
        key.useBytes {
            operationBytes = it
            observed = it.toString(Charsets.UTF_8)
        }
        assertEquals("synthetic-one-use-value", observed)
        assertTrue(operationBytes.all { it == 0.toByte() })

        key.close()

        assertTrue(runCatching { key.useBytes { } }.isFailure)
        key.close()
    }

    @Test
    fun `raw loopback credentials enforce exact reviewed size`() {
        val address = com.excp.podroid.transport.api.TransportEndpoint("127.0.0.1", 41112)
        val credentials = RawLoopbackCredentials.copyOf(address, ByteArray(32), ByteArray(32))
        lateinit var operationBytes: ByteArray
        credentials.useLocalApiCredential { operationBytes = it }
        assertTrue(operationBytes.all { it == 0.toByte() })
        credentials.close()
        assertTrue(runCatching { credentials.useProxyCredential { } }.isFailure)
        assertTrue(runCatching {
            RawLoopbackCredentials.copyOf(address, ByteArray(31), ByteArray(32))
        }.isFailure)
    }
}
