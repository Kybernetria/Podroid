package com.excp.podroid.ui.screens.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.excp.podroid.BuildConfig
import com.excp.podroid.data.repository.ContainerStatsRepository
import com.excp.podroid.data.repository.PortForwardRepository
import com.excp.podroid.data.repository.SettingsRepository
import com.excp.podroid.data.repository.UpdateInfo
import com.excp.podroid.data.repository.UpdateRepository
import com.excp.podroid.service.VmServiceClient
import com.excp.podroid.service.VmUiState
import com.excp.podroid.util.NetworkUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Aggregated Home metadata used by the data sections (resources, network,
 * last-session). `Resources` is shown in the meta row in every state; the
 * Network/LastSession sections render conditionally based on vmState.
 */
data class HomeMeta(
    val ramMb: Int,
    val cpus: Int,
    val storageGb: Int,
    val sshEnabled: Boolean,
    val portForwardCount: Int,
    val lastBootDurationMs: Long,
) {
    val resourcesLabel: String = "${formatRam(ramMb)} · $cpus CPU · ${storageGb} GB"

    private fun formatRam(mb: Int): String =
        if (mb >= 1024) "${mb / 1024} GB" else "$mb MB"
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vmServiceClient: VmServiceClient,
    private val settingsRepository: SettingsRepository,
    private val portForwardRepository: PortForwardRepository,
    private val containerStatsRepository: ContainerStatsRepository,
    private val updateRepository: UpdateRepository,
) : ViewModel() {

    val vmState: StateFlow<VmUiState> = vmServiceClient.vmState

    /** True while a stop is tearing the VM down (Running/Starting -> Stopped). */
    val stopping: StateFlow<Boolean> = vmServiceClient.observation
        .map { it.stopping }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val bootStage: StateFlow<String> = vmServiceClient.observation
        .map { it.bootStage }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private val _containerCount = MutableStateFlow<Int?>(null)
    val containerCount: StateFlow<Int?> = _containerCount.asStateFlow()

    /** Aggregated metadata for the Home data sections. */
    val meta: StateFlow<HomeMeta> = combine(
        settingsRepository.vmRamMb,
        settingsRepository.vmCpus,
        settingsRepository.storageSizeGb,
        settingsRepository.sshEnabled,
        portForwardRepository.rules.map { it.size }.distinctUntilChanged(),
        settingsRepository.lastBootDurationMs,
    ) { values ->
        HomeMeta(
            ramMb = values[0] as Int,
            cpus = values[1] as Int,
            storageGb = values[2] as Int,
            sshEnabled = values[3] as Boolean,
            portForwardCount = values[4] as Int,
            lastBootDurationMs = values[5] as Long,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        HomeMeta(ramMb = 512, cpus = 2, storageGb = 8, sshEnabled = false, portForwardCount = 0, lastBootDurationMs = 0L),
    )

    // Ticker — drives uptime display refresh every second, but only while the VM
    // is Running. Gated via flatMapLatest on vmState so no ticks (and no HomeScreen
    // recompositions) happen while the VM is Idle/Starting/Stopped.
    val uptimeTicker: StateFlow<Long> = vmState
        .map { it is VmUiState.Running }
        .distinctUntilChanged()
        .flatMapLatest { isRunning ->
            if (isRunning) flow {
                while (true) {
                    emit(System.currentTimeMillis() / 1000)
                    delay(1000)
                }
            } else flowOf(0L)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    /** Phone IPv4 — cheap, lazily recomputed when the screen reads it. */
    fun phoneIp(): String = NetworkUtils.localIpv4(context)

    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateInfo: StateFlow<UpdateInfo?> = _updateInfo.asStateFlow()

    /**
     * True when AVF (pKVM) is present on this device but neither
     * MANAGE_VIRTUAL_MACHINE nor USE_CUSTOM_VIRTUAL_MACHINE have been granted
     * yet — and the user hasn't dismissed the banner.
     *
     * The AVF probe is fast (no IPC, no binder), so we derive it via a
     * simple map on the dismissed flow rather than a heavy combine.
     */
    private val _avfHintAvailable = MutableStateFlow(false)
    val showAvfHint: StateFlow<Boolean> = combine(
        settingsRepository.avfHintDismissed,
        _avfHintAvailable,
    ) { dismissed, available -> available && !dismissed }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** True when AVF was the active backend and the VM ended in Error
     *  (boot failure / crash). Drives the actionable failure surface. */
    val avfBootFailure: StateFlow<Boolean> = combine(vmState, vmServiceClient.observation) { state, observation ->
        state is VmUiState.Error && observation.backendId == "avf"
    }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Advice for the failure surface, based on the current vCPU setting. */
    val avfFailureAdvice: StateFlow<com.excp.podroid.vm.VmFailureAdvice> =
        settingsRepository.vmCpus
            .map { com.excp.podroid.vm.vmFailureAdvice(it) }
            .stateIn(
                viewModelScope, SharingStarted.Eagerly,
                com.excp.podroid.vm.VmFailureAdvice.SWITCH_TO_QEMU,
            )

    fun useOneCoreAndRetry() {
        viewModelScope.launch {
            settingsRepository.setVmCpus(1)
            restartVm()
        }
    }

    fun switchToQemuAndRetry() {
        viewModelScope.launch {
            settingsRepository.setEngineSelection(com.excp.podroid.vm.EngineSelection.QEMU)
            restartVm()
        }
    }

    // Fallback timestamp stamped the first time we observe Running, used when the
    // engine doesn't supply runningSinceMs (e.g. a future engine that omits the override).
    private var fallbackRunningSinceMs: Long? = null

    // The uptime baseline: prefer the real →Running timestamp from the engine so
    // rotation / Activity recreation doesn't reset the displayed uptime to "Up 0s"
    // while the VM has actually been running for minutes.
    private val runningSinceMs: Long?
        get() = vmServiceClient.observation.value.runningSinceMs ?: fallbackRunningSinceMs

    init {
        checkForUpdate()
        viewModelScope.launch {
            runCatching { vmServiceClient.backendProbe() }.getOrNull()?.let { probe ->
                _avfHintAvailable.value = probe.featureSupported && !probe.managePermissionGranted
            }
        }
        viewModelScope.launch {
            val cached = settingsRepository.getLastContainerCount()
            if (cached != null) _containerCount.value = cached
        }
        viewModelScope.launch {
            var lastRunning = false
            vmState.collect { state ->
                val running = state is VmUiState.Running
                if (running && !lastRunning) refreshContainerCount()
                lastRunning = running
            }
        }
        viewModelScope.launch {
            while (true) {
                if (vmState.value is VmUiState.Running) refreshContainerCount()
                delay(5_000)
            }
        }
        // Maintain fallbackRunningSinceMs for engines that don't override runningSinceMs.
        viewModelScope.launch {
            var lastWasRunning = false
            vmState.collect { state ->
                val nowRunning = state is VmUiState.Running
                if (nowRunning && !lastWasRunning) {
                    // Only stamp the fallback when the engine doesn't provide the real time.
                    if (vmServiceClient.observation.value.runningSinceMs == null) {
                        fallbackRunningSinceMs = System.currentTimeMillis()
                    }
                }
                if (!nowRunning) fallbackRunningSinceMs = null
                lastWasRunning = nowRunning
            }
        }
    }

    /** Format "Up Xm Ys" / "Up Xh Ym" from runningSinceMs. */
    fun uptimeLabel(@Suppress("UNUSED_PARAMETER") tickerTrigger: Long): String? {
        val since = runningSinceMs ?: return null
        val totalSec = ((System.currentTimeMillis() - since) / 1000).coerceAtLeast(0)
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        val seconds = totalSec % 60
        return when {
            hours > 0   -> "Up ${hours}h ${minutes}m"
            minutes > 0 -> "Up ${minutes}m ${seconds}s"
            else        -> "Up ${seconds}s"
        }
    }

    private fun checkForUpdate() {
        viewModelScope.launch {
            try {
                val info = updateRepository.checkForUpdate(BuildConfig.VERSION_NAME) ?: return@launch
                if (!updateRepository.isDismissed(info.latestVersion)) {
                    _updateInfo.value = info
                }
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (e: Exception) {
                android.util.Log.w("HomeViewModel", "update check failed", e)
            }
        }
    }

    fun dismissUpdate() {
        val version = _updateInfo.value?.latestVersion ?: return
        _updateInfo.value = null
        viewModelScope.launch { updateRepository.dismissUpdate(version) }
    }

    fun dismissAvfHint() {
        viewModelScope.launch { settingsRepository.setAvfHintDismissed(true) }
    }

    fun refreshContainerCount() {
        viewModelScope.launch {
            _containerCount.value = containerStatsRepository.readContainerCount()
        }
    }

    fun startPodroid() = launchVmCommand("start") { vmServiceClient.start() }

    fun stopVm() = launchVmCommand("stop") { vmServiceClient.gracefulStop() }

    fun restartVm() = launchVmCommand("restart") { vmServiceClient.restart() }

    private fun launchVmCommand(name: String, command: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { command() }
                .onFailure { android.util.Log.e("HomeViewModel", "VM $name command failed", it) }
        }
    }
}
