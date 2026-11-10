package dev.locus.core.data.linux

import android.content.Context
import androidx.annotation.StringRes
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.locus.core.data.R
import dev.locus.core.data.di.ApplicationScope
import dev.locus.core.linux.CommandResult
import dev.locus.core.linux.EngineText
import dev.locus.core.linux.LinuxEnvironment
import dev.locus.core.linux.LinuxStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Linux packages Locus offers to add (Alpine v3.24 package names, checked on pkgs.alpinelinux.org).
 * [downloadMb] and [diskMb] are rough sizes with their main dependencies, for the install estimate:
 * the package pages list python3 7.9 MB (25 MB installed) and py3-pip 1.6 MB (5.4 MB), git 3.4 MB
 * (7 MB), nodejs 17.1 MB (46.7 MB) and npm 2.2 MB (7.5 MB), curl 0.2 MB (0.3 MB), each before
 * dependencies (aarch64, 2026-11-02).
 */
/** An offered tool: [label] is its name (not translated), [noteRes] what it is, in the app's language. */
enum class LinuxPackage(val label: String, @param:StringRes val noteRes: Int, val apk: List<String>, val downloadMb: Int, val diskMb: Int) {
    Python("Python", R.string.data_linux_package_python_note, listOf("python3", "py3-pip"), downloadMb = 11, diskMb = 35),
    Git("Git", R.string.data_linux_package_git_note, listOf("git"), downloadMb = 5, diskMb = 10),
    Node("Node.js", R.string.data_linux_package_node_note, listOf("nodejs", "npm"), downloadMb = 22, diskMb = 65),
    Curl("curl", R.string.data_linux_package_curl_note, listOf("curl"), downloadMb = 1, diskMb = 1),
}

/** How one offered package stands: unknown, already there, waiting, being installed, or failed. */
sealed interface PackageState {
    data object Unknown : PackageState
    data object Installed : PackageState
    data object Queued : PackageState
    data object Installing : PackageState
    /** Why, then apk's last lines of output when there are any. */
    data class Failed(val detail: EngineText) : PackageState
}

/** What [LinuxSetup] is doing. Install and Reinstall download, so they run as a foreground job. */
enum class LinuxOperation { Install, Reinstall, Repair, Remove }

/**
 * Linux setup as onboarding and the Workspace's Linux card show it: whether an [operation] is
 * [running], its current [phase] and [progress] (0..1, null when unknown), each package's state, and
 * the last outcome ([message] on success, [error] on failure). [cancelled] is set when the user
 * cancelled the last install. The texts are only shown, never given to the model.
 */
data class LinuxSetupState(
    val running: Boolean = false,
    val operation: LinuxOperation? = null,
    val phase: EngineText? = null,
    val progress: Float? = null,
    val packages: Map<LinuxPackage, PackageState> = emptyMap(),
    val message: EngineText? = null,
    val error: EngineText? = null,
    val cancelled: Boolean = false,
) {
    val installedPackages: Set<LinuxPackage> get() = packages.filterValues { it == PackageState.Installed }.keys

    /** An install or reinstall is running, which [LinuxSetup.cancel] can stop. */
    val cancellable: Boolean get() = running && (operation == LinuxOperation.Install || operation == LinuxOperation.Reinstall)
}

/** An install run: [packages] to add (base Linux comes first when missing), after removing everything when [reinstall]. */
data class LinuxSetupRequest(val packages: List<LinuxPackage>, val reinstall: Boolean = false)

/** What [LinuxSetup] needs from Linux, so the state machine can be tested without a phone. */
interface LinuxOps {
    val status: StateFlow<LinuxStatus>
    suspend fun ensureInstalled()
    suspend fun run(command: String, timeoutMs: Long, maxChars: Int): CommandResult
    suspend fun uninstall()
    suspend fun repair(): String
}

/** [LinuxOps] on the real [LinuxEnvironment]. */
class EnvironmentLinuxOps(private val linux: LinuxEnvironment) : LinuxOps {
    override val status: StateFlow<LinuxStatus> get() = linux.status
    override suspend fun ensureInstalled() { linux.ensureInstalled() }
    override suspend fun run(command: String, timeoutMs: Long, maxChars: Int) = linux.run(command, timeoutMs = timeoutMs, maxChars = maxChars)
    override suspend fun uninstall() { linux.uninstall() }
    override suspend fun repair() = linux.repair()
}

/**
 * Installs, repairs and removes Locus's Linux system and its packages: the one place this happens,
 * shared by onboarding's Workspace step and the Workspace screen's Linux card (docs/design/linux-setup.md).
 *
 * Install and Reinstall download for minutes, so they run in [background] (a WorkManager job in the
 * foreground with a progress notification, [LinuxSetupWorker]) and survive leaving the screen, or the
 * app. The job calls back into [execute], which updates [state] here, in the same process, where the
 * screens read it. Repair and Remove are quick and local, so they run in the application [scope].
 * One operation runs at a time.
 *
 * Nothing here runs unless the user asked: the download is third-party software that F-Droid hasn't
 * checked (see [LinuxEnvironment.ensureInstalled]).
 */
@Singleton
class LinuxSetup(
    private val linux: LinuxOps,
    private val scope: CoroutineScope,
    /** Runs install requests outside any screen; null runs them in [scope] (tests). */
    private val background: Background?,
) {
    @Inject constructor(
        environment: LinuxEnvironment,
        @ApplicationScope scope: CoroutineScope,
        @ApplicationContext context: Context,
    ) : this(EnvironmentLinuxOps(environment), scope, LinuxSetupWorker.Launcher(context))

    /** Starts and cancels [execute] somewhere that outlives the screen. */
    interface Background {
        fun start(request: LinuxSetupRequest)
        fun cancel()
    }

    private val _state = MutableStateFlow(LinuxSetupState())
    val state: StateFlow<LinuxSetupState> = _state.asStateFlow()

    val status: StateFlow<LinuxStatus> get() = linux.status

    private val lock = Mutex()

    /** An inline run (no [background]), kept so [cancel] can stop it. */
    private var inline: Job? = null

    /** True between [install] and the end of its run, including while the job waits to start. */
    @Volatile
    private var pending = false

    /**
     * Installs Linux if it's missing, then [packages] that aren't installed yet. With [reinstall], first
     * removes Linux (packages included; the workspace stays). Ignored while something else runs.
     */
    fun install(packages: Collection<LinuxPackage>, reinstall: Boolean = false) {
        if (_state.value.running || pending) return
        val request = LinuxSetupRequest(LinuxPackage.entries.filter { it in packages }, reinstall)
        pending = true
        // Show it at once; the job may take a moment to start.
        _state.update {
            it.copy(
                running = true,
                operation = if (reinstall) LinuxOperation.Reinstall else LinuxOperation.Install,
                phase = EngineText.Res(R.string.data_linux_phase_starting),
                progress = null,
                error = null,
                message = null,
                cancelled = false,
            )
        }
        if (background != null) background.start(request) else inline = scope.launch { execute(request) }
    }

    /** Cancels an install or reinstall; whatever it downloaded and unpacked so far is deleted. */
    fun cancel() {
        if (!_state.value.cancellable) return
        background?.cancel() ?: inline?.cancel()
        // A job cancelled before it started never runs execute, which would otherwise reset this.
        if (!executing) finish(cancelled = true)
    }

    @Volatile
    private var executing = false

    /**
     * Runs an install request; called by [LinuxSetupWorker] (or inline in tests). apk update runs once,
     * then apk add per package, so each package gets its own progress and result. Failures end up in
     * [state], never thrown; cancellation is rethrown after the state is reset.
     */
    suspend fun execute(request: LinuxSetupRequest) = lock.withLock {
        executing = true
        pending = true
        val chosen = request.packages
        _state.update { s ->
            s.copy(
                running = true,
                operation = if (request.reinstall) LinuxOperation.Reinstall else LinuxOperation.Install,
                phase = null,
                progress = null,
                error = null,
                message = null,
                cancelled = false,
                packages = (if (request.reinstall) emptyMap() else s.packages) + chosen.associateWith { PackageState.Queued },
            )
        }
        var cancelled = false
        try {
            if (request.reinstall) {
                phase(R.string.data_linux_phase_removing_old)
                linux.uninstall()
            }
            if (linux.status.value != LinuxStatus.Ready) {
                phase(R.string.data_linux_phase_installing_alpine)
                linux.ensureInstalled()
            }
            if (chosen.isEmpty()) {
                _state.update { it.copy(message = EngineText.Res(if (request.reinstall) R.string.data_linux_reinstalled else R.string.data_linux_ready)) }
                refreshInstalled()
                return@withLock
            }
            phase(R.string.data_linux_phase_checking)
            val already = installedPackages()
            setPackages(chosen.filter { it in already }.associateWith { PackageState.Installed })
            val todo = chosen.filterNot { it in already }
            if (todo.isNotEmpty()) {
                phase(R.string.data_linux_phase_updating)
                val update = linux.run("apk update", timeoutMs = 5 * 60_000L, maxChars = 4_000)
                if (update.exitCode != 0) {
                    val why = EngineText.Res(if (update.timedOut) R.string.data_linux_update_timed_out else R.string.data_linux_update_unreachable)
                    setPackages(todo.associateWith { PackageState.Failed(why) })
                    _state.update { it.copy(error = withOutput(EngineText.Res(R.string.data_linux_update_failed, why), update.output)) }
                    return@withLock
                }
                todo.forEachIndexed { i, p ->
                    _state.update { it.copy(phase = EngineText.Res(R.string.data_linux_phase_installing_package, p.label, i + 1, todo.size), progress = i.toFloat() / todo.size) }
                    setPackages(mapOf(p to PackageState.Installing))
                    val r = runCatching { linux.run("apk add --no-progress ${p.apk.joinToString(" ")}", timeoutMs = 10 * 60_000L, maxChars = 6_000) }
                    val state = r.fold(
                        onSuccess = { res ->
                            when {
                                res.exitCode == 0 -> PackageState.Installed
                                res.timedOut -> PackageState.Failed(withOutput(EngineText.Res(R.string.data_linux_package_timed_out), res.output))
                                else -> PackageState.Failed(withOutput(EngineText.Res(R.string.data_linux_package_exit, res.exitCode.toString()), res.output))
                            }
                        },
                        onFailure = { e -> if (e is CancellationException) throw e else PackageState.Failed(EngineText.Plain(e.message ?: e.toString())) },
                    )
                    setPackages(mapOf(p to state))
                }
            }
            val failed = chosen.count { _state.value.packages[it] is PackageState.Failed }
            _state.update {
                when {
                    failed > 0 -> it.copy(error = EngineText.Res(R.string.data_linux_packages_failed, failed, chosen.size))
                    request.reinstall -> it.copy(message = EngineText.Res(R.string.data_linux_reinstalled_with, chosen.joinToString { p -> p.label }))
                    else -> it.copy(message = EngineText.Res(R.string.data_linux_installed, chosen.joinToString { p -> p.label }))
                }
            }
        } catch (e: CancellationException) {
            cancelled = true
            throw e
        } catch (e: Throwable) {
            _state.update { it.copy(error = EngineText.Plain(e.message ?: e.toString())) }
        } finally {
            executing = false
            finish(cancelled)
        }
    }

    /** Ends a run: nothing running, no package left waiting. */
    private fun finish(cancelled: Boolean) {
        pending = false
        _state.update { s ->
            s.copy(
                running = false,
                phase = null,
                progress = null,
                cancelled = cancelled,
                message = if (cancelled) EngineText.Res(R.string.data_linux_cancelled) else s.message,
                packages = s.packages.mapValues { (_, v) -> if (v == PackageState.Queued || v == PackageState.Installing) PackageState.Unknown else v },
            )
        }
    }

    /**
     * Rewrites the files Locus manages in Linux and checks it starts (see [LinuxEnvironment.repair]);
     * packages and the workspace are kept.
     */
    fun repair() = quick(LinuxOperation.Repair, R.string.data_linux_phase_repairing) {
        val release = linux.repair()
        EngineText.Res(R.string.data_linux_repaired, release)
    }

    /** Removes Linux and its packages (services stop first). The workspace is kept. */
    fun remove() = quick(LinuxOperation.Remove, R.string.data_linux_phase_removing) {
        linux.uninstall()
        _state.update { it.copy(packages = emptyMap()) }
        EngineText.Res(R.string.data_linux_removed)
    }

    /** Clears the last outcome once a screen has shown it. */
    fun outcomeShown() = _state.update { it.copy(message = null, cancelled = false) }

    /** Reads which offered packages are installed (`apk info -e`), when Linux is ready and nothing runs. */
    fun refreshPackages() {
        if (_state.value.running || linux.status.value != LinuxStatus.Ready) return
        scope.launch { runCatching { refreshInstalled() } }
    }

    private fun quick(operation: LinuxOperation, @StringRes phase: Int, block: suspend () -> EngineText) {
        if (_state.value.running || pending) return
        pending = true
        _state.update { it.copy(running = true, operation = operation, phase = EngineText.Res(phase), progress = null, error = null, message = null, cancelled = false) }
        scope.launch {
            lock.withLock {
                try {
                    val done = block()
                    _state.update { it.copy(message = done) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    _state.update { it.copy(error = EngineText.Plain(e.message ?: e.toString())) }
                } finally {
                    finish(cancelled = false)
                }
            }
        }
    }

    private suspend fun refreshInstalled() {
        val installed = installedPackages()
        _state.update { s ->
            s.copy(packages = LinuxPackage.entries.associateWith { p -> if (p in installed) PackageState.Installed else s.packages[p].takeUnless { it == PackageState.Installed } ?: PackageState.Unknown })
        }
    }

    private fun phase(@StringRes text: Int) = _state.update { it.copy(phase = EngineText.Res(text), progress = null) }

    private fun setPackages(states: Map<LinuxPackage, PackageState>) = _state.update { it.copy(packages = it.packages + states) }

    /** The offered packages whose apk names are all installed. */
    private suspend fun installedPackages(): Set<LinuxPackage> {
        val names = LinuxPackage.entries.flatMap { it.apk }.distinct()
        val r = linux.run(names.joinToString("; ") { "apk info -e $it >/dev/null 2>&1 && echo ok:$it" } + "; true", timeoutMs = 60_000, maxChars = 4_000)
        val ok = r.output.lines().map { it.trim() }.filter { it.startsWith("ok:") }.map { it.removePrefix("ok:") }.toSet()
        return LinuxPackage.entries.filter { p -> p.apk.all { it in ok } }.toSet()
    }

    private fun tail(output: String) = output.trim().lines().takeLast(6).joinToString("\n")

    /** [text], then the last lines of [output] on the lines below, when there are any. */
    private fun withOutput(text: EngineText, output: String): EngineText =
        tail(output).takeIf { it.isNotEmpty() }?.let { EngineText.Res(R.string.data_linux_with_output, text, it) } ?: text
}
