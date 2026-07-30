package com.excp.podroid.profiles

import android.content.Context
import com.excp.podroid.BuildConfig
import com.excp.podroid.vm.VmPaths
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

interface ProfileLifecycleOperations {
    /** Fresh, manager-fenced authority required immediately before a destructive activation. */
    suspend fun issueDataDeletionConfirmation(
        candidate: PreparedProfileCandidate,
    ): DataDeletionConfirmation

    suspend fun activateProfile(
        candidate: PreparedProfileCandidate,
        dataPolicy: GuestDataPolicy,
        deletionConfirmation: DataDeletionConfirmation? = null,
    ): ActivationState

    suspend fun rollbackProfile(
        expectedActivationSequence: Long,
        dataPolicy: GuestDataPolicy,
    ): ActivationState
}

internal sealed interface PreparedBootContract {
    data object DirectKernelOverlayV1 : PreparedBootContract
    data object UefiNoCloudV1 : PreparedBootContract
}

internal interface ProfileLifecycleStore {
    /** Read-only contract inspection used for backend fencing before lifecycle effects. */
    suspend fun preparedBootContract(candidate: PreparedProfileCandidate): PreparedBootContract =
        PreparedBootContract.DirectKernelOverlayV1
    suspend fun issueDataDeletionConfirmation(candidate: PreparedProfileCandidate): DataDeletionConfirmation

    suspend fun install(
        candidate: PreparedProfileCandidate,
        dataPolicy: GuestDataPolicy,
        deletionConfirmation: DataDeletionConfirmation?,
    ): ActivationState

    suspend fun rollback(expectedActivationSequence: Long, dataPolicy: GuestDataPolicy): ActivationState
}

internal interface ActiveProfileRuntime {
    val availability: DownloadableProfileAvailability
    fun resolveActiveProfile(): PreparedProfile?
}

data class ProfileActivationDiagnostic(
    val availability: DownloadableProfileAvailability,
    val activation: ActivationState?,
    val lastFailure: ActivationFailureState?,
    val trustQuarantine: TrustQuarantineState?,
)

class DownloadableProfileUnavailableException(
    val reason: DownloadableProfileUnavailableReason,
) : IOException("downloadable profiles are unavailable: ${reason.name}")

/** Shared immutable trust/path environment. Opening a repository does not grant lifecycle authority. */
@Singleton
internal class DownloadableProfileEnvironment @Inject constructor(
    @ApplicationContext context: Context,
    vmPaths: VmPaths,
) {
    private val configuration = DownloadableProfileConfigurationParser.parse(
        RawDownloadableProfileConfiguration(
            signingKeyId = BuildConfig.PROFILE_SIGNING_KEY_ID,
            ed25519X509PublicKeyBase64 = BuildConfig.PROFILE_ED25519_X509_PUBLIC_KEY_BASE64,
            trustEpoch = BuildConfig.PROFILE_TRUST_EPOCH,
            canonicalOrigins = BuildConfig.PROFILE_CANONICAL_ORIGINS,
        ),
    )
    val availability: DownloadableProfileAvailability = when (configuration) {
        is DownloadableProfileConfigurationResult.Configured -> DownloadableProfileAvailability.Available
        is DownloadableProfileConfigurationResult.Unavailable ->
            DownloadableProfileAvailability.Unavailable(configuration.reason)
    }
    val approvedOrigins: ApprovedArtifactOrigins? =
        (configuration as? DownloadableProfileConfigurationResult.Configured)?.value?.approvedOrigins
    private val trustPolicy: ProfileTrustPolicy? =
        (configuration as? DownloadableProfileConfigurationResult.Configured)?.value?.trustPolicy
    private val repositoryDirectory = context.filesDir.resolve(PROFILE_STORE_DIRECTORY)
    private val storageFile = vmPaths.storageImage
    private val uefiVarsFile = vmPaths.uefiVars

    fun openConfiguredRepository(fetcher: ProfileArtifactFetcher): ProfileRepository? {
        val origins = approvedOrigins ?: return null
        val trust = trustPolicy ?: return null
        return ProfileRepository(
            repositoryDirectory = repositoryDirectory,
            storageFile = storageFile,
            approvedOrigins = origins,
            trustPolicy = trust,
            artifactFetcher = fetcher,
            directoryDurability = AndroidDirectoryDurability,
            uefiVarsFile = uefiVarsFile,
        )
    }

    fun requireBundledFallbackAllowed() {
        DownloadedProfileLineageGuard.requireBundledFallbackAllowed(repositoryDirectory.toPath())
    }

    companion object {
        const val PROFILE_STORE_DIRECTORY = "profile-store-v1"
    }
}

/**
 * The only production adapter allowed to call raw profile lifecycle mutations. It is injected
 * directly into DefaultVmManager and is deliberately not exposed as a Hilt interface binding.
 */
@Singleton
internal class ManagerProfileLifecycleStore @Inject constructor(
    environment: DownloadableProfileEnvironment,
) : ProfileLifecycleStore {
    private val repository = environment.openConfiguredRepository(HttpUrlConnectionProfileArtifactFetcher())
    private val availability = environment.availability

    override suspend fun preparedBootContract(candidate: PreparedProfileCandidate): PreparedBootContract =
        withContext(Dispatchers.IO) { requireConfigured().preparedBootContract(candidate) }

    override suspend fun issueDataDeletionConfirmation(
        candidate: PreparedProfileCandidate,
    ): DataDeletionConfirmation = withContext(Dispatchers.IO) {
        requireConfigured().issueDataDeletionConfirmation(candidate)
    }

    override suspend fun install(
        candidate: PreparedProfileCandidate,
        dataPolicy: GuestDataPolicy,
        deletionConfirmation: DataDeletionConfirmation?,
    ): ActivationState = withContext(Dispatchers.IO) {
        requireConfigured().activate(candidate, dataPolicy, deletionConfirmation)
    }

    override suspend fun rollback(
        expectedActivationSequence: Long,
        dataPolicy: GuestDataPolicy,
    ): ActivationState = withContext(Dispatchers.IO) {
        requireConfigured().rollback(expectedActivationSequence, dataPolicy)
    }

    private fun requireConfigured(): ProfileRepository = repository ?: run {
        val unavailable = availability as DownloadableProfileAvailability.Unavailable
        throw DownloadableProfileUnavailableException(unavailable.reason)
    }
}

/** Application-lifetime read/prepare facade. Lifecycle mutation authority is intentionally absent. */
@Singleton
class DownloadableProfileRuntime @Inject internal constructor(
    private val environment: DownloadableProfileEnvironment,
) : ActiveProfileRuntime, ProfilePreparationOperations {
    override val availability: DownloadableProfileAvailability = environment.availability
    private val fetcher = HttpUrlConnectionProfileArtifactFetcher()
    private val repository = environment.openConfiguredRepository(fetcher)

    /** Downloads only signed metadata/artifacts; preparation never mutates activation state. */
    override suspend fun prepareEnvelopeUrl(url: String): PreparedProfile = runInterruptible(Dispatchers.IO) {
        val origins = environment.approvedOrigins ?: throw unavailable()
        val configuredRepository = repository ?: throw unavailable()
        val admittedUrl = origins.parseUrl(url)
        val deadlineNanos = deadlineAfterMillis(ENVELOPE_FETCH_TIMEOUT_MILLIS)
        val request = ArtifactFetchRequest(
            url = admittedUrl,
            maxResponseBytes = ProfileLimits.MAX_ENVELOPE_BYTES.toLong() + 1L,
            deadlineNanos = deadlineNanos,
        )
        val response = try {
            fetcher.fetch(request)
        } catch (failure: IOException) {
            throw ProfileDownloadException("profile envelope fetch failed", failure)
        }
        val envelope = response.use {
            validateEnvelopeResponse(request, response)
            readEnvelopeMaxPlusOne(response.body, deadlineNanos)
        }
        configuredRepository.prepare(envelope)
    }

    override suspend fun diagnosticActivationState(): ProfileActivationDiagnostic {
        val configuredRepository = repository
            ?: return ProfileActivationDiagnostic(availability, null, null, null)
        return withContext(Dispatchers.IO) {
            ProfileActivationDiagnostic(
                availability = availability,
                activation = configuredRepository.activationState(),
                lastFailure = configuredRepository.lastActivationFailure(),
                trustQuarantine = configuredRepository.lastTrustQuarantine(),
            )
        }
    }

    override fun resolveActiveProfile(): PreparedProfile? {
        val configuredRepository = repository
        if (configuredRepository == null) {
            environment.requireBundledFallbackAllowed()
            return null
        }
        return configuredRepository.resolveActiveProfile()
    }

    private fun unavailable(): DownloadableProfileUnavailableException {
        val unavailable = availability as DownloadableProfileAvailability.Unavailable
        return DownloadableProfileUnavailableException(unavailable.reason)
    }

    private fun validateEnvelopeResponse(request: ArtifactFetchRequest, response: ArtifactFetchResponse) {
        if (response.statusCode != 200) throw ProfileDownloadException("profile envelope response status is not 200")
        if (response.redirectCount != 0 || response.finalUrl != request.url.value) {
            throw ProfileDownloadException("profile envelope redirects or final URL changes are forbidden")
        }
        if (response.contentEncoding != null) {
            throw ProfileDownloadException("profile envelope content encoding is forbidden")
        }
        if (response.contentLengthBytes != null &&
            response.contentLengthBytes > ProfileLimits.MAX_ENVELOPE_BYTES
        ) {
            throw ProfileDownloadException("profile envelope Content-Length exceeds the byte bound")
        }
        if (request.deadlineNanos - System.nanoTime() <= 0L) {
            throw ProfileDownloadException("profile envelope fetch deadline expired before reading")
        }
    }

    private fun readEnvelopeMaxPlusOne(input: InputStream, deadlineNanos: Long): ByteArray {
        val output = ByteArrayOutputStream(ProfileLimits.MAX_ENVELOPE_BYTES)
        val buffer = ByteArray(8 * 1024)
        val maxPlusOne = ProfileLimits.MAX_ENVELOPE_BYTES + 1
        var total = 0
        var zeroReads = 0
        while (total < maxPlusOne) {
            val count = try {
                input.read(buffer, 0, minOf(buffer.size, maxPlusOne - total))
            } catch (failure: IOException) {
                throw ProfileDownloadException("profile envelope read failed", failure)
            }
            if (deadlineNanos - System.nanoTime() <= 0L) {
                throw ProfileDownloadException("profile envelope fetch deadline expired")
            }
            if (count < 0) break
            if (count == 0) {
                zeroReads++
                if (zeroReads > MAX_ZERO_READS) {
                    throw ProfileDownloadException("profile envelope read made no progress")
                }
                continue
            }
            zeroReads = 0
            output.write(buffer, 0, count)
            total += count
        }
        if (total > ProfileLimits.MAX_ENVELOPE_BYTES) {
            throw ProfileDownloadException("profile envelope exceeds the byte bound")
        }
        if (total == 0) throw ProfileDownloadException("profile envelope is empty")
        return output.toByteArray()
    }

    private fun deadlineAfterMillis(timeoutMillis: Long): Long {
        val now = System.nanoTime()
        val timeoutNanos = timeoutMillis * 1_000_000L
        return if (Long.MAX_VALUE - now < timeoutNanos) Long.MAX_VALUE else now + timeoutNanos
    }

    private companion object {
        const val ENVELOPE_FETCH_TIMEOUT_MILLIS = 60_000L
        const val MAX_ZERO_READS = 8
    }
}

internal object DownloadedProfileLineageGuard {
    private const val STATE_DIRECTORY = "state"
    private const val MARKER_FILE = "downloaded-lineage.claimed"
    private val MARKER_BYTES = "podroid-downloaded-profile-lineage-v1\n".toByteArray(Charsets.US_ASCII)

    fun requireBundledFallbackAllowed(repositoryDirectory: Path) {
        val root = repositoryDirectory.toAbsolutePath().normalize()
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        requireDirectoryNoFollow(root, "profile repository")
        val state = root.resolve(STATE_DIRECTORY)
        if (!Files.exists(state, LinkOption.NOFOLLOW_LINKS)) return
        requireDirectoryNoFollow(state, "profile state")
        val marker = state.resolve(MARKER_FILE)
        if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) return
        val actual = try {
            FileChannel.open(marker, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                if (channel.size() != MARKER_BYTES.size.toLong()) {
                    throw ProfileRepositoryCorruptException("downloaded profile lineage marker is invalid")
                }
                val buffer = ByteBuffer.allocate(MARKER_BYTES.size)
                var zeroReads = 0
                while (buffer.hasRemaining()) {
                    val count = channel.read(buffer)
                    if (count < 0) break
                    if (count == 0) {
                        zeroReads++
                        if (zeroReads > 8) {
                            throw ProfileRepositoryCorruptException("downloaded profile lineage marker made no progress")
                        }
                    } else {
                        zeroReads = 0
                    }
                }
                if (buffer.hasRemaining()) {
                    throw ProfileRepositoryCorruptException("downloaded profile lineage marker is truncated")
                }
                buffer.array()
            }
        } catch (failure: ProfileRepositoryCorruptException) {
            throw failure
        } catch (failure: IOException) {
            throw ProfileRepositoryCorruptException("downloaded profile lineage marker is invalid", failure)
        }
        if (!actual.contentEquals(MARKER_BYTES)) {
            throw ProfileRepositoryCorruptException("downloaded profile lineage marker is invalid")
        }
        throw ProfileActivationException(
            "bundled fallback is blocked because downloaded profile lineage was previously claimed",
        )
    }

    private fun requireDirectoryNoFollow(path: Path, label: String) {
        val attributes = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!attributes.isDirectory || attributes.isSymbolicLink) {
            throw ProfileRepositoryCorruptException("$label is not a fixed directory")
        }
    }
}
