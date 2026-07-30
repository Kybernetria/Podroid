package com.excp.podroid.management

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditPolicyTest {
    @Test
    fun `durable pre-dispatch append is required before permit`() {
        val store = InMemoryDurableManagementAuditStore()
        val policy = ManagementAuditPolicy(store) { 123_456L }
        val request = request()
        val identity = ManagementClientIdentity("a".repeat(64))
        val decision = policy.authorizePreDispatch(request, ManagementRole.OPERATE, identity)
        assertEquals(PreDispatchAuditDecision.Permit(DurableAuditReceipt(1)), decision)
        val record = store.snapshot().single()
        assertEquals(AuditStage.PRE_DISPATCH, record.stage)
        assertEquals(AuditOutcome.ADMITTED, record.outcome)
        assertEquals(7L, record.ifGeneration)
        assertEquals(123_456L, record.epochMillis)
    }

    @Test
    fun `audit failure and exhausted capacity deny dispatch`() {
        val failing = object : DurableManagementAuditStore {
            override fun appendDurably(build: (Long) -> ManagementAuditRecord): DurableAuditReceipt =
                throw AuditUnavailableException("disk unavailable")
        }
        assertEquals(
            PreDispatchAuditDecision.Deny(ManagementErrorCode.AUDIT_UNAVAILABLE),
            ManagementAuditPolicy(failing) { 0 }.authorizePreDispatch(
                request(), ManagementRole.OPERATE, ManagementClientIdentity("a".repeat(64)),
            ),
        )

        val bounded = InMemoryDurableManagementAuditStore(capacity = 1)
        val policy = ManagementAuditPolicy(bounded) { 0 }
        assertTrue(policy.authorizePreDispatch(request(), ManagementRole.OPERATE, ManagementClientIdentity("a".repeat(64))) is PreDispatchAuditDecision.Permit)
        assertEquals(
            PreDispatchAuditDecision.Deny(ManagementErrorCode.AUDIT_UNAVAILABLE),
            policy.authorizePreDispatch(
                request(id = "550e8400-e29b-41d4-a716-446655440001"),
                ManagementRole.OPERATE,
                ManagementClientIdentity("a".repeat(64)),
            ),
        )
    }

    @Test
    fun `audit identity is transport bound hashed and DTO has no secret or payload slot`() {
        val rawNode = "sensitive-node-name"
        val identity = ManagementAuditRedactor.clientIdentity(
            "b".repeat(64),
            TransportAuthenticatedIdentity("tailscale", rawNode, true),
        )
        assertTrue(identity.sha256.matches(Regex("[0-9a-f]{64}")))
        assertFalse(identity.sha256.contains(rawNode))

        val store = InMemoryDurableManagementAuditStore()
        ManagementAuditPolicy(store) { 0 }.authorizePreDispatch(request(), ManagementRole.OPERATE, identity)
        val serialized = store.snapshot().single().stableFields().entries.joinToString("|")
        assertFalse(serialized.contains(rawNode))
        assertFalse(serialized.contains("certificate"))
        assertFalse(serialized.contains("payload"))
        assertFalse(serialized.contains("private"))
        assertTrue(store.snapshot().single().stableFields().values.all {
            it.length <= ManagementLimits.MAX_AUDIT_FIELD_CHARS
        })
    }

    @Test
    fun `completion invariants require fixed errors for rejected and indeterminate outcomes`() {
        val base = completion(AuditOutcome.SUCCEEDED, null)
        assertEquals(AuditOutcome.SUCCEEDED, base.outcome)
        assertTrue(runCatching { completion(AuditOutcome.ADMITTED, null) }.isFailure)
        assertTrue(runCatching { completion(AuditOutcome.SUCCEEDED, ManagementErrorCode.INTERNAL_ERROR) }.isFailure)
        assertTrue(runCatching { completion(AuditOutcome.REJECTED, null) }.isFailure)
        assertTrue(runCatching {
            completion(AuditOutcome.REJECTED, ManagementErrorCode.INDETERMINATE)
        }.isFailure)
        assertTrue(runCatching { completion(AuditOutcome.INDETERMINATE, ManagementErrorCode.INTERNAL_ERROR) }.isFailure)
        assertEquals(
            ManagementErrorCode.INDETERMINATE,
            completion(AuditOutcome.INDETERMINATE, ManagementErrorCode.INDETERMINATE).errorCode,
        )
    }

    private fun completion(outcome: AuditOutcome, error: ManagementErrorCode?) = ManagementAuditRecord(
        sequence = 1,
        epochMillis = 0,
        requestId = "550e8400-e29b-41d4-a716-446655440000",
        clientIdentitySha256 = "a".repeat(64),
        operation = ManagementOperation.VM_DEFAULT_START,
        role = ManagementRole.OPERATE,
        stage = AuditStage.COMPLETION,
        outcome = outcome,
        ifGeneration = 7,
        resultingGeneration = 8,
        errorCode = error,
    )

    private fun request(id: String = "550e8400-e29b-41d4-a716-446655440000") = ManagementRequest(
        ManagementRequestId(id),
        ManagementOperation.VM_DEFAULT_START,
        7,
        "c".repeat(64),
    )
}
