/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.vm

import android.content.Context
import com.excp.podroid.PodroidApplication
import com.excp.podroid.data.repository.PortForwardRepository
import com.excp.podroid.data.repository.PortForwardRule
import com.excp.podroid.data.repository.SettingsRepository
import com.excp.podroid.engine.VmConfig
import com.excp.podroid.profiles.ProfileBootArtifactSource
import com.excp.podroid.util.NetworkUtils

/** Reuses the application's hardened, atomic extraction path for reinstalls. */
internal class ApplicationVmInstaller(private val context: Context) : VmInstaller {
    override suspend fun awaitInitial(vmId: VmId) {
        require(vmId == VmId.DEFAULT) { "Only the default VM is supported" }
        application().awaitAssetsReady()
    }

    override suspend fun <T> withExclusiveTree(
        vmId: VmId,
        action: suspend (VmAssetTreeLease) -> T,
    ): T {
        require(vmId == VmId.DEFAULT) { "Only the default VM is supported" }
        return application().withVmAssetTreeLease { installAssets ->
            action(object : VmAssetTreeLease {
                override suspend fun install(vmId: VmId) {
                    require(vmId == VmId.DEFAULT) { "Only the default VM is supported" }
                    installAssets()
                }
            })
        }
    }

    private fun application(): PodroidApplication =
        context.applicationContext as? PodroidApplication
            ?: throw IllegalStateException("Podroid application installer is unavailable")
}

/** Owns all service-independent settings/network/forward launch assembly. */
internal class RepositoryVmConfigurationSource(
    private val context: Context,
    private val settings: SettingsRepository,
    private val portForwards: PortForwardRepository,
    private val profileBootArtifacts: ProfileBootArtifactSource,
    private val selectedBackendId: () -> String,
) : VmConfigurationSource {
    override suspend fun launchPlan(vmId: VmId): VmLaunchPlan {
        require(vmId == VmId.DEFAULT) { "Only the default VM is supported" }
        val persistedRules = portForwards.getRulesSnapshot()
        val sshEnabled = settings.getSshEnabledSnapshot()
        val rules = assembleRules(persistedRules, sshEnabled)
        // DefaultVmManager invokes launchPlan while it owns the application asset-tree lease.
        // Any configured active profile is repository/trust/digest validated here; only a truly
        // absent activation returns null and selects the bundled legacy paths.
        val bootArtifacts = profileBootArtifacts.resolveActiveBootArtifacts(selectedBackendId())
        val qemuExtraArgs = settings.getQemuExtraArgsSnapshot()
        requireSignedProfileQemuArgsAreClosed(bootArtifacts != null, qemuExtraArgs)

        return VmLaunchPlan(
            portForwards = rules,
            config = VmConfig(
                vmId = vmId,
                bootArtifacts = bootArtifacts,
                ramMb = settings.getVmRamMbSnapshot(),
                cpus = settings.getVmCpusSnapshot(),
                sshEnabled = sshEnabled,
                androidIp = NetworkUtils.localIpv4(context),
                storageSizeGb = settings.getStorageSizeGbSnapshot(),
                storageAccessEnabled = settings.getStorageAccessEnabledSnapshot(),
                qemuExtraArgs = qemuExtraArgs,
                kernelExtraCmdline = settings.getKernelExtraCmdlineSnapshot(),
                verboseLogging = settings.getAvfVerboseLoggingSnapshot(),
                x11Dpi = settings.getX11DpiSnapshot(),
                usbPassthroughEnabled = settings.getUsbPassthroughEnabledSnapshot(),
                bandwidthMbps = settings.getBandwidthMbpsSnapshot(),
            ),
        )
    }

    override suspend fun sshEnabled(vmId: VmId): Boolean {
        require(vmId == VmId.DEFAULT) { "Only the default VM is supported" }
        return settings.getSshEnabledSnapshot()
    }

    companion object {
        internal fun requireSignedProfileQemuArgsAreClosed(
            downloadedProfileActive: Boolean,
            qemuExtraArgs: String,
        ) {
            if (downloadedProfileActive && qemuExtraArgs.isNotBlank()) {
                throw IllegalStateException(
                    "QEMU extra arguments must be blank while a downloaded profile is active",
                )
            }
        }

        internal fun assembleRules(
            persistedRules: List<PortForwardRule>,
            sshEnabled: Boolean,
        ): List<PortForwardRule> {
            if (!sshEnabled) return persistedRules.toList()
            val implicitSsh = PortForwardRule(
                hostPort = DefaultVmManager.SSH_HOST_PORT,
                guestPort = 22,
                protocol = "tcp",
                loopbackOnly = true,
            )
            check(persistedRules.none {
                it.protocol == "tcp" &&
                    it.hostPort == DefaultVmManager.SSH_HOST_PORT &&
                    it != implicitSsh
            }) {
                "TCP ${DefaultVmManager.SSH_HOST}:${DefaultVmManager.SSH_HOST_PORT} is reserved for enabled SSH"
            }
            return if (implicitSsh in persistedRules) persistedRules.toList() else persistedRules + implicitSsh
        }
    }
}
