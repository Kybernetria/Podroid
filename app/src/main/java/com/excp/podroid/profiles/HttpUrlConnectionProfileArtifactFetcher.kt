package com.excp.podroid.profiles

import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.math.min

internal fun interface HttpsConnectionFactory {
    @Throws(IOException::class)
    fun open(url: URL): HttpsURLConnection
}

/**
 * Strict synchronous HTTPS transport. Callers own the returned response and must close it;
 * closing its body always disconnects the underlying connection.
 */
class HttpUrlConnectionProfileArtifactFetcher internal constructor(
    private val connectionFactory: HttpsConnectionFactory,
    private val connectTimeoutMillis: Int,
    private val readTimeoutMillis: Int,
    private val nanoTime: () -> Long,
) : ProfileArtifactFetcher {
    constructor() : this(
        connectionFactory = HttpsConnectionFactory { url ->
            val connection = url.openConnection()
            connection as? HttpsURLConnection
                ?: throw IOException("profile transport requires an HTTPS connection")
        },
        connectTimeoutMillis = DEFAULT_CONNECT_TIMEOUT_MILLIS,
        readTimeoutMillis = DEFAULT_READ_TIMEOUT_MILLIS,
        nanoTime = System::nanoTime,
    )

    init {
        require(connectTimeoutMillis in 1..MAX_IO_TIMEOUT_MILLIS)
        require(readTimeoutMillis in 1..MAX_IO_TIMEOUT_MILLIS)
    }

    override fun fetch(request: ArtifactFetchRequest): ArtifactFetchResponse {
        require(request.maxResponseBytes in 1..ProfileLimits.MAX_ARTIFACT_BYTES + 1L) {
            "profile response byte bound is invalid"
        }
        val initialRemainingNanos = request.deadlineNanos - nanoTime()
        require(initialRemainingNanos in 1..MAX_OVERALL_TIMEOUT_NANOS) {
            "profile fetch overall deadline is outside the supported bound"
        }
        checkCancellationAndDeadline(request.deadlineNanos)
        val requestedUrl = URL(request.url.value)
        val connection = connectionFactory.open(requestedUrl)
        var transferred = false
        try {
            require(connection.url.toExternalForm() == request.url.value) {
                "profile connection changed the approved URL before transport"
            }
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.doInput = true
            connection.doOutput = false
            connection.useCaches = false
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.connectTimeout = boundedTimeoutMillis(
                request.deadlineNanos,
                connectTimeoutMillis,
            )
            connection.readTimeout = boundedTimeoutMillis(
                request.deadlineNanos,
                readTimeoutMillis,
            )

            checkCancellationAndDeadline(request.deadlineNanos)
            val statusCode = connection.responseCode
            checkCancellationAndDeadline(request.deadlineNanos)
            if (connection.url.toExternalForm() != request.url.value) {
                throw IOException("profile transport final URL changed")
            }
            val contentLength = connection.contentLengthLong.takeIf { it >= 0L }
            if (contentLength != null && contentLength > request.maxResponseBytes) {
                throw IOException("profile response Content-Length exceeds the byte bound")
            }
            connection.readTimeout = boundedTimeoutMillis(request.deadlineNanos, readTimeoutMillis)
            checkCancellationAndDeadline(request.deadlineNanos)
            val stream = responseStream(connection, statusCode)
            try {
                checkCancellationAndDeadline(request.deadlineNanos)
            } catch (failure: Throwable) {
                try {
                    stream.close()
                } catch (closeFailure: Throwable) {
                    failure.addSuppressed(closeFailure)
                }
                throw failure
            }
            val body = DeadlineBoundedConnectionInputStream(
                delegate = stream,
                connection = connection,
                maxResponseBytes = request.maxResponseBytes,
                deadlineNanos = request.deadlineNanos,
                readTimeoutMillis = readTimeoutMillis,
                nanoTime = nanoTime,
            )
            transferred = true
            return ArtifactFetchResponse(
                statusCode = statusCode,
                finalUrl = connection.url.toExternalForm(),
                redirectCount = 0,
                contentEncoding = connection.contentEncoding,
                contentLengthBytes = contentLength,
                body = body,
            )
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("profile fetch was cancelled").also { it.initCause(failure) }
        } finally {
            if (!transferred) connection.disconnect()
        }
    }

    private fun responseStream(connection: HttpsURLConnection, statusCode: Int): InputStream =
        if (statusCode >= 400) {
            connection.errorStream ?: ByteArrayInputStream(ByteArray(0))
        } else {
            connection.inputStream
        }

    private fun boundedTimeoutMillis(deadlineNanos: Long, configuredMillis: Int): Int {
        val remainingNanos = deadlineNanos - nanoTime()
        if (remainingNanos <= 0L) throw SocketTimeoutException("profile fetch deadline expired")
        val remainingMillis = ((remainingNanos - 1L) / NANOS_PER_MILLI + 1L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        return min(configuredMillis, remainingMillis).coerceAtLeast(1)
    }

    private fun checkCancellationAndDeadline(deadlineNanos: Long) {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedIOException("profile fetch was cancelled")
        }
        if (deadlineNanos - nanoTime() <= 0L) {
            throw SocketTimeoutException("profile fetch deadline expired")
        }
    }

    private class DeadlineBoundedConnectionInputStream(
        delegate: InputStream,
        private val connection: HttpsURLConnection,
        private val maxResponseBytes: Long,
        private val deadlineNanos: Long,
        private val readTimeoutMillis: Int,
        private val nanoTime: () -> Long,
    ) : FilterInputStream(delegate) {
        private var deliveredBytes = 0L
        private var closed = false

        override fun read(): Int {
            val one = ByteArray(1)
            val count = read(one, 0, 1)
            return if (count < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (closed) throw IOException("profile response is closed")
            if (length == 0) return 0
            checkCancellationAndDeadline()
            if (deliveredBytes >= maxResponseBytes) return -1
            connection.readTimeout = boundedReadTimeoutMillis()
            val allowed = min(length.toLong(), maxResponseBytes - deliveredBytes).toInt()
            val count = try {
                super.read(buffer, offset, allowed)
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("profile response read was cancelled").also { it.initCause(failure) }
            }
            checkCancellationAndDeadline()
            if (count > 0) deliveredBytes += count.toLong()
            return count
        }

        override fun close() {
            if (closed) return
            closed = true
            var failure: Throwable? = null
            try {
                super.close()
            } catch (caught: Throwable) {
                failure = caught
            } finally {
                connection.disconnect()
            }
            if (failure != null) throw failure
        }

        private fun boundedReadTimeoutMillis(): Int {
            val remainingNanos = deadlineNanos - nanoTime()
            if (remainingNanos <= 0L) throw SocketTimeoutException("profile fetch deadline expired")
            val remainingMillis = ((remainingNanos - 1L) / NANOS_PER_MILLI + 1L)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
            return min(readTimeoutMillis, remainingMillis).coerceAtLeast(1)
        }

        private fun checkCancellationAndDeadline() {
            if (Thread.currentThread().isInterrupted) {
                throw InterruptedIOException("profile response read was cancelled")
            }
            if (deadlineNanos - nanoTime() <= 0L) {
                throw SocketTimeoutException("profile fetch deadline expired")
            }
        }
    }

    private companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 15_000
        const val DEFAULT_READ_TIMEOUT_MILLIS = 30_000
        const val MAX_IO_TIMEOUT_MILLIS = 60_000
        const val NANOS_PER_MILLI = 1_000_000L
        const val MAX_OVERALL_TIMEOUT_NANOS = 30L * 60L * 1_000L * NANOS_PER_MILLI
    }
}
