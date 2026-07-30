package com.excp.podroid.profiles

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileSettingsWorkflowTest {
    private val candidate = PreparedProfileCandidate(
        ProfileId("alpine-direct"),
        ProfileGeneration(3),
        Sha256Digest("a".repeat(64)),
        SigningKeyId("release-1"),
        Sha256Digest("b".repeat(64)),
        TrustEpoch(1),
    )

    @Test fun `preserve activation never requests destructive confirmation`() = kotlinx.coroutines.runBlocking {
        val lifecycle = FakeLifecycle()
        val workflow = ProfileSettingsWorkflow(FakePreparation(), lifecycle)

        workflow.activate(candidate, GuestDataPolicy.PRESERVE_DATA)

        assertEquals(0, lifecycle.confirmationCalls)
        assertNull(lifecycle.receivedConfirmation)
    }

    @Test fun `delete activation obtains a fresh confirmation immediately before manager activation`() =
        kotlinx.coroutines.runBlocking {
            val lifecycle = FakeLifecycle()
            val workflow = ProfileSettingsWorkflow(FakePreparation(), lifecycle)

            workflow.activate(candidate, GuestDataPolicy.DELETE_DATA)
            workflow.activate(candidate, GuestDataPolicy.DELETE_DATA)

            assertEquals(2, lifecycle.confirmationCalls)
            assertEquals(2, lifecycle.activationCalls)
            assertEquals(lifecycle.latestConfirmation, lifecycle.receivedConfirmation)
        }

    @Test fun `workflow bounds URL before preparation`() = kotlinx.coroutines.runBlocking {
        val preparation = FakePreparation()
        val workflow = ProfileSettingsWorkflow(preparation, FakeLifecycle())

        runCatching { workflow.prepare("x".repeat(ProfileLimits.MAX_URL_CHARS + 1)) }

        assertEquals(0, preparation.prepareCalls)
    }

    private inner class FakePreparation : ProfilePreparationOperations {
        override val availability = DownloadableProfileAvailability.Available
        var prepareCalls = 0
        override suspend fun prepareEnvelopeUrl(url: String): PreparedProfile {
            prepareCalls++
            throw UnsupportedOperationException()
        }
        override suspend fun diagnosticActivationState() = ProfileActivationDiagnostic(
            availability, null, null, null,
        )
    }

    private inner class FakeLifecycle : ProfileLifecycleOperations {
        var confirmationCalls = 0
        var activationCalls = 0
        var latestConfirmation: DataDeletionConfirmation? = null
        var receivedConfirmation: DataDeletionConfirmation? = null

        override suspend fun issueDataDeletionConfirmation(
            candidate: PreparedProfileCandidate,
        ): DataDeletionConfirmation {
            confirmationCalls++
            return DataDeletionConfirmation.issue(
                owner = Any(),
                expectedActivationSequence = confirmationCalls.toLong(),
                candidate = candidate,
                storageIdentity = StorageIdentity("parent", "time", false, null, null, null, null),
            ).also { latestConfirmation = it }
        }

        override suspend fun activateProfile(
            candidate: PreparedProfileCandidate,
            dataPolicy: GuestDataPolicy,
            deletionConfirmation: DataDeletionConfirmation?,
        ): ActivationState {
            activationCalls++
            receivedConfirmation = deletionConfirmation
            return ActivationState(activationCalls.toLong(), candidate, null)
        }

        override suspend fun rollbackProfile(
            expectedActivationSequence: Long,
            dataPolicy: GuestDataPolicy,
        ): ActivationState = ActivationState(expectedActivationSequence + 1, candidate, null)
    }
}
