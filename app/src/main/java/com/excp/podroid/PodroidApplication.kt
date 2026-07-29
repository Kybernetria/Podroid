/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Application class — extracts QEMU, kernel, and initrd assets on first run
 * (and on app upgrade when an asset's size changes).
 */
package com.excp.podroid

import android.app.Application
import android.os.Build
import android.util.Log
import com.excp.podroid.vm.LegacyVmFilesMigration
import com.excp.podroid.vm.StaleTmpFileCleaner
import com.excp.podroid.vm.VmAtomicFile
import com.excp.podroid.vm.VmPathSecurity
import com.excp.podroid.vm.VmPaths
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class PodroidApplication : Application() {

    @Inject lateinit var vmPaths: VmPaths

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val assetInstallMutex = Mutex()

    // Completion signal for asset extraction. The VM launch path
    // (PodroidService.launchPodroid) reads the extracted files synchronously,
    // so it MUST await this before starting the engine — see awaitAssetsReady.
    // Failed migration/extraction completes this exceptionally. The service
    // then aborts launch instead of booting from a partial or unsafe layout.
    private val assetsReady = CompletableDeferred<Unit>()

    override fun onCreate() {
        super.onCreate()
        exemptHiddenApi()
        // Extract off the main thread: the squashfs alone is ~225 MB and
        // blocking onCreate on first install/upgrade would ANR the cold start.
        appScope.launch {
            try {
                installVmAssets()
                assetsReady.complete(Unit)
            } catch (failure: Throwable) {
                Log.e(TAG, "VM path migration or asset extraction failed", failure)
                // Catch Throwable, not only Exception: an Error must release every
                // readiness waiter exceptionally rather than strand launch forever.
                assetsReady.completeExceptionally(failure)
            }
        }
    }

    /**
     * Suspends until the bundled assets (qemu/, kernel, initrd, squashfs) have
     * finished extracting to `filesDir/instances/default`. The foreground service awaits this
     * before launching the VM so QEMU/AVF never read a partial or missing file.
     */
    suspend fun awaitAssetsReady() = assetsReady.await()

    /**
     * Safe installer seam used by VmManager. It is serialized with initial app
     * extraction and may be called again after an explicit installation remove;
     * missing assets are then restored through the same atomic NOFOLLOW path.
     */
    private suspend fun installVmAssets() = withContext(Dispatchers.IO) {
        assetInstallMutex.withLock { extractAssets() }
    }

    /**
     * Application-wide asset-tree lease used by VmManager. Initial extraction,
     * retries, launch-time file reads, console reads, and removal all use this
     * same mutex, so a queued extraction cannot overtake removal or launch.
     */
    internal suspend fun <T> withVmAssetTreeLease(
        action: suspend (installAssets: suspend () -> Unit) -> T,
    ): T = withContext(Dispatchers.IO) {
        assetInstallMutex.withLock { action { extractAssets() } }
    }

    // Android 14+ hides @SystemApi reflection lookups (returning NoSuchMethod
    // even via getDeclared*). Prefixes needing exemption:
    //   - Landroid/system/virtualmachine/ — AVF framework (AvfDiagnostics + AvfEngine)
    //   - Landroid/system/virtualizationservice/ — AVF AIDL parcelables
    //     (CpuOptions, VirtualMachineRawConfig, IVirtualizationService) used by
    //     AvfReflect's explicit-vCPU-count hook (issue #29).
    //   - Ljava/net/UnixDomainSocketAddress — ConsoleFanout needs UDS.of(String)
    //     which Android marks BLOCKED for untrusted_app even though the class
    //     itself is on the bootclasspath.
    // No-op on sub-P; the exemption itself never throws.
    private fun exemptHiddenApi() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        runCatching {
            HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/system/virtualmachine/",
                "Landroid/system/virtualizationservice/",
                "Landroid/system/UnixSocketAddress",
                "Ljava/net/UnixDomainSocketAddress",
            )
        }.onFailure { Log.w(TAG, "HiddenApiBypass exemption failed", it) }
    }

    private fun extractAssets() {
        // Migration is deliberately before every extraction and launch. It is
        // idempotent, so an interrupted prior run continues from whole-file
        // renames; a collision or symlink fails the readiness gate closed.
        LegacyVmFilesMigration(filesDir, vmPaths).migrate()
        val instanceDir = vmPaths.instanceDirectory
        val pathSecurity = VmPathSecurity(vmPaths)
        pathSecurity.prepareExtractionLayout()
        // Process recreation can leave a live QEMU/AVF runtime. Asset refresh
        // must never infer that its sockets are stale or delete them. The typed
        // runtime preflight owns that decision before any later launch.
        StaleTmpFileCleaner(allowedSpecialFiles = runtimeEndpoints()).clean(instanceDir)
        pathSecurity.validateForAssetRefresh()

        // Asset extraction has a self-healing version stamp: on every install
        // or upgrade `packageInfo.lastUpdateTime` changes, so we record it in
        // `.assets_stamp` and force a re-copy on mismatch. Pure size checks
        // are deceiving because `mksquashfs -all-root -noappend` is
        // deterministic — changing service scripts inside the rootfs can
        // produce a byte-identical-size file with different content, which
        // older extraction logic silently kept stale.
        val stampFile = vmPaths.assetStamp
        val currentStamp = runCatching {
            packageManager.getPackageInfo(packageName, 0).lastUpdateTime
        }.getOrDefault(0L).toString()
        val previousStamp = runCatching { stampFile.readText() }.getOrDefault("")
        val forceCopy = previousStamp != currentStamp
        if (forceCopy) {
            Log.i(TAG, "asset stamp drift ($previousStamp → $currentStamp) — forcing re-extract")
        }

        // Stale .tmp files were removed by the bounded NOFOLLOW traversal
        // above. Every new temporary is then created exclusively.

        // Fan out the four top-level extractions across a small thread pool.
        // Disk-write throughput is the bottleneck for the squashfs (~225 MB),
        // but decompression, asset-FD lookup, and skip-when-size-matches all
        // overlap usefully across threads. Runs on a background coroutine
        // (not the main thread); the VM launch path awaits awaitAssetsReady.
        val tasks: List<() -> Unit> = listOf(
            { copyAssetDir("qemu", instanceDir, forceCopy, pathSecurity) },
            { copyAssetIfNeeded("vmlinuz-virt", instanceDir, forceCopy, pathSecurity) },
            { copyAssetIfNeeded("initrd.img", instanceDir, forceCopy, pathSecurity) },
            { copyAssetIfNeeded("alpine-rootfs.squashfs", instanceDir, forceCopy, pathSecurity) },
        )
        val pool = Executors.newFixedThreadPool(tasks.size.coerceAtMost(4))
        var allSucceeded = true
        try {
            // invokeAll blocks until every Callable finishes (or times out).
            // Each Callable wraps the task so a thrown exception is captured
            // in the returned Future rather than killing the worker silently.
            val futures = pool.invokeAll(tasks.map { task ->
                java.util.concurrent.Callable<Unit> { task() }
            })
            for (f in futures) {
                try { f.get() } catch (e: Exception) {
                    // copyAssetIfNeeded / copyAssetFileIfNeeded already log
                    // their own failures; this catches anything that escaped.
                    Log.w(TAG, "Asset extraction task failed", e)
                    allSucceeded = false
                }
            }
        } finally {
            pool.shutdown()
            if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
                pool.shutdownNow()
                allSucceeded = false
            }
        }

        // Commit the new stamp ONLY if every extraction task succeeded.
        // Writing it after a failed copy (e.g. squashfs copy failed on an
        // upgrade: disk full, killed mid-copy) would mark the OLD file as
        // current — and because mksquashfs is deterministic the size check
        // can't catch it either, so a stale rootfs would boot forever. On
        // failure we leave the stamp stale so the next launch re-extracts.
        if (!allSucceeded) {
            Log.w(TAG, "asset extraction incomplete — leaving stamp stale to force re-extract next launch")
            throw java.io.IOException("One or more VM asset extraction tasks failed")
        }
        VmAtomicFile.write(stampFile, pathSecurity) { output ->
            output.write(currentStamp.toByteArray(Charsets.UTF_8))
        }
        pathSecurity.validateForAssetRefresh()
    }

    private fun runtimeEndpoints(): Set<File> = setOf(
        vmPaths.serialSocket,
        vmPaths.terminalSocket,
        vmPaths.controlSocket,
        vmPaths.hostSocket,
        vmPaths.qmpSocket,
        vmPaths.avfTerminalSocket,
        vmPaths.avfControlSocket,
    )

    /**
     * Copies an asset to destDir if missing OR if the size differs OR if the
     * install-time stamp drifted. The stamp is the key bit: `mksquashfs` is
     * deterministic, so an upgrade can ship a same-size squashfs with
     * different content (e.g. an init.d script edited) — size-only checks
     * would silently keep the stale copy and the VM boots the old rootfs.
     */
    private fun copyAssetIfNeeded(
        assetName: String,
        destDir: File,
        forceCopy: Boolean,
        pathSecurity: VmPathSecurity,
    ) {
        val destFile = File(destDir, assetName)
        try {
            pathSecurity.validateRegularFileDestination(destFile)
            val assetSize = try { assets.openFd(assetName).use { it.length } } catch (_: Exception) { -1L }
            if (!forceCopy && assetSize >= 0 && destFile.exists() && destFile.length() == assetSize) return

            destFile.parentFile?.let { pathSecurity.createExtractionDirectory(it) }
            copyAssetAtomically(assetName, destFile, pathSecurity)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract $assetName", e)
            throw e
        }
    }

    /**
     * Walks an asset directory tree and mirrors it under destDir.
     * Each file is copied if missing OR if its size differs OR if forceCopy
     * is true (install-stamp drift).
     */
    private fun copyAssetDir(
        assetPath: String,
        destDir: File,
        forceCopy: Boolean,
        pathSecurity: VmPathSecurity,
    ) {
        pathSecurity.createExtractionDirectory(destDir)
        val entries = assets.list(assetPath) ?: return
        for (entry in entries) {
            val src = "$assetPath/$entry"
            val dest = File(destDir, entry)
            val subEntries = assets.list(src)
            if (subEntries != null && subEntries.isNotEmpty()) {
                pathSecurity.createExtractionDirectory(dest)
                copyAssetDir(src, dest, forceCopy, pathSecurity)
            } else {
                copyAssetFileIfNeeded(src, dest, forceCopy, pathSecurity)
            }
        }
    }

    private fun copyAssetFileIfNeeded(
        assetPath: String,
        destFile: File,
        forceCopy: Boolean,
        pathSecurity: VmPathSecurity,
    ) {
        try {
            pathSecurity.validateRegularFileDestination(destFile)
            val assetSize = try { assets.openFd(assetPath).use { it.length } } catch (_: Exception) { -1L }
            if (!forceCopy && assetSize >= 0 && destFile.exists() && destFile.length() == assetSize) return

            copyAssetAtomically(assetPath, destFile, pathSecurity)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract $assetPath", e)
            throw e
        }
    }

    /**
     * Streams [assetPath] to `<destFile>.tmp`, fsyncs the data to disk, then
     * atomically renames it onto [destFile]. The final canonical path therefore
     * only ever holds a fully-written file — an async reader (the VM launch)
     * never sees a half-written squashfs/kernel. Throws on any failure so the
     * caller logs it and the stale/missing file is caught by the next size-check.
     */
    private fun copyAssetAtomically(
        assetPath: String,
        destFile: File,
        pathSecurity: VmPathSecurity,
    ) {
        VmAtomicFile.write(destFile, pathSecurity) { output ->
            assets.open(assetPath).use { input -> input.copyTo(output) }
        }
    }

    companion object {
        private const val TAG = "PodroidApp"
    }
}
