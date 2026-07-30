/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * AvfEngine — VmEngine implementation backed by Android Virtualization Framework.
 *
 * Uses AvfReflect for all android.system.virtualmachine.* calls so the APK
 * compiles against the public SDK. On devices without pKVM the start() call
 * lands in the catch branch and transitions to VmState.Error.
 *
 * Terminal wiring: ConsoleFanout bridges AVF console streams to a filesystem
 * unix socket; libpodroid-bridge connects to that socket and splices PTY ↔
 * socket, same as the QEMU path. The ctrl socket is a dummy path (never bound)
 * — bridge tolerates the connect failure gracefully (no resize on AVF, MVP).
 */
package com.excp.podroid.engine.avf

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.excp.podroid.data.repository.PortForwardRule
import com.excp.podroid.data.repository.SettingsRepository
import com.excp.podroid.engine.BootStageDetector
import com.excp.podroid.engine.BootStageGenerationGate
import com.excp.podroid.engine.BoundedRunLog
import com.excp.podroid.engine.GenerationBoundTerminalSlot
import com.excp.podroid.engine.SensitiveConsolePolicy
import com.excp.podroid.engine.QmpController
import com.excp.podroid.engine.VmConfig
import com.excp.podroid.engine.VmEngine
import com.excp.podroid.engine.VmState
import com.excp.podroid.util.LogProxy
import com.excp.podroid.vm.VmBootArtifacts
import com.excp.podroid.vm.VmBootFiles
import com.excp.podroid.vm.VmId
import com.excp.podroid.vm.VmPathSecurity
import com.excp.podroid.vm.VmPaths
import com.excp.podroid.vm.bootFiles
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

internal fun avfBootFiles(config: VmConfig, paths: VmPaths): VmBootFiles = config.bootFiles(paths)

// AVF requires API 34 (UpsideDownCake). EngineHolder only resolves the
// Provider<AvfEngine> when getCapabilities() reports AVF support, which implies
// API 34+, but lint can't see that runtime gate - declaring the class here lets
// the in-class calls to the @RequiresApi(34) avf/* helpers type-check cleanly.
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
@Singleton
class AvfEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val vmPaths: VmPaths,
) : VmEngine {
    override val vmId: VmId = vmPaths.vmId

    companion object {
        private const val TAG = "AvfEngine"
        // Use "podroid" as the VM name. The smoke-test in AvfDiagnostics used
        // "podroid-avf-smoke" — a different name — so there is no config
        // conflict between the two paths.
        private const val VM_NAME = "podroid"
        // Safety net for a guest that never emits "Ready!" (a console quirk, a
        // lost marker, no onStopped/onDied) so it doesn't strand the engine in
        // Starting forever — EngineHolder.trySwap waits on a terminal state, so
        // a stuck Starting blocks backend switch. Sized to match QemuEngine's
        // BOOT_READY_SAFETY_MS: a first boot does dropbear host-key generation
        // (~56s worst case) which AVF performs too. A premature forced-Running
        // is worse on AVF than QEMU — the console.log privacy gate (onVmBytes)
        // closes on leaving Starting, so the tail of a genuinely slow boot
        // (including a late panic) would be lost.
        private const val BOOT_TIMEOUT_MS = 120_000L
        // Upper bound on the best-effort guest flush before stop. Bounded so a
        // hung guest can never block the stop path; the caller proceeds to kill
        // the VM if no SYNCED ack arrives.
        private const val SYNC_TIMEOUT_MS = 8_000L
        private const val FRAMEWORK_STOP_CALLBACK_TIMEOUT_MS = 5_000L
        // VirtualMachineCallback.STOP_REASON_REBOOT — a guest-requested reboot,
        // which during early boot is the MATCH_HOST topology crash (issue #29).
        private const val STOP_REASON_REBOOT = 5
    }

    private val _state = MutableStateFlow<VmState>(VmState.Idle)
    override val state: StateFlow<VmState> = _state.asStateFlow()
    private val _quiescent = MutableStateFlow(true)
    override val quiescent: StateFlow<Boolean> = _quiescent.asStateFlow()

    private val _bootStage = MutableStateFlow("")
    override val bootStage: StateFlow<String> = _bootStage.asStateFlow()

    private val _consoleText = MutableStateFlow("")
    override val consoleText: StateFlow<String> = _consoleText.asStateFlow()

    private val _stopping = MutableStateFlow(false)
    override val stopping: StateFlow<Boolean> = _stopping.asStateFlow()

    // Every slot access is serialized on this engine's monitor, the same
    // monitor held by cleanup(), so a session built outside the lock can only
    // register into its still-live launch generation.
    private val terminalSlot = GenerationBoundTerminalSlot<TerminalSession>()

    override val terminalSession: TerminalSession?
        @Synchronized get() = terminalSlot.current()

    // Wall-clock millis of the most recent →Running transition (both the
    // detector onReady path and the boot-timeout fallback set it), null until
    // running and after cleanup(). Drives the Home uptime readout. @Volatile:
    // set on the detector/timeout thread, read by the UI ticker on another.
    @Volatile private var _runningSinceMs: Long? = null
    override val runningSinceMs: Long? get() = _runningSinceMs

    override val backendId: String = "avf"

    /** AVF has no QMP socket. */
    override val qmpController: QmpController? = null

    override var sessionClientDelegate: TerminalSessionClient? = null

    private val proxySessionClient = object : TerminalSessionClient {
        override fun onTextChanged(s: TerminalSession) { sessionClientDelegate?.onTextChanged(s) }
        override fun onTitleChanged(s: TerminalSession) { sessionClientDelegate?.onTitleChanged(s) }
        override fun onSessionFinished(s: TerminalSession) { sessionClientDelegate?.onSessionFinished(s) }
        override fun onCopyTextToClipboard(s: TerminalSession, text: String?) { sessionClientDelegate?.onCopyTextToClipboard(s, text) }
        override fun onPasteTextFromClipboard(s: TerminalSession?) { sessionClientDelegate?.onPasteTextFromClipboard(s) }
        override fun onBell(s: TerminalSession) { sessionClientDelegate?.onBell(s) }
        override fun onColorsChanged(s: TerminalSession) { sessionClientDelegate?.onColorsChanged(s) }
        override fun onTerminalCursorStateChange(state: Boolean) { sessionClientDelegate?.onTerminalCursorStateChange(state) }
        override fun setTerminalShellPid(s: TerminalSession, pid: Int) { sessionClientDelegate?.setTerminalShellPid(s, pid) }
        override fun getTerminalCursorStyle(): Int = sessionClientDelegate?.terminalCursorStyle ?: 0
        override fun getTerminalVersionString(): String? = sessionClientDelegate?.terminalVersionString
        override fun logError(tag: String?, msg: String?) = LogProxy.error(tag, TAG, msg)
        override fun logWarn(tag: String?, msg: String?) = LogProxy.warn(tag, TAG, msg)
        override fun logInfo(tag: String?, msg: String?) = LogProxy.info(tag, TAG, msg)
        override fun logDebug(tag: String?, msg: String?) = LogProxy.debug(tag, TAG, msg)
        override fun logVerbose(tag: String?, msg: String?) = LogProxy.verbose(tag, TAG, msg)
        override fun logStackTraceWithMessage(tag: String?, msg: String?, e: Exception?) =
            LogProxy.stackTraceWithMessage(tag, TAG, msg, e)
        override fun logStackTrace(tag: String?, e: Exception?) = LogProxy.stackTrace(tag, TAG, e)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Serializes the check-and-claim in start(). Two near-simultaneous
    // ACTION_STARTs can both pass PodroidService's main-thread alreadyActive
    // check before either coroutine flips _state to Starting; without this lock
    // both would run launchAttempt against the same VM_NAME (mirrors
    // QemuEngine.startMutex).
    private val startMutex = Mutex()
    // @Volatile: written on the start()/cleanup() thread, read from stop() and
    // addPortForward() on other threads. Every other shared lifecycle field here
    // (control, stopRequested, vmGeneration) is already @Volatile for the same
    // cross-thread visibility reason; vmHandle was the lone unsynchronized gap.
    @Volatile private var vmHandle: Any? = null
    /**
     * Monotonic per-start token. Captured in each VM's callback closures so a
     * late callback from a PREVIOUS VM (callbacks run on ForkJoinPool, so a fast
     * Stop → Start can leave the old VM's events in flight) can't clobber the new
     * VM's state. QemuEngine funnels all state through one exit thread for the
     * same reason; AVF has no single exit thread, so a generation token guards it.
     */
    @Volatile private var vmGeneration: Long = 0
    @Volatile private var activeBootGeneration: Long? = null
    private val bootStageGate = BootStageGenerationGate()
    /**
     * Set by stop() before signalling the framework so a subsequent
     * onError/onStopped/onDied is treated as the expected consequence of a
     * user-initiated stop and never downgrades a clean Stopped to Error.
     */
    @Volatile private var stopRequested = false
    /** Invalidates a graceful sync coroutine when forceStop preempts it. */
    @Volatile private var stopSequence = 0L
    /** Runs cleanup() exactly once per VM lifetime (every terminal path routes through it). */
    private val cleanedUp = AtomicBoolean(true)
    /** Guards the StringBuilder mutated on the fanout pump thread vs cleared in start(). */
    private val consoleLock = Any()
    private var bootTimeoutJob: kotlinx.coroutines.Job? = null
    private var consoleStream: java.io.InputStream? = null
    private var consoleStreamInput: java.io.OutputStream? = null
    private var fanout: ConsoleFanout? = null
    // Console capture — matches QemuEngine's pattern. Without this AVF kernel
    // panics are invisible (issue #29): the boot bytes only went to the bridge
    // socket + boot detector, never to console.log or the consoleText flow
    // surfaced by the diagnostic exporter.
    private val consoleBuilder = StringBuilder()
    private val maxConsoleSize = 64 * 1024
    private var logOut: java.io.OutputStream? = null
    @Volatile private var persistedConsoleCaptureEnabled = false
    @Volatile private var control: VsockControlChannel? = null
    /** Live AVF Downloads share (9p over vsock); started in bringUpControlChannel(), torn down in cleanup(). */
    @Volatile private var downloadsShare: AvfDownloadsShare? = null
    /**
     * Initial rules captured from start()'s portForwards arg — includes the
     * auto-injected SSH/X11/audio rules that PodroidService adds in-memory
     * (not in DataStore), which EngineHolder's diff loop never sees. Replayed
     * via addPortForward() in the onReady callback. The putIfAbsent dedup in
     * addPortForward handles the race with EngineHolder's parallel dispatch
     * of DataStore rules — whichever path wins owns the forwarder.
     */
    private val initialRules = mutableListOf<com.excp.podroid.data.repository.PortForwardRule>()
    /** vsock port → forwarder. Written via addPortForward/removePortForward only. */
    private val forwarders = java.util.concurrent.ConcurrentHashMap<Int, Forwarder>()
    @Volatile private var lastSentRows = -1
    @Volatile private var lastSentCols = -1

    // Adaptive multi-vCPU fallback (issue #29). The launch args are remembered so
    // an early-boot reset can re-launch with fewer cores WITHOUT going through a
    // terminal state (which would tear the foreground service down). attemptedCpus
    // is the effective count the current boot was started with, read by the
    // onStopped funnel to decide the next rung. See AvfCpuPolicy.
    @Volatile private var lastConfig: VmConfig? = null
    private val lastPortForwards = mutableListOf<com.excp.podroid.data.repository.PortForwardRule>()
    @Volatile private var attemptedCpus = 1
    // True when buildConfig chose the explicit-cpuCount topology (Android 16+
    // raw AIDL, the #29 fix proper) — launchAttempt must then arm the createVm
    // hook; the cmdline carries no nr_cpus on that path.
    @Volatile private var useExplicitCpuCount = false

    /** Backend-specific diagnostics for the export log (observational only). */
    @Volatile private var lastLifecycleEvent: String = "(no lifecycle callback yet)"
    @Volatile private var launchConfigSummary: String = "(VM not started this session)"
    private var resizeDebounceJob: kotlinx.coroutines.Job? = null

    val terminalSockPath: String get() = vmPaths.avfTerminalSocket.absolutePath
    val ctrlSockPath: String get() = vmPaths.avfControlSocket.absolutePath

    // Per-run detector (re-created each launchAttempt, not reset-and-reused).
    // A fresh instance means a prior run's still-draining ConsoleFanout pump
    // feeds the OLD detector (already ready=true, ignored) instead of flipping
    // the NEW boot to Running on a late "Ready!" — the one state-transition
    // path the generation token didn't otherwise cover.
    @Volatile private var detector = BootStageDetector { }

    private fun newDetector(generation: Long) = BootStageDetector { stage ->
        val accepted = bootStageGate.apply(
            generation,
            isStarting = { _state.value is VmState.Starting },
            isQuiescent = { _quiescent.value },
            mutation = {
                _bootStage.value = stage
                if (stage == "Ready") {
                    _runningSinceMs = System.currentTimeMillis()
                    _state.value = VmState.Running
                }
            },
        )
        if (!accepted || stage != "Ready") return@BootStageDetector
        Log.i(TAG, "boot Ready! detected — bridge connects via ConsoleFanout")
        // Wipe the boot log from the emulator so the user sees a clean login
        // prompt. AVF only exposes one captured console stream, so unlike
        // QEMU (separate serial.sock for boot) every kernel byte ends up in
        // the PTY scrollback by the time Open Terminal is tapped. ESC c =
        // RIS (full terminal reset); ESC [2J + ESC [H clears + homes the
        // cursor in case the emulator silently ignores RIS.
        val reset = byteArrayOf(0x1b, 'c'.code.toByte(),
            0x1b, '['.code.toByte(), '2'.code.toByte(), 'J'.code.toByte(),
            0x1b, '['.code.toByte(), 'H'.code.toByte())
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching { terminalSession?.emulator?.append(reset, reset.size) }
        }
        bringUpControlChannel()
    }

    /**
     * Bring up the vsock control channel and replay the initial port-forward
     * rules. EngineHolder's diff loop fires on state → Running and dispatches
     * DataStore rules; we replay the initialRules here too because PodroidService
     * auto-injects SSH/X11/audio rules in-memory (never in DataStore) and
     * EngineHolder never sees them. addPortForward's putIfAbsent dedups the
     * parallel path. Idempotent (guarded on a null `control`) so the detector's
     * onReady and the boot-timeout fallback can't both open a second channel.
     */
    @Synchronized
    private fun bringUpControlChannel() {
        if (control != null) return
        val vm = vmHandle ?: return
        val ctl = VsockControlChannel(vm, scope)
        // Assign `control` BEFORE replaying initialRules so each replayed
        // addPortForward sees a non-null channel and its ADD lands (the channel
        // buffers commands internally until the guest agent connects). A
        // DataStore rule that raced in ahead of this still has its host listener
        // bound; addPortForward logs a one-time warning for that narrow window.
        control = ctl
        ctl.open()
        if (lastConfig?.storageAccessEnabled == true) {
            // Assign `downloadsShare` BEFORE calling start() (mirrors `control =
            // ctl; ctl.open()` above): start() only launches a non-blocking
            // connect+serve coroutine now, but a racing cleanup() must always
            // see a non-null share to stop, even in the instant before that
            // coroutine's connect attempt completes. The real outcome
            // ("added"/"unavailable") is logged from inside the coroutine.
            val share = AvfDownloadsShare(vm, scope)
            downloadsShare = share
            Log.i(TAG, "downloadsShare=starting")
            share.start()
        }
        scope.launch {
            // addPortForward now rethrows on failure (for EngineHolder's retry).
            // Guard each replay so one failing rule doesn't abort the rest;
            // EngineHolder's diff still retries genuinely-failed DataStore rules.
            for (rule in initialRules) {
                try {
                    addPortForward(rule)
                } catch (c: CancellationException) {
                    throw c
                } catch (e: Throwable) {
                    Log.w(TAG, "initial forward replay failed for $rule (continuing)", e)
                }
            }
        }
    }

    override suspend fun start(portForwards: List<PortForwardRule>, config: VmConfig) {
        require(config.vmId == vmId) { "AVF engine ${vmId.serialized} cannot start ${config.vmId.serialized}" }
        startMutex.withLock {
            if (!_quiescent.value || _state.value is VmState.Running || _state.value is VmState.Starting) return
            // Remember the launch args so the adaptive multi-vCPU fallback (issue
            // #29) can re-launch with fewer cores after an early-boot reset WITHOUT
            // routing through a terminal state (Error/Stopped tears the foreground
            // service down — see PodroidService.observeStateForShutdown). Claim
            // Starting under the lock so a re-entrant start() is rejected.
            lastConfig = config
            stopRequested = false
            // Defensive generation boundary: cleanup normally emptied the slot,
            // but a stale reference must never suppress this run's bridge.
            retireTerminalBridge()
            synchronized(lastPortForwards) { lastPortForwards.clear(); lastPortForwards.addAll(portForwards) }
            // Publish before AVF manager/config/stream resources can be acquired.
            _quiescent.value = false
            _stopping.value = false
            _state.value = VmState.Starting
        }
        launchAttempt(portForwards, config, bootMsg = "Initializing AVF...")
    }

    /**
     * One VM boot attempt. Split out of start() so the adaptive vCPU fallback
     * (relaunchWithFewerCpus) can re-enter it after an early-boot reset while the
     * state stays Starting. Assumes _state is already Starting.
     */
    private suspend fun launchAttempt(
        portForwards: List<PortForwardRule>,
        config: VmConfig,
        bootMsg: String,
    ) {
        // New VM lifetime: bump the generation so any in-flight callback from a
        // prior VM is ignored, clear the user-stop flag, and arm cleanup() to run
        // once on this run's first terminal transition.
        val generation = ++vmGeneration
        activeBootGeneration = generation
        bootStageGate.arm(generation)
        cleanedUp.set(false)
        persistedConsoleCaptureEnabled = SensitiveConsolePolicy.persistedCaptureAllowed(
            config.qemuExtraArgs,
            config.kernelExtraCmdline,
        )
        if (!persistedConsoleCaptureEnabled) vmPaths.consoleLog.delete()
        _quiescent.value = false
        // A stop that landed after adaptive cleanup but before this relaunch must
        // not be erased. Abort before reacquiring AVF resources.
        if (stopRequested) {
            cleanup(terminalState = VmState.Stopped)
            return
        }
        // Reset console capture from any prior run before we start emitting.
        // Guarded against the prior run's fanout pump still appending (a fast
        // Stop → Start can leave the old vm→bridge pump alive a moment).
        synchronized(consoleLock) { consoleBuilder.clear() }
        _consoleText.value = ""
        initialRules.clear()
        initialRules.addAll(portForwards)
        _bootStage.value = bootMsg
        // Fresh detector per run so a Stop → Start cycle's second boot can fire
        // onReady again AND a prior run's late console bytes can't drive this
        // boot's state (see the field comment).
        detector = newDetector(generation)

        val pathSecurity = VmPathSecurity(vmPaths)
        try {
            pathSecurity.validateForLaunch()
            config.bootArtifacts?.validateFiles()
            val mgr = AvfReflect.manager(context)
            val vmConfigObj = buildConfig(mgr, config, pathSecurity)

            // AOSP canonical pattern: get-or-create, then attempt to update the
            // config on the existing VM. If AVF rejects the new config as
            // incompatible (e.g. vmOutputCaptured changed from a stale record),
            // delete the old VM and create a fresh one.
            val vm: Any = run {
                val existing = AvfReflect.getOrCreate(mgr, VM_NAME, vmConfigObj)
                runCatching { AvfReflect.setConfig(existing, vmConfigObj) }.fold(
                    onSuccess = { existing },
                    onFailure = {
                        // Framework messages may echo resolved kernel parameters.
                        Log.w(TAG, "stale VM config detected type=${it.javaClass.simpleName}; deleting + recreating")
                        AvfReflect.delete(mgr, VM_NAME)
                        AvfReflect.create(mgr, VM_NAME, vmConfigObj)
                    },
                )
            }
            vmHandle = vm

            // Explicit-cpuCount topology (issue #29): arm the createVm rewrite
            // before run(). A failed install poisons the hook process-wide, so
            // the immediate relaunch rebuilds the cmdline on the nr_cpus
            // fallback path — this cannot recurse a second time.
            if (useExplicitCpuCount && !AvfReflect.installExplicitCpuCount(vm, attemptedCpus)) {
                Log.w(TAG, "explicit vCPU hook unavailable at install time; " +
                    "relaunching on the nr_cpus fallback path")
                cleanup(publishQuiescent = false)
                launchAttempt(portForwards, config, bootMsg)
                return
            }

            val cb = AvfReflect.newVmCallback(
                onError = { code, msg ->
                    val reason = AvfReasonCodes.errorCode(code)
                    // Framework messages may echo user kernel extras. Preserve
                    // reason codes and explicit redaction, never the raw text.
                    val messageSummary = if (msg == null) "none" else "[redacted; charCount=${msg.length}]"
                    Log.e(TAG, "VM onError $reason msg=$messageSummary")
                    lastLifecycleEvent = "onError($reason) msg=$messageSummary"
                    // Funnel respects terminal states (won't clobber a user Stop)
                    // and the generation token (ignores a prior VM's late error).
                    onVmTerminal(generation, VmState.Error("AVF onError($reason): $messageSummary"))
                },
                onStopped = { reason ->
                    Log.i(TAG, "VM onStopped ${AvfReasonCodes.stopReason(reason)}")
                    lastLifecycleEvent = "onStopped(${AvfReasonCodes.stopReason(reason)})"
                    // Adaptive multi-vCPU fallback (issue #29): an early-boot reset
                    // (STOP_REASON_REBOOT while still Starting) with more than one
                    // core attempted is the MATCH_HOST heterogeneous-topology crash
                    // on Tensor G3/G4. Step the core count down and relaunch rather
                    // than surfacing an error; only a reset at 1 core (or a clean
                    // stop) falls through to the normal terminal handling.
                    if (!maybeRelaunchWithFewerCpus(generation, reason)) {
                        // A stop while still Starting means the VM exited during boot;
                        // otherwise it's a clean stop. The funnel maps a user-stop to
                        // Stopped regardless and ignores stale/terminal transitions.
                        val target = if (_state.value is VmState.Starting && !stopRequested) {
                            VmState.Error("VM exited during boot (${AvfReasonCodes.stopReason(reason)})")
                        } else {
                            VmState.Stopped
                        }
                        onVmTerminal(generation, target)
                    }
                },
                onDied = { reason ->
                    // onDied fires on backend-level death (virtmgr/crosvm), often
                    // with no preceding onStopped. Previously it only logged, so a
                    // death while Starting stranded the engine in Starting forever
                    // (UI stuck, EngineHolder.trySwap never released). Drive a
                    // terminal transition + cleanup through the same funnel.
                    Log.w(TAG, "VM onDied ${AvfReasonCodes.stopReason(reason)}")
                    lastLifecycleEvent = "onDied(${AvfReasonCodes.stopReason(reason)})"
                    val target = if (stopRequested) {
                        VmState.Stopped
                    } else {
                        VmState.Error("VM died (${AvfReasonCodes.stopReason(reason)})")
                    }
                    onVmTerminal(generation, target)
                },
            )
            AvfReflect.setCallback(vm, java.util.concurrent.ForkJoinPool.commonPool(), cb)

            val inStream = AvfReflect.consoleOutput(vm)
            val outStream = AvfReflect.consoleInput(vm)
            consoleStream = inStream
            consoleStreamInput = outStream

            // Open console.log fresh (mirrors QemuEngine's monitorBootSerial).
            // The diagnostic exporter cats this path; on AVF it was previously
            // always empty, masking kernel panics like issue #29's reason=5
            // reboot on Pixel 8a. Tee inside ConsoleFanout's vm→bridge pump.
            val logFile = vmPaths.consoleLog
            runCatching { logFile.delete() }
            val log = if (persistedConsoleCaptureEnabled) {
                runCatching {
                    BoundedRunLog(java.io.FileOutputStream(logFile, false))
                }
                    .onFailure { Log.w(TAG, "console.log open failed (continuing without capture)", it) }
                    .getOrNull()
            } else {
                null
            }
            logOut = log

            // Fan out: VM ↔ filesystem socket. The bridge subprocess connects to
            // that socket and splices PTY ↔ socket. BootStageDetector tees the
            // VM output to drive boot-stage + state transitions; onVmBytes tees
            // the boot-phase bytes (Starting only — see the privacy note there)
            // to console.log + the consoleText flow.
            val fo = ConsoleFanout(
                consoleOutput = inStream,
                consoleInput = outStream,
                socketPath = terminalSockPath,
                detector = detector,
                scope = scope,
                onVmBytes = { buf, n ->
                    // PRIVACY: on AVF the console rides hvc0 — the SAME device as the
                    // interactive getty/shell — so persist to console.log ONLY while
                    // booting. Once the VM is Running, hvc0 carries everything the user
                    // types and sees (login, shell I/O, file contents); writing that to
                    // console.log would leak the whole session into the exportable
                    // diagnostic log. Boot output (incl. boot-time kernel panics like
                    // issue #29) is still captured; a later death's reason still lands
                    // via the onStopped/onDied callbacks.
                    bootStageGate.applyCurrent(generation) {
                        if (_state.value is VmState.Starting) {
                            runCatching {
                                log?.write(buf, 0, n)
                                log?.flush()
                            }
                            // UTF-8 decode best-effort (split multi-byte sequences may
                            // surface as U+FFFD here — acceptable for the diagnostic
                            // tail; the raw bytes hit disk faithfully above).
                            val snapshot = synchronized(consoleLock) {
                                consoleBuilder.append(String(buf, 0, n, Charsets.UTF_8))
                                if (consoleBuilder.length > maxConsoleSize) {
                                    consoleBuilder.delete(0, consoleBuilder.length - maxConsoleSize)
                                }
                                consoleBuilder.toString()
                            }
                            _consoleText.value = snapshot
                        }
                    }
                },
            )
            fanout = fo
            fo.start()

            // Final stop-vs-start interleave guard: a user Stop landing during
            // config build / VM create set stopRequested (and took the
            // null-handle terminal path). Don't run() a VM the user already
            // asked to stop — clean up and bail before it boots headless.
            if (stopRequested || cleanedUp.get()) {
                Log.i(TAG, "AVF launch aborted before run() — stop requested mid-start")
                cleanup(terminalState = VmState.Stopped)
                return
            }

            // Console setup and image preparation can change the tree; validate
            // again at the final boundary immediately before vm.run().
            pathSecurity.validateForLaunch()
            AvfReflect.run(vm)
            val status = runCatching { AvfReflect.getStatus(vm) }.getOrDefault(-1)
            Log.i(TAG, "vm.run() returned — VM booting (status=$status)")
            spawnBridge(generation)

            // Boot-timeout fallback (mirrors QemuEngine): the engine leaves
            // Starting only via the detector's "Ready!" or a VM callback. If a
            // console quirk or a lost marker keeps both from firing, promote a
            // still-Starting VM of THIS generation to Running and bring up the
            // control channel (idempotent) so EngineHolder.trySwap can release
            // and the bridge is wired. The generation/cleanedUp guard prevents
            // promoting a VM that already stopped in the delay window.
            bootTimeoutJob = scope.launch {
                kotlinx.coroutines.delay(BOOT_TIMEOUT_MS)
                val promoted = bootStageGate.apply(
                    generation,
                    isStarting = { _state.value is VmState.Starting },
                    isQuiescent = { _quiescent.value },
                    mutation = {
                        _bootStage.value = "Ready"
                        _runningSinceMs = System.currentTimeMillis()
                        _state.value = VmState.Running
                    },
                )
                if (promoted) {
                    Log.w(TAG, "AVF boot timeout — forcing Running state")
                    bringUpControlChannel()
                }
            }
        } catch (e: CancellationException) {
            // The start coroutine runs in PodroidService.serviceScope, cancelled
            // on every stop/teardown; buildConfig has real suspension points (the
            // DataStore cpu-cap read). A cancel landing mid-start is NOT an engine
            // error — letting it fall into the generic catch below would set a
            // sticky Error that poisons the next start (QemuEngine guards the same
            // way). Clean up and let the cancellation propagate.
            Log.i(TAG, "AVF start cancelled (teardown) — not an error")
            cleanup(terminalState = VmState.Stopped)
            throw e
        } catch (e: Throwable) {
            val cause = e.cause ?: e
            // Throwable messages from AVF may include the resolved cmdline.
            Log.e(TAG, "AVF start failed type=${cause.javaClass.simpleName}; message=[redacted]")
            cleanup(
                terminalState = VmState.Error(
                    "AVF rejected: ${cause.javaClass.simpleName}: [message redacted]",
                ),
            )
        }
    }

    /**
     * Adaptive multi-vCPU fallback for the Tensor G3/G4 MATCH_HOST early-boot
     * reset (issue #29). Returns true if it consumed the stop event by scheduling
     * a relaunch with fewer cores; false to let normal terminal handling proceed.
     *
     * Fires only on a STOP_REASON_REBOOT for the live generation, while still
     * Starting, not user-initiated, with more than one core attempted. The next
     * lower count (AvfCpuPolicy.nextRungDown) is persisted so later launches skip
     * straight to a value that boots; the relaunch keeps _state in Starting so the
     * foreground service stays up (a terminal state would tear it down). The
     * ladder is finite and monotonic (e.g. 8 -> 2 -> 1), so this can't loop.
     */
    @Synchronized
    private fun maybeRelaunchWithFewerCpus(generation: Long, reason: Int): Boolean {
        if (reason != STOP_REASON_REBOOT) return false
        if (generation != vmGeneration) return false
        if (stopRequested || _state.value !is VmState.Starting) return false
        val next = AvfCpuPolicy.nextRungDown(attemptedCpus) ?: return false
        val config = lastConfig ?: return false
        val forwards = synchronized(lastPortForwards) { lastPortForwards.toList() }
        val mode = if (useExplicitCpuCount) "explicit-count" else "MATCH_HOST+nr_cpus"
        // Fence this dead VM immediately, before the asynchronous cleanup →
        // relaunch gap. A second framework callback from [generation] is stale
        // from this point and cannot publish a terminal/quiescent replacement.
        ++vmGeneration
        Log.w(TAG, "AVF early-boot reset at $attemptedCpus vCPU(s) ($mode topology, #29); " +
            "capping to $next and relaunching")
        scope.launch {
            runCatching { settingsRepository.setAvfCpuCap(next) }
                .onFailure { Log.w(TAG, "persisting AVF cpu cap failed (continuing)", it) }
            // Free the dead VM's resources without a terminal transition: cleanup()
            // never touches _state, and launchAttempt re-arms cleanedUp.
            cleanup(publishQuiescent = false)
            // A user Stop landing between the callback-thread check above and here
            // must win: cleanup() ran, so honour the stop instead of relaunching.
            if (stopRequested) {
                Log.i(TAG, "AVF relaunch aborted — stop requested during cleanup")
                onVmTerminal(vmGeneration, VmState.Stopped)
                return@launch
            }
            val plural = if (next == 1) "" else "s"
            launchAttempt(forwards, config, bootMsg = "Retrying with $next core$plural...")
        }
        return true
    }

    /**
     * Spawn libpodroid-bridge.so unconditionally at VM start, NOT on demand.
     * The fanout's accept() loops until the bridge connects; without an early
     * bridge spawn, the VM's console output is never drained and BootStageDetector
     * never sees "Ready!". This matches QemuEngine's autoStartBridge pattern but
     * with inverse timing (QEMU spawns AFTER ready; AVF spawns BEFORE ready).
     */
    private fun spawnBridge(generation: Long) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (hasLiveTerminalBridge(generation)) return@post
            val bridgeExe = File(context.applicationInfo.nativeLibraryDir, "libpodroid-bridge.so")
            if (!bridgeExe.exists()) {
                Log.e(TAG, "bridge missing at ${bridgeExe.absolutePath}")
                return@post
            }
            // Construction starts the subprocess and therefore stays outside the
            // engine monitor. Registration below revalidates the exact launch;
            // cleanup winning this window causes immediate session termination.
            val sess = newTerminalSession(bridgeExe)
            val selected = registerTerminalBridge(generation, sess)
            if (selected === sess) Log.d(TAG, "AVF bridge auto-spawned (resize-notifying)")
        }
    }

    override fun stop() {
        stopRequested = true
        val vm = vmHandle
        if (vm == null) {
            onVmTerminal(vmGeneration, VmState.Stopped)
            return
        }
        _stopping.value = true
        val generation = vmGeneration
        val requestSequence = ++stopSequence
        scope.launch {
            // Graceful only: forceStop deliberately bypasses this guest round-trip.
            runCatching { control?.syncAndWait(SYNC_TIMEOUT_MS) }
            if (generation != vmGeneration || requestSequence != stopSequence || cleanedUp.get()) return@launch
            requestFrameworkStop(vm, generation)
        }
    }

    /** Immediate AVF force path: no guest sync, directly request framework stop. */
    override fun forceStop() {
        stopRequested = true
        ++stopSequence // invalidate any graceful guest-sync waiter immediately
        val vm = vmHandle
        if (vm == null) {
            onVmTerminal(vmGeneration, VmState.Stopped)
            return
        }
        _stopping.value = true
        requestFrameworkStop(vm, vmGeneration)
    }

    private fun requestFrameworkStop(vm: Any, generation: Long) {
        if (generation != vmGeneration || cleanedUp.get()) return
        val requested = runCatching { AvfReflect.stop(vm) }
            .onFailure { Log.w(TAG, "AVF framework stop request rejected; retaining live handle", it) }
            .isSuccess
        if (!requested) {
            if (generation == vmGeneration && !cleanedUp.get()) {
                _stopping.value = false
                // Keep vmHandle and non-quiescent. Serialize this Error against a
                // detector that may already have checked Starting.
                bootStageGate.applyCurrent(generation) {
                    _state.value = VmState.Error("AVF stop request rejected; VM may still be running")
                }
            }
            return
        }
        scope.launch {
            kotlinx.coroutines.delay(FRAMEWORK_STOP_CALLBACK_TIMEOUT_MS)
            if (generation == vmGeneration && !cleanedUp.get()) {
                Log.w(TAG, "AVF stop callback timeout — retaining handle for retry")
                _stopping.value = false
                bootStageGate.applyCurrent(generation) {
                    _state.value = VmState.Error("AVF stop callback timed out; cleanup is incomplete")
                }
            }
        }
    }

    override fun openHostTransport(): com.excp.podroid.engine.hostbridge.HostTransport? {
        val vm = vmHandle ?: return null
        return com.excp.podroid.engine.hostbridge.AvfHostTransport.open(vm)
    }

    override suspend fun addPortForward(rule: com.excp.podroid.data.repository.PortForwardRule) {
        if (_state.value !is VmState.Running) return
        val vm = vmHandle ?: return
        // TCP keeps vport == hostPort (unchanged); UDP is offset so a TCP and a
        // UDP rule on the same host port don't collide on the vsock port space.
        val vport = AvfVport.forRule(rule)
        // Explicit user rules stay LAN-reachable. loopbackOnly is retained for
        // narrowly scoped internal rules, but no display/audio rules are seeded.
        val bindAddr = if (rule.loopbackOnly) "127.0.0.1" else "0.0.0.0"
        try {
            val fw: Forwarder = if (rule.protocol == "udp") {
                VsockUdpForwarder(hostPort = rule.hostPort, guestVsockPort = vport, vm = vm, scope = scope, bindAddress = bindAddr)
            } else {
                VsockPortForwarder(hostPort = rule.hostPort, guestVsockPort = vport, vm = vm, scope = scope, bindAddress = bindAddr)
            }
            // putIfAbsent races safely with a concurrent addPortForward for the
            // same vport — only one forwarder wins; the loser is closed here. fw
            // hasn't been started yet, so close() is a cheap no-op, but it keeps
            // this correct if start() ever moves above putIfAbsent.
            val existing = forwarders.putIfAbsent(vport, fw)
            if (existing != null) {
                Log.d(TAG, "addPortForward($rule) — forwarder already exists for vsock:$vport")
                runCatching { fw.close() }
                return
            }
            // Re-check Running before start() so a concurrent cleanup() doesn't
            // orphan us. If state slipped, drop the freshly-mapped forwarder.
            if (_state.value !is VmState.Running) {
                forwarders.remove(vport)
                return
            }
            fw.start()
            val ctl = control
            if (ctl != null) {
                ctl.addForward(vport, rule.protocol, "127.0.0.1", rule.guestPort)
            } else {
                Log.w(TAG, "addPortForward(${rule.hostPort}/${rule.protocol}): control channel not up yet; " +
                    "guest ADD skipped (host listener is bound)")
            }
            Log.i(TAG, "live forward up: ${rule.hostPort}/${rule.protocol} → vsock:$vport → 127.0.0.1:${rule.guestPort}")
        } catch (c: CancellationException) {
            forwarders.remove(vport)
            throw c
        } catch (e: Throwable) {
            forwarders.remove(vport)
            Log.w(TAG, "addPortForward($rule) failed", e)
            // Rethrow so EngineHolder's diff records this rule as not-applied and
            // retries on the next diff, instead of permanently treating a
            // transient failure as live. The initialRules replay loop guards each
            // call so one failure there doesn't abort the rest.
            throw e
        }
    }

    override suspend fun removePortForward(rule: com.excp.podroid.data.repository.PortForwardRule) {
        val vport = AvfVport.forRule(rule)
        val fw = forwarders.remove(vport) ?: return
        runCatching { fw.close() }
        runCatching { control?.removeForward(vport) }
        Log.i(TAG, "live forward down: 0.0.0.0:${rule.hostPort}/${rule.protocol}")
    }

    /**
     * Coalesce SIGWINCH bursts (keyboard slide produces ~25 layout events) into
     * one RESIZE message. 80 ms matches the bridge's RESIZE_DEBOUNCE_MS constant
     * so the AVF behavior tracks the QEMU bridge's user-visible cadence.
     */
    private fun sendResizeDebounced(rows: Int, cols: Int) {
        if (rows == lastSentRows && cols == lastSentCols) return
        resizeDebounceJob?.cancel()
        resizeDebounceJob = scope.launch {
            kotlinx.coroutines.delay(80)
            val ctl = control ?: return@launch
            // sendResize queues if the connect retry hasn't completed yet —
            // no need to gate on isOpen.
            ctl.sendResize(rows, cols)
            lastSentRows = rows
            lastSentCols = cols
        }
    }

    override fun createTerminalSession(client: TerminalSessionClient): TerminalSession {
        sessionClientDelegate = client
        val generation = synchronized(this) {
            val currentGeneration = activeBootGeneration
                ?: throw IllegalStateException("AVF terminal requires an active VM generation")
            terminalSlot.current()?.takeIf { it.isRunning }?.let {
                Log.d(TAG, "Returning auto-spawned AVF terminal session")
                return it
            }
            terminalSlot.clearDead(currentGeneration) { it.isRunning }?.finishIfRunning()
            currentGeneration
        }
        // start() hasn't reached spawnBridge yet — spawn synchronously as fallback.
        Log.w(TAG, "createTerminalSession called before bridge auto-spawn; spawning now")
        val bridgeExe = File(context.applicationInfo.nativeLibraryDir, "libpodroid-bridge.so")
        if (!bridgeExe.exists()) {
            throw IllegalStateException("podroid-bridge not found at ${bridgeExe.absolutePath}")
        }
        val candidate = newTerminalSession(bridgeExe)
        return registerTerminalBridge(generation, candidate)
            ?: throw IllegalStateException("AVF VM generation ended while creating terminal session")
    }

    private fun newTerminalSession(bridgeExe: File): TerminalSession =
        com.excp.podroid.engine.ResizeNotifyingSession(
            shellPath = bridgeExe.absolutePath,
            cwd = vmPaths.avfWorkingDirectory.absolutePath,
            args = arrayOf(bridgeExe.absolutePath, terminalSockPath, ctrlSockPath),
            env = null,
            transcriptRows = 2000,
            client = proxySessionClient,
            onResize = { rows, cols -> sendResizeDebounced(rows, cols) },
        ).also { it.updateSize(80, 24, 0, 0) }

    @Synchronized
    private fun hasLiveTerminalBridge(generation: Long): Boolean =
        generation == activeBootGeneration && generation == vmGeneration &&
            terminalSlot.current()?.isRunning == true

    @Synchronized
    private fun registerTerminalBridge(generation: Long, candidate: TerminalSession): TerminalSession? {
        terminalSlot.clearDead(generation) { it.isRunning }?.finishIfRunning()
        val registration = terminalSlot.register(
            candidate = candidate,
            candidateGeneration = generation,
            currentGeneration = activeBootGeneration,
            active = generation == vmGeneration &&
                (_state.value is VmState.Starting || _state.value is VmState.Running),
            nonQuiescent = !_quiescent.value && !cleanedUp.get(),
        )
        registration.rejected?.finishIfRunning()
        return registration.selected
    }

    @Synchronized
    private fun retireTerminalBridge() {
        terminalSlot.clear()?.finishIfRunning()
    }

    /**
     * Single synchronized funnel for the VM lifecycle callbacks (onError /
     * onStopped / onDied), which fire on ForkJoinPool threads and can interleave.
     * Drops events from a stale generation, never overwrites a terminal state
     * (Stopped/Error/Idle), suppresses an Error downgrade of a user-initiated
     * Stopped, and runs cleanup() once on the first terminal transition. A
     * successful start/run never calls this (only the detector flips to Running).
     */
    @Synchronized
    private fun onVmTerminal(generation: Long, newState: VmState) {
        when (AvfTerminalPolicy.decide(
            generation, vmGeneration, _state.value, cleanedUp.get(),
        )) {
            AvfTerminalPolicy.Decision.IGNORE -> {
                if (generation != vmGeneration) {
                    Log.d(TAG, "ignoring stale VM callback (gen $generation != current $vmGeneration)")
                }
            }
            AvfTerminalPolicy.Decision.CLEANUP_RETAIN_ERROR -> {
                // Error may represent a rejected/timed-out stop while AVF still
                // owns a live handle. This real callback is authoritative evidence
                // that teardown happened; retain diagnostics but finish cleanup.
                cleanup(terminalState = _state.value)
            }
            AvfTerminalPolicy.Decision.TRANSITION_AND_CLEANUP -> {
                cleanup(terminalState = if (stopRequested) VmState.Stopped else newState)
            }
        }
    }

    @Synchronized
    private fun cleanup(
        terminalState: VmState? = null,
        publishQuiescent: Boolean = true,
    ) {
        // Invalidate atomically against detector/timeout mutation before either
        // console worker is cancelled. Terminal state, when present, is committed
        // in that same critical section so a checked-but-buffered Ready cannot win.
        val generation = activeBootGeneration
        if (generation != null) {
            if (terminalState != null) {
                bootStageGate.invalidateAndApply(generation) { _state.value = terminalState }
            } else {
                bootStageGate.invalidate(generation)
            }
        } else if (terminalState != null) {
            _state.value = terminalState
        }
        activeBootGeneration = null
        if (cleanedUp.getAndSet(true)) {
            if (publishQuiescent && _state.value !is VmState.Starting &&
                _state.value !is VmState.Running) {
                _quiescent.value = true
            }
            return
        }
        _stopping.value = false
        _runningSinceMs = null
        bootTimeoutJob?.cancel()
        bootTimeoutJob = null
        // Tear down forwarders + control before draining the fanout so the
        // VM doesn't see late vsock connect attempts after onStopped.
        forwarders.values.forEach { runCatching { it.close() } }
        forwarders.clear()
        lastSentRows = -1
        lastSentCols = -1
        resizeDebounceJob?.cancel()
        resizeDebounceJob = null
        runCatching { control?.close() }
        control = null
        downloadsShare?.stop()
        downloadsShare = null
        // Close fanout next — drains its coroutines AND closes the console
        // streams (it is their single owner). We hold the same references only
        // to hand them to the fanout; closing them here too would double-close
        // the ParcelFileDescriptor-backed streams from another thread (FD-reuse
        // hazard). Only if the fanout was never constructed (an early start()
        // failure between obtaining the streams and building the fanout) do we
        // close them directly so they don't leak.
        val fo = fanout
        if (fo != null) {
            runCatching { fo.close() }
        } else {
            runCatching { consoleStreamInput?.close() }
            runCatching { consoleStream?.close() }
        }
        fanout = null
        consoleStreamInput = null
        consoleStream = null
        // Flush + close console.log; the tail stays on disk for the next
        // diagnostic export. consoleText flow keeps its last value (UI reads
        // it cached); start() clears both on the next run.
        runCatching {
            logOut?.flush()
            logOut?.close()
        }
        logOut = null
        // Detach under the cleanup monitor before publishing quiescence. A
        // delayed bridge candidate can no longer register for this generation.
        runCatching { terminalSlot.clear()?.finishIfRunning() }
        vmHandle = null
        // Last cleanup write. Callers establish a terminal state before public
        // quiescence; internal adaptive relaunches deliberately retain Starting.
        if (publishQuiescent) {
            check(_state.value !is VmState.Starting && _state.value !is VmState.Running) {
                "AVF cleanup cannot publish quiescence from an active lifecycle state"
            }
            _quiescent.value = true
        }
    }

    /**
     * Creates (or resizes) the persistent storage disk. Mirrors QemuEngine's
     * helper exactly so a VM can boot under either backend with the same
     * disk identity. The file is sparse — the actual disk allocation grows
     * as the guest writes blocks. Alpine's init formats it ext4 on first
     * boot via the standard initramfs path.
     */
    private fun ensureStorageImage(storageSizeGb: Int): File {
        val storageFile = vmPaths.storageImage
        val desiredBytes = storageSizeGb.toLong() * 1024L * 1024L * 1024L
        if (storageFile.exists()) {
            val current = storageFile.length()
            when {
                current == desiredBytes -> return storageFile
                // NEVER delete: storage.img is the persistent ext4 overlay. Grow
                // in place when larger (guest resize2fs claims it on next boot);
                // keep as-is if smaller (shrinking would corrupt the filesystem).
                // Deleting on mismatch could wipe all guest data. See QemuEngine.
                desiredBytes > current -> {
                    runCatching {
                        java.io.RandomAccessFile(storageFile, "rw").use { it.setLength(desiredBytes) }
                    }.onSuccess {
                        Log.i(TAG, "storage.img grown ${current / (1024 * 1024)}MB → ${storageSizeGb}GB (guest resize2fs on next boot)")
                    }.onFailure { Log.e(TAG, "Failed to grow storage.img", it) }
                    return storageFile
                }
                else -> {
                    Log.w(TAG, "storage.img (${current / (1024 * 1024)}MB) larger than requested ${storageSizeGb}GB; keeping existing image (shrink would corrupt the filesystem)")
                    return storageFile
                }
            }
        }
        try {
            java.io.RandomAccessFile(storageFile, "rw").use { it.setLength(desiredBytes) }
            Log.d(TAG, "Created storage.img (${storageSizeGb}GB)")
        } catch (e: Exception) {
            // Don't swallow + boot a 0-byte image into an opaque early-boot stop.
            // Rethrow so start()'s catch surfaces a clear storage error.
            Log.e(TAG, "Failed to create storage.img", e)
            runCatching { storageFile.delete() }
            throw java.io.IOException("Couldn't create the ${storageSizeGb} GB VM disk image: ${e.message}", e)
        }
        return storageFile
    }

    override fun diagnosticsReport(): String = buildString {
        appendLine("persisted console capture: $persistedConsoleCaptureEnabled")
        appendLine("backend launch config: $launchConfigSummary")
        appendLine("last VM lifecycle callback: $lastLifecycleEvent")
    }

    private suspend fun buildConfig(
        mgr: Any,
        config: VmConfig,
        pathSecurity: VmPathSecurity,
    ): Any {
        // Resolve the effective vCPU count: the user's request, clamped by any
        // per-device cap discovered by a prior early-boot reset (issue #29).
        // attemptedCpus feeds the onStopped fallback ladder.
        val cpuCap = runCatching { settingsRepository.getAvfCpuCapSnapshot() }
            .getOrDefault(AvfCpuPolicy.NO_CAP)
        val effectiveCpus = AvfCpuPolicy.effectiveCpus(config.cpus, cpuCap)
        attemptedCpus = effectiveCpus
        // Multi-core topology mode (issue #29). Preferred: an exact homogeneous
        // vCPU count via the Android 16+ raw AIDL (no heterogeneous host
        // topology cloned into the guest — the confirmed reset trigger on
        // Tensor G3/G4). Fallback: MATCH_HOST bounded by nr_cpus on the kernel
        // cmdline. At 1 core the builder's ONE_CPU already means a clean
        // single-vCPU topology, so neither mechanism is needed.
        useExplicitCpuCount = effectiveCpus > 1 && AvfReflect.supportsExplicitCpuCount()
        // Clear any explicit-count arming left on the process-shared binder proxy
        // from a prior launch. launchAttempt re-arms via installExplicitCpuCount
        // when useExplicitCpuCount is true; on every other path (ONE_CPU, the
        // nr_cpus fallback tier, the ladder's 1-core rescue) a stale count must
        // NOT rewrite this launch's topology back up (issue #29).
        if (!useExplicitCpuCount) AvfReflect.disarmExplicitCpuCount()

        config.bootArtifacts?.validateFiles()
        val bootFiles = avfBootFiles(config, vmPaths)
        val kernelSrc = bootFiles.kernel.also {
            require(it.exists()) { "kernel missing at ${it.absolutePath}" }
        }
        val kernelDigest = bootFiles.kernelDigest ?: VmBootArtifacts.digest(kernelSrc)
        val kernel = AvfRawKernelCache.prepare(
            source = kernelSrc,
            sourceDigest = kernelDigest,
            raw = vmPaths.rawKernel,
            stamp = vmPaths.rawKernelDigestStamp,
            pathSecurity = pathSecurity,
        )
        val initrd = bootFiles.initrd.also {
            require(it.exists()) { "initrd missing at ${it.absolutePath}" }
        }
        val storage = ensureStorageImage(config.storageSizeGb)
        val squashfs = bootFiles.rootfs.also {
            require(it.exists()) { "rootfs missing at ${it.absolutePath}" }
        }

        val cb = AvfReflect.newCustomBuilder()
        AvfReflect.setName(cb, VM_NAME)
        AvfReflect.setKernelPath(cb, kernel.absolutePath)
        AvfReflect.setInitrdPath(cb, initrd.absolutePath)
        // Console wiring: hvc0 (virtio-console), NOT ttyS0 (PL011 UART).
        // crosvm's PL011 is rate-limited to ~14 KB/s — fine for boot logs,
        // catastrophic for TUI redraws (a btop frame is 8–16 KB, so each
        // refresh takes ~700 ms–1 s on ttyS0). virtio-console runs at host
        // memory speed. setConsoleInputDevice(hvc0) tells AVF to wire its
        // captured input/output streams to hvc0; `console=hvc0` in the
        // kernel cmdline routes kernel logs the same way; the guest's
        // /etc/inittab spawns the getty on the device named by the
        // `podroid.tty=` marker.
        //
        // `podroid.backend=avf` is a stable backend identifier — used by
        // guest OpenRC scripts (podroid-network, podroid-vsock) to pick
        // AVF-specific behaviour without coupling to the tty choice.
        //
        // `podroid.epoch=...` seeds the wall clock — AVF/crosvm doesn't
        // wire an RTC the way QEMU TCG does, so without this the guest
        // boots at 1970-01-01 and TLS fails on every cert.
        val epoch = System.currentTimeMillis() / 1000
        // earlycon captures kernel output BEFORE hvc0 is up. crosvm folds the
        // 8250 serial into the same stream getConsoleOutput() reads, so a guest
        // that reboots in early boot (e.g. the Tensor G3 MATCH_HOST crash, #29)
        // finally leaves a panic instead of an empty console.log. keep_bootcon
        // keeps it printing after the real console registers.
        val earlycon = "earlycon keep_bootcon"
        // ignore_loglevel makes earlycon print the full early boot (incl. an
        // early-boot panic like the #29 MATCH_HOST reset) into console.log. The
        // default extra cmdline is `loglevel=1 quiet`, which suppresses it, so
        // gate this behind the verbose-logging toggle — on for reporters who need
        // a capture, off for everyone else.
        val verboseFlags = if (config.verboseLogging) " ignore_loglevel" else ""
        // Fallback tier only (devices without the explicit-count AIDL): bound
        // the cores the GUEST KERNEL brings up under MATCH_HOST. The full
        // heterogeneous topology is still emitted; nr_cpus just limits how many
        // vCPUs the kernel onlines (#29). On the explicit-count path the guest
        // gets a clean N-vCPU topology and must online all of them. See
        // AvfCpuPolicy.
        val nrCpusFlag =
            if (effectiveCpus > 1 && !useExplicitCpuCount) " nr_cpus=$effectiveCpus" else ""
        val resolvedCmdline = ("console=hvc0 $earlycon root=/dev/ram0 mitigations=off " +
            "elevator=mq-deadline podroid.tty=hvc0 podroid.backend=avf podroid.epoch=$epoch " +
            "podroid.x11.dpi=${config.x11Dpi}$verboseFlags$nrCpusFlag " +
            (if (config.bandwidthMbps > 0) "podroid.bandwidth=${config.bandwidthMbps} " else "") +
            config.kernelExtraCmdline).trim()
        AvfReflect.addParams(cb, resolvedCmdline)
        if (config.verboseLogging) {
            // The resolved value includes user kernel extras and must never be
            // copied to logcat. Retain only a clearly redacted size signal.
            Log.i(TAG, "verbose: backend=avf kernelCmdline=[redacted] charCount=${resolvedCmdline.length}")
            Log.i(TAG, "verbose: ramMb=${config.ramMb} cpus=$effectiveCpus (requested ${config.cpus}) " +
                "storageAccess=${config.storageAccessEnabled}")
        }
        AvfReflect.addDisk(cb, storage.absolutePath, writable = true)
        AvfReflect.addDisk(cb, squashfs.absolutePath, writable = false)
        // The actual share is a post-boot AvfDownloadsShare connecting over vsock
        // (see bringUpControlChannel()); the real outcome ("added"/"unavailable")
        // isn't known until then, so this summary just reflects the setting here.
        val shareSummary = if (config.storageAccessEnabled) "pending" else "off"
        AvfReflect.setNetworkSupported(cb, true)
        // Declaring a (headless, display-less) GPU forces virtmgr onto the full
        // `crosvm` instead of `crosvm_minimal`, which on Pixel's APEX lacks the
        // `net` feature and aborts a networked VM at `--net`. See setGpuConfig.
        val gpuAttached = AvfReflect.setGpuConfig(cb)
        Log.i(TAG, "avf: headless GPU attached=$gpuAttached (forces net-capable crosvm)")
        val customCfg = AvfReflect.build(cb)

        val vb = AvfReflect.newVmConfigBuilder(context)
        val protectedStr = when (val choice = AvfReflect.applyProtectedVm(mgr, vb)) {
            is AvfCapabilities.ProtectedVmChoice.Unsupported ->
                throw UnsupportedOperationException(choice.reason)
            is AvfCapabilities.ProtectedVmChoice.NonProtected -> {
                Log.i(TAG, "protectedVm=false (device supports non-protected VMs)")
                "false (non-protected supported)"
            }
            is AvfCapabilities.ProtectedVmChoice.Unknown -> {
                Log.w(TAG, "getCapabilities() unavailable; attempted protectedVm=false")
                "false (capabilities unknown)"
            }
        }
        AvfReflect.setMemoryBytes(vb, config.ramMb.toLong() * 1024 * 1024)
        AvfReflect.setNumCpus(vb, effectiveCpus)
        AvfReflect.setDebugLevel(vb, AvfReflect.DEBUG_LEVEL_FULL)
        AvfReflect.setConsoleInputDevice(vb, "hvc0")         // virtio-console (line-rate), NOT PL011
        AvfReflect.setConnectVmConsole(vb, false)            // false: avoid inheriting Activity FDs (SurfaceFlinger sync fences → SELinux denial)
        AvfReflect.setVmOutputCaptured(vb, true)
        AvfReflect.setVmConsoleInputSupported(vb, true)
        AvfReflect.setCustomImageConfig(vb, customCfg)
        val capStr = if (cpuCap > AvfCpuPolicy.NO_CAP) ", cpuCap=$cpuCap" else ""
        val cpuMode = when {
            effectiveCpus <= 1 -> "one-cpu"
            useExplicitCpuCount -> "explicit-count"
            else -> "match-host+nr_cpus"
        }
        launchConfigSummary = "vCPUs=$effectiveCpus ($cpuMode, requested ${config.cpus}$capStr), " +
            "memory=${config.ramMb}MB, console=hvc0, " +
            "protectedVm=$protectedStr, downloadsShare=$shareSummary, verboseLogging=${config.verboseLogging}"
        return AvfReflect.build(vb)
    }
}
