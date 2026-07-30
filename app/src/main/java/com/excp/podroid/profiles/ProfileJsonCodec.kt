package com.excp.podroid.profiles

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
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
            VmProfile(ProfileId(profileId), ProfileGeneration(generation), artifacts)
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
            "\"artifacts\":[$artifacts]" +
            "}").toByteArray(Charsets.UTF_8)
        require(encoded.size <= ProfileLimits.MAX_PAYLOAD_BYTES) { "encoded profile payload exceeds the byte bound" }
        return encoded
    }

    private val PAYLOAD_FIELDS = setOf("version", "profile_id", "generation", "artifacts")
    private val ARTIFACT_FIELDS = setOf("role", "url", "sha256", "size_bytes")
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

/** Verifies the envelope before parsing its payload and resolves keys only by signed-envelope key ID. */
object VerifiedProfileJsonCodec {
    fun decode(
        envelopeBytes: ByteArray,
        approvedOrigins: ApprovedArtifactOrigins,
        resolvePublicKey: (SigningKeyId) -> Ed25519PublicKey?,
        verifier: Ed25519Verifier = JcaEd25519Verifier,
    ): VmProfile {
        val envelope = SignedProfileEnvelopeJsonCodec.decode(envelopeBytes)
        val publicKey = resolvePublicKey(envelope.keyId)
            ?: throw InvalidProfileSignatureException("profile signing key is not trusted")
        val payload = envelope.payloadBytes()
        val verified = try {
            verifier.verify(publicKey, ProfileSigning.messageFor(payload), envelope.signatureBytes())
        } catch (failure: ProfileVerificationException) {
            throw failure
        } catch (failure: RuntimeException) {
            throw ProfileVerificationException("profile signature verification failed", failure)
        }
        if (!verified) throw InvalidProfileSignatureException("profile signature is invalid")
        return ProfilePayloadJsonCodec.decode(payload, approvedOrigins)
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
