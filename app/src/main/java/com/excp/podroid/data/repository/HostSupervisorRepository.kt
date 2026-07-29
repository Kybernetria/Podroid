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
import com.excp.podroid.vm.HostPowerPolicy
import com.excp.podroid.vm.HostSupervisorState
import com.excp.podroid.vm.HostSupervisorTransactions
import com.excp.podroid.vm.HostThermalPolicy
import com.excp.podroid.vm.HostWakePolicy
import com.excp.podroid.vm.LifecycleErrorCode
import com.excp.podroid.vm.LifecycleOperation
import com.excp.podroid.vm.LifecycleOutcome
import com.excp.podroid.vm.LifecycleTransaction
import com.excp.podroid.vm.LifecycleTransactionToken
import com.excp.podroid.vm.VmDesiredState
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withTimeout

// Deliberately has no replacement corruption handler. This store contains
// lifecycle evidence; malformed Preferences or record data must remain in place.
private val Context.hostSupervisorDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "host_supervisor_state",
)

internal fun interface AtomicHostSupervisorRecordStore {
    /** Atomically replaces the one encoded record and returns its committed value. */
    suspend fun update(transform: (String?) -> String): String
}

private class PreferencesHostSupervisorRecordStore(
    private val dataStore: DataStore<Preferences>,
) : AtomicHostSupervisorRecordStore {
    override suspend fun update(transform: (String?) -> String): String {
        lateinit var committed: String
        dataStore.edit { preferences ->
            committed = transform(preferences[RECORD_KEY])
            preferences[RECORD_KEY] = committed
        }
        return committed
    }

    companion object {
        private val RECORD_KEY = stringPreferencesKey("record")
    }
}

class HostSupervisorSchemaException(message: String) : IOException(message)
class HostSupervisorCorruptionException(message: String) : IOException(message)

/** Pure strict codec. Unknown versions and malformed records are never normalized. */
internal object HostSupervisorRecordCodec {
    private const val MAX_ENCODED_CHARS = 2_048
    private const val ABSENT_RESULT = "-"

    fun decodeV0AbsentOrV1(encoded: String?): HostSupervisorState =
        if (encoded == null) HostSupervisorState.safeDefaults() else decodeV1(encoded)

    fun decodeV1(encoded: String): HostSupervisorState {
        if (encoded.length > MAX_ENCODED_CHARS || '\u0000' in encoded) corrupt("record size or content")
        val lines = encoded.split('\n')
        val schema = value(lines, 0, "schema").toIntOrNull() ?: corrupt("schema")
        if (schema != HostSupervisorState.SCHEMA_VERSION) {
            throw HostSupervisorSchemaException("Unsupported Host supervisor schema version $schema")
        }
        val hasTransaction = parseBoolean(value(lines, 8, "transaction"), "transaction")
        val expectedLines = if (hasTransaction) 15 else 9
        if (lines.size != expectedLines) corrupt("field count")

        val transaction = if (hasTransaction) {
            val outcome = parseEnum<LifecycleOutcome>(value(lines, 11, "tx_outcome"), "tx_outcome")
            val completedRaw = value(lines, 13, "tx_completed_ms")
            val errorRaw = value(lines, 14, "tx_error")
            construct("transaction") {
                LifecycleTransaction(
                    id = parseNonNegativeLong(value(lines, 9, "tx_id"), "tx_id").also {
                        if (it == 0L) corrupt("tx_id")
                    },
                    operation = parseEnum(value(lines, 10, "tx_operation"), "tx_operation"),
                    outcome = outcome,
                    requestedAtEpochMs = parseNonNegativeLong(
                        value(lines, 12, "tx_requested_ms"), "tx_requested_ms",
                    ),
                    completedAtEpochMs = if (completedRaw == ABSENT_RESULT) null else
                        parseNonNegativeLong(completedRaw, "tx_completed_ms"),
                    errorCode = if (errorRaw == ABSENT_RESULT) null else
                        parseEnum<LifecycleErrorCode>(errorRaw, "tx_error"),
                )
            }
        } else {
            null
        }

        return construct("record invariants") {
            HostSupervisorState(
                schemaVersion = schema,
                hostEnabled = parseBoolean(value(lines, 1, "host_enabled"), "host_enabled"),
                desiredState = parseEnum(value(lines, 2, "desired_state"), "desired_state"),
                autostart = parseBoolean(value(lines, 3, "autostart"), "autostart"),
                wakePolicy = parseEnum<HostWakePolicy>(value(lines, 4, "wake_policy"), "wake_policy"),
                powerPolicy = parseEnum<HostPowerPolicy>(value(lines, 5, "power_policy"), "power_policy"),
                thermalPolicy = parseEnum<HostThermalPolicy>(
                    value(lines, 6, "thermal_policy"), "thermal_policy",
                ),
                runtimeGeneration = parseNonNegativeLong(
                    value(lines, 7, "runtime_generation"), "runtime_generation",
                ),
                latestTransaction = transaction,
            )
        }
    }

    fun encode(state: HostSupervisorState): String = buildString {
        appendLine("schema=${state.schemaVersion}")
        appendLine("host_enabled=${bit(state.hostEnabled)}")
        appendLine("desired_state=${state.desiredState.name}")
        appendLine("autostart=${bit(state.autostart)}")
        appendLine("wake_policy=${state.wakePolicy.name}")
        appendLine("power_policy=${state.powerPolicy.name}")
        appendLine("thermal_policy=${state.thermalPolicy.name}")
        appendLine("runtime_generation=${state.runtimeGeneration}")
        val transaction = state.latestTransaction
        if (transaction == null) {
            append("transaction=0")
        } else {
            appendLine("transaction=1")
            appendLine("tx_id=${transaction.id}")
            appendLine("tx_operation=${transaction.operation.name}")
            appendLine("tx_outcome=${transaction.outcome.name}")
            appendLine("tx_requested_ms=${transaction.requestedAtEpochMs}")
            appendLine("tx_completed_ms=${transaction.completedAtEpochMs ?: ABSENT_RESULT}")
            append("tx_error=${transaction.errorCode?.name ?: ABSENT_RESULT}")
        }
    }.also {
        check(it.length <= MAX_ENCODED_CHARS) { "Host supervisor record exceeded its fixed bound" }
    }

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

/**
 * Dedicated app-private desired-state repository. Every mutation decodes,
 * validates, transforms, and writes the complete v1 record in one DataStore edit.
 */
@Singleton
class HostSupervisorRepository internal constructor(
    private val store: AtomicHostSupervisorRecordStore,
    private val currentTimeMillis: () -> Long,
    private val datastoreTimeoutMs: Long = DEFAULT_DATASTORE_TIMEOUT_MS,
) : HostSupervisorTransactions {
    @Inject constructor(
        @ApplicationContext context: Context,
    ) : this(
        PreferencesHostSupervisorRecordStore(context.hostSupervisorDataStore),
        System::currentTimeMillis,
    )

    init { require(datastoreTimeoutMs > 0) { "DataStore timeout must be positive" } }

    override suspend fun snapshot(): HostSupervisorState = mutate { it }

    override suspend fun begin(operation: LifecycleOperation): LifecycleTransactionToken {
        lateinit var token: LifecycleTransactionToken
        mutate { current ->
            val nextId = Math.addExact(current.latestTransaction?.id ?: 0L, 1L)
            val desired = when (operation) {
                LifecycleOperation.START, LifecycleOperation.RESTART -> VmDesiredState.RUNNING
                LifecycleOperation.STOP, LifecycleOperation.FORCE_STOP, LifecycleOperation.REMOVE ->
                    VmDesiredState.STOPPED
                LifecycleOperation.SETUP -> current.desiredState
            }
            token = LifecycleTransactionToken(nextId, operation, current.runtimeGeneration)
            current.copy(
                hostEnabled = current.hostEnabled || operation == LifecycleOperation.SETUP,
                desiredState = desired,
                latestTransaction = LifecycleTransaction(
                    id = nextId,
                    operation = operation,
                    outcome = LifecycleOutcome.PENDING,
                    requestedAtEpochMs = now(),
                    completedAtEpochMs = null,
                    errorCode = null,
                ),
            )
        }
        return token
    }

    override suspend fun succeed(token: LifecycleTransactionToken, runtimeStarted: Boolean) {
        finish(token, LifecycleOutcome.SUCCEEDED, null, runtimeStarted)
    }

    override suspend fun fail(token: LifecycleTransactionToken, errorCode: LifecycleErrorCode) {
        finish(token, LifecycleOutcome.FAILED, errorCode, runtimeStarted = false)
    }

    private suspend fun finish(
        token: LifecycleTransactionToken,
        outcome: LifecycleOutcome,
        errorCode: LifecycleErrorCode?,
        runtimeStarted: Boolean,
    ) {
        mutate { current ->
            val transaction = current.latestTransaction
            // A newer command is authoritative. Duplicate or stale completion
            // cannot overwrite its outcome or regress the runtime generation.
            if (transaction?.id != token.id || transaction.outcome != LifecycleOutcome.PENDING) {
                // A superseded launch may still have created a real runtime before
                // its completion write. Preserve the newer transaction, but do
                // account for that observed generation monotonically.
                if (runtimeStarted && outcome == LifecycleOutcome.SUCCEEDED) {
                    current.copy(
                        runtimeGeneration = maxOf(
                            current.runtimeGeneration,
                            Math.addExact(token.baseRuntimeGeneration, 1L),
                        ),
                    )
                } else {
                    current
                }
            } else {
                val generation = if (runtimeStarted) {
                    maxOf(current.runtimeGeneration, Math.addExact(token.baseRuntimeGeneration, 1L))
                } else {
                    current.runtimeGeneration
                }
                current.copy(
                    runtimeGeneration = generation,
                    latestTransaction = transaction.copy(
                        outcome = outcome,
                        completedAtEpochMs = maxOf(now(), transaction.requestedAtEpochMs),
                        errorCode = errorCode,
                    ),
                )
            }
        }
    }

    private suspend fun mutate(transform: (HostSupervisorState) -> HostSupervisorState): HostSupervisorState {
        val encoded = withTimeout(datastoreTimeoutMs) {
            store.update { raw ->
                val current = HostSupervisorRecordCodec.decodeV0AbsentOrV1(raw)
                HostSupervisorRecordCodec.encode(transform(current))
            }
        }
        return HostSupervisorRecordCodec.decodeV1(encoded)
    }

    private fun now(): Long = currentTimeMillis().also {
        require(it >= 0) { "wall clock must be non-negative" }
    }

    private companion object {
        const val DEFAULT_DATASTORE_TIMEOUT_MS = 5_000L
    }
}
