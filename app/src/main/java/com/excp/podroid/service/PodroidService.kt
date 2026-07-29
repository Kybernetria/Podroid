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
import android.os.IBinder
import android.os.PowerManager
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
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

@AndroidEntryPoint
class PodroidService : Service() {

    @Inject lateinit var engine: VmEngine
    @Inject lateinit var vmManager: VmManager
    @Inject lateinit var portForwardRepository: PortForwardRepository
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var usbPassthroughManager: UsbPassthroughManager
    @Inject lateinit var notificationPoster: com.excp.podroid.engine.hostbridge.AndroidNotificationPoster
    @Inject lateinit var headlessModeManager: com.excp.podroid.engine.hostbridge.HeadlessModeManager
    private var hostRequestServer: com.excp.podroid.engine.hostbridge.HostRequestServer? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var notificationJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    // True only while this service owns a manager.start call that has not yet
    // been accepted or failed. A retained terminal replay may not tear down that
    // pending start, but every other terminal+quiescent snapshot is actionable.
    private val pendingStartOwned = MutableStateFlow(false)

    private var notificationBuilder: NotificationCompat.Builder? = null
    private var stopPendingIntent: PendingIntent? = null
    private var openPendingIntent: PendingIntent? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // Android 14+ (API 34) requires the foregroundServiceType argument
                // when the manifest declares foregroundServiceType="specialUse";
                // otherwise Android throws MissingForegroundServiceTypeException.
                val fgType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                }
                // Non-fatal diagnostic: on API 33+ a missing POST_NOTIFICATIONS
                // grant makes the persistent notification (and its Stop action)
                // invisible while the WakeLock is held. We do NOT gate VM start on
                // this, just log so the invisible-notification state is
                // diagnosable. (The setup screen requests the permission.)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                    Log.w(TAG, "POST_NOTIFICATIONS not granted; foreground notification " +
                        "and its Stop action will be invisible while the VM holds the WakeLock")
                }
                // Always (re-)assert foreground within the start window — required
                // even on a redundant ACTION_START so the system doesn't fault us
                // for not calling startForeground.
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildNotification("Starting VM..."),
                    fgType,
                )
                // Manager busy includes launch assembly and Error cleanup, not
                // merely UI-active states. ACTION_START always restores foreground
                // supervision and the WakeLock for such a generation, but never
                // launches a duplicate. Claim pending ownership before collectors
                // start so a retained terminal replay cannot cancel our new start.
                val startDecision = VmServiceStartPolicy.decide(
                    managerBusy = vmManager.busy(VmId.DEFAULT).value,
                    pendingStartOwned = pendingStartOwned.value,
                )
                if (startDecision.launchNewGeneration) pendingStartOwned.value = true
                if (startDecision.acquireWakeLock) acquireWakeLock()
                if (startDecision.armSupervision) startSupervision()
                if (startDecision.launchNewGeneration) launchPodroid()
            }
            ACTION_STOP -> {
                val wasBusy = vmManager.busy(VmId.DEFAULT).value || pendingStartOwned.value
                serviceScope.launch(Dispatchers.IO) {
                    stopAndApplyPolicy("VM graceful stop failed")
                }
                if (wasBusy) {
                    // VmManager's bounded stop preserves the backend's async
                    // best-effort guest flush (AVF syncAndWait). Releasing the
                    // partial WakeLock here
                    // would let the CPU suspend mid-flush with the screen off,
                    // defeating the deliberate ext4 sync. Keep it held; the
                    // shutdown observer runs teardown() (release + stopSelf) once
                    // the engine reaches Stopped/Error.
                    Log.d(TAG, "ACTION_STOP: VM active — deferring teardown to state observer")
                } else {
                    // Stop while idle (e.g. a fresh service spun up only to stop):
                    // no terminal transition will arrive, so release + stop now so
                    // this doesn't linger as a started-but-never-foregrounded orphan.
                    releaseWakeLock()
                    stopForegroundCompat()
                    stopSelf()
                }
            }
            else -> {
                // Null/unrecognized action (e.g. a system redelivery): we never
                // called startForeground for this start, so just stop to avoid a
                // started-but-not-foregrounded service.
                stopSelf()
            }
        }
        return START_NOT_STICKY
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
        // App swiped from recents — request the manager's bounded graceful stop.
        // Keep the WakeLock/foreground service until the state observer sees the
        // terminal transition; an idle service has no transition, so tear it down.
        val wasBusy = vmManager.busy(VmId.DEFAULT).value || pendingStartOwned.value
        serviceScope.launch(Dispatchers.IO) {
            stopAndApplyPolicy("Task-removal graceful stop failed")
        }
        if (!wasBusy) teardown()
    }

    private suspend fun stopAndApplyPolicy(failureLog: String) {
        runCatching { vmManager.stop(VmId.DEFAULT) }
            .onFailure { Log.e(TAG, failureLog, it) }
        withContext(Dispatchers.Main) {
            val decision = currentLifecycleDecision()
            if (decision.teardown) teardown()
            else if (decision.notification == VmServiceNotification.CLEANUP_INCOMPLETE) {
                updateNotification("VM error — cleanup incomplete; Stop retries cleanup")
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
            pendingStartOwned,
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
            pendingStartOwned.value,
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

    private fun launchPodroid() {
        serviceScope.launch {
            var failure: Throwable? = null
            try {
                withContext(Dispatchers.IO) {
                    // Installation, launch settings, implicit SSH forwarding, and
                    // backend engine invocation are all owned by VmManager.
                    vmManager.start(VmId.DEFAULT)
                }
            } catch (caught: Throwable) {
                failure = caught
                Log.e(TAG, "VM failed to start", caught)
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    // Acceptance and failure both end this service's ownership.
                    // Re-evaluate the exact current snapshot immediately because
                    // a terminal emission may already have been suppressed while
                    // pendingStartOwned was true.
                    pendingStartOwned.value = false
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
        val manager = vmManager
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        main.postDelayed({
            PodroidService.stop(ctx)
            var tries = 0
            val poll = object : Runnable {
                override fun run() {
                    val s = manager.lifecycle(VmId.DEFAULT).value
                    // Only Stopped/Error are genuine terminals after manager.stop().
                    // Idle is the pre-start normalized state, so treating it as
                    // terminal could fire start() during a teardown blip.
                    val terminal = (s == VmLifecycleState.STOPPED || s == VmLifecycleState.ERROR) &&
                        manager.quiescent(VmId.DEFAULT).value && !manager.busy(VmId.DEFAULT).value
                    when {
                        terminal -> PodroidService.start(ctx)
                        tries++ >= 40 -> {
                            Log.w(TAG, "restart: VM did not reach a stopped state in time (state=$s); starting anyway")
                            PodroidService.start(ctx)
                        }
                        else -> main.postDelayed(this, 250)
                    }
                }
            }
            main.postDelayed(poll, 500)
        }, 300)
    }

    companion object {
        private const val TAG = "PodroidService"
        private const val CHANNEL_ID = "podroid_service"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START   = "com.excp.podroid.action.START"
        const val ACTION_STOP    = "com.excp.podroid.action.STOP"

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
    }
}
