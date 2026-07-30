package com.excp.podroid.profiles

import com.excp.podroid.vm.ResolvedVmBootPlan
import com.excp.podroid.vm.UefiNoCloudVmBootPlan
import com.excp.podroid.vm.VmBootArtifact
import com.excp.podroid.vm.VmBootArtifacts
import com.excp.podroid.vm.VmBootDigest
import com.excp.podroid.vm.VmBootGeneration
import com.excp.podroid.vm.VmGuestCapabilities
import java.io.IOException

/** Resolves one complete validated active generation, or null when bundled assets remain active. */
fun interface ProfileBootArtifactSource {
    @Throws(IOException::class)
    fun resolveActiveBootArtifacts(selectedBackendId: String): ResolvedVmBootPlan?
}

class RepositoryProfileBootArtifactSource(
    private val repository: ProfileRepository,
) : ProfileBootArtifactSource {
    override fun resolveActiveBootArtifacts(selectedBackendId: String): ResolvedVmBootPlan? =
        repository.resolveActiveProfile()?.toResolvedVmBootPlan(requireSupportedBackend(selectedBackendId))
}

internal class RuntimeProfileBootArtifactSource(
    private val runtime: ActiveProfileRuntime,
) : ProfileBootArtifactSource {
    override fun resolveActiveBootArtifacts(selectedBackendId: String): ResolvedVmBootPlan? =
        runtime.resolveActiveProfile()?.toResolvedVmBootPlan(requireSupportedBackend(selectedBackendId))
}

private fun requireSupportedBackend(selectedBackendId: String): ProfileBackend =
    ProfileBackend.fromWireName(selectedBackendId)
        ?: throw ProfileActivationException("selected VM backend is not recognized by the profile contract")

internal fun PreparedProfile.toResolvedVmBootPlan(selectedBackend: ProfileBackend): ResolvedVmBootPlan {
    if (selectedBackend !in supportedBackends) {
        throw ProfileActivationException("active profile does not support selected backend '${selectedBackend.wireName}'")
    }
    fun bootArtifact(file: java.io.File, digest: Sha256Digest) = VmBootArtifact(
        file.absoluteFile,
        VmBootDigest(digest.value),
    )
    val generation = VmBootGeneration(candidate.generation.value)
    val manifest = VmBootDigest(candidate.manifestSha256.value)
    return when (val preparedPlan = plan) {
        is PreparedProfilePlan.DirectKernelOverlayV1 -> {
            fun artifact(role: ArtifactRole) = bootArtifact(
                preparedPlan.artifactFiles.getValue(role),
                preparedPlan.artifactDigests.getValue(role),
            )
            VmBootArtifacts(
                generation,
                manifest,
                artifact(ArtifactRole.KERNEL),
                artifact(ArtifactRole.INITRD),
                artifact(ArtifactRole.ROOTFS),
                supportedBackends.mapTo(linkedSetOf()) { it.wireName },
            )
        }
        is PreparedProfilePlan.UefiNoCloudV1 -> {
            fun artifact(role: ProfileV2ArtifactRole) = bootArtifact(
                preparedPlan.artifactFiles.getValue(role),
                preparedPlan.artifactDigests.getValue(role),
            )
            val integrations = preparedPlan.capabilities
            UefiNoCloudVmBootPlan(
                generation = generation,
                manifestSha256 = manifest,
                cloudRootDisk = preparedPlan.fixedStorageFile.absoluteFile,
                uefiCode = artifact(ProfileV2ArtifactRole.UEFI_CODE),
                uefiVars = preparedPlan.fixedVarsFile.absoluteFile,
                noCloudSeed = artifact(ProfileV2ArtifactRole.NOCLOUD_SEED),
                readinessMarker = preparedPlan.readinessMarker,
                capabilities = VmGuestCapabilities(
                    terminal = integrations.allows(ProfileV2GuestIntegration.PODROID_TERMINAL_V1),
                    resize = integrations.allows(ProfileV2GuestIntegration.PODROID_RESIZE_V1),
                    hostBridge = integrations.allows(ProfileV2GuestIntegration.PODROID_HOST_BRIDGE_V1),
                    downloads = integrations.allows(ProfileV2GuestIntegration.PODROID_DOWNLOADS_V1),
                ),
            )
        }
    }.also { it.requireBackend(selectedBackend.wireName) }
}
