/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.management

import java.security.MessageDigest

enum class AuditStage { PRE_DISPATCH, COMPLETION }
enum class AuditOutcome { ADMITTED, SUCCEEDED, REJECTED, INDETERMINATE }

data class ManagementAuditRecord(
    val sequence: Long,
    val epochMillis: Long,
    val requestId: String,
    val clientIdentitySha256: String,
    val operation: ManagementOperation,
    val role: ManagementRole,
    val stage: AuditStage,
    val outcome: AuditOutcome,
    val ifGeneration: Long?,
    val resultingGeneration: Long?,
    val errorCode: ManagementErrorCode?,
) {
    init {
        require(sequence > 0)
        require(epochMillis >= 0)
        ManagementRequestId(requestId)
        require(clientIdentitySha256.matches(Regex("[0-9a-f]{64}")))
        require(ifGeneration == null || ifGeneration >= 0)
        require(resultingGeneration == null || resultingGeneration >= 0)
        when (stage) {
            AuditStage.PRE_DISPATCH -> {
                require(outcome == AuditOutcome.ADMITTED)
                require(resultingGeneration == null && errorCode == null)
            }
            AuditStage.COMPLETION -> require(outcome in setOf(
                AuditOutcome.SUCCEEDED,
                AuditOutcome.REJECTED,
                AuditOutcome.INDETERMINATE,
            ))
        }
        when (outcome) {
            AuditOutcome.ADMITTED, AuditOutcome.SUCCEEDED -> require(errorCode == null)
            AuditOutcome.REJECTED -> require(
                errorCode != null && errorCode != ManagementErrorCode.INDETERMINATE
            )
            AuditOutcome.INDETERMINATE ->
                require(errorCode == ManagementErrorCode.INDETERMINATE)
        }
    }

    /** Explicit fixed-field representation; request bodies, keys, and exception text have no slot. */
    fun stableFields(): Map<String, String> = linkedMapOf(
        "sequence" to sequence.toString(),
        "epoch_millis" to epochMillis.toString(),
        "request_id" to requestId,
        "client_identity_sha256" to clientIdentitySha256,
        "operation" to operation.wireName,
        "role" to role.principal,
        "stage" to stage.name.lowercase(),
        "outcome" to outcome.name.lowercase(),
        "if_generation" to (ifGeneration?.toString() ?: ""),
        "resulting_generation" to (resultingGeneration?.toString() ?: ""),
        "error_code" to (errorCode?.wireName ?: ""),
    ).also { fields ->
        require(fields.size == 11 && fields.values.all { it.length <= ManagementLimits.MAX_AUDIT_FIELD_CHARS })
    }
}

data class DurableAuditReceipt(val sequence: Long) {
    init { require(sequence > 0) }
}

class AuditUnavailableException(message: String) : IllegalStateException(message)

/** A successful return means the exact record is durable and ordered. */
interface DurableManagementAuditStore {
    fun appendDurably(build: (nextSequence: Long) -> ManagementAuditRecord): DurableAuditReceipt
}

/** Bounded deterministic fake; production composition requires a separately reviewed durable store. */
class InMemoryDurableManagementAuditStore(
    private val capacity: Int = ManagementLimits.MAX_AUDIT_RECORDS,
) : DurableManagementAuditStore {
    private val lock = Any()
    private val records = mutableListOf<ManagementAuditRecord>()

    init { require(capacity in 1..ManagementLimits.MAX_AUDIT_RECORDS) }

    override fun appendDurably(build: (nextSequence: Long) -> ManagementAuditRecord): DurableAuditReceipt =
        synchronized(lock) {
            if (records.size >= capacity) throw AuditUnavailableException("audit capacity exhausted")
            val sequence = records.size.toLong() + 1
            val record = build(sequence)
            check(record.sequence == sequence) { "audit sequence mismatch" }
            record.stableFields()
            records += record
            DurableAuditReceipt(sequence)
        }

    fun snapshot(): List<ManagementAuditRecord> = synchronized(lock) { records.toList() }
}

sealed interface PreDispatchAuditDecision {
    data class Permit(val receipt: DurableAuditReceipt) : PreDispatchAuditDecision
    data class Deny(val errorCode: ManagementErrorCode) : PreDispatchAuditDecision
}

class ManagementAuditPolicy(
    private val store: DurableManagementAuditStore,
    private val epochMillis: () -> Long,
) {
    /** No caller may dispatch unless this returns [PreDispatchAuditDecision.Permit]. */
    fun authorizePreDispatch(
        request: ManagementRequest,
        role: ManagementRole,
        clientIdentity: ManagementClientIdentity,
    ): PreDispatchAuditDecision = try {
        PreDispatchAuditDecision.Permit(
            store.appendDurably { sequence ->
                ManagementAuditRecord(
                    sequence = sequence,
                    epochMillis = epochMillis(),
                    requestId = request.requestId.value,
                    clientIdentitySha256 = clientIdentity.sha256,
                    operation = request.operation,
                    role = role,
                    stage = AuditStage.PRE_DISPATCH,
                    outcome = AuditOutcome.ADMITTED,
                    ifGeneration = request.ifGeneration,
                    resultingGeneration = null,
                    errorCode = null,
                )
            },
        )
    } catch (_: Exception) {
        PreDispatchAuditDecision.Deny(ManagementErrorCode.AUDIT_UNAVAILABLE)
    }
}

object ManagementAuditRedactor {
    /** Hashes the enrolled certificate and authenticated transport identity into one non-reversible key. */
    fun clientIdentity(
        certificateFingerprintSha256: String,
        transportIdentity: TransportAuthenticatedIdentity,
    ): ManagementClientIdentity {
        require(certificateFingerprintSha256.matches(Regex("[0-9a-f]{64}")))
        val source = listOf(
            certificateFingerprintSha256,
            transportIdentity.providerId,
            transportIdentity.nodeId,
        ).joinToString("\u0000").toByteArray(Charsets.UTF_8)
        return ManagementClientIdentity(
            MessageDigest.getInstance("SHA-256").digest(source)
                .joinToString("") { "%02x".format(it) },
        )
    }
}
