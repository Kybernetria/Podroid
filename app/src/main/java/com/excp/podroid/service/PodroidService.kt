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
import com.excp.podroid.vm.LifecycleErrorCode
import com.excp.podroid.vm.LifecycleOperation
import com.excp.podroid.vm.LifecycleTransactionToken
import com.excp.podroid.vm.VmId
import com.excp.podroid.vm.VmManager
import com.excp.podroid.vm.VmPaths
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

@AndroidEntryPoint
class PodroidService : Service() {

    @Inject lateinit var engine: VmEngine
    @Inject lateinit var vmManager: VmManager
    @Inject lateinit var vmPaths: VmPaths
    @Inject lateinit var portForwardRepository: PortForwardRepository
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var usbPassthroughManager: UsbPassthroughManager
    @Inject lateinit var hostReconciler: HostReconciler
    @Inject lateinit var notificationPoster: com.excp.podroid.engine.hostbridge.AndroidNotificationPoster
    @Inject lateinit var headlessModeManager: com.excp.podroid.engine.hostbridge.HeadlessModeManager
    private var hostRequestServer: com.excp.podroid.engine.hostbridge.HostRequestServer? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var notificationJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    // Main-thread commands synchronously claim the exact lazy launch Job. Stop
    // invalidates its generation before cancellation and retains ownership until
    // both launch joining and manager.stop have completed.
    private data class ServiceLaunchOwner(
        val job: Job,
        val command: LifecycleTransactionToken,
        var generation: Long = 0L,
    )

    private val launchCoordinator = ServiceLaunchCoordinator<ServiceLaunchOwner>()
    private val reconciliationActive = kotlinx.coroutines.flow.MutableStateFlow(false)
    /** Main-thread count prevents a duplicate delivery from disarming an older run. */
    private var activeReconciliationDeliveries = 0
    private val serviceDispatchMutex = Mutex()
    @Volatile private var queuedLaunchCommand: LifecycleTransactionToken? = null

    private var notificationBuilder: NotificationCompat.Builder? = null
    private var stopPendingIntent: PendingIntent? = null
    private var openPendingIntent: PendingIntent? = null
    private lateinit var localBinder: LocalBinder
    private val guestPowerRequestHandler by lazy {
        GuestPowerRequestHandler(
            lifecycle = { vmManager.lifecycle(VmId.DEFAULT).value },
            admitAndSchedule = { operation, schedule ->
                admitAndDispatch(operation, dispatch = schedule)
            },
            schedule = ::scheduleGuestPowerCommand,
            admissionFailed = { Log.e(TAG, "Guest power admission failed", it) },
        )
    }

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
                override suspend fun startForeground() {
                    admitAndDispatch(
                        operation = LifecycleOperation.START,
                        enqueueAction = ACTION_START,
                    )
                }

                override suspend fun stop(force: Boolean) {
                    val operation = if (force) {
                        LifecycleOperation.FORCE_STOP
                    } else {
                        LifecycleOperation.STOP
                    }
                    admitAndDispatch(operation) { command ->
                        requestServiceStop(
                            command = command,
                            failureLog = if (force) "VM force stop failed" else "VM graceful stop failed",
                            force = force,
                        )
                    }
                }

                override suspend fun restart() {
                    admitAndDispatch(
                        operation = LifecycleOperation.RESTART,
                        enqueueAction = ACTION_RESTART,
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

                override suspend fun runBackendSmokeTest(deadlineNanos: Long): String {
                    val report = com.excp.podroid.engine.avf.AvfDiagnostics.runSmokeTest(
                        this@PodroidService,
                        vmPaths,
                        deadlineNanos,
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
        val action = intent?.action
        when (action) {
            ACTION_START, ACTION_RESTART -> {
                // Android requires this for each delivered foreground start,
                // including a token that became stale after enqueue.
                enterForegroundStartWindow()
                val command = intent.preparedCommandOrNull(action)
                if (command == null) {
                    Log.w(TAG, "Ignoring malformed prepared lifecycle command source=$action")
                    reconcileAfterStaleForegroundDelivery()
                } else {
                    serviceScope.launch {
                        deliverPreparedIntent(action, command)
                    }
                }
            }
            ACTION_RECONCILE_BOOT, ACTION_RECONCILE_APP, null -> {
                enterForegroundStartWindow()
                acquireWakeLock()
                activeReconciliationDeliveries++
                reconciliationActive.value = true
                startSupervision()
                val trigger = checkNotNull(ReconciliationServiceTriggerPolicy.fromAction(action))
                serviceScope.launch(Dispatchers.IO) {
                    val result = try {
                        Result.success(hostReconciler.reconcile(trigger))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        Result.failure(failure)
                    }
                    withContext(NonCancellable + Dispatchers.Main.immediate) {
                        activeReconciliationDeliveries = (activeReconciliationDeliveries - 1).coerceAtLeast(0)
                        reconciliationActive.value = activeReconciliationDeliveries > 0
                        result.onSuccess { completed ->
                            Log.i(TAG, "Reconciliation trigger=${trigger.name} outcome=${completed.outcome.name}")
                            if (completed.disposition == ReconciliationServiceDisposition.SUPERVISE_RUNTIME) {
                                acquireWakeLock()
                                startSupervision()
                            } else if (currentLifecycleDecision().teardown) {
                                teardown()
                            }
                        }.onFailure { failure ->
                            Log.e(TAG, "Reconciliation failed type=${failure.javaClass.simpleName}")
                            if (currentLifecycleDecision().teardown) teardown()
                        }
                    }
                }
            }
            ACTION_STOP -> {
                val command = intent.preparedCommandOrNull(action)
                if (command != null) {
                    serviceScope.launch { deliverPreparedIntent(action, command) }
                } else if (intent?.getBooleanExtra(EXTRA_EXTERNAL_NOTIFICATION_DELIVERY, false) == true) {
                    // Notification PendingIntents cannot contain a token prepared
                    // at click time. Delivery enters the same suspend admission
                    // path before launch cancellation or backend effects.
                    serviceScope.launch {
                        runCatching {
                            admitAndDispatch(LifecycleOperation.STOP) {
                                requestServiceStop(it, "Notification graceful stop failed", force = false)
                            }
                        }.onFailure { Log.e(TAG, "Notification stop admission failed", it) }
                    }
                } else {
                    Log.w(TAG, "Ignoring malformed stop command")
                }
            }
            else -> stopSelf()
        }
        // Null-intent recreation is an explicit process-crash trigger. stopSelf()
        // after desired STOPPED still prevents a framework restart; Android
        // force-stop suppresses both sticky restart and receivers until launch.
        return START_STICKY
    }

    private suspend fun deliverPreparedIntent(
        action: String,
        command: LifecycleTransactionToken,
    ) {
        val delivery = commandOrder.deliverAndExecute(
            reservedGeneration = command.id,
            validatePrepared = { vmManager.acceptPrepared(VmId.DEFAULT, command) },
        ) {
            when (command.operation) {
                LifecycleOperation.START -> executeStartCommand(command)
                LifecycleOperation.RESTART -> {
                    acquireWakeLock()
                    startSupervision()
                    requestServiceRestart(command, "VM restart failed")
                }
                LifecycleOperation.STOP ->
                    requestServiceStop(command, "VM graceful stop failed", force = false)
                else -> Log.w(TAG, "Ignoring operation ${command.operation} for service Intent")
            }
        }
        if (!delivery.execute) {
            logStaleCommand(action, delivery)
            if (action != ACTION_STOP) reconcileAfterStaleForegroundDelivery()
        }
    }

    private suspend fun executeStartCommand(command: LifecycleTransactionToken) =
        serviceDispatchMutex.withLock {
            var dispatchAfterAuthorization: (() -> Unit)? = null
            val authorized = vmManager.authorizeServiceDispatch(VmId.DEFAULT, command) {
                // The durable RUNNING/PENDING command is authoritative. This queue
                // only coordinates execution; process death intentionally leaves it
                // pending for ticket #11 reconciliation.
                val queuedDuringStop = launchCoordinator.queueStartDuringStop()
                if (queuedDuringStop) queuedLaunchCommand = command
                val startDecision = VmServiceStartPolicy.decide(
                    managerBusy = vmManager.busy(VmId.DEFAULT).value,
                    pendingStartOwned = launchCoordinator.ownershipActive.value,
                )
                val launch = if (!queuedDuringStop && startDecision.launchNewGeneration) {
                    prepareLaunch(command)
                } else {
                    null
                }
                dispatchAfterAuthorization = {
                    if (startDecision.acquireWakeLock) acquireWakeLock()
                    if (startDecision.armSupervision) startSupervision()
                    if (!queuedDuringStop && launch == null) {
                        serviceScope.launch(Dispatchers.IO) {
                            runCatching { vmManager.executeAccepted(VmId.DEFAULT, command) }
                                .onFailure { Log.e(TAG, "VM start command failed", it) }
                        }
                    }
                    launch?.owner?.job?.start()
                }
            }
            if (!authorized) {
                logStaleServiceDispatch(command)
                return@withLock
            }
            checkNotNull(dispatchAfterAuthorization).invoke()
        }

    private fun logStaleCommand(source: String, delivery: ServiceCommandOrder.Delivery) {
        Log.i(
            TAG,
            "Ignoring stale service command source=$source generation=${delivery.generation} " +
                "newest=${delivery.newestGeneration}",
        )
    }

    private fun logStaleServiceDispatch(command: LifecycleTransactionToken) {
        Log.i(
            TAG,
            "Ignoring stale prepared service dispatch generation=${command.id} " +
                "operation=${command.operation}",
        )
    }

    private fun reconcileAfterStaleForegroundDelivery() {
        if (currentLifecycleDecision().teardown) {
            teardown()
        } else {
            acquireWakeLock()
            startSupervision()
        }
    }

    private suspend fun admitAndDispatch(
        operation: LifecycleOperation,
        enqueueAction: String,
    ) = admitAndDispatch(operation, claimBeforeDispatch = false) { command ->
        val intent = lifecycleIntent(enqueueAction, command)
        if (enqueueAction == ACTION_START || enqueueAction == ACTION_RESTART) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            startService(intent)
        }
    }

    private suspend fun admitAndDispatch(
        operation: LifecycleOperation,
        claimBeforeDispatch: Boolean = true,
        dispatch: suspend (LifecycleTransactionToken) -> Unit,
    ): LifecycleTransactionToken = commandOrder.admitAndDispatch(
        latestDurableGeneration = {
            vmManager.supervisorState(VmId.DEFAULT).latestTransaction?.id ?: 0L
        },
        prepare = { generation ->
            vmManager.prepareLifecycleCommand(VmId.DEFAULT, operation, generation).also {
                if (claimBeforeDispatch) {
                    check(vmManager.acceptPrepared(VmId.DEFAULT, it)) {
                        "Fresh durable lifecycle command could not be claimed"
                    }
                }
            }
        },
        dispatch = { admission ->
            check(admission.prepared.id == admission.generation) {
                "Durable transaction order diverged from service command order"
            }
            dispatch(admission.prepared)
        },
    ).prepared

    private fun lifecycleIntent(action: String, command: LifecycleTransactionToken): Intent =
        Intent(this, PodroidService::class.java).apply {
            this.action = action
            putExtra(EXTRA_TRANSACTION_ID, command.id)
            putExtra(EXTRA_TRANSACTION_OPERATION, command.operation.name)
            putExtra(EXTRA_TRANSACTION_BASE_RUNTIME_GENERATION, command.baseRuntimeGeneration)
        }

    private fun Intent?.preparedCommandOrNull(action: String): LifecycleTransactionToken? {
        if (this == null || !hasExtra(EXTRA_TRANSACTION_ID) ||
            !hasExtra(EXTRA_TRANSACTION_OPERATION) ||
            !hasExtra(EXTRA_TRANSACTION_BASE_RUNTIME_GENERATION)) return null
        val id = getLongExtra(EXTRA_TRANSACTION_ID, 0L)
        val operationName = getStringExtra(EXTRA_TRANSACTION_OPERATION)
            ?.takeIf { it.length <= MAX_OPERATION_NAME_CHARS }
            ?: return null
        val operation = enumValues<LifecycleOperation>().singleOrNull { it.name == operationName }
            ?: return null
        val expected = when (action) {
            ACTION_START -> LifecycleOperation.START
            ACTION_RESTART -> LifecycleOperation.RESTART
            ACTION_STOP -> LifecycleOperation.STOP
            else -> return null
        }
        val baseRuntimeGeneration = getLongExtra(
            EXTRA_TRANSACTION_BASE_RUNTIME_GENERATION,
            -1L,
        )
        if (operation != expected || id <= 0L || baseRuntimeGeneration < 0L) return null
        return LifecycleTransactionToken.restore(id, operation, baseRuntimeGeneration)
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
        serviceScope.launch {
            runCatching {
                admitAndDispatch(LifecycleOperation.STOP) {
                    requestServiceStop(it, "Task-removal graceful stop failed", force = false)
                }
            }.onFailure { Log.e(TAG, "Task-removal stop admission failed", it) }
        }
    }

    private suspend fun requestServiceStop(
        command: LifecycleTransactionToken,
        failureLog: String,
        force: Boolean,
    ) = serviceDispatchMutex.withLock {
        var dispatchAfterAuthorization: (() -> Unit)? = null
        val authorized = vmManager.authorizeServiceDispatch(VmId.DEFAULT, command) {
            queuedLaunchCommand = null
            val wasBusy = vmManager.busy(VmId.DEFAULT).value ||
                launchCoordinator.ownershipActive.value
            val stop = launchCoordinator.beginStop()
            stop.launchOwner?.job?.cancel()
            dispatchAfterAuthorization = if (!stop.shouldExecute) {
                {
                    // The current teardown remains the in-memory owner. This
                    // newer command may share/escalate the manager stop task.
                    serviceScope.launch(Dispatchers.IO) {
                        runCatching { vmManager.executeAccepted(VmId.DEFAULT, command) }
                            .onFailure { Log.e(TAG, failureLog, it) }
                    }
                }
            } else {
                { dispatchServiceStop(stop, command, failureLog, wasBusy) }
            }
        }
        if (!authorized) {
            logStaleServiceDispatch(command)
            return@withLock
        }
        checkNotNull(dispatchAfterAuthorization).invoke()
    }

    private suspend fun requestServiceRestart(
        command: LifecycleTransactionToken,
        failureLog: String,
    ) = serviceDispatchMutex.withLock {
        var dispatchAfterAuthorization: (() -> Unit)? = null
        val authorized = vmManager.authorizeServiceDispatch(VmId.DEFAULT, command) {
            val wasBusy = vmManager.busy(VmId.DEFAULT).value ||
                launchCoordinator.ownershipActive.value
            val stop = launchCoordinator.beginStop()
            stop.launchOwner?.job?.cancel()
            if (!stop.shouldExecute) {
                // The retained replacement is a coordinator-only effect;
                // ticket #11 will reconcile it after process death.
                queuedLaunchCommand = command
                launchCoordinator.queueStartDuringStop()
                dispatchAfterAuthorization = {}
            } else {
                dispatchAfterAuthorization = {
                    // Restart remains one manager transaction, PENDING until
                    // replacement acceptance finishes.
                    dispatchServiceStop(stop, command, failureLog, wasBusy)
                }
            }
        }
        if (!authorized) {
            logStaleServiceDispatch(command)
            return@withLock
        }
        checkNotNull(dispatchAfterAuthorization).invoke()
    }

    private fun dispatchServiceStop(
        stop: ServiceLaunchCoordinator.Stop<ServiceLaunchOwner>,
        command: LifecycleTransactionToken,
        failureLog: String,
        wasBusy: Boolean,
    ) {
        if (!stop.shouldExecute) return

        // Launch invalidation/cancellation was admitted under manager authority;
        // backend and persistence work starts only after that gate was released.
        val stopScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        stopScope.launch {
            try {
                stopAndApplyPolicy(stop, command, failureLog)
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
        command: LifecycleTransactionToken,
        failureLog: String,
    ) {
        val stopResult = coroutineScope {
            val managerStop = async {
                runCatching { vmManager.executeAccepted(VmId.DEFAULT, command) }
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
            serviceDispatchMutex.withLock {
                if (stopResult.isFailure) {
                    queuedLaunchCommand?.let { queued ->
                        val failed = withContext(Dispatchers.IO) {
                            runCatching {
                                vmManager.failAccepted(
                                    VmId.DEFAULT,
                                    queued,
                                    LifecycleErrorCode.INVALID_STATE,
                                )
                            }
                        }
                        failed.onFailure { Log.e(TAG, "Queued launch failure persistence failed", it) }
                    }
                    launchCoordinator.beginStop()
                    queuedLaunchCommand = null
                }
                val queuedCommand = queuedLaunchCommand
                var queuedOwner: ServiceLaunchOwner? = null
                var launch: ServiceLaunchCoordinator.Launch<ServiceLaunchOwner>? = null
                if (queuedCommand != null) {
                    val authorized = vmManager.authorizeServiceDispatch(VmId.DEFAULT, queuedCommand) {
                        // Re-fence the stop-to-start handoff: the command may have
                        // been superseded while backend shutdown was in flight.
                        if (queuedLaunchCommand == queuedCommand) {
                            queuedLaunchCommand = null
                            queuedOwner = createLaunchOwner(queuedCommand)
                            launch = launchCoordinator.completeStop(
                                stop.generation,
                                checkNotNull(queuedOwner),
                            )?.launch
                            queuedOwner?.generation = launch?.generation ?: 0L
                        }
                    }
                    if (!authorized) {
                        logStaleServiceDispatch(queuedCommand)
                        if (queuedLaunchCommand == queuedCommand) {
                            queuedLaunchCommand = null
                            launchCoordinator.beginStop()
                        }
                    }
                }
                if (queuedOwner == null) {
                    val unusedOwner = createLaunchOwner(command).also { it.job.cancel() }
                    launch = launchCoordinator.completeStop(stop.generation, unusedOwner)?.launch
                }
                val admittedOwner = queuedOwner
                if (launch != null && admittedOwner != null) {
                    acquireWakeLock()
                    startSupervision()
                    launch?.owner?.job?.start()
                } else {
                    admittedOwner?.job?.cancel()
                    val decision = currentLifecycleDecision()
                    if (decision.teardown) teardown()
                    else if (decision.notification == VmServiceNotification.CLEANUP_INCOMPLETE) {
                        updateNotification("VM error — cleanup incomplete; Stop retries cleanup")
                    }
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
            reconciliationActive,
        ) { state, quiescent, busy, pendingStart, reconciling ->
            VmServiceLifecyclePolicy.decide(state, quiescent, busy, pendingStart || reconciling)
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
            launchCoordinator.ownershipActive.value || reconciliationActive.value,
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

    private fun prepareLaunch(
        command: LifecycleTransactionToken,
    ): ServiceLaunchCoordinator.Launch<ServiceLaunchOwner>? {
        val owner = createLaunchOwner(command)
        val launch = launchCoordinator.beginLaunch(owner)
        if (launch == null) {
            owner.job.cancel()
            return null
        }
        owner.generation = launch.generation
        return launch
    }

    private fun createLaunchOwner(command: LifecycleTransactionToken): ServiceLaunchOwner {
        lateinit var owner: ServiceLaunchOwner
        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
            var failure: Throwable? = null
            try {
                withContext(Dispatchers.IO) {
                    vmManager.executeAccepted(VmId.DEFAULT, owner.command)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (caught: Throwable) {
                failure = caught
                Log.e(TAG, "VM failed to execute prepared launch", caught)
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
        owner = ServiceLaunchOwner(job, command)
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
            Intent(this, PodroidService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_EXTERNAL_NOTIFICATION_DELIVERY, true)
            },
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

    // Admission is suspend-capable: durable desired state + PENDING token must
    // exist before the bridge receives OK. Only dispatch is delayed so the
    // response can flush before this service and its VM transport tear down.
    private suspend fun handlePowerRequest(action: String): String =
        guestPowerRequestHandler.handle(action)

    private fun scheduleGuestPowerCommand(command: LifecycleTransactionToken) {
        check(serviceScope.isActive) { "Service is stopping" }
        serviceScope.launch {
            delay(GUEST_POWER_RESPONSE_FLUSH_MS)
            when (command.operation) {
                LifecycleOperation.STOP -> requestServiceStop(
                    command,
                    "Guest graceful stop failed",
                    force = false,
                )
                LifecycleOperation.RESTART -> requestServiceRestart(
                    command,
                    "Guest restart failed",
                )
                else -> error("Unsupported guest power operation")
            }
        }
    }

    companion object {
        private const val TAG = "PodroidService"
        private const val CHANNEL_ID = "podroid_service"
        private const val NOTIFICATION_ID = 1001
        private const val SERVICE_LAUNCH_JOIN_TIMEOUT_MS = 8_000L
        private const val GUEST_POWER_RESPONSE_FLUSH_MS = 300L
        private const val EXTRA_TRANSACTION_ID =
            "com.excp.podroid.extra.LIFECYCLE_TRANSACTION_ID"
        private const val EXTRA_TRANSACTION_OPERATION =
            "com.excp.podroid.extra.LIFECYCLE_TRANSACTION_OPERATION"
        private const val EXTRA_TRANSACTION_BASE_RUNTIME_GENERATION =
            "com.excp.podroid.extra.LIFECYCLE_TRANSACTION_BASE_RUNTIME_GENERATION"
        private const val EXTRA_EXTERNAL_NOTIFICATION_DELIVERY =
            "com.excp.podroid.extra.EXTERNAL_NOTIFICATION_DELIVERY"
        private const val MAX_OPERATION_NAME_CHARS = 32

        // Process-lifetime ordering is anchored to the durable latest id on
        // every admission and can adopt a prepared token after recreation.
        private val commandOrder = ServiceCommandOrder()

        const val ACTION_START   = "com.excp.podroid.action.START"
        const val ACTION_STOP    = "com.excp.podroid.action.STOP"
        const val ACTION_RESTART = "com.excp.podroid.action.RESTART"
        const val ACTION_RECONCILE_BOOT = "com.excp.podroid.action.RECONCILE_BOOT"
        const val ACTION_RECONCILE_APP = "com.excp.podroid.action.RECONCILE_APP"

        internal fun reconciliationIntent(context: Context, action: String) =
            Intent(context, PodroidService::class.java).setAction(action)
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
