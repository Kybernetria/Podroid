package com.excp.podroid.profiles

import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileJsonCodecTest {
    private val origins = ApprovedArtifactOrigins.of(APPROVED_ORIGIN)

    @Test
    fun `payload model and deterministic codec round trip`() {
        val decoded = ProfilePayloadJsonCodec.decode(validPayload().toByteArray(), origins)

        assertEquals(ProfileId("alpine-direct"), decoded.id)
        assertEquals(ProfileGeneration(7), decoded.generation)
        assertEquals(DataCompatibilityId("alpine-direct-v1"), decoded.dataCompatibility)
        assertEquals(ArtifactRole.entries.toSet(), decoded.artifacts.map { it.role }.toSet())
        assertEquals("$APPROVED_ORIGIN/kernel", decoded.artifact(ArtifactRole.KERNEL).url.value)
        assertEquals(decoded, ProfilePayloadJsonCodec.decode(ProfilePayloadJsonCodec.encode(decoded), origins))

        val mutableArtifacts = decoded.artifacts.toMutableList()
        val immutableProfile = VmProfile(decoded.id, decoded.generation, decoded.dataCompatibility, mutableArtifacts)
        mutableArtifacts.clear()
        assertEquals(ArtifactRole.entries.size, immutableProfile.artifacts.size)
    }

    @Test
    fun `envelope codec preserves exact payload and canonical signature bytes`() {
        val payload = (" \n" + validPayload() + "\t").toByteArray()
        val signature = ByteArray(ProfileLimits.ED25519_SIGNATURE_BYTES) { it.toByte() }
        val encoded = SignedProfileEnvelopeJsonCodec.encode(SigningKeyId("release-1"), payload, signature)
        val decoded = SignedProfileEnvelopeJsonCodec.decode(encoded)

        assertEquals(SigningKeyId("release-1"), decoded.keyId)
        assertArrayEquals(payload, decoded.payloadBytes())
        assertArrayEquals(signature, decoded.signatureBytes())
        assertArrayEquals(payload, decoded.payloadBytes().also { it.fill(0) }.let { decoded.payloadBytes() })
    }

    @Test
    fun `verifier receives domain separator followed by exact payload bytes before payload parsing`() {
        val invalidJsonPayload = " \nnot-json\t".toByteArray()
        val envelope = SignedProfileEnvelopeJsonCodec.encode(
            SigningKeyId("release-1"),
            invalidJsonPayload,
            ByteArray(ProfileLimits.ED25519_SIGNATURE_BYTES),
        )
        var capturedMessage: ByteArray? = null
        var resolvedId: SigningKeyId? = null
        val failure = runCatching {
            VerifiedProfileJsonCodec.decode(
                envelope,
                origins,
                resolvePublicKey = {
                    resolvedId = it
                    Ed25519PublicKey.fromX509(byteArrayOf(1))
                },
                verifier = Ed25519Verifier { _, message, _ ->
                    capturedMessage = message.copyOf()
                    true
                },
            )
        }.exceptionOrNull()

        assertTrue(failure is ProfileCodecException)
        assertEquals(SigningKeyId("release-1"), resolvedId)
        assertArrayEquals(ProfileSigning.messageFor(invalidJsonPayload), capturedMessage)
        val expected = "com.excp.podroid.vm-profile.v1\u0000".toByteArray(Charsets.US_ASCII) + invalidJsonPayload
        assertEquals("com.excp.podroid.vm-profile.v1\u0000", ProfileSigning.DOMAIN_SEPARATOR)
        assertArrayEquals(expected, capturedMessage)
    }

    @Test
    fun `JCA signed exact payload verifies and any payload byte change fails`() {
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val payload = validPayload().toByteArray()
        val signature = Signature.getInstance("Ed25519").run {
            initSign(keyPair.private)
            update(ProfileSigning.messageFor(payload))
            sign()
        }
        val publicKey = Ed25519PublicKey.fromX509(keyPair.public.encoded)
        val envelope = SignedProfileEnvelopeJsonCodec.encode(SigningKeyId("release-1"), payload, signature)

        assertEquals(
            ProfileGeneration(7),
            VerifiedProfileJsonCodec.decode(envelope, origins, { publicKey }).generation,
        )

        val changedPayload = (" " + validPayload()).toByteArray()
        val changedEnvelope = SignedProfileEnvelopeJsonCodec.encode(
            SigningKeyId("release-1"), changedPayload, signature,
        )
        val failure = runCatching {
            VerifiedProfileJsonCodec.decode(changedEnvelope, origins, { publicKey })
        }.exceptionOrNull()
        assertTrue(failure is InvalidProfileSignatureException)
    }

    @Test
    fun `untrusted key fails before signature verification`() {
        val envelope = SignedProfileEnvelopeJsonCodec.encode(
            SigningKeyId("unknown"),
            validPayload().toByteArray(),
            ByteArray(ProfileLimits.ED25519_SIGNATURE_BYTES),
        )
        var verificationCalled = false

        val failure = runCatching {
            VerifiedProfileJsonCodec.decode(
                envelope,
                origins,
                resolvePublicKey = { null },
                verifier = Ed25519Verifier { _, _, _ -> verificationCalled = true; true },
            )
        }.exceptionOrNull()

        assertTrue(failure is InvalidProfileSignatureException)
        assertFalse(verificationCalled)
    }

    @Test
    fun `envelope rejects duplicate unknown and unsupported version fields`() {
        val valid = validEnvelopeText()
        val cases = listOf(
            valid.replaceFirst("\"version\":1,", "\"version\":1,\"version\":1,"),
            valid.dropLast(1) + ",\"extra\":0}",
            valid.replaceFirst("\"version\":1", "\"version\":2"),
            valid.replaceFirst("\"version\":1", "\"version\":1.0"),
        )
        cases.forEach { assertFails { SignedProfileEnvelopeJsonCodec.decode(it.toByteArray()) } }
        assertTrue(
            runCatching {
                SignedProfileEnvelopeJsonCodec.decode(cases[2].toByteArray())
            }.exceptionOrNull() is UnsupportedProfileVersionException,
        )
    }

    @Test
    fun `payload and artifact reject duplicate unknown and unsupported fields`() {
        val valid = validPayload()
        val cases = listOf(
            valid.replaceFirst("\"version\":1,", "\"version\":1,\"version\":1,"),
            valid.dropLast(1) + ",\"extra\":0}",
            valid.replaceFirst("\"version\":1", "\"version\":2"),
            valid.replaceFirst("\"role\":\"kernel\",", "\"role\":\"kernel\",\"role\":\"kernel\","),
            valid.replaceFirst("\"role\":\"kernel\",", "\"role\":\"kernel\",\"extra\":0,"),
        )
        cases.forEach { assertFails { ProfilePayloadJsonCodec.decode(it.toByteArray(), origins) } }
        assertTrue(
            runCatching { ProfilePayloadJsonCodec.decode(cases[2].toByteArray(), origins) }
                .exceptionOrNull() is UnsupportedProfileVersionException,
        )
    }

    @Test
    fun `closed artifact roles require kernel initrd rootfs exactly once`() {
        val duplicate = validPayload(
            listOf(
                artifact("kernel", "kernel"),
                artifact("kernel", "initrd"),
                artifact("rootfs", "rootfs"),
            ),
        )
        val unknown = validPayload(
            listOf(
                artifact("kernel", "kernel"),
                artifact("initrd", "initrd"),
                artifact("firmware", "rootfs"),
            ),
        )
        val missing = validPayload(listOf(artifact("kernel", "kernel"), artifact("rootfs", "rootfs")))
        val extra = validPayload(
            listOf(
                artifact("kernel", "kernel"), artifact("initrd", "initrd"),
                artifact("rootfs", "rootfs"), artifact("rootfs", "copy"),
            ),
        )

        listOf(duplicate, unknown, missing, extra).forEach {
            assertFails { ProfilePayloadJsonCodec.decode(it.toByteArray(), origins) }
        }
    }

    @Test
    fun `artifact URLs require canonical HTTPS 443 and exact approved origin`() {
        assertEquals(
            "$APPROVED_ORIGIN/releases/rootfs%20image",
            origins.parseUrl("$APPROVED_ORIGIN/releases/rootfs%20image").value,
        )
        val rejected = listOf(
            "http://profiles.example:443/kernel",
            "https://profiles.example/kernel",
            "https://profiles.example:444/kernel",
            "https://PROFILES.example:443/kernel",
            "https://profiles.example:443",
            "https://profiles.example:443/../kernel",
            "https://profiles.example:443/%2e%2e/kernel",
            "https://profiles.example:443/safe%2F..%2Fkernel",
            "https://profiles.example:443/safe%5c..%5ckernel",
            "https://profiles.example:443/kernel%00ignored",
            "https://profiles.example:443//kernel",
            "https://user@profiles.example:443/kernel",
            "https://profiles.example:443/kernel?token=x",
            "https://profiles.example:443/kernel#fragment",
            "https://other.example:443/kernel",
            "https://profiles.example:443\\kernel",
        )
        rejected.forEach { value -> assertFails(value) { origins.parseUrl(value) } }
    }

    @Test
    fun `approved origins themselves are closed canonical HTTPS origins`() {
        listOf(
            "http://profiles.example:443",
            "https://profiles.example",
            "https://profiles.example:444",
            "https://PROFILES.example:443",
            "https://profiles.example:443/",
            "https://profiles.example:443/path",
            "https://profiles.example:443?x=1",
            "https://user@profiles.example:443",
        ).forEach { value -> assertFails(value) { ApprovedArtifactOrigins.of(value) } }
        assertFails { ApprovedArtifactOrigins.of(emptyList()) }
    }

    @Test
    fun `SHA256 is exactly lowercase hex`() {
        val invalid = listOf(
            "a".repeat(63),
            "a".repeat(65),
            "A" + "a".repeat(63),
            "g" + "a".repeat(63),
        )
        invalid.forEach { digest ->
            val payload = validPayload(replaceDigest = digest)
            assertFails(digest) { ProfilePayloadJsonCodec.decode(payload.toByteArray(), origins) }
        }
    }

    @Test
    fun `artifact and aggregate sizes are strictly bounded bytes`() {
        listOf(0L, -1L, ProfileLimits.MAX_ARTIFACT_BYTES + 1).forEach { size ->
            assertFails(size.toString()) {
                ProfilePayloadJsonCodec.decode(validPayload(kernelSize = size).toByteArray(), origins)
            }
        }
        val overTotal = validPayload(
            listOf(
                artifact("kernel", "kernel", ProfileLimits.MAX_ARTIFACT_BYTES),
                artifact("initrd", "initrd", ProfileLimits.MAX_ARTIFACT_BYTES),
                artifact("rootfs", "rootfs", 1),
            ),
        )
        assertFails { ProfilePayloadJsonCodec.decode(overTotal.toByteArray(), origins) }

        val exactTotal = validPayload(
            listOf(
                artifact("kernel", "kernel", 2L * 1024 * 1024 * 1024),
                artifact("initrd", "initrd", 2L * 1024 * 1024 * 1024),
                artifact("rootfs", "rootfs", 2L * 1024 * 1024 * 1024),
            ),
        )
        ProfilePayloadJsonCodec.decode(exactTotal.toByteArray(), origins)
    }

    @Test
    fun `generation is a positive int64 and profile id is constrained`() {
        listOf("0", "-1", "1.0", "1e0", "9223372036854775808", "\"1\"").forEach { generation ->
            assertFails(generation) {
                ProfilePayloadJsonCodec.decode(validPayload(generation = generation).toByteArray(), origins)
            }
        }
        listOf("", "Upper", "../escape", "a".repeat(ProfileLimits.MAX_PROFILE_ID_CHARS + 1)).forEach { id ->
            assertFails(id) {
                ProfilePayloadJsonCodec.decode(validPayload(profileId = id).toByteArray(), origins)
            }
        }
        assertEquals(
            Long.MAX_VALUE,
            ProfilePayloadJsonCodec.decode(validPayload(generation = Long.MAX_VALUE.toString()).toByteArray(), origins)
                .generation.value,
        )
    }

    @Test
    fun `data compatibility is required signed and constrained`() {
        listOf("", "UPPER", "../escape", "a".repeat(ProfileLimits.MAX_DATA_COMPATIBILITY_ID_CHARS + 1)).forEach { value ->
            val payload = validPayload().replace("alpine-direct-v1", value)
            assertFails(value) { ProfilePayloadJsonCodec.decode(payload.toByteArray(), origins) }
        }
        assertFails {
            ProfilePayloadJsonCodec.decode(
                validPayload().replace("\"data_compatibility\":\"alpine-direct-v1\",", "").toByteArray(),
                origins,
            )
        }
    }

    @Test
    fun `strict JSON rejects malformed UTF8 nesting excess entries and trailing data`() {
        assertFails { ProfilePayloadJsonCodec.decode(byteArrayOf(0xc3.toByte(), 0x28), origins) }
        assertFails { ProfilePayloadJsonCodec.decode((validPayload() + " true").toByteArray(), origins) }
        assertFails {
            ProfilePayloadJsonCodec.decode(
                "{\"version\":1,\"profile_id\":\"x\",\"generation\":1,\"artifacts\":".toByteArray(),
                origins,
            )
        }
        val manyArtifacts = (0..16).joinToString(",") { artifact("kernel", "k$it") }
        assertFails {
            ProfilePayloadJsonCodec.decode(
                "{\"version\":1,\"profile_id\":\"x\",\"generation\":1,\"artifacts\":[$manyArtifacts]}".toByteArray(),
                origins,
            )
        }
    }

    @Test
    fun `byte and base64 bounds reject before use`() {
        assertFails {
            SignedProfileEnvelopeJsonCodec.decode(ByteArray(ProfileLimits.MAX_ENVELOPE_BYTES + 1) { ' '.code.toByte() })
        }
        assertFails {
            ProfilePayloadJsonCodec.decode(ByteArray(ProfileLimits.MAX_PAYLOAD_BYTES + 1) { ' '.code.toByte() }, origins)
        }
        val valid = validEnvelopeText()
        val unpadded = valid.replace(Regex("(\"signature\":\"[^\"]+)=\"}"), "$1\"}")
        assertFails { SignedProfileEnvelopeJsonCodec.decode(unpadded.toByteArray()) }
        val shortSignature = valid.replace(
            Base64.getEncoder().encodeToString(ByteArray(ProfileLimits.ED25519_SIGNATURE_BYTES)),
            Base64.getEncoder().encodeToString(ByteArray(ProfileLimits.ED25519_SIGNATURE_BYTES - 1)),
        )
        assertFails { SignedProfileEnvelopeJsonCodec.decode(shortSignature.toByteArray()) }
    }

    private fun validEnvelopeText(): String = SignedProfileEnvelopeJsonCodec.encode(
        SigningKeyId("release-1"),
        validPayload().toByteArray(),
        ByteArray(ProfileLimits.ED25519_SIGNATURE_BYTES),
    ).toString(Charsets.UTF_8)

    private fun validPayload(
        artifacts: List<String> = listOf(
            artifact("kernel", "kernel"),
            artifact("initrd", "initrd"),
            artifact("rootfs", "rootfs"),
        ),
        generation: String = "7",
        profileId: String = "alpine-direct",
        replaceDigest: String = DIGEST,
        kernelSize: Long? = null,
    ): String {
        val actualArtifacts = if (kernelSize == null && replaceDigest == DIGEST) {
            artifacts
        } else {
            artifacts.mapIndexed { index, value ->
                var updated = value
                if (index == 0 && kernelSize != null) {
                    updated = updated.replace("\"size_bytes\":1024", "\"size_bytes\":$kernelSize")
                }
                if (index == 0) updated = updated.replace(DIGEST, replaceDigest)
                updated
            }
        }
        return "{" +
            "\"version\":1," +
            "\"profile_id\":\"$profileId\"," +
            "\"generation\":$generation," +
            "\"data_compatibility\":\"alpine-direct-v1\"," +
            "\"artifacts\":[${actualArtifacts.joinToString(",")}]" +
            "}"
    }

    private fun artifact(role: String, path: String, size: Long = 1024): String =
        "{" +
            "\"role\":\"$role\"," +
            "\"url\":\"$APPROVED_ORIGIN/$path\"," +
            "\"sha256\":\"$DIGEST\"," +
            "\"size_bytes\":$size" +
            "}"

    private fun assertFails(label: String = "", block: () -> Unit) {
        assertTrue(label, runCatching(block).isFailure)
    }

    private companion object {
        const val APPROVED_ORIGIN = "https://profiles.example:443"
        val DIGEST = "ab".repeat(32)
    }
}
