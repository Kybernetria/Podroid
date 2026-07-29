/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.engine

import kotlinx.coroutines.flow.StateFlow

/**
 * Keeps normal [StateFlow] collection while sourcing imperative [value] reads
 * directly from the authoritative owner rather than an asynchronous flow cache.
 */
internal class ExactValueStateFlow<T>(
    updates: StateFlow<T>,
    private val exactValue: () -> T,
) : StateFlow<T> by updates {
    override val value: T get() = exactValue()
}
