/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.excp.podroid.data.repository.HostSupervisorRepository
import com.excp.podroid.vm.HostReconciliationPolicy
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Credential-unlock-safe boot trigger; LOCKED_BOOT_COMPLETED is not registered. */
@AndroidEntryPoint
class BootCompletedReceiver : BroadcastReceiver() {
    @Inject lateinit var supervisor: HostSupervisorRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val state = supervisor.snapshot()
                if (HostReconciliationPolicy.shouldStartServiceAtBoot(state)) {
                    ContextCompat.startForegroundService(
                        context,
                        PodroidService.reconciliationIntent(
                            context,
                            PodroidService.ACTION_RECONCILE_BOOT,
                        ),
                    )
                }
            } catch (failure: Throwable) {
                // Stable class only; DataStore/codec messages can contain record data.
                Log.e(TAG, "Boot reconciliation gate failed type=${failure.javaClass.simpleName}")
            } finally {
                pending.finish()
                scope.cancel()
            }
        }
    }

    private companion object { const val TAG = "PodroidBoot" }
}
