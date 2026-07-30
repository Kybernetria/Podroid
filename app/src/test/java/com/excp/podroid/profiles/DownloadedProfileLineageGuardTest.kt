package com.excp.podroid.profiles

import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DownloadedProfileLineageGuardTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun `missing configuration fallback is allowed before any downloaded activation`() {
        val repository = temporaryFolder.newFolder("empty-store").toPath()
        DownloadedProfileLineageGuard.requireBundledFallbackAllowed(repository)
    }

    @Test fun `missing configuration fallback is blocked after downloaded lineage claim`() {
        val repository = temporaryFolder.newFolder("claimed-store").toPath()
        val state = repository.resolve("state").also { it.toFile().mkdir() }
        state.resolve("downloaded-lineage.claimed").toFile()
            .writeText("podroid-downloaded-profile-lineage-v1\n")

        val failure = runCatching {
            DownloadedProfileLineageGuard.requireBundledFallbackAllowed(repository)
        }.exceptionOrNull()

        assertTrue(failure is ProfileActivationException)
    }

    @Test fun `malformed lineage marker fails closed`() {
        val repository = temporaryFolder.newFolder("bad-store").toPath()
        val state = repository.resolve("state").also { it.toFile().mkdir() }
        state.resolve("downloaded-lineage.claimed").toFile().writeText("invalid")

        val failure = runCatching {
            DownloadedProfileLineageGuard.requireBundledFallbackAllowed(repository)
        }.exceptionOrNull()

        assertTrue(failure is ProfileRepositoryCorruptException)
    }
}
