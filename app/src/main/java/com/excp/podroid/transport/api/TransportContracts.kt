/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.transport.api

import java.net.URI

/** Host and guest identities are deliberately different domain types. */
data class HostTransportIdentity(val stableName: String) {
    init { require(stableName.matches(SAFE_ID)) { "invalid host transport identity" } }
    private companion object { val SAFE_ID = Regex("[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?") }
}

data class GuestWorkloadIdentity(val stableName: String) {
    init { require(stableName.matches(SAFE_ID)) { "invalid guest workload identity" } }
    private companion object { val SAFE_ID = Regex("[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?") }
}

/** Monotonic deadline. Wall-clock timestamps are never used as operation budgets. */
data class TransportDeadline(val monotonicDeadlineNanos: Long) {
    init { require(monotonicDeadlineNanos > 0) }

    fun remainingNanos(monotonicNowNanos: Long): Long =
        (monotonicDeadlineNanos - monotonicNowNanos).coerceAtLeast(0)
}

fun interface TransportCancellation {
    fun isCancellationRequested(): Boolean

    companion object { val NEVER = TransportCancellation { false } }
}

enum class TransportProtocol { TCP }

data class TransportEndpoint(
    val host: String,
    val port: Int,
    val protocol: TransportProtocol = TransportProtocol.TCP,
) {
    init {
        require(host.length in 1..MAX_HOST_CHARS && host.none { it.isWhitespace() || it == '\u0000' }) {
            "invalid transport host"
        }
        require(port in 1..65_535) { "invalid transport port" }
    }

    companion object { const val MAX_HOST_CHARS = 253 }
}

data class ListenRequest(
    val endpoint: TransportEndpoint,
    val backlog: Int,
    val deadline: TransportDeadline,
    val cancellation: TransportCancellation = TransportCancellation.NEVER,
) {
    init { require(backlog in 1..MAX_BACKLOG) { "listen backlog is out of bounds" } }
    companion object { const val MAX_BACKLOG = 32 }
}

data class DialRequest(
    val endpoint: TransportEndpoint,
    val deadline: TransportDeadline,
    val cancellation: TransportCancellation = TransportCancellation.NEVER,
)

data class AcceptRequest(
    val deadline: TransportDeadline,
    val cancellation: TransportCancellation = TransportCancellation.NEVER,
)

data class BoundedIoRequest(
    val bytes: ByteArray,
    val offset: Int = 0,
    val length: Int = bytes.size,
    val deadline: TransportDeadline,
    val cancellation: TransportCancellation = TransportCancellation.NEVER,
) {
    init {
        require(offset >= 0 && length in 1..MAX_IO_BYTES && offset <= bytes.size - length) {
            "I/O slice is invalid or too large"
        }
    }

    companion object { const val MAX_IO_BYTES = 64 * 1024 }
}

/** An admitted connection is the only public contract that can carry payload bytes. */
interface BoundedTransportConnection : AutoCloseable {
    val remoteAddress: TransportEndpoint
    fun read(request: BoundedIoRequest): Int
    fun write(request: BoundedIoRequest)
    override fun close()
}

/**
 * An inbound candidate intentionally has no read/write methods. [promote] may be
 * called only by [PeerAdmissionGate] after policy admission.
 */
interface PendingInboundConnection : AutoCloseable {
    val remoteAddress: TransportEndpoint
    val peerEvidence: PeerIdentityEvidence
    fun promote(): BoundedTransportConnection
    override fun close()
}

interface TransportListener : AutoCloseable {
    fun accept(request: AcceptRequest): PendingInboundConnection
    override fun close()
}

interface HostTransportRuntime : AutoCloseable {
    fun listen(request: ListenRequest): TransportListener
    fun dial(request: DialRequest): BoundedTransportConnection
    override fun close()
}

data class HostTransportConfiguration(
    val identity: HostTransportIdentity,
    val controlUrl: URI,
) {
    init {
        require(controlUrl.scheme == "https") { "coordination URL must use HTTPS" }
        require(controlUrl.host != null && controlUrl.rawUserInfo == null && controlUrl.rawFragment == null) {
            "coordination URL must be an absolute authority without credentials or fragments"
        }
    }
}

enum class TransportCapability {
    ANDROID_ABI_ARTIFACT,
    PER_NETWORK_SOCKET_BINDING,
    PER_NETWORK_DNS,
    DEFAULT_NETWORK_REBINDING,
    DETERMINISTIC_CANCELLATION,
    AUTHENTICATED_PEER_IDENTITY,
    PERSISTENT_HOST_IDENTITY,
    TAILNET_LISTEN,
    TAILNET_DIAL,
}

enum class ProviderAvailability { AVAILABLE, UNAVAILABLE }

data class ProviderCapabilityReport(
    val providerId: String,
    val availability: ProviderAvailability,
    val supported: Set<TransportCapability>,
    val blockers: Set<TransportCapability>,
    val detail: String,
) {
    init {
        require(providerId.matches(Regex("[a-z0-9-]{1,64}")))
        require(supported.intersect(blockers).isEmpty())
        require(detail.length in 1..1_024)
        if (availability == ProviderAvailability.AVAILABLE) require(blockers.isEmpty())
    }
}

class TransportProviderUnavailableException(message: String) : IllegalStateException(message)

data class OpenHostTransportRequest(
    val configuration: HostTransportConfiguration,
    val deadline: TransportDeadline,
    val cancellation: TransportCancellation = TransportCancellation.NEVER,
)

interface HostTransportProvider {
    fun capabilities(): ProviderCapabilityReport
    fun open(request: OpenHostTransportRequest): HostTransportRuntime
}
