package com.excp.podroid.profiles

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Collections
import kotlin.math.min

open class ProfileRepositoryException(message: String, cause: Throwable? = null) : IOException(message, cause)
class ProfileRepositoryCorruptException(message: String, cause: Throwable? = null) :
    ProfileRepositoryException(message, cause)
class ProfileGenerationRollbackException(message: String) : ProfileRepositoryException(message)
class ProfileGenerationEquivocationException(message: String) : ProfileRepositoryException(message)
class ProfileDownloadException(message: String, cause: Throwable? = null) : ProfileRepositoryException(message, cause)
class ProfileActivationException(message: String, cause: Throwable? = null) : ProfileRepositoryException(message, cause)

/** The transport must apply [deadlineNanos] with a monotonic clock and must not follow redirects. */
data class ArtifactFetchRequest(
    val url: ArtifactDownloadUrl,
    val maxResponseBytes: Long,
    val deadlineNanos: Long,
)

/** Hostile transport metadata is checked before [body] is consumed. */
class ArtifactFetchResponse(
    val statusCode: Int,
    val finalUrl: String,
    val redirectCount: Int,
    val contentEncoding: String?,
    val contentLengthBytes: Long?,
    val body: InputStream,
) : Closeable {
    override fun close() = body.close()
}

fun interface ProfileArtifactFetcher {
    @Throws(IOException::class)
    fun fetch(request: ArtifactFetchRequest): ArtifactFetchResponse
}

enum class GuestDataPolicy {
    PRESERVE_DATA,
    DELETE_DATA,
}

data class PreparedProfileCandidate(
    val profileId: ProfileId,
    val generation: ProfileGeneration,
    val manifestSha256: Sha256Digest,
)

class PreparedProfile internal constructor(
    val candidate: PreparedProfileCandidate,
    val dataCompatibility: DataCompatibilityId,
    artifactFiles: Map<ArtifactRole, File>,
) {
    val artifactFiles: Map<ArtifactRole, File> = Collections.unmodifiableMap(artifactFiles.toMap())
}

data class ActivationState(
    val activationSequence: Long,
    val active: PreparedProfileCandidate,
    val rollback: PreparedProfileCandidate?,
)

/** Explicit destructive authority, scoped to the state and candidate observed by the caller. */
class DataDeletionConfirmation private constructor(
    val expectedActivationSequence: Long,
    val candidate: PreparedProfileCandidate,
    internal val storagePathSha256: Sha256Digest,
) {
    companion object {
        fun confirm(
            expectedActivationSequence: Long,
            candidate: PreparedProfileCandidate,
            storageFile: File,
        ): DataDeletionConfirmation {
            require(expectedActivationSequence >= 0) { "activation sequence must not be negative" }
            return DataDeletionConfirmation(
                expectedActivationSequence,
                candidate,
                Sha256Digest(storageFile.toPath().toAbsolutePath().normalize().toString().sha256Hex()),
            )
        }
    }
}

enum class ProfileRepositoryFaultPoint {
    AFTER_DELETION_INTENT,
    AFTER_STORAGE_DELETION,
}

fun interface ProfileRepositoryFaultInjector {
    @Throws(IOException::class)
    fun check(point: ProfileRepositoryFaultPoint)
}

/**
 * Pure JVM profile store. The caller owns the app-private [repositoryDirectory] and storage path;
 * this class never constructs a path from a URL, profile ID, role, or other unchecked metadata.
 */
class ProfileRepository(
    repositoryDirectory: File,
    private val approvedOrigins: ApprovedArtifactOrigins,
    private val resolvePublicKey: (SigningKeyId) -> Ed25519PublicKey?,
    private val artifactFetcher: ProfileArtifactFetcher,
    private val verifier: Ed25519Verifier = JcaEd25519Verifier,
    private val fetchTimeoutMillis: Long = DEFAULT_FETCH_TIMEOUT_MILLIS,
    private val lockTimeoutMillis: Long = DEFAULT_LOCK_TIMEOUT_MILLIS,
    private val faultInjector: ProfileRepositoryFaultInjector = ProfileRepositoryFaultInjector { },
) {
    private val root = repositoryDirectory.toPath().toAbsolutePath().normalize()
    private val blobs = root.resolve(BLOBS_DIRECTORY)
    private val prepared = root.resolve(PREPARED_DIRECTORY)
    private val state = root.resolve(STATE_DIRECTORY)
    private val temporary = root.resolve(TEMP_DIRECTORY)
    private val lockPath = root.resolve(LOCK_FILE)
    private val artifactTemporary = temporary.resolve(ARTIFACT_TEMP_FILE)
    private val recordTemporary = temporary.resolve(RECORD_TEMP_FILE)
    private val pendingActivationPath = state.resolve(PENDING_ACTIVATION_FILE)

    init {
        require(fetchTimeoutMillis in 1..MAX_FETCH_TIMEOUT_MILLIS) { "fetch timeout is outside the bound" }
        require(lockTimeoutMillis in 1..MAX_LOCK_TIMEOUT_MILLIS) { "lock timeout is outside the bound" }
    }

    /** Verifies, streams, fsyncs, CAS-publishes, then atomically records a complete generation. */
    @Throws(IOException::class)
    fun prepare(signedEnvelopeBytes: ByteArray): PreparedProfile {
        val verified = VerifiedProfileJsonCodec.decodeManifest(
            signedEnvelopeBytes,
            approvedOrigins,
            resolvePublicKey,
            verifier,
        )
        return withRepositoryLock {
            recoverLocked()
            prepareLocked(verified)
        }
    }

    /** Bounded startup recovery: stale exclusive temps are removed and floors are reconciled upward. */
    @Throws(IOException::class)
    fun recover() {
        withRepositoryLock {
            recoverLocked()
            if (readPendingActivationLocked() != null) {
                throw ProfileActivationException(
                    "confirmed data deletion is pending; retry DELETE_DATA activation with the bound storage path",
                )
            }
        }
    }

    @Throws(IOException::class)
    fun activationState(): ActivationState? = withRepositoryLock {
        recoverLocked()
        if (readPendingActivationLocked() != null) {
            throw ProfileActivationException("a confirmed data-deletion activation requires recovery")
        }
        readActivationStateLocked()
    }

    /**
     * Activates only the current prepared floor. DELETE_DATA is executed only with an exact
     * confirmation; PRESERVE_DATA validates but never writes, renames, or deletes [storageFile].
     */
    @Throws(IOException::class)
    fun activate(
        candidate: PreparedProfileCandidate,
        dataPolicy: GuestDataPolicy,
        storageFile: File,
        deletionConfirmation: DataDeletionConfirmation? = null,
    ): ActivationState = withRepositoryLock {
        recoverLocked()
        val normalizedStorage = storageFile.toPath().toAbsolutePath().normalize()
        validateStoragePath(normalizedStorage)
        readPendingActivationLocked()?.let { pending ->
            if (dataPolicy != GuestDataPolicy.DELETE_DATA || pending.desired.active != candidate ||
                pending.storagePathSha256 != Sha256Digest(normalizedStorage.toString().sha256Hex())
            ) {
                throw ProfileActivationException("a different confirmed data-deletion activation requires recovery")
            }
            val currentDuringRecovery = readActivationStateLocked()
            if (currentDuringRecovery != pending.desired) {
                deleteStorageFile(normalizedStorage)
                faultInjector.check(ProfileRepositoryFaultPoint.AFTER_STORAGE_DELETION)
                writeReplaceRecord(activationPath(), encodeActivation(pending.desired))
            }
            clearPendingActivation()
            return@withRepositoryLock pending.desired
        }

        val current = readActivationStateLocked()
        if (current?.active == candidate) return@withRepositoryLock current

        val preparedRecord = requireUsableCandidateLocked(candidate, requireCurrentFloor = true)
        when (dataPolicy) {
            GuestDataPolicy.PRESERVE_DATA -> {
                if (deletionConfirmation != null) {
                    throw ProfileActivationException("deletion confirmation is invalid for PRESERVE_DATA")
                }
                current?.let { activation ->
                    val activeRecord = requireUsableCandidateLocked(activation.active, requireCurrentFloor = false)
                    if (activeRecord.dataCompatibility != preparedRecord.dataCompatibility) {
                        throw ProfileActivationException("PRESERVE_DATA activation requires storage-compatible profiles")
                    }
                }
            }
            GuestDataPolicy.DELETE_DATA -> {
                val expectedSequence = current?.activationSequence ?: 0L
                val storageDigest = Sha256Digest(normalizedStorage.toString().sha256Hex())
                if (deletionConfirmation?.expectedActivationSequence != expectedSequence ||
                    deletionConfirmation.candidate != candidate ||
                    deletionConfirmation.storagePathSha256 != storageDigest
                ) {
                    throw ProfileActivationException(
                        "DELETE_DATA requires confirmation for the current activation state, candidate, and storage path",
                    )
                }
            }
        }

        val next = ActivationState(nextActivationSequence(current?.activationSequence ?: 0L), candidate, current?.active)
        if (dataPolicy == GuestDataPolicy.DELETE_DATA) {
            writeImmutableRecord(pendingActivationPath, encodePendingActivation(PendingActivation(next, deletionConfirmation!!.storagePathSha256)))
            faultInjector.check(ProfileRepositoryFaultPoint.AFTER_DELETION_INTENT)
            deleteStorageFile(normalizedStorage)
            faultInjector.check(ProfileRepositoryFaultPoint.AFTER_STORAGE_DELETION)
        }
        writeReplaceRecord(activationPath(), encodeActivation(next))
        if (dataPolicy == GuestDataPolicy.DELETE_DATA) clearPendingActivation()
        // Decode the durable representation before returning it as authoritative state.
        val published = readActivationStateLocked()
            ?: throw ProfileRepositoryCorruptException("published activation record is missing")
        if (published != next || preparedRecord.candidate != candidate) {
            throw ProfileRepositoryCorruptException("published activation record changed unexpectedly")
        }
        published
    }

    /** Rollback is local-only, sequence-bound, preserve-only, and compatibility-gated. */
    @Throws(IOException::class)
    fun rollback(
        expectedActivationSequence: Long,
        dataPolicy: GuestDataPolicy,
        storageFile: File,
    ): ActivationState = withRepositoryLock {
        recoverLocked()
        if (readPendingActivationLocked() != null) {
            throw ProfileActivationException("a confirmed data-deletion activation requires recovery")
        }
        if (dataPolicy != GuestDataPolicy.PRESERVE_DATA) {
            throw ProfileActivationException("rollback requires explicit PRESERVE_DATA")
        }
        validateStoragePath(storageFile.toPath())
        val current = readActivationStateLocked()
            ?: throw ProfileActivationException("there is no active profile to roll back")
        if (current.activationSequence != expectedActivationSequence) {
            throw ProfileActivationException("rollback confirmation does not match the current activation sequence")
        }
        val rollbackCandidate = current.rollback
            ?: throw ProfileActivationException("there is no retained local rollback profile")
        val activeRecord = requireUsableCandidateLocked(current.active, requireCurrentFloor = false)
        val rollbackRecord = requireUsableCandidateLocked(rollbackCandidate, requireCurrentFloor = false)
        if (activeRecord.dataCompatibility != rollbackRecord.dataCompatibility) {
            throw ProfileActivationException("rollback profile is not storage-compatible with the active profile")
        }

        val next = ActivationState(
            nextActivationSequence(current.activationSequence),
            rollbackCandidate,
            current.active,
        )
        writeReplaceRecord(activationPath(), encodeActivation(next))
        readActivationStateLocked()?.also {
            if (it != next) throw ProfileRepositoryCorruptException("published rollback record changed unexpectedly")
        } ?: throw ProfileRepositoryCorruptException("published rollback record is missing")
    }

    private fun prepareLocked(verified: VerifiedProfileManifest): PreparedProfile {
        val profile = verified.profile
        val profileKey = profileKey(profile.id)
        val floor = readFloor(profileKey)
        val preparedRecords = readPreparedRecords(profileKey)
        val highestPrepared = preparedRecords.maxByOrNull { it.candidate.generation.value }
        val effectiveFloor = when {
            floor == null -> highestPrepared
            highestPrepared == null -> throw ProfileRepositoryCorruptException("generation floor has no immutable prepared record")
            floor.generation.value > highestPrepared.candidate.generation.value ->
                throw ProfileRepositoryCorruptException("generation floor is above all immutable prepared records")
            floor.generation == highestPrepared.candidate.generation &&
                floor.manifestSha256 != highestPrepared.candidate.manifestSha256 ->
                throw ProfileRepositoryCorruptException("generation floor conflicts with its immutable prepared record")
            else -> highestPrepared
        }

        if (effectiveFloor != null) {
            when {
                profile.generation.value < effectiveFloor.candidate.generation.value ->
                    throw ProfileGenerationRollbackException("profile generation is below the monotonic floor")
                profile.generation == effectiveFloor.candidate.generation &&
                    verified.manifestSha256 != effectiveFloor.candidate.manifestSha256 ->
                    throw ProfileGenerationEquivocationException("equal profile generation has a different signed manifest")
            }
        }

        val candidate = PreparedProfileCandidate(profile.id, profile.generation, verified.manifestSha256)
        val expectedRecord = PreparedRecord(
            candidate,
            profile.dataCompatibility,
            profile.artifacts.sortedBy { it.role.ordinal }.map {
                PreparedArtifact(it.role, it.sha256, it.sizeBytes)
            },
        )
        val existing = preparedRecords.singleOrNull { it.candidate.generation == profile.generation }
        if (existing != null) {
            if (existing != expectedRecord) {
                throw ProfileGenerationEquivocationException("immutable generation record conflicts with the signed manifest")
            }
            validatePreparedBlobs(existing)
            publishFloorIfNeeded(profileKey, existing)
            return existing.toPublic()
        }

        if (preparedRecords.size >= MAX_GENERATIONS_PER_PROFILE) {
            throw ProfileRepositoryException("prepared generation retention bound reached")
        }
        profile.artifacts.sortedBy { it.role.ordinal }.forEach { ensureBlob(it) }
        publishPrepared(profileKey, expectedRecord)
        validatePreparedBlobs(expectedRecord)
        publishFloorIfNeeded(profileKey, expectedRecord)
        return expectedRecord.toPublic()
    }

    private fun ensureBlob(artifact: ProfileArtifact) {
        val destination = blobPath(artifact.sha256)
        if (existsNoFollow(destination)) {
            validateBlob(destination, artifact.sha256, artifact.sizeBytes)
            return
        }

        ensureNoPath(artifactTemporary)
        var created = false
        try {
            val options = setOf<OpenOption>(
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            )
            FileChannel.open(artifactTemporary, options).use { outputChannel ->
                created = true
                val deadline = deadlineAfterMillis(fetchTimeoutMillis)
                val request = ArtifactFetchRequest(
                    artifact.url,
                    artifact.sizeBytes.value + 1L,
                    deadline,
                )
                val response = try {
                    artifactFetcher.fetch(request)
                } catch (failure: IOException) {
                    throw ProfileDownloadException("artifact fetch failed", failure)
                }
                response.use {
                    validateTransportResponse(request, artifact, response)
                    streamExactArtifact(response.body, outputChannel, artifact, deadline)
                }
                outputChannel.force(true)
            }
            if (existsNoFollow(destination)) {
                validateBlob(destination, artifact.sha256, artifact.sizeBytes)
                Files.delete(artifactTemporary)
            } else {
                Files.move(artifactTemporary, destination, StandardCopyOption.ATOMIC_MOVE)
                forceDirectory(blobs)
                validateBlob(destination, artifact.sha256, artifact.sizeBytes)
            }
            created = false
        } catch (failure: Throwable) {
            if (created) runCatching { Files.deleteIfExists(artifactTemporary) }
            if (failure is IOException) throw failure
            throw ProfileDownloadException("artifact download failed", failure)
        }
    }

    private fun validateTransportResponse(
        request: ArtifactFetchRequest,
        artifact: ProfileArtifact,
        response: ArtifactFetchResponse,
    ) {
        if (response.statusCode != 200) throw ProfileDownloadException("artifact response status is not 200")
        if (response.redirectCount != 0 || response.finalUrl != request.url.value) {
            throw ProfileDownloadException("artifact redirects or final URL changes are forbidden")
        }
        if (response.contentEncoding != null) {
            throw ProfileDownloadException("artifact content encoding is forbidden")
        }
        val contentLength = response.contentLengthBytes
        if (contentLength != null && contentLength != artifact.sizeBytes.value) {
            throw ProfileDownloadException("artifact Content-Length does not match the signed size")
        }
        if (System.nanoTime() - request.deadlineNanos >= 0L) {
            throw ProfileDownloadException("artifact fetch deadline expired before streaming")
        }
    }

    private fun streamExactArtifact(
        input: InputStream,
        output: FileChannel,
        artifact: ProfileArtifact,
        deadlineNanos: Long,
    ) {
        val expected = artifact.sizeBytes.value
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        var total = 0L
        var zeroReads = 0
        while (total < expected + 1L) {
            val wanted = min(buffer.size.toLong(), expected + 1L - total).toInt()
            val count = try {
                input.read(buffer, 0, wanted)
            } catch (failure: IOException) {
                throw ProfileDownloadException("artifact stream read failed", failure)
            }
            if (System.nanoTime() - deadlineNanos >= 0L) {
                throw ProfileDownloadException("artifact streaming deadline expired")
            }
            if (count < 0) break
            if (count == 0) {
                zeroReads++
                if (zeroReads > MAX_ZERO_READS) throw ProfileDownloadException("artifact stream made no progress")
                continue
            }
            zeroReads = 0
            total += count
            if (total > expected) throw ProfileDownloadException("artifact stream exceeds the signed size")
            digest.update(buffer, 0, count)
            val bytes = ByteBuffer.wrap(buffer, 0, count)
            writeFully(output, bytes, "artifact file write made no progress")
        }
        if (total != expected) throw ProfileDownloadException("artifact stream is shorter than the signed size")
        val actualDigest = digest.digest().toLowerHex()
        if (actualDigest != artifact.sha256.value) throw ProfileDownloadException("artifact SHA-256 does not match the signed digest")
    }

    private fun validateBlob(path: Path, digest: Sha256Digest, size: ArtifactSizeBytes) {
        requireRegularFile(path, "content-addressed blob")
        val attributes = attributesNoFollow(path)
        if (attributes.size() != size.value) {
            throw ProfileRepositoryCorruptException("content-addressed blob size does not match its manifest")
        }
        val actual = MessageDigest.getInstance("SHA-256")
        FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.allocate(STREAM_BUFFER_BYTES)
            var total = 0L
            var zeroReads = 0
            while (total < size.value + 1L) {
                buffer.clear()
                buffer.limit(min(buffer.capacity().toLong(), size.value + 1L - total).toInt())
                val count = channel.read(buffer)
                if (count < 0) break
                if (count == 0) {
                    zeroReads++
                    if (zeroReads > MAX_ZERO_READS) {
                        throw ProfileRepositoryCorruptException("blob validation made no progress")
                    }
                    continue
                }
                zeroReads = 0
                total += count
                if (total > size.value) throw ProfileRepositoryCorruptException("blob exceeds its manifest size")
                actual.update(buffer.array(), 0, count)
            }
            if (total != size.value) throw ProfileRepositoryCorruptException("blob is shorter than its manifest size")
        }
        if (actual.digest().toLowerHex() != digest.value) {
            throw ProfileRepositoryCorruptException("content-addressed blob digest mismatch")
        }
    }

    private fun publishPrepared(profileKey: String, record: PreparedRecord) {
        val directory = prepared.resolve(profileKey)
        if (!existsNoFollow(directory)) {
            if (countDirectories(prepared) >= MAX_PROFILES) throw ProfileRepositoryException("profile retention bound reached")
            createDirectory(directory)
        } else {
            requireDirectory(directory)
        }
        val destination = preparedPath(profileKey, record.candidate.generation)
        if (existsNoFollow(destination)) {
            val existing = decodePrepared(readBoundedRecord(destination), profileKey)
            if (existing != record) throw ProfileGenerationEquivocationException("prepared generation is immutable")
            return
        }
        writeImmutableRecord(destination, encodePrepared(record))
    }

    private fun publishFloorIfNeeded(profileKey: String, record: PreparedRecord) {
        val current = readFloor(profileKey)
        when {
            current == null || current.generation.value < record.candidate.generation.value -> {
                writeReplaceRecord(
                    floorPath(profileKey),
                    encodeFloor(FloorRecord(record.candidate.profileId, record.candidate.generation, record.candidate.manifestSha256)),
                )
            }
            current.generation == record.candidate.generation && current.manifestSha256 == record.candidate.manifestSha256 -> Unit
            current.generation == record.candidate.generation ->
                throw ProfileGenerationEquivocationException("generation floor records a different manifest")
            else -> throw ProfileGenerationRollbackException("generation floor cannot be lowered")
        }
    }

    private fun requireUsableCandidateLocked(
        candidate: PreparedProfileCandidate,
        requireCurrentFloor: Boolean,
    ): PreparedRecord {
        val profileKey = profileKey(candidate.profileId)
        val path = preparedPath(profileKey, candidate.generation)
        if (!existsNoFollow(path)) throw ProfileActivationException("candidate is not retained locally")
        val record = decodePrepared(readBoundedRecord(path), profileKey)
        if (record.candidate != candidate) throw ProfileActivationException("candidate does not match its immutable prepared record")
        if (requireCurrentFloor) {
            val floor = readFloor(profileKey)
                ?: throw ProfileRepositoryCorruptException("candidate profile has no generation floor")
            if (floor.generation != candidate.generation || floor.manifestSha256 != candidate.manifestSha256) {
                throw ProfileActivationException("normal activation requires the current monotonic generation floor")
            }
        }
        validatePreparedBlobs(record)
        return record
    }

    private fun validatePreparedBlobs(record: PreparedRecord) {
        record.artifacts.forEach { validateBlob(blobPath(it.sha256), it.sha256, it.sizeBytes) }
    }

    private fun validateStoragePath(storagePath: Path) {
        val normalized = storagePath.toAbsolutePath().normalize()
        val parent = normalized.parent ?: throw ProfileActivationException("storage file has no parent")
        requireDirectory(parent)
        if (parent.toRealPath() != parent) {
            throw ProfileActivationException("storage file parent contains a symbolic path")
        }
        if (existsNoFollow(normalized)) requireRegularFile(normalized, "storage file")
    }

    private fun deleteStorageFile(storagePath: Path) {
        val normalized = storagePath.toAbsolutePath().normalize()
        if (existsNoFollow(normalized)) {
            requireRegularFile(normalized, "storage file")
            Files.delete(normalized)
            forceDirectory(normalized.parent)
        }
    }

    private fun recoverLocked() {
        validateTreeAndCleanTemps()
        val preparedByKey = readAllPreparedRecords()
        val floorsByKey = readAllFloors()
        for ((profileKey, records) in preparedByKey) {
            val highest = records.maxByOrNull { it.candidate.generation.value } ?: continue
            val floor = floorsByKey[profileKey]
            when {
                floor == null || floor.generation.value < highest.candidate.generation.value -> publishFloorIfNeeded(profileKey, highest)
                floor.generation.value > highest.candidate.generation.value ->
                    throw ProfileRepositoryCorruptException("generation floor is above retained immutable state")
                floor.manifestSha256 != highest.candidate.manifestSha256 ->
                    throw ProfileRepositoryCorruptException("generation floor conflicts with retained immutable state")
            }
        }
        val orphanFloors = floorsByKey.keys - preparedByKey.keys
        if (orphanFloors.isNotEmpty()) throw ProfileRepositoryCorruptException("generation floor has no retained immutable state")
        val activation = readActivationStateLocked()
        val pendingActivation = readPendingActivationLocked()
        pendingActivation?.let { pending ->
            when {
                activation == pending.desired -> clearPendingActivation()
                activation == null && pending.desired.activationSequence != 1L ->
                    throw ProfileRepositoryCorruptException("pending activation sequence has no predecessor")
                activation != null &&
                    (pending.desired.activationSequence <= activation.activationSequence ||
                        pending.desired.activationSequence - activation.activationSequence != 1L ||
                        pending.desired.rollback != activation.active) ->
                    throw ProfileRepositoryCorruptException("pending activation does not follow current state")
            }
        }
        val effectiveActivation = readActivationStateLocked()
        val effectivePending = readPendingActivationLocked()
        val referencedCandidates = buildList {
            effectiveActivation?.let { add(it.active); it.rollback?.let(::add) }
            effectivePending?.desired?.let { add(it.active); it.rollback?.let(::add) }
        }
        // Records are parsed and references must remain local; bytes are fully revalidated on use.
        referencedCandidates.distinct().forEach { candidate ->
            val key = profileKey(candidate.profileId)
            val path = preparedPath(key, candidate.generation)
            if (!existsNoFollow(path)) throw ProfileRepositoryCorruptException("activation references missing local state")
            val record = decodePrepared(readBoundedRecord(path), key)
            if (record.candidate != candidate) throw ProfileRepositoryCorruptException("activation candidate conflicts with local state")
        }
    }

    private fun validateTreeAndCleanTemps() {
        requireDirectory(root)
        if (root.toRealPath() != root) throw ProfileRepositoryCorruptException("repository root is not a physical fixed path")
        validateDirectoryEntries(root, setOf(LOCK_FILE, BLOBS_DIRECTORY, PREPARED_DIRECTORY, STATE_DIRECTORY, TEMP_DIRECTORY))
        requireRegularFile(lockPath, "repository lock")
        listOf(blobs, prepared, state, temporary).forEach(::requireDirectory)

        var blobCount = 0
        Files.newDirectoryStream(blobs).use { entries ->
            for (entry in entries) {
                blobCount++
                if (blobCount > MAX_BLOBS) throw ProfileRepositoryCorruptException("blob entry bound exceeded")
                if (!BLOB_NAME.matches(entry.fileName.toString())) throw ProfileRepositoryCorruptException("unknown blob entry")
                requireRegularFile(entry, "content-addressed blob")
                if (attributesNoFollow(entry).size() > ProfileLimits.MAX_ARTIFACT_BYTES) {
                    throw ProfileRepositoryCorruptException("blob exceeds the repository byte bound")
                }
            }
        }

        var profileCount = 0
        Files.newDirectoryStream(prepared).use { profiles ->
            for (profileDirectory in profiles) {
                profileCount++
                if (profileCount > MAX_PROFILES) throw ProfileRepositoryCorruptException("profile entry bound exceeded")
                if (!PROFILE_KEY.matches(profileDirectory.fileName.toString())) {
                    throw ProfileRepositoryCorruptException("unknown prepared profile entry")
                }
                requireDirectory(profileDirectory)
                var generationCount = 0
                Files.newDirectoryStream(profileDirectory).use { generations ->
                    for (generation in generations) {
                        generationCount++
                        if (generationCount > MAX_GENERATIONS_PER_PROFILE) {
                            throw ProfileRepositoryCorruptException("prepared generation entry bound exceeded")
                        }
                        if (!PREPARED_NAME.matches(generation.fileName.toString())) {
                            throw ProfileRepositoryCorruptException("unknown prepared generation entry")
                        }
                        requireRegularFile(generation, "prepared generation record")
                        if (attributesNoFollow(generation).size() > MAX_RECORD_BYTES) {
                            throw ProfileRepositoryCorruptException("prepared record exceeds the byte bound")
                        }
                    }
                }
            }
        }

        var stateCount = 0
        Files.newDirectoryStream(state).use { entries ->
            for (entry in entries) {
                stateCount++
                if (stateCount > MAX_STATE_RECORDS) throw ProfileRepositoryCorruptException("state entry bound exceeded")
                val name = entry.fileName.toString()
                if (name != ACTIVATION_FILE && name != PENDING_ACTIVATION_FILE && !FLOOR_NAME.matches(name)) {
                    throw ProfileRepositoryCorruptException("unknown repository state entry")
                }
                requireRegularFile(entry, "repository state record")
                if (attributesNoFollow(entry).size() > MAX_RECORD_BYTES) {
                    throw ProfileRepositoryCorruptException("state record exceeds the byte bound")
                }
            }
        }

        val stale = ArrayList<Path>()
        Files.newDirectoryStream(temporary).use { entries ->
            for (entry in entries) {
                if (entry.fileName.toString() !in setOf(ARTIFACT_TEMP_FILE, RECORD_TEMP_FILE)) {
                    throw ProfileRepositoryCorruptException("unknown temporary repository entry")
                }
                requireRegularFile(entry, "temporary repository file")
                stale.add(entry)
            }
        }
        stale.forEach(Files::delete)
        if (stale.isNotEmpty()) forceDirectory(temporary)
    }

    private fun validateDirectoryEntries(directory: Path, expected: Set<String>) {
        val actual = Files.newDirectoryStream(directory).use { stream -> stream.map { it.fileName.toString() }.toSet() }
        if (actual != expected) throw ProfileRepositoryCorruptException("repository layout is incomplete or contains unknown entries")
    }

    private fun readPreparedRecords(profileKey: String): List<PreparedRecord> {
        val directory = prepared.resolve(profileKey)
        if (!existsNoFollow(directory)) return emptyList()
        requireDirectory(directory)
        return Files.newDirectoryStream(directory).use { entries ->
            entries.map { decodePrepared(readBoundedRecord(it), profileKey) }.toList()
        }
    }

    private fun readAllPreparedRecords(): Map<String, List<PreparedRecord>> =
        Files.newDirectoryStream(prepared).use { profiles ->
            profiles.associate { directory ->
                val key = directory.fileName.toString()
                key to readPreparedRecords(key)
            }
        }

    private fun readAllFloors(): Map<String, FloorRecord> =
        Files.newDirectoryStream(state, "*$FLOOR_SUFFIX").use { entries ->
            entries.associate { path ->
                val key = path.fileName.toString().removeSuffix(FLOOR_SUFFIX)
                key to decodeFloor(readBoundedRecord(path), key)
            }
        }

    private fun readFloor(profileKey: String): FloorRecord? {
        val path = floorPath(profileKey)
        return if (existsNoFollow(path)) decodeFloor(readBoundedRecord(path), profileKey) else null
    }

    private fun readActivationStateLocked(): ActivationState? {
        val path = activationPath()
        return if (existsNoFollow(path)) decodeActivation(readBoundedRecord(path)) else null
    }

    private fun readPendingActivationLocked(): PendingActivation? =
        if (existsNoFollow(pendingActivationPath)) {
            decodePendingActivation(readBoundedRecord(pendingActivationPath))
        } else {
            null
        }

    private fun clearPendingActivation() {
        if (existsNoFollow(pendingActivationPath)) {
            requireRegularFile(pendingActivationPath, "pending activation record")
            Files.delete(pendingActivationPath)
            forceDirectory(state)
        }
    }

    private fun readBoundedRecord(path: Path): ByteArray {
        requireRegularFile(path, "repository record")
        FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            if (channel.size() !in 1..MAX_RECORD_BYTES.toLong()) {
                throw ProfileRepositoryCorruptException("repository record is outside the byte bound")
            }
            val output = ByteArrayOutputStream(channel.size().toInt())
            Channels.newInputStream(channel).use { input ->
                val buffer = ByteArray(RECORD_BUFFER_BYTES)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_RECORD_BYTES) throw ProfileRepositoryCorruptException("repository record exceeds the byte bound")
                    output.write(buffer, 0, count)
                }
            }
            return output.toByteArray()
        }
    }

    private fun writeImmutableRecord(destination: Path, bytes: ByteArray) {
        if (existsNoFollow(destination)) throw ProfileRepositoryCorruptException("immutable record already exists")
        writeTemporaryRecord(bytes)
        try {
            Files.move(recordTemporary, destination, StandardCopyOption.ATOMIC_MOVE)
            forceDirectory(destination.parent)
        } catch (failure: Throwable) {
            runCatching { Files.deleteIfExists(recordTemporary) }
            throw failure
        }
    }

    private fun writeReplaceRecord(destination: Path, bytes: ByteArray) {
        if (existsNoFollow(destination)) requireRegularFile(destination, "replaceable repository record")
        writeTemporaryRecord(bytes)
        try {
            Files.move(
                recordTemporary,
                destination,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            forceDirectory(destination.parent)
        } catch (failure: Throwable) {
            runCatching { Files.deleteIfExists(recordTemporary) }
            throw failure
        }
    }

    private fun writeTemporaryRecord(bytes: ByteArray) {
        if (bytes.isEmpty() || bytes.size > MAX_RECORD_BYTES) throw ProfileRepositoryCorruptException("record encoding exceeds bound")
        ensureNoPath(recordTemporary)
        val options = setOf<OpenOption>(
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        )
        FileChannel.open(recordTemporary, options).use { channel ->
            val source = ByteBuffer.wrap(bytes)
            writeFully(channel, source, "record file write made no progress")
            channel.force(true)
        }
    }

    private fun encodePrepared(record: PreparedRecord): ByteArray = encodeRecord(PREPARED_MAGIC) { output ->
        output.writeUTF(record.candidate.profileId.value)
        output.writeLong(record.candidate.generation.value)
        output.writeUTF(record.candidate.manifestSha256.value)
        output.writeUTF(record.dataCompatibility.value)
        output.writeInt(record.artifacts.size)
        record.artifacts.sortedBy { it.role.ordinal }.forEach { artifact ->
            output.writeUTF(artifact.role.wireName)
            output.writeUTF(artifact.sha256.value)
            output.writeLong(artifact.sizeBytes.value)
        }
    }

    private fun decodePrepared(bytes: ByteArray, expectedProfileKey: String): PreparedRecord =
        decodeRecord(bytes, PREPARED_MAGIC) { input ->
            val profileId = checkedRecordValue { ProfileId(input.readUTF()) }
            val generation = checkedRecordValue { ProfileGeneration(input.readLong()) }
            val manifest = checkedRecordValue { Sha256Digest(input.readUTF()) }
            val compatibility = checkedRecordValue { DataCompatibilityId(input.readUTF()) }
            val count = input.readInt()
            if (count != ArtifactRole.entries.size) throw ProfileRepositoryCorruptException("prepared artifact count is invalid")
            val artifacts = (0 until count).map {
                val role = ArtifactRole.fromWireName(input.readUTF())
                    ?: throw ProfileRepositoryCorruptException("prepared artifact role is invalid")
                PreparedArtifact(
                    role,
                    checkedRecordValue { Sha256Digest(input.readUTF()) },
                    checkedRecordValue { ArtifactSizeBytes(input.readLong()) },
                )
            }
            if (artifacts.map { it.role }.toSet() != ArtifactRole.entries.toSet()) {
                throw ProfileRepositoryCorruptException("prepared artifact roles are not closed")
            }
            if (profileKey(profileId) != expectedProfileKey) {
                throw ProfileRepositoryCorruptException("prepared record is in the wrong fixed profile path")
            }
            PreparedRecord(PreparedProfileCandidate(profileId, generation, manifest), compatibility, artifacts)
        }

    private fun encodeFloor(record: FloorRecord): ByteArray = encodeRecord(FLOOR_MAGIC) { output ->
        output.writeUTF(record.profileId.value)
        output.writeLong(record.generation.value)
        output.writeUTF(record.manifestSha256.value)
    }

    private fun decodeFloor(bytes: ByteArray, expectedProfileKey: String): FloorRecord =
        decodeRecord(bytes, FLOOR_MAGIC) { input ->
            val record = FloorRecord(
                checkedRecordValue { ProfileId(input.readUTF()) },
                checkedRecordValue { ProfileGeneration(input.readLong()) },
                checkedRecordValue { Sha256Digest(input.readUTF()) },
            )
            if (profileKey(record.profileId) != expectedProfileKey) {
                throw ProfileRepositoryCorruptException("floor record is in the wrong fixed profile path")
            }
            record
        }

    private fun encodeActivation(record: ActivationState): ByteArray = encodeRecord(ACTIVATION_MAGIC) { output ->
        writeActivation(output, record)
    }

    private fun decodeActivation(bytes: ByteArray): ActivationState = decodeRecord(bytes, ACTIVATION_MAGIC, ::readActivation)

    private fun encodePendingActivation(record: PendingActivation): ByteArray =
        encodeRecord(PENDING_ACTIVATION_MAGIC) { output ->
            writeActivation(output, record.desired)
            output.writeUTF(record.storagePathSha256.value)
        }

    private fun decodePendingActivation(bytes: ByteArray): PendingActivation =
        decodeRecord(bytes, PENDING_ACTIVATION_MAGIC) { input ->
            PendingActivation(
                readActivation(input),
                checkedRecordValue { Sha256Digest(input.readUTF()) },
            )
        }

    private fun writeActivation(output: DataOutputStream, record: ActivationState) {
        output.writeLong(record.activationSequence)
        writeCandidate(output, record.active)
        output.writeBoolean(record.rollback != null)
        record.rollback?.let { writeCandidate(output, it) }
    }

    private fun readActivation(input: DataInputStream): ActivationState {
        val sequence = input.readLong()
        if (sequence <= 0) throw ProfileRepositoryCorruptException("activation sequence is invalid")
        val active = readCandidate(input)
        val hasRollback = input.readBoolean()
        val rollback = if (hasRollback) readCandidate(input) else null
        if (rollback == active) throw ProfileRepositoryCorruptException("active and rollback candidates must differ")
        return ActivationState(sequence, active, rollback)
    }

    private fun writeCandidate(output: DataOutputStream, candidate: PreparedProfileCandidate) {
        output.writeUTF(candidate.profileId.value)
        output.writeLong(candidate.generation.value)
        output.writeUTF(candidate.manifestSha256.value)
    }

    private fun readCandidate(input: DataInputStream): PreparedProfileCandidate = PreparedProfileCandidate(
        checkedRecordValue { ProfileId(input.readUTF()) },
        checkedRecordValue { ProfileGeneration(input.readLong()) },
        checkedRecordValue { Sha256Digest(input.readUTF()) },
    )

    private fun encodeRecord(magic: Int, writer: (DataOutputStream) -> Unit): ByteArray {
        val contentBytes = ByteArrayOutputStream()
        DataOutputStream(contentBytes).use { output ->
            output.writeInt(magic)
            output.writeInt(RECORD_VERSION)
            writer(output)
        }
        val content = contentBytes.toByteArray()
        return content + MessageDigest.getInstance("SHA-256").digest(content)
    }

    private fun <T> decodeRecord(bytes: ByteArray, expectedMagic: Int, reader: (DataInputStream) -> T): T = try {
        if (bytes.size <= RECORD_CHECKSUM_BYTES) {
            throw ProfileRepositoryCorruptException("repository record is too short")
        }
        val content = bytes.copyOf(bytes.size - RECORD_CHECKSUM_BYTES)
        val checksum = bytes.copyOfRange(bytes.size - RECORD_CHECKSUM_BYTES, bytes.size)
        if (!MessageDigest.getInstance("SHA-256").digest(content).contentEquals(checksum)) {
            throw ProfileRepositoryCorruptException("repository record checksum mismatch")
        }
        DataInputStream(ByteArrayInputStream(content)).use { input ->
            if (input.readInt() != expectedMagic || input.readInt() != RECORD_VERSION) {
                throw ProfileRepositoryCorruptException("repository record type or version is invalid")
            }
            val value = reader(input)
            if (input.read() != -1) throw ProfileRepositoryCorruptException("repository record has trailing fields")
            value
        }
    } catch (failure: ProfileRepositoryCorruptException) {
        throw failure
    } catch (failure: Exception) {
        throw ProfileRepositoryCorruptException("repository record is malformed", failure)
    }

    private inline fun <T> checkedRecordValue(block: () -> T): T = try {
        block()
    } catch (failure: IllegalArgumentException) {
        throw ProfileRepositoryCorruptException("repository record contains an invalid domain value", failure)
    }

    private fun PreparedRecord.toPublic(): PreparedProfile = PreparedProfile(
        candidate,
        dataCompatibility,
        artifacts.associate { it.role to blobPath(it.sha256).toFile() },
    )

    private fun profileKey(profileId: ProfileId): String = MessageDigest.getInstance("SHA-256")
        .digest(profileId.value.toByteArray(Charsets.UTF_8)).toLowerHex()

    private fun blobPath(digest: Sha256Digest): Path = blobs.resolve("${digest.value}$BLOB_SUFFIX")
    private fun preparedPath(profileKey: String, generation: ProfileGeneration): Path =
        prepared.resolve(profileKey).resolve("${generation.value}$PREPARED_SUFFIX")
    private fun floorPath(profileKey: String): Path = state.resolve("$profileKey$FLOOR_SUFFIX")
    private fun activationPath(): Path = state.resolve(ACTIVATION_FILE)

    private fun <T> withRepositoryLock(block: () -> T): T {
        prepareLayoutForLock()
        val options = setOf<OpenOption>(
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        )
        FileChannel.open(lockPath, options).use { channel ->
            val lock = acquireLock(channel)
            lock.use { return block() }
        }
    }

    private fun acquireLock(channel: FileChannel): FileLock {
        val deadline = deadlineAfterMillis(lockTimeoutMillis)
        while (true) {
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            if (lock != null) return lock
            if (System.nanoTime() - deadline >= 0L) throw ProfileRepositoryException("repository lock deadline expired")
            try {
                Thread.sleep(LOCK_POLL_MILLIS)
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                throw ProfileRepositoryException("repository lock wait was interrupted", failure)
            }
        }
    }

    @Synchronized
    private fun prepareLayoutForLock() {
        if (!existsNoFollow(root)) {
            val parent = root.parent ?: throw ProfileRepositoryCorruptException("repository root has no parent")
            requireDirectory(parent)
            try {
                Files.createDirectory(root)
                forceDirectory(parent)
            } catch (failure: FileAlreadyExistsException) {
                requireDirectory(root)
            }
        }
        requireDirectory(root)
        if (root.toRealPath() != root) throw ProfileRepositoryCorruptException("repository root contains a symbolic path")
        listOf(blobs, prepared, state, temporary).forEach { path ->
            if (!existsNoFollow(path)) createDirectory(path) else requireDirectory(path)
        }
        if (existsNoFollow(lockPath)) requireRegularFile(lockPath, "repository lock")
    }

    private fun createDirectory(path: Path) {
        val parent = path.parent ?: throw ProfileRepositoryCorruptException("repository directory has no parent")
        requireDirectory(parent)
        try {
            Files.createDirectory(path)
            forceDirectory(parent)
        } catch (failure: FileAlreadyExistsException) {
            requireDirectory(path)
        }
        requireDirectory(path)
    }

    private fun countDirectories(path: Path): Int = Files.newDirectoryStream(path).use { stream ->
        stream.count { attributesNoFollow(it).isDirectory }
    }

    private fun ensureNoPath(path: Path) {
        if (existsNoFollow(path)) throw ProfileRepositoryCorruptException("exclusive temporary path already exists")
    }

    private fun requireDirectory(path: Path) {
        val attributes = try {
            attributesNoFollow(path)
        } catch (failure: IOException) {
            throw ProfileRepositoryCorruptException("required repository directory is missing", failure)
        }
        if (attributes.isSymbolicLink || !attributes.isDirectory) {
            throw ProfileRepositoryCorruptException("expected a non-symlink directory at $path")
        }
    }

    private fun requireRegularFile(path: Path, label: String) {
        val attributes = try {
            attributesNoFollow(path)
        } catch (failure: IOException) {
            throw ProfileRepositoryCorruptException("required $label is missing", failure)
        }
        if (attributes.isSymbolicLink || !attributes.isRegularFile) {
            throw ProfileRepositoryCorruptException("expected a non-symlink regular $label at $path")
        }
    }

    private fun attributesNoFollow(path: Path): BasicFileAttributes = Files.readAttributes(
        path,
        BasicFileAttributes::class.java,
        LinkOption.NOFOLLOW_LINKS,
    )

    private fun existsNoFollow(path: Path): Boolean = Files.exists(path, LinkOption.NOFOLLOW_LINKS)

    private fun forceDirectory(path: Path) {
        try {
            FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) }
        } catch (_: IOException) {
            // Android/JVM providers may reject directory channels. File contents are always forced.
        }
    }

    private fun deadlineAfterMillis(timeoutMillis: Long): Long {
        val now = System.nanoTime()
        val duration = timeoutMillis * NANOS_PER_MILLISECOND
        return if (Long.MAX_VALUE - now < duration) Long.MAX_VALUE else now + duration
    }

    private fun writeFully(channel: FileChannel, source: ByteBuffer, zeroProgressMessage: String) {
        var zeroWrites = 0
        while (source.hasRemaining()) {
            if (channel.write(source) == 0) {
                zeroWrites++
                if (zeroWrites > MAX_ZERO_READS) throw ProfileRepositoryException(zeroProgressMessage)
            } else {
                zeroWrites = 0
            }
        }
    }

    private fun nextActivationSequence(current: Long): Long {
        if (current == Long.MAX_VALUE) throw ProfileActivationException("activation sequence is exhausted")
        return current + 1L
    }

    private data class PreparedArtifact(
        val role: ArtifactRole,
        val sha256: Sha256Digest,
        val sizeBytes: ArtifactSizeBytes,
    )

    private data class PreparedRecord(
        val candidate: PreparedProfileCandidate,
        val dataCompatibility: DataCompatibilityId,
        val artifacts: List<PreparedArtifact>,
    )

    private data class FloorRecord(
        val profileId: ProfileId,
        val generation: ProfileGeneration,
        val manifestSha256: Sha256Digest,
    )

    private data class PendingActivation(
        val desired: ActivationState,
        val storagePathSha256: Sha256Digest,
    )

    private companion object {
        const val BLOBS_DIRECTORY = "blobs"
        const val PREPARED_DIRECTORY = "prepared"
        const val STATE_DIRECTORY = "state"
        const val TEMP_DIRECTORY = "tmp"
        const val LOCK_FILE = "repository.lock"
        const val ARTIFACT_TEMP_FILE = "artifact.tmp"
        const val RECORD_TEMP_FILE = "record.tmp"
        const val ACTIVATION_FILE = "activation.record"
        const val PENDING_ACTIVATION_FILE = "activation.pending"
        const val BLOB_SUFFIX = ".blob"
        const val PREPARED_SUFFIX = ".prepared"
        const val FLOOR_SUFFIX = ".floor"
        const val RECORD_VERSION = 1
        const val RECORD_CHECKSUM_BYTES = 32
        const val PREPARED_MAGIC = 0x50525044
        const val FLOOR_MAGIC = 0x5052464c
        const val ACTIVATION_MAGIC = 0x50524143
        const val PENDING_ACTIVATION_MAGIC = 0x5052504e
        const val MAX_RECORD_BYTES = 16 * 1024
        const val RECORD_BUFFER_BYTES = 4 * 1024
        const val STREAM_BUFFER_BYTES = 64 * 1024
        const val MAX_ZERO_READS = 16
        const val MAX_PROFILES = 32
        const val MAX_GENERATIONS_PER_PROFILE = 64
        const val MAX_BLOBS = MAX_PROFILES * MAX_GENERATIONS_PER_PROFILE * 3
        const val MAX_STATE_RECORDS = MAX_PROFILES + 2
        const val DEFAULT_FETCH_TIMEOUT_MILLIS = 5L * 60 * 1000
        const val MAX_FETCH_TIMEOUT_MILLIS = 30L * 60 * 1000
        const val DEFAULT_LOCK_TIMEOUT_MILLIS = 30_000L
        const val MAX_LOCK_TIMEOUT_MILLIS = 5L * 60 * 1000
        const val LOCK_POLL_MILLIS = 10L
        const val NANOS_PER_MILLISECOND = 1_000_000L

        val PROFILE_KEY = Regex("[0-9a-f]{64}")
        val BLOB_NAME = Regex("[0-9a-f]{64}\\.blob")
        val PREPARED_NAME = Regex("[1-9][0-9]{0,18}\\.prepared")
        val FLOOR_NAME = Regex("[0-9a-f]{64}\\.floor")
    }
}

private fun ByteArray.toLowerHex(): String = joinToString("") {
    (it.toInt() and 0xff).toString(16).padStart(2, '0')
}

private fun String.sha256Hex(): String =
    MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8)).toLowerHex()
