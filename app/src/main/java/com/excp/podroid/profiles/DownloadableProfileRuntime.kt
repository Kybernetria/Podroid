package com.excp.podroid.profiles

import android.content.Context
import com.excp.podroid.BuildConfig
import com.excp.podroid.vm.VmPaths
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

interface ProfileLifecycleOperations {
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

internal interface ProfileLifecycleStore {
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

/** Application-lifetime owner of the one configured profile repository and its fixed storage target. */
@Singleton
class DownloadableProfileRuntime @Inject constructor(
    @ApplicationContext context: Context,
    vmPaths: VmPaths,
) : ProfileLifecycleStore, ActiveProfileRuntime {
    private val configuration = DownloadableProfileConfigurationParser.parse(
        RawDownloadableProfileConfiguration(
            signingKeyId = BuildConfig.PROFILE_SIGNING_KEY_ID,
            ed25519X509PublicKeyBase64 = BuildConfig.PROFILE_ED25519_X509_PUBLIC_KEY_BASE64,
            trustEpoch = BuildConfig.PROFILE_TRUST_EPOCH,
            canonicalOrigins = BuildConfig.PROFILE_CANONICAL_ORIGINS,
        ),
    )
    override val availability: DownloadableProfileAvailability = when (configuration) {
        is DownloadableProfileConfigurationResult.Configured -> DownloadableProfileAvailability.Available
        is DownloadableProfileConfigurationResult.Unavailable ->
            DownloadableProfileAvailability.Unavailable(configuration.reason)
    }
    private val configured: ConfiguredRuntime? =
        (configuration as? DownloadableProfileConfigurationResult.Configured)?.value?.let { parsed ->
            val fetcher = HttpUrlConnectionProfileArtifactFetcher()
            ConfiguredRuntime(
                approvedOrigins = parsed.approvedOrigins,
                fetcher = fetcher,
                repository = ProfileRepository(
                    repositoryDirectory = context.filesDir.resolve(PROFILE_STORE_DIRECTORY),
                    storageFile = vmPaths.storageImage,
                    approvedOrigins = parsed.approvedOrigins,
                    trustPolicy = parsed.trustPolicy,
                    artifactFetcher = fetcher,
                    directoryDurability = AndroidDirectoryDurability,
                ),
            )
        }

    /** Downloads only signed metadata; preparation never mutates activation state. */
    suspend fun prepareEnvelopeUrl(url: String): PreparedProfile = runInterruptible(Dispatchers.IO) {
        val runtime = requireConfigured()
        val admittedUrl = runtime.approvedOrigins.parseUrl(url)
        val deadlineNanos = deadlineAfterMillis(ENVELOPE_FETCH_TIMEOUT_MILLIS)
        val request = ArtifactFetchRequest(
            url = admittedUrl,
            maxResponseBytes = ProfileLimits.MAX_ENVELOPE_BYTES.toLong() + 1L,
            deadlineNanos = deadlineNanos,
        )
        val response = try {
            runtime.fetcher.fetch(request)
        } catch (failure: IOException) {
            throw ProfileDownloadException("profile envelope fetch failed", failure)
        }
        val envelope = response.use {
            validateEnvelopeResponse(request, response)
            readEnvelopeMaxPlusOne(response.body, deadlineNanos)
        }
        runtime.repository.prepare(envelope)
    }

    override suspend fun install(
        candidate: PreparedProfileCandidate,
        dataPolicy: GuestDataPolicy,
        deletionConfirmation: DataDeletionConfirmation?,
    ): ActivationState = withContext(Dispatchers.IO) {
        requireConfigured().repository.activate(candidate, dataPolicy, deletionConfirmation)
    }

    override suspend fun rollback(
        expectedActivationSequence: Long,
        dataPolicy: GuestDataPolicy,
    ): ActivationState = withContext(Dispatchers.IO) {
        requireConfigured().repository.rollback(expectedActivationSequence, dataPolicy)
    }

    suspend fun issueDataDeletionConfirmation(
        candidate: PreparedProfileCandidate,
    ): DataDeletionConfirmation = withContext(Dispatchers.IO) {
        requireConfigured().repository.issueDataDeletionConfirmation(candidate)
    }

    suspend fun diagnosticActivationState(): ProfileActivationDiagnostic {
        val runtime = configured ?: return ProfileActivationDiagnostic(availability, null, null, null)
        return withContext(Dispatchers.IO) {
            ProfileActivationDiagnostic(
                availability = availability,
                activation = runtime.repository.activationState(),
                lastFailure = runtime.repository.lastActivationFailure(),
                trustQuarantine = runtime.repository.lastTrustQuarantine(),
            )
        }
    }

    suspend fun collectGarbage(): ProfileGarbageCollectionResult = withContext(Dispatchers.IO) {
        requireConfigured().repository.collectGarbage()
    }

    override fun resolveActiveProfile(): PreparedProfile? =
        requireConfigured().repository.resolveActiveProfile()

    private fun requireConfigured(): ConfiguredRuntime = configured ?: run {
        val unavailable = availability as DownloadableProfileAvailability.Unavailable
        throw DownloadableProfileUnavailableException(unavailable.reason)
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

    private data class ConfiguredRuntime(
        val approvedOrigins: ApprovedArtifactOrigins,
        val fetcher: ProfileArtifactFetcher,
        val repository: ProfileRepository,
    )

    private companion object {
        const val PROFILE_STORE_DIRECTORY = "profile-store-v1"
        const val ENVELOPE_FETCH_TIMEOUT_MILLIS = 60_000L
        const val MAX_ZERO_READS = 8
    }
}
