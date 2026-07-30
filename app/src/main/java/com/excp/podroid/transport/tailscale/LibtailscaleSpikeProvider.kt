/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.transport.tailscale

import com.excp.podroid.transport.api.HostTransportProvider
import com.excp.podroid.transport.api.OpenHostTransportRequest
import com.excp.podroid.transport.api.HostTransportRuntime
import com.excp.podroid.transport.api.ProviderAvailability
import com.excp.podroid.transport.api.ProviderCapabilityReport
import com.excp.podroid.transport.api.TransportCapability
import com.excp.podroid.transport.api.TransportProviderUnavailableException

/**
 * Capability-only spike for official libtailscale commit
 * 5e89501def80a6579ca5d0f9a02f336be62b8f2e. It is deliberately impossible
 * to open, so adding these contracts cannot create host reachability or effects.
 */
object LibtailscaleSpikeProvider : HostTransportProvider {
    private val report = ProviderCapabilityReport(
        providerId = "official-libtailscale-spike",
        availability = ProviderAvailability.UNAVAILABLE,
        supported = setOf(
            TransportCapability.ANDROID_ABI_ARTIFACT,
            TransportCapability.PERSISTENT_HOST_IDENTITY,
            TransportCapability.TAILNET_LISTEN,
            TransportCapability.TAILNET_DIAL,
        ),
        blockers = setOf(
            TransportCapability.PER_NETWORK_SOCKET_BINDING,
            TransportCapability.PER_NETWORK_DNS,
            TransportCapability.DEFAULT_NETWORK_REBINDING,
            TransportCapability.DETERMINISTIC_CANCELLATION,
            TransportCapability.AUTHENTICATED_PEER_IDENTITY,
        ),
        detail = "The official pin is reproducibly packaged as a debug arm64-v8a artifact and " +
            "exposes tsnet lifecycle/listen/dial state APIs, but has no Android socket/DNS " +
            "injection, deterministic cancellation, or per-connection authenticated peer identity.",
    )

    override fun capabilities(): ProviderCapabilityReport = report

    override fun open(request: OpenHostTransportRequest): HostTransportRuntime {
        throw TransportProviderUnavailableException(
            "official libtailscale spike is unavailable: required Android and peer-identity hooks are absent",
        )
    }
}
