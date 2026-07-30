package com.excp.podroid.transport.api

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportContractsTest {
    @Test
    fun `listen dial and IO inputs enforce explicit bounds`() {
        val deadline = TransportDeadline(100)
        val cancelled = TransportCancellation { true }
        val listen = ListenRequest(TransportEndpoint("host.tailnet", 443), 32, deadline, cancelled)
        assertEquals(32, listen.backlog)
        assertTrue(listen.cancellation.isCancellationRequested())
        assertTrue(runCatching { ListenRequest(TransportEndpoint("host", 1), 33, deadline) }.isFailure)
        assertTrue(runCatching { TransportEndpoint("host", 0) }.isFailure)
        assertTrue(runCatching { TransportEndpoint("x".repeat(254), 443) }.isFailure)

        assertEquals(40, deadline.remainingNanos(60))
        assertEquals(0, deadline.remainingNanos(101))
        assertTrue(runCatching {
            BoundedIoRequest(ByteArray(BoundedIoRequest.MAX_IO_BYTES + 1), deadline = deadline)
        }.isFailure)
        assertEquals(
            4,
            BoundedIoRequest(ByteArray(8), offset = 2, length = 4, deadline = deadline).length,
        )
        assertTrue(
            DialRequest(TransportEndpoint("peer.tailnet", 443), deadline, cancelled)
                .cancellation.isCancellationRequested(),
        )
        assertTrue(AcceptRequest(deadline, cancelled).cancellation.isCancellationRequested())
    }

    @Test
    fun `host and guest identities are separate constrained domain values`() {
        val host = HostTransportIdentity("podroid-host")
        val guest = GuestWorkloadIdentity("podroid-guest")
        assertEquals("podroid-host", host.stableName)
        assertEquals("podroid-guest", guest.stableName)
        assertTrue(host::class != guest::class)
        assertTrue(runCatching { HostTransportIdentity("Podroid Host") }.isFailure)
    }

    @Test
    fun `coordination configuration accepts only credential-free HTTPS authority`() {
        val config = HostTransportConfiguration(
            HostTransportIdentity("podroid-host"),
            URI("https://control.example.test"),
        )
        assertEquals("control.example.test", config.controlUrl.host)
        val request = OpenHostTransportRequest(config, TransportDeadline(100))
        assertEquals(config, request.configuration)
        assertTrue(runCatching {
            HostTransportConfiguration(HostTransportIdentity("host"), URI("http://control.example.test"))
        }.isFailure)
        assertTrue(runCatching {
            HostTransportConfiguration(HostTransportIdentity("host"), URI("https://user@control.example.test"))
        }.isFailure)
    }
}
