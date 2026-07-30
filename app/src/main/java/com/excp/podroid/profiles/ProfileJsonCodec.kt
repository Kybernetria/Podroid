package com.excp.podroid.profiles

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64

open class ProfileCodecException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

class UnsupportedProfileVersionException(message: String) : ProfileCodecException(message)
class InvalidProfileSignatureException(message: String) : ProfileCodecException(message)

/** A bounded signed envelope retaining the exact payload bytes covered by the signature. */
class SignedProfileEnvelope internal constructor(
    val keyId: SigningKeyId,
    payloadBytes: ByteArray,
    signatureBytes: ByteArray,
) {
    private val payload = payloadBytes.copyOf()
    private val signature = signatureBytes.copyOf()

    fun payloadBytes(): ByteArray = payload.copyOf()
    fun signatureBytes(): ByteArray = signature.copyOf()
}

object ProfileSigning {
    const val DOMAIN_SEPARATOR = "com.excp.podroid.vm-profile.v1\u0000"

    fun messageFor(payloadBytes: ByteArray): ByteArray {
        require(payloadBytes.size in 1..ProfileLimits.MAX_PAYLOAD_BYTES) {
            "profile payload is outside the supported byte bound"
        }
        val domain = DOMAIN_SEPARATOR.toByteArray(Charsets.US_ASCII)
        return ByteArray(domain.size + payloadBytes.size).also { message ->
            domain.copyInto(message)
            payloadBytes.copyInto(message, domain.size)
        }
    }
}

object ProfilePayloadJsonCodec {
    fun decode(payloadBytes: ByteArray, approvedOrigins: ApprovedArtifactOrigins): VmProfile {
        val root = parseObject(payloadBytes, ProfileLimits.MAX_PAYLOAD_BYTES, "profile payload")
        requireExactFields(root, PAYLOAD_FIELDS, "profile payload")
        requireVersion(root, ProfileLimits.PAYLOAD_VERSION, "profile payload")

        val profileId = root.requiredString("profile_id", "profile payload")
        val generation = root.requiredLong("generation", "profile payload")
        val dataCompatibility = root.requiredString("data_compatibility", "profile payload")
        val architecture = root.requiredString("architecture", "profile payload")
        val bootContract = root.requiredString("boot_contract", "profile payload")
        val storageContract = root.requiredString("storage_contract", "profile payload")
        val healthContract = root.requiredString("health_contract", "profile payload")
        val supportedBackendNames = root.requiredStringArray("supported_backends", "profile payload")
        if (supportedBackendNames.isEmpty() || supportedBackendNames.distinct().size != supportedBackendNames.size) {
            throw ProfileCodecException("supported_backends must be a nonempty set")
        }
        val artifactsValue = root["artifacts"] as? JsonValue.ArrayValue
            ?: throw ProfileCodecException("artifacts must be an array")
        if (artifactsValue.value.size != ArtifactRole.entries.size) {
            throw ProfileCodecException("artifacts must contain exactly ${ArtifactRole.entries.size} entries")
        }

        return codecBoundary {
            val artifacts = artifactsValue.value.mapIndexed { index, value ->
                val artifact = (value as? JsonValue.ObjectValue)?.value
                    ?: throw ProfileCodecException("artifact[$index] must be an object")
                requireExactFields(artifact, ARTIFACT_FIELDS, "artifact[$index]")
                val roleName = artifact.requiredString("role", "artifact[$index]")
                val role = ArtifactRole.fromWireName(roleName)
                    ?: throw ProfileCodecException("artifact[$index] has an unknown role")
                ProfileArtifact(
                    role = role,
                    url = approvedOrigins.parseUrl(artifact.requiredString("url", "artifact[$index]")),
                    sha256 = Sha256Digest(artifact.requiredString("sha256", "artifact[$index]")),
                    sizeBytes = ArtifactSizeBytes(artifact.requiredLong("size_bytes", "artifact[$index]")),
                )
            }
            VmProfile(
                id = ProfileId(profileId),
                generation = ProfileGeneration(generation),
                dataCompatibility = DataCompatibilityId(dataCompatibility),
                architecture = ProfileArchitecture.fromWireName(architecture)
                    ?: throw ProfileCodecException("architecture is unsupported"),
                bootContract = ProfileBootContract.fromWireName(bootContract)
                    ?: throw ProfileCodecException("boot_contract is unsupported"),
                storageContract = ProfileStorageContract.fromWireName(storageContract)
                    ?: throw ProfileCodecException("storage_contract is unsupported"),
                healthContract = ProfileHealthContract.fromWireName(healthContract)
                    ?: throw ProfileCodecException("health_contract is unsupported"),
                supportedBackends = supportedBackendNames.map { backend ->
                    ProfileBackend.fromWireName(backend)
                        ?: throw ProfileCodecException("supported_backends contains an unknown backend")
                }.toSet(),
                artifacts = artifacts,
            )
        }
    }

    fun encode(profile: VmProfile): ByteArray {
        val artifacts = profile.artifacts.sortedBy { it.role.ordinal }.joinToString(",") { artifact ->
            "{" +
                "\"role\":${jsonString(artifact.role.wireName)}," +
                "\"url\":${jsonString(artifact.url.value)}," +
                "\"sha256\":${jsonString(artifact.sha256.value)}," +
                "\"size_bytes\":${artifact.sizeBytes.value}" +
                "}"
        }
        val encoded = ("{" +
            "\"version\":${ProfileLimits.PAYLOAD_VERSION}," +
            "\"profile_id\":${jsonString(profile.id.value)}," +
            "\"generation\":${profile.generation.value}," +
            "\"data_compatibility\":${jsonString(profile.dataCompatibility.value)}," +
            "\"architecture\":${jsonString(profile.architecture.wireName)}," +
            "\"boot_contract\":${jsonString(profile.bootContract.wireName)}," +
            "\"storage_contract\":${jsonString(profile.storageContract.wireName)}," +
            "\"health_contract\":${jsonString(profile.healthContract.wireName)}," +
            "\"supported_backends\":[${profile.supportedBackends.sortedBy { it.ordinal }.joinToString(",") { jsonString(it.wireName) }}]," +
            "\"artifacts\":[$artifacts]" +
            "}").toByteArray(Charsets.UTF_8)
        require(encoded.size <= ProfileLimits.MAX_PAYLOAD_BYTES) { "encoded profile payload exceeds the byte bound" }
        return encoded
    }

    private val PAYLOAD_FIELDS = setOf(
        "version", "profile_id", "generation", "data_compatibility", "architecture",
        "boot_contract", "storage_contract", "health_contract", "supported_backends", "artifacts",
    )
    private val ARTIFACT_FIELDS = setOf("role", "url", "sha256", "size_bytes")
}

object ProfileSigningV2 {
    const val ENVELOPE_VERSION = 2L
    const val DOMAIN_ID = "com.excp.podroid.vm-profile.v2"
    const val DOMAIN_SEPARATOR = "$DOMAIN_ID\u0000"

    fun messageFor(payloadBytes: ByteArray): ByteArray {
        require(payloadBytes.size in 1..ProfileLimits.MAX_PAYLOAD_BYTES) {
            "profile v2 payload is outside the supported byte bound"
        }
        val domain = DOMAIN_SEPARATOR.toByteArray(Charsets.US_ASCII)
        return ByteArray(domain.size + payloadBytes.size).also { message ->
            domain.copyInto(message)
            payloadBytes.copyInto(message, domain.size)
        }
    }
}

/** Strict codec for the separate QEMU UEFI/NoCloud payload v2 contract. */
object ProfilePayloadV2JsonCodec {
    fun decode(payloadBytes: ByteArray, approvedOrigins: ApprovedArtifactOrigins): VmProfileV2 {
        val root = parseObject(payloadBytes, ProfileLimits.MAX_PAYLOAD_BYTES, "profile v2 payload")
        requireExactFields(root, PAYLOAD_FIELDS, "profile v2 payload")
        requireVersion(root, ProfileV2Limits.PAYLOAD_VERSION, "profile v2 payload")

        val backendNames = root.requiredStringArray("supported_backends", "profile v2 payload")
        if (backendNames.distinct().size != backendNames.size) {
            throw ProfileCodecException("profile v2 supported_backends must be a set")
        }
        val capabilitiesObject = (root["capabilities"] as? JsonValue.ObjectValue)?.value
            ?: throw ProfileCodecException("profile v2 capabilities must be an object")
        requireExactFields(capabilitiesObject, CAPABILITIES_FIELDS, "profile v2 capabilities")
        val integrationNames = capabilitiesObject.requiredStringArray(
            "guest_integrations",
            "profile v2 capabilities",
        )
        if (integrationNames.distinct().size != integrationNames.size) {
            throw ProfileCodecException("profile v2 guest_integrations must be a set")
        }
        val artifactsValue = root["artifacts"] as? JsonValue.ArrayValue
            ?: throw ProfileCodecException("profile v2 artifacts must be an array")
        if (artifactsValue.value.size != ProfileV2ArtifactRole.entries.size) {
            throw ProfileCodecException(
                "profile v2 artifacts must contain exactly ${ProfileV2ArtifactRole.entries.size} entries",
            )
        }

        return codecBoundary {
            val artifacts = artifactsValue.value.mapIndexed { index, value ->
                val artifact = (value as? JsonValue.ObjectValue)?.value
                    ?: throw ProfileCodecException("profile v2 artifact[$index] must be an object")
                requireExactFields(artifact, ARTIFACT_FIELDS, "profile v2 artifact[$index]")
                val role = ProfileV2ArtifactRole.fromWireName(
                    artifact.requiredString("role", "profile v2 artifact[$index]"),
                ) ?: throw ProfileCodecException("profile v2 artifact[$index] has an unknown role")
                val format = ProfileV2ArtifactFormat.fromWireName(
                    artifact.requiredString("format", "profile v2 artifact[$index]"),
                ) ?: throw ProfileCodecException("profile v2 artifact[$index] has an unknown format")
                ProfileV2Artifact(
                    role = role,
                    format = format,
                    url = approvedOrigins.parseUrl(
                        artifact.requiredString("url", "profile v2 artifact[$index]"),
                    ),
                    sha256 = Sha256Digest(
                        artifact.requiredString("sha256", "profile v2 artifact[$index]"),
                    ),
                    sizeBytes = artifact.requiredLong("size_bytes", "profile v2 artifact[$index]"),
                )
            }
            VmProfileV2(
                id = ProfileId(root.requiredString("profile_id", "profile v2 payload")),
                generation = ProfileGeneration(root.requiredLong("generation", "profile v2 payload")),
                dataCompatibility = DataCompatibilityId(
                    root.requiredString("data_compatibility", "profile v2 payload"),
                ),
                architecture = ProfileArchitecture.fromWireName(
                    root.requiredString("architecture", "profile v2 payload"),
                ) ?: throw ProfileCodecException("profile v2 architecture is unsupported"),
                bootContract = root.requiredString("boot_contract", "profile v2 payload"),
                storageContract = root.requiredString("storage_contract", "profile v2 payload"),
                healthContract = root.requiredString("health_contract", "profile v2 payload"),
                readinessMarker = root.requiredString("readiness_marker", "profile v2 payload"),
                supportedBackends = backendNames.map { backend ->
                    ProfileBackend.fromWireName(backend)
                        ?: throw ProfileCodecException("profile v2 supported_backends contains an unknown backend")
                }.toSet(),
                capabilities = ProfileV2Capabilities(integrationNames.map { integration ->
                    ProfileV2GuestIntegration.fromWireName(integration)
                        ?: throw ProfileCodecException("profile v2 capabilities contains an unknown guest integration")
                }.toSet()),
                artifacts = artifacts,
            )
        }
    }

    fun encode(profile: VmProfileV2): ByteArray {
        val artifacts = profile.artifacts.sortedBy { it.role.ordinal }.joinToString(",") { artifact ->
            "{" +
                "\"role\":${jsonString(artifact.role.wireName)}," +
                "\"format\":${jsonString(artifact.format.wireName)}," +
                "\"url\":${jsonString(artifact.url.value)}," +
                "\"sha256\":${jsonString(artifact.sha256.value)}," +
                "\"size_bytes\":${artifact.sizeBytes}" +
                "}"
        }
        val integrations = profile.capabilities.guestIntegrations.sortedBy { it.ordinal }
            .joinToString(",") { jsonString(it.wireName) }
        val encoded = ("{" +
            "\"version\":${ProfileV2Limits.PAYLOAD_VERSION}," +
            "\"profile_id\":${jsonString(profile.id.value)}," +
            "\"generation\":${profile.generation.value}," +
            "\"data_compatibility\":${jsonString(profile.dataCompatibility.value)}," +
            "\"architecture\":${jsonString(profile.architecture.wireName)}," +
            "\"boot_contract\":${jsonString(profile.bootContract)}," +
            "\"storage_contract\":${jsonString(profile.storageContract)}," +
            "\"health_contract\":${jsonString(profile.healthContract)}," +
            "\"readiness_marker\":${jsonString(profile.readinessMarker)}," +
            "\"supported_backends\":[${profile.supportedBackends.sortedBy { it.ordinal }.joinToString(",") { jsonString(it.wireName) }}]," +
            "\"capabilities\":{\"guest_integrations\":[$integrations]}," +
            "\"artifacts\":[$artifacts]" +
            "}").toByteArray(Charsets.UTF_8)
        require(encoded.size <= ProfileLimits.MAX_PAYLOAD_BYTES) {
            "encoded profile v2 payload exceeds the byte bound"
        }
        return encoded
    }

    private val PAYLOAD_FIELDS = setOf(
        "version", "profile_id", "generation", "data_compatibility", "architecture",
        "boot_contract", "storage_contract", "health_contract", "readiness_marker",
        "supported_backends", "capabilities", "artifacts",
    )
    private val CAPABILITIES_FIELDS = setOf("guest_integrations")
    private val ARTIFACT_FIELDS = setOf("role", "format", "url", "sha256", "size_bytes")
}

object SignedProfileEnvelopeJsonCodec {
    fun decode(envelopeBytes: ByteArray): SignedProfileEnvelope {
        val root = parseObject(envelopeBytes, ProfileLimits.MAX_ENVELOPE_BYTES, "signed profile envelope")
        requireExactFields(root, ENVELOPE_FIELDS, "signed profile envelope")
        requireVersion(root, ProfileLimits.ENVELOPE_VERSION, "signed profile envelope")

        return codecBoundary {
            val keyId = SigningKeyId(root.requiredString("key_id", "signed profile envelope"))
            val payload = decodeCanonicalBase64(
                root.requiredString("payload", "signed profile envelope"),
                maxEncodedChars = ((ProfileLimits.MAX_PAYLOAD_BYTES + 2) / 3) * 4,
                label = "payload",
            )
            require(payload.size in 1..ProfileLimits.MAX_PAYLOAD_BYTES) {
                "payload is outside the supported byte bound"
            }
            val signature = decodeCanonicalBase64(
                root.requiredString("signature", "signed profile envelope"),
                maxEncodedChars = ((ProfileLimits.ED25519_SIGNATURE_BYTES + 2) / 3) * 4,
                label = "signature",
            )
            require(signature.size == ProfileLimits.ED25519_SIGNATURE_BYTES) {
                "signature must decode to exactly ${ProfileLimits.ED25519_SIGNATURE_BYTES} bytes"
            }
            SignedProfileEnvelope(keyId, payload, signature)
        }
    }

    fun encode(keyId: SigningKeyId, payloadBytes: ByteArray, signatureBytes: ByteArray): ByteArray {
        require(payloadBytes.size in 1..ProfileLimits.MAX_PAYLOAD_BYTES) {
            "payload is outside the supported byte bound"
        }
        require(signatureBytes.size == ProfileLimits.ED25519_SIGNATURE_BYTES) {
            "signature must be exactly ${ProfileLimits.ED25519_SIGNATURE_BYTES} bytes"
        }
        val base64 = Base64.getEncoder()
        val encoded = ("{" +
            "\"version\":${ProfileLimits.ENVELOPE_VERSION}," +
            "\"key_id\":${jsonString(keyId.value)}," +
            "\"payload\":${jsonString(base64.encodeToString(payloadBytes))}," +
            "\"signature\":${jsonString(base64.encodeToString(signatureBytes))}" +
            "}").toByteArray(Charsets.UTF_8)
        require(encoded.size <= ProfileLimits.MAX_ENVELOPE_BYTES) { "encoded envelope exceeds the byte bound" }
        return encoded
    }

    private val ENVELOPE_FIELDS = setOf("version", "key_id", "payload", "signature")
}

/** V2 framing makes both the envelope version and signing domain explicit. */
object SignedProfileEnvelopeV2JsonCodec {
    fun decode(envelopeBytes: ByteArray): SignedProfileEnvelope {
        val root = parseObject(envelopeBytes, ProfileLimits.MAX_ENVELOPE_BYTES, "signed profile v2 envelope")
        requireExactFields(root, ENVELOPE_FIELDS, "signed profile v2 envelope")
        requireVersion(root, ProfileSigningV2.ENVELOPE_VERSION, "signed profile v2 envelope")
        if (root.requiredString("signing_domain", "signed profile v2 envelope") != ProfileSigningV2.DOMAIN_ID) {
            throw ProfileCodecException("signed profile v2 envelope signing domain is unsupported")
        }
        return decodeEnvelopeValues(root, "signed profile v2 envelope")
    }

    fun encode(keyId: SigningKeyId, payloadBytes: ByteArray, signatureBytes: ByteArray): ByteArray {
        require(payloadBytes.size in 1..ProfileLimits.MAX_PAYLOAD_BYTES)
        require(signatureBytes.size == ProfileLimits.ED25519_SIGNATURE_BYTES)
        val base64 = Base64.getEncoder()
        val encoded = ("{" +
            "\"version\":${ProfileSigningV2.ENVELOPE_VERSION}," +
            "\"signing_domain\":${jsonString(ProfileSigningV2.DOMAIN_ID)}," +
            "\"key_id\":${jsonString(keyId.value)}," +
            "\"payload\":${jsonString(base64.encodeToString(payloadBytes))}," +
            "\"signature\":${jsonString(base64.encodeToString(signatureBytes))}" +
            "}").toByteArray(Charsets.UTF_8)
        require(encoded.size <= ProfileLimits.MAX_ENVELOPE_BYTES)
        return encoded
    }

    private val ENVELOPE_FIELDS = setOf("version", "signing_domain", "key_id", "payload", "signature")
}

private fun decodeEnvelopeValues(root: Map<String, JsonValue>, label: String): SignedProfileEnvelope = codecBoundary {
    val keyId = SigningKeyId(root.requiredString("key_id", label))
    val payload = decodeCanonicalBase64(
        root.requiredString("payload", label),
        ((ProfileLimits.MAX_PAYLOAD_BYTES + 2) / 3) * 4,
        "payload",
    )
    require(payload.size in 1..ProfileLimits.MAX_PAYLOAD_BYTES)
    val signature = decodeCanonicalBase64(
        root.requiredString("signature", label),
        ((ProfileLimits.ED25519_SIGNATURE_BYTES + 2) / 3) * 4,
        "signature",
    )
    require(signature.size == ProfileLimits.ED25519_SIGNATURE_BYTES)
    SignedProfileEnvelope(keyId, payload, signature)
}

/** Closed discriminator; payload bytes never choose their verification domain. */
internal fun signedProfileEnvelopeVersion(envelopeBytes: ByteArray): Long {
    val root = parseObject(envelopeBytes, ProfileLimits.MAX_ENVELOPE_BYTES, "signed profile envelope")
    return (root["version"] as? JsonValue.NumberValue)?.value
        ?: throw ProfileCodecException("signed profile envelope version must be an integer")
}

sealed interface VerifiedProfileManifestAny {
    val manifestSha256: Sha256Digest
    val signingKeyId: SigningKeyId
    val signingKeyFingerprint: Sha256Digest
    val trustEpoch: TrustEpoch

    data class Direct(val value: VerifiedProfileManifest) : VerifiedProfileManifestAny {
        override val manifestSha256 get() = value.manifestSha256
        override val signingKeyId get() = value.signingKeyId
        override val signingKeyFingerprint get() = value.signingKeyFingerprint
        override val trustEpoch get() = value.trustEpoch
    }

    data class UefiNoCloud(val value: VerifiedProfileV2Manifest) : VerifiedProfileManifestAny {
        override val manifestSha256 get() = value.manifestSha256
        override val signingKeyId get() = value.signingKeyId
        override val signingKeyFingerprint get() = value.signingKeyFingerprint
        override val trustEpoch get() = value.trustEpoch
    }
}

object VerifiedProfileEnvelopeJsonCodec {
    fun decodeManifest(
        envelopeBytes: ByteArray,
        approvedOrigins: ApprovedArtifactOrigins,
        trustPolicy: ProfileTrustPolicy,
        verifier: Ed25519Verifier = TinkEd25519Verifier,
    ): VerifiedProfileManifestAny = when (signedProfileEnvelopeVersion(envelopeBytes)) {
        ProfileLimits.ENVELOPE_VERSION -> VerifiedProfileManifestAny.Direct(
            VerifiedProfileJsonCodec.decodeManifest(envelopeBytes, approvedOrigins, trustPolicy, verifier),
        )
        ProfileSigningV2.ENVELOPE_VERSION -> VerifiedProfileManifestAny.UefiNoCloud(
            VerifiedProfileV2JsonCodec.decodeManifest(envelopeBytes, approvedOrigins, trustPolicy, verifier),
        )
        else -> throw UnsupportedProfileVersionException("unsupported signed profile envelope version")
    }
}

data class VerifiedProfileManifest(
    val profile: VmProfile,
    /** SHA-256 of the exact signed payload bytes, used as the manifest identity. */
    val manifestSha256: Sha256Digest,
    val signingKeyId: SigningKeyId,
    val signingKeyFingerprint: Sha256Digest,
    val trustEpoch: TrustEpoch,
)

/** Verifies before payload parsing and retains the APK trust decision needed for later revalidation. */
object VerifiedProfileJsonCodec {
    fun decode(
        envelopeBytes: ByteArray,
        approvedOrigins: ApprovedArtifactOrigins,
        trustPolicy: ProfileTrustPolicy,
        verifier: Ed25519Verifier = TinkEd25519Verifier,
    ): VmProfile = decodeManifest(envelopeBytes, approvedOrigins, trustPolicy, verifier).profile

    fun decodeManifest(
        envelopeBytes: ByteArray,
        approvedOrigins: ApprovedArtifactOrigins,
        trustPolicy: ProfileTrustPolicy,
        verifier: Ed25519Verifier = TinkEd25519Verifier,
    ): VerifiedProfileManifest {
        val envelope = SignedProfileEnvelopeJsonCodec.decode(envelopeBytes)
        val trustedKey = trustPolicy.resolve(envelope.keyId)
            ?: throw InvalidProfileSignatureException("profile signing key is not trusted")
        val publicKey = trustedKey.publicKey
        val payload = envelope.payloadBytes()
        val verified = try {
            verifier.verify(publicKey, ProfileSigning.messageFor(payload), envelope.signatureBytes())
        } catch (failure: ProfileVerificationException) {
            throw failure
        } catch (failure: RuntimeException) {
            throw ProfileVerificationException("profile signature verification failed", failure)
        }
        if (!verified) throw InvalidProfileSignatureException("profile signature is invalid")
        return VerifiedProfileManifest(
            profile = ProfilePayloadJsonCodec.decode(payload, approvedOrigins),
            manifestSha256 = Sha256Digest(
                MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") {
                    (it.toInt() and 0xff).toString(16).padStart(2, '0')
                },
            ),
            signingKeyId = envelope.keyId,
            signingKeyFingerprint = publicKey.fingerprint,
            trustEpoch = trustPolicy.trustEpoch,
        )
    }
}

data class VerifiedProfileV2Manifest(
    val profile: VmProfileV2,
    /** SHA-256 of the exact v2 signed payload bytes, used as the manifest identity. */
    val manifestSha256: Sha256Digest,
    val signingKeyId: SigningKeyId,
    val signingKeyFingerprint: Sha256Digest,
    val trustEpoch: TrustEpoch,
)

/** V2 verification is deliberately separate so untrusted bytes cannot select their signing domain. */
object VerifiedProfileV2JsonCodec {
    fun decode(
        envelopeBytes: ByteArray,
        approvedOrigins: ApprovedArtifactOrigins,
        trustPolicy: ProfileTrustPolicy,
        verifier: Ed25519Verifier = TinkEd25519Verifier,
    ): VmProfileV2 = decodeManifest(envelopeBytes, approvedOrigins, trustPolicy, verifier).profile

    fun decodeManifest(
        envelopeBytes: ByteArray,
        approvedOrigins: ApprovedArtifactOrigins,
        trustPolicy: ProfileTrustPolicy,
        verifier: Ed25519Verifier = TinkEd25519Verifier,
    ): VerifiedProfileV2Manifest {
        val envelope = SignedProfileEnvelopeV2JsonCodec.decode(envelopeBytes)
        val trustedKey = trustPolicy.resolve(envelope.keyId)
            ?: throw InvalidProfileSignatureException("profile v2 signing key is not trusted")
        val publicKey = trustedKey.publicKey
        val payload = envelope.payloadBytes()
        val verified = try {
            verifier.verify(publicKey, ProfileSigningV2.messageFor(payload), envelope.signatureBytes())
        } catch (failure: ProfileVerificationException) {
            throw failure
        } catch (failure: RuntimeException) {
            throw ProfileVerificationException("profile v2 signature verification failed", failure)
        }
        if (!verified) throw InvalidProfileSignatureException("profile v2 signature is invalid")
        return VerifiedProfileV2Manifest(
            profile = ProfilePayloadV2JsonCodec.decode(payload, approvedOrigins),
            manifestSha256 = Sha256Digest(
                MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") {
                    (it.toInt() and 0xff).toString(16).padStart(2, '0')
                },
            ),
            signingKeyId = envelope.keyId,
            signingKeyFingerprint = publicKey.fingerprint,
            trustEpoch = trustPolicy.trustEpoch,
        )
    }
}

private sealed interface JsonValue {
    data class ObjectValue(val value: Map<String, JsonValue>) : JsonValue
    data class ArrayValue(val value: List<JsonValue>) : JsonValue
    data class StringValue(val value: String) : JsonValue
    data class NumberValue(val value: Long) : JsonValue
    data class BooleanValue(val value: Boolean) : JsonValue
    data object NullValue : JsonValue
}

/** Small strict reader with duplicate-key, depth, and collection bounds. */
private class StrictProfileJson private constructor(private val source: String) {
    private var index = 0

    private fun parseRootObject(): Map<String, JsonValue> {
        skipWhitespace()
        val root = parseObject(depth = 0).value
        skipWhitespace()
        require(index == source.length) { "trailing JSON content" }
        return root
    }

    private fun parseObject(depth: Int): JsonValue.ObjectValue {
        require(depth <= MAX_DEPTH) { "JSON nesting exceeds the supported bound" }
        expect('{')
        skipWhitespace()
        val fields = linkedMapOf<String, JsonValue>()
        if (take('}')) return JsonValue.ObjectValue(fields)
        while (true) {
            require(fields.size < MAX_COLLECTION_ENTRIES) { "JSON object has too many fields" }
            skipWhitespace()
            val key = parseString()
            require(!fields.containsKey(key)) { "duplicate JSON field '$key'" }
            skipWhitespace()
            expect(':')
            skipWhitespace()
            fields[key] = parseValue(depth + 1)
            skipWhitespace()
            if (take('}')) return JsonValue.ObjectValue(fields)
            expect(',')
        }
    }

    private fun parseArray(depth: Int): JsonValue.ArrayValue {
        require(depth <= MAX_DEPTH) { "JSON nesting exceeds the supported bound" }
        expect('[')
        skipWhitespace()
        val values = mutableListOf<JsonValue>()
        if (take(']')) return JsonValue.ArrayValue(values)
        while (true) {
            require(values.size < MAX_COLLECTION_ENTRIES) { "JSON array has too many entries" }
            values += parseValue(depth + 1)
            skipWhitespace()
            if (take(']')) return JsonValue.ArrayValue(values)
            expect(',')
            skipWhitespace()
        }
    }

    private fun parseValue(depth: Int): JsonValue = when (peek()) {
        '{' -> parseObject(depth)
        '[' -> parseArray(depth)
        '"' -> JsonValue.StringValue(parseString())
        't' -> { literal("true"); JsonValue.BooleanValue(true) }
        'f' -> { literal("false"); JsonValue.BooleanValue(false) }
        'n' -> { literal("null"); JsonValue.NullValue }
        '-', in '0'..'9' -> JsonValue.NumberValue(parseInteger())
        else -> error("unsupported JSON value")
    }

    private fun parseString(): String {
        expect('"')
        val out = StringBuilder()
        while (index < source.length) {
            val char = source[index++]
            when {
                char == '"' -> return out.toString()
                char == '\\' -> {
                    require(index < source.length) { "truncated JSON escape" }
                    when (val escaped = source[index++]) {
                        '"', '\\', '/' -> out.append(escaped)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000c')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            require(index + 4 <= source.length) { "truncated unicode escape" }
                            val code = source.substring(index, index + 4).toIntOrNull(16)
                                ?: error("invalid unicode escape")
                            require(code !in 0xd800..0xdfff) { "surrogate escapes are not accepted" }
                            out.append(code.toChar())
                            index += 4
                        }
                        else -> error("invalid JSON escape")
                    }
                }
                char.code < 0x20 -> error("unescaped JSON control character")
                char.isSurrogate() -> error("surrogate characters are not accepted")
                else -> out.append(char)
            }
        }
        error("unterminated JSON string")
    }

    private fun parseInteger(): Long {
        val start = index
        if (take('-')) require(peek() in '0'..'9') { "invalid JSON number" }
        if (take('0')) {
            require(peek() !in '0'..'9') { "leading-zero JSON number" }
        } else {
            require(peek() in '1'..'9') { "invalid JSON number" }
            while (peek() in '0'..'9') index++
        }
        require(peek() != '.' && peek() != 'e' && peek() != 'E') { "JSON schema accepts integers only" }
        return source.substring(start, index).toLongOrNull() ?: error("integer is outside int64")
    }

    private fun literal(value: String) {
        require(source.regionMatches(index, value, 0, value.length)) { "invalid JSON literal" }
        index += value.length
    }

    private fun skipWhitespace() {
        while (peek() == ' ' || peek() == '\n' || peek() == '\r' || peek() == '\t') index++
    }

    private fun peek(): Char = source.getOrNull(index) ?: '\u0000'
    private fun take(char: Char): Boolean = if (peek() == char) { index++; true } else false
    private fun expect(char: Char) { require(take(char)) { "expected '$char'" } }

    companion object {
        private const val MAX_DEPTH = 5
        private const val MAX_COLLECTION_ENTRIES = 16
        fun parseObject(source: String): Map<String, JsonValue> = StrictProfileJson(source).parseRootObject()
    }
}

private fun parseObject(bytes: ByteArray, maxBytes: Int, label: String): Map<String, JsonValue> {
    if (bytes.size !in 1..maxBytes) throw ProfileCodecException("$label is outside the byte bound")
    val text = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (failure: Exception) {
        throw ProfileCodecException("$label is not valid UTF-8", failure)
    }
    return try {
        StrictProfileJson.parseObject(text)
    } catch (failure: IllegalArgumentException) {
        throw ProfileCodecException(failure.message ?: "$label is invalid JSON", failure)
    }
}

private fun requireExactFields(root: Map<String, JsonValue>, fields: Set<String>, label: String) {
    if (root.keys != fields) throw ProfileCodecException("$label fields do not match the closed schema")
}

private fun requireVersion(root: Map<String, JsonValue>, expected: Long, label: String) {
    val version = (root["version"] as? JsonValue.NumberValue)?.value
        ?: throw ProfileCodecException("$label version must be an integer")
    if (version != expected) throw UnsupportedProfileVersionException("unsupported $label version")
}

private fun Map<String, JsonValue>.requiredString(name: String, label: String): String =
    (this[name] as? JsonValue.StringValue)?.value
        ?: throw ProfileCodecException("$label field '$name' must be a string")

private fun Map<String, JsonValue>.requiredLong(name: String, label: String): Long =
    (this[name] as? JsonValue.NumberValue)?.value
        ?: throw ProfileCodecException("$label field '$name' must be an integer")

private fun Map<String, JsonValue>.requiredStringArray(name: String, label: String): List<String> {
    val values = (this[name] as? JsonValue.ArrayValue)?.value
        ?: throw ProfileCodecException("$label field '$name' must be an array")
    return values.mapIndexed { index, value ->
        (value as? JsonValue.StringValue)?.value
            ?: throw ProfileCodecException("$label field '$name'[$index] must be a string")
    }
}

private fun decodeCanonicalBase64(value: String, maxEncodedChars: Int, label: String): ByteArray {
    require(value.length in 1..maxEncodedChars) { "$label base64 is outside the encoded length bound" }
    val decoded = try {
        Base64.getDecoder().decode(value)
    } catch (failure: IllegalArgumentException) {
        throw ProfileCodecException("$label is not valid base64", failure)
    }
    require(Base64.getEncoder().encodeToString(decoded) == value) { "$label base64 is not canonical" }
    return decoded
}

private inline fun <T> codecBoundary(block: () -> T): T = try {
    block()
} catch (failure: ProfileCodecException) {
    throw failure
} catch (failure: IllegalArgumentException) {
    throw ProfileCodecException(failure.message ?: "profile schema validation failed", failure)
}

private fun jsonString(value: String): String = buildString(value.length + 2) {
    append('"')
    value.forEach { char ->
        when (char) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000c' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
        }
    }
    append('"')
}
