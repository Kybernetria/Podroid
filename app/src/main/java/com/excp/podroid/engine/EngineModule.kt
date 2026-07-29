/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * The single Hilt binding for VmEngine is EngineHolder. The holder picks the
 * concrete engine (QemuEngine vs AvfEngine) at construction, watches Settings
 * for backend changes, and routes Imperative calls + flow access to whichever
 * is current. Provider<QemuEngine>/Provider<AvfEngine> let the holder lazy-
 * instantiate either side so we don't pay AvfEngine's reflection cost on
 * QEMU-only devices.
 */
package com.excp.podroid.engine

import android.content.Context
import com.excp.podroid.data.repository.HostSupervisorRepository
import com.excp.podroid.data.repository.PortForwardRepository
import com.excp.podroid.data.repository.SettingsRepository
import com.excp.podroid.di.ApplicationScope
import com.excp.podroid.vm.ApplicationVmInstaller
import com.excp.podroid.vm.DefaultVmManager
import com.excp.podroid.vm.EngineManagedVmRuntime
import com.excp.podroid.vm.RepositoryVmConfigurationSource
import com.excp.podroid.vm.VmManager
import com.excp.podroid.vm.VmPathFiles
import com.excp.podroid.vm.VmPaths
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object EngineModule {

    @Provides
    @Singleton
    fun provideVmEngine(holder: EngineHolder): VmEngine = holder

    @Provides
    @Singleton
    fun provideVmManager(
        engine: VmEngine,
        @ApplicationContext context: Context,
        settings: SettingsRepository,
        portForwards: PortForwardRepository,
        hostSupervisor: HostSupervisorRepository,
        paths: VmPaths,
        @ApplicationScope scope: CoroutineScope,
    ): VmManager = DefaultVmManager(
        runtime = EngineManagedVmRuntime(engine),
        installer = ApplicationVmInstaller(context),
        configuration = RepositoryVmConfigurationSource(context, settings, portForwards),
        files = VmPathFiles(paths),
        supervisor = hostSupervisor,
        scope = scope,
    )
}
