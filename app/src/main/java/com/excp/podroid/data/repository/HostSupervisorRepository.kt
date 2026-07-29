/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.excp.podroid.vm.*
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

// Lifecycle evidence is never replaced on corruption. The default DataStore is
// credential protected, so the boot integration intentionally uses only
// BOOT_COMPLETED (not LOCKED_BOOT_COMPLETED).
private val Context.hostSupervisorDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "host_supervisor_state",
)

internal interface AtomicHostSupervisorRecordStore {
    suspend fun read(): String?
    suspend fun update(transform: (String?) -> String): String
}

private class PreferencesHostSupervisorRecordStore(
    private val dataStore: DataStore<Preferences>,
) : AtomicHostSupervisorRecordStore {
    override suspend fun read(): String? = dataStore.data.first()[RECORD_KEY]
    override suspend fun update(transform: (String?) -> String): String {
        lateinit var committed: String
        dataStore.edit { preferences ->
            committed = transform(preferences[RECORD_KEY])
            preferences[RECORD_KEY] = committed
        }
        return committed
    }
    companion object { private val RECORD_KEY = stringPreferencesKey("record") }
}

class HostSupervisorSchemaException(message: String) : IOException(message)
class HostSupervisorCorruptionException(message: String) : IOException(message)

/** Strict bounded codec with the one explicit v1 -> v2 migration. */
internal object HostSupervisorRecordCodec {
    private const val MAX_ENCODED_CHARS = 2_048
    private const val ABSENT_RESULT = "-"
    private const val V1 = 1
    private const val V2 = 2

    fun decodeV0AbsentOrCurrent(encoded: String?): HostSupervisorState = when {
        encoded == null -> HostSupervisorState.safeDefaults()
        schema(encoded) == V1 -> decodeV1(encoded)
        schema(encoded) == V2 -> decodeV2(encoded)
        else -> throw HostSupervisorSchemaException(
            "Unsupported Host supervisor schema version ${schema(encoded)}",
        )
    }

    /** Returns a current in-memory value; callers commit [encode] atomically. */
    fun decodeV1(encoded: String): HostSupervisorState {
        val lines = checkedLines(encoded)
        if (parsedSchema(lines) != V1) throw HostSupervisorSchemaException("Not a schema v1 record")
        val common = decodeCommon(lines, transactionIndex = 8, noTransactionCount = 9, transactionCounts = 15..16)
        return HostSupervisorState(
            schemaVersion = V2,
            hostEnabled = common.hostEnabled,
            desiredState = common.desiredState,
            autostart = common.autostart,
            wakePolicy = common.wakePolicy,
            powerPolicy = common.powerPolicy,
            thermalPolicy = common.thermalPolicy,
            runtimeGeneration = common.runtimeGeneration,
            latestTransaction = common.transaction,
            reconciliation = ReconciliationMetadata.safeDefaults(),
        )
    }

    fun decodeV2(encoded: String): HostSupervisorState {
        val lines = checkedLines(encoded)
        if (parsedSchema(lines) != V2) throw HostSupervisorSchemaException("Not a schema v2 record")
        val common = decodeCommon(lines, transactionIndex = 13, noTransactionCount = 14, transactionCounts = 20..21)
        val triggerRaw = value(lines, 10, "reconcile_last_trigger")
        val errorRaw = value(lines, 12, "reconcile_last_error")
        return construct("record invariants") {
            HostSupervisorState(
                schemaVersion = V2,
                hostEnabled = common.hostEnabled,
                desiredState = common.desiredState,
                autostart = common.autostart,
                wakePolicy = common.wakePolicy,
                powerPolicy = common.powerPolicy,
                thermalPolicy = common.thermalPolicy,
                runtimeGeneration = common.runtimeGeneration,
                latestTransaction = common.transaction,
                reconciliation = ReconciliationMetadata(
                    consecutiveAttempts = value(lines, 8, "reconcile_attempts").toIntOrNull()
                        ?.takeIf { it >= 0 } ?: corrupt("reconcile_attempts"),
                    nextEligibleEpochMs = parseNonNegativeLong(
                        value(lines, 9, "reconcile_next_ms"), "reconcile_next_ms",
                    ),
                    lastTrigger = if (triggerRaw == ABSENT_RESULT) null else
                        parseEnum<ReconciliationTrigger>(triggerRaw, "reconcile_last_trigger"),
                    lastOutcome = parseEnum(
                        value(lines, 11, "reconcile_last_outcome"), "reconcile_last_outcome",
                    ),
                    lastErrorCode = if (errorRaw == ABSENT_RESULT) null else
                        parseEnum<LifecycleErrorCode>(errorRaw, "reconcile_last_error"),
                ),
            )
        }
    }

    fun encode(state: HostSupervisorState): String = buildString {
        appendLine("schema=$V2")
        appendLine("host_enabled=${bit(state.hostEnabled)}")
        appendLine("desired_state=${state.desiredState.name}")
        appendLine("autostart=${bit(state.autostart)}")
        appendLine("wake_policy=${state.wakePolicy.name}")
        appendLine("power_policy=${state.powerPolicy.name}")
        appendLine("thermal_policy=${state.thermalPolicy.name}")
        appendLine("runtime_generation=${state.runtimeGeneration}")
        val reconciliation = state.reconciliation
        appendLine("reconcile_attempts=${reconciliation.consecutiveAttempts}")
        appendLine("reconcile_next_ms=${reconciliation.nextEligibleEpochMs}")
        appendLine("reconcile_last_trigger=${reconciliation.lastTrigger?.name ?: ABSENT_RESULT}")
        appendLine("reconcile_last_outcome=${reconciliation.lastOutcome.name}")
        appendLine("reconcile_last_error=${reconciliation.lastErrorCode?.name ?: ABSENT_RESULT}")
        appendTransaction(state.latestTransaction)
    }.also { check(it.length <= MAX_ENCODED_CHARS) }

    /** Test/documentation helper for producing the exact legacy schema. */
    fun encodeV1ForMigration(state: HostSupervisorState): String = buildString {
        appendLine("schema=$V1")
        appendLine("host_enabled=${bit(state.hostEnabled)}")
        appendLine("desired_state=${state.desiredState.name}")
        appendLine("autostart=${bit(state.autostart)}")
        appendLine("wake_policy=${state.wakePolicy.name}")
        appendLine("power_policy=${state.powerPolicy.name}")
        appendLine("thermal_policy=${state.thermalPolicy.name}")
        appendLine("runtime_generation=${state.runtimeGeneration}")
        appendTransaction(state.latestTransaction)
    }

    private fun StringBuilder.appendTransaction(transaction: LifecycleTransaction?) {
        if (transaction == null) {
            append("transaction=0")
        } else {
            appendLine("transaction=1")
            appendLine("tx_id=${transaction.id}")
            appendLine("tx_operation=${transaction.operation.name}")
            appendLine("tx_outcome=${transaction.outcome.name}")
            appendLine("tx_requested_ms=${transaction.requestedAtEpochMs}")
            appendLine("tx_completed_ms=${transaction.completedAtEpochMs ?: ABSENT_RESULT}")
            appendLine("tx_error=${transaction.errorCode?.name ?: ABSENT_RESULT}")
            append("tx_effect_started=${bit(transaction.effectStarted)}")
        }
    }

    private data class Common(
        val hostEnabled: Boolean,
        val desiredState: VmDesiredState,
        val autostart: Boolean,
        val wakePolicy: HostWakePolicy,
        val powerPolicy: HostPowerPolicy,
        val thermalPolicy: HostThermalPolicy,
        val runtimeGeneration: Long,
        val transaction: LifecycleTransaction?,
    )

    private fun decodeCommon(
        lines: List<String>,
        transactionIndex: Int,
        noTransactionCount: Int,
        transactionCounts: IntRange,
    ): Common {
        val hasTransaction = parseBoolean(value(lines, transactionIndex, "transaction"), "transaction")
        if ((!hasTransaction && lines.size != noTransactionCount) ||
            (hasTransaction && lines.size !in transactionCounts)
        ) corrupt("field count")
        val transaction = if (!hasTransaction) null else {
            val base = transactionIndex + 1
            val outcome = parseEnum<LifecycleOutcome>(value(lines, base + 2, "tx_outcome"), "tx_outcome")
            val completedRaw = value(lines, base + 4, "tx_completed_ms")
            val errorRaw = value(lines, base + 5, "tx_error")
            construct("transaction") {
                LifecycleTransaction(
                    id = parseNonNegativeLong(value(lines, base, "tx_id"), "tx_id").also {
                        if (it == 0L) corrupt("tx_id")
                    },
                    operation = parseEnum(value(lines, base + 1, "tx_operation"), "tx_operation"),
                    outcome = outcome,
                    requestedAtEpochMs = parseNonNegativeLong(
                        value(lines, base + 3, "tx_requested_ms"), "tx_requested_ms",
                    ),
                    completedAtEpochMs = if (completedRaw == ABSENT_RESULT) null else
                        parseNonNegativeLong(completedRaw, "tx_completed_ms"),
                    errorCode = if (errorRaw == ABSENT_RESULT) null else
                        parseEnum<LifecycleErrorCode>(errorRaw, "tx_error"),
                    effectStarted = if (lines.size == transactionCounts.last) {
                        parseBoolean(value(lines, base + 6, "tx_effect_started"), "tx_effect_started")
                    } else outcome != LifecycleOutcome.PENDING,
                )
            }
        }
        return Common(
            hostEnabled = parseBoolean(value(lines, 1, "host_enabled"), "host_enabled"),
            desiredState = parseEnum(value(lines, 2, "desired_state"), "desired_state"),
            autostart = parseBoolean(value(lines, 3, "autostart"), "autostart"),
            wakePolicy = parseEnum(value(lines, 4, "wake_policy"), "wake_policy"),
            powerPolicy = parseEnum(value(lines, 5, "power_policy"), "power_policy"),
            thermalPolicy = parseEnum(value(lines, 6, "thermal_policy"), "thermal_policy"),
            runtimeGeneration = parseNonNegativeLong(value(lines, 7, "runtime_generation"), "runtime_generation"),
            transaction = transaction,
        )
    }

    private fun checkedLines(encoded: String): List<String> {
        if (encoded.length > MAX_ENCODED_CHARS || '\u0000' in encoded) corrupt("record size or content")
        return encoded.split('\n')
    }
    private fun schema(encoded: String): Int = parsedSchema(checkedLines(encoded))
    private fun parsedSchema(lines: List<String>): Int =
        value(lines, 0, "schema").toIntOrNull() ?: corrupt("schema")
    private fun value(lines: List<String>, index: Int, key: String): String {
        val line = lines.getOrNull(index) ?: corrupt("missing $key")
        val prefix = "$key="
        if (!line.startsWith(prefix) || line.indexOf('=', prefix.length) >= 0) corrupt(key)
        return line.substring(prefix.length).also { if (it.isEmpty()) corrupt(key) }
    }
    private fun parseBoolean(value: String, field: String): Boolean = when (value) {
        "0" -> false
        "1" -> true
        else -> corrupt(field)
    }
    private fun parseNonNegativeLong(value: String, field: String): Long =
        value.toLongOrNull()?.takeIf { it >= 0 } ?: corrupt(field)
    private inline fun <reified T : Enum<T>> parseEnum(value: String, field: String): T =
        enumValues<T>().singleOrNull { it.name == value } ?: corrupt(field)
    private inline fun <T> construct(field: String, block: () -> T): T = try {
        block()
    } catch (failure: HostSupervisorCorruptionException) {
        throw failure
    } catch (_: IllegalArgumentException) {
        corrupt(field)
    }
    private fun bit(value: Boolean) = if (value) 1 else 0
    private fun corrupt(field: String): Nothing =
        throw HostSupervisorCorruptionException("Corrupt Host supervisor record: $field")
}

@Singleton
class HostSupervisorRepository internal constructor(
    private val store: AtomicHostSupervisorRecordStore,
    private val currentTimeMillis: () -> Long,
    private val datastoreTimeoutMs: Long = DEFAULT_DATASTORE_TIMEOUT_MS,
) : HostSupervisorTransactions, HostReconciliationStore {
    @Inject constructor(@ApplicationContext context: Context) : this(
        PreferencesHostSupervisorRecordStore(context.hostSupervisorDataStore),
        System::currentTimeMillis,
    )

    init { require(datastoreTimeoutMs > 0) }

    override suspend fun snapshot(): HostSupervisorState = mutate { it }

    override suspend fun setAutostart(enabled: Boolean): HostSupervisorState = mutate {
        it.copy(autostart = enabled)
    }

    override suspend fun prepare(operation: LifecycleOperation, expectedId: Long?): LifecycleTransactionToken {
        lateinit var token: LifecycleTransactionToken
        mutate { current ->
            val nextId = Math.addExact(current.latestTransaction?.id ?: 0L, 1L)
            if (expectedId != null && expectedId != nextId) {
                throw StaleLifecycleCommandException(
                    "Lifecycle command generation $expectedId is stale; next durable id is $nextId",
                )
            }
            val desired = when (operation) {
                LifecycleOperation.START, LifecycleOperation.RESTART -> VmDesiredState.RUNNING
                LifecycleOperation.STOP, LifecycleOperation.FORCE_STOP, LifecycleOperation.REMOVE -> VmDesiredState.STOPPED
                LifecycleOperation.SETUP -> current.desiredState
            }
            token = LifecycleTransactionToken(nextId, operation, current.runtimeGeneration)
            current.copy(
                hostEnabled = current.hostEnabled || operation == LifecycleOperation.SETUP,
                desiredState = desired,
                latestTransaction = LifecycleTransaction(
                    nextId, operation, LifecycleOutcome.PENDING, now(), null, null,
                ),
            )
        }
        return token
    }

    override suspend fun claim(token: LifecycleTransactionToken): Boolean {
        var claimed = false
        mutate { current ->
            val transaction = current.latestTransaction
            if (transaction.matchesPending(token) && !transaction!!.effectStarted &&
                token.baseRuntimeGeneration <= current.runtimeGeneration
            ) {
                claimed = true
                current.copy(latestTransaction = transaction.copy(effectStarted = true))
            } else current
        }
        return claimed
    }

    override suspend fun isCurrent(token: LifecycleTransactionToken): Boolean {
        val raw = withTimeout(datastoreTimeoutMs) { store.read() }
        val current = HostSupervisorRecordCodec.decodeV0AbsentOrCurrent(raw)
        return current.latestTransaction.matchesPending(token) &&
            current.latestTransaction?.effectStarted == true &&
            token.baseRuntimeGeneration <= current.runtimeGeneration
    }

    override suspend fun succeed(token: LifecycleTransactionToken, runtimeStarted: Boolean): Boolean =
        finishLifecycle(token, LifecycleOutcome.SUCCEEDED, null, runtimeStarted)

    override suspend fun fail(token: LifecycleTransactionToken, errorCode: LifecycleErrorCode): Boolean =
        finishLifecycle(token, LifecycleOutcome.FAILED, errorCode, false)

    private suspend fun finishLifecycle(
        token: LifecycleTransactionToken,
        outcome: LifecycleOutcome,
        errorCode: LifecycleErrorCode?,
        runtimeStarted: Boolean,
    ): Boolean {
        var completed = false
        mutate { current ->
            val transaction = current.latestTransaction
            if (!transaction.matchesPending(token) || transaction?.effectStarted != true) {
                if (runtimeStarted && outcome == LifecycleOutcome.SUCCEEDED && transaction?.id != token.id) {
                    current.copy(runtimeGeneration = maxOf(
                        current.runtimeGeneration,
                        Math.addExact(token.baseRuntimeGeneration, 1L),
                    ))
                } else current
            } else {
                completed = true
                current.copy(
                    runtimeGeneration = if (runtimeStarted) Math.addExact(current.runtimeGeneration, 1L)
                        else current.runtimeGeneration,
                    latestTransaction = transaction!!.copy(
                        outcome = outcome,
                        completedAtEpochMs = maxOf(now(), transaction.requestedAtEpochMs),
                        errorCode = errorCode,
                    ),
                    reconciliation = if (outcome == LifecycleOutcome.SUCCEEDED) {
                        current.reconciliation.copy(
                            consecutiveAttempts = 0,
                            nextEligibleEpochMs = 0,
                            lastOutcome = if (current.reconciliation.lastOutcome == ReconciliationOutcome.ATTEMPTING) {
                                ReconciliationOutcome.SUCCEEDED
                            } else ReconciliationOutcome.NEVER_RUN,
                            lastErrorCode = null,
                        )
                    } else current.reconciliation,
                )
            }
        }
        return completed
    }

    override suspend fun begin(trigger: ReconciliationTrigger): ReconciliationAdmission {
        lateinit var admission: ReconciliationAdmission
        mutate { original ->
            val timestamp = now()
            val pending = original.latestTransaction?.takeIf { it.outcome == LifecycleOutcome.PENDING }
            var current = if (pending != null) {
                original.copy(
                    latestTransaction = pending.copy(
                        outcome = LifecycleOutcome.FAILED,
                        completedAtEpochMs = maxOf(timestamp, pending.requestedAtEpochMs),
                        errorCode = LifecycleErrorCode.PROCESS_DIED,
                    ),
                    reconciliation = original.reconciliation.copy(
                        lastTrigger = trigger,
                        lastOutcome = ReconciliationOutcome.INTERRUPTED,
                        lastErrorCode = LifecycleErrorCode.PROCESS_DIED,
                    ),
                )
            } else original
            val decision = HostReconciliationPolicy.decide(current, trigger, timestamp)
            if (decision != ReconciliationOutcome.ATTEMPTING) {
                // Preserve the stronger INTERRUPTED/PROCESS_DIED evidence when
                // policy prevents recovery. The returned decision still tells
                // Android why no service effect is eligible.
                if (pending == null) {
                    current = current.copy(reconciliation = current.reconciliation.copy(
                        lastTrigger = trigger,
                        lastOutcome = decision,
                        lastErrorCode = if (decision == ReconciliationOutcome.EXHAUSTED) {
                            current.reconciliation.lastErrorCode
                        } else null,
                    ))
                }
                admission = ReconciliationAdmission.Skip(decision)
                current
            } else {
                val attempt = current.reconciliation.consecutiveAttempts + 1
                val expectedId = Math.addExact(current.latestTransaction?.id ?: 0L, 1L)
                val token = ReconciliationAttemptToken(trigger, attempt, expectedId)
                admission = ReconciliationAdmission.Execute(token, pending?.id)
                current.copy(reconciliation = current.reconciliation.copy(
                    consecutiveAttempts = attempt,
                    lastTrigger = trigger,
                    lastOutcome = ReconciliationOutcome.ATTEMPTING,
                    lastErrorCode = null,
                ))
            }
        }
        return admission
    }

    override suspend fun finish(
        token: ReconciliationAttemptToken,
        outcome: ReconciliationOutcome,
        errorCode: LifecycleErrorCode?,
    ): HostSupervisorState {
        require(outcome in setOf(
            ReconciliationOutcome.SUCCEEDED,
            ReconciliationOutcome.SUPERSEDED,
            ReconciliationOutcome.FAILED,
        ))
        require((outcome == ReconciliationOutcome.FAILED) == (errorCode != null))
        return mutate { current ->
            val metadata = current.reconciliation
            if (metadata.lastTrigger != token.trigger ||
                (metadata.consecutiveAttempts != token.attempt && metadata.lastOutcome != ReconciliationOutcome.SUCCEEDED)
            ) return@mutate current
            when (outcome) {
                ReconciliationOutcome.SUCCEEDED -> current.copy(reconciliation = metadata.copy(
                    consecutiveAttempts = 0,
                    nextEligibleEpochMs = 0,
                    lastOutcome = ReconciliationOutcome.SUCCEEDED,
                    lastErrorCode = null,
                ))
                ReconciliationOutcome.SUPERSEDED -> current.copy(reconciliation = metadata.copy(
                    nextEligibleEpochMs = 0,
                    lastOutcome = ReconciliationOutcome.SUPERSEDED,
                    lastErrorCode = null,
                ))
                ReconciliationOutcome.FAILED -> {
                    val delayMs = HostReconciliationPolicy.backoffDelayMs(token.attempt)
                    val next = saturatingAdd(now(), delayMs)
                    current.copy(reconciliation = metadata.copy(
                        nextEligibleEpochMs = next,
                        lastOutcome = if (token.attempt >= ReconciliationMetadata.MAX_ATTEMPTS) {
                            ReconciliationOutcome.EXHAUSTED
                        } else ReconciliationOutcome.FAILED,
                        lastErrorCode = errorCode,
                    ))
                }
                else -> error("validated above")
            }
        }
    }

    private fun LifecycleTransaction?.matchesPending(token: LifecycleTransactionToken): Boolean =
        this?.id == token.id && operation == token.operation && outcome == LifecycleOutcome.PENDING

    private suspend fun mutate(transform: (HostSupervisorState) -> HostSupervisorState): HostSupervisorState {
        val encoded = withTimeout(datastoreTimeoutMs) {
            store.update { raw ->
                val current = HostSupervisorRecordCodec.decodeV0AbsentOrCurrent(raw)
                HostSupervisorRecordCodec.encode(transform(current))
            }
        }
        return HostSupervisorRecordCodec.decodeV2(encoded)
    }

    private fun now(): Long = currentTimeMillis().also { require(it >= 0) }
    private fun saturatingAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

    private companion object { const val DEFAULT_DATASTORE_TIMEOUT_MS = 5_000L }
}
