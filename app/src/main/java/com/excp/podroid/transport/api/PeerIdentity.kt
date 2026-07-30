/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.transport.api

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** A network address is routing metadata, never authorization evidence. */
sealed interface PeerIdentityEvidence {
    data class RemoteAddressOnly(val address: TransportEndpoint) : PeerIdentityEvidence

    data class Authenticated internal constructor(
        val assertion: ParsedPeerIdentityAssertion,
        val origin: AuthenticatedEvidenceOrigin,
    ) : PeerIdentityEvidence
}

enum class AuthenticatedEvidenceOrigin { TAILSCALE_LOCAL_API }

data class ParsedPeerIdentityAssertion(
    val nodeId: String,
    val userId: String,
    val loginName: String,
    val tailnet: String,
) {
    init {
        require(nodeId.matches(OPAQUE_ID))
        require(userId.matches(OPAQUE_ID))
        require(loginName.length in 1..MAX_NAME_CHARS && loginName.none(Char::isISOControl))
        require(tailnet.length in 1..MAX_NAME_CHARS && tailnet.none(Char::isISOControl))
    }

    private companion object {
        val OPAQUE_ID = Regex("[A-Za-z0-9_-]{1,128}")
        const val MAX_NAME_CHARS = 253
    }
}

/**
 * Strictly parses a bounded identity assertion. Parsing proves only shape; the
 * provider must obtain the bytes from an authenticated identity API before it
 * can create [PeerIdentityEvidence.Authenticated].
 */
object PeerIdentityAssertionParser {
    const val MAX_ASSERTION_BYTES = 1_024

    fun parse(encoded: ByteArray): ParsedPeerIdentityAssertion {
        require(encoded.size in 1..MAX_ASSERTION_BYTES) { "identity assertion size is invalid" }
        val text = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(encoded))
            .toString()
        require('\u0000' !in text && !text.endsWith('\n')) { "identity assertion content is invalid" }
        val lines = text.split('\n')
        require(lines.size == 5) { "identity assertion field count is invalid" }
        require(lines[0] == "schema=1") { "identity assertion schema is unsupported" }

        fun field(index: Int, key: String): String {
            val prefix = "$key="
            val line = lines[index]
            require(line.startsWith(prefix) && line.indexOf('=', prefix.length) < 0) {
                "identity assertion field $key is invalid"
            }
            return line.substring(prefix.length).also { require(it.isNotEmpty()) }
        }

        return ParsedPeerIdentityAssertion(
            nodeId = field(1, "node_id"),
            userId = field(2, "user_id"),
            loginName = field(3, "login_name"),
            tailnet = field(4, "tailnet"),
        )
    }
}

sealed interface PeerAdmissionDecision {
    data class Allow(val policyId: String) : PeerAdmissionDecision {
        init { require(policyId.matches(Regex("[a-z0-9-]{1,64}"))) }
    }
    data class Deny(val reason: PeerDenialReason) : PeerAdmissionDecision
}

enum class PeerDenialReason {
    REMOTE_ADDRESS_IS_NOT_IDENTITY,
    PROVIDER_IDENTITY_UNAVAILABLE,
    PRODUCTION_POLICY_DISABLED,
    IDENTITY_NOT_AUTHORIZED,
}

fun interface PeerAdmissionPolicy {
    fun evaluate(evidence: PeerIdentityEvidence): PeerAdmissionDecision
}

/** Production remains fail-closed until the provider can supply authenticated identity. */
object ProductionDenyAllPeerPolicy : PeerAdmissionPolicy {
    override fun evaluate(evidence: PeerIdentityEvidence): PeerAdmissionDecision =
        PeerAdmissionDecision.Deny(
            if (evidence is PeerIdentityEvidence.RemoteAddressOnly) {
                PeerDenialReason.REMOTE_ADDRESS_IS_NOT_IDENTITY
            } else {
                PeerDenialReason.PRODUCTION_POLICY_DISABLED
            },
        )
}

sealed interface PeerAdmissionResult {
    data class Admitted(val connection: BoundedTransportConnection) : PeerAdmissionResult
    data class Denied(val reason: PeerDenialReason) : PeerAdmissionResult
}

/** Closes denied candidates without exposing a payload-byte capability. */
object PeerAdmissionGate {
    fun admit(
        candidate: PendingInboundConnection,
        policy: PeerAdmissionPolicy = ProductionDenyAllPeerPolicy,
    ): PeerAdmissionResult {
        val decision = try {
            when (val evidence = candidate.peerEvidence) {
                is PeerIdentityEvidence.RemoteAddressOnly -> PeerAdmissionDecision.Deny(
                    PeerDenialReason.REMOTE_ADDRESS_IS_NOT_IDENTITY,
                )
                is PeerIdentityEvidence.Authenticated -> policy.evaluate(evidence)
            }
        } catch (failure: Exception) {
            candidate.close()
            throw failure
        }
        return when (decision) {
            is PeerAdmissionDecision.Allow -> try {
                PeerAdmissionResult.Admitted(candidate.promote())
            } catch (failure: Exception) {
                candidate.close()
                throw failure
            }
            is PeerAdmissionDecision.Deny -> {
                candidate.close()
                PeerAdmissionResult.Denied(decision.reason)
            }
        }
    }
}
