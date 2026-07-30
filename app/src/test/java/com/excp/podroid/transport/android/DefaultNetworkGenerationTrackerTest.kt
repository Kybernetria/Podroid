package com.excp.podroid.transport.android

import java.net.InetAddress
import java.util.concurrent.TimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultNetworkGenerationTrackerTest {
    @Test
    fun `default network events are monotonic and stale network callbacks are ignored`() {
        val tracker = DefaultNetworkGenerationTracker<String>()
        assertEquals(1, tracker.available("network-a")!!.generation)
        assertEquals(
            2,
            tracker.changed("network-a", DefaultNetworkChange.CAPABILITIES_CHANGED)!!.generation,
        )
        assertNull(tracker.changed("network-b", DefaultNetworkChange.LINK_PROPERTIES_CHANGED))
        assertNull(tracker.lost("network-b"))
        val replacement = tracker.available("network-b")!!
        assertEquals(3, replacement.generation)
        assertNull(tracker.lost("network-a"))
        val lost = tracker.lost("network-b")!!
        assertEquals(4, lost.generation)
        assertNull(lost.network)
        assertEquals(DefaultNetworkChange.LOST, lost.change)
    }

    @Test
    fun `close suppresses callbacks and does not create another generation`() {
        val tracker = DefaultNetworkGenerationTracker<String>()
        tracker.available("network-a")
        tracker.close()
        assertNull(tracker.available("network-b"))
        assertNull(tracker.lost("network-a"))
    }

    @Test
    fun `bounded network DNS uses selected network and limits raw results`() {
        val first = InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1))
        val second = InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))
        var observed: Pair<String, String>? = null
        val resolver = BoundedPerNetworkDnsResolver<String> { network, hostname ->
            observed = network to hostname
            arrayOf(first, first, second)
        }
        try {
            val request = NetworkDnsRequest("host.example.test", timeoutMillis = 1_000, maxAddresses = 3)
            assertEquals(listOf(first, second), resolver.resolve("network-a", request))
            assertEquals("network-a" to "host.example.test", observed)
        } finally {
            resolver.close()
        }

        val limited = BoundedPerNetworkDnsResolver<String> { _, _ -> arrayOf(first, first) }
        try {
            val failure = runCatching {
                limited.resolve(
                    "network-b",
                    NetworkDnsRequest("host.example.test", timeoutMillis = 1_000, maxAddresses = 1),
                )
            }.exceptionOrNull()
            assertTrue(failure is DnsResultLimitException)
        } finally {
            limited.close()
        }
    }

    @Test
    fun `network DNS wait is bounded and close rejects later work`() {
        val resolver = BoundedPerNetworkDnsResolver<String> { _, _ ->
            Thread.sleep(10_000)
            emptyArray()
        }
        val failure = runCatching {
            resolver.resolve(
                "network-a",
                NetworkDnsRequest("host.example.test", timeoutMillis = 25, maxAddresses = 1),
            )
        }.exceptionOrNull()
        assertTrue(failure is TimeoutException)

        resolver.close()
        assertTrue(runCatching {
            resolver.resolve(
                "network-a",
                NetworkDnsRequest("host.example.test", timeoutMillis = 25, maxAddresses = 1),
            )
        }.isFailure)
    }

    @Test
    fun `per-socket binder applies only the supplied network and socket`() {
        val network = Any()
        val socket = Any()
        var observedNetwork: Any? = null
        var observedSocket: Any? = null
        val binder = PerSocketNetworkBinder<Any, Any> { selectedNetwork, selectedSocket ->
            observedNetwork = selectedNetwork
            observedSocket = selectedSocket
        }

        binder.bind(network, socket)

        assertSame(network, observedNetwork)
        assertSame(socket, observedSocket)
    }
}
