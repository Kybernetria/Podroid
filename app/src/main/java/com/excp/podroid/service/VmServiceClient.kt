/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.excp.podroid.di.ApplicationScope
import com.excp.podroid.vm.ConsoleLog
import com.excp.podroid.vm.ConsoleLogRequest
import com.excp.podroid.vm.MonotonicDeadline
import com.excp.podroid.vm.SshEndpointDiscovery
import com.excp.podroid.vm.VmDiagnostics
import com.excp.podroid.vm.VmDiagnosticsRequest
import com.excp.podroid.vm.VmId
import com.excp.podroid.vm.VmLifecycleState
import com.excp.podroid.vm.VmObservation
import com.excp.podroid.vm.VmQmpOperation
import com.excp.podroid.vm.VmQmpResult
import com.excp.podroid.vm.VmRemovePolicy
import com.excp.podroid.vm.VmRuntimeMetrics
import com.excp.podroid.vm.VmStatus
import com.excp.podroid.vm.VmSummary
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Application-lifetime client for the non-exported local Binder. Activity and
 * ViewModel recreation cannot accidentally unbind or stop the VM. Android also
 * keeps a started foreground service alive independently of this binding.
 */
@Singleton
class VmServiceClient @Inject internal constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val state = VmBindingStateMachine(
        scope = scope,
        initialObservation = VmObservation(
            vmId = VmId.DEFAULT,
            lifecycle = VmLifecycleState.IDLE,
            backendId = "unknown",
        ),
    )
    private var bindRequested = false
    private var pendingTerminalRelease: java.lang.ref.WeakReference<TerminalSessionClient>? = null

    val bindingState: StateFlow<VmBindingState> = state.bindingState
    val observation: StateFlow<VmObservation> = state.observation
    val headlessMode: StateFlow<Boolean> = state.headlessMode
    val vmState: StateFlow<VmUiState> = observation.map { it.toUiState() }
        .stateIn(scope, SharingStarted.Eagerly, VmUiState.Idle)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val local = binder as? PodroidService.LocalBinder
            if (local == null) {
                Log.e(TAG, "Rejected unexpected Binder implementation from $name")
                state.disconnected()
                return
            }
            state.connected(local.endpoint)
            synchronized(this@VmServiceClient) {
                pendingTerminalRelease?.get()?.let(local.endpoint::releaseTerminalClient)
                pendingTerminalRelease = null
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            state.disconnected()
            // Framework normally reconnects this binding. Do not issue Stop: a
            // started foreground VM outlives client/process binding loss.
            Log.w(TAG, "VM service binding disconnected: $name")
        }

        override fun onBindingDied(name: ComponentName) {
            state.disconnected()
            runCatching { context.unbindService(this) }
            synchronized(this@VmServiceClient) { bindRequested = false }
            scope.launch {
                delay(REBIND_DELAY_MS)
                bind()
            }
        }

        override fun onNullBinding(name: ComponentName) {
            state.disconnected()
            Log.e(TAG, "VM service returned a null binding: $name")
        }
    }

    init { bind() }

    @Synchronized
    private fun bind() {
        if (bindRequested) return
        bindRequested = true
        state.connecting()
        val accepted = runCatching {
            context.bindService(
                Intent(context, PodroidService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        }.getOrElse {
            Log.e(TAG, "VM service bind failed", it)
            false
        }
        if (!accepted) {
            bindRequested = false
            state.disconnected()
        }
    }

    suspend fun list(): List<VmSummary> = call { it.list() }
    suspend fun status(): VmStatus = call { it.status() }
    suspend fun ensureInstalled() = call { it.ensureInstalled() }
    suspend fun start() = call { it.start() }
    suspend fun gracefulStop() = call { it.gracefulStop() }
    suspend fun forceStop() = call { it.forceStop() }
    suspend fun restart() = call { it.restart() }
    suspend fun remove(policy: VmRemovePolicy) = call { it.remove(policy) }
    suspend fun readConsoleLog(request: ConsoleLogRequest): ConsoleLog = call { it.readConsoleLog(request) }
    suspend fun executeQmp(operation: VmQmpOperation): VmQmpResult = call { it.executeQmp(operation) }
    suspend fun discoverSshEndpoint(): SshEndpointDiscovery = call { it.discoverSshEndpoint() }
    suspend fun runtimeMetrics(): VmRuntimeMetrics = call { it.runtimeMetrics() }
    suspend fun diagnostics(request: VmDiagnosticsRequest): VmDiagnostics = call { it.diagnostics(request) }
    suspend fun backendProbe(): VmBackendProbe = call { it.backendProbe() }
    suspend fun runBackendSmokeTest(): String {
        val deadlineNanos = MonotonicDeadline.afterMillis(
            DEFAULT_BACKEND_SMOKE_TOTAL_DEADLINE_MS,
        )
        bind()
        return state.commandUntil(
            deadlineNanos = deadlineNanos,
            timeoutResult = backendSmokeDeadlineResult(DEFAULT_BACKEND_SMOKE_TOTAL_DEADLINE_MS),
        ) { it.runBackendSmokeTest(deadlineNanos) }
    }
    suspend fun setHeadlessMode(active: Boolean) = call { it.setHeadlessMode(active) }

    fun createTerminalSession(client: TerminalSessionClient): TerminalSession =
        endpointNow().createTerminalSession(client)

    fun releaseTerminalClient(client: TerminalSessionClient) {
        synchronized(this) {
            val connected = state.connectedEndpointOrNull()
            if (connected != null) {
                connected.releaseTerminalClient(client)
            } else {
                // Binding loss must not retain a dead Activity through the engine's
                // singleton delegate. Keep only a weak, single-slot cleanup request
                // and apply it on rebind; a newer terminal client safely supersedes it.
                pendingTerminalRelease = java.lang.ref.WeakReference(client)
            }
        }
    }

    private suspend fun <T> call(block: suspend (VmServiceEndpoint) -> T): T {
        bind()
        return state.command(block)
    }

    private fun endpointNow(): VmServiceEndpoint {
        bind()
        return state.connectedEndpointOrNull()
            ?: throw IOException("VM service binding unavailable")
    }

    companion object {
        private const val TAG = "VmServiceClient"
        private const val REBIND_DELAY_MS = 250L
    }
}
