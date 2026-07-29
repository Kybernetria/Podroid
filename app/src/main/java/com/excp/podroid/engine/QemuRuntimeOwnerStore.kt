/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.engine

import android.net.LocalSocket
import android.os.Process
import com.excp.podroid.vm.LifecycleErrorCode
import com.excp.podroid.vm.StaleRuntimeEvidence
import com.excp.podroid.vm.VmPathSecurity
import com.excp.podroid.vm.VmPaths
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

internal data class QemuProcessIdentity(val pid: Long, val startTimeTicks: Long) {
    init {
        require(pid > 0 && startTimeTicks > 0)
    }
}

internal data class QemuRuntimeOwner(
    val process: QemuProcessIdentity,
    val generation: Long,
) {
    init { require(generation > 0) }
}

internal sealed interface ProcessIdentityObservation {
    data class Alive(val identity: QemuProcessIdentity) : ProcessIdentityObservation
    data object Dead : ProcessIdentityObservation
    data class Uncertain(val errorCode: LifecycleErrorCode) : ProcessIdentityObservation
}

internal fun interface ProcessIdentityReader {
    fun observe(pid: Long): ProcessIdentityObservation
}

internal object ProcProcessIdentityReader : ProcessIdentityReader {
    override fun observe(pid: Long): ProcessIdentityObservation {
        if (pid <= 0) return ProcessIdentityObservation.Uncertain(LifecycleErrorCode.SECURITY)
        val stat = Paths.get("/proc", pid.toString(), "stat")
        return try {
            val bytes = readBoundedNoFollow(stat, MAX_PROC_STAT_BYTES)
            val text = bytes.toString(Charsets.UTF_8)
            val closeParen = text.lastIndexOf(')')
            val openParen = text.indexOf('(')
            if (openParen <= 0 || closeParen <= openParen ||
                text.substring(0, openParen).trim().toLongOrNull() != pid
            ) return ProcessIdentityObservation.Uncertain(LifecycleErrorCode.SECURITY)
            // Tokens after ')' start at proc field 3 (state); starttime is field 22.
            val fields = text.substring(closeParen + 1).trim().split(Regex("\\s+"))
            val startTime = fields.getOrNull(19)?.toLongOrNull()?.takeIf { it > 0 }
                ?: return ProcessIdentityObservation.Uncertain(LifecycleErrorCode.SECURITY)
            ProcessIdentityObservation.Alive(QemuProcessIdentity(pid, startTime))
        } catch (_: NoSuchFileException) {
            ProcessIdentityObservation.Dead
        } catch (_: SecurityException) {
            ProcessIdentityObservation.Uncertain(LifecycleErrorCode.SECURITY)
        } catch (_: IOException) {
            ProcessIdentityObservation.Uncertain(LifecycleErrorCode.RUNTIME_OWNERSHIP)
        }
    }

    private const val MAX_PROC_STAT_BYTES = 4 * 1024
}

internal sealed interface QemuOwnerInspection {
    data object Missing : QemuOwnerInspection
    data class Valid(val owner: QemuRuntimeOwner, val file: CheckedFileIdentity) : QemuOwnerInspection
    data class Uncertain(val errorCode: LifecycleErrorCode) : QemuOwnerInspection
}

internal sealed interface DeadOwnerProof {
    data class Proven(val evidence: QemuStaleRuntimeEvidence) : DeadOwnerProof
    data object ExactProcessAlive : DeadOwnerProof
    data class Uncertain(val errorCode: LifecycleErrorCode) : DeadOwnerProof
}

internal enum class QmpPeerOwnershipVerdict { AUTHENTICATED, DEAD_OWNER, REJECTED }

internal object QmpPeerOwnershipPolicy {
    fun classify(
        expected: QemuRuntimeOwner,
        appUid: Int,
        peer: LocalSocketPeerIdentity,
        process: ProcessIdentityObservation,
    ): QmpPeerOwnershipVerdict = when {
        process == ProcessIdentityObservation.Dead -> QmpPeerOwnershipVerdict.DEAD_OWNER
        process !is ProcessIdentityObservation.Alive -> QmpPeerOwnershipVerdict.REJECTED
        process.identity != expected.process -> QmpPeerOwnershipVerdict.DEAD_OWNER
        peer.uid != appUid || peer.pid.toLong() != expected.process.pid ->
            QmpPeerOwnershipVerdict.REJECTED
        else -> QmpPeerOwnershipVerdict.AUTHENTICATED
    }
}

internal class QmpPeerAuthenticationException : IOException("QMP peer ownership check failed")

/** Revalidates owner file, peer UID/PID, and /proc start ticks after connect. */
internal class QemuOwnerPeerVerifier(
    private val ownerStore: QemuRuntimeOwnerStore,
    private val expectedOwner: QemuRuntimeOwner? = null,
    private val appUid: Int = Process.myUid(),
    private val credentials: LocalSocketPeerCredentialReader = AndroidLocalSocketPeerCredentialReader,
) : QmpPeerVerifier {
    override fun verify(socket: LocalSocket) {
        val inspection = ownerStore.inspect() as? QemuOwnerInspection.Valid
            ?: throw QmpPeerAuthenticationException()
        val owner = inspection.owner
        if (expectedOwner != null && owner != expectedOwner) throw QmpPeerAuthenticationException()
        val verdict = QmpPeerOwnershipPolicy.classify(
            owner,
            appUid,
            credentials.read(socket),
            ownerStore.observeProcess(owner.process.pid),
        )
        if (verdict != QmpPeerOwnershipVerdict.AUTHENTICATED) {
            throw QmpPeerAuthenticationException()
        }
    }
}

internal data class CheckedFileIdentity(
    val fileKey: Any,
    val size: Long,
)

internal data class PublishedQemuOwner(
    val owner: QemuRuntimeOwner,
    val file: CheckedFileIdentity,
)

internal data class QemuStaleRuntimeEvidence(
    val instanceRoot: Path,
    val owner: QemuRuntimeOwner,
    val ownerFile: CheckedFileIdentity,
    val endpoints: Map<Path, CheckedFileIdentity?>,
) : StaleRuntimeEvidence

/**
 * Durable process identity and fixed-endpoint cleanup for one app-private QEMU.
 * The owner file is published atomically and is always deleted after endpoints.
 */
internal class QemuRuntimeOwnerStore(
    paths: VmPaths,
    private val processIdentityReader: ProcessIdentityReader = ProcProcessIdentityReader,
) {
    private val instanceRoot = paths.instanceDirectory.toPath().toAbsolutePath().normalize()
    private val ownerPath = paths.qemuOwnerRecord.toPath().toAbsolutePath().normalize()
    private val endpoints = listOf(
        paths.serialSocket,
        paths.terminalSocket,
        paths.controlSocket,
        paths.hostSocket,
        paths.qmpSocket,
    ).map { it.toPath().toAbsolutePath().normalize() }

    init {
        require(ownerPath.parent == instanceRoot)
        require(endpoints.all { it.parent == instanceRoot })
    }

    fun capture(pid: Long, generation: Long): QemuRuntimeOwner {
        val expected = when (val observed = processIdentityReader.observe(pid)) {
            is ProcessIdentityObservation.Alive -> observed.identity
            ProcessIdentityObservation.Dead -> throw IOException("QEMU exited before ownership commit")
            is ProcessIdentityObservation.Uncertain -> throw IOException(
                "QEMU process identity unavailable: ${observed.errorCode.name}",
            )
        }
        return QemuRuntimeOwner(expected, generation)
    }

    fun publish(owner: QemuRuntimeOwner): PublishedQemuOwner {
        validateRoot()
        if (existsNoFollow(ownerPath)) throw IOException("QEMU owner record already exists")
        val temporary = Files.createTempFile(instanceRoot, ".qemu-owner-", ".tmp")
        try {
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
                val bytes = encode(owner).toByteArray(Charsets.UTF_8)
                var buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(temporary, ownerPath, StandardCopyOption.ATOMIC_MOVE)
            VmPathSecurity.forceDirectory(instanceRoot)
            val checked = checkedOwnerFile()
            val decoded = decode(readCheckedOwner(checked))
            if (decoded != owner) throw IOException("Published QEMU owner record changed")
            return PublishedQemuOwner(owner, checked)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    fun inspect(): QemuOwnerInspection {
        if (!existsNoFollow(ownerPath)) return QemuOwnerInspection.Missing
        return try {
            val checked = checkedOwnerFile()
            QemuOwnerInspection.Valid(decode(readCheckedOwner(checked)), checked)
        } catch (_: SecurityException) {
            QemuOwnerInspection.Uncertain(LifecycleErrorCode.SECURITY)
        } catch (_: IOException) {
            QemuOwnerInspection.Uncertain(LifecycleErrorCode.SECURITY)
        } catch (_: IllegalArgumentException) {
            QemuOwnerInspection.Uncertain(LifecycleErrorCode.SECURITY)
        }
    }

    internal fun observeProcess(pid: Long): ProcessIdentityObservation =
        processIdentityReader.observe(pid)

    fun proveDead(inspection: QemuOwnerInspection.Valid): DeadOwnerProof =
        when (val observed = observeProcess(inspection.owner.process.pid)) {
            ProcessIdentityObservation.Dead -> deadEvidence(inspection)
            is ProcessIdentityObservation.Alive -> if (observed.identity == inspection.owner.process) {
                DeadOwnerProof.ExactProcessAlive
            } else {
                // A reused PID proves that the recorded pid/start-time pair no longer exists.
                deadEvidence(inspection)
            }
            is ProcessIdentityObservation.Uncertain -> DeadOwnerProof.Uncertain(observed.errorCode)
        }

    fun exactProcessIsDead(owner: QemuRuntimeOwner): Boolean =
        when (val observed = processIdentityReader.observe(owner.process.pid)) {
            ProcessIdentityObservation.Dead -> true
            is ProcessIdentityObservation.Alive -> observed.identity != owner.process
            is ProcessIdentityObservation.Uncertain -> false
        }

    fun cleanup(evidence: QemuStaleRuntimeEvidence) {
        require(evidence.instanceRoot == instanceRoot) { "Cleanup evidence belongs to another VM" }
        validateRoot()
        val currentOwner = inspect()
        if (currentOwner !is QemuOwnerInspection.Valid ||
            currentOwner.owner != evidence.owner || currentOwner.file != evidence.ownerFile
        ) throw IOException("QEMU owner record changed before cleanup")
        if (proveDead(currentOwner) !is DeadOwnerProof.Proven) {
            throw IOException("QEMU process death proof changed before cleanup")
        }
        verifyEndpointSnapshot(evidence.endpoints)
        deleteEndpointSnapshot(evidence.endpoints)
        removeExactOwner(PublishedQemuOwner(evidence.owner, evidence.ownerFile))
        VmPathSecurity.forceDirectory(instanceRoot)
    }

    /** Called only after waitFor returned for the exact child owned by [owner]. */
    fun cleanupAfterConfirmedReap(owner: QemuRuntimeOwner, published: PublishedQemuOwner?) {
        validateRoot()
        if (published == null && existsNoFollow(ownerPath)) {
            throw IOException("Cannot clean endpoints owned by another QEMU record")
        }
        val snapshot = endpoints.associateWith(::checkedEndpointOrNull)
        verifyEndpointSnapshot(snapshot)
        deleteEndpointSnapshot(snapshot)
        if (published != null) {
            if (published.owner != owner) throw IOException("QEMU owner generation mismatch")
            removeExactOwner(published)
        }
        VmPathSecurity.forceDirectory(instanceRoot)
    }

    private fun deadEvidence(inspection: QemuOwnerInspection.Valid): DeadOwnerProof = try {
        DeadOwnerProof.Proven(
            QemuStaleRuntimeEvidence(
                instanceRoot,
                inspection.owner,
                inspection.file,
                endpoints.associateWith(::checkedEndpointOrNull),
            ),
        )
    } catch (_: SecurityException) {
        DeadOwnerProof.Uncertain(LifecycleErrorCode.SECURITY)
    } catch (_: IOException) {
        DeadOwnerProof.Uncertain(LifecycleErrorCode.SECURITY)
    }

    private fun verifyEndpointSnapshot(expected: Map<Path, CheckedFileIdentity?>) {
        if (expected.keys != endpoints.toSet()) throw IOException("Incomplete endpoint cleanup evidence")
        for ((path, identity) in expected) {
            if (checkedEndpointOrNull(path) != identity) {
                throw IOException("Runtime endpoint changed before cleanup: $path")
            }
        }
    }

    private fun deleteEndpointSnapshot(expected: Map<Path, CheckedFileIdentity?>) {
        for ((path, identity) in expected) {
            if (identity == null) continue
            if (checkedEndpointOrNull(path) != identity) {
                throw IOException("Runtime endpoint replaced during cleanup: $path")
            }
            Files.delete(path)
        }
    }

    private fun removeExactOwner(published: PublishedQemuOwner) {
        val current = checkedOwnerFile()
        if (current != published.file || decode(readCheckedOwner(current)) != published.owner) {
            throw IOException("QEMU owner record replaced before removal")
        }
        Files.delete(ownerPath)
    }

    private fun checkedEndpointOrNull(path: Path): CheckedFileIdentity? {
        if (!existsNoFollow(path)) return null
        val attributes = attributes(path)
        if (attributes.isDirectory || attributes.isRegularFile || attributes.isSymbolicLink) {
            throw SecurityException("Unsafe runtime endpoint type")
        }
        requireExpectedOwner(path)
        return identity(attributes)
    }

    private fun checkedOwnerFile(): CheckedFileIdentity {
        val attributes = attributes(ownerPath)
        if (!attributes.isRegularFile || attributes.isDirectory || attributes.isSymbolicLink ||
            attributes.size() !in 1..MAX_OWNER_BYTES.toLong()
        ) throw SecurityException("Unsafe QEMU owner record")
        requireExpectedOwner(ownerPath)
        return identity(attributes)
    }

    private fun readCheckedOwner(before: CheckedFileIdentity): String {
        val bytes = readBoundedNoFollow(ownerPath, MAX_OWNER_BYTES)
        if (checkedOwnerFile() != before) throw IOException("QEMU owner record changed while reading")
        return bytes.toString(Charsets.UTF_8)
    }

    private fun validateRoot() {
        val attributes = attributes(instanceRoot)
        if (!attributes.isDirectory || attributes.isSymbolicLink || instanceRoot.toRealPath() != instanceRoot) {
            throw SecurityException("Unsafe QEMU instance directory")
        }
    }

    private fun requireExpectedOwner(path: Path) {
        if (Files.getOwner(path, LinkOption.NOFOLLOW_LINKS) !=
            Files.getOwner(instanceRoot, LinkOption.NOFOLLOW_LINKS)
        ) throw SecurityException("Unexpected runtime file owner")
    }

    private fun attributes(path: Path): BasicFileAttributes = Files.readAttributes(
        path,
        BasicFileAttributes::class.java,
        LinkOption.NOFOLLOW_LINKS,
    )

    private fun identity(attributes: BasicFileAttributes): CheckedFileIdentity = CheckedFileIdentity(
        attributes.fileKey() ?: throw SecurityException("Filesystem identity unavailable"),
        attributes.size(),
    )

    private fun encode(owner: QemuRuntimeOwner): String = buildString {
        appendLine("version=1")
        appendLine("pid=${owner.process.pid}")
        appendLine("start_time_ticks=${owner.process.startTimeTicks}")
        append("generation=${owner.generation}")
    }

    private fun decode(encoded: String): QemuRuntimeOwner {
        val lines = encoded.split('\n')
        if (lines.size != 4 || lines[0] != "version=1") throw IOException("Corrupt QEMU owner record")
        fun positive(index: Int, key: String): Long {
            val prefix = "$key="
            val line = lines[index]
            if (!line.startsWith(prefix) || line.indexOf('=', prefix.length) >= 0) {
                throw IOException("Corrupt QEMU owner record")
            }
            return line.substring(prefix.length).toLongOrNull()?.takeIf { it > 0 }
                ?: throw IOException("Corrupt QEMU owner record")
        }
        return QemuRuntimeOwner(
            QemuProcessIdentity(positive(1, "pid"), positive(2, "start_time_ticks")),
            positive(3, "generation"),
        )
    }

    private companion object { const val MAX_OWNER_BYTES = 256 }
}

private fun existsNoFollow(path: Path): Boolean = Files.exists(path, LinkOption.NOFOLLOW_LINKS)

private fun readBoundedNoFollow(path: Path, maxBytes: Int): ByteArray {
    FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
        val buffer = ByteBuffer.allocate(maxBytes + 1)
        while (buffer.hasRemaining() && channel.read(buffer) >= 0) Unit
        if (buffer.position() > maxBytes) throw IOException("Bounded file is too large")
        return buffer.array().copyOf(buffer.position())
    }
}
