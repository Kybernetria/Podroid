/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.transport.android

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import com.excp.podroid.transport.api.TransportEndpoint
import java.io.Closeable
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal enum class DefaultNetworkChange { AVAILABLE, LOST, CAPABILITIES_CHANGED, LINK_PROPERTIES_CHANGED }

internal data class DefaultNetworkGeneration<T>(
    val generation: Long,
    val network: T?,
    val change: DefaultNetworkChange,
)

/** Pure, synchronized generation fence used by the Android callback adapter. */
internal class DefaultNetworkGenerationTracker<T> {
    private var generation = 0L
    private var current: T? = null
    private var closed = false

    @Synchronized
    fun available(network: T): DefaultNetworkGeneration<T>? = change(network, DefaultNetworkChange.AVAILABLE)

    @Synchronized
    fun changed(network: T, reason: DefaultNetworkChange): DefaultNetworkGeneration<T>? {
        require(reason == DefaultNetworkChange.CAPABILITIES_CHANGED ||
            reason == DefaultNetworkChange.LINK_PROPERTIES_CHANGED)
        if (closed || current != network) return null
        return next(network, reason)
    }

    @Synchronized
    fun lost(network: T): DefaultNetworkGeneration<T>? {
        if (closed || current != network) return null
        return next(null, DefaultNetworkChange.LOST)
    }

    @Synchronized
    fun close() { closed = true; current = null }

    private fun change(network: T, reason: DefaultNetworkChange): DefaultNetworkGeneration<T>? {
        if (closed) return null
        return next(network, reason)
    }

    private fun next(network: T?, reason: DefaultNetworkChange): DefaultNetworkGeneration<T> {
        generation = Math.addExact(generation, 1L)
        current = network
        return DefaultNetworkGeneration(generation, network, reason)
    }
}

/**
 * Default-network callback boundary. Registration is explicit and close owns
 * unregistering. It never changes process-wide network binding.
 */
internal class AndroidDefaultNetworkCallback(
    private val connectivityManager: ConnectivityManager,
    private val onGeneration: (DefaultNetworkGeneration<Network>) -> Unit,
    private val onCallbackFailure: (RuntimeException) -> Unit,
) : Closeable {
    private val tracker = DefaultNetworkGenerationTracker<Network>()
    private var started = false
    private var closed = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = emit(tracker.available(network))
        override fun onLost(network: Network) = emit(tracker.lost(network))
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
            emit(tracker.changed(network, DefaultNetworkChange.CAPABILITIES_CHANGED))
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) =
            emit(tracker.changed(network, DefaultNetworkChange.LINK_PROPERTIES_CHANGED))
    }

    @Synchronized
    fun start() {
        check(!closed) { "network callback is closed" }
        check(!started) { "network callback already started" }
        started = true
        try {
            connectivityManager.registerDefaultNetworkCallback(callback)
        } catch (failure: RuntimeException) {
            started = false
            throw failure
        }
    }

    override fun close() {
        val unregister = synchronized(this) {
            if (closed) return
            closed = true
            tracker.close()
            started.also { started = false }
        }
        if (unregister) connectivityManager.unregisterNetworkCallback(callback)
    }

    private fun emit(event: DefaultNetworkGeneration<Network>?) {
        if (event == null) return
        synchronized(this) {
            if (closed) return
            try {
                onGeneration(event)
            } catch (failure: RuntimeException) {
                onCallbackFailure(failure)
            }
        }
    }
}

/** Injectable seam keeps per-socket selection logic testable without Android runtime effects. */
internal fun interface PerSocketBindOperation<N, S> {
    fun bind(network: N, socket: S)
}

internal class PerSocketNetworkBinder<N, S>(
    private val operation: PerSocketBindOperation<N, S>,
) {
    fun bind(network: N, socket: S) = operation.bind(network, socket)
}

/** Per-socket binding only; no process-wide routing API is used. */
internal object AndroidPerSocketNetworkBinder {
    private val stream = PerSocketNetworkBinder<Network, Socket> { network, socket ->
        network.bindSocket(socket)
    }
    private val datagram = PerSocketNetworkBinder<Network, DatagramSocket> { network, socket ->
        network.bindSocket(socket)
    }

    fun bind(network: Network, socket: Socket) = stream.bind(network, socket)
    fun bind(network: Network, socket: DatagramSocket) = datagram.bind(network, socket)
}

internal data class NetworkDnsRequest(
    val hostname: String,
    val timeoutMillis: Long,
    val maxAddresses: Int,
) {
    init {
        require(hostname.length in 1..TransportEndpoint.MAX_HOST_CHARS)
        require(hostname.none { it.isWhitespace() || it == '\u0000' })
        require(timeoutMillis in 1..MAX_TIMEOUT_MILLIS)
        require(maxAddresses in 1..MAX_ADDRESSES)
    }

    companion object {
        const val MAX_TIMEOUT_MILLIS = 10_000L
        const val MAX_ADDRESSES = 16
    }
}

internal class DnsResultLimitException : IOException("per-network DNS result exceeded the configured bound")

internal fun interface PerNetworkDnsLookup<N> {
    @Throws(UnknownHostException::class)
    fun getAllByName(network: N, hostname: String): Array<InetAddress>
}

/** Android API adapter; DNS always remains scoped to the selected Network. */
internal object AndroidPerNetworkDnsLookup : PerNetworkDnsLookup<Network> {
    override fun getAllByName(network: Network, hostname: String): Array<InetAddress> =
        network.getAllByName(hostname)
}

/**
 * Executes Network-scoped DNS on one worker with one queued request. Caller
 * waiting and returned cardinality are bounded. Future cancellation is best
 * effort because Network.getAllByName has no deterministic cancellation API.
 */
internal class BoundedPerNetworkDnsResolver<N>(
    private val lookup: PerNetworkDnsLookup<N>,
) : Closeable {
    private val executor = ThreadPoolExecutor(
        1,
        1,
        0,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(1),
        { command -> Thread(command, "podroid-network-dns").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    @Volatile private var closed = false

    @Throws(IOException::class, TimeoutException::class)
    fun resolve(network: N, request: NetworkDnsRequest): List<InetAddress> {
        check(!closed) { "DNS resolver is closed" }
        val future = try {
            executor.submit<List<InetAddress>> {
                val addresses = lookup.getAllByName(network, request.hostname)
                if (addresses.size > request.maxAddresses) throw DnsResultLimitException()
                addresses.distinct()
            }
        } catch (failure: RejectedExecutionException) {
            throw IOException("per-network DNS capacity is exhausted", failure)
        }
        return try {
            future.get(request.timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (failure: TimeoutException) {
            future.cancel(true)
            throw failure
        } catch (failure: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw IOException("per-network DNS interrupted", failure)
        } catch (failure: ExecutionException) {
            when (val cause = failure.cause) {
                is UnknownHostException -> throw cause
                is IOException -> throw cause
                is SecurityException -> throw cause
                else -> throw IOException("per-network DNS failed", cause)
            }
        }
    }

    override fun close() {
        closed = true
        executor.shutdownNow()
    }
}
