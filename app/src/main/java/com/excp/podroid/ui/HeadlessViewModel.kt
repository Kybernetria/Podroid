/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Exposes the shared server-mode state to MainActivity so it can draw the black
 * overlay and drop window brightness, and lets the overlay turn it off.
 */
package com.excp.podroid.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.excp.podroid.service.VmServiceClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HeadlessViewModel @Inject constructor(
    private val vmServiceClient: VmServiceClient,
) : ViewModel() {
    val active: StateFlow<Boolean> = vmServiceClient.headlessMode
    fun disable() {
        viewModelScope.launch {
            runCatching { vmServiceClient.setHeadlessMode(false) }
                .onFailure { android.util.Log.e("HeadlessViewModel", "Disable failed", it) }
        }
    }
}
