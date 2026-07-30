package com.excp.podroid.profiles

import java.net.URI
import java.util.Collections

/** Hard limits applied before a profile can cause downloads or persistent effects. */
object ProfileLimits {
    const val ENVELOPE_VERSION = 1L
    const val PAYLOAD_VERSION = 1L
    const val MAX_ENVELOPE_BYTES = 64 * 1024
    const val MAX_PAYLOAD_BYTES = 32 * 1024
    const val MAX_KEY_ID_CHARS = 64
    const val MAX_PROFILE_ID_CHARS = 64
    const val MAX_DATA_COMPATIBILITY_ID_CHARS = 64
    const val MAX_URL_CHARS = 2_048
    const val ED25519_SIGNATURE_BYTES = 64
    const val ED25519_PUBLIC_KEY_BYTES = 32
    const val ED25519_X509_PUBLIC_KEY_BYTES = 44
    const val MAX_PROFILE_GENERATION = Long.MAX_VALUE - 1L
    const val MAX_TRUST_EPOCH = Long.MAX_VALUE - 1L
    const val MAX_ARTIFACT_BYTES = 4L * 1024 * 1024 * 1024
    const val MAX_TOTAL_ARTIFACT_BYTES = 6L * 1024 * 1024 * 1024
}

@JvmInline
value class SigningKeyId(val value: String) {
    init {
        require(value.length in 1..ProfileLimits.MAX_KEY_ID_CHARS && value.matches(SAFE_ID)) {
            "key_id must be a lowercase safe identifier"
        }
    }

    private companion object {
        val SAFE_ID = Regex("[a-z0-9](?:[a-z0-9._-]*[a-z0-9])?")
    }
}

@JvmInline
value class ProfileId(val value: String) {
    init {
        require(value.length in 1..ProfileLimits.MAX_PROFILE_ID_CHARS && value.matches(SAFE_ID)) {
            "profile_id must be a lowercase safe identifier"
        }
    }

    private companion object {
        val SAFE_ID = Regex("[a-z0-9](?:[a-z0-9._-]*[a-z0-9])?")
    }
}

@JvmInline
value class DataCompatibilityId(val value: String) {
    init {
        require(value.length in 1..ProfileLimits.MAX_DATA_COMPATIBILITY_ID_CHARS && value.matches(SAFE_ID)) {
            "data_compatibility must be a lowercase safe identifier"
        }
    }

    private companion object {
        val SAFE_ID = Regex("[a-z0-9](?:[a-z0-9._-]*[a-z0-9])?")
    }
}

@JvmInline
value class ProfileGeneration(val value: Long) {
    init {
        require(value in 1..ProfileLimits.MAX_PROFILE_GENERATION) {
            "generation must be within the supported positive bound"
        }
    }
}

@JvmInline
value class TrustEpoch(val value: Long) {
    init {
        require(value in 1..ProfileLimits.MAX_TRUST_EPOCH) {
            "trust epoch must be within the supported positive bound"
        }
    }
}

@JvmInline
value class Sha256Digest(val value: String) {
    init {
        require(value.matches(LOWERCASE_SHA256)) { "sha256 must be exactly 64 lowercase hexadecimal characters" }
    }

    private companion object {
        val LOWERCASE_SHA256 = Regex("[0-9a-f]{64}")
    }
}

@JvmInline
value class ArtifactSizeBytes(val value: Long) {
    init {
        require(value in 1..ProfileLimits.MAX_ARTIFACT_BYTES) {
            "artifact size must be within the supported byte bound"
        }
    }
}

enum class ArtifactRole(val wireName: String) {
    KERNEL("kernel"),
    INITRD("initrd"),
    ROOTFS("rootfs");

    companion object {
        fun fromWireName(value: String): ArtifactRole? = entries.singleOrNull { it.wireName == value }
    }
}

enum class ProfileArchitecture(val wireName: String) {
    AARCH64("aarch64");

    companion object {
        fun fromWireName(value: String): ProfileArchitecture? = entries.singleOrNull { it.wireName == value }
    }
}

enum class ProfileBootContract(val wireName: String) {
    PODROID_DIRECT_V1("podroid-direct-v1");

    companion object {
        fun fromWireName(value: String): ProfileBootContract? = entries.singleOrNull { it.wireName == value }
    }
}

enum class ProfileStorageContract(val wireName: String) {
    PODROID_OVERLAY_EXT4_V1("podroid-overlay-ext4-v1");

    companion object {
        fun fromWireName(value: String): ProfileStorageContract? = entries.singleOrNull { it.wireName == value }
    }
}

enum class ProfileHealthContract(val wireName: String) {
    PODROID_READY_V1("podroid-ready-v1");

    companion object {
        fun fromWireName(value: String): ProfileHealthContract? = entries.singleOrNull { it.wireName == value }
    }
}

enum class ProfileBackend(val wireName: String) {
    QEMU("qemu"),
    AVF("avf");

    companion object {
        fun fromWireName(value: String): ProfileBackend? = entries.singleOrNull { it.wireName == value }
    }
}

object ProfileDataLineage {
    val BUNDLED_ALPINE = DataCompatibilityId("podroid-alpine-overlay-v1")
}

/** A URL admitted against an explicit trusted-origin policy. */
data class ArtifactDownloadUrl private constructor(val value: String) {
    internal companion object {
        fun admitted(value: String): ArtifactDownloadUrl = ArtifactDownloadUrl(value)
    }
}

/**
 * Exact origin allowlist. Both configured origins and admitted artifact URLs must use lowercase
 * DNS hosts, explicit HTTPS port 443, and no credentials, query, or fragment.
 */
class ApprovedArtifactOrigins private constructor(private val origins: Set<String>) {
    init {
        require(origins.isNotEmpty()) { "at least one artifact origin must be approved" }
    }

    fun parseUrl(value: String): ArtifactDownloadUrl {
        require(value.length in 1..ProfileLimits.MAX_URL_CHARS) { "artifact URL exceeds the length bound" }
        require(value.all { it.code in 0x21..0x7e } && '\\' !in value) { "artifact URL must be printable ASCII" }
        val uri = parseUri(value, "artifact URL")
        requireHttps443(uri, "artifact URL")
        require(uri.rawQuery == null && uri.rawFragment == null) { "artifact URL must not contain a query or fragment" }
        val rawPath = uri.rawPath
        require(!rawPath.isNullOrEmpty() && rawPath.startsWith('/')) { "artifact URL requires an absolute path" }
        require(!rawPath.contains("//")) { "artifact URL path contains an empty segment" }
        require(!ENCODED_PATH_DELIMITER.containsMatchIn(rawPath)) {
            "artifact URL path contains an encoded delimiter or control character"
        }
        require(rawPathSegmentsAreSafe(rawPath)) { "artifact URL path contains a dot segment" }
        val origin = canonicalOrigin(uri)
        require(origin in origins) { "artifact URL origin is not approved" }
        require(value == origin + rawPath) { "artifact URL must use canonical HTTPS origin syntax" }
        return ArtifactDownloadUrl.admitted(value)
    }

    companion object {
        fun of(vararg origins: String): ApprovedArtifactOrigins = of(origins.asIterable())

        fun of(origins: Iterable<String>): ApprovedArtifactOrigins {
            val admitted = origins.map { value ->
                require(value.length <= ProfileLimits.MAX_URL_CHARS) { "approved origin exceeds the length bound" }
                require(value.all { it.code in 0x21..0x7e } && '\\' !in value) {
                    "approved origin must be printable ASCII"
                }
                val uri = parseUri(value, "approved origin")
                requireHttps443(uri, "approved origin")
                require(uri.rawPath.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null) {
                    "approved origin must not contain a path, query, or fragment"
                }
                val canonical = canonicalOrigin(uri)
                require(value == canonical) { "approved origin must use canonical HTTPS origin syntax" }
                canonical
            }.toSet()
            return ApprovedArtifactOrigins(admitted)
        }

        private val DNS_HOST = Regex(
            "(?=.{1,253}\\z)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)*" +
                "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?",
        )
        private val ENCODED_PATH_DELIMITER = Regex("%(?:2f|5c|0[0-9a-f]|1[0-9a-f]|7f)", RegexOption.IGNORE_CASE)

        private fun parseUri(value: String, label: String): URI = try {
            URI(value)
        } catch (failure: Exception) {
            throw IllegalArgumentException("$label is malformed", failure)
        }

        private fun requireHttps443(uri: URI, label: String) {
            require(uri.scheme == "https" && uri.port == 443) { "$label must use HTTPS on explicit port 443" }
            require(uri.userInfo == null) { "$label must not contain user information" }
            require(uri.host != null && uri.host.matches(DNS_HOST)) { "$label must contain a lowercase DNS host" }
            require(uri.rawAuthority == "${uri.host}:443") { "$label authority is not canonical" }
        }

        private fun canonicalOrigin(uri: URI): String = "https://${uri.host}:443"

        private fun rawPathSegmentsAreSafe(rawPath: String): Boolean = rawPath.split('/').none { segment ->
            val dotDecoded = segment.replace("%2e", ".", ignoreCase = true)
            dotDecoded == "." || dotDecoded == ".."
        }
    }
}

data class ProfileArtifact(
    val role: ArtifactRole,
    val url: ArtifactDownloadUrl,
    val sha256: Sha256Digest,
    val sizeBytes: ArtifactSizeBytes,
)

class VmProfile(
    val id: ProfileId,
    val generation: ProfileGeneration,
    val dataCompatibility: DataCompatibilityId,
    val architecture: ProfileArchitecture,
    val bootContract: ProfileBootContract,
    val storageContract: ProfileStorageContract,
    val healthContract: ProfileHealthContract,
    supportedBackends: Set<ProfileBackend>,
    artifacts: List<ProfileArtifact>,
) {
    val supportedBackends: Set<ProfileBackend> = Collections.unmodifiableSet(supportedBackends.toSet())
    val artifacts: List<ProfileArtifact> = Collections.unmodifiableList(artifacts.toList())

    init {
        require(this.supportedBackends.isNotEmpty()) { "profile must support at least one known backend" }
        require(this.artifacts.size == ArtifactRole.entries.size) {
            "profile must contain exactly ${ArtifactRole.entries.size} artifacts"
        }
        require(this.artifacts.map { it.role }.toSet() == ArtifactRole.entries.toSet()) {
            "profile must contain kernel, initrd, and rootfs exactly once"
        }
        var totalBytes = 0L
        this.artifacts.forEach { artifact ->
            require(artifact.sizeBytes.value <= ProfileLimits.MAX_TOTAL_ARTIFACT_BYTES - totalBytes) {
                "profile artifact total exceeds the supported byte bound"
            }
            totalBytes += artifact.sizeBytes.value
        }
    }

    fun artifact(role: ArtifactRole): ProfileArtifact = artifacts.single { it.role == role }

    override fun equals(other: Any?): Boolean =
        other is VmProfile && id == other.id && generation == other.generation &&
            dataCompatibility == other.dataCompatibility && architecture == other.architecture &&
            bootContract == other.bootContract && storageContract == other.storageContract &&
            healthContract == other.healthContract && supportedBackends == other.supportedBackends &&
            artifacts == other.artifacts

    override fun hashCode(): Int = listOf(
        id, generation, dataCompatibility, architecture, bootContract, storageContract,
        healthContract, supportedBackends, artifacts,
    ).fold(1) { result, value -> 31 * result + value.hashCode() }

    override fun toString(): String =
        "VmProfile(id=$id, generation=$generation, dataCompatibility=$dataCompatibility, " +
            "architecture=$architecture, bootContract=$bootContract, storageContract=$storageContract, " +
            "healthContract=$healthContract, supportedBackends=$supportedBackends, artifacts=$artifacts)"
}
