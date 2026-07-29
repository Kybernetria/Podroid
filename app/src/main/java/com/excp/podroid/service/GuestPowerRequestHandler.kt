/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.service

import com.excp.podroid.engine.hostbridge.HostProtocol
import com.excp.podroid.vm.LifecycleOperation
import com.excp.podroid.vm.LifecycleTransactionToken
import com.excp.podroid.vm.VmLifecycleState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

/**
 * Suspend-capable guest POWER admission boundary.
 *
 * STOP/RESTART return OK only after [admitAndSchedule] has durably prepared and
 * claimed the command, then scheduled the exact token for delayed dispatch.
 */
internal class GuestPowerRequestHandler(
    private val lifecycle: () -> VmLifecycleState,
    private val admitAndSchedule: suspend (
        operation: LifecycleOperation,
        schedule: (LifecycleTransactionToken) -> Unit,
    ) -> LifecycleTransactionToken,
    private val schedule: (LifecycleTransactionToken) -> Unit,
    private val admissionFailed: (Throwable) -> Unit,
) {
    suspend fun handle(action: String): String = when (action) {
        "status" -> HostProtocol.ok(when (lifecycle()) {
            VmLifecycleState.IDLE -> "idle"
            VmLifecycleState.STARTING -> "starting"
            VmLifecycleState.RUNNING -> "running"
            VmLifecycleState.STOPPED -> "stopped"
            VmLifecycleState.ERROR -> "error"
        })
        "stop" -> admit(LifecycleOperation.STOP)
        "restart" -> admit(LifecycleOperation.RESTART)
        else -> HostProtocol.err("usage: stop|restart|status")
    }

    private suspend fun admit(operation: LifecycleOperation): String = try {
        admitAndSchedule(operation, schedule)
        HostProtocol.ok()
    } catch (timeout: TimeoutCancellationException) {
        admissionError(timeout)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        admissionError(failure)
    }

    private fun admissionError(failure: Throwable): String {
        admissionFailed(failure)
        return HostProtocol.err("power command admission failed")
    }
}
