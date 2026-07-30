package com.excp.podroid.profiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeProfileBootArtifactSourceTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun `unavailable runtime selects bundled artifacts only after lineage guard resolution`() {
        var resolutions = 0
        val runtime = object : ActiveProfileRuntime {
            override val availability = DownloadableProfileAvailability.Unavailable(
                DownloadableProfileUnavailableReason.INVALID_CONFIGURATION,
            )
            override fun resolveActiveProfile(): PreparedProfile? {
                resolutions++
                return null
            }
        }

        assertNull(RuntimeProfileBootArtifactSource(runtime).resolveActiveBootArtifacts("qemu"))
        assertEquals(1, resolutions)
    }

    @Test fun `configured runtime with no activation selects bundled artifacts`() {
        assertNull(RuntimeProfileBootArtifactSource(fakeRuntime { null }).resolveActiveBootArtifacts("qemu"))
    }

    @Test fun `configured active corruption propagates and blocks bundled fallback`() {
        val runtime = fakeRuntime { throw ProfileRepositoryCorruptException("corrupt active generation") }

        try {
            RuntimeProfileBootArtifactSource(runtime).resolveActiveBootArtifacts("qemu")
            fail("Expected active profile corruption")
        } catch (_: ProfileRepositoryCorruptException) {
            Unit
        }
    }

    @Test fun `configured active generation maps all validated boot artifacts`() {
        val prepared = prepared(setOf(ProfileBackend.QEMU))

        val resolved = RuntimeProfileBootArtifactSource(fakeRuntime { prepared })
            .resolveActiveBootArtifacts("qemu")!!

        assertEquals(7L, resolved.generation.value)
        assertEquals(prepared.artifactFiles.getValue(ArtifactRole.KERNEL).absoluteFile, resolved.kernel.file)
        assertEquals(prepared.artifactFiles.getValue(ArtifactRole.INITRD).absoluteFile, resolved.initrd.file)
        assertEquals(prepared.artifactFiles.getValue(ArtifactRole.ROOTFS).absoluteFile, resolved.rootfs.file)
    }

    @Test fun `selected backend must be declared by active profile`() {
        val failure = runCatching {
            RuntimeProfileBootArtifactSource(fakeRuntime { prepared(setOf(ProfileBackend.QEMU)) })
                .resolveActiveBootArtifacts("avf")
        }.exceptionOrNull()

        assertEquals(ProfileActivationException::class.java, failure?.javaClass)
    }

    private fun prepared(backends: Set<ProfileBackend>): PreparedProfile {
        val files = ArtifactRole.entries.associateWith { role ->
            temporaryFolder.newFile("${backends.first().wireName}-${role.wireName}").apply { writeText(role.wireName) }
        }
        val digest = Sha256Digest("a".repeat(64))
        return PreparedProfile(
            candidate = candidate(),
            dataCompatibility = ProfileDataLineage.BUNDLED_ALPINE,
            supportedBackends = backends,
            artifactFiles = files,
            artifactDigests = ArtifactRole.entries.associateWith { digest },
        )
    }

    private fun fakeRuntime(resolve: () -> PreparedProfile?): ActiveProfileRuntime =
        object : ActiveProfileRuntime {
            override val availability = DownloadableProfileAvailability.Available
            override fun resolveActiveProfile(): PreparedProfile? = resolve()
        }

    private fun candidate() = PreparedProfileCandidate(
        profileId = ProfileId("default"),
        generation = ProfileGeneration(7),
        manifestSha256 = Sha256Digest("b".repeat(64)),
        signingKeyId = SigningKeyId("release-1"),
        signingKeyFingerprint = Sha256Digest("c".repeat(64)),
        trustEpoch = TrustEpoch(1),
    )
}
