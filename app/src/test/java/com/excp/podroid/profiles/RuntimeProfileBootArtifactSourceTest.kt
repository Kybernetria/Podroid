package com.excp.podroid.profiles

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeProfileBootArtifactSourceTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun `unavailable runtime selects bundled artifacts without consulting store`() = runBlocking {
        var resolutions = 0
        val runtime = object : ActiveProfileRuntime {
            override val availability = DownloadableProfileAvailability.Unavailable(
                DownloadableProfileUnavailableReason.INVALID_CONFIGURATION,
            )
            override fun resolveActiveProfile(): PreparedProfile? {
                resolutions++
                error("must not resolve")
            }
        }

        assertNull(RuntimeProfileBootArtifactSource(runtime).resolveActiveBootArtifacts())
        assertEquals(0, resolutions)
    }

    @Test fun `configured runtime with no activation selects bundled artifacts`() = runBlocking {
        val runtime = fakeRuntime { null }

        assertNull(RuntimeProfileBootArtifactSource(runtime).resolveActiveBootArtifacts())
    }

    @Test fun `configured active corruption propagates and blocks bundled fallback`() = runBlocking {
        val runtime = fakeRuntime { throw ProfileRepositoryCorruptException("corrupt active generation") }

        try {
            RuntimeProfileBootArtifactSource(runtime).resolveActiveBootArtifacts()
            fail("Expected active profile corruption")
        } catch (_: ProfileRepositoryCorruptException) {
            Unit
        }
    }

    @Test fun `configured active generation maps all validated boot artifacts`() = runBlocking {
        val files = ArtifactRole.entries.associateWith { role ->
            temporaryFolder.newFile(role.wireName).apply { writeText(role.wireName) }
        }
        val digest = Sha256Digest("a".repeat(64))
        val prepared = PreparedProfile(
            candidate = candidate(),
            dataCompatibility = DataCompatibilityId("stable"),
            artifactFiles = files,
            artifactDigests = ArtifactRole.entries.associateWith { digest },
        )

        val resolved = RuntimeProfileBootArtifactSource(fakeRuntime { prepared })
            .resolveActiveBootArtifacts()!!

        assertEquals(7L, resolved.generation.value)
        assertEquals(files.getValue(ArtifactRole.KERNEL).absoluteFile, resolved.kernel.file)
        assertEquals(files.getValue(ArtifactRole.INITRD).absoluteFile, resolved.initrd.file)
        assertEquals(files.getValue(ArtifactRole.ROOTFS).absoluteFile, resolved.rootfs.file)
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
