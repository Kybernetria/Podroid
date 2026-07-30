package com.excp.podroid.profiles

import java.util.Collections

/** Closed limits for the UEFI/NoCloud profile v2 contract. */
object ProfileV2Limits {
    const val PAYLOAD_VERSION = 2L
    const val MAX_CLOUD_DISK_BYTES = 4L * 1024 * 1024 * 1024
    const val MAX_UEFI_CODE_BYTES = 64L * 1024 * 1024
    const val MAX_UEFI_VARS_TEMPLATE_BYTES = 64L * 1024 * 1024
    const val MAX_NOCLOUD_SEED_BYTES = 16L * 1024 * 1024
    const val MAX_TOTAL_ARTIFACT_BYTES = MAX_CLOUD_DISK_BYTES + MAX_UEFI_CODE_BYTES +
        MAX_UEFI_VARS_TEMPLATE_BYTES + MAX_NOCLOUD_SEED_BYTES
    const val READINESS_MARKER = "PODROID_CLOUD_READY_V1"
}

enum class ProfileV2ArtifactFormat(val wireName: String) {
    RAW("raw"),
    RAW_PFLASH("raw-pflash"),
    ISO9660_CIDATA("iso9660-cidata");

    companion object {
        fun fromWireName(value: String): ProfileV2ArtifactFormat? = entries.singleOrNull { it.wireName == value }
    }
}

enum class ProfileV2ArtifactRole(
    val wireName: String,
    val requiredFormat: ProfileV2ArtifactFormat,
    val maxSizeBytes: Long,
) {
    CLOUD_DISK("cloud-disk", ProfileV2ArtifactFormat.RAW, ProfileV2Limits.MAX_CLOUD_DISK_BYTES),
    UEFI_CODE("uefi-code", ProfileV2ArtifactFormat.RAW_PFLASH, ProfileV2Limits.MAX_UEFI_CODE_BYTES),
    UEFI_VARS_TEMPLATE(
        "uefi-vars-template",
        ProfileV2ArtifactFormat.RAW_PFLASH,
        ProfileV2Limits.MAX_UEFI_VARS_TEMPLATE_BYTES,
    ),
    NOCLOUD_SEED("nocloud-seed", ProfileV2ArtifactFormat.ISO9660_CIDATA, ProfileV2Limits.MAX_NOCLOUD_SEED_BYTES);

    companion object {
        fun fromWireName(value: String): ProfileV2ArtifactRole? = entries.singleOrNull { it.wireName == value }
    }
}

/**
 * Guest integrations require later engine/runtime composition. An absent capability is denied.
 * Version 2 currently recognizes these declarations but enables none by implication.
 */
enum class ProfileV2GuestIntegration(val wireName: String) {
    PODROID_TERMINAL_V1("podroid-terminal-v1"),
    PODROID_RESIZE_V1("podroid-resize-v1"),
    PODROID_HOST_BRIDGE_V1("podroid-host-bridge-v1"),
    PODROID_DOWNLOADS_V1("podroid-downloads-v1");

    companion object {
        fun fromWireName(value: String): ProfileV2GuestIntegration? = entries.singleOrNull { it.wireName == value }
    }
}

class ProfileV2Capabilities(guestIntegrations: Set<ProfileV2GuestIntegration> = emptySet()) {
    val guestIntegrations: Set<ProfileV2GuestIntegration> =
        Collections.unmodifiableSet(guestIntegrations.toSet())

    fun allows(integration: ProfileV2GuestIntegration): Boolean = integration in guestIntegrations

    override fun equals(other: Any?): Boolean =
        other is ProfileV2Capabilities && guestIntegrations == other.guestIntegrations

    override fun hashCode(): Int = guestIntegrations.hashCode()

    override fun toString(): String = "ProfileV2Capabilities(guestIntegrations=$guestIntegrations)"

    companion object {
        val DEFAULT_DENY = ProfileV2Capabilities()
    }
}

data class ProfileV2Artifact(
    val role: ProfileV2ArtifactRole,
    val format: ProfileV2ArtifactFormat,
    val url: ArtifactDownloadUrl,
    val sha256: Sha256Digest,
    val sizeBytes: Long,
) {
    init {
        require(format == role.requiredFormat) { "${role.wireName} requires ${role.requiredFormat.wireName} format" }
        require(sizeBytes in 1..role.maxSizeBytes) {
            "${role.wireName} size must be within its supported byte bound"
        }
    }
}

/** Strict signed input model for the closed QEMU UEFI/NoCloud runtime contract. */
class VmProfileV2(
    val id: ProfileId,
    val generation: ProfileGeneration,
    val dataCompatibility: DataCompatibilityId,
    val architecture: ProfileArchitecture,
    val bootContract: String,
    val storageContract: String,
    val healthContract: String,
    val readinessMarker: String,
    supportedBackends: Set<ProfileBackend>,
    val capabilities: ProfileV2Capabilities,
    artifacts: List<ProfileV2Artifact>,
) {
    val supportedBackends: Set<ProfileBackend> = Collections.unmodifiableSet(supportedBackends.toSet())
    val artifacts: List<ProfileV2Artifact> = Collections.unmodifiableList(artifacts.toList())

    init {
        require(architecture == ProfileArchitecture.AARCH64) { "profile v2 architecture must be aarch64" }
        require(bootContract == BOOT_CONTRACT) { "profile v2 boot contract is unsupported" }
        require(storageContract == STORAGE_CONTRACT) { "profile v2 storage contract is unsupported" }
        require(healthContract == HEALTH_CONTRACT) { "profile v2 health contract is unsupported" }
        require(readinessMarker == ProfileV2Limits.READINESS_MARKER) { "profile v2 readiness marker is unsupported" }
        require(this.supportedBackends == setOf(ProfileBackend.QEMU)) { "profile v2 supports exactly the QEMU backend" }
        require(this.artifacts.size == ProfileV2ArtifactRole.entries.size) {
            "profile v2 must contain exactly ${ProfileV2ArtifactRole.entries.size} artifacts"
        }
        require(this.artifacts.map { it.role }.toSet() == ProfileV2ArtifactRole.entries.toSet()) {
            "profile v2 must contain each closed artifact role exactly once"
        }
        var totalBytes = 0L
        this.artifacts.forEach { artifact ->
            require(artifact.sizeBytes <= ProfileV2Limits.MAX_TOTAL_ARTIFACT_BYTES - totalBytes) {
                "profile v2 artifact total exceeds the supported byte bound"
            }
            totalBytes += artifact.sizeBytes
        }
    }

    fun artifact(role: ProfileV2ArtifactRole): ProfileV2Artifact = artifacts.single { it.role == role }

    override fun equals(other: Any?): Boolean =
        other is VmProfileV2 && id == other.id && generation == other.generation &&
            dataCompatibility == other.dataCompatibility && architecture == other.architecture &&
            bootContract == other.bootContract && storageContract == other.storageContract &&
            healthContract == other.healthContract && readinessMarker == other.readinessMarker &&
            supportedBackends == other.supportedBackends && capabilities == other.capabilities &&
            artifacts == other.artifacts

    override fun hashCode(): Int = listOf(
        id, generation, dataCompatibility, architecture, bootContract, storageContract,
        healthContract, readinessMarker, supportedBackends, capabilities, artifacts,
    ).fold(1) { result, value -> 31 * result + value.hashCode() }

    override fun toString(): String =
        "VmProfileV2(id=$id, generation=$generation, dataCompatibility=$dataCompatibility, " +
            "architecture=$architecture, supportedBackends=$supportedBackends, capabilities=$capabilities, " +
            "artifacts=$artifacts)"

    companion object {
        const val BOOT_CONTRACT = "podroid-uefi-nocloud-v1"
        const val STORAGE_CONTRACT = "podroid-cloud-disk-v1"
        const val HEALTH_CONTRACT = "podroid-cloud-ready-v1"
    }
}

object ProfileV2DataLineage {
    val DEBIAN_12_GENERICCLOUD = DataCompatibilityId("podroid-debian-12-genericcloud-v1")
}
