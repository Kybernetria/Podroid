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

sealed interface PreparedProfilePlan {
    val supportedBackends: Set<ProfileBackend>

    class DirectKernelOverlayV1 internal constructor(
        supportedBackends: Set<ProfileBackend>,
        artifactFiles: Map<ArtifactRole, File>,
        artifactDigests: Map<ArtifactRole, Sha256Digest>,
    ) : PreparedProfilePlan {
        override val supportedBackends: Set<ProfileBackend> = Collections.unmodifiableSet(supportedBackends.toSet())
        val artifactFiles: Map<ArtifactRole, File> = Collections.unmodifiableMap(artifactFiles.toMap())
        val artifactDigests: Map<ArtifactRole, Sha256Digest> = Collections.unmodifiableMap(artifactDigests.toMap())
    }

    class UefiNoCloudV1 internal constructor(
        supportedBackends: Set<ProfileBackend>,
        artifactFiles: Map<ProfileV2ArtifactRole, File>,
        artifactDigests: Map<ProfileV2ArtifactRole, Sha256Digest>,
        val capabilities: ProfileV2Capabilities,
        val readinessMarker: String,
        internal val fixedStorageFile: File,
        internal val fixedVarsFile: File,
    ) : PreparedProfilePlan {
        override val supportedBackends: Set<ProfileBackend> = Collections.unmodifiableSet(supportedBackends.toSet())
        val artifactFiles: Map<ProfileV2ArtifactRole, File> = Collections.unmodifiableMap(artifactFiles.toMap())
        val artifactDigests: Map<ProfileV2ArtifactRole, Sha256Digest> = Collections.unmodifiableMap(artifactDigests.toMap())
    }
}

class PreparedProfile internal constructor(
    val candidate: PreparedProfileCandidate,
    val dataCompatibility: DataCompatibilityId,
    val plan: PreparedProfilePlan,
) {
    internal constructor(
        candidate: PreparedProfileCandidate,
        dataCompatibility: DataCompatibilityId,
        supportedBackends: Set<ProfileBackend>,
        artifactFiles: Map<ArtifactRole, File>,
        artifactDigests: Map<ArtifactRole, Sha256Digest>,
    ) : this(
        candidate,
        dataCompatibility,
        PreparedProfilePlan.DirectKernelOverlayV1(supportedBackends, artifactFiles, artifactDigests),
    )
    val supportedBackends: Set<ProfileBackend> get() = plan.supportedBackends
    /** V1 compatibility view; cloud callers use the sealed [plan]. */
    val artifactFiles: Map<ArtifactRole, File>
        get() = (plan as? PreparedProfilePlan.DirectKernelOverlayV1)?.artifactFiles.orEmpty()
    val artifactDigests: Map<ArtifactRole, Sha256Digest>
        get() = (plan as? PreparedProfilePlan.DirectKernelOverlayV1)?.artifactDigests.orEmpty()

    init {
        require(supportedBackends.isNotEmpty()) { "prepared profile backend contract is empty" }
    }
}

data class ActivationState(
    val activationSequence: Long,
    val active: PreparedProfileCandidate,
    val rollback: PreparedProfileCandidate?,
)

enum class QuarantinedCandidateRole {
    ACTIVE,
    ROLLBACK,
}

enum class TrustQuarantineReason {
    TRUST_EPOCH_OBSOLETE,
    SIGNING_KEY_NOT_TRUSTED,
    SIGNING_KEY_CHANGED,
    NOT_SELECTED_AS_AUTOMATIC_FALLBACK,
}

data class QuarantinedProfileCandidate(
    val role: QuarantinedCandidateRole,
    val candidate: PreparedProfileCandidate,
    val reason: TrustQuarantineReason,
)

class TrustQuarantineState internal constructor(
    val activationSequence: Long,
    candidates: List<QuarantinedProfileCandidate>,
) {
    val candidates: List<QuarantinedProfileCandidate> = Collections.unmodifiableList(candidates.toList())
}

enum class ActivationFailureReason {
    CANDIDATE_NO_LONGER_USABLE,
    VM_DATA_REMOVED,
}

data class ActivationFailureState(
    val attemptedActivationSequence: Long,
    val candidate: PreparedProfileCandidate,
    val reason: ActivationFailureReason,
    val storageDeletionIrreversible: Boolean,
    val uefiVarsDeletionIrreversible: Boolean = false,
)

/** Opaque destructive authority issued only after this repository validates its fixed storage target. */
class DataDeletionConfirmation private constructor(
    internal val owner: Any,
    val expectedActivationSequence: Long,
    val candidate: PreparedProfileCandidate,
    internal val storageIdentity: StorageIdentity,
    internal val varsIdentity: StorageIdentity,
) {
    internal companion object {
        fun issue(
            owner: Any,
            expectedActivationSequence: Long,
            candidate: PreparedProfileCandidate,
            storageIdentity: StorageIdentity,
            varsIdentity: StorageIdentity = storageIdentity,
        ) = DataDeletionConfirmation(owner, expectedActivationSequence, candidate, storageIdentity, varsIdentity)
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
    /** Legacy direct-storage reset checkpoint. */
    AFTER_STORAGE_DELETION,
    AFTER_CLOUD_STORAGE_PUBLISHED,
    AFTER_CLOUD_VARS_PUBLISHED,
    AFTER_CLOUD_LINEAGE_PUBLISHED,
    AFTER_CLOUD_ACTIVATION_PUBLISHED,
    AFTER_CLOUD_ORIGINALS_FINALIZED,
}

fun interface ProfileRepositoryFaultInjector {
    @Throws(IOException::class)
    fun check(point: ProfileRepositoryFaultPoint)
}

fun interface ProfileUsableSpaceProvider {
    @Throws(IOException::class)
    fun usableSpace(path: Path): Long
}

fun interface ProfileNoCloudSeedPolicy {
    @Throws(IOException::class)
    fun requireCanonical(sha256: Sha256Digest, sizeBytes: Long)
}

internal object CanonicalNoCloudSeedPolicy : ProfileNoCloudSeedPolicy {
    override fun requireCanonical(sha256: Sha256Digest, sizeBytes: Long) {
        if (sizeBytes != ProfileV2Limits.CANONICAL_NOCLOUD_SEED_BYTES ||
            sha256.value != ProfileV2Limits.CANONICAL_NOCLOUD_SEED_SHA256
        ) throw ProfileDownloadException("NoCloud seed is outside the closed canonical semantics")
    }
}

/**
 * One-stream profile store. [storageFile] is the sole authoritative destructive target and is
 * fixed for the repository lifetime; no operation accepts a caller-selected path.
 */
class ProfileRepository(
    repositoryDirectory: File,
    storageFile: File,
    private val approvedOrigins: ApprovedArtifactOrigins,
    private val trustPolicy: ProfileTrustPolicy,
    private val artifactFetcher: ProfileArtifactFetcher,
    private val directoryDurability: DirectoryDurability,
    private val verifier: Ed25519Verifier = TinkEd25519Verifier,
    private val storeLimits: ProfileStoreLimits = ProfileStoreLimits(),
    private val fetchTimeoutMillis: Long = DEFAULT_FETCH_TIMEOUT_MILLIS,
    private val lockTimeoutMillis: Long = DEFAULT_LOCK_TIMEOUT_MILLIS,
    private val faultInjector: ProfileRepositoryFaultInjector = ProfileRepositoryFaultInjector { },
    private val usableSpaceProvider: ProfileUsableSpaceProvider = ProfileUsableSpaceProvider {
        Files.getFileStore(it).usableSpace
    },
    private val noCloudSeedPolicy: ProfileNoCloudSeedPolicy = CanonicalNoCloudSeedPolicy,
    uefiVarsFile: File = storageFile.parentFile.resolve("uefi-vars.fd"),
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
    private val trustQuarantinePath = state.resolve(TRUST_QUARANTINE_FILE)
    private val cloudLineagePath = state.resolve(CLOUD_LINEAGE_FILE)
    private val fixedStorage = storageFile.toPath().toAbsolutePath().normalize()
    private val fixedVars = uefiVarsFile.toPath().toAbsolutePath().normalize()
    private val storageTombstone = fixedStorage.parent?.resolve(
        ".podroid-profile-delete-${fixedStorage.toString().sha256Hex()}",
    ) ?: throw IllegalArgumentException("fixed storage file has no parent")
    private val storageRecoveryQuarantine = fixedStorage.parent?.resolve(
        ".podroid-profile-preserved-${fixedStorage.toString().sha256Hex()}",
    ) ?: throw IllegalArgumentException("fixed storage file has no parent")
    private val varsTombstone = fixedVars.parent?.resolve(
        ".podroid-profile-delete-${fixedVars.toString().sha256Hex()}",
    ) ?: throw IllegalArgumentException("fixed UEFI vars file has no parent")
    private val varsRecoveryQuarantine = fixedVars.parent?.resolve(
        ".podroid-profile-preserved-${fixedVars.toString().sha256Hex()}",
    ) ?: throw IllegalArgumentException("fixed UEFI vars file has no parent")
    private val cloudStorageTemporary = fixedStorage.parent.resolve(".podroid-cloud-storage.init")
    private val cloudVarsTemporary = fixedVars.parent.resolve(".podroid-cloud-vars.init")
    private val confirmationOwner = Any()

    init {
        require(!fixedStorage.startsWith(root) && !fixedVars.startsWith(root)) {
            "fixed mutable files must be outside the repository metadata tree"
        }
        require(fixedStorage.parent == fixedVars.parent && fixedStorage != fixedVars) {
            "fixed storage and UEFI vars must be distinct siblings"
        }
        require(fetchTimeoutMillis in 1..MAX_FETCH_TIMEOUT_MILLIS) { "fetch timeout is outside the bound" }
        require(lockTimeoutMillis in 1..MAX_LOCK_TIMEOUT_MILLIS) { "lock timeout is outside the bound" }
    }

    /** Verifies, streams, fsyncs, CAS-publishes, then atomically records a complete generation. */
    @Throws(IOException::class)
    fun prepare(signedEnvelopeBytes: ByteArray): PreparedProfile {
        val verified = VerifiedProfileEnvelopeJsonCodec.decodeManifest(
            signedEnvelopeBytes,
            approvedOrigins,
            trustPolicy,
            verifier,
        )
        return withRepositoryLock {
            recoverLocked()
            if (readPendingActivationLocked() != null) {
                throw ProfileActivationException("prepare is blocked while confirmed data deletion is pending")
            }
            if (verified.trustEpoch != trustPolicy.trustEpoch) {
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
            recoverPendingActivationLocked()
        }
    }

    /** Diagnostic lifecycle metadata only. Launches must use [resolveActiveProfile]. */
    @Throws(IOException::class)
    fun activationState(): ActivationState? = withRepositoryLock {
        recoverLocked()
        if (readPendingActivationLocked() != null) {
            throw ProfileActivationException("a confirmed data-deletion activation requires recovery")
        }
        readActivationStateLocked()
    }

    /** Resolves the active profile only after current trust and every artifact blob are revalidated. */
    @Throws(IOException::class)
    fun resolveActiveProfile(): PreparedProfile? = withRepositoryLock {
        recoverLocked()
        recoverPendingActivationLocked()
        val activation = readActivationStateLocked()
        if (activation == null && downloadedLineageClaimedLocked()) {
            throw ProfileActivationException(
                "bundled fallback is blocked because downloaded profile lineage was previously claimed",
            )
        }
        activation?.let {
            requireUsableCandidateLocked(
                it.active,
                requireCurrentFloor = false,
                requireCurrentTrust = true,
            ).toPublic()
        }
    }

    /** Resolves retained candidate artifacts only after current trust and every blob are revalidated. */
    @Throws(IOException::class)
    fun resolveCandidate(candidate: PreparedProfileCandidate): PreparedProfile = withRepositoryLock {
        recoverLocked()
        recoverPendingActivationLocked()
        requireUsableCandidateLocked(
            candidate,
            requireCurrentFloor = false,
            requireCurrentTrust = true,
        ).toPublic()
    }

    /** Returns the latest bounded terminal failure recorded while recovering a destructive activation. */
    @Throws(IOException::class)
    fun lastActivationFailure(): ActivationFailureState? = withRepositoryLock {
        recoverLocked()
        recoverPendingActivationLocked()
        readActivationFailureLocked()
    }

    /** Returns the latest bounded diagnostic record for lifecycle references rejected by this policy. */
    @Throws(IOException::class)
    fun lastTrustQuarantine(): TrustQuarantineState? = withRepositoryLock {
        recoverLocked()
        recoverPendingActivationLocked()
        readTrustQuarantineLocked()
    }

    internal fun candidateSupportedBackends(candidate: PreparedProfileCandidate): Set<ProfileBackend> =
        withRepositoryLock {
            recoverLocked()
            requireUsableCandidateLocked(candidate, false, true).supportedBackends.toSet()
        }

    internal fun rollbackSupportedBackends(expectedActivationSequence: Long): Set<ProfileBackend> =
        withRepositoryLock {
            recoverLocked()
            if (readPendingActivationLocked() != null) {
                throw ProfileActivationException("a confirmed data-deletion activation requires recovery")
            }
            val current = readActivationStateLocked()
                ?: throw ProfileActivationException("there is no active profile to roll back")
            if (current.activationSequence != expectedActivationSequence) {
                throw ProfileActivationException("rollback confirmation does not match the current activation sequence")
            }
            val rollback = current.rollback
                ?: throw ProfileActivationException("there is no retained local rollback profile")
            requireUsableCandidateLocked(rollback, false, true).supportedBackends.toSet()
        }

    /** Issues destructive authority for the current sequence, candidate, and fixed file identity. */
    @Throws(IOException::class)
    fun issueDataDeletionConfirmation(candidate: PreparedProfileCandidate): DataDeletionConfirmation =
        withRepositoryLock {
            recoverLocked()
            if (readPendingActivationLocked() != null) {
                throw ProfileActivationException("a confirmed data-deletion activation requires recovery")
            }
            val activation = readActivationStateLocked()
            requireUsableCandidateLocked(
                candidate,
                requireCurrentFloor = activation?.active != candidate,
                requireCurrentTrust = true,
            )
            DataDeletionConfirmation.issue(
                confirmationOwner,
                latestActivationSequenceLocked(),
                candidate,
                captureFileIdentity(fixedStorage, "fixed storage", requireStableKeys = true),
                captureFileIdentity(fixedVars, "fixed UEFI vars", requireStableKeys = true),
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
            if (completePendingActivationLocked(pending) == PendingRecoveryResult.ABORTED) {
                throw ProfileActivationException("the pending data-deletion activation was aborted during recovery")
            }
            return@withRepositoryLock readActivationStateLocked()
                ?: throw ProfileActivationException("the recovered activation is no longer launchable")
        }

        val current = readActivationStateLocked()
        val sameActive = current?.active == candidate
        val preparedRecord = requireUsableCandidateLocked(
            candidate,
            requireCurrentFloor = !sameActive,
            requireCurrentTrust = true,
        )
        when (dataPolicy) {
            GuestDataPolicy.PRESERVE_DATA -> {
                if (deletionConfirmation != null) {
                    throw ProfileActivationException("deletion confirmation is invalid for PRESERVE_DATA")
                }
                captureStorageIdentity(requireStableKeys = false)
                if (sameActive) return@withRepositoryLock current
                if (current == null && preparedRecord.planKind == PreparedPlanKind.UEFI_NOCLOUD_V1) {
                    throw ProfileActivationException("UEFI cloud activation requires confirmed DELETE_DATA over bundled storage")
                }
                if (current == null && Files.exists(fixedStorage, LinkOption.NOFOLLOW_LINKS) &&
                    preparedRecord.dataCompatibility != ProfileDataLineage.BUNDLED_ALPINE
                ) {
                    throw ProfileActivationException(
                        "first PRESERVE_DATA activation must match bundled Alpine storage lineage",
                    )
                }
                current?.let { activation ->
                    val activeRecord = requireUsableCandidateLocked(
                        activation.active,
                        requireCurrentFloor = false,
                        requireCurrentTrust = false,
                    )
                    requirePreserveCompatible(activeRecord, preparedRecord)
                }
            }
            GuestDataPolicy.DELETE_DATA -> {
                val expectedSequence = latestActivationSequenceLocked()
                if (deletionConfirmation?.owner !== confirmationOwner ||
                    deletionConfirmation.expectedActivationSequence != expectedSequence ||
                    deletionConfirmation.candidate != candidate
                ) {
                    throw ProfileActivationException(
                        "DELETE_DATA requires repository-issued confirmation for the current state and candidate",
                    )
                }
                requireFileIdentity(
                    fixedStorage, storageTombstone, deletionConfirmation.storageIdentity,
                    "fixed storage", allowCompletedDeletion = false,
                )
                requireFileIdentity(
                    fixedVars, varsTombstone, deletionConfirmation.varsIdentity,
                    "fixed UEFI vars", allowCompletedDeletion = false,
                )
            }
        }

        val rollback = when {
            sameActive -> current?.rollback
            dataPolicy == GuestDataPolicy.DELETE_DATA -> current?.active?.takeIf { previous ->
                val previousRecord = requireUsableCandidateLocked(previous, false, false)
                storageContractsCompatible(previousRecord, preparedRecord)
            }
            else -> current?.active
        }
        val next = ActivationState(nextActivationSequence(latestActivationSequenceLocked()), candidate, rollback)
        ensureDownloadedLineageMarkerLocked()
        if (dataPolicy == GuestDataPolicy.DELETE_DATA) {
            if (preparedRecord.planKind == PreparedPlanKind.UEFI_NOCLOUD_V1) {
                ensureCloudInitializationCapacity(preparedRecord)
            }
            val pending = PendingActivation(
                desired = next,
                storageIdentity = deletionConfirmation!!.storageIdentity,
                varsIdentity = deletionConfirmation.varsIdentity,
                previousActivation = current,
                previousCloudLineage = readCloudLineageLocked(),
            )
            writeImmutableRecord(pendingActivationPath, encodePendingActivation(pending))
            faultInjector.check(ProfileRepositoryFaultPoint.AFTER_DELETION_INTENT)
            if (completePendingActivationLocked(pending) == PendingRecoveryResult.ABORTED) {
                throw ProfileActivationException("data-deletion activation was aborted after trust changed")
            }
        } else {
            writeReplaceRecord(activationPath(), encodeActivation(next))
        }
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
        requirePreserveCompatible(activeRecord, rollbackRecord)

        val next = ActivationState(nextActivationSequence(latestActivationSequenceLocked()), rollbackCandidate, current.active)
        writeReplaceRecord(activationPath(), encodeActivation(next))
        readActivationStateLocked()?.also {
            if (it != next) throw ProfileRepositoryCorruptException("published rollback record changed unexpectedly")
        } ?: throw ProfileRepositoryCorruptException("published rollback record is missing")
    }

    /**
     * Manager-only removal preparation. Clearing activation before VM files are deleted ensures a
     * crash can never leave old activation metadata booting replacement storage. The irreversible
     * downloaded-lineage marker and immutable prepared records remain for explicit reactivation.
     */
    internal fun clearForVmRemoval() = withRepositoryLock {
        recoverLocked()
        recoverPendingActivationLocked()
        readActivationStateLocked()?.let { active ->
            writeReplaceRecord(
                activationFailurePath(),
                encodeActivationFailure(
                    ActivationFailureState(
                        active.activationSequence,
                        active.active,
                        ActivationFailureReason.VM_DATA_REMOVED,
                        storageDeletionIrreversible = false,
                        uefiVarsDeletionIrreversible = false,
                    ),
                ),
            )
        }
        clearActivationLocked()
        clearCloudLineageLocked()
        clearMutableInitializationArtifactsLocked()
        prunePreparedRecordsAndBlobsLocked()
    }

    /** Deletes only bounded, unreferenced CAS blobs. Prepared and lifecycle references are preserved. */
    @Throws(IOException::class)
    fun collectGarbage(): ProfileGarbageCollectionResult = withRepositoryLock {
        recoverLocked()
        collectGarbageLocked()
    }

    private fun prepareLocked(verified: VerifiedProfileManifestAny): PreparedProfile {
        val candidate: PreparedProfileCandidate
        val compatibility: DataCompatibilityId
        val backends: Set<ProfileBackend>
        val planKind: PreparedPlanKind
        val capabilities: Set<ProfileV2GuestIntegration>
        val readiness: String?
        val incoming: List<IncomingArtifact>
        when (verified) {
            is VerifiedProfileManifestAny.Direct -> {
                val profile = verified.value.profile
                candidate = PreparedProfileCandidate(
                    profile.id, profile.generation, verified.manifestSha256, verified.signingKeyId,
                    verified.signingKeyFingerprint, verified.trustEpoch,
                )
                compatibility = profile.dataCompatibility
                backends = profile.supportedBackends
                planKind = PreparedPlanKind.DIRECT_KERNEL_OVERLAY_V1
                capabilities = emptySet()
                readiness = null
                incoming = profile.artifacts.map {
                    IncomingArtifact(it.role.wireName, null, it.url, it.sha256, it.sizeBytes.value)
                }
            }
            is VerifiedProfileManifestAny.UefiNoCloud -> {
                val profile = verified.value.profile
                candidate = PreparedProfileCandidate(
                    profile.id, profile.generation, verified.manifestSha256, verified.signingKeyId,
                    verified.signingKeyFingerprint, verified.trustEpoch,
                )
                compatibility = profile.dataCompatibility
                backends = profile.supportedBackends
                planKind = PreparedPlanKind.UEFI_NOCLOUD_V1
                capabilities = profile.capabilities.guestIntegrations
                readiness = profile.readinessMarker
                incoming = profile.artifacts.map {
                    IncomingArtifact(it.role.wireName, it.format.wireName, it.url, it.sha256, it.sizeBytes)
                }
            }
        }
        val profileKey = profileKey(candidate.profileId)
        val currentEpoch = trustPolicy.trustEpoch
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
        } else epochRecords.maxByOrNull { it.candidate.generation.value }
        if (effectiveFloor != null) when {
            candidate.generation.value < effectiveFloor.candidate.generation.value ->
                throw ProfileGenerationRollbackException("profile generation is below the current trust-epoch floor")
            candidate.generation == effectiveFloor.candidate.generation &&
                candidate.manifestSha256 != effectiveFloor.candidate.manifestSha256 ->
                throw ProfileGenerationEquivocationException("equal profile generation has a different signed manifest")
        }
        val expectedRecord = PreparedRecord(
            candidate, compatibility, backends, planKind, capabilities, readiness,
            incoming.sortedBy { artifactOrder(planKind, it.role) }
                .map { PreparedArtifact(it.role, it.format, it.sha256, it.sizeBytes) },
        )
        val existing = epochRecords.singleOrNull { it.candidate.generation == candidate.generation }
        if (existing != null) {
            if (existing != expectedRecord) throw ProfileGenerationEquivocationException(
                "immutable generation record conflicts with the signed manifest",
            )
            validatePreparedBlobs(existing)
            validateClosedNoCloudSeed(existing)
            revalidateVerifiedTrust(verified)
            publishFloorIfNeeded(profileKey, existing)
            prunePreparedRecordsAndBlobsLocked()
            return existing.toPublic()
        }
        if (preparedRecords.size >= MAX_GENERATIONS_PER_PROFILE) {
            throw ProfileRepositoryException("prepared generation retention bound reached")
        }
        ensureCasCapacity(incoming)
        val newlyPublished = linkedSetOf<Path>()
        try {
            incoming.sortedBy { artifactOrder(planKind, it.role) }.forEach { ensureBlob(it, newlyPublished) }
            revalidateVerifiedTrust(verified)
            validateClosedNoCloudSeed(expectedRecord)
            publishPrepared(profileKey, expectedRecord)
            validatePreparedBlobs(expectedRecord)
            publishFloorIfNeeded(profileKey, expectedRecord)
            prunePreparedRecordsAndBlobsLocked()
            return expectedRecord.toPublic()
        } catch (failure: Throwable) {
            cleanupNewUnreferencedBlobs(newlyPublished, failure)
            throw failure
        }
    }

    private fun validateClosedNoCloudSeed(record: PreparedRecord) {
        if (record.planKind != PreparedPlanKind.UEFI_NOCLOUD_V1) return
        val seed = record.artifacts.single { it.role == ProfileV2ArtifactRole.NOCLOUD_SEED.wireName }
        noCloudSeedPolicy.requireCanonical(seed.sha256, seed.sizeBytes)
    }

    private fun artifactOrder(kind: PreparedPlanKind, role: String): Int = when (kind) {
        PreparedPlanKind.DIRECT_KERNEL_OVERLAY_V1 -> ArtifactRole.fromWireName(role)!!.ordinal
        PreparedPlanKind.UEFI_NOCLOUD_V1 -> ProfileV2ArtifactRole.fromWireName(role)!!.ordinal
    }

    private fun revalidateVerifiedTrust(verified: VerifiedProfileManifestAny) {
        if (verified.trustEpoch != trustPolicy.trustEpoch) {
            throw InvalidProfileSignatureException("profile trust policy changed during preparation")
        }
        val resolved = trustPolicy.resolve(verified.signingKeyId)
            ?: throw InvalidProfileSignatureException("profile signing key was revoked during preparation")
        if (resolved.publicKey.fingerprint != verified.signingKeyFingerprint) {
            throw InvalidProfileSignatureException("profile signing key changed during preparation")
        }
    }

    private fun ensureBlob(artifact: IncomingArtifact, newlyPublished: MutableSet<Path>) {
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
                    artifact.sizeBytes + 1L,
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

    private fun ensureCasCapacity(artifacts: List<IncomingArtifact>) {
        collectGarbageLocked()
        val uniqueIncoming = linkedMapOf<Sha256Digest, Long>()
        artifacts.forEach { artifact ->
            val previous = uniqueIncoming.putIfAbsent(artifact.sha256, artifact.sizeBytes)
            if (previous != null && previous != artifact.sizeBytes) {
                throw ProfileDownloadException("one artifact digest has conflicting signed sizes")
            }
        }
        val usage = casUsage()
        val missing = uniqueIncoming.filterKeys { !existsNoFollow(blobPath(it)) }
        val incomingBytes = missing.values.fold(0L) { total, size -> checkedAdd(total, size, "incoming CAS bytes") }
        if (usage.first + missing.size > storeLimits.maxBlobCount) {
            throw ProfileQuotaExceededException("content-addressed blob count quota would be exceeded")
        }
        if (incomingBytes > storeLimits.maxCasBytes - usage.second) {
            throw ProfileQuotaExceededException("content-addressed byte quota would be exceeded")
        }
        val requiredFree = checkedAdd(incomingBytes, storeLimits.reservedFreeBytes, "CAS free-space reservation")
        val usable = try {
            usableSpaceProvider.usableSpace(blobs)
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

    private fun prunePreparedRecordsAndBlobsLocked() {
        val currentEpoch = trustPolicy.trustEpoch
        val activation = readActivationStateLocked()
        val pending = readPendingActivationLocked()
        val retainedCandidates = buildSet {
            activation?.let { add(it.active); it.rollback?.let(::add) }
            pending?.desired?.let { add(it.active); it.rollback?.let(::add) }
        }.toMutableSet()

        var prunedRecord = false
        val floors = readAllFloors()
        floors.forEach { (profileKey, floor) ->
            if (floor.candidate.trustEpoch == currentEpoch) {
                retainedCandidates.add(floor.candidate)
            } else {
                val path = floorPath(profileKey)
                requireRegularFile(path, "obsolete generation floor")
                Files.delete(path)
                forceDirectory(state)
                prunedRecord = true
            }
        }

        val emptiedProfileDirectories = ArrayList<Path>()
        Files.newDirectoryStream(prepared).use { profileDirectories ->
            for (profileDirectory in profileDirectories) {
                var deletedRecord = false
                Files.newDirectoryStream(profileDirectory).use { records ->
                    for (path in records) {
                        val profileKey = profileDirectory.fileName.toString()
                        val record = decodePrepared(readBoundedRecord(path), profileKey, path.fileName.toString())
                        if (record.candidate !in retainedCandidates) {
                            Files.delete(path)
                            deletedRecord = true
                            prunedRecord = true
                        }
                    }
                }
                if (deletedRecord) forceDirectory(profileDirectory)
                if (Files.newDirectoryStream(profileDirectory).use { !it.iterator().hasNext() }) {
                    emptiedProfileDirectories.add(profileDirectory)
                }
            }
        }
        emptiedProfileDirectories.forEach(Files::delete)
        if (emptiedProfileDirectories.isNotEmpty()) forceDirectory(prepared)

        if (prunedRecord) collectGarbageLocked()
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
        artifact: IncomingArtifact,
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
        if (contentLength != null && contentLength != artifact.sizeBytes) {
            throw ProfileDownloadException("artifact Content-Length does not match the signed size")
        }
        if (System.nanoTime() - request.deadlineNanos >= 0L) {
            throw ProfileDownloadException("artifact fetch deadline expired before streaming")
        }
    }

    private fun streamExactArtifact(
        input: InputStream,
        output: FileChannel,
        artifact: IncomingArtifact,
        deadlineNanos: Long,
    ) {
        val expected = artifact.sizeBytes
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

    private fun validateBlob(path: Path, digest: Sha256Digest, size: Long) {
        requireRegularFile(path, "content-addressed blob")
        val attributes = attributesNoFollow(path)
        if (attributes.size() != size) {
            throw ProfileRepositoryCorruptException("content-addressed blob size does not match its manifest")
        }
        val actual = MessageDigest.getInstance("SHA-256")
        FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.allocate(STREAM_BUFFER_BYTES)
            var total = 0L
            var zeroReads = 0
            while (total < size + 1L) {
                buffer.clear()
                buffer.limit(min(buffer.capacity().toLong(), size + 1L - total).toInt())
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
                if (total > size) throw ProfileRepositoryCorruptException("blob exceeds its manifest size")
                actual.update(buffer.array(), 0, count)
            }
            if (total != size) throw ProfileRepositoryCorruptException("blob is shorter than its manifest size")
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
        validateClosedNoCloudSeed(record)
        return record
    }

    private fun revalidateTrust(record: PreparedRecord) {
        val candidate = record.candidate
        if (candidate.trustEpoch != trustPolicy.trustEpoch) {
            throw ProfileActivationException("prepared candidate trust epoch is no longer current")
        }
        val resolved = trustPolicy.resolve(candidate.signingKeyId)
            ?: throw ProfileActivationException("prepared candidate signing key is no longer trusted")
        if (resolved.publicKey.fingerprint != candidate.signingKeyFingerprint) {
            throw ProfileActivationException("prepared candidate signing key fingerprint changed")
        }
    }

    private fun validatePreparedBlobs(record: PreparedRecord) {
        record.artifacts.forEach { validateBlob(blobPath(it.sha256), it.sha256, it.sizeBytes) }
        if (record.planKind == PreparedPlanKind.UEFI_NOCLOUD_V1 && readActivationStateLocked()?.active == record.candidate) {
            requireCloudLineageCompatible(record)
            requireMutableCloudFiles()
        }
    }

    private fun storageContractsCompatible(left: PreparedRecord, right: PreparedRecord): Boolean =
        left.planKind == right.planKind && left.dataCompatibility == right.dataCompatibility &&
            (left.planKind != PreparedPlanKind.UEFI_NOCLOUD_V1 || cloudLineageFor(left) == cloudLineageFor(right))

    private fun requirePreserveCompatible(left: PreparedRecord, right: PreparedRecord) {
        if (!storageContractsCompatible(left, right)) {
            throw ProfileActivationException("PRESERVE_DATA requires an exact compatible storage lineage")
        }
        if (right.planKind == PreparedPlanKind.UEFI_NOCLOUD_V1) requireCloudLineageCompatible(right)
    }

    private fun cloudLineageFor(record: PreparedRecord): CloudLineage {
        if (record.planKind != PreparedPlanKind.UEFI_NOCLOUD_V1) {
            throw ProfileActivationException("profile does not use the UEFI cloud storage contract")
        }
        fun digest(role: ProfileV2ArtifactRole) = record.artifacts.single { it.role == role.wireName }.sha256
        return CloudLineage(
            record.dataCompatibility,
            digest(ProfileV2ArtifactRole.CLOUD_DISK),
            digest(ProfileV2ArtifactRole.UEFI_VARS_TEMPLATE),
        )
    }

    private fun requireCloudLineageCompatible(record: PreparedRecord) {
        val actual = readCloudLineageLocked()
            ?: throw ProfileActivationException("active UEFI cloud mutable lineage is missing")
        if (actual != cloudLineageFor(record)) {
            throw ProfileActivationException("active UEFI cloud mutable lineage is incompatible")
        }
    }

    private fun requireMutableCloudFiles() {
        requireRegularFile(fixedStorage, "cloud root disk")
        requireRegularFile(fixedVars, "writable UEFI vars")
        val storageSize = attributesNoFollow(fixedStorage).size()
        val varsSize = attributesNoFollow(fixedVars).size()
        if (storageSize !in 1..ProfileV2Limits.MAX_CLOUD_DISK_BYTES ||
            varsSize !in 1..ProfileV2Limits.MAX_UEFI_VARS_TEMPLATE_BYTES
        ) throw ProfileActivationException("active UEFI cloud mutable files are outside their bounds")
    }

    private fun captureStorageIdentity(requireStableKeys: Boolean): StorageIdentity =
        captureFileIdentity(fixedStorage, "fixed storage", requireStableKeys)

    private fun captureFileIdentity(path: Path, label: String, requireStableKeys: Boolean): StorageIdentity {
        val parent = path.parent ?: throw ProfileActivationException("$label file has no parent")
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
        if (!existsNoFollow(path)) {
            return StorageIdentity(parentKey.orEmpty(), parentCreationTime, false, null, null, null, null)
        }
        requireRegularFile(path, label)
        val attributes = attributesNoFollow(path)
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

    private fun requireStorageIdentity(expected: StorageIdentity, allowCompletedDeletion: Boolean) =
        requireFileIdentity(fixedStorage, storageTombstone, expected, "fixed storage", allowCompletedDeletion)

    private fun requireFileIdentity(
        path: Path,
        tombstone: Path,
        expected: StorageIdentity,
        label: String,
        allowCompletedDeletion: Boolean,
    ) {
        val actual = captureFileIdentity(path, label, requireStableKeys = true)
        if (actual.parentFileKey != expected.parentFileKey ||
            actual.parentCreationTime != expected.parentCreationTime
        ) {
            throw ProfileActivationException("fixed storage parent was replaced")
        }
        if (actual == expected) {
            if (existsNoFollow(tombstone)) {
                throw ProfileActivationException("$label deletion tombstone is unexpectedly occupied")
            }
            return
        }
        if (expected.existed && !actual.existed && existsNoFollow(tombstone)) {
            requireRegularFile(tombstone, "$label deletion tombstone")
            val tombstoneAttributes = attributesNoFollow(tombstone)
            if (tombstoneAttributes.matches(expected)) return
            throw ProfileActivationException("fixed storage deletion tombstone has a different identity")
        }
        if (expected.existed && !actual.existed && allowCompletedDeletion && !existsNoFollow(tombstone)) return
        if (!expected.existed && !actual.existed && !existsNoFollow(tombstone)) return
        throw ProfileActivationException("fixed storage file was replaced after confirmation")
    }

    private fun deleteFixedStorage(expected: StorageIdentity) =
        deleteFixedFile(fixedStorage, storageTombstone, expected, "fixed storage")

    private fun deleteFixedFile(path: Path, tombstone: Path, expected: StorageIdentity, label: String) {
        requireFileIdentity(path, tombstone, expected, label, allowCompletedDeletion = true)
        if (existsNoFollow(path)) {
            Files.move(path, tombstone, StandardCopyOption.ATOMIC_MOVE)
            forceDirectory(path.parent)
            requireRegularFile(tombstone, "$label deletion tombstone")
            if (!attributesNoFollow(tombstone).matches(expected)) {
                throw ProfileActivationException("$label was replaced during deletion")
            }
        }
        if (existsNoFollow(tombstone)) {
            requireRegularFile(tombstone, "$label deletion tombstone")
            if (!attributesNoFollow(tombstone).matches(expected)) {
                throw ProfileActivationException("$label deletion tombstone was replaced")
            }
            Files.delete(tombstone)
            forceDirectory(path.parent)
        }
    }

    private fun ensureCloudInitializationCapacity(record: PreparedRecord) {
        val diskBytes = record.artifacts.single {
            it.role == ProfileV2ArtifactRole.CLOUD_DISK.wireName
        }.sizeBytes
        val varsBytes = record.artifacts.single {
            it.role == ProfileV2ArtifactRole.UEFI_VARS_TEMPLATE.wireName
        }.sizeBytes
        // Both originals remain as tombstones through activation publication, so none of their
        // bytes are guaranteed reclaimable at the peak-copy point. Conservatively credit zero.
        val copies = checkedAdd(diskBytes, varsBytes, "cloud mutable copies")
        val required = checkedAdd(copies, storeLimits.reservedFreeBytes, "cloud mutable free-space reserve")
        val usable = try {
            usableSpaceProvider.usableSpace(fixedStorage.parent)
        } catch (failure: IOException) {
            throw ProfileQuotaExceededException("cloud mutable free space cannot be established: ${failure.message}")
        }
        if (usable < required) {
            throw ProfileQuotaExceededException("cloud mutable initialization free-space reserve would be violated")
        }
    }

    private fun initializeCloudMutableFiles(record: PreparedRecord, pending: PendingActivation): PendingActivation {
        val varsIdentity = pending.varsIdentity
            ?: throw ProfileRepositoryCorruptException("cloud activation has no fixed UEFI vars identity")
        val diskArtifact = record.artifacts.single { it.role == ProfileV2ArtifactRole.CLOUD_DISK.wireName }
        val varsArtifact = record.artifacts.single { it.role == ProfileV2ArtifactRole.UEFI_VARS_TEMPLATE.wireName }
        var current = pending

        val storageReplacement = publishFixedReplacement(
            fixedStorage, storageTombstone, cloudStorageTemporary, pending.storageIdentity,
            current.replacementStorageIdentity,
            current.legacyDestructiveJournal || current.phase >= CloudInitializationPhase.ACTIVATION_PUBLISHED,
            diskArtifact, "cloud root disk",
        )
        if (current.phase < CloudInitializationPhase.STORAGE_PUBLISHED || current.replacementStorageIdentity == null) {
            current = current.copy(
                phase = CloudInitializationPhase.STORAGE_PUBLISHED,
                replacementStorageIdentity = storageReplacement,
            )
            writeReplaceRecord(pendingActivationPath, encodePendingActivation(current))
            faultInjector.check(ProfileRepositoryFaultPoint.AFTER_CLOUD_STORAGE_PUBLISHED)
        }

        val varsReplacement = publishFixedReplacement(
            fixedVars, varsTombstone, cloudVarsTemporary, varsIdentity,
            current.replacementVarsIdentity,
            current.legacyDestructiveJournal || current.phase >= CloudInitializationPhase.ACTIVATION_PUBLISHED,
            varsArtifact, "writable UEFI vars",
        )
        if (current.phase < CloudInitializationPhase.VARS_PUBLISHED || current.replacementVarsIdentity == null) {
            current = current.copy(
                phase = CloudInitializationPhase.VARS_PUBLISHED,
                replacementVarsIdentity = varsReplacement,
            )
            writeReplaceRecord(pendingActivationPath, encodePendingActivation(current))
            faultInjector.check(ProfileRepositoryFaultPoint.AFTER_CLOUD_VARS_PUBLISHED)
        }

        if (current.phase < CloudInitializationPhase.LINEAGE_PUBLISHED) {
            writeReplaceRecord(cloudLineagePath, encodeCloudLineage(cloudLineageFor(record)))
            current = current.copy(phase = CloudInitializationPhase.LINEAGE_PUBLISHED)
            writeReplaceRecord(pendingActivationPath, encodePendingActivation(current))
            faultInjector.check(ProfileRepositoryFaultPoint.AFTER_CLOUD_LINEAGE_PUBLISHED)
        } else {
            requireCloudLineageCompatible(record)
        }
        return current
    }

    private fun publishFixedReplacement(
        target: Path,
        tombstone: Path,
        temporaryPath: Path,
        expected: StorageIdentity,
        recordedReplacement: StorageIdentity?,
        allowMissingOriginalTombstone: Boolean,
        sourceArtifact: PreparedArtifact,
        label: String,
    ): StorageIdentity {
        val actual = captureFileIdentity(target, label, requireStableKeys = true)
        if (actual.parentFileKey != expected.parentFileKey ||
            actual.parentCreationTime != expected.parentCreationTime
        ) throw ProfileActivationException("$label parent was replaced during initialization")

        if (recordedReplacement != null) {
            if (actual != recordedReplacement) throw ProfileActivationException("$label replacement identity changed")
            if (expected.existed && !existsNoFollow(tombstone) && !allowMissingOriginalTombstone) {
                throw ProfileActivationException("$label original tombstone is missing")
            }
            validateBlob(target, sourceArtifact.sha256, sourceArtifact.sizeBytes)
            return actual
        }

        if (actual == expected && expected.existed) {
            ensureNoPath(tombstone)
            Files.move(target, tombstone, StandardCopyOption.ATOMIC_MOVE)
            forceDirectory(target.parent)
        }
        if (expected.existed && existsNoFollow(tombstone)) {
            requireRegularFile(tombstone, "$label original tombstone")
            if (!attributesNoFollow(tombstone).matches(expected)) {
                throw ProfileActivationException("$label original tombstone identity changed")
            }
        } else if (expected.existed && actual != expected && !allowMissingOriginalTombstone) {
            throw ProfileActivationException("$label original tombstone is missing")
        } else if (!expected.existed && existsNoFollow(tombstone)) {
            throw ProfileActivationException("$label has an unexpected original tombstone")
        }

        // Recovery may observe a replacement published just before its phase record.
        if (existsNoFollow(target)) {
            validateBlob(target, sourceArtifact.sha256, sourceArtifact.sizeBytes)
            return captureFileIdentity(target, label, requireStableKeys = true)
        }
        if (existsNoFollow(temporaryPath)) {
            requireRegularFile(temporaryPath, "$label temporary file")
            Files.delete(temporaryPath)
            forceDirectory(temporaryPath.parent)
        }
        val source = blobPath(sourceArtifact.sha256)
        validateBlob(source, sourceArtifact.sha256, sourceArtifact.sizeBytes)
        FileChannel.open(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            FileChannel.open(
                temporaryPath,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            ).use { output ->
                var position = 0L
                var zeroProgress = 0
                while (position < sourceArtifact.sizeBytes) {
                    val count = input.transferTo(position, sourceArtifact.sizeBytes - position, output)
                    if (count == 0L) {
                        zeroProgress++
                        if (zeroProgress > MAX_ZERO_READS) throw ProfileActivationException("$label copy made no progress")
                    } else {
                        zeroProgress = 0
                        position += count
                    }
                }
                output.force(true)
            }
        }
        Files.move(temporaryPath, target, StandardCopyOption.ATOMIC_MOVE)
        forceDirectory(target.parent)
        validateBlob(target, sourceArtifact.sha256, sourceArtifact.sizeBytes)
        return captureFileIdentity(target, label, requireStableKeys = true)
    }

    private fun BasicFileAttributes.matches(expected: StorageIdentity): Boolean =
        fileKey()?.toString() == expected.fileKey &&
            size() == expected.sizeBytes &&
            creationTime().toString() == expected.creationTime &&
            lastModifiedTime().toString() == expected.lastModifiedTime

    private fun recoverPendingActivationLocked() {
        readPendingActivationLocked()?.let(::completePendingActivationLocked)
    }

    private fun completePendingActivationLocked(pending: PendingActivation): PendingRecoveryResult {
        val activation = readActivationStateLocked()
        validatePendingSequence(pending, activation)
        if (activation == pending.desired) {
            val committedRecord = try {
                requireUsableCandidateLocked(activation.active, false, false)
            } catch (_: ProfileRepositoryException) {
                abortPendingActivationLocked(pending)
                return PendingRecoveryResult.ABORTED
            }
            if (committedRecord.planKind == PreparedPlanKind.DIRECT_KERNEL_OVERLAY_V1) {
                clearPendingActivation()
                reconcileActivationTrustLocked()
                prunePreparedRecordsAndBlobsLocked()
                return PendingRecoveryResult.COMPLETED
            }
        }
        val desiredRecord = try {
            requireUsableCandidateLocked(
                pending.desired.active,
                requireCurrentFloor = activation?.active != pending.desired.active,
                requireCurrentTrust = true,
            ).also { record ->
                pending.desired.rollback?.let {
                    requireUsableCandidateLocked(it, requireCurrentFloor = false, requireCurrentTrust = false)
                }
                revalidateTrust(record)
            }
        } catch (_: ProfileRepositoryException) {
            abortPendingActivationLocked(pending)
            return PendingRecoveryResult.ABORTED
        }

        var current = pending
        if (desiredRecord.planKind == PreparedPlanKind.UEFI_NOCLOUD_V1) {
            current = initializeCloudMutableFiles(desiredRecord, current)
            requireMutableCloudFiles()
            if (activation != pending.desired) {
                writeReplaceRecord(activationPath(), encodeActivation(pending.desired))
            }
            if (current.phase < CloudInitializationPhase.ACTIVATION_PUBLISHED) {
                current = current.copy(phase = CloudInitializationPhase.ACTIVATION_PUBLISHED)
                writeReplaceRecord(pendingActivationPath, encodePendingActivation(current))
                faultInjector.check(ProfileRepositoryFaultPoint.AFTER_CLOUD_ACTIVATION_PUBLISHED)
            }
            finalizeCloudOriginalTombstones(current)
            faultInjector.check(ProfileRepositoryFaultPoint.AFTER_CLOUD_ORIGINALS_FINALIZED)
        } else {
            if (activation != pending.desired) {
                requireStorageIdentity(pending.storageIdentity, allowCompletedDeletion = true)
                deleteFixedStorage(pending.storageIdentity)
                pending.varsIdentity?.let { deleteFixedFile(fixedVars, varsTombstone, it, "fixed UEFI vars") }
                clearCloudLineageLocked()
                faultInjector.check(ProfileRepositoryFaultPoint.AFTER_STORAGE_DELETION)
                writeReplaceRecord(activationPath(), encodeActivation(pending.desired))
            }
        }
        clearPendingActivation()
        reconcileActivationTrustLocked()
        prunePreparedRecordsAndBlobsLocked()
        return PendingRecoveryResult.COMPLETED
    }

    private fun finalizeCloudOriginalTombstones(pending: PendingActivation) {
        requireReplacementIdentity(fixedStorage, pending.replacementStorageIdentity, "cloud root disk")
        requireReplacementIdentity(fixedVars, pending.replacementVarsIdentity, "writable UEFI vars")
        val allowAlreadyFinalized = pending.legacyDestructiveJournal ||
            pending.phase >= CloudInitializationPhase.ACTIVATION_PUBLISHED
        deleteOriginalTombstone(
            storageTombstone, pending.storageIdentity, "fixed storage", allowAlreadyFinalized,
        )
        deleteOriginalTombstone(
            varsTombstone,
            pending.varsIdentity ?: throw ProfileRepositoryCorruptException("cloud activation has no vars identity"),
            "fixed UEFI vars",
            allowAlreadyFinalized,
        )
    }

    private fun requireReplacementIdentity(path: Path, expected: StorageIdentity?, label: String) {
        val identity = expected ?: throw ProfileRepositoryCorruptException("$label replacement identity is missing")
        if (captureFileIdentity(path, label, requireStableKeys = true) != identity) {
            throw ProfileActivationException("$label replacement identity changed before commit")
        }
    }

    private fun deleteOriginalTombstone(
        tombstone: Path,
        expected: StorageIdentity,
        label: String,
        allowLegacyMissing: Boolean,
    ) {
        if (!expected.existed) {
            if (existsNoFollow(tombstone)) throw ProfileActivationException("$label has an unexpected tombstone")
            return
        }
        if (!existsNoFollow(tombstone)) {
            if (allowLegacyMissing) return
            throw ProfileActivationException("$label original tombstone is missing before commit")
        }
        requireRegularFile(tombstone, "$label original tombstone")
        if (!attributesNoFollow(tombstone).matches(expected)) {
            throw ProfileActivationException("$label original tombstone identity changed")
        }
        Files.delete(tombstone)
        forceDirectory(tombstone.parent)
    }

    private data class OriginalRestoreOutcome(val restoredAtFixedPath: Boolean, val irreversibleLoss: Boolean)

    private fun abortPendingActivationLocked(pending: PendingActivation) {
        val storageOutcome = restoreOriginalAfterAbort(
            fixedStorage, storageTombstone, storageRecoveryQuarantine, cloudStorageTemporary,
            pending.storageIdentity, pending.replacementStorageIdentity, "fixed storage",
        )
        val varsOutcome = pending.varsIdentity?.let { varsExpected ->
            restoreOriginalAfterAbort(
                fixedVars, varsTombstone, varsRecoveryQuarantine, cloudVarsTemporary,
                varsExpected, pending.replacementVarsIdentity, "fixed UEFI vars",
            )
        } ?: OriginalRestoreOutcome(restoredAtFixedPath = true, irreversibleLoss = false)
        val originalsRestored = storageOutcome.restoredAtFixedPath && varsOutcome.restoredAtFixedPath
        if (originalsRestored) {
            pending.previousCloudLineage?.let {
                writeReplaceRecord(cloudLineagePath, encodeCloudLineage(it))
            } ?: clearCloudLineageLocked()
            if (readActivationStateLocked() == pending.desired) {
                pending.previousActivation?.let {
                    writeReplaceRecord(activationPath(), encodeActivation(it))
                } ?: clearActivationLocked()
            }
        } else {
            // Never leave old boot authority able to consume partial or replacement mutable files.
            clearCloudLineageLocked()
            clearActivationLocked()
        }

        val failure = ActivationFailureState(
            pending.desired.activationSequence,
            pending.desired.active,
            ActivationFailureReason.CANDIDATE_NO_LONGER_USABLE,
            storageOutcome.irreversibleLoss,
            varsOutcome.irreversibleLoss,
        )
        writeReplaceRecord(activationFailurePath(), encodeActivationFailure(failure))
        clearPendingActivation()
        prunePreparedRecordsAndBlobsLocked()
    }

    private fun restoreOriginalAfterAbort(
        target: Path,
        tombstone: Path,
        quarantine: Path,
        temporaryPath: Path,
        expected: StorageIdentity,
        replacement: StorageIdentity?,
        label: String,
    ): OriginalRestoreOutcome {
        runCatching {
            if (existsNoFollow(temporaryPath)) {
                requireRegularFile(temporaryPath, "$label temporary replacement")
                Files.delete(temporaryPath)
                forceDirectory(temporaryPath.parent)
            }
        }
        val actual = runCatching { captureFileIdentity(target, label, requireStableKeys = true) }
            .getOrElse { return OriginalRestoreOutcome(false, expected.existed) }
        if (actual.parentFileKey != expected.parentFileKey ||
            actual.parentCreationTime != expected.parentCreationTime
        ) return OriginalRestoreOutcome(false, expected.existed)

        if (existsNoFollow(tombstone)) {
            val validTombstone = runCatching {
                requireRegularFile(tombstone, "$label original tombstone")
                attributesNoFollow(tombstone).matches(expected)
            }.getOrDefault(false)
            if (!expected.existed || !validTombstone) {
                return OriginalRestoreOutcome(false, expected.existed)
            }
            if (actual.existed) {
                if (replacement != null && actual == replacement) {
                    runCatching { Files.delete(target); forceDirectory(target.parent) }
                        .getOrElse { return OriginalRestoreOutcome(false, false) }
                } else {
                    // A crash may publish a replacement just before recording its identity. Move
                    // any unexpected target aside without deleting it, then restore the proven
                    // original to the authoritative fixed path.
                    if (existsNoFollow(quarantine)) return OriginalRestoreOutcome(false, false)
                    runCatching {
                        Files.move(target, quarantine, StandardCopyOption.ATOMIC_MOVE)
                        forceDirectory(target.parent)
                    }.getOrElse { return OriginalRestoreOutcome(false, false) }
                }
            }
            return runCatching {
                Files.move(tombstone, target, StandardCopyOption.ATOMIC_MOVE)
                forceDirectory(target.parent)
                OriginalRestoreOutcome(true, false)
            }.getOrElse { OriginalRestoreOutcome(false, false) }
        }

        if (expected.existed) {
            if (actual == expected) return OriginalRestoreOutcome(true, false)
            if (actual.existed) runCatching {
                if (!existsNoFollow(quarantine)) {
                    Files.move(target, quarantine, StandardCopyOption.ATOMIC_MOVE)
                    forceDirectory(target.parent)
                }
            }
            return OriginalRestoreOutcome(false, true)
        }
        if (!actual.existed) return OriginalRestoreOutcome(true, false)
        return runCatching {
            if (replacement != null && actual == replacement) {
                Files.delete(target)
            } else {
                ensureNoPath(quarantine)
                Files.move(target, quarantine, StandardCopyOption.ATOMIC_MOVE)
            }
            forceDirectory(target.parent)
            OriginalRestoreOutcome(true, false)
        }.getOrElse { OriginalRestoreOutcome(false, false) }
    }

    private fun validatePendingSequence(pending: PendingActivation, activation: ActivationState?) {
        if (activation == pending.desired) return
        val predecessorSequence = maxOf(
            activation?.activationSequence ?: 0L,
            latestTerminalActivationSequenceLocked(),
        )
        val sequenceFollows = pending.desired.activationSequence == nextActivationSequence(predecessorSequence)
        val candidatesFollow = if (activation == null) {
            pending.desired.rollback == null
        } else {
            (pending.desired.active == activation.active && pending.desired.rollback == activation.rollback) ||
                (pending.desired.active != activation.active &&
                    (pending.desired.rollback == activation.active || pending.desired.rollback == null))
        }
        if (!sequenceFollows || !candidatesFollow) {
            throw ProfileRepositoryCorruptException("pending activation does not follow current state")
        }
    }

    private fun reconcileActivationTrustLocked() {
        if (readPendingActivationLocked() != null) return
        val activation = readActivationStateLocked() ?: return
        val activeReason = trustRejectionReason(activation.active)
        val rollbackReason = activation.rollback?.let(::trustRejectionReason)
        if (activeReason == null && rollbackReason == null) return

        val quarantined = ArrayList<QuarantinedProfileCandidate>(2)
        if (activeReason != null) {
            quarantined += QuarantinedProfileCandidate(
                QuarantinedCandidateRole.ACTIVE,
                activation.active,
                activeReason,
            )
            activation.rollback?.let { rollback ->
                quarantined += QuarantinedProfileCandidate(
                    QuarantinedCandidateRole.ROLLBACK,
                    rollback,
                    rollbackReason ?: TrustQuarantineReason.NOT_SELECTED_AS_AUTOMATIC_FALLBACK,
                )
            }
        } else {
            quarantined += QuarantinedProfileCandidate(
                QuarantinedCandidateRole.ROLLBACK,
                activation.rollback!!,
                rollbackReason!!,
            )
        }
        writeReplaceRecord(
            trustQuarantinePath,
            encodeTrustQuarantine(TrustQuarantineState(activation.activationSequence, quarantined)),
        )
        if (activeReason != null) {
            requireRegularFile(activationPath(), "nonlaunchable activation record")
            Files.delete(activationPath())
            forceDirectory(state)
        } else {
            writeReplaceRecord(
                activationPath(),
                encodeActivation(ActivationState(activation.activationSequence, activation.active, null)),
            )
        }
    }

    private fun trustRejectionReason(candidate: PreparedProfileCandidate): TrustQuarantineReason? {
        if (candidate.trustEpoch != trustPolicy.trustEpoch) {
            return TrustQuarantineReason.TRUST_EPOCH_OBSOLETE
        }
        val trustedKey = trustPolicy.resolve(candidate.signingKeyId)
            ?: return TrustQuarantineReason.SIGNING_KEY_NOT_TRUSTED
        if (trustedKey.publicKey.fingerprint != candidate.signingKeyFingerprint) {
            return TrustQuarantineReason.SIGNING_KEY_CHANGED
        }
        return null
    }

    private fun recoverLocked() {
        validateTreeAndCleanTemps()
        downloadedLineageClaimedLocked()
        val preparedByKey = readAllPreparedRecords()
        val floorsByKey = readAllFloors()
        val currentEpoch = trustPolicy.trustEpoch
        for ((profileKey, records) in preparedByKey) {
            if (records.any { it.candidate.trustEpoch.value > currentEpoch.value }) {
                throw ProfileRepositoryCorruptException("prepared state is from a future trust epoch")
            }
            val floor = floorsByKey[profileKey]
            if (floor != null && floor.candidate.trustEpoch.value > currentEpoch.value) {
                throw ProfileRepositoryCorruptException("generation floor is from a future trust epoch")
            }
            if (floor != null && records.none { it.candidate == floor.candidate }) {
                throw ProfileRepositoryCorruptException("generation floor has no matching immutable prepared record")
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
        val trustQuarantine = readTrustQuarantineLocked()
        if (activation != null || pendingActivation != null || trustQuarantine != null) {
            ensureDownloadedLineageMarkerLocked()
        }
        if (activation != null && trustQuarantine != null &&
            trustQuarantine.activationSequence > activation.activationSequence
        ) {
            throw ProfileRepositoryCorruptException("activation sequence precedes trust quarantine")
        }
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
        if (pendingActivation == null) {
            reconcileActivationTrustLocked()
            readActivationStateLocked()?.let { active ->
                val record = requireUsableCandidateLocked(active.active, false, false)
                if (record.planKind == PreparedPlanKind.UEFI_NOCLOUD_V1) {
                    requireCloudLineageCompatible(record)
                    requireMutableCloudFiles()
                }
            }
        }
        prunePreparedRecordsAndBlobsLocked()
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
                if (name != ACTIVATION_FILE && name != PENDING_ACTIVATION_FILE &&
                    name != ACTIVATION_FAILURE_FILE && name != TRUST_QUARANTINE_FILE &&
                    name != CLOUD_LINEAGE_FILE && name != DOWNLOADED_LINEAGE_FILE && !FLOOR_NAME.matches(name)
                ) {
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

    private fun readActivationFailureLocked(): ActivationFailureState? {
        val path = activationFailurePath()
        return if (existsNoFollow(path)) decodeActivationFailure(readBoundedRecord(path)) else null
    }

    private fun readTrustQuarantineLocked(): TrustQuarantineState? =
        if (existsNoFollow(trustQuarantinePath)) {
            decodeTrustQuarantine(readBoundedRecord(trustQuarantinePath))
        } else {
            null
        }

    private fun latestActivationSequenceLocked(): Long = maxOf(
        readActivationStateLocked()?.activationSequence ?: 0L,
        latestTerminalActivationSequenceLocked(),
    )

    private fun latestTerminalActivationSequenceLocked(): Long = maxOf(
        readActivationFailureLocked()?.attemptedActivationSequence ?: 0L,
        readTrustQuarantineLocked()?.activationSequence ?: 0L,
    )

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

    private fun encodePrepared(record: PreparedRecord): ByteArray = when (record.planKind) {
        PreparedPlanKind.DIRECT_KERNEL_OVERLAY_V1 -> encodeRecord(PREPARED_MAGIC) { output ->
            writePreparedHeader(output, record)
            output.writeInt(record.artifacts.size)
            record.artifacts.sortedBy { ArtifactRole.fromWireName(it.role)!!.ordinal }.forEach { artifact ->
                output.writeUTF(artifact.role)
                output.writeUTF(artifact.sha256.value)
                output.writeLong(artifact.sizeBytes)
            }
        }
        PreparedPlanKind.UEFI_NOCLOUD_V1 -> encodeRecord(PREPARED_V2_MAGIC) { output ->
            writePreparedHeader(output, record)
            output.writeUTF(VmProfileV2.BOOT_CONTRACT)
            output.writeUTF(VmProfileV2.STORAGE_CONTRACT)
            output.writeUTF(VmProfileV2.HEALTH_CONTRACT)
            output.writeUTF(record.readinessMarker!!)
            output.writeInt(record.capabilities.size)
            record.capabilities.sortedBy { it.ordinal }.forEach { output.writeUTF(it.wireName) }
            output.writeInt(record.artifacts.size)
            record.artifacts.sortedBy { ProfileV2ArtifactRole.fromWireName(it.role)!!.ordinal }.forEach { artifact ->
                output.writeUTF(artifact.role)
                output.writeUTF(artifact.format!!)
                output.writeUTF(artifact.sha256.value)
                output.writeLong(artifact.sizeBytes)
            }
        }
    }

    private fun writePreparedHeader(output: DataOutputStream, record: PreparedRecord) {
        writeCandidate(output, record.candidate)
        output.writeUTF(record.dataCompatibility.value)
        output.writeInt(record.supportedBackends.size)
        record.supportedBackends.sortedBy { it.ordinal }.forEach { output.writeUTF(it.wireName) }
    }

    private fun decodePrepared(
        bytes: ByteArray,
        expectedProfileKey: String,
        expectedFileName: String,
    ): PreparedRecord {
        if (bytes.size < 4) throw ProfileRepositoryCorruptException("prepared record is truncated")
        val magic = ByteBuffer.wrap(bytes, 0, 4).int
        val record = when (magic) {
            PREPARED_MAGIC -> decodeRecord(bytes, PREPARED_MAGIC) { input ->
                val header = readPreparedHeader(input)
                val count = input.readInt()
                if (count != ArtifactRole.entries.size) throw ProfileRepositoryCorruptException("prepared artifact count is invalid")
                val artifacts = (0 until count).map {
                    val role = input.readUTF()
                    if (ArtifactRole.fromWireName(role) == null) throw ProfileRepositoryCorruptException("prepared artifact role is invalid")
                    PreparedArtifact(role, null, checkedRecordValue { Sha256Digest(input.readUTF()) }, checkedArtifactSize(input.readLong()))
                }
                if (artifacts.map { it.role }.toSet() != ArtifactRole.entries.map { it.wireName }.toSet()) {
                    throw ProfileRepositoryCorruptException("prepared artifact roles are not closed")
                }
                PreparedRecord(header.first, header.second, header.third, PreparedPlanKind.DIRECT_KERNEL_OVERLAY_V1, artifacts = artifacts)
            }
            PREPARED_V2_MAGIC -> decodeRecord(bytes, PREPARED_V2_MAGIC) { input ->
                val header = readPreparedHeader(input)
                if (input.readUTF() != VmProfileV2.BOOT_CONTRACT ||
                    input.readUTF() != VmProfileV2.STORAGE_CONTRACT ||
                    input.readUTF() != VmProfileV2.HEALTH_CONTRACT
                ) throw ProfileRepositoryCorruptException("prepared cloud contracts are invalid")
                val readiness = input.readUTF()
                if (readiness != ProfileV2Limits.READINESS_MARKER) throw ProfileRepositoryCorruptException("prepared cloud readiness is invalid")
                val capabilityCount = input.readInt()
                if (capabilityCount !in 0..ProfileV2GuestIntegration.entries.size) throw ProfileRepositoryCorruptException("prepared capability count is invalid")
                val capabilities = (0 until capabilityCount).map {
                    ProfileV2GuestIntegration.fromWireName(input.readUTF())
                        ?: throw ProfileRepositoryCorruptException("prepared capability is invalid")
                }.toSet()
                if (capabilities.size != capabilityCount) throw ProfileRepositoryCorruptException("prepared capabilities are duplicated")
                val count = input.readInt()
                if (count != ProfileV2ArtifactRole.entries.size) throw ProfileRepositoryCorruptException("prepared cloud artifact count is invalid")
                val artifacts = (0 until count).map {
                    val role = ProfileV2ArtifactRole.fromWireName(input.readUTF())
                        ?: throw ProfileRepositoryCorruptException("prepared cloud artifact role is invalid")
                    val format = ProfileV2ArtifactFormat.fromWireName(input.readUTF())
                        ?: throw ProfileRepositoryCorruptException("prepared cloud artifact format is invalid")
                    if (format != role.requiredFormat) throw ProfileRepositoryCorruptException("prepared cloud artifact role and format conflict")
                    val digest = checkedRecordValue { Sha256Digest(input.readUTF()) }
                    val size = checkedArtifactSize(input.readLong())
                    if (size > role.maxSizeBytes) throw ProfileRepositoryCorruptException("prepared cloud artifact exceeds role bound")
                    PreparedArtifact(role.wireName, format.wireName, digest, size)
                }
                if (artifacts.map { it.role }.toSet() != ProfileV2ArtifactRole.entries.map { it.wireName }.toSet()) {
                    throw ProfileRepositoryCorruptException("prepared cloud artifact roles are not closed")
                }
                PreparedRecord(header.first, header.second, header.third, PreparedPlanKind.UEFI_NOCLOUD_V1, capabilities, readiness, artifacts)
            }
            else -> throw ProfileRepositoryCorruptException("prepared record type is invalid")
        }
        val candidate = record.candidate
        if (profileKey(candidate.profileId) != expectedProfileKey ||
            expectedFileName != "${candidate.trustEpoch.value}-${candidate.generation.value}$PREPARED_SUFFIX"
        ) throw ProfileRepositoryCorruptException("prepared record is in the wrong fixed candidate path")
        return record
    }

    private fun readPreparedHeader(input: DataInputStream): Triple<PreparedProfileCandidate, DataCompatibilityId, Set<ProfileBackend>> {
        val candidate = readCandidate(input)
        val compatibility = checkedRecordValue { DataCompatibilityId(input.readUTF()) }
        val backendCount = input.readInt()
        if (backendCount !in 1..ProfileBackend.entries.size) throw ProfileRepositoryCorruptException("prepared backend count is invalid")
        val backends = (0 until backendCount).map {
            ProfileBackend.fromWireName(input.readUTF()) ?: throw ProfileRepositoryCorruptException("prepared backend is invalid")
        }.toSet()
        if (backends.size != backendCount) throw ProfileRepositoryCorruptException("prepared backends are duplicated")
        return Triple(candidate, compatibility, backends)
    }

    private fun checkedArtifactSize(value: Long): Long {
        if (value !in 1..ProfileLimits.MAX_ARTIFACT_BYTES) throw ProfileRepositoryCorruptException("prepared artifact size is invalid")
        return value
    }

    private fun encodeCloudLineage(lineage: CloudLineage): ByteArray = encodeRecord(CLOUD_LINEAGE_MAGIC) { output ->
        writeCloudLineage(output, lineage)
    }

    private fun writeCloudLineage(output: DataOutputStream, lineage: CloudLineage) {
        output.writeUTF(lineage.dataCompatibility.value)
        output.writeUTF(lineage.cloudDiskSha256.value)
        output.writeUTF(lineage.varsTemplateSha256.value)
    }

    private fun readCloudLineage(input: DataInputStream): CloudLineage = CloudLineage(
        checkedRecordValue { DataCompatibilityId(input.readUTF()) },
        checkedRecordValue { Sha256Digest(input.readUTF()) },
        checkedRecordValue { Sha256Digest(input.readUTF()) },
    )

    private fun decodeCloudLineage(bytes: ByteArray): CloudLineage = decodeRecord(bytes, CLOUD_LINEAGE_MAGIC) { input ->
        readCloudLineage(input)
    }

    private fun readCloudLineageLocked(): CloudLineage? =
        if (existsNoFollow(cloudLineagePath)) decodeCloudLineage(readBoundedRecord(cloudLineagePath)) else null

    private fun clearCloudLineageLocked() {
        if (existsNoFollow(cloudLineagePath)) {
            requireRegularFile(cloudLineagePath, "cloud lineage record")
            Files.delete(cloudLineagePath)
            forceDirectory(state)
        }
    }

    private fun clearActivationLocked() {
        val path = activationPath()
        if (existsNoFollow(path)) {
            requireRegularFile(path, "activation record")
            Files.delete(path)
            forceDirectory(state)
        }
    }

    private fun clearMutableInitializationArtifactsLocked() {
        listOf(cloudStorageTemporary, cloudVarsTemporary).forEach { path ->
            if (existsNoFollow(path)) {
                requireRegularFile(path, "cloud initialization temporary")
                Files.delete(path)
                forceDirectory(path.parent)
            }
        }
        if (existsNoFollow(storageTombstone) || existsNoFollow(varsTombstone)) {
            throw ProfileRepositoryCorruptException("orphan mutable initialization tombstone remains")
        }
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
        encodeRecord(PENDING_ACTIVATION_V3_MAGIC) { output ->
            writeActivation(output, record.desired)
            writeStorageIdentity(output, record.storageIdentity)
            output.writeBoolean(record.varsIdentity != null)
            record.varsIdentity?.let { writeStorageIdentity(output, it) }
            output.writeUTF(record.phase.name)
            output.writeBoolean(record.replacementStorageIdentity != null)
            record.replacementStorageIdentity?.let { writeStorageIdentity(output, it) }
            output.writeBoolean(record.replacementVarsIdentity != null)
            record.replacementVarsIdentity?.let { writeStorageIdentity(output, it) }
            output.writeBoolean(record.previousActivation != null)
            record.previousActivation?.let { writeActivation(output, it) }
            output.writeBoolean(record.previousCloudLineage != null)
            record.previousCloudLineage?.let { writeCloudLineage(output, it) }
        }

    private fun writeStorageIdentity(output: DataOutputStream, identity: StorageIdentity) {
        output.writeUTF(identity.parentFileKey)
        output.writeUTF(identity.parentCreationTime)
        output.writeBoolean(identity.existed)
        if (identity.existed) {
            output.writeUTF(identity.fileKey!!)
            output.writeLong(identity.sizeBytes!!)
            output.writeUTF(identity.creationTime!!)
            output.writeUTF(identity.lastModifiedTime!!)
        }
    }

    private fun encodeActivationFailure(record: ActivationFailureState): ByteArray =
        encodeRecord(ACTIVATION_FAILURE_V2_MAGIC) { output ->
            output.writeLong(record.attemptedActivationSequence)
            writeCandidate(output, record.candidate)
            output.writeUTF(record.reason.name)
            output.writeBoolean(record.storageDeletionIrreversible)
            output.writeBoolean(record.uefiVarsDeletionIrreversible)
        }

    private fun encodeTrustQuarantine(record: TrustQuarantineState): ByteArray =
        encodeRecord(TRUST_QUARANTINE_MAGIC) { output ->
            output.writeLong(record.activationSequence)
            output.writeInt(record.candidates.size)
            record.candidates.forEach { quarantined ->
                output.writeUTF(quarantined.role.name)
                writeCandidate(output, quarantined.candidate)
                output.writeUTF(quarantined.reason.name)
            }
        }

    private fun decodeActivationFailure(bytes: ByteArray): ActivationFailureState {
        if (bytes.size < 4) throw ProfileRepositoryCorruptException("activation failure is truncated")
        val magic = ByteBuffer.wrap(bytes, 0, 4).int
        return decodeRecord(bytes, magic) { input ->
            val sequence = input.readLong()
            if (sequence <= 0) throw ProfileRepositoryCorruptException("failure activation sequence is invalid")
            val candidate = readCandidate(input)
            val reason = try {
                ActivationFailureReason.valueOf(input.readUTF())
            } catch (failure: IllegalArgumentException) {
                throw ProfileRepositoryCorruptException("activation failure reason is invalid", failure)
            }
            val storageLoss = input.readBoolean()
            val varsLoss = when (magic) {
                ACTIVATION_FAILURE_MAGIC -> false
                ACTIVATION_FAILURE_V2_MAGIC -> input.readBoolean()
                else -> throw ProfileRepositoryCorruptException("activation failure type is invalid")
            }
            ActivationFailureState(sequence, candidate, reason, storageLoss, varsLoss)
        }
    }

    private fun decodeTrustQuarantine(bytes: ByteArray): TrustQuarantineState =
        decodeRecord(bytes, TRUST_QUARANTINE_MAGIC) { input ->
            val sequence = input.readLong()
            if (sequence <= 0) throw ProfileRepositoryCorruptException("quarantine activation sequence is invalid")
            val count = input.readInt()
            if (count !in 1..2) throw ProfileRepositoryCorruptException("quarantine candidate count is invalid")
            val candidates = (0 until count).map {
                val role = try {
                    QuarantinedCandidateRole.valueOf(input.readUTF())
                } catch (failure: IllegalArgumentException) {
                    throw ProfileRepositoryCorruptException("quarantine candidate role is invalid", failure)
                }
                val candidate = readCandidate(input)
                val reason = try {
                    TrustQuarantineReason.valueOf(input.readUTF())
                } catch (failure: IllegalArgumentException) {
                    throw ProfileRepositoryCorruptException("trust quarantine reason is invalid", failure)
                }
                QuarantinedProfileCandidate(role, candidate, reason)
            }
            if (candidates.map { it.role }.distinct().size != candidates.size) {
                throw ProfileRepositoryCorruptException("quarantine candidate roles are duplicated")
            }
            TrustQuarantineState(sequence, candidates)
        }

    private fun decodePendingActivation(bytes: ByteArray): PendingActivation {
        if (bytes.size < 4) throw ProfileRepositoryCorruptException("pending activation is truncated")
        return when (ByteBuffer.wrap(bytes, 0, 4).int) {
            PENDING_ACTIVATION_MAGIC -> decodeRecord(bytes, PENDING_ACTIVATION_MAGIC) { input ->
                PendingActivation(
                    readActivation(input), readStorageIdentity(input), null,
                    legacyDestructiveJournal = true,
                )
            }
            PENDING_ACTIVATION_V2_MAGIC -> decodeRecord(bytes, PENDING_ACTIVATION_V2_MAGIC) { input ->
                PendingActivation(
                    readActivation(input), readStorageIdentity(input), readStorageIdentity(input),
                    legacyDestructiveJournal = true,
                )
            }
            PENDING_ACTIVATION_V3_MAGIC -> decodeRecord(bytes, PENDING_ACTIVATION_V3_MAGIC) { input ->
                val desired = readActivation(input)
                val storage = readStorageIdentity(input)
                val vars = if (input.readBoolean()) readStorageIdentity(input) else null
                val phase = try {
                    CloudInitializationPhase.valueOf(input.readUTF())
                } catch (failure: IllegalArgumentException) {
                    throw ProfileRepositoryCorruptException("cloud initialization phase is invalid", failure)
                }
                val replacementStorage = if (input.readBoolean()) readStorageIdentity(input) else null
                val replacementVars = if (input.readBoolean()) readStorageIdentity(input) else null
                val previousActivation = if (input.readBoolean()) readActivation(input) else null
                val previousLineage = if (input.readBoolean()) readCloudLineage(input) else null
                PendingActivation(
                    desired, storage, vars, phase, replacementStorage, replacementVars,
                    previousActivation, previousLineage,
                )
            }
            else -> throw ProfileRepositoryCorruptException("pending activation type is invalid")
        }.also(::validatePendingJournal)
    }

    private fun validatePendingJournal(pending: PendingActivation) {
        if (pending.phase >= CloudInitializationPhase.STORAGE_PUBLISHED &&
            pending.replacementStorageIdentity == null
        ) throw ProfileRepositoryCorruptException("pending storage phase has no replacement identity")
        if (pending.phase >= CloudInitializationPhase.VARS_PUBLISHED &&
            pending.replacementVarsIdentity == null
        ) throw ProfileRepositoryCorruptException("pending vars phase has no replacement identity")
        if (pending.replacementVarsIdentity != null && pending.replacementStorageIdentity == null) {
            throw ProfileRepositoryCorruptException("pending vars identity precedes storage identity")
        }
        pending.previousActivation?.let {
            if (it.activationSequence >= pending.desired.activationSequence) {
                throw ProfileRepositoryCorruptException("pending predecessor activation is not older")
            }
        }
    }

    private fun readStorageIdentity(input: DataInputStream): StorageIdentity {
        val parentKey = checkedFileKey(input.readUTF())
        val parentCreationTime = checkedFileTimestamp(input.readUTF())
        val existed = input.readBoolean()
        val fileKey = if (existed) checkedFileKey(input.readUTF()) else null
        val sizeBytes = if (existed) input.readLong().also {
            if (it < 0) throw ProfileRepositoryCorruptException("stored file size is invalid")
        } else null
        val creationTime = if (existed) checkedFileTimestamp(input.readUTF()) else null
        val lastModifiedTime = if (existed) checkedFileTimestamp(input.readUTF()) else null
        return StorageIdentity(parentKey, parentCreationTime, existed, fileKey, sizeBytes, creationTime, lastModifiedTime)
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

    private fun PreparedRecord.toPublic(): PreparedProfile {
        val plan = when (planKind) {
            PreparedPlanKind.DIRECT_KERNEL_OVERLAY_V1 -> PreparedProfilePlan.DirectKernelOverlayV1(
                supportedBackends,
                artifacts.associate { ArtifactRole.fromWireName(it.role)!! to blobPath(it.sha256).toFile() },
                artifacts.associate { ArtifactRole.fromWireName(it.role)!! to it.sha256 },
            )
            PreparedPlanKind.UEFI_NOCLOUD_V1 -> PreparedProfilePlan.UefiNoCloudV1(
                supportedBackends,
                artifacts.associate { ProfileV2ArtifactRole.fromWireName(it.role)!! to blobPath(it.sha256).toFile() },
                artifacts.associate { ProfileV2ArtifactRole.fromWireName(it.role)!! to it.sha256 },
                ProfileV2Capabilities(capabilities),
                readinessMarker!!,
                fixedStorage.toFile(),
                fixedVars.toFile(),
            )
        }
        return PreparedProfile(candidate, dataCompatibility, plan)
    }

    private fun profileKey(profileId: ProfileId): String = MessageDigest.getInstance("SHA-256")
        .digest(profileId.value.toByteArray(Charsets.UTF_8)).toLowerHex()

    private fun blobPath(digest: Sha256Digest): Path = blobs.resolve("${digest.value}$BLOB_SUFFIX")
    private fun preparedPath(profileKey: String, epoch: TrustEpoch, generation: ProfileGeneration): Path =
        prepared.resolve(profileKey).resolve("${epoch.value}-${generation.value}$PREPARED_SUFFIX")
    private fun floorPath(profileKey: String): Path = state.resolve("$profileKey$FLOOR_SUFFIX")
    private fun activationPath(): Path = state.resolve(ACTIVATION_FILE)
    private fun activationFailurePath(): Path = state.resolve(ACTIVATION_FAILURE_FILE)
    private fun downloadedLineagePath(): Path = state.resolve(DOWNLOADED_LINEAGE_FILE)

    private fun downloadedLineageClaimedLocked(): Boolean {
        val marker = downloadedLineagePath()
        if (!existsNoFollow(marker)) return false
        requireRegularFile(marker, "downloaded profile lineage marker")
        if (!readBoundedRecord(marker).contentEquals(DOWNLOADED_LINEAGE_BYTES)) {
            throw ProfileRepositoryCorruptException("downloaded profile lineage marker is invalid")
        }
        return true
    }

    private fun ensureDownloadedLineageMarkerLocked() {
        if (downloadedLineageClaimedLocked()) return
        writeImmutableRecord(downloadedLineagePath(), DOWNLOADED_LINEAGE_BYTES)
    }

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

    private enum class PreparedPlanKind { DIRECT_KERNEL_OVERLAY_V1, UEFI_NOCLOUD_V1 }

    private data class PreparedArtifact(
        val role: String,
        val format: String?,
        val sha256: Sha256Digest,
        val sizeBytes: Long,
    )

    private data class PreparedRecord(
        val candidate: PreparedProfileCandidate,
        val dataCompatibility: DataCompatibilityId,
        val supportedBackends: Set<ProfileBackend>,
        val planKind: PreparedPlanKind,
        val capabilities: Set<ProfileV2GuestIntegration> = emptySet(),
        val readinessMarker: String? = null,
        val artifacts: List<PreparedArtifact>,
    )

    private data class IncomingArtifact(
        val role: String,
        val format: String?,
        val url: ArtifactDownloadUrl,
        val sha256: Sha256Digest,
        val sizeBytes: Long,
    )

    private data class FloorRecord(val candidate: PreparedProfileCandidate)

    private data class PendingActivation(
        val desired: ActivationState,
        val storageIdentity: StorageIdentity,
        val varsIdentity: StorageIdentity?,
        val phase: CloudInitializationPhase = CloudInitializationPhase.INTENT,
        val replacementStorageIdentity: StorageIdentity? = null,
        val replacementVarsIdentity: StorageIdentity? = null,
        val previousActivation: ActivationState? = null,
        val previousCloudLineage: CloudLineage? = null,
        val legacyDestructiveJournal: Boolean = false,
    )

    private enum class CloudInitializationPhase {
        INTENT,
        STORAGE_PUBLISHED,
        VARS_PUBLISHED,
        LINEAGE_PUBLISHED,
        ACTIVATION_PUBLISHED,
    }

    private data class CloudLineage(
        val dataCompatibility: DataCompatibilityId,
        val cloudDiskSha256: Sha256Digest,
        val varsTemplateSha256: Sha256Digest,
    )

    private enum class PendingRecoveryResult {
        COMPLETED,
        ABORTED,
    }

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
        const val ACTIVATION_FAILURE_FILE = "activation.failure"
        const val TRUST_QUARANTINE_FILE = "trust.quarantine"
        const val CLOUD_LINEAGE_FILE = "cloud-lineage.record"
        const val DOWNLOADED_LINEAGE_FILE = "downloaded-lineage.claimed"
        val DOWNLOADED_LINEAGE_BYTES = "podroid-downloaded-profile-lineage-v1\n".toByteArray(Charsets.US_ASCII)
        const val BLOB_SUFFIX = ".blob"
        const val PREPARED_SUFFIX = ".prepared"
        const val FLOOR_SUFFIX = ".floor"
        const val RECORD_VERSION = 3
        const val RECORD_CHECKSUM_BYTES = 32
        const val PREPARED_MAGIC = 0x50525044
        const val PREPARED_V2_MAGIC = 0x50525632
        const val CLOUD_LINEAGE_MAGIC = 0x5052434c
        const val FLOOR_MAGIC = 0x5052464c
        const val ACTIVATION_MAGIC = 0x50524143
        const val PENDING_ACTIVATION_MAGIC = 0x5052504e
        const val PENDING_ACTIVATION_V2_MAGIC = 0x50524e32
        const val PENDING_ACTIVATION_V3_MAGIC = 0x50524e33
        const val ACTIVATION_FAILURE_MAGIC = 0x50524641
        const val ACTIVATION_FAILURE_V2_MAGIC = 0x50524632
        const val TRUST_QUARANTINE_MAGIC = 0x50525154
        const val MAX_RECORD_BYTES = 16 * 1024
        const val RECORD_BUFFER_BYTES = 4 * 1024
        const val STREAM_BUFFER_BYTES = 64 * 1024
        const val MAX_ZERO_READS = 16
        const val MAX_PROFILES = 32
        const val MAX_GENERATIONS_PER_PROFILE = 64
        const val MAX_STATE_RECORDS = MAX_PROFILES + 6
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
