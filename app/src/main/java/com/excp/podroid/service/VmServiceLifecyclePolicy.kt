/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.service

import com.excp.podroid.vm.VmLifecycleState

internal enum class RuntimeChannelDirective { START, STOP, KEEP }
internal enum class VmServiceNotification { STARTING, RUNNING, CLEANUP_INCOMPLETE, NONE }

internal data class VmServiceLifecycleDecision(
    val retainSupervision: Boolean,
    val teardown: Boolean,
    val runtimeChannels: RuntimeChannelDirective,
    val notification: VmServiceNotification,
)

internal data class VmServiceStartDecision(
    val launchNewGeneration: Boolean,
    val armSupervision: Boolean = true,
    val acquireWakeLock: Boolean = true,
)

/** Pure ACTION_START policy: busy work is supervised but never relaunched. */
internal object VmServiceStartPolicy {
    fun decide(managerBusy: Boolean, pendingStartOwned: Boolean) = VmServiceStartDecision(
        launchNewGeneration = !managerBusy && !pendingStartOwned,
    )
}

/** Pure adapter from manager lifecycle signals to foreground-service effects. */
internal object VmServiceLifecyclePolicy {
    fun decide(
        lifecycle: VmLifecycleState,
        quiescent: Boolean,
        busy: Boolean,
        pendingStartOwned: Boolean = false,
    ): VmServiceLifecycleDecision {
        val terminal = lifecycle == VmLifecycleState.IDLE ||
            lifecycle == VmLifecycleState.STOPPED || lifecycle == VmLifecycleState.ERROR
        return VmServiceLifecycleDecision(
            retainSupervision = pendingStartOwned || busy || !quiescent,
            teardown = terminal && quiescent && !busy && !pendingStartOwned,
            runtimeChannels = when {
                lifecycle == VmLifecycleState.RUNNING -> RuntimeChannelDirective.START
                quiescent -> RuntimeChannelDirective.STOP
                else -> RuntimeChannelDirective.KEEP
            },
            notification = when {
                lifecycle == VmLifecycleState.ERROR && !quiescent ->
                    VmServiceNotification.CLEANUP_INCOMPLETE
                lifecycle == VmLifecycleState.RUNNING -> VmServiceNotification.RUNNING
                lifecycle == VmLifecycleState.STARTING -> VmServiceNotification.STARTING
                else -> VmServiceNotification.NONE
            },
        )
    }
}
