package com.excp.podroid.profiles

import com.excp.podroid.vm.VmBootArtifact
import com.excp.podroid.vm.VmBootArtifacts
import com.excp.podroid.vm.VmBootDigest
import com.excp.podroid.vm.VmBootGeneration
import java.io.IOException

/** Resolves one complete validated active generation, or null when bundled assets remain active. */
fun interface ProfileBootArtifactSource {
    @Throws(IOException::class)
    fun resolveActiveBootArtifacts(): VmBootArtifacts?
}

/** Read-only adapter over a configured repository; validation failures remain launch-blocking. */
class RepositoryProfileBootArtifactSource(
    private val repository: ProfileRepository,
) : ProfileBootArtifactSource {
    override fun resolveActiveBootArtifacts(): VmBootArtifacts? =
        repository.resolveActiveProfile()?.toVmBootArtifacts()
}

/** Production source: unavailable configuration and absent activation alone select bundled assets. */
internal class RuntimeProfileBootArtifactSource(
    private val runtime: ActiveProfileRuntime,
) : ProfileBootArtifactSource {
    override fun resolveActiveBootArtifacts(): VmBootArtifacts? = when (runtime.availability) {
        DownloadableProfileAvailability.Available -> runtime.resolveActiveProfile()?.toVmBootArtifacts()
        is DownloadableProfileAvailability.Unavailable -> null
    }
}

internal fun PreparedProfile.toVmBootArtifacts(): VmBootArtifacts {
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
    )
}
