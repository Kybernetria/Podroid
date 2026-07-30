package com.excp.podroid.profiles

import java.security.KeyPairGenerator
import java.security.Signature
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileV2JsonCodecTest {
    private val origins = ApprovedArtifactOrigins.of(ORIGIN)

    @Test
    fun `v2 model and deterministic codec round trip closed cloud contract`() {
        val decoded = ProfilePayloadV2JsonCodec.decode(validPayload().toByteArray(), origins)

        assertEquals(ProfileId("debian-12-cloud"), decoded.id)
        assertEquals(ProfileV2DataLineage.DEBIAN_12_GENERICCLOUD, decoded.dataCompatibility)
        assertEquals(setOf(ProfileBackend.QEMU), decoded.supportedBackends)
        assertEquals(ProfileV2Limits.READINESS_MARKER, decoded.readinessMarker)
        assertEquals(ProfileV2ArtifactRole.entries.toSet(), decoded.artifacts.map { it.role }.toSet())
        ProfileV2ArtifactRole.entries.forEach { role ->
            assertEquals(role.requiredFormat, decoded.artifact(role).format)
        }
        assertTrue(decoded.capabilities.guestIntegrations.isEmpty())
        ProfileV2GuestIntegration.entries.forEach { assertFalse(decoded.capabilities.allows(it)) }
        assertEquals(decoded, ProfilePayloadV2JsonCodec.decode(ProfilePayloadV2JsonCodec.encode(decoded), origins))
    }

    @Test
    fun `v1 encoding and signing domain remain byte for byte unchanged`() {
        val v1 = VmProfile(
            ProfileId("alpine-direct"), ProfileGeneration(7), DataCompatibilityId("alpine-direct-v1"),
            ProfileArchitecture.AARCH64, ProfileBootContract.PODROID_DIRECT_V1,
            ProfileStorageContract.PODROID_OVERLAY_EXT4_V1, ProfileHealthContract.PODROID_READY_V1,
            setOf(ProfileBackend.QEMU, ProfileBackend.AVF),
            ArtifactRole.entries.map { role ->
                ProfileArtifact(role, origins.parseUrl("$ORIGIN/${role.wireName}"), Sha256Digest(DIGEST), ArtifactSizeBytes(1024))
            },
        )
        val expected = "{" +
            "\"version\":1,\"profile_id\":\"alpine-direct\",\"generation\":7," +
            "\"data_compatibility\":\"alpine-direct-v1\",\"architecture\":\"aarch64\"," +
            "\"boot_contract\":\"podroid-direct-v1\",\"storage_contract\":\"podroid-overlay-ext4-v1\"," +
            "\"health_contract\":\"podroid-ready-v1\",\"supported_backends\":[\"qemu\",\"avf\"]," +
            "\"artifacts\":[" +
            "{\"role\":\"kernel\",\"url\":\"$ORIGIN/kernel\",\"sha256\":\"$DIGEST\",\"size_bytes\":1024}," +
            "{\"role\":\"initrd\",\"url\":\"$ORIGIN/initrd\",\"sha256\":\"$DIGEST\",\"size_bytes\":1024}," +
            "{\"role\":\"rootfs\",\"url\":\"$ORIGIN/rootfs\",\"sha256\":\"$DIGEST\",\"size_bytes\":1024}]}"

        assertArrayEquals(expected.toByteArray(), ProfilePayloadJsonCodec.encode(v1))
        assertEquals("com.excp.podroid.vm-profile.v1\u0000", ProfileSigning.DOMAIN_SEPARATOR)
        assertArrayEquals(
            (ProfileSigning.DOMAIN_SEPARATOR + expected).toByteArray(Charsets.US_ASCII),
            ProfileSigning.messageFor(expected.toByteArray()),
        )
    }

    @Test
    fun `v2 has a separate domain and versions cannot cross codecs`() {
        val payload = validPayload().toByteArray()
        assertEquals("com.excp.podroid.vm-profile.v2\u0000", ProfileSigningV2.DOMAIN_SEPARATOR)
        assertArrayEquals(
            ProfileSigningV2.DOMAIN_SEPARATOR.toByteArray(Charsets.US_ASCII) + payload,
            ProfileSigningV2.messageFor(payload),
        )
        assertFalse(ProfileSigning.messageFor(payload).contentEquals(ProfileSigningV2.messageFor(payload)))
        assertFailure<ProfileCodecException> { ProfilePayloadJsonCodec.decode(payload, origins) }
        assertFailure<ProfileCodecException> {
            ProfilePayloadV2JsonCodec.decode(validV1Payload().toByteArray(), origins)
        }
        assertFailure<UnsupportedProfileVersionException> {
            ProfilePayloadV2JsonCodec.decode(validPayload().replaceFirst("\"version\":2", "\"version\":1").toByteArray(), origins)
        }
    }

    @Test
    fun `v2 signature verifies only with v2 domain`() {
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val publicKey = Ed25519PublicKey.fromX509(keyPair.public.encoded)
        val policy = ProfileTrustPolicy(
            TrustEpoch(1), mapOf(SigningKeyId("release-2") to TrustedProfileSigningKey(publicKey)),
        )
        val payload = validPayload().toByteArray()
        fun envelope(message: ByteArray): ByteArray {
            val signature = Signature.getInstance("Ed25519").run {
                initSign(keyPair.private)
                update(message)
                sign()
            }
            return SignedProfileEnvelopeV2JsonCodec.encode(SigningKeyId("release-2"), payload, signature)
        }

        assertEquals(
            ProfileGeneration(9),
            VerifiedProfileV2JsonCodec.decode(envelope(ProfileSigningV2.messageFor(payload)), origins, policy).generation,
        )
        assertFailure<InvalidProfileSignatureException> {
            VerifiedProfileV2JsonCodec.decode(envelope(ProfileSigning.messageFor(payload)), origins, policy)
        }
    }

    @Test
    fun `closed v2 boot backend readiness and lineage fields reject drift`() {
        val replacements = listOf(
            "\"architecture\":\"aarch64\"" to "\"architecture\":\"x86_64\"",
            "\"boot_contract\":\"podroid-uefi-nocloud-v1\"" to "\"boot_contract\":\"podroid-direct-v1\"",
            "\"storage_contract\":\"podroid-cloud-disk-v1\"" to "\"storage_contract\":\"other\"",
            "\"health_contract\":\"podroid-cloud-ready-v1\"" to "\"health_contract\":\"podroid-ready-v1\"",
            "\"readiness_marker\":\"PODROID_CLOUD_READY_V1\"" to "\"readiness_marker\":\"Ready!\"",
            "\"supported_backends\":[\"qemu\"]" to "\"supported_backends\":[\"avf\"]",
            "\"supported_backends\":[\"qemu\"]" to "\"supported_backends\":[\"qemu\",\"avf\"]",
            "podroid-debian-12-genericcloud-v1" to "../lineage",
        )
        replacements.forEach { (valid, invalid) ->
            assertFailure<ProfileCodecException>(invalid) {
                ProfilePayloadV2JsonCodec.decode(validPayload().replace(valid, invalid).toByteArray(), origins)
            }
        }
    }

    @Test
    fun `four roles formats and role specific byte limits are mandatory`() {
        ProfileV2ArtifactRole.entries.forEach { role ->
            val over = validPayload().replace(
                "\"role\":\"${role.wireName}\",\"format\":\"${role.requiredFormat.wireName}\",\"url\":\"$ORIGIN/${role.wireName}\",\"sha256\":\"$DIGEST\",\"size_bytes\":1024",
                "\"role\":\"${role.wireName}\",\"format\":\"${role.requiredFormat.wireName}\",\"url\":\"$ORIGIN/${role.wireName}\",\"sha256\":\"$DIGEST\",\"size_bytes\":${role.maxSizeBytes + 1}",
            )
            assertFailure<ProfileCodecException>(role.wireName) { ProfilePayloadV2JsonCodec.decode(over.toByteArray(), origins) }
        }
        val wrongFormat = validPayload().replaceFirst("\"format\":\"raw\"", "\"format\":\"raw-pflash\"")
        val duplicateRole = validPayload().replaceFirst("\"role\":\"uefi-code\"", "\"role\":\"cloud-disk\"")
        val unknownRole = validPayload().replaceFirst("\"role\":\"cloud-disk\"", "\"role\":\"kernel\"")
        listOf(wrongFormat, duplicateRole, unknownRole).forEach {
            assertFailure<ProfileCodecException> { ProfilePayloadV2JsonCodec.decode(it.toByteArray(), origins) }
        }
    }

    @Test
    fun `capabilities are typed immutable and default deny unknown duplicate and schema drift`() {
        val enabled = validPayload().replace(
            "\"guest_integrations\":[]",
            "\"guest_integrations\":[\"podroid-terminal-v1\",\"podroid-resize-v1\"]",
        )
        val profile = ProfilePayloadV2JsonCodec.decode(enabled.toByteArray(), origins)
        assertTrue(profile.capabilities.allows(ProfileV2GuestIntegration.PODROID_TERMINAL_V1))
        assertFalse(profile.capabilities.allows(ProfileV2GuestIntegration.PODROID_HOST_BRIDGE_V1))
        assertFailure<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (profile.capabilities.guestIntegrations as MutableSet).add(ProfileV2GuestIntegration.PODROID_DOWNLOADS_V1)
        }
        listOf(
            validPayload().replace("\"guest_integrations\":[]", "\"guest_integrations\":[\"unknown\"]"),
            validPayload().replace("\"guest_integrations\":[]", "\"guest_integrations\":[\"podroid-terminal-v1\",\"podroid-terminal-v1\"]"),
            validPayload().replace("\"guest_integrations\":[]", "\"guest_integrations\":[],\"ssh\":true"),
            validPayload().replace("\"capabilities\":{\"guest_integrations\":[]},", ""),
        ).forEach { invalid ->
            assertFailure<ProfileCodecException> { ProfilePayloadV2JsonCodec.decode(invalid.toByteArray(), origins) }
        }
    }

    @Test
    fun `v2 rejects duplicate unknown malformed and oversized boundary data`() {
        listOf(
            validPayload().replaceFirst("\"version\":2,", "\"version\":2,\"version\":2,"),
            validPayload().dropLast(1) + ",\"extra\":true}",
            validPayload().replaceFirst("\"format\":\"raw\",", "\"format\":\"raw\",\"extra\":0,"),
            validPayload().replaceFirst("\"version\":2", "\"version\":2.0"),
        ).forEach { invalid ->
            assertFailure<ProfileCodecException> { ProfilePayloadV2JsonCodec.decode(invalid.toByteArray(), origins) }
        }
        assertFailure<ProfileCodecException> {
            ProfilePayloadV2JsonCodec.decode(ByteArray(ProfileLimits.MAX_PAYLOAD_BYTES + 1), origins)
        }
    }

    private fun validPayload(): String = "{" +
        "\"version\":2," +
        "\"profile_id\":\"debian-12-cloud\"," +
        "\"generation\":9," +
        "\"data_compatibility\":\"podroid-debian-12-genericcloud-v1\"," +
        "\"architecture\":\"aarch64\"," +
        "\"boot_contract\":\"podroid-uefi-nocloud-v1\"," +
        "\"storage_contract\":\"podroid-cloud-disk-v1\"," +
        "\"health_contract\":\"podroid-cloud-ready-v1\"," +
        "\"readiness_marker\":\"PODROID_CLOUD_READY_V1\"," +
        "\"supported_backends\":[\"qemu\"]," +
        "\"capabilities\":{\"guest_integrations\":[]}," +
        "\"artifacts\":[${ProfileV2ArtifactRole.entries.joinToString(",") { artifact(it) }}]" +
        "}"

    private fun artifact(role: ProfileV2ArtifactRole): String = "{" +
        "\"role\":\"${role.wireName}\"," +
        "\"format\":\"${role.requiredFormat.wireName}\"," +
        "\"url\":\"$ORIGIN/${role.wireName}\"," +
        "\"sha256\":\"$DIGEST\"," +
        "\"size_bytes\":1024}"

    private fun validV1Payload(): String = "{" +
        "\"version\":1,\"profile_id\":\"alpine-direct\",\"generation\":1," +
        "\"data_compatibility\":\"alpine-direct-v1\",\"architecture\":\"aarch64\"," +
        "\"boot_contract\":\"podroid-direct-v1\",\"storage_contract\":\"podroid-overlay-ext4-v1\"," +
        "\"health_contract\":\"podroid-ready-v1\",\"supported_backends\":[\"qemu\"]," +
        "\"artifacts\":[" + ArtifactRole.entries.joinToString(",") { role ->
            "{\"role\":\"${role.wireName}\",\"url\":\"$ORIGIN/${role.wireName}\",\"sha256\":\"$DIGEST\",\"size_bytes\":1024}"
        } + "]}"

    private inline fun <reified T : Throwable> assertFailure(label: String = "", block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertTrue("$label expected ${T::class.java.simpleName}, got $failure", failure is T)
    }

    private companion object {
        const val ORIGIN = "https://profiles.example:443"
        val DIGEST = "ab".repeat(32)
    }
}
