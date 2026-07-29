/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Foreground service that hosts the QEMU process for Podroid.
 * Holds a WakeLock to prevent the device from sleeping while the VM runs.
 */
package com.excp.podroid.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.excp.podroid.MainActivity
import com.excp.podroid.R
import com.excp.podroid.data.repository.PortForwardRepository
import com.excp.podroid.data.repository.SettingsRepository
import com.excp.podroid.engine.VmEngine
import com.excp.podroid.engine.usb.UsbPassthroughManager
import com.excp.podroid.vm.VmId
import com.excp.podroid.vm.VmLifecycleState
import com.excp.podroid.vm.VmManager
import com.excp.podroid.vm.VmPaths
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

@AndroidEntryPoint
class PodroidService : Service() {

    @Inject lateinit var engine: VmEngine
    @Inject lateinit var vmManager: VmManager
    @Inject lateinit var vmPaths: VmPaths
    @Inject lateinit var portForwardRepository: PortForwardRepository
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var usbPassthroughManager: UsbPassthroughManager
    @Inject lateinit var notificationPoster: com.excp.podroid.engine.hostbridge.AndroidNotificationPoster
    @Inject lateinit var headlessModeManager: com.excp.podroid.engine.hostbridge.HeadlessModeManager
    private var hostRequestServer: com.excp.podroid.engine.hostbridge.HostRequestServer? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var notificationJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    // Main-thread commands synchronously claim the exact lazy launch Job. Stop
    // invalidates its generation before cancellation and retains ownership until
    // both launch joining and manager.stop have completed.
    private data class ServiceLaunchOwner(val job: Job, var generation: Long = 0L)

    private val launchCoordinator = ServiceLaunchCoordinator<ServiceLaunchOwner>()

    private var notificationBuilder: NotificationCompat.Builder? = null
    private var stopPendingIntent: PendingIntent? = null
    private var openPendingIntent: PendingIntent? = null
    private lateinit var localBinder: LocalBinder

    internal class LocalBinder internal constructor(
        val endpoint: VmServiceEndpoint,
    ) : Binder()

    override fun onBind(intent: Intent?): IBinder = localBinder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val endpoint = LocalVmServiceEndpoint(
            manager = vmManager,
            lifecycleCommands = object : VmServiceLifecycleCommands {
                override fun startForeground() {
                    ContextCompat.startForegroundService(
                        this@PodroidService,
                        Intent(this@PodroidService, PodroidService::class.java).apply { action = ACTION_START },
                    )
                }

                override fun stop(force: Boolean) {
                    requestServiceStop(
                        failureLog = if (force) "VM force stop failed" else "VM graceful stop failed",
                        force = force,
                    )
                }

                override fun restart() {
                    ContextCompat.startForegroundService(
                        this@PodroidService,
                        Intent(this@PodroidService, PodroidService::class.java).apply { action = ACTION_RESTART },
                    )
                }
            },
            auxiliary = object : VmServiceAuxiliaryCapabilities {
                override val headlessMode: kotlinx.coroutines.flow.StateFlow<Boolean>
                    get() = headlessModeManager.active

                override fun backendProbe(): VmBackendProbe {
                    val report = com.excp.podroid.engine.avf.AvfDiagnostics.probe(this@PodroidService)
                    return report.toServiceDto(engine.backendId)
                }

                override suspend fun runBackendSmokeTest(): String {
                    val report = com.excp.podroid.engine.avf.AvfDiagnostics.runSmokeTest(
                        this@PodroidService,
                        vmPaths,
                    )
                    return report
                        .replace(vmPaths.instanceDirectory.absolutePath, "[default VM]")
                        .replace(filesDir.absolutePath, "[app files]")
                }

                override fun setHeadlessMode(active: Boolean) = headlessModeManager.setActive(active)

                override fun createTerminalSession(
                    client: com.termux.terminal.TerminalSessionClient,
                ): com.termux.terminal.TerminalSession {
                    engine.sessionClientDelegate = client
                    return engine.createTerminalSession(client)
                }

                override fun releaseTerminalClient(client: com.termux.terminal.TerminalSessionClient) {
                    if (engine.sessionClientDelegate === client) engine.sessionClientDelegate = null
                }
            },
            caller = CallerUidVerifier.sameUid(Process.myUid()) { Binder.getCallingUid() },
        )
        localBinder = LocalBinder(endpoint)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                enterForegroundStartWindow()
                // STOPPING owns the current generation, but an ACTION_START in
                // that window is durable and idempotent. completeStop performs
                // the atomic handoff to exactly one fresh lazy launch.
                val queuedDuringStop = launchCoordinator.queueStartDuringStop()
                val startDecision = VmServiceStartPolicy.decide(
                    managerBusy = vmManager.busy(VmId.DEFAULT).value,
                    pendingStartOwned = launchCoordinator.ownershipActive.value,
                )
                val launch = if (!queuedDuringStop && startDecision.launchNewGeneration) {
                    prepareLaunch()
                } else {
                    null
                }
                if (startDecision.acquireWakeLock) acquireWakeLock()
                if (startDecision.armSupervision) startSupervision()
                launch?.owner?.job?.start()
            }
            ACTION_RESTART -> {
                enterForegroundStartWindow()
                acquireWakeLock()
                startSupervision()
                requestServiceRestart("VM restart stop failed")
            }
            ACTION_STOP -> requestServiceStop("VM graceful stop failed", force = false)
            else -> {
                // Null/unrecognized action (e.g. a system redelivery): we never
                // called startForeground for this start, so just stop to avoid a
                // started-but-not-foregrounded service.
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun enterForegroundStartWindow() {
        val fgType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted; foreground notification " +
                "and its Stop action will be invisible while the VM holds the WakeLock")
        }
        // Required for every startForegroundService command, including a
        // redundant start and restart while backend cleanup is still active.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification("Starting VM..."),
            fgType,
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        notificationJob?.cancel()
        hostRequestServer?.stop()
        usbPassthroughManager.stop()
        releaseWakeLock()
        serviceScope.cancel()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // App swiped from recents uses the same generation invalidation, launch
        // cancellation/join, and bounded manager stop as the notification action.
        requestServiceStop("Task-removal graceful stop failed", force = false)
    }

    private fun requestServiceStop(failureLog: String, force: Boolean) {
        val wasBusy = vmManager.busy(VmId.DEFAULT).value || launchCoordinator.ownershipActive.value
        val stop = launchCoordinator.beginStop()
        if (force && !stop.shouldExecute) {
            // A graceful service stop already owns launch joining/teardown. Route
            // the stronger duplicate to the manager so its force signal can
            // escalate that exact in-flight stop without starting a second
            // service teardown sequence.
            serviceScope.launch(Dispatchers.IO) {
                runCatching { vmManager.forceStop(VmId.DEFAULT) }
                    .onFailure { Log.e(TAG, failureLog, it) }
            }
            return
        }
        dispatchServiceStop(stop, failureLog, wasBusy, force)
    }

    private fun requestServiceRestart(failureLog: String) {
        val wasBusy = vmManager.busy(VmId.DEFAULT).value || launchCoordinator.ownershipActive.value
        dispatchServiceStop(launchCoordinator.beginRestart(), failureLog, wasBusy, force = false)
    }

    private fun dispatchServiceStop(
        stop: ServiceLaunchCoordinator.Stop<ServiceLaunchOwner>,
        failureLog: String,
        wasBusy: Boolean,
        force: Boolean,
    ) {
        if (!stop.shouldExecute) return

        stop.launchOwner?.job?.cancel()
        val stopScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        stopScope.launch {
            try {
                stopAndApplyPolicy(stop, failureLog, force)
            } finally {
                stopScope.cancel()
            }
        }
        if (wasBusy) {
            Log.d(TAG, "Stop requested: deferring teardown until launch/backend cleanup")
        }
    }

    private suspend fun stopAndApplyPolicy(
        stop: ServiceLaunchCoordinator.Stop<ServiceLaunchOwner>,
        failureLog: String,
        force: Boolean,
    ) {
        val stopResult = coroutineScope {
            // Start manager.stop together with the bounded join. If cancellation
            // landed during manager acceptance, its manager-owned cleanup and
            // this stop operation serialize at the manager lifecycle boundary.
            val managerStop = async {
                runCatching {
                    if (force) vmManager.forceStop(VmId.DEFAULT) else vmManager.stop(VmId.DEFAULT)
                }
            }
            val joined = withTimeoutOrNull(SERVICE_LAUNCH_JOIN_TIMEOUT_MS) {
                stop.launchOwner?.job?.join()
                true
            } == true
            if (!joined) {
                Log.e(TAG, "Service launch Job did not join within ${SERVICE_LAUNCH_JOIN_TIMEOUT_MS}ms")
            }
            managerStop.await()
        }
        stopResult.onFailure { Log.e(TAG, failureLog, it) }
        withContext(NonCancellable + Dispatchers.Main.immediate) {
            val restartOwner = createLaunchOwner()
            val completion = launchCoordinator.completeStop(stop.generation, restartOwner)
            val restart = completion?.launch
            if (restart != null) {
                restartOwner.generation = restart.generation
                acquireWakeLock()
                startSupervision()
                restart.owner.job.start()
            } else {
                restartOwner.job.cancel()
                val decision = currentLifecycleDecision()
                if (decision.teardown) teardown()
                else if (decision.notification == VmServiceNotification.CLEANUP_INCOMPLETE) {
                    updateNotification("VM error — cleanup incomplete; Stop retries cleanup")
                }
            }
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Podroid::VmWakeLock"
            ).apply {
                @SuppressLint("WakelockTimeout")
                acquire() // VM must run indefinitely — timeout would kill it
            }
            Log.d(TAG, "WakeLock acquired")
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "WakeLock released")
            }
        }
        wakeLock = null
    }

    private fun startSupervision() {
        if (notificationJob?.isActive == true) return
        notificationJob = serviceScope.launch {
            launch { observeStateForNotification() }
            launch { observeStateForShutdown() }
            launch { observeStateForHostBridge() }
            launch {
                if (withContext(Dispatchers.IO) {
                        settingsRepository.getUsbPassthroughEnabledSnapshot()
                    }) {
                    observeStateForUsb()
                }
            }
        }
    }

    /** Updates notification text from lifecycle, cleanup, busy, and boot stage. */
    private suspend fun observeStateForNotification() {
        combine(
            vmManager.lifecycle(VmId.DEFAULT),
            vmManager.quiescent(VmId.DEFAULT),
            vmManager.busy(VmId.DEFAULT),
            engine.bootStage,
        ) { state, quiescent, busy, stage ->
            VmServiceLifecyclePolicy.decide(state, quiescent, busy) to stage
        }.collect { (decision, stage) ->
            when (decision.notification) {
                VmServiceNotification.RUNNING -> updateNotification("VM is running")
                VmServiceNotification.STARTING ->
                    updateNotification(stage.ifEmpty { "Starting VM..." })
                VmServiceNotification.CLEANUP_INCOMPLETE ->
                    updateNotification("VM error — cleanup incomplete; Stop retries cleanup")
                VmServiceNotification.NONE -> Unit
            }
        }
    }

    /**
     * Tears down only when a terminal state and authoritative quiescence agree.
     * Error while non-quiescent remains foreground-supervised until a later
     * cleanup signal (including one produced by a Stop retry).
     */
    private suspend fun observeStateForShutdown() {
        combine(
            vmManager.lifecycle(VmId.DEFAULT),
            vmManager.quiescent(VmId.DEFAULT),
            vmManager.busy(VmId.DEFAULT),
            launchCoordinator.ownershipActive,
        ) { state, quiescent, busy, pendingStart ->
            VmServiceLifecyclePolicy.decide(state, quiescent, busy, pendingStart)
        }.collect { decision ->
            // No unconditional baseline suppression: if cleanup completed before
            // this collector's first emission, that first terminal snapshot must
            // tear down the service. Only an actually-owned pending start defers it.
            if (decision.teardown) teardown()
        }
    }

    private fun currentLifecycleDecision(): VmServiceLifecycleDecision =
        VmServiceLifecyclePolicy.decide(
            vmManager.lifecycle(VmId.DEFAULT).value,
            vmManager.quiescent(VmId.DEFAULT).value,
            vmManager.busy(VmId.DEFAULT).value,
            launchCoordinator.ownershipActive.value,
        )

    /** Release the WakeLock, drop the foreground notification, and stop. */
    private fun teardown() {
        releaseWakeLock()
        stopForegroundCompat()
        stopSelf()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 33) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
    }

    /**
     * Arms USB passthrough once the VM is Running and tears it down when the VM
     * reaches a terminal state. Only launched when the feature is enabled, so
     * the BroadcastReceiver is live strictly while a VM session is up.
     */
    private suspend fun observeStateForUsb() {
        combine(
            vmManager.lifecycle(VmId.DEFAULT),
            vmManager.quiescent(VmId.DEFAULT),
            vmManager.busy(VmId.DEFAULT),
        ) { state, quiescent, busy ->
            VmServiceLifecyclePolicy.decide(state, quiescent, busy).runtimeChannels
        }.collect { directive ->
            when (directive) {
                RuntimeChannelDirective.START -> usbPassthroughManager.start()
                RuntimeChannelDirective.STOP -> usbPassthroughManager.stop()
                RuntimeChannelDirective.KEEP -> Unit
            }
        }
    }

    private fun ensureHostBridge(): com.excp.podroid.engine.hostbridge.HostRequestServer {
        hostRequestServer?.let { return it }
        val dispatcher = com.excp.podroid.engine.hostbridge.HostRequestDispatcher(
            notifications = notificationPoster,
            addForward = { portForwardRepository.addRule(it) },
            removeForward = { portForwardRepository.removeRule(it) },
            listForwards = { portForwardRepository.getRulesSnapshot() },
            openUrl = { handleOpenUrl(it) },
            power = { handlePowerRequest(it) },
            setHeadless = { handleHeadlessRequest(it) },
        )
        return com.excp.podroid.engine.hostbridge.HostRequestServer(
            openTransport = { engine.openHostTransport() },
            dispatcher = dispatcher,
            scope = serviceScope,
        ).also { hostRequestServer = it }
    }

    /** Always-on: starts the guest host bridge on Running, stops it on terminal. */
    private suspend fun observeStateForHostBridge() {
        combine(
            vmManager.lifecycle(VmId.DEFAULT),
            vmManager.quiescent(VmId.DEFAULT),
            vmManager.busy(VmId.DEFAULT),
        ) { state, quiescent, busy ->
            VmServiceLifecyclePolicy.decide(state, quiescent, busy).runtimeChannels
        }.collect { directive ->
            when (directive) {
                RuntimeChannelDirective.START -> ensureHostBridge().start()
                RuntimeChannelDirective.STOP -> {
                    hostRequestServer?.stop()
                    // Drop server mode only after backend cleanup is authoritative.
                    headlessModeManager.setActive(false)
                }
                RuntimeChannelDirective.KEEP -> Unit
            }
        }
    }

    private fun prepareLaunch(): ServiceLaunchCoordinator.Launch<ServiceLaunchOwner>? {
        val owner = createLaunchOwner()
        val launch = launchCoordinator.beginLaunch(owner)
        if (launch == null) {
            owner.job.cancel()
            return null
        }
        owner.generation = launch.generation
        return launch
    }

    private fun createLaunchOwner(): ServiceLaunchOwner {
        lateinit var owner: ServiceLaunchOwner
        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
            var failure: Throwable? = null
            try {
                withContext(Dispatchers.IO) {
                    vmManager.start(VmId.DEFAULT)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (caught: Throwable) {
                failure = caught
                Log.e(TAG, "VM failed to start", caught)
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    if (launchCoordinator.completeLaunch(owner.generation)) {
                        val decision = currentLifecycleDecision()
                        if (decision.teardown) teardown()
                        else if (failure != null &&
                            decision.notification == VmServiceNotification.CLEANUP_INCOMPLETE) {
                            updateNotification("VM error — cleanup incomplete; Stop retries cleanup")
                        }
                    }
                }
            }
        }
        owner = ServiceLaunchOwner(job)
        return owner
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Podroid Service",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows the status of the Podman VM"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    /**
     * Lazily build (and cache) the NotificationCompat.Builder + its PendingIntents
     * once per service lifetime. Boot streams ~5 state-change emits per second, so
     * recreating the Builder + two PendingIntents on every emit is pure churn.
     * After the first call, updateNotification() just mutates contentText on the
     * cached builder.
     */
    private fun getOrCreateNotificationBuilder(): NotificationCompat.Builder {
        notificationBuilder?.let { return it }

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, PodroidService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        openPendingIntent = openIntent
        stopPendingIntent = stopIntent

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Podroid")
            .setSmallIcon(R.drawable.ic_vm_notification)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_stop, "Stop", stopIntent)
        notificationBuilder = builder
        return builder
    }

    private fun buildNotification(status: String): Notification {
        return getOrCreateNotificationBuilder()
            .setContentText(status)
            .build()
    }

    private fun updateNotification(status: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun handleOpenUrl(url: String): String {
        val uri = runCatching { android.net.Uri.parse(url) }.getOrNull()
        if (uri == null || uri.scheme?.lowercase() !in setOf("http", "https")) {
            return com.excp.podroid.engine.hostbridge.HostProtocol.err("only http/https URLs are allowed")
        }
        return try {
            val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            applicationContext.startActivity(intent)
            com.excp.podroid.engine.hostbridge.HostProtocol.ok()
        } catch (e: Throwable) {
            com.excp.podroid.engine.hostbridge.HostProtocol.err("no app available to open this URL")
        }
    }

    private fun handleHeadlessRequest(action: String): String = when (action) {
        "on" -> { headlessModeManager.setActive(true); com.excp.podroid.engine.hostbridge.HostProtocol.ok() }
        "off" -> { headlessModeManager.setActive(false); com.excp.podroid.engine.hostbridge.HostProtocol.ok() }
        "status" -> com.excp.podroid.engine.hostbridge.HostProtocol.ok(if (headlessModeManager.active.value) "on" else "off")
        else -> com.excp.podroid.engine.hostbridge.HostProtocol.err("usage: on|off|status")
    }

    // Reply returned now; the stop/restart is posted to the main looper so the
    // bridge flushes the response before the VM (and this service) tear down. The
    // Handler callbacks capture the app-scoped engine + applicationContext (NOT
    // `this`), so they survive this service's death.
    private fun handlePowerRequest(action: String): String {
        val proto = com.excp.podroid.engine.hostbridge.HostProtocol
        return when (action) {
            // Map explicitly, not via javaClass.simpleName: R8 obfuscates class
            // names in release builds, so simpleName returns garbage like "wc2".
            "status" -> proto.ok(when (vmManager.lifecycle(VmId.DEFAULT).value) {
                VmLifecycleState.IDLE -> "idle"
                VmLifecycleState.STARTING -> "starting"
                VmLifecycleState.RUNNING -> "running"
                VmLifecycleState.STOPPED -> "stopped"
                VmLifecycleState.ERROR -> "error"
            })
            "stop" -> {
                val ctx = applicationContext
                android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed({ PodroidService.stop(ctx) }, 300)
                proto.ok()
            }
            "restart" -> { scheduleRestart(); proto.ok() }
            else -> proto.err("usage: stop|restart|status")
        }
    }

    private fun scheduleRestart() {
        val ctx = applicationContext
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            PodroidService.restart(ctx)
        }, 300)
    }

    companion object {
        private const val TAG = "PodroidService"
        private const val CHANNEL_ID = "podroid_service"
        private const val NOTIFICATION_ID = 1001
        private const val SERVICE_LAUNCH_JOIN_TIMEOUT_MS = 8_000L

        const val ACTION_START   = "com.excp.podroid.action.START"
        const val ACTION_STOP    = "com.excp.podroid.action.STOP"
        const val ACTION_RESTART = "com.excp.podroid.action.RESTART"

        fun start(context: Context) {
            val intent = Intent(context, PodroidService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, PodroidService::class.java).apply {
                action = ACTION_STOP
            })
        }

        fun restart(context: Context) {
            context.startForegroundService(Intent(context, PodroidService::class.java).apply {
                action = ACTION_RESTART
            })
        }
    }
}

private fun com.excp.podroid.engine.avf.AvfReport.toServiceDto(activeBackendId: String) =
    VmBackendProbe(
        featureSupported = featureSupported,
        managePermissionGranted = managePermissionGranted,
        customPermissionGranted = customPermissionGranted,
        virtApexPresent = virtApexPresent,
        managerClassPresent = managerClassPresent,
        serviceReachable = serviceReachable,
        customVmConfigSupported = customVmConfigSupported,
        capabilitiesRaw = capabilitiesRaw,
        capabilitiesDecoded = capabilitiesDecoded,
        activeBackend = activeBackendId,
        smokeTestResult = smokeTestResult,
    )
