package com.excp.podroid.profiles

import javax.inject.Inject

/** Read/prepare capability used by the local Settings workflow; it carries no lifecycle mutation. */
interface ProfilePreparationOperations {
    val availability: DownloadableProfileAvailability
    suspend fun prepareEnvelopeUrl(url: String): PreparedProfile
    suspend fun diagnosticActivationState(): ProfileActivationDiagnostic
}

/** Local-only use-case joining preparation with the manager-owned lifecycle authority. */
class ProfileSettingsWorkflow @Inject constructor(
    private val preparation: ProfilePreparationOperations,
    private val lifecycle: ProfileLifecycleOperations,
) {
    val availability: DownloadableProfileAvailability get() = preparation.availability

    suspend fun diagnostics(): ProfileActivationDiagnostic = preparation.diagnosticActivationState()

    suspend fun prepare(envelopeUrl: String): PreparedProfile {
        require(envelopeUrl.isNotBlank()) { "profile envelope URL is required" }
        require(envelopeUrl.length <= ProfileLimits.MAX_URL_CHARS) { "profile envelope URL exceeds the length bound" }
        return preparation.prepareEnvelopeUrl(envelopeUrl)
    }

    suspend fun activate(candidate: PreparedProfileCandidate, dataPolicy: GuestDataPolicy): ActivationState {
        val confirmation = if (dataPolicy == GuestDataPolicy.DELETE_DATA) {
            // Issued immediately before activation; callers never retain or manufacture destructive authority.
            lifecycle.issueDataDeletionConfirmation(candidate)
        } else {
            null
        }
        return lifecycle.activateProfile(candidate, dataPolicy, confirmation)
    }

    suspend fun rollbackCurrent(activationSequence: Long): ActivationState {
        require(activationSequence > 0) { "activation sequence must be positive" }
        return lifecycle.rollbackProfile(activationSequence, GuestDataPolicy.PRESERVE_DATA)
    }
}
