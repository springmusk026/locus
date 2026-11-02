package dev.locus.feature.workspace.linux

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.locus.core.data.linux.LinuxFacts
import dev.locus.core.data.linux.LinuxPackage
import dev.locus.core.data.linux.LinuxPanel
import dev.locus.core.data.linux.LinuxSetup
import dev.locus.core.data.linux.PackageState
import dev.locus.core.data.linux.linuxPanel
import dev.locus.core.linux.LinuxEnvironment
import dev.locus.core.linux.LinuxStatus
import dev.locus.core.terminal.TerminalSessions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The Workspace's Linux card: [panel] is the one state it shows, [packages] each offered package's
 * state, [selected] the ones ticked to install, [error] the last operation's failure (shown in the
 * card until the next one), and [message] a one-off outcome for the feedback channel.
 */
data class LinuxUiState(
    val panel: LinuxPanel,
    val packages: Map<LinuxPackage, PackageState> = emptyMap(),
    val selected: Set<LinuxPackage> = emptySet(),
    val error: String? = null,
    val message: String? = null,
)

/**
 * Drives the Linux card in the Workspace (docs/design/linux-setup.md). Installing, repairing and
 * removing go through [LinuxSetup], the same use case onboarding uses; this adds the facts the card
 * shows (version, disk use, services, Android's kills) and the package selection.
 */
@HiltViewModel
class LinuxViewModel(
    private val setup: LinuxSetup,
    private val linux: LinuxEnvironment,
    /** Closes every terminal tab, before Linux is removed or reinstalled from under them. */
    private val closeTerminals: () -> Unit,
) : ViewModel() {

    @Inject constructor(setup: LinuxSetup, linux: LinuxEnvironment, terminals: TerminalSessions) :
        this(setup, linux, { terminals.closeAll() })

    private val selected = MutableStateFlow(DEFAULT_PACKAGES)
    private val facts = MutableStateFlow(LinuxFacts(version = linux.version))

    /** Services, read every few seconds while the card is on screen. */
    private val services = flow {
        while (true) {
            emit(if (linux.status.value == LinuxStatus.Ready) linux.serviceStatuses() else emptyList())
            delay(SERVICE_POLL_MS)
        }
    }.flowOn(Dispatchers.IO).distinctUntilChanged()

    val uiState: StateFlow<LinuxUiState> = combine(
        combine(linux.status, setup.state, selected, ::Triple),
        combine(facts, services, linux.androidKill, ::Triple),
    ) { (status, run, picked), (known, running, killed) ->
        val installed = run.installedPackages
        LinuxUiState(
            panel = linuxPanel(status, run, known.copy(services = running, killed = killed), picked),
            packages = run.packages,
            selected = picked - installed,
            error = run.error.takeIf { status == LinuxStatus.Ready && !run.running },
            message = run.message,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        LinuxUiState(linuxPanel(linux.status.value, setup.state.value, facts.value, selected.value), selected = selected.value),
    )

    init {
        // Once Linux is ready and idle (installed, repaired, or an operation finished): how much
        // space it takes, and which packages are there. Installed packages are no longer "to add".
        viewModelScope.launch {
            combine(linux.status, setup.state.map { it.running }) { status, running -> status == LinuxStatus.Ready && !running }
                .distinctUntilChanged()
                .collect { ready ->
                    if (ready) {
                        facts.update { it.copy(diskBytes = runCatching { linux.diskUsage() }.getOrNull()) }
                        setup.refreshPackages()
                        selected.value = emptySet()
                    } else if (linux.status.value != LinuxStatus.Ready && !setup.state.value.running) {
                        facts.update { it.copy(diskBytes = null) }
                        selected.value = DEFAULT_PACKAGES
                    }
                }
        }
    }

    fun toggle(p: LinuxPackage) {
        if (setup.state.value.packages[p] == PackageState.Installed || setup.state.value.running) return
        selected.update { if (p in it) it - p else it + p }
    }

    /** Installs Linux (when missing) and the ticked packages; also Try again after a failure. */
    fun install() = setup.install(selected.value - setup.state.value.installedPackages)

    fun cancel() = setup.cancel()

    fun repair() = setup.repair()

    /** Removes everything and installs it again, with the packages that were installed. */
    fun reinstall() {
        closeTerminals()
        setup.install(setup.state.value.installedPackages, reinstall = true)
    }

    fun remove() {
        closeTerminals()
        setup.remove()
    }

    fun stopService(name: String) {
        viewModelScope.launch { linux.stopService(name) }
    }

    fun dismissKill() = linux.dismissAndroidKill()

    fun messageShown() = setup.outcomeShown()

    companion object {
        val DEFAULT_PACKAGES = setOf(LinuxPackage.Python, LinuxPackage.Git)
        private const val SERVICE_POLL_MS = 5_000L
    }
}
