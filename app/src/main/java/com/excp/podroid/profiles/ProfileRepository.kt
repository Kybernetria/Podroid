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
class ProfileQuotaExceededException(message: String) : ProfileRepositoryException(message)

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
    val signingKeyId: SigningKeyId,
    val signingKeyFingerprint: Sha256Digest,
    val trustEpoch: TrustEpoch,
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

/** Opaque destructive authority issued only after this repository validates its fixed storage target. */
class DataDeletionConfirmation private constructor(
    internal val owner: Any,
    val expectedActivationSequence: Long,
    val candidate: PreparedProfileCandidate,
    internal val storageIdentity: StorageIdentity,
) {
    internal companion object {
        fun issue(
            owner: Any,
            expectedActivationSequence: Long,
            candidate: PreparedProfileCandidate,
            storageIdentity: StorageIdentity,
        ) = DataDeletionConfirmation(owner, expectedActivationSequence, candidate, storageIdentity)
    }
}

data class ProfileStoreLimits(
    val maxCasBytes: Long = ProfileLimits.MAX_TOTAL_ARTIFACT_BYTES * 2L,
    val maxBlobCount: Int = 6_144,
    val reservedFreeBytes: Long = 64L * 1024 * 1024,
) {
    init {
        require(maxCasBytes in 1..Long.MAX_VALUE / 2) { "CAS byte quota is outside the bound" }
        require(maxBlobCount in ArtifactRole.entries.size..6_144) { "blob count quota is outside the bound" }
        require(reservedFreeBytes in 0..maxCasBytes) { "free-space reserve is outside the bound" }
    }
}

data class ProfileGarbageCollectionResult(val deletedBlobCount: Int, val deletedBytes: Long)

internal data class StorageIdentity(
    val parentFileKey: String,
    val parentCreationTime: String,
    val existed: Boolean,
    val fileKey: String?,
    val sizeBytes: Long?,
    val creationTime: String?,
    val lastModifiedTime: String?,
)

enum class ProfileRepositoryFaultPoint {
    AFTER_DELETION_INTENT,
    AFTER_STORAGE_DELETION,
}

fun interface ProfileRepositoryFaultInjector {
    @Throws(IOException::class)
    fun check(point: ProfileRepositoryFaultPoint)
}

/**
 * One-stream profile store. [storageFile] is the sole authoritative destructive target and is
 * fixed for the repository lifetime; no operation accepts a caller-selected path.
 */
class ProfileRepository(
    repositoryDirectory: File,
    storageFile: File,
    private val approvedOrigins: ApprovedArtifactOrigins,
    private val trustResolver: ProfileTrustResolver,
    private val artifactFetcher: ProfileArtifactFetcher,
    private val verifier: Ed25519Verifier = TinkEd25519Verifier,
    private val directoryDurability: DirectoryDurability = FileChannelDirectoryDurability,
    private val storeLimits: ProfileStoreLimits = ProfileStoreLimits(),
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
    private val fixedStorage = storageFile.toPath().toAbsolutePath().normalize()
    private val storageTombstone = fixedStorage.parent?.resolve(
        ".podroid-profile-delete-${fixedStorage.toString().sha256Hex()}",
    ) ?: throw IllegalArgumentException("fixed storage file has no parent")
    private val confirmationOwner = Any()

    init {
        require(!fixedStorage.startsWith(root)) { "fixed storage file must be outside the repository metadata tree" }
        require(fetchTimeoutMillis in 1..MAX_FETCH_TIMEOUT_MILLIS) { "fetch timeout is outside the bound" }
        require(lockTimeoutMillis in 1..MAX_LOCK_TIMEOUT_MILLIS) { "lock timeout is outside the bound" }
    }

    /** Verifies, streams, fsyncs, CAS-publishes, then atomically records a complete generation. */
    @Throws(IOException::class)
    fun prepare(signedEnvelopeBytes: ByteArray): PreparedProfile {
        val verified = VerifiedProfileJsonCodec.decodeManifest(
            signedEnvelopeBytes,
            approvedOrigins,
            trustResolver,
            verifier,
        )
        return withRepositoryLock {
            recoverLocked()
            if (readPendingActivationLocked() != null) {
                throw ProfileActivationException("prepare is blocked while confirmed data deletion is pending")
            }
            if (verified.trustEpoch != trustResolver.currentTrustEpoch) {
                throw InvalidProfileSignatureException("profile trust policy changed during verification")
            }
            prepareLocked(verified)
        }
    }

    /** Completes bounded recovery, including a previously durable destructive intent. */
    @Throws(IOException::class)
    fun recover() {
        withRepositoryLock {
            recoverLocked()
            readPendingActivationLocked()?.let(::completePendingActivationLocked)
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

    /** Issues destructive authority for the current sequence, candidate, and fixed file identity. */
    @Throws(IOException::class)
    fun issueDataDeletionConfirmation(candidate: PreparedProfileCandidate): DataDeletionConfirmation =
        withRepositoryLock {
            recoverLocked()
            if (readPendingActivationLocked() != null) {
                throw ProfileActivationException("a confirmed data-deletion activation requires recovery")
            }
            requireUsableCandidateLocked(candidate, requireCurrentFloor = true, requireCurrentTrust = true)
            DataDeletionConfirmation.issue(
                confirmationOwner,
                readActivationStateLocked()?.activationSequence ?: 0L,
                candidate,
                captureStorageIdentity(requireStableKeys = true),
            )
        }

    /** Activates a prepared candidate; destructive policy can target only the fixed storage file. */
    @Throws(IOException::class)
    fun activate(
        candidate: PreparedProfileCandidate,
        dataPolicy: GuestDataPolicy,
        deletionConfirmation: DataDeletionConfirmation? = null,
    ): ActivationState = withRepositoryLock {
        recoverLocked()
        readPendingActivationLocked()?.let { pending ->
            if (dataPolicy != GuestDataPolicy.DELETE_DATA || pending.desired.active != candidate) {
                throw ProfileActivationException("a different confirmed data-deletion activation requires recovery")
            }
            completePendingActivationLocked(pending)
            return@withRepositoryLock pending.desired
        }

        val current = readActivationStateLocked()
        if (current?.active == candidate) {
            requireUsableCandidateLocked(candidate, requireCurrentFloor = false, requireCurrentTrust = true)
            captureStorageIdentity(requireStableKeys = false)
            when (dataPolicy) {
                GuestDataPolicy.PRESERVE_DATA -> if (deletionConfirmation != null) {
                    throw ProfileActivationException("deletion confirmation is invalid for PRESERVE_DATA")
                }
                GuestDataPolicy.DELETE_DATA -> if (deletionConfirmation != null &&
                    (deletionConfirmation.owner !== confirmationOwner || deletionConfirmation.candidate != candidate)
                ) {
                    throw ProfileActivationException("deletion confirmation was not issued for this repository and candidate")
                }
            }
            return@withRepositoryLock current
        }

        val preparedRecord = requireUsableCandidateLocked(
            candidate,
            requireCurrentFloor = true,
            requireCurrentTrust = true,
        )
        when (dataPolicy) {
            GuestDataPolicy.PRESERVE_DATA -> {
                if (deletionConfirmation != null) {
                    throw ProfileActivationException("deletion confirmation is invalid for PRESERVE_DATA")
                }
                captureStorageIdentity(requireStableKeys = false)
                current?.let { activation ->
                    val activeRecord = requireUsableCandidateLocked(
                        activation.active,
                        requireCurrentFloor = false,
                        requireCurrentTrust = false,
                    )
                    if (activeRecord.dataCompatibility != preparedRecord.dataCompatibility) {
                        throw ProfileActivationException("PRESERVE_DATA activation requires storage-compatible profiles")
                    }
                }
            }
            GuestDataPolicy.DELETE_DATA -> {
                val expectedSequence = current?.activationSequence ?: 0L
                if (deletionConfirmation?.owner !== confirmationOwner ||
                    deletionConfirmation.expectedActivationSequence != expectedSequence ||
                    deletionConfirmation.candidate != candidate
                ) {
                    throw ProfileActivationException(
                        "DELETE_DATA requires repository-issued confirmation for the current state and candidate",
                    )
                }
                requireStorageIdentity(deletionConfirmation.storageIdentity, allowCompletedDeletion = false)
            }
        }

        val next = ActivationState(nextActivationSequence(current?.activationSequence ?: 0L), candidate, current?.active)
        if (dataPolicy == GuestDataPolicy.DELETE_DATA) {
            val pending = PendingActivation(next, deletionConfirmation!!.storageIdentity)
            writeImmutableRecord(pendingActivationPath, encodePendingActivation(pending))
            faultInjector.check(ProfileRepositoryFaultPoint.AFTER_DELETION_INTENT)
            deleteFixedStorage(pending.storageIdentity)
            faultInjector.check(ProfileRepositoryFaultPoint.AFTER_STORAGE_DELETION)
        }
        writeReplaceRecord(activationPath(), encodeActivation(next))
        if (dataPolicy == GuestDataPolicy.DELETE_DATA) clearPendingActivation()
        val published = readActivationStateLocked()
            ?: throw ProfileRepositoryCorruptException("published activation record is missing")
        if (published != next || preparedRecord.candidate != candidate) {
            throw ProfileRepositoryCorruptException("published activation record changed unexpectedly")
        }
        published
    }

    /** Rollback is local-only, sequence-bound, preserve-only, trust-checked, and compatibility-gated. */
    @Throws(IOException::class)
    fun rollback(
        expectedActivationSequence: Long,
        dataPolicy: GuestDataPolicy,
    ): ActivationState = withRepositoryLock {
        recoverLocked()
        if (readPendingActivationLocked() != null) {
            throw ProfileActivationException("a confirmed data-deletion activation requires recovery")
        }
        if (dataPolicy != GuestDataPolicy.PRESERVE_DATA) {
            throw ProfileActivationException("rollback requires explicit PRESERVE_DATA")
        }
        captureStorageIdentity(requireStableKeys = false)
        val current = readActivationStateLocked()
            ?: throw ProfileActivationException("there is no active profile to roll back")
        if (current.activationSequence != expectedActivationSequence) {
            throw ProfileActivationException("rollback confirmation does not match the current activation sequence")
        }
        val rollbackCandidate = current.rollback
            ?: throw ProfileActivationException("there is no retained local rollback profile")
        val activeRecord = requireUsableCandidateLocked(
            current.active,
            requireCurrentFloor = false,
            requireCurrentTrust = true,
        )
        val rollbackRecord = requireUsableCandidateLocked(
            rollbackCandidate,
            requireCurrentFloor = false,
            requireCurrentTrust = true,
        )
        if (activeRecord.dataCompatibility != rollbackRecord.dataCompatibility) {
            throw ProfileActivationException("rollback profile is not storage-compatible with the active profile")
        }

        val next = ActivationState(nextActivationSequence(current.activationSequence), rollbackCandidate, current.active)
        writeReplaceRecord(activationPath(), encodeActivation(next))
        readActivationStateLocked()?.also {
            if (it != next) throw ProfileRepositoryCorruptException("published rollback record changed unexpectedly")
        } ?: throw ProfileRepositoryCorruptException("published rollback record is missing")
    }

    /** Deletes only bounded, unreferenced CAS blobs. Prepared and lifecycle references are preserved. */
    @Throws(IOException::class)
    fun collectGarbage(): ProfileGarbageCollectionResult = withRepositoryLock {
        recoverLocked()
        collectGarbageLocked()
    }

    private fun prepareLocked(verified: VerifiedProfileManifest): PreparedProfile {
        val profile = verified.profile
        val profileKey = profileKey(profile.id)
        val currentEpoch = trustResolver.currentTrustEpoch
        revalidateVerifiedTrust(verified)

        val floor = readFloor(profileKey)
        if (floor != null && floor.candidate.trustEpoch.value > currentEpoch.value) {
            throw ProfileRepositoryCorruptException("generation floor is from a future trust epoch")
        }
        val preparedRecords = readPreparedRecords(profileKey)
        val epochRecords = preparedRecords.filter { it.candidate.trustEpoch == currentEpoch }
        val effectiveFloor = if (floor?.candidate?.trustEpoch == currentEpoch) {
            val retained = epochRecords.singleOrNull { it.candidate == floor.candidate }
                ?: throw ProfileRepositoryCorruptException("generation floor has no matching immutable prepared record")
            val highest = epochRecords.maxByOrNull { it.candidate.generation.value } ?: retained
            if (highest.candidate.generation.value < floor.candidate.generation.value) {
                throw ProfileRepositoryCorruptException("generation floor is above retained immutable state")
            }
            highest
        } else {
            epochRecords.maxByOrNull { it.candidate.generation.value }
        }

        if (effectiveFloor != null) {
            when {
                profile.generation.value < effectiveFloor.candidate.generation.value ->
                    throw ProfileGenerationRollbackException("profile generation is below the current trust-epoch floor")
                profile.generation == effectiveFloor.candidate.generation &&
                    verified.manifestSha256 != effectiveFloor.candidate.manifestSha256 ->
                    throw ProfileGenerationEquivocationException("equal profile generation has a different signed manifest")
            }
        }

        val candidate = PreparedProfileCandidate(
            profile.id,
            profile.generation,
            verified.manifestSha256,
            verified.signingKeyId,
            verified.signingKeyFingerprint,
            verified.trustEpoch,
        )
        val expectedRecord = PreparedRecord(
            candidate,
            profile.dataCompatibility,
            profile.artifacts.sortedBy { it.role.ordinal }.map {
                PreparedArtifact(it.role, it.sha256, it.sizeBytes)
            },
        )
        val existing = epochRecords.singleOrNull { it.candidate.generation == profile.generation }
        if (existing != null) {
            if (existing != expectedRecord) {
                throw ProfileGenerationEquivocationException("immutable generation record conflicts with the signed manifest")
            }
            validatePreparedBlobs(existing)
            revalidateVerifiedTrust(verified)
            publishFloorIfNeeded(profileKey, existing)
            return existing.toPublic()
        }

        if (preparedRecords.size >= MAX_GENERATIONS_PER_PROFILE) {
            throw ProfileRepositoryException("prepared generation retention bound reached")
        }
        ensureCasCapacity(profile.artifacts)
        val newlyPublished = linkedSetOf<Path>()
        try {
            profile.artifacts.sortedBy { it.role.ordinal }.forEach { ensureBlob(it, newlyPublished) }
            revalidateVerifiedTrust(verified)
            publishPrepared(profileKey, expectedRecord)
            validatePreparedBlobs(expectedRecord)
            publishFloorIfNeeded(profileKey, expectedRecord)
            return expectedRecord.toPublic()
        } catch (failure: Throwable) {
            cleanupNewUnreferencedBlobs(newlyPublished, failure)
            throw failure
        }
    }

    private fun revalidateVerifiedTrust(verified: VerifiedProfileManifest) {
        if (verified.trustEpoch != trustResolver.currentTrustEpoch) {
            throw InvalidProfileSignatureException("profile trust policy changed during preparation")
        }
        val resolved = trustResolver.resolve(verified.signingKeyId)
            ?: throw InvalidProfileSignatureException("profile signing key was revoked during preparation")
        if (resolved.publicKey.fingerprint != verified.signingKeyFingerprint) {
            throw InvalidProfileSignatureException("profile signing key changed during preparation")
        }
    }

    private fun ensureBlob(artifact: ProfileArtifact, newlyPublished: MutableSet<Path>) {
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
                newlyPublished.add(destination)
                forceDirectory(blobs)
                validateBlob(destination, artifact.sha256, artifact.sizeBytes)
            }
            created = false
        } catch (failure: Throwable) {
            if (created) deleteTemporaryAfterFailure(artifactTemporary, failure)
            if (failure is IOException) throw failure
            throw ProfileDownloadException("artifact download failed", failure)
        }
    }

    private fun ensureCasCapacity(artifacts: List<ProfileArtifact>) {
        collectGarbageLocked()
        val uniqueIncoming = linkedMapOf<Sha256Digest, ArtifactSizeBytes>()
        artifacts.forEach { artifact ->
            val previous = uniqueIncoming.putIfAbsent(artifact.sha256, artifact.sizeBytes)
            if (previous != null && previous != artifact.sizeBytes) {
                throw ProfileDownloadException("one artifact digest has conflicting signed sizes")
            }
        }
        val usage = casUsage()
        val missing = uniqueIncoming.filterKeys { !existsNoFollow(blobPath(it)) }
        val incomingBytes = missing.values.fold(0L) { total, size -> checkedAdd(total, size.value, "incoming CAS bytes") }
        if (usage.first + missing.size > storeLimits.maxBlobCount) {
            throw ProfileQuotaExceededException("content-addressed blob count quota would be exceeded")
        }
        if (incomingBytes > storeLimits.maxCasBytes - usage.second) {
            throw ProfileQuotaExceededException("content-addressed byte quota would be exceeded")
        }
        val requiredFree = checkedAdd(incomingBytes, storeLimits.reservedFreeBytes, "CAS free-space reservation")
        val usable = try {
            Files.getFileStore(blobs).usableSpace
        } catch (failure: IOException) {
            throw ProfileQuotaExceededException("content-addressed free space cannot be established: ${failure.message}")
        }
        if (usable < requiredFree) {
            throw ProfileQuotaExceededException("content-addressed free-space reserve would be violated")
        }
    }

    private fun casUsage(): Pair<Int, Long> {
        var count = 0
        var bytes = 0L
        Files.newDirectoryStream(blobs).use { entries ->
            for (entry in entries) {
                requireRegularFile(entry, "content-addressed blob")
                count++
                if (count > storeLimits.maxBlobCount) {
                    throw ProfileRepositoryCorruptException("blob count exceeds the configured quota")
                }
                bytes = checkedAdd(bytes, attributesNoFollow(entry).size(), "CAS usage")
                if (bytes > storeLimits.maxCasBytes) {
                    throw ProfileRepositoryCorruptException("CAS bytes exceed the configured quota")
                }
            }
        }
        return count to bytes
    }

    private fun collectGarbageLocked(): ProfileGarbageCollectionResult {
        val referenced = readAllPreparedRecords().values.flatten()
            .flatMap { record -> record.artifacts.map { it.sha256 } }
            .toSet()
        var deletedCount = 0
        var deletedBytes = 0L
        Files.newDirectoryStream(blobs).use { entries ->
            for (entry in entries) {
                val name = entry.fileName.toString()
                if (!BLOB_NAME.matches(name)) throw ProfileRepositoryCorruptException("unknown blob entry")
                val digest = checkedRecordValue { Sha256Digest(name.removeSuffix(BLOB_SUFFIX)) }
                if (digest !in referenced) {
                    requireRegularFile(entry, "unreferenced content-addressed blob")
                    deletedBytes = checkedAdd(deletedBytes, attributesNoFollow(entry).size(), "garbage bytes")
                    Files.delete(entry)
                    deletedCount++
                }
            }
        }
        if (deletedCount > 0) forceDirectory(blobs)
        return ProfileGarbageCollectionResult(deletedCount, deletedBytes)
    }

    private fun cleanupNewUnreferencedBlobs(newlyPublished: Set<Path>, primary: Throwable) {
        if (newlyPublished.isEmpty()) return
        try {
            val referenced = readAllPreparedRecords().values.flatten()
                .flatMap { record -> record.artifacts.map { it.sha256 } }
                .toSet()
            var deleted = false
            newlyPublished.forEach { path ->
                val digest = Sha256Digest(path.fileName.toString().removeSuffix(BLOB_SUFFIX))
                if (digest !in referenced && existsNoFollow(path)) {
                    requireRegularFile(path, "newly published content-addressed blob")
                    Files.delete(path)
                    deleted = true
                }
            }
            if (deleted) forceDirectory(blobs)
        } catch (cleanupFailure: Throwable) {
            primary.addSuppressed(cleanupFailure)
        }
    }

    private fun checkedAdd(left: Long, right: Long, label: String): Long {
        if (right < 0 || left > Long.MAX_VALUE - right) {
            throw ProfileRepositoryCorruptException("$label overflow")
        }
        return left + right
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
        val destination = preparedPath(profileKey, record.candidate.trustEpoch, record.candidate.generation)
        if (existsNoFollow(destination)) {
            val existing = decodePrepared(readBoundedRecord(destination), profileKey, destination.fileName.toString())
            if (existing != record) throw ProfileGenerationEquivocationException("prepared generation is immutable")
            return
        }
        writeImmutableRecord(destination, encodePrepared(record))
    }

    private fun publishFloorIfNeeded(profileKey: String, record: PreparedRecord) {
        val current = readFloor(profileKey)
        val candidate = record.candidate
        when {
            current == null || current.candidate.trustEpoch.value < candidate.trustEpoch.value ->
                writeReplaceRecord(floorPath(profileKey), encodeFloor(FloorRecord(candidate)))
            current.candidate.trustEpoch.value > candidate.trustEpoch.value ->
                throw ProfileGenerationRollbackException("trust epoch cannot be lowered")
            current.candidate.generation.value < candidate.generation.value ->
                writeReplaceRecord(floorPath(profileKey), encodeFloor(FloorRecord(candidate)))
            current.candidate == candidate -> Unit
            current.candidate.generation == candidate.generation ->
                throw ProfileGenerationEquivocationException("generation floor records a different signed candidate")
            else -> throw ProfileGenerationRollbackException("generation floor cannot be lowered within a trust epoch")
        }
    }

    private fun requireUsableCandidateLocked(
        candidate: PreparedProfileCandidate,
        requireCurrentFloor: Boolean,
        requireCurrentTrust: Boolean,
    ): PreparedRecord {
        val profileKey = profileKey(candidate.profileId)
        val path = preparedPath(profileKey, candidate.trustEpoch, candidate.generation)
        if (!existsNoFollow(path)) throw ProfileActivationException("candidate is not retained locally")
        val record = decodePrepared(readBoundedRecord(path), profileKey, path.fileName.toString())
        if (record.candidate != candidate) throw ProfileActivationException("candidate does not match its immutable prepared record")
        if (requireCurrentFloor) {
            val floor = readFloor(profileKey)
                ?: throw ProfileRepositoryCorruptException("candidate profile has no generation floor")
            if (floor.candidate != candidate) {
                throw ProfileActivationException("normal activation requires the current trust-epoch generation floor")
            }
        }
        if (requireCurrentTrust) revalidateTrust(record)
        validatePreparedBlobs(record)
        return record
    }

    private fun revalidateTrust(record: PreparedRecord) {
        val candidate = record.candidate
        if (candidate.trustEpoch != trustResolver.currentTrustEpoch) {
            throw ProfileActivationException("prepared candidate trust epoch is no longer current")
        }
        val resolved = trustResolver.resolve(candidate.signingKeyId)
            ?: throw ProfileActivationException("prepared candidate signing key is no longer trusted")
        if (resolved.publicKey.fingerprint != candidate.signingKeyFingerprint) {
            throw ProfileActivationException("prepared candidate signing key fingerprint changed")
        }
    }

    private fun validatePreparedBlobs(record: PreparedRecord) {
        record.artifacts.forEach { validateBlob(blobPath(it.sha256), it.sha256, it.sizeBytes) }
    }

    private fun captureStorageIdentity(requireStableKeys: Boolean): StorageIdentity {
        val parent = fixedStorage.parent ?: throw ProfileActivationException("fixed storage file has no parent")
        requireDirectory(parent)
        if (parent.toRealPath() != parent) {
            throw ProfileActivationException("fixed storage parent contains a symbolic path")
        }
        val parentAttributes = attributesNoFollow(parent)
        val parentKey = parentAttributes.fileKey()?.toString()
        if (requireStableKeys && parentKey.isNullOrBlank()) {
            throw ProfileActivationException("fixed storage parent identity is unavailable")
        }
        val parentCreationTime = parentAttributes.creationTime().toString()
        if (!existsNoFollow(fixedStorage)) {
            return StorageIdentity(parentKey.orEmpty(), parentCreationTime, false, null, null, null, null)
        }
        requireRegularFile(fixedStorage, "fixed storage file")
        val attributes = attributesNoFollow(fixedStorage)
        val fileKey = attributes.fileKey()?.toString()
        if (requireStableKeys && fileKey.isNullOrBlank()) {
            throw ProfileActivationException("fixed storage file identity is unavailable")
        }
        return StorageIdentity(
            parentKey.orEmpty(),
            parentCreationTime,
            true,
            fileKey,
            attributes.size(),
            attributes.creationTime().toString(),
            attributes.lastModifiedTime().toString(),
        )
    }

    private fun requireStorageIdentity(expected: StorageIdentity, allowCompletedDeletion: Boolean) {
        val actual = captureStorageIdentity(requireStableKeys = true)
        if (actual.parentFileKey != expected.parentFileKey ||
            actual.parentCreationTime != expected.parentCreationTime
        ) {
            throw ProfileActivationException("fixed storage parent was replaced")
        }
        if (actual == expected) {
            if (existsNoFollow(storageTombstone)) {
                throw ProfileActivationException("fixed storage deletion tombstone is unexpectedly occupied")
            }
            return
        }
        if (expected.existed && !actual.existed && existsNoFollow(storageTombstone)) {
            requireRegularFile(storageTombstone, "fixed storage deletion tombstone")
            val tombstoneAttributes = attributesNoFollow(storageTombstone)
            if (tombstoneAttributes.matches(expected)) return
            throw ProfileActivationException("fixed storage deletion tombstone has a different identity")
        }
        if (expected.existed && !actual.existed && allowCompletedDeletion && !existsNoFollow(storageTombstone)) return
        if (!expected.existed && !actual.existed && !existsNoFollow(storageTombstone)) return
        throw ProfileActivationException("fixed storage file was replaced after confirmation")
    }

    private fun deleteFixedStorage(expected: StorageIdentity) {
        requireStorageIdentity(expected, allowCompletedDeletion = true)
        if (existsNoFollow(fixedStorage)) {
            Files.move(fixedStorage, storageTombstone, StandardCopyOption.ATOMIC_MOVE)
            forceDirectory(fixedStorage.parent)
            val movedIdentityMatches = try {
                requireRegularFile(storageTombstone, "fixed storage deletion tombstone")
                attributesNoFollow(storageTombstone).matches(expected)
            } catch (failure: Throwable) {
                restoreStorageTombstone(failure)
                throw failure
            }
            if (!movedIdentityMatches) {
                val failure = ProfileActivationException("fixed storage was replaced during deletion")
                restoreStorageTombstone(failure)
                throw failure
            }
        }
        if (existsNoFollow(storageTombstone)) {
            requireRegularFile(storageTombstone, "fixed storage deletion tombstone")
            if (!attributesNoFollow(storageTombstone).matches(expected)) {
                throw ProfileActivationException("fixed storage deletion tombstone was replaced")
            }
            Files.delete(storageTombstone)
            forceDirectory(fixedStorage.parent)
        }
    }

    private fun BasicFileAttributes.matches(expected: StorageIdentity): Boolean =
        fileKey()?.toString() == expected.fileKey &&
            size() == expected.sizeBytes &&
            creationTime().toString() == expected.creationTime &&
            lastModifiedTime().toString() == expected.lastModifiedTime

    private fun restoreStorageTombstone(primary: Throwable) {
        try {
            if (!existsNoFollow(fixedStorage) && existsNoFollow(storageTombstone)) {
                Files.move(storageTombstone, fixedStorage, StandardCopyOption.ATOMIC_MOVE)
                forceDirectory(fixedStorage.parent)
            }
        } catch (restoreFailure: Throwable) {
            primary.addSuppressed(restoreFailure)
        }
    }

    private fun completePendingActivationLocked(pending: PendingActivation) {
        val activation = readActivationStateLocked()
        validatePendingSequence(pending, activation)
        requireUsableCandidateLocked(
            pending.desired.active,
            requireCurrentFloor = true,
            requireCurrentTrust = true,
        )
        pending.desired.rollback?.let {
            requireUsableCandidateLocked(it, requireCurrentFloor = false, requireCurrentTrust = false)
        }
        requireStorageIdentity(pending.storageIdentity, allowCompletedDeletion = true)
        if (activation != pending.desired) {
            deleteFixedStorage(pending.storageIdentity)
            faultInjector.check(ProfileRepositoryFaultPoint.AFTER_STORAGE_DELETION)
            writeReplaceRecord(activationPath(), encodeActivation(pending.desired))
        }
        clearPendingActivation()
    }

    private fun validatePendingSequence(pending: PendingActivation, activation: ActivationState?) {
        when {
            activation == pending.desired -> Unit
            activation == null && pending.desired.activationSequence != 1L ->
                throw ProfileRepositoryCorruptException("pending activation sequence has no predecessor")
            activation != null &&
                (pending.desired.activationSequence <= activation.activationSequence ||
                    pending.desired.activationSequence - activation.activationSequence != 1L ||
                    pending.desired.rollback != activation.active) ->
                throw ProfileRepositoryCorruptException("pending activation does not follow current state")
        }
    }

    private fun recoverLocked() {
        validateTreeAndCleanTemps()
        val preparedByKey = readAllPreparedRecords()
        val floorsByKey = readAllFloors()
        val currentEpoch = trustResolver.currentTrustEpoch
        for ((profileKey, records) in preparedByKey) {
            if (records.any { it.candidate.trustEpoch.value > currentEpoch.value }) {
                throw ProfileRepositoryCorruptException("prepared state is from a future trust epoch")
            }
            val floor = floorsByKey[profileKey]
            if (floor != null && floor.candidate.trustEpoch.value > currentEpoch.value) {
                throw ProfileRepositoryCorruptException("generation floor is from a future trust epoch")
            }
            val currentRecords = records.filter { it.candidate.trustEpoch == currentEpoch }
            val highest = currentRecords.maxByOrNull { it.candidate.generation.value }
            when {
                highest == null -> Unit
                floor == null || floor.candidate.trustEpoch.value < currentEpoch.value -> publishFloorIfNeeded(profileKey, highest)
                floor.candidate.trustEpoch == currentEpoch && floor.candidate != highest.candidate -> {
                    if (floor.candidate.generation.value > highest.candidate.generation.value) {
                        throw ProfileRepositoryCorruptException("generation floor is above retained immutable state")
                    }
                    publishFloorIfNeeded(profileKey, highest)
                }
            }
        }
        val orphanFloors = floorsByKey.keys - preparedByKey.keys
        if (orphanFloors.isNotEmpty()) throw ProfileRepositoryCorruptException("generation floor has no retained immutable state")
        val activation = readActivationStateLocked()
        val pendingActivation = readPendingActivationLocked()
        pendingActivation?.let { validatePendingSequence(it, activation) }
        val referencedCandidates = buildList {
            activation?.let { add(it.active); it.rollback?.let(::add) }
            pendingActivation?.desired?.let { add(it.active); it.rollback?.let(::add) }
        }
        referencedCandidates.distinct().forEach { candidate ->
            val key = profileKey(candidate.profileId)
            val path = preparedPath(key, candidate.trustEpoch, candidate.generation)
            if (!existsNoFollow(path)) throw ProfileRepositoryCorruptException("activation references missing local state")
            val record = decodePrepared(readBoundedRecord(path), key, path.fileName.toString())
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
        var casBytes = 0L
        Files.newDirectoryStream(blobs).use { entries ->
            for (entry in entries) {
                blobCount++
                if (blobCount > storeLimits.maxBlobCount) {
                    throw ProfileRepositoryCorruptException("blob entry quota exceeded")
                }
                if (!BLOB_NAME.matches(entry.fileName.toString())) throw ProfileRepositoryCorruptException("unknown blob entry")
                requireRegularFile(entry, "content-addressed blob")
                val size = attributesNoFollow(entry).size()
                if (size > ProfileLimits.MAX_ARTIFACT_BYTES) {
                    throw ProfileRepositoryCorruptException("blob exceeds the repository byte bound")
                }
                casBytes = checkedAdd(casBytes, size, "CAS tree bytes")
                if (casBytes > storeLimits.maxCasBytes) {
                    throw ProfileRepositoryCorruptException("CAS byte quota exceeded")
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
            entries.map { decodePrepared(readBoundedRecord(it), profileKey, it.fileName.toString()) }.toList()
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
            deleteTemporaryAfterFailure(recordTemporary, failure)
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
            deleteTemporaryAfterFailure(recordTemporary, failure)
            throw failure
        }
    }

    private fun deleteTemporaryAfterFailure(path: Path, primary: Throwable) {
        try {
            if (Files.deleteIfExists(path)) forceDirectory(path.parent)
        } catch (cleanupFailure: Throwable) {
            primary.addSuppressed(cleanupFailure)
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
        writeCandidate(output, record.candidate)
        output.writeUTF(record.dataCompatibility.value)
        output.writeInt(record.artifacts.size)
        record.artifacts.sortedBy { it.role.ordinal }.forEach { artifact ->
            output.writeUTF(artifact.role.wireName)
            output.writeUTF(artifact.sha256.value)
            output.writeLong(artifact.sizeBytes.value)
        }
    }

    private fun decodePrepared(
        bytes: ByteArray,
        expectedProfileKey: String,
        expectedFileName: String,
    ): PreparedRecord =
        decodeRecord(bytes, PREPARED_MAGIC) { input ->
            val candidate = readCandidate(input)
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
            if (profileKey(candidate.profileId) != expectedProfileKey ||
                expectedFileName != "${candidate.trustEpoch.value}-${candidate.generation.value}$PREPARED_SUFFIX"
            ) {
                throw ProfileRepositoryCorruptException("prepared record is in the wrong fixed candidate path")
            }
            PreparedRecord(candidate, compatibility, artifacts)
        }

    private fun encodeFloor(record: FloorRecord): ByteArray = encodeRecord(FLOOR_MAGIC) { output ->
        writeCandidate(output, record.candidate)
    }

    private fun decodeFloor(bytes: ByteArray, expectedProfileKey: String): FloorRecord =
        decodeRecord(bytes, FLOOR_MAGIC) { input ->
            val record = FloorRecord(readCandidate(input))
            if (profileKey(record.candidate.profileId) != expectedProfileKey) {
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
            output.writeUTF(record.storageIdentity.parentFileKey)
            output.writeUTF(record.storageIdentity.parentCreationTime)
            output.writeBoolean(record.storageIdentity.existed)
            if (record.storageIdentity.existed) {
                output.writeUTF(record.storageIdentity.fileKey!!)
                output.writeLong(record.storageIdentity.sizeBytes!!)
                output.writeUTF(record.storageIdentity.creationTime!!)
                output.writeUTF(record.storageIdentity.lastModifiedTime!!)
            }
        }

    private fun decodePendingActivation(bytes: ByteArray): PendingActivation =
        decodeRecord(bytes, PENDING_ACTIVATION_MAGIC) { input ->
            val desired = readActivation(input)
            val parentKey = checkedFileKey(input.readUTF())
            val parentCreationTime = checkedFileTimestamp(input.readUTF())
            val existed = input.readBoolean()
            val fileKey = if (existed) checkedFileKey(input.readUTF()) else null
            val sizeBytes = if (existed) input.readLong().also {
                if (it < 0) throw ProfileRepositoryCorruptException("stored file size is invalid")
            } else null
            val creationTime = if (existed) checkedFileTimestamp(input.readUTF()) else null
            val lastModifiedTime = if (existed) checkedFileTimestamp(input.readUTF()) else null
            PendingActivation(
                desired,
                StorageIdentity(
                    parentKey,
                    parentCreationTime,
                    existed,
                    fileKey,
                    sizeBytes,
                    creationTime,
                    lastModifiedTime,
                ),
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
        output.writeUTF(candidate.signingKeyId.value)
        output.writeUTF(candidate.signingKeyFingerprint.value)
        output.writeLong(candidate.trustEpoch.value)
    }

    private fun readCandidate(input: DataInputStream): PreparedProfileCandidate = PreparedProfileCandidate(
        checkedRecordValue { ProfileId(input.readUTF()) },
        checkedRecordValue { ProfileGeneration(input.readLong()) },
        checkedRecordValue { Sha256Digest(input.readUTF()) },
        checkedRecordValue { SigningKeyId(input.readUTF()) },
        checkedRecordValue { Sha256Digest(input.readUTF()) },
        checkedRecordValue { TrustEpoch(input.readLong()) },
    )

    private fun checkedFileKey(value: String): String {
        if (value.isBlank() || value.length > MAX_FILE_KEY_CHARS) {
            throw ProfileRepositoryCorruptException("stored file identity is invalid")
        }
        return value
    }

    private fun checkedFileTimestamp(value: String): String {
        if (value.isBlank() || value.length > MAX_FILE_TIMESTAMP_CHARS) {
            throw ProfileRepositoryCorruptException("stored file timestamp is invalid")
        }
        return value
    }

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
    private fun preparedPath(profileKey: String, epoch: TrustEpoch, generation: ProfileGeneration): Path =
        prepared.resolve(profileKey).resolve("${epoch.value}-${generation.value}$PREPARED_SUFFIX")
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
            directoryDurability.force(path)
        } catch (failure: IOException) {
            throw ProfileRepositoryException("directory durability failed for $path", failure)
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

    private data class FloorRecord(val candidate: PreparedProfileCandidate)

    private data class PendingActivation(
        val desired: ActivationState,
        val storageIdentity: StorageIdentity,
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
        const val RECORD_VERSION = 2
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
        const val MAX_STATE_RECORDS = MAX_PROFILES + 2
        const val MAX_FILE_KEY_CHARS = 512
        const val MAX_FILE_TIMESTAMP_CHARS = 64
        const val DEFAULT_FETCH_TIMEOUT_MILLIS = 5L * 60 * 1000
        const val MAX_FETCH_TIMEOUT_MILLIS = 30L * 60 * 1000
        const val DEFAULT_LOCK_TIMEOUT_MILLIS = 30_000L
        const val MAX_LOCK_TIMEOUT_MILLIS = 5L * 60 * 1000
        const val LOCK_POLL_MILLIS = 10L
        const val NANOS_PER_MILLISECOND = 1_000_000L

        val PROFILE_KEY = Regex("[0-9a-f]{64}")
        val BLOB_NAME = Regex("[0-9a-f]{64}\\.blob")
        val PREPARED_NAME = Regex("[1-9][0-9]{0,18}-[1-9][0-9]{0,18}\\.prepared")
        val FLOOR_NAME = Regex("[0-9a-f]{64}\\.floor")
    }
}

private fun ByteArray.toLowerHex(): String = joinToString("") {
    (it.toInt() and 0xff).toString(16).padStart(2, '0')
}

private fun String.sha256Hex(): String =
    MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8)).toLowerHex()
