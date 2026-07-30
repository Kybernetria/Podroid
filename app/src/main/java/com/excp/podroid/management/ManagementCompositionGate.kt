/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.management

import com.excp.podroid.transport.api.ProviderAvailability
import com.excp.podroid.transport.api.ProviderCapabilityReport
import com.excp.podroid.transport.api.TransportCapability

enum class SshProviderCapability {
    ED25519_USER_CERTIFICATE_VERIFICATION,
    CERTIFICATE_CA_AND_REVOCATION_POLICY,
    TRANSPORT_IDENTITY_BINDING,
    EXACT_EXEC_AND_CHANNEL_FILTERING,
    UINT32_BOUNDED_FRAMING,
    MONOTONIC_DEADLINES_AND_CANCELLATION,
    BOUNDED_SESSION_AND_CHANNEL_COUNTS,
}

data class SshProviderCapabilityReport(
    val available: Boolean,
    val supported: Set<SshProviderCapability>,
) {
    init { if (!available) require(supported.isEmpty()) }
}

data class ManagementPersistenceCapabilities(
    val atomicDurableLedger: Boolean,
    val atomicDurableAudit: Boolean,
    val trustAndRevocationLoaded: Boolean,
)

enum class CompositionBlocker {
    TRANSPORT_PROVIDER_UNAVAILABLE,
    TRANSPORT_CAPABILITIES_MISSING,
    SSH_PROVIDER_UNAVAILABLE,
    SSH_CAPABILITIES_MISSING,
    DURABLE_LEDGER_UNAVAILABLE,
    DURABLE_AUDIT_UNAVAILABLE,
    TRUST_POLICY_UNAVAILABLE,
    RUNTIME_COMPOSITION_NOT_IMPLEMENTED,
}

data class DisabledManagementComposition(val blockers: Set<CompositionBlocker>) {
    init { require(blockers.isNotEmpty()) }
}

/**
 * Pure evidence gate only. It cannot return a listener/server/runtime and always
 * retains RUNTIME_COMPOSITION_NOT_IMPLEMENTED, even when test evidence satisfies
 * every provider prerequisite.
 */
object ManagementCompositionGate {
    val REQUIRED_TRANSPORT_CAPABILITIES = setOf(
        TransportCapability.PER_NETWORK_SOCKET_BINDING,
        TransportCapability.PER_NETWORK_DNS,
        TransportCapability.DEFAULT_NETWORK_REBINDING,
        TransportCapability.DETERMINISTIC_CANCELLATION,
        TransportCapability.AUTHENTICATED_PEER_IDENTITY,
        TransportCapability.PERSISTENT_HOST_IDENTITY,
        TransportCapability.TAILNET_LISTEN,
    )
    val REQUIRED_SSH_CAPABILITIES: Set<SshProviderCapability> = SshProviderCapability.entries.toSet()

    fun evaluate(
        transport: ProviderCapabilityReport,
        ssh: SshProviderCapabilityReport,
        persistence: ManagementPersistenceCapabilities,
    ): DisabledManagementComposition {
        val blockers = mutableSetOf(CompositionBlocker.RUNTIME_COMPOSITION_NOT_IMPLEMENTED)
        if (transport.availability != ProviderAvailability.AVAILABLE) {
            blockers += CompositionBlocker.TRANSPORT_PROVIDER_UNAVAILABLE
        }
        if (!transport.supported.containsAll(REQUIRED_TRANSPORT_CAPABILITIES)) {
            blockers += CompositionBlocker.TRANSPORT_CAPABILITIES_MISSING
        }
        if (!ssh.available) blockers += CompositionBlocker.SSH_PROVIDER_UNAVAILABLE
        if (!ssh.supported.containsAll(REQUIRED_SSH_CAPABILITIES)) {
            blockers += CompositionBlocker.SSH_CAPABILITIES_MISSING
        }
        if (!persistence.atomicDurableLedger) blockers += CompositionBlocker.DURABLE_LEDGER_UNAVAILABLE
        if (!persistence.atomicDurableAudit) blockers += CompositionBlocker.DURABLE_AUDIT_UNAVAILABLE
        if (!persistence.trustAndRevocationLoaded) blockers += CompositionBlocker.TRUST_POLICY_UNAVAILABLE
        return DisabledManagementComposition(blockers)
    }
}
