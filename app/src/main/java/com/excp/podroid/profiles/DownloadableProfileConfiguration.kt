package com.excp.podroid.profiles

import java.util.Base64

/** Non-sensitive immutable trust and origin inputs generated into BuildConfig. */
data class RawDownloadableProfileConfiguration(
    val signingKeyId: String,
    val ed25519X509PublicKeyBase64: String,
    val trustEpoch: String,
    val canonicalOrigins: String,
)

enum class DownloadableProfileUnavailableReason {
    NOT_CONFIGURED,
    INVALID_CONFIGURATION,
}

sealed interface DownloadableProfileAvailability {
    data object Available : DownloadableProfileAvailability
    data class Unavailable(
        val reason: DownloadableProfileUnavailableReason,
    ) : DownloadableProfileAvailability
}

internal data class ConfiguredDownloadableProfile(
    val approvedOrigins: ApprovedArtifactOrigins,
    val trustPolicy: ProfileTrustPolicy,
)

internal sealed interface DownloadableProfileConfigurationResult {
    data class Configured(val value: ConfiguredDownloadableProfile) : DownloadableProfileConfigurationResult
    data class Unavailable(
        val reason: DownloadableProfileUnavailableReason,
    ) : DownloadableProfileConfigurationResult
}

/** Parses all BuildConfig fields as one closed snapshot; partial or malformed input is unavailable. */
internal object DownloadableProfileConfigurationParser {
    private const val MAX_ORIGINS = 16
    private const val MAX_ORIGINS_CHARS = MAX_ORIGINS * (ProfileLimits.MAX_URL_CHARS + 1)
    private const val MAX_BASE64_KEY_CHARS = 128
    private const val MAX_EPOCH_CHARS = 19

    fun parse(raw: RawDownloadableProfileConfiguration): DownloadableProfileConfigurationResult {
        val fields = listOf(
            raw.signingKeyId,
            raw.ed25519X509PublicKeyBase64,
            raw.trustEpoch,
            raw.canonicalOrigins,
        )
        if (fields.all(String::isEmpty)) {
            return DownloadableProfileConfigurationResult.Unavailable(
                DownloadableProfileUnavailableReason.NOT_CONFIGURED,
            )
        }
        if (fields.any(String::isEmpty)) return invalid()
        if (raw.ed25519X509PublicKeyBase64.length > MAX_BASE64_KEY_CHARS ||
            raw.trustEpoch.length > MAX_EPOCH_CHARS ||
            raw.canonicalOrigins.length > MAX_ORIGINS_CHARS
        ) {
            return invalid()
        }

        return try {
            val keyId = SigningKeyId(raw.signingKeyId)
            require(raw.trustEpoch.all { it in '0'..'9' } && !raw.trustEpoch.startsWith('0'))
            val epoch = TrustEpoch(raw.trustEpoch.toLong())
            val encodedKey = Base64.getDecoder().decode(raw.ed25519X509PublicKeyBase64)
            require(Base64.getEncoder().encodeToString(encodedKey) == raw.ed25519X509PublicKeyBase64)
            val publicKey = Ed25519PublicKey.fromX509(encodedKey)
            val originValues = raw.canonicalOrigins.split(',')
            require(originValues.size in 1..MAX_ORIGINS && originValues.none(String::isEmpty))
            require(originValues.toSet().size == originValues.size)
            val origins = ApprovedArtifactOrigins.of(originValues)
            DownloadableProfileConfigurationResult.Configured(
                ConfiguredDownloadableProfile(
                    approvedOrigins = origins,
                    trustPolicy = ProfileTrustPolicy(
                        epoch,
                        mapOf(keyId to TrustedProfileSigningKey(publicKey)),
                    ),
                ),
            )
        } catch (_: RuntimeException) {
            invalid()
        }
    }

    private fun invalid() = DownloadableProfileConfigurationResult.Unavailable(
        DownloadableProfileUnavailableReason.INVALID_CONFIGURATION,
    )
}
