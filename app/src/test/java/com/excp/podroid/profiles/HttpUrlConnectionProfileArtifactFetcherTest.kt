package com.excp.podroid.profiles

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.security.Principal
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HttpUrlConnectionProfileArtifactFetcherTest {
    private val origins = ApprovedArtifactOrigins.of(ORIGIN)
    private val url = origins.parseUrl("$ORIGIN/profile/envelope.json")

    @Test fun `fetch enforces GET identity no redirects and returns hostile metadata`() {
        val connection = FakeHttpsConnection(URL(url.value)).apply {
            responseStatus = 206
            responseBytes = "payload".toByteArray()
            responseEncoding = "gzip"
        }
        val fetcher = fetcher(connection)

        val response = fetcher.fetch(request(maxBytes = 20))

        assertEquals("GET", connection.requestMethod)
        assertEquals("identity", connection.getRequestProperty("Accept-Encoding"))
        assertFalse(connection.instanceFollowRedirects)
        assertFalse(connection.useCaches)
        assertTrue(connection.doInput)
        assertFalse(connection.doOutput)
        assertEquals(206, response.statusCode)
        assertEquals(url.value, response.finalUrl)
        assertEquals(0, response.redirectCount)
        assertEquals("gzip", response.contentEncoding)
        assertEquals(7L, response.contentLengthBytes)
        assertFalse(connection.disconnected)
        response.close()
        assertTrue(connection.bodyClosed)
        assertTrue(connection.disconnected)
    }

    @Test fun `redirect responses are never followed and report their original status`() {
        val connection = FakeHttpsConnection(URL(url.value)).apply {
            responseStatus = 302
        }
        val response = fetcher(connection).fetch(request(maxBytes = 1))

        response.use {
            assertEquals(302, it.statusCode)
            assertEquals(url.value, it.finalUrl)
            assertEquals(0, it.redirectCount)
        }
        assertFalse(connection.instanceFollowRedirects)
        assertTrue(connection.disconnected)
    }

    @Test fun `a connection final URL change is rejected and disconnected`() {
        val connection = FakeHttpsConnection(URL(url.value)).apply {
            finalUrlAfterResponse = URL("$ORIGIN/other")
        }

        assertIOException { fetcher(connection).fetch(request(maxBytes = 1)) }

        assertTrue(connection.disconnected)
        assertFalse(connection.inputOpened)
    }

    @Test fun `content length above caller bound is rejected and disconnected before body transfer`() {
        val connection = FakeHttpsConnection(URL(url.value)).apply {
            responseBytes = ByteArray(11)
        }

        assertIOException { fetcher(connection).fetch(request(maxBytes = 10)) }

        assertTrue(connection.disconnected)
        assertFalse(connection.inputOpened)
    }

    @Test fun `response stream never exposes more than the caller byte bound`() {
        val connection = FakeHttpsConnection(URL(url.value)).apply {
            responseBytes = ByteArray(9) { it.toByte() }
            reportedContentLength = -1
        }
        val response = fetcher(connection).fetch(request(maxBytes = 5))

        response.use {
            assertEquals(5, it.body.readBytes().size)
        }
        assertTrue(connection.disconnected)
    }

    @Test fun `connect and reads use the smaller configured or overall deadline`() {
        var now = 0L
        val connection = FakeHttpsConnection(URL(url.value)).apply {
            responseBytes = byteArrayOf(1)
        }
        val fetcher = HttpUrlConnectionProfileArtifactFetcher(
            connectionFactory = HttpsConnectionFactory { connection },
            connectTimeoutMillis = 8_000,
            readTimeoutMillis = 9_000,
            nanoTime = { now },
        )
        val response = fetcher.fetch(request(maxBytes = 2, deadlineNanos = 3_500_000_000L))

        assertEquals(3_500, connection.connectTimeout)
        assertEquals(3_500, connection.readTimeout)
        now = 3_500_000_000L
        assertIOException { response.body.read() }
        response.close()
        assertTrue(connection.disconnected)
    }

    @Test fun `deadline expiry during stream acquisition closes body and disconnects`() {
        var now = 0L
        val connection = FakeHttpsConnection(URL(url.value)).apply {
            responseBytes = byteArrayOf(1)
            onInputOpened = { now = 2_000_000_000L }
        }
        val fetcher = HttpUrlConnectionProfileArtifactFetcher(
            connectionFactory = HttpsConnectionFactory { connection },
            connectTimeoutMillis = 1_000,
            readTimeoutMillis = 1_000,
            nanoTime = { now },
        )

        assertIOException {
            fetcher.fetch(request(maxBytes = 1, deadlineNanos = 2_000_000_000L))
        }

        assertTrue(connection.bodyClosed)
        assertTrue(connection.disconnected)
    }

    @Test fun `overall deadlines above the production bound are rejected before connection`() {
        var opens = 0
        val fetcher = HttpUrlConnectionProfileArtifactFetcher(
            connectionFactory = HttpsConnectionFactory {
                opens++
                FakeHttpsConnection(it)
            },
            connectTimeoutMillis = 1_000,
            readTimeoutMillis = 1_000,
            nanoTime = { 0L },
        )

        try {
            fetcher.fetch(request(maxBytes = 1, deadlineNanos = 31L * 60L * 1_000_000_000L))
            fail("Expected invalid deadline")
        } catch (_: IllegalArgumentException) {
            Unit
        }
        assertEquals(0, opens)
    }

    @Test fun `interrupted caller is cancelled before opening a connection`() {
        var opens = 0
        val fetcher = HttpUrlConnectionProfileArtifactFetcher(
            connectionFactory = HttpsConnectionFactory {
                opens++
                FakeHttpsConnection(it)
            },
            connectTimeoutMillis = 1_000,
            readTimeoutMillis = 1_000,
            nanoTime = { 0L },
        )

        Thread.currentThread().interrupt()
        try {
            assertIOException { fetcher.fetch(request(maxBytes = 1)) }
        } finally {
            Thread.interrupted()
        }
        assertEquals(0, opens)
    }

    private fun fetcher(connection: FakeHttpsConnection) = HttpUrlConnectionProfileArtifactFetcher(
        connectionFactory = HttpsConnectionFactory { connection },
        connectTimeoutMillis = 5_000,
        readTimeoutMillis = 6_000,
        nanoTime = { 0L },
    )

    private fun request(maxBytes: Long, deadlineNanos: Long = 10_000_000_000L) = ArtifactFetchRequest(
        url = url,
        maxResponseBytes = maxBytes,
        deadlineNanos = deadlineNanos,
    )

    private fun assertIOException(action: () -> Unit) {
        try {
            action()
            fail("Expected IOException")
        } catch (_: IOException) {
            Unit
        }
    }

    private class FakeHttpsConnection(url: URL) : HttpsURLConnection(url) {
        var responseStatus = 200
        var responseBytes = ByteArray(0)
        var responseEncoding: String? = null
        var reportedContentLength: Long? = null
        var finalUrlAfterResponse: URL? = null
        var onInputOpened: () -> Unit = {}
        var disconnected = false
        var inputOpened = false
        var bodyClosed = false

        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int {
            finalUrlAfterResponse?.let { url = it }
            return responseStatus
        }
        override fun getContentEncoding(): String? = responseEncoding
        override fun getContentLengthLong(): Long = reportedContentLength ?: responseBytes.size.toLong()
        override fun getInputStream(): InputStream {
            inputOpened = true
            onInputOpened()
            return object : ByteArrayInputStream(responseBytes) {
                override fun close() {
                    bodyClosed = true
                    super.close()
                }
            }
        }
        override fun getErrorStream(): InputStream? = null
        override fun getCipherSuite(): String = "TLS_FAKE"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getPeerPrincipal(): Principal = throw SSLPeerUnverifiedException("fake")
        override fun getLocalPrincipal(): Principal? = null
    }

    private companion object {
        const val ORIGIN = "https://profiles.example:443"
    }
}
