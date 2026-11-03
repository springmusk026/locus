package dev.locus.feature.workspace.linux.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.locus.core.data.linux.LinuxOperation
import dev.locus.core.data.linux.LinuxSetup
import dev.locus.core.data.linux.LinuxSetupState
import dev.locus.core.linux.HealthCheck
import dev.locus.core.linux.LinuxEnvironment
import dev.locus.core.model.runCatchingCancellable
import dev.locus.core.linux.LinuxHealth
import dev.locus.core.linux.LinuxProbe
import dev.locus.core.linux.LinuxReport
import dev.locus.core.linux.LinuxStatus
import dev.locus.core.terminal.TerminalSessions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the health screen loads; replaced in tests. */
interface LinuxHealthSource {
    suspend fun report(): LinuxReport?
    suspend fun network(): LinuxReport.Network
    suspend fun cleanCaches(): Long
}

/**
 * The probe's results and what's being done: [report] is null until the first load ends; [busy]
 * names the action running (one at a time, so a second tap can't start another); [message] is a
 * one-off outcome for [dev.locus.core.designsystem.Locus.feedback].
 */
data class LinuxHealthUiState(
    val status: LinuxStatus,
    val report: LinuxReport? = null,
    val checks: List<HealthCheck> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null,
    val checkingNetwork: Boolean = false,
    val busy: Busy? = null,
    val repairing: Boolean = false,
    val message: Message? = null,
) {
    enum class Busy { Clean, Dns, Restart }

    data class Message(val text: String, val problem: Boolean = false)
}

/**
 * The Linux health screen (docs/design/linux-health.md): loads [LinuxProbe]'s report when Linux is
 * ready, and runs the checks' fixes. Repair and Reinstall go through [LinuxSetup], the use case the
 * Workspace's Linux card uses, so both screens show the same operation.
 */
@HiltViewModel
class LinuxHealthViewModel(
    private val linux: LinuxEnvironment,
    private val setup: LinuxSetup,
    private val source: LinuxHealthSource,
    private val closeTerminals: () -> Unit,
) : ViewModel() {

    @Inject constructor(linux: LinuxEnvironment, setup: LinuxSetup, terminals: TerminalSessions) : this(
        linux,
        setup,
        object : LinuxHealthSource {
            private val probe by lazy { LinuxProbe(linux) }
            override suspend fun report() = probe.report()
            override suspend fun network() = probe.network()
            override suspend fun cleanCaches() = probe.cleanCaches()
        },
        { terminals.closeAll() },
    )

    private val local = MutableStateFlow(LinuxHealthUiState(linux.status.value))
    private var load: Job? = null

    val uiState: StateFlow<LinuxHealthUiState> = combine(local, linux.status, setup.state) { s, status, run ->
        s.copy(status = status, repairing = run.running && run.operation == LinuxOperation.Repair)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    init {
        // Load when Linux becomes ready (now, or after an install or reinstall finishes); forget the report when it goes.
        viewModelScope.launch {
            linux.status.map { it == LinuxStatus.Ready }.distinctUntilChanged().collect { ready ->
                if (ready) refresh(initial = local.value.report == null) else {
                    load?.cancel()
                    local.update { it.copy(report = null, checks = emptyList(), loading = false, refreshing = false, error = null) }
                }
            }
        }
        // Repair and reinstall run in LinuxSetup: when one ends, say how it went and look again.
        viewModelScope.launch {
            setup.state.map { it.running to it.operation }.distinctUntilChanged().drop(1).collect { (running, operation) ->
                if (!running && (operation == LinuxOperation.Repair || operation == LinuxOperation.Reinstall)) outcome(setup.state.value)
            }
        }
    }

    private fun outcome(run: LinuxSetupState) {
        val text = run.error?.let { "That didn't work: $it" } ?: run.message ?: return
        local.update { it.copy(message = LinuxHealthUiState.Message(text, problem = run.error != null)) }
        setup.outcomeShown()
        if (linux.status.value == LinuxStatus.Ready) refresh()
    }

    /** Reads everything again (pull to refresh); keeps the last network result, which only changes on request. */
    fun refresh(initial: Boolean = false) {
        if (linux.status.value != LinuxStatus.Ready) return
        load?.cancel()
        local.update { it.copy(loading = initial || it.report == null, refreshing = !initial && it.report != null, error = null) }
        load = viewModelScope.launch {
            try {
                val fresh = source.report()
                local.update { s ->
                    val merged = fresh?.copy(network = s.report?.network)
                    s.copy(report = merged, checks = merged?.let(LinuxHealth::checks).orEmpty(), loading = false, refreshing = false, error = if (merged == null) NOT_READABLE else null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                local.update { it.copy(loading = false, refreshing = false, error = e.message ?: e.toString()) }
            }
        }
    }

    /** DNS and HTTPS from inside Linux (reaches the internet, so only when the user asks). */
    fun checkNetwork() {
        if (local.value.checkingNetwork || local.value.report == null) return
        local.update { it.copy(checkingNetwork = true) }
        viewModelScope.launch {
            val result = runCatchingCancellable { source.network() }
            local.update { s ->
                val report = result.getOrNull()?.let { n -> s.report?.copy(network = n) } ?: s.report
                s.copy(
                    checkingNetwork = false,
                    report = report,
                    checks = report?.let(LinuxHealth::checks).orEmpty(),
                    message = result.exceptionOrNull()?.let { LinuxHealthUiState.Message("Couldn't check the network: ${it.message ?: it}", problem = true) } ?: s.message,
                )
            }
        }
    }

    /** Empties the package and download caches, then measures again. */
    fun cleanCaches() = act(LinuxHealthUiState.Busy.Clean) {
        val freed = source.cleanCaches()
        "Freed ${LinuxHealth.bytes(freed)}."
    }

    /** Puts Linux's DNS servers back, then checks the network again if it was checked. */
    fun resetDns() = act(LinuxHealthUiState.Busy.Dns) {
        linux.resetDns()
        if (local.value.report?.network != null) {
            val n = runCatchingCancellable { source.network() }.getOrNull()
            if (n != null) local.update { s -> s.copy(report = s.report?.copy(network = n)) }
        }
        "DNS servers reset to 1.1.1.1 and 8.8.8.8."
    }

    fun restartService(name: String) = act(LinuxHealthUiState.Busy.Restart) {
        val service = linux.restartService(name) ?: error("There's no service “$name” any more.")
        // A server that fails on start (a port in use, a missing file) usually exits within a second.
        kotlinx.coroutines.delay(RESTART_SETTLE_MS)
        if (linux.isAlive(service)) "Started “$name”." else error("“$name” exited right away. Its log may say why.")
    }

    fun stopService(name: String) = act(LinuxHealthUiState.Busy.Restart) {
        if (linux.stopService(name)) "Stopped “$name”." else "“$name” had already stopped."
    }

    fun repair() = setup.repair()

    /** Alpine from scratch with Locus's offered tools put back; terminals close first, as from the card. */
    fun reinstall() {
        closeTerminals()
        setup.install(setup.state.value.installedPackages, reinstall = true)
    }

    fun messageShown() = local.update { it.copy(message = null) }

    private fun act(kind: LinuxHealthUiState.Busy, block: suspend () -> String) {
        if (local.value.busy != null) return
        local.update { it.copy(busy = kind) }
        viewModelScope.launch {
            val result = try {
                LinuxHealthUiState.Message(block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LinuxHealthUiState.Message(e.message ?: e.toString(), problem = true)
            }
            local.update { it.copy(busy = null, message = result) }
            refresh()
        }
    }

    private companion object {
        const val RESTART_SETTLE_MS = 1_500L
        const val NOT_READABLE = "Linux's state couldn't be read. It may have just been removed or be reinstalling."
    }
}
