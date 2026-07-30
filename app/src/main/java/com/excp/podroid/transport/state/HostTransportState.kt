/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.transport.state

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/** Host transport state is a sibling of instances/, never VM or guest state. */
class HostTransportPaths(filesDirectory: File) {
    private val filesRoot = filesDirectory.toPath().toAbsolutePath().normalize()
    val root: Path = filesRoot.resolve("host-transport")
    val supervisorStateFile: Path = root.resolve("supervisor.state")
    val libtailscaleStateDirectory: Path = root.resolve("libtailscale-state")

    init {
        require(root.parent == filesRoot)
        require(supervisorStateFile.parent == root)
        require(libtailscaleStateDirectory.parent == root)
        require(!root.startsWith(filesRoot.resolve("instances")))
    }
}

enum class HostTransportPhase { STOPPED, STARTING, RUNNING, STOPPING, RECOVERY_REQUIRED, FAILED }
enum class HostTransportFailure { START_FAILED, CLOSE_FAILED, OWNERSHIP_CONFLICT }

data class HostTransportPersistentState(
    val schemaVersion: Int,
    val desiredEnabled: Boolean,
    val desiredGeneration: Long,
    val appliedGeneration: Long,
    val phase: HostTransportPhase,
    val ownerProcess: String?,
    val ownerGeneration: Long?,
    val lastFailure: HostTransportFailure?,
) {
    init {
        require(schemaVersion == SCHEMA_VERSION)
        require(desiredGeneration >= 0 && appliedGeneration in 0..desiredGeneration)
        require(ownerProcess == null || ownerProcess.matches(PROCESS_TOKEN))
        require((ownerProcess == null) == (ownerGeneration == null))
        require(ownerGeneration == null || ownerGeneration in 1..desiredGeneration)
        when (phase) {
            HostTransportPhase.STARTING -> {
                require(ownerProcess != null)
                require(lastFailure == null)
            }
            HostTransportPhase.RUNNING -> {
                require(ownerProcess != null && ownerGeneration == appliedGeneration)
                require(lastFailure == null)
            }
            HostTransportPhase.STOPPING -> {
                require(ownerProcess != null)
                require(lastFailure == null)
            }
            HostTransportPhase.RECOVERY_REQUIRED -> {
                require(ownerProcess != null)
                require(lastFailure in setOf(
                    HostTransportFailure.CLOSE_FAILED,
                    HostTransportFailure.OWNERSHIP_CONFLICT,
                ))
            }
            HostTransportPhase.STOPPED -> {
                require(ownerProcess == null && lastFailure == null)
            }
            HostTransportPhase.FAILED -> {
                require(ownerProcess == null && lastFailure == HostTransportFailure.START_FAILED)
            }
        }
    }

    companion object {
        const val SCHEMA_VERSION = 1
        internal val PROCESS_TOKEN = Regex("[A-Za-z0-9._-]{1,64}")
        fun safeDefaults() = HostTransportPersistentState(
            schemaVersion = SCHEMA_VERSION,
            desiredEnabled = false,
            desiredGeneration = 0,
            appliedGeneration = 0,
            phase = HostTransportPhase.STOPPED,
            ownerProcess = null,
            ownerGeneration = null,
            lastFailure = null,
        )
    }
}

class HostTransportStateSchemaException(message: String) : IOException(message)
class HostTransportStateCorruptionException(message: String) : IOException(message)

internal object HostTransportStateCodec {
    const val MAX_ENCODED_BYTES = 1_024
    private const val ABSENT = "-"

    fun encode(state: HostTransportPersistentState): ByteArray = buildString {
        appendLine("schema=${HostTransportPersistentState.SCHEMA_VERSION}")
        appendLine("desired_enabled=${if (state.desiredEnabled) 1 else 0}")
        appendLine("desired_generation=${state.desiredGeneration}")
        appendLine("applied_generation=${state.appliedGeneration}")
        appendLine("phase=${state.phase.name}")
        appendLine("owner_process=${state.ownerProcess ?: ABSENT}")
        appendLine("owner_generation=${state.ownerGeneration ?: ABSENT}")
        append("last_failure=${state.lastFailure?.name ?: ABSENT}")
    }.toByteArray(Charsets.UTF_8).also { check(it.size <= MAX_ENCODED_BYTES) }

    fun decode(encoded: ByteArray): HostTransportPersistentState {
        if (encoded.size !in 1..MAX_ENCODED_BYTES || encoded.any { it == 0.toByte() }) corrupt("size or content")
        val text = encoded.toString(Charsets.UTF_8)
        if (!text.toByteArray(Charsets.UTF_8).contentEquals(encoded)) corrupt("UTF-8")
        val lines = text.split('\n')
        if (lines.size != 8) corrupt("field count")
        val schema = value(lines, 0, "schema").toIntOrNull() ?: corrupt("schema")
        if (schema != HostTransportPersistentState.SCHEMA_VERSION) {
            throw HostTransportStateSchemaException("Unsupported Host transport state schema $schema")
        }
        val ownerProcess = optional(value(lines, 5, "owner_process"))
        val ownerGeneration = optional(value(lines, 6, "owner_generation"))?.toLongOrNull()
            ?: if (value(lines, 6, "owner_generation") == ABSENT) null else corrupt("owner_generation")
        val failureRaw = optional(value(lines, 7, "last_failure"))
        return construct {
            HostTransportPersistentState(
                schemaVersion = schema,
                desiredEnabled = when (value(lines, 1, "desired_enabled")) {
                    "0" -> false
                    "1" -> true
                    else -> corrupt("desired_enabled")
                },
                desiredGeneration = nonNegative(value(lines, 2, "desired_generation"), "desired_generation"),
                appliedGeneration = nonNegative(value(lines, 3, "applied_generation"), "applied_generation"),
                phase = enumValue(value(lines, 4, "phase"), "phase"),
                ownerProcess = ownerProcess,
                ownerGeneration = ownerGeneration,
                lastFailure = failureRaw?.let { enumValue<HostTransportFailure>(it, "last_failure") },
            )
        }
    }

    private fun value(lines: List<String>, index: Int, key: String): String {
        val line = lines.getOrNull(index) ?: corrupt(key)
        val prefix = "$key="
        if (!line.startsWith(prefix) || line.indexOf('=', prefix.length) >= 0) corrupt(key)
        return line.substring(prefix.length).also { if (it.isEmpty()) corrupt(key) }
    }

    private fun optional(value: String): String? = if (value == ABSENT) null else value
    private fun nonNegative(value: String, field: String): Long =
        value.toLongOrNull()?.takeIf { it >= 0 } ?: corrupt(field)
    private inline fun <reified T : Enum<T>> enumValue(value: String, field: String): T =
        enumValues<T>().singleOrNull { it.name == value } ?: corrupt(field)
    private inline fun <T> construct(block: () -> T): T = try {
        block()
    } catch (failure: HostTransportStateCorruptionException) {
        throw failure
    } catch (_: IllegalArgumentException) {
        corrupt("invariants")
    }
    private fun corrupt(field: String): Nothing =
        throw HostTransportStateCorruptionException("Corrupt Host transport state: $field")
}

internal interface AtomicHostTransportStateStore {
    fun read(): HostTransportPersistentState
    fun update(transform: (HostTransportPersistentState) -> HostTransportPersistentState): HostTransportPersistentState
}

/** Strict bounded file codec with same-directory atomic replacement. */
internal class AtomicFileHostTransportStateStore(
    paths: HostTransportPaths,
) : AtomicHostTransportStateStore {
    private val normalized = paths.supervisorStateFile.toAbsolutePath().normalize()
    private val lock = LOCK

    init { require(normalized.fileName.toString() == "supervisor.state") }

    override fun read(): HostTransportPersistentState = synchronized(lock) { readLocked() }

    override fun update(
        transform: (HostTransportPersistentState) -> HostTransportPersistentState,
    ): HostTransportPersistentState = synchronized(lock) {
        val parent = normalized.parent ?: throw IOException("Host transport state has no parent")
        Files.createDirectories(parent)
        requireSafeDirectory(parent)
        val current = readLocked()
        val updated = transform(current)
        val encoded = HostTransportStateCodec.encode(updated)
        val temporary = Files.createTempFile(parent, ".supervisor-", ".tmp")
        try {
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(encoded)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(
                temporary,
                normalized,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            FileChannel.open(parent, StandardOpenOption.READ).use { directory ->
                directory.force(true)
            }
            readExistingLocked().also {
                if (it != updated) throw IOException("Host transport state changed during atomic publication")
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun readLocked(): HostTransportPersistentState {
        val parent = normalized.parent ?: throw IOException("Host transport state has no parent")
        if (Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) requireSafeDirectory(parent)
        return if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) readExistingLocked()
        else HostTransportPersistentState.safeDefaults()
    }

    private fun readExistingLocked(): HostTransportPersistentState {
        val before = checkedFile()
        val bytes = FileChannel.open(normalized, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.allocate(HostTransportStateCodec.MAX_ENCODED_BYTES + 1)
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) Unit
            if (buffer.position() > HostTransportStateCodec.MAX_ENCODED_BYTES) {
                throw HostTransportStateCorruptionException("Host transport state is oversized")
            }
            buffer.array().copyOf(buffer.position())
        }
        if (checkedFile() != before) throw IOException("Host transport state changed while reading")
        return HostTransportStateCodec.decode(bytes)
    }

    private fun checkedFile(): FileIdentity {
        val attributes = Files.readAttributes(
            normalized,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        if (!attributes.isRegularFile || attributes.isSymbolicLink ||
            attributes.size() !in 1..HostTransportStateCodec.MAX_ENCODED_BYTES.toLong()
        ) throw HostTransportStateCorruptionException("Unsafe Host transport state file")
        return FileIdentity(attributes.fileKey(), attributes.size(), attributes.lastModifiedTime().toMillis())
    }

    private fun requireSafeDirectory(path: Path) {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!attributes.isDirectory || attributes.isSymbolicLink) {
            throw HostTransportStateCorruptionException("Unsafe Host transport state directory")
        }
    }

    private data class FileIdentity(val key: Any?, val size: Long, val modifiedMillis: Long)

    private companion object { val LOCK = Any() }
}
