package com.excp.podroid.transport.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerIdentityTest {
    private val remote = TransportEndpoint("100.64.0.8", 43123)

    @Test
    fun `strict parser accepts exact v1 shape but does not itself authorize`() {
        val assertion = PeerIdentityAssertionParser.parse(
            "schema=1\nnode_id=node_123\nuser_id=user_9\nlogin_name=operator@example.test\ntailnet=example.test"
                .toByteArray(),
        )
        assertEquals("node_123", assertion.nodeId)
        assertEquals("user_9", assertion.userId)
        val evidence = PeerIdentityEvidence.Authenticated(
            assertion,
            AuthenticatedEvidenceOrigin.TAILSCALE_LOCAL_API,
        )
        val decision = ProductionDenyAllPeerPolicy.evaluate(evidence)
        assertEquals(
            PeerDenialReason.PRODUCTION_POLICY_DISABLED,
            (decision as PeerAdmissionDecision.Deny).reason,
        )
    }

    @Test
    fun `parser rejects oversized malformed reordered duplicate and unknown schema assertions`() {
        val valid = "schema=1\nnode_id=node1\nuser_id=user1\nlogin_name=user\ntailnet=tail"
        val invalid = listOf(
            ByteArray(PeerIdentityAssertionParser.MAX_ASSERTION_BYTES + 1),
            valid.replace("schema=1", "schema=2").toByteArray(),
            valid.replace("node_id=node1\nuser_id=user1", "user_id=user1\nnode_id=node1").toByteArray(),
            valid.replace("user_id=user1", "node_id=user1").toByteArray(),
            "$valid\nextra=value".toByteArray(),
            valid.toByteArray() + byteArrayOf(0),
            byteArrayOf(0xC3.toByte(), 0x28),
        )
        invalid.forEach { assertTrue(runCatching { PeerIdentityAssertionParser.parse(it) }.isFailure) }
    }

    @Test
    fun `remote IP alone is denied and candidate closes before payload promotion`() {
        val candidate = FakeCandidate(PeerIdentityEvidence.RemoteAddressOnly(remote))
        val result = PeerAdmissionGate.admit(candidate)

        assertEquals(
            PeerDenialReason.REMOTE_ADDRESS_IS_NOT_IDENTITY,
            (result as PeerAdmissionResult.Denied).reason,
        )
        assertTrue(candidate.closed)
        assertFalse(candidate.promoted)
    }

    @Test
    fun `remote IP cannot be admitted even by a permissive policy`() {
        val candidate = FakeCandidate(PeerIdentityEvidence.RemoteAddressOnly(remote))

        val result = PeerAdmissionGate.admit(candidate) {
            PeerAdmissionDecision.Allow("unsafe-test-policy")
        }

        assertEquals(
            PeerDenialReason.REMOTE_ADDRESS_IS_NOT_IDENTITY,
            (result as PeerAdmissionResult.Denied).reason,
        )
        assertTrue(candidate.closed)
        assertFalse(candidate.promoted)
    }

    @Test
    fun `production gate denies authenticated shape before payload bytes`() {
        val assertion = PeerIdentityAssertionParser.parse(
            "schema=1\nnode_id=node1\nuser_id=user1\nlogin_name=user\ntailnet=tail".toByteArray(),
        )
        val candidate = FakeCandidate(
            PeerIdentityEvidence.Authenticated(assertion, AuthenticatedEvidenceOrigin.TAILSCALE_LOCAL_API),
        )

        val result = PeerAdmissionGate.admit(candidate)

        assertTrue(result is PeerAdmissionResult.Denied)
        assertTrue(candidate.closed)
        assertFalse(candidate.promoted)
    }

    @Test
    fun `policy failure also closes candidate without promotion`() {
        val assertion = PeerIdentityAssertionParser.parse(
            "schema=1\nnode_id=node1\nuser_id=user1\nlogin_name=user\ntailnet=tail".toByteArray(),
        )
        val candidate = FakeCandidate(
            PeerIdentityEvidence.Authenticated(assertion, AuthenticatedEvidenceOrigin.TAILSCALE_LOCAL_API),
        )
        val failure = runCatching {
            PeerAdmissionGate.admit(candidate) { error("policy failed") }
        }
        assertTrue(failure.isFailure)
        assertTrue(candidate.closed)
        assertFalse(candidate.promoted)
    }

    private class FakeCandidate(
        override val peerEvidence: PeerIdentityEvidence,
    ) : PendingInboundConnection {
        override val remoteAddress = TransportEndpoint("100.64.0.8", 43123)
        var promoted = false
        var closed = false

        override fun promote(): BoundedTransportConnection {
            promoted = true
            error("not needed")
        }

        override fun close() { closed = true }
    }
}
