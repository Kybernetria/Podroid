/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.management

data class ManagementClientIdentity(val sha256: String) {
    init { require(sha256.matches(Regex("[0-9a-f]{64}"))) }
}

data class LedgerKey(
    val client: ManagementClientIdentity,
    val requestId: ManagementRequestId,
)

enum class LedgerState { RESERVED, EXECUTING, COMPLETED, REJECTED, INDETERMINATE }

data class LedgerEntry(
    val key: LedgerKey,
    val requestSha256: String,
    val operation: ManagementOperation,
    val state: LedgerState,
    val responseFrame: ByteArray? = null,
    val errorCode: ManagementErrorCode? = null,
) {
    init {
        require(requestSha256.matches(Regex("[0-9a-f]{64}")))
        when (state) {
            LedgerState.RESERVED, LedgerState.EXECUTING -> {
                require(responseFrame == null && errorCode == null)
            }
            LedgerState.COMPLETED -> {
                require(responseFrame != null)
                ManagementFrameCodec.requireValidResponseFrame(responseFrame)
                require(errorCode == null)
            }
            LedgerState.REJECTED -> require(responseFrame == null && errorCode != null)
            LedgerState.INDETERMINATE -> {
                require(responseFrame == null && errorCode == ManagementErrorCode.INDETERMINATE)
            }
        }
    }

    fun defensiveCopy(): LedgerEntry = copy(responseFrame = responseFrame?.copyOf())
}

/** Implementations must commit a transaction atomically and durably before returning. */
interface AtomicIdempotencyStore {
    fun <T> transactionDurably(block: (MutableMap<LedgerKey, LedgerEntry>) -> T): T
}

/** Deterministic JVM fake. It is atomic but deliberately makes no production durability claim. */
class InMemoryAtomicIdempotencyStore : AtomicIdempotencyStore {
    private val lock = Any()
    private val entries = linkedMapOf<LedgerKey, LedgerEntry>()

    override fun <T> transactionDurably(block: (MutableMap<LedgerKey, LedgerEntry>) -> T): T =
        synchronized(lock) { block(entries) }

    fun snapshot(): List<LedgerEntry> = synchronized(lock) { entries.values.map(LedgerEntry::defensiveCopy) }
}

sealed interface ReservationDecision {
    data object Reserved : ReservationDecision
    data class HashConflict(val state: LedgerState) : ReservationDecision
    data class NoReplay(val state: LedgerState) : ReservationDecision
    data class Completed(val responseFrame: ByteArray) : ReservationDecision
    data class Rejected(val errorCode: ManagementErrorCode) : ReservationDecision
    data object Indeterminate : ReservationDecision
    data object CapacityExceeded : ReservationDecision
}

/**
 * Closed mutation state machine. No RESERVED/EXECUTING duplicate can dispatch,
 * and no automatic eviction can make an old mutation replayable.
 */
class ManagementIdempotencyLedger(
    private val store: AtomicIdempotencyStore,
    private val capacity: Int = ManagementLimits.MAX_LEDGER_ENTRIES,
) {
    init { require(capacity in 1..ManagementLimits.MAX_LEDGER_ENTRIES) }

    fun reserve(key: LedgerKey, request: ManagementRequest): ReservationDecision =
        store.transactionDurably { entries ->
            val existing = entries[key]
            if (existing != null) {
                if (existing.requestSha256 != request.payloadSha256 || existing.operation != request.operation) {
                    return@transactionDurably ReservationDecision.HashConflict(existing.state)
                }
                return@transactionDurably when (existing.state) {
                    LedgerState.RESERVED, LedgerState.EXECUTING ->
                        ReservationDecision.NoReplay(existing.state)
                    LedgerState.COMPLETED ->
                        ReservationDecision.Completed(requireNotNull(existing.responseFrame).copyOf())
                    LedgerState.REJECTED ->
                        ReservationDecision.Rejected(requireNotNull(existing.errorCode))
                    LedgerState.INDETERMINATE -> ReservationDecision.Indeterminate
                }
            }
            if (entries.size >= capacity) return@transactionDurably ReservationDecision.CapacityExceeded
            entries[key] = LedgerEntry(
                key = key,
                requestSha256 = request.payloadSha256,
                operation = request.operation,
                state = LedgerState.RESERVED,
            )
            ReservationDecision.Reserved
        }

    /** Must commit immediately before irreversible dispatch. */
    fun markExecuting(key: LedgerKey, requestSha256: String) {
        transition(key, requestSha256, setOf(LedgerState.RESERVED)) {
            it.copy(state = LedgerState.EXECUTING)
        }
    }

    /** Must commit only after the authoritative owner has durably completed the operation. */
    fun complete(key: LedgerKey, requestSha256: String, responseFrame: ByteArray) {
        ManagementFrameCodec.requireValidResponseFrame(responseFrame)
        transition(key, requestSha256, setOf(LedgerState.EXECUTING)) {
            it.copy(state = LedgerState.COMPLETED, responseFrame = responseFrame.copyOf())
        }
    }

    /** Records a stable pre-effect result; callers must use a new UUID for any deliberate retry. */
    fun reject(key: LedgerKey, requestSha256: String, errorCode: ManagementErrorCode) {
        require(errorCode != ManagementErrorCode.INDETERMINATE)
        transition(key, requestSha256, setOf(LedgerState.RESERVED)) {
            it.copy(state = LedgerState.REJECTED, errorCode = errorCode)
        }
    }

    /** Records possible effect after dispatch; this state can never dispatch or replay. */
    fun markIndeterminate(key: LedgerKey, requestSha256: String) {
        transition(key, requestSha256, setOf(LedgerState.EXECUTING)) {
            it.copy(
                state = LedgerState.INDETERMINATE,
                errorCode = ManagementErrorCode.INDETERMINATE,
            )
        }
    }

    /**
     * Startup conversion is one atomic durable transaction. RESERVED had no
     * effect and becomes retryable INTERRUPTED; EXECUTING may have effected and
     * becomes permanently non-replayable INDETERMINATE.
     */
    fun recoverAfterRestart(): Int = store.transactionDurably { entries ->
        var converted = 0
        entries.replaceAll { _, entry ->
            when (entry.state) {
                LedgerState.RESERVED -> {
                    converted++
                    entry.copy(state = LedgerState.REJECTED, errorCode = ManagementErrorCode.INTERRUPTED)
                }
                LedgerState.EXECUTING -> {
                    converted++
                    entry.copy(
                        state = LedgerState.INDETERMINATE,
                        errorCode = ManagementErrorCode.INDETERMINATE,
                    )
                }
                else -> entry
            }
        }
        converted
    }

    private fun transition(
        key: LedgerKey,
        requestSha256: String,
        allowedStates: Set<LedgerState>,
        update: (LedgerEntry) -> LedgerEntry,
    ) {
        require(requestSha256.matches(Regex("[0-9a-f]{64}")))
        store.transactionDurably { entries ->
            val current = entries[key] ?: error("ledger reservation is absent")
            check(current.requestSha256 == requestSha256) { "request hash conflict" }
            check(current.state in allowedStates) { "invalid ledger transition from ${current.state}" }
            entries[key] = update(current)
        }
    }
}
