package com.excp.podroid.profiles

import com.excp.podroid.vm.VmBootArtifact
import com.excp.podroid.vm.VmBootArtifacts
import com.excp.podroid.vm.VmBootDigest
import com.excp.podroid.vm.VmBootGeneration
import java.io.IOException

/** Resolves one complete validated active generation, or null when bundled assets remain active. */
fun interface ProfileBootArtifactSource {
    @Throws(IOException::class)
    fun resolveActiveBootArtifacts(selectedBackendId: String): VmBootArtifacts?
}

/** Read-only adapter over a configured repository; validation failures remain launch-blocking. */
class RepositoryProfileBootArtifactSource(
    private val repository: ProfileRepository,
) : ProfileBootArtifactSource {
    override fun resolveActiveBootArtifacts(selectedBackendId: String): VmBootArtifacts? =
        repository.resolveActiveProfile()?.toVmBootArtifacts(requireSupportedBackend(selectedBackendId))
}

/** Production source: only an explicit, lineage-safe absence selects bundled assets. */
internal class RuntimeProfileBootArtifactSource(
    private val runtime: ActiveProfileRuntime,
) : ProfileBootArtifactSource {
    override fun resolveActiveBootArtifacts(selectedBackendId: String): VmBootArtifacts? =
        runtime.resolveActiveProfile()?.toVmBootArtifacts(requireSupportedBackend(selectedBackendId))
}

private fun requireSupportedBackend(selectedBackendId: String): ProfileBackend =
    ProfileBackend.fromWireName(selectedBackendId)
        ?: throw ProfileActivationException("selected VM backend is not recognized by the profile contract")

internal fun PreparedProfile.toVmBootArtifacts(selectedBackend: ProfileBackend): VmBootArtifacts {
    if (selectedBackend !in supportedBackends) {
        throw ProfileActivationException("active profile does not support selected backend '${selectedBackend.wireName}'")
    }
    fun artifact(role: ArtifactRole): VmBootArtifact = VmBootArtifact(
        file = artifactFiles.getValue(role).absoluteFile,
        sha256 = VmBootDigest(artifactDigests.getValue(role).value),
    )
    return VmBootArtifacts(
        generation = VmBootGeneration(candidate.generation.value),
        manifestSha256 = VmBootDigest(candidate.manifestSha256.value),
        kernel = artifact(ArtifactRole.KERNEL),
        initrd = artifact(ArtifactRole.INITRD),
        rootfs = artifact(ArtifactRole.ROOTFS),
        supportedBackendIds = supportedBackends.mapTo(linkedSetOf()) { it.wireName },
    )
}
