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
import com.excp.podroid.util.NetworkUtils

/** Reuses the application's hardened, atomic extraction path for reinstalls. */
internal class ApplicationVmInstaller(private val context: Context) : VmInstaller {
    override suspend fun install(vmId: VmId) {
        require(vmId == VmId.DEFAULT) { "Only the default VM is supported" }
        val application = context.applicationContext as? PodroidApplication
            ?: throw IllegalStateException("Podroid application installer is unavailable")
        application.installVmAssets()
    }

    override suspend fun awaitIdle(vmId: VmId) {
        require(vmId == VmId.DEFAULT) { "Only the default VM is supported" }
        val application = context.applicationContext as? PodroidApplication
            ?: throw IllegalStateException("Podroid application installer is unavailable")
        application.awaitVmAssetInstallerIdle()
    }
}

/** Owns all service-independent settings/network/forward launch assembly. */
internal class RepositoryVmConfigurationSource(
    private val context: Context,
    private val settings: SettingsRepository,
    private val portForwards: PortForwardRepository,
) : VmConfigurationSource {
    override suspend fun launchPlan(vmId: VmId): VmLaunchPlan {
        require(vmId == VmId.DEFAULT) { "Only the default VM is supported" }
        val rules = portForwards.getRulesSnapshot().toMutableList()
        val sshEnabled = settings.getSshEnabledSnapshot()
        if (sshEnabled) {
            val conflictingSshPort = rules.any {
                it.protocol == "tcp" && it.hostPort == DefaultVmManager.SSH_HOST_PORT && it.guestPort != 22
            }
            check(!conflictingSshPort) {
                "TCP port ${DefaultVmManager.SSH_HOST_PORT} is reserved for enabled SSH"
            }
            if (rules.none {
                    it.protocol == "tcp" &&
                        it.hostPort == DefaultVmManager.SSH_HOST_PORT &&
                        it.guestPort == 22
                }) {
                rules.add(PortForwardRule(DefaultVmManager.SSH_HOST_PORT, 22, "tcp"))
            }
        }

        return VmLaunchPlan(
            portForwards = rules,
            config = VmConfig(
                vmId = vmId,
                ramMb = settings.getVmRamMbSnapshot(),
                cpus = settings.getVmCpusSnapshot(),
                sshEnabled = sshEnabled,
                androidIp = NetworkUtils.localIpv4(context),
                storageSizeGb = settings.getStorageSizeGbSnapshot(),
                storageAccessEnabled = settings.getStorageAccessEnabledSnapshot(),
                qemuExtraArgs = settings.getQemuExtraArgsSnapshot(),
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
}
