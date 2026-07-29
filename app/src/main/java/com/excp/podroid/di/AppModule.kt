/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Hilt module for dependency injection.
 */
package com.excp.podroid.di

import android.content.Context
import com.excp.podroid.vm.HostTransportReconciler
import com.excp.podroid.vm.NoConfiguredHostTransportReconciler
import com.excp.podroid.vm.VmPaths
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Qualifies the application-lifetime [CoroutineScope] — created once and never
 * cancelled while the process lives. Use it for fire-and-forget persistence that
 * must finish even when the caller's own scope is torn down mid-write (e.g. a
 * ViewModel cleared on back-navigation cancelling its viewModelScope before a
 * DataStore write commits — see issue #46).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    /** The MVP has exactly one named VM and one authoritative path model. */
    @Provides
    @Singleton
    fun provideDefaultVmPaths(@ApplicationContext context: Context): VmPaths =
        VmPaths.default(context.filesDir)

    /**
     * SupervisorJob so one failed write doesn't cancel the scope for every other
     * consumer; Dispatchers.IO because every consumer here persists to DataStore
     * or disk.
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Explicit successful no-op until ticket #15 configures host transport. */
    @Provides
    @Singleton
    internal fun provideHostTransportReconciler(): HostTransportReconciler =
        NoConfiguredHostTransportReconciler
}
