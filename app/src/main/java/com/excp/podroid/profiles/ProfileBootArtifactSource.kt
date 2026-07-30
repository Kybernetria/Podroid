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

/** Read-only launch adapter; profile preparation and activation remain owned by [ProfileRepository]. */
class RepositoryProfileBootArtifactSource(
    private val repository: ProfileRepository,
) : ProfileBootArtifactSource {
    override fun resolveActiveBootArtifacts(): VmBootArtifacts? =
        repository.resolveActiveProfile()?.toVmBootArtifacts()

    private fun PreparedProfile.toVmBootArtifacts(): VmBootArtifacts {
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
}

/** Temporary production binding until the separate downloader/repository composition is added. */
object BundledProfileBootArtifactSource : ProfileBootArtifactSource {
    override fun resolveActiveBootArtifacts(): VmBootArtifacts? = null
}
