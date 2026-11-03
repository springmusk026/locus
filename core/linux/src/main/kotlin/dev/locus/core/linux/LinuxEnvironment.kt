package dev.locus.core.linux

import dev.locus.core.agent.ChatScope
import dev.locus.core.model.FileTrees
import dev.locus.core.model.LINUX_INSTALL_PATH
import dev.locus.core.model.ProjectPolicy
import android.content.Context
import android.os.Build
import android.os.Process
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.InputStream
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

sealed interface LinuxStatus {
    data object NotInstalled : LinuxStatus
    /** [fraction] is how far the current [step] is (0..1), or null when that isn't known (unpacking). */
    data class Installing(val step: String, val fraction: Float? = null) : LinuxStatus
    data object Ready : LinuxStatus
    data class Unavailable(val reason: String) : LinuxStatus
    data class Failed(val message: String) : LinuxStatus
}

/**
 * A command needed Linux, but it isn't installed (Locus never downloads it without the user asking)
 * or can't run on this phone ([unavailableReason]), so a program that needs it can't start.
 */
class LinuxNotInstalledException(val unavailableReason: String? = null) :
    IllegalStateException(unavailableReason ?: "Locus's Linux system isn't installed. Install it from $LINUX_INSTALL_PATH.")

/**
 * The last time Android killed one of Locus's Linux processes while Locus was in the background
 * ([AndroidKill]): [what] it was ("A command", "Service web", "A terminal") and [at] when.
 */
data class AndroidKillNotice(val what: String, val at: Long)

/**
 * [savedTo] is where the whole output was saved, scrubbed of secrets, when it was [truncated] and the
 * caller asked for that; [savedBytes] is its size and [savedCapped] whether even that was cut short.
 */
data class CommandResult(
    val exitCode: Int?,
    val output: String,
    val timedOut: Boolean,
    val truncated: Boolean,
    val savedTo: File? = null,
    val savedBytes: Long = 0,
    val savedCapped: Boolean = false,
    /** It died from signal 9 while Locus was in the background: most likely Android's phantom process limit ([AndroidKill]). */
    val killedByAndroid: Boolean = false,
)

/**
 * A small Linux system (Alpine) the agent works in, running under proot without root.
 *
 * proot and its loader ship inside the APK as `lib*.so` so Android extracts them to the native
 * library directory, the one place an app may execute files from. proot's loader then maps the
 * distro's programs into memory itself, which Android allows. The agent workspace is mounted at
 * `/workspace`, so the shell, the file tools and the Files app all see the same files.
 */
@Singleton
class LinuxEnvironment @Inject constructor(@param:ApplicationContext private val context: Context) {

    private val _status = MutableStateFlow(initialStatus())
    val status: StateFlow<LinuxStatus> = _status.asStateFlow()

    /**
     * Where commands run instead of proot. Only the JVM benchmark harness (`:bench`, see
     * docs/benchmarks/README.md) sets one, through [runOnHost]; the app never does, so on a phone every
     * command runs in the distro as described above.
     */
    fun interface CommandHost {
        /** The process for `/bin/sh -c` [command] in [cwd], a path inside Linux (`/workspace/...`). */
        fun process(command: String, cwd: String): ProcessBuilder
    }

    @Volatile
    private var host: CommandHost? = null

    /** Runs every command (run_command, services, the git tools) through [host] from now on; Linux counts as ready. */
    fun runOnHost(host: CommandHost) {
        this.host = host
        _status.value = LinuxStatus.Ready
    }

    /** The process for [command] in [cwd]: proot with its environment, or the [CommandHost] when one is set. */
    private fun processFor(command: String, cwd: String, mount: File? = null): ProcessBuilder =
        host?.process(command, cwd) ?: ProcessBuilder((if (mount != null) guest(mount) else guest()).command(command, cwd))
            .apply { environment().apply { clear(); putAll(prootEnvironment()) } }

    private val installLock = Mutex()
    private val nativeDir = File(context.applicationInfo.nativeLibraryDir)
    private val base = File(context.filesDir, "linux")
    private val rootfs = File(base, "alpine")
    private val tmp = File(base, "tmp")
    private val fakes = File(base, "proc-fakes")
    private val libDir = File(base, "lib")
    private val workspace = File(context.filesDir, "workspace")
    private val proot = File(nativeDir, "libproot.so")
    private val marker = File(base, ".installed-$ALPINE_VERSION")

    init {
        // LocusApplication already did this; it's repeated here so no process can start Linux without it.
        ProcIsolation.makeNotDumpable()
    }

    private fun initialStatus(): LinuxStatus = when {
        !File(context.applicationInfo.nativeLibraryDir, "libproot.so").exists() ->
            LinuxStatus.Unavailable("This build doesn't include the Linux runtime for ${Build.SUPPORTED_ABIS.firstOrNull()}.")
        File(File(context.filesDir, "linux"), ".installed-$ALPINE_VERSION").exists() -> LinuxStatus.Ready
        else -> LinuxStatus.NotInstalled
    }

    /**
     * Downloads and unpacks the distro (about 4 MB) if needed. Safe to call repeatedly.
     *
     * Only call this when the user has asked for Linux: the download is third-party software from
     * alpinelinux.org that F-Droid hasn't checked, so it must stay opt-in. [run] and [startService]
     * never install it themselves.
     *
     * Cancelling stops the download between chunks; what was downloaded or unpacked is deleted and
     * the status goes back to not installed, so a cancel never leaves a half-installed system.
     */
    suspend fun ensureInstalled() = installLock.withLock {
        if (_status.value == LinuxStatus.Ready) return@withLock
        (_status.value as? LinuxStatus.Unavailable)?.let { throw IllegalStateException(it.reason) }
        withContext(Dispatchers.IO) {
            val archive = File(base, "rootfs.tar.gz")
            try {
                val arch = when (Build.SUPPORTED_ABIS.firstOrNull()) {
                    "arm64-v8a" -> Arch.AARCH64
                    "x86_64" -> Arch.X86_64
                    else -> throw IllegalStateException("Unsupported CPU: ${Build.SUPPORTED_ABIS.joinToString()}")
                }
                val downloading = "Downloading Alpine Linux $ALPINE_VERSION"
                _status.value = LinuxStatus.Installing(downloading, 0f)
                base.mkdirs()
                download(arch.url, archive, arch.sha256) { fraction -> _status.value = LinuxStatus.Installing(downloading, fraction) }

                currentCoroutineContext().ensureActive()
                _status.value = LinuxStatus.Installing("Unpacking")
                FileTrees.delete(rootfs)
                rootfs.mkdirs()
                GZIPInputStream(archive.inputStream().buffered()).use { Tar.extract(it, rootfs) }
                archive.delete()

                currentCoroutineContext().ensureActive()
                configure()
                marker.writeText(ALPINE_VERSION)
                _status.value = LinuxStatus.Ready
            } catch (e: CancellationException) {
                archive.delete()
                FileTrees.delete(rootfs)
                _status.value = initialStatus()
                throw e
            } catch (e: Exception) {
                _status.value = LinuxStatus.Failed(e.message ?: e.toString())
                throw e
            }
        }
    }

    /** The Alpine release this build installs, e.g. "3.24.3". */
    val version: String get() = ALPINE_VERSION

    /**
     * Repairs an installed system without touching its packages or the workspace: rewrites the files
     * Locus manages (DNS servers, hosts, the fake /proc files), re-creates its folders and re-links
     * proot's libraries, then runs a command to check the system starts. Throws when the system's own files
     * are damaged; only a reinstall fixes that. Returns the release the check printed.
     */
    suspend fun repair(): String {
        requireReady()
        installLock.withLock {
            withContext(Dispatchers.IO) {
                check(File(rootfs, "bin/busybox").exists() && File(rootfs, "etc/alpine-release").exists()) {
                    "Linux's own files are damaged or missing. Reinstall it to fix this."
                }
                configure()
                // Not emptied: running services and terminals use it.
                tmp.mkdirs()
                workspace.mkdirs()
                linkSharedLibraries()
            }
        }
        val probe = run("cat /etc/alpine-release", timeoutMs = 30_000, maxChars = 2_000)
        check(probe.exitCode == 0) {
            val why = if (probe.timedOut) "timed out" else "exit code ${probe.exitCode}"
            "Linux still doesn't start ($why). Reinstall it to fix this.\n${probe.output.trim().takeLast(500)}".trim()
        }
        return probe.output.trim().lines().lastOrNull().orEmpty()
    }

    /**
     * Bytes the Linux system takes on the phone (the distro, its packages, logs and temp files; the
     * workspace isn't counted). Symlinks are counted as links, never followed.
     */
    suspend fun diskUsage(): Long = withContext(Dispatchers.IO) {
        val path = base.toPath()
        if (!java.nio.file.Files.exists(path)) return@withContext 0L
        var total = 0L
        java.nio.file.Files.walkFileTree(path, object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                if (attrs.isRegularFile) total += attrs.size()
                return java.nio.file.FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: java.nio.file.Path, exc: java.io.IOException): java.nio.file.FileVisitResult =
                java.nio.file.FileVisitResult.CONTINUE
        })
        total
    }

    private val _androidKill = MutableStateFlow<AndroidKillNotice?>(null)

    /** The last Linux process Android killed while Locus was in the background, until [dismissAndroidKill]. */
    val androidKill: StateFlow<AndroidKillNotice?> = _androidKill.asStateFlow()

    /** Records that Android killed [what] (see [AndroidKill]); the Workspace's Linux card explains it. */
    fun noteAndroidKill(what: String) {
        _androidKill.value = AndroidKillNotice(what, System.currentTimeMillis())
    }

    fun dismissAndroidKill() {
        _androidKill.value = null
    }

    /** Throws [LinuxNotInstalledException] unless the user has already installed Linux. */
    private fun requireReady() {
        when (val s = _status.value) {
            LinuxStatus.Ready -> Unit
            is LinuxStatus.Unavailable -> throw LinuxNotInstalledException(s.reason)
            is LinuxStatus.Installing -> throw LinuxNotInstalledException("Linux is still being installed.")
            else -> throw LinuxNotInstalledException("Linux isn't installed.")
        }
    }

    /** Removes the Linux system (packages included). The workspace is not touched. */
    suspend fun uninstall() = installLock.withLock {
        withContext(Dispatchers.IO) {
            // Services and piped programs (local MCP servers) run from the system being removed.
            services.keys.toList().forEach { name -> services.remove(name)?.let { stop(it) } }
            piped.toList().forEach { stopPiped(it) }
            records.clear()
            // Linux programs can leave links to any host path in the rootfs (a link to Locus's own
            // data included); FileTrees removes the links instead of what they point at.
            FileTrees.delete(base)
        }
        _androidKill.value = null
        _status.value = initialStatus()
    }

    /**
     * Runs [command] with `/bin/sh -c` inside the distro, in [cwd] (default /workspace). Output is
     * captured from both streams and capped at [maxChars]; the process is stopped after [timeoutMs].
     * When the output is longer than that and [saveFullOutputTo] is given, all of it (up to
     * [MAX_SAVED_BYTES]) is written there, scrubbed of secrets like the returned text.
     */
    suspend fun run(
        command: String,
        cwd: String = "/workspace",
        timeoutMs: Long = 120_000,
        maxChars: Int = 30_000,
        saveFullOutputTo: File? = null,
    ): CommandResult {
        requireReady()
        return withContext(Dispatchers.IO) {
            linkSharedLibraries()
            tmp.mkdirs()
            workspace.mkdirs()
            // The raw copy stays in Locus's private storage (not mounted in Linux) until it is scrubbed.
            val spool = saveFullOutputTo?.let { File(base, "spool").apply { mkdirs() }.let { dir -> File.createTempFile("out", ".log", dir) } }
            try {
                val process = processFor(command, cwd)
                    .redirectErrorStream(true)
                    .start()
                process.outputStream.close()
                val output = BoundedBuffer(maxChars)
                coroutineScope {
                    val reader = async(Dispatchers.IO) { spool?.let { pumpAndKeep(process.inputStream, output, it) } ?: pump(process.inputStream, output) }
                    val exited = try {
                        withTimeoutOrNull(timeoutMs) {
                            while (process.isAliveCompat()) kotlinx.coroutines.delay(100)
                            true
                        } ?: false
                    } catch (e: CancellationException) {
                        // Cancelled (the user cancelled a package install, say): stop the process rather
                        // than leave it running, which also ends the reader this scope waits for.
                        withContext(NonCancellable) { stop(process) }
                        throw e
                    }
                    if (!exited) stop(process)
                    val capped = reader.await() == true
                    val code = if (exited) process.exitValue() else null
                    val result = CommandResult(
                        code, redactor.scrub(output.text()), timedOut = !exited, truncated = output.truncated,
                        killedByAndroid = AndroidKill.applies(code, inBackground()),
                    )
                    if (result.killedByAndroid) noteAndroidKill("A command")
                    if (spool == null || saveFullOutputTo == null || !output.truncated) return@coroutineScope result
                    // The output folder is in /workspace, where Linux can leave a link at the name; replace, never write through.
                    // A folder on the way that Linux made a link is refused; the result is then just not saved.
                    try {
                        FileTrees.writeReplacing(workspaceRoot(), saveFullOutputTo, redactor.scrub(spool.readText()))
                    } catch (_: java.io.IOException) {
                        return@coroutineScope result
                    }
                    result.copy(savedTo = saveFullOutputTo, savedBytes = saveFullOutputTo.length(), savedCapped = capped)
                }
            } finally {
                spool?.delete()
            }
        }
    }

    /** A long-running process started with [startService], e.g. a dev server. */
    class Service(val name: String, val command: String, val process: java.lang.Process, val log: File, val startedAt: Long) {
        /** Locus is stopping it, so its exit (a SIGKILL after the grace period, too) isn't Android's doing. */
        @Volatile internal var stopping = false

        /** It died from signal 9 while Locus was in the background ([AndroidKill]). */
        @Volatile var killedByAndroid = false
            internal set
    }

    /**
     * Whether Locus is in the background, read when a process exits to tell an Android kill from others
     * ([AndroidKill]). Replaceable for tests.
     */
    @Volatile
    var inBackground: () -> Boolean = AndroidKill::appInBackground

    private val services = java.util.concurrent.ConcurrentHashMap<String, Service>()
    private val records = ServiceRecords(File(base, "services.json"))

    /** Why a service is or isn't running ([ServiceStatus.state]). */
    enum class ServiceState {
        Running,

        /** Locus (or the phone) restarted while it ran, which ended it; nothing went wrong with it. */
        StoppedByRestart,

        /** It ended on its own with exit code 0. */
        Finished,

        /** It ended on its own with an error: a non-zero exit code or a signal. */
        Crashed,

        /** Android's phantom process limit killed it while Locus was in the background ([AndroidKill]). */
        KilledByAndroid,
    }

    /**
     * A service as the agent and the health page see it: running, ended on its own ([exitCode]), or
     * [lostOnRestart] (started before Locus last restarted, which ended it; see [ServiceRecords]).
     * [autostart] is the user's "Start when Locus opens".
     */
    data class ServiceStatus(
        val name: String,
        val command: String,
        val running: Boolean,
        val lostOnRestart: Boolean,
        val startedAt: Long,
        /** It stopped because Android killed it while Locus was in the background ([AndroidKill]). */
        val killedByAndroid: Boolean = false,
        /** How it ended on its own; null while it runs and when a restart ended it. */
        val exitCode: Int? = null,
        val autostart: Boolean = false,
    ) {
        val state: ServiceState get() = when {
            running -> ServiceState.Running
            killedByAndroid -> ServiceState.KilledByAndroid
            lostOnRestart -> ServiceState.StoppedByRestart
            exitCode == 0 -> ServiceState.Finished
            // Ended on its own with a non-zero code, a signal, or a code that couldn't be read.
            else -> ServiceState.Crashed
        }
    }

    /** Every service started since it was last stopped, including ones a restart of Locus ended. */
    fun serviceStatuses(): List<ServiceStatus> {
        val saved = records.all().associateBy { it.name }
        val live = services.values.map {
            val running = isAlive(it)
            val code = if (running) null else runCatching { it.process.exitValue() }.getOrNull()
            ServiceStatus(
                it.name, it.command, running, lostOnRestart = false, it.startedAt,
                killedByAndroid = !running && it.killedByAndroid,
                exitCode = code,
                autostart = saved[it.name]?.autostart == true,
            )
        }
        // A record without an exit is one a restart ended; one with an exit ended on its own before that.
        val lost = saved.values.filter { !services.containsKey(it.name) }.map {
            ServiceStatus(
                it.name, it.command, running = false,
                lostOnRestart = it.exitCode == null && !it.killedByAndroid,
                startedAt = it.startedAt,
                killedByAndroid = it.killedByAndroid,
                exitCode = it.exitCode,
                autostart = it.autostart,
            )
        }
        return (live + lost).sortedBy { it.startedAt }
    }

    /**
     * Turns "Start when Locus opens" on or off for service [name] (the health page's switch; the agent
     * has no tool for it, since it runs a command without asking). False when there's no such service.
     */
    suspend fun setServiceAutostart(name: String, on: Boolean): Boolean =
        withContext(Dispatchers.IO) { records.update(name) { it.copy(autostart = on) } }

    /**
     * Starts every service the user set to start automatically that isn't running, once Linux is
     * ready (core/data's LinuxAutostart calls it when Locus opens). Returns the names that couldn't start, with why;
     * those show on the health page as set to start but not running.
     */
    suspend fun startAutostartServices(): Map<String, String> {
        if (_status.value != LinuxStatus.Ready) return emptyMap()
        val due = withContext(Dispatchers.IO) { records.all() }.filter { it.autostart && services[it.name]?.let(::isAlive) != true }
        val failed = linkedMapOf<String, String>()
        for (record in due) {
            try {
                restartService(record.name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed[record.name] = e.message ?: e.toString()
            }
        }
        return failed
    }

    /**
     * Starts [command] in the background and keeps it running until [stopService] or until Locus
     * exits. Output goes to a log file; use [serviceLog] to read it.
     */
    suspend fun startService(name: String, command: String, cwd: String = "/workspace"): Service =
        startServiceIn(name, command, cwd, ChatScope.current()?.folder)

    /**
     * Starts service [name] again with the command, directory and workspace folder it last ran with
     * (the health screen's Restart, docs/design/linux-health.md). A project's service gets only its
     * project's folder again, never the whole workspace. Null when there's no such service.
     */
    suspend fun restartService(name: String): Service? {
        val record = withContext(Dispatchers.IO) { records.all().firstOrNull { it.name == name } } ?: return null
        return startServiceIn(name, record.command, record.cwd, record.folder)
    }

    private suspend fun startServiceIn(name: String, command: String, cwd: String, folder: String?): Service {
        requireReady()
        return withContext(Dispatchers.IO) {
            linkSharedLibraries()
            tmp.mkdirs()
            workspace.mkdirs()
            services.remove(name)?.let { stop(it) }
            val log = File(base, "logs/$name.log").apply { parentFile?.mkdirs(); delete() }
            val process = processFor(command, cwd, mount = folder?.let(::folderMount) ?: workspace)
                .redirectErrorStream(true)
                .redirectOutput(log)
                .start()
            process.outputStream.close()
            Service(name, command, process, log, System.currentTimeMillis()).also {
                services[name] = it
                // "Start when Locus opens" stays only for the same command in the same place: the user
                // turned it on for what they saw, and a new command from the agent needs their say again.
                val keep = records.all().firstOrNull { r -> r.name == name }
                    ?.takeIf { r -> r.autostart && r.command == command && r.cwd == cwd && r.folder == folder } != null
                records.add(ServiceRecords.Record(name, command, cwd, it.startedAt, folder, autostart = keep))
                watchExit(it)
            }
        }
    }

    /**
     * Notes how [service] ended at the moment it does, since whether Locus was in the background then
     * is what tells an Android kill apart ([AndroidKill]). One daemon thread per service, blocked in waitFor.
     */
    private fun watchExit(service: Service) {
        Thread({
            val code = runCatching { service.process.waitFor() }.getOrNull()
            if (!service.stopping) service.killedByAndroid = AndroidKill.applies(code, inBackground())
            if (service.killedByAndroid) noteAndroidKill("Service ${service.name}")
            // Kept on disk, so after a restart a crash still reads as a crash, not as "a restart ended it".
            if (!service.stopping && code != null && services[service.name] === service) {
                runCatching { records.update(service.name) { r -> if (r.startedAt == service.startedAt) r.copy(exitCode = code, killedByAndroid = service.killedByAndroid) else r } }
            }
        }, "locus-service-${service.name}").apply { isDaemon = true }.start()
    }

    /** Stops [service] as Locus's own doing (see [Service.stopping]). */
    private suspend fun stop(service: Service) {
        service.stopping = true
        stop(service.process)
    }

    fun services(): List<Service> = services.values.sortedBy { it.startedAt }

    fun isAlive(service: Service) = service.process.isAliveCompat()

    /** The end of a service's output; also for one a restart ended, whose log file is still there. */
    fun serviceLog(name: String, maxChars: Int = 4_000): String? =
        (services[name]?.log ?: File(base, "logs/$name.log").takeIf { records.all().any { it.name == name } })
            ?.takeIf { it.exists() }?.let { tail(it, maxChars) }?.let(redactor::scrub)

    /** About the last [maxChars] characters of [file], read from its end so a large log isn't loaded whole. */
    private fun tail(file: File, maxChars: Int): String = java.io.RandomAccessFile(file, "r").use { raf ->
        val length = raf.length()
        val start = (length - maxChars.toLong() * 2).coerceAtLeast(0)
        val bytes = ByteArray((length - start).toInt())
        raf.seek(start)
        raf.readFully(bytes)
        String(bytes, Charsets.UTF_8).takeLast(maxChars)
    }

    /** Stops a service, or forgets one a restart already ended. False if there's no such service. */
    suspend fun stopService(name: String): Boolean {
        val forgotten = withContext(Dispatchers.IO) { records.remove(name) }
        val service = services.remove(name) ?: return forgotten
        withContext(Dispatchers.IO) { stop(service) }
        return true
    }

    /** Secret values given to piped programs, scrubbed from command output and service logs. */
    val redactor = OutputRedactor()

    private val piped: MutableSet<java.lang.Process> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * Piped programs started with secrets in their environment (local MCP servers). Their process
     * trees are hidden from every Linux launch after them ([SecretProcesses]), so another command,
     * the terminal or another server can't read the secrets through `/proc/<pid>/environ`.
     */
    private val secretHolders: MutableSet<java.lang.Process> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** True once the Linux system is installed; nothing here installs it implicitly. */
    val isInstalled: Boolean get() = _status.value == LinuxStatus.Ready

    /**
     * Starts [command] with `/bin/sh -c` in [cwd] with its standard streams piped to Locus, for a
     * program that talks over them (a local MCP server). Unlike [run] this never installs Linux: it
     * throws [LinuxNotInstalledException] instead, so the caller can say so. [extraEnv] is added to
     * the usual environment (it can't replace what proot needs); values aren't logged anywhere.
     * The process sees the distro plus /workspace, like every other command, and ends with Locus.
     */
    suspend fun startPiped(command: String, extraEnv: Map<String, String>, cwd: String = "/workspace"): java.lang.Process {
        if (!isInstalled) throw LinuxNotInstalledException((_status.value as? LinuxStatus.Unavailable)?.reason)
        return withContext(Dispatchers.IO) {
            linkSharedLibraries()
            tmp.mkdirs()
            workspace.mkdirs()
            val base = prootEnvironment()
            // Local MCP servers are shared by every chat, so they always get the whole workspace; a
            // project's connector allow-list decides whether its chats may use them.
            ProcessBuilder(guest(workspace).command(command, cwd))
                .apply { environment().apply { clear(); putAll(extraEnv.filterKeys { it !in base }); putAll(base) } }
                .start()
                .also {
                    piped += it
                    if (extraEnv.isNotEmpty()) secretHolders += it
                }
        }
    }

    /**
     * Stops a [startPiped] process the way the MCP stdio spec asks: close its input, wait for it to
     * exit, then force it (SIGQUIT, which proot passes on, then kill).
     */
    suspend fun stopPiped(process: java.lang.Process, graceMs: Long = 2_000) {
        withContext(Dispatchers.IO) {
            runCatching { process.outputStream.close() }
            withTimeoutOrNull(graceMs) { while (process.isAliveCompat()) kotlinx.coroutines.delay(50) }
            if (process.isAliveCompat()) stop(process)
            piped -= process
            secretHolders -= process
        }
    }

    /**
     * The folder bound at `/workspace`: the project's folder in a project chat ([ChatScope]), so the
     * shell sees the same files as the file tools; otherwise the whole workspace. A folder that
     * isn't a clean workspace-relative path is refused rather than widened.
     */
    private fun scopedWorkspace(): File {
        val folder = ChatScope.current()?.folder ?: return workspace
        return folderMount(folder)
    }

    /** The workspace folder of a project ([ChatScope]); refused rather than widened when it isn't a clean relative path. */
    private fun folderMount(folder: String): File {
        val clean = ProjectPolicy.cleanFolder(folder) ?: throw IllegalStateException("This project's folder '$folder' isn't a valid workspace folder.")
        val root = BindSources.realDir(workspaceRoot())
        // Checked before mkdirs, which would otherwise create folders wherever a link on the way points.
        val dir = BindSources.inside(root, File(root, clean))
        dir.mkdirs()
        return dir
    }

    /**
     * The workspace with links resolved in Locus's own part of the path (`/data/user/0`), but not in
     * `workspace` itself, which Linux can replace: `rmdir /workspace; ln -s ... /workspace` acts on it.
     */
    private fun workspaceRoot(): File = File(context.filesDir.canonicalFile, workspace.name)

    /**
     * The proot command lines ([GuestLaunch]), with [mount] at /workspace. Locus's own processes and
     * threads, and the binder devices, are hidden from the guest (see [ProcIsolation]). They're
     * looked up again for every launch, so processes and threads Locus started since the last one
     * are hidden too.
     */
    private fun guest(mount: File = scopedWorkspace()): GuestLaunch = GuestLaunch(
        ProotArgs.Paths(proot.path, rootfs.path, BindSources.realDir(tmp).path, bindableMount(mount).path, fakes.path, emptyShadow().path),
        FAKE_PROC.onEach(::checkFake),
        hiddenPids = hiddenProcesses(),
        hiddenDevices = ProcIsolation.BINDER_DEVICES.filter { File(it).exists() },
    )

    /**
     * What a new launch must not see under `/proc`: Locus's own processes and threads, and the process
     * trees of local MCP servers that hold secrets ([secretHolders]). Looked up again for every launch.
     */
    private fun hiddenProcesses(): Set<Int> {
        val proc = File("/proc")
        val app = runCatching { AppProcesses.find(proc, Process.myUid(), context.packageName, Process.myPid()) }.getOrDefault(setOf(Process.myPid()))
        secretHolders.removeAll { !it.isAliveCompat() }
        val roots = secretHolders.mapNotNull(::pidOf)
        return app + runCatching { SecretProcesses.find(proc, roots) }.getOrDefault(roots.toSet())
    }

    /**
     * How to start an interactive login shell for the terminal (docs/design/terminal.md): the same
     * proot command line and environment as agent commands ([GuestLaunch]), with the whole
     * workspace at /workspace like local MCP servers, since the terminal belongs to no chat or
     * project. The terminal opens the PTY and starts the process itself. Never installs Linux: throws
     * [LinuxNotInstalledException] instead. Locus processes started after the shell aren't in its
     * hidden list; the non-dumpable flag ([ProcIsolation]) still keeps their files closed to it.
     */
    suspend fun terminalLaunch(cwd: String = "/workspace"): TerminalLaunch {
        if (!isInstalled || host != null) throw LinuxNotInstalledException((_status.value as? LinuxStatus.Unavailable)?.reason)
        return withContext(Dispatchers.IO) {
            linkSharedLibraries()
            tmp.mkdirs()
            workspace.mkdirs()
            val argv = guest(workspace).loginShell(cwd, hasBash = File(rootfs, "bin/bash").exists())
            TerminalLaunch(argv.first(), argv, GuestLaunch.terminalEnvironment(prootEnvironment()), workspace.path)
        }
    }

    /**
     * The folder bound at /workspace, with links resolved, which must be the workspace or inside it.
     * proot resolves links in a binding's host path (`realpath2` in `new_binding`, src/path/binding.c),
     * and Linux can replace the workspace or a project's folder with a link (`rm -rf /workspace/app;
     * ln -s /data/data/dev.locus /workspace/app`), which would bind Locus's own data at /workspace.
     * The resolved path is what proot gets, so only a swap in the moment between here and proot's
     * start could still change it.
     */
    private fun bindableMount(mount: File): File {
        val root = workspaceRoot()
        BindSources.realDir(root)
        return BindSources.inside(root, mount)
    }

    /**
     * A fake /proc file is bound read-write, so Linux can delete it and leave a link in its place,
     * which proot would then bind (and resolve) at /proc/<name): put the real file back first.
     */
    private fun checkFake(name: String) {
        if (!BindSources.isPlainFile(File(fakes, name))) {
            writeManaged(fakes, name, context.assets.open("proc-fakes/$name").use { it.readBytes() })
        }
    }

    /** An empty directory bound over what the guest mustn't see; emptied again if anything got into it. */
    private fun emptyShadow(): File {
        val dir = File(base, "proc-shadow")
        // Bound in the guest too (over /dev/binder, say), so Linux can replace it with a link.
        if (!FileTrees.isLink(dir) && dir.isDirectory && dir.list().isNullOrEmpty()) return dir
        // Not File.deleteRecursively, which would follow a symlink a guest program left in here.
        FileTrees.delete(dir)
        dir.mkdirs()
        return dir
    }

    private fun prootEnvironment(): Map<String, String> = mapOf(
        "PROOT_LOADER" to File(nativeDir, "libproot-loader.so").path,
        "PROOT_LOADER_32" to File(nativeDir, "libproot-loader32.so").path,
        "PROOT_TMP_DIR" to tmp.path,
        "LD_LIBRARY_PATH" to "${libDir.path}:${nativeDir.path}",
        "HOME" to "/root",
        "USER" to "root",
        "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "LANG" to "C.UTF-8",
        "TERM" to "dumb",
        "TMPDIR" to "/tmp",
        "TZ" to posixTimeZone(),
    ).filterKeys { it != "PROOT_LOADER_32" || File(nativeDir, "libproot-loader32.so").exists() }

    /**
     * The phone's current UTC offset as a POSIX TZ string (e.g. Kathmandu → "<+0545>-05:45"). The
     * minimal Alpine image has no zoneinfo database, so named zones would silently fall back to UTC.
     */
    private fun posixTimeZone(): String {
        val offsetMinutes = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
        val sign = if (offsetMinutes >= 0) "+" else "-"
        val abs = kotlin.math.abs(offsetMinutes)
        val name = "%s%02d%02d".format(sign, abs / 60, abs % 60)
        // POSIX offsets are west-positive, the opposite of the usual notation.
        val posix = "%s%02d:%02d".format(if (offsetMinutes >= 0) "-" else "+", abs / 60, abs % 60)
        return "<$name>$posix"
    }

    /** proot ignores SIGTERM/SIGINT; SIGQUIT makes it clean up its children. */
    private suspend fun stop(process: java.lang.Process) {
        // A host process (benchmarks on the JVM) isn't proot, and there is no android.os.Process to signal it with.
        if (host != null) {
            process.destroyForcibly()
            return
        }
        pidOf(process)?.let { Process.sendSignal(it, 3) }
        withTimeoutOrNull(2_000) { while (process.isAliveCompat()) kotlinx.coroutines.delay(100) }
        process.destroyForcibly()
    }

    /**
     * proot builds that link talloc dynamically ask for `libtalloc.so.2`, but an APK can only ship
     * files named `lib*.so`. Expose the shipped copy under its soname in a private directory.
     * (tools/build-proot.sh now links talloc statically, so newer builds don't need this.)
     */
    private fun linkSharedLibraries() {
        if (host != null) return
        val shipped = File(nativeDir, "libtalloc.so")
        if (!shipped.exists()) return
        val alias = File(libDir, "libtalloc.so.2")
        if (alias.exists() && alias.length() == shipped.length()) return
        libDir.mkdirs()
        shipped.copyTo(alias, overwrite = true)
    }

    /**
     * Writes the files Locus manages in Linux. Linux programs can replace any of them with a link to
     * a host path (proot keeps a link's target as written), and the fake /proc files are bound into
     * the guest read-write, so each is replaced through [writeManaged] rather than written through.
     */
    private fun configure() {
        writeManaged(rootfs, "etc/resolv.conf", DEFAULT_RESOLV_CONF)
        writeManaged(rootfs, "etc/hosts", "127.0.0.1 localhost\n::1 localhost\n")
        File(rootfs, "root").mkdirs()
        File(rootfs, "workspace").mkdirs()
        File(rootfs, "etc/profile.d").mkdirs()
        writeManaged(rootfs, "etc/motd", "")
        fakes.mkdirs()
        FAKE_PROC.forEach { name ->
            writeManaged(fakes, name, context.assets.open("proc-fakes/$name").use { it.readBytes() })
        }
    }

    private fun writeManaged(root: File, relative: String, text: String) = writeManaged(root, relative, text.toByteArray())

    private fun writeManaged(root: File, relative: String, bytes: ByteArray) {
        try {
            FileTrees.replaceFile(root, relative, bytes)
        } catch (e: java.io.IOException) {
            throw IllegalStateException("Linux's own files are damaged or missing. Reinstall it to fix this.", e)
        }
    }

    /**
     * Downloads [url] to [dest], checking it against [sha256]. [onProgress] gets the fraction done
     * (when the server sends a length), at most once per percent. Checks for cancellation between chunks.
     */
    private suspend fun download(url: String, dest: File, sha256: String, onProgress: (Float) -> Unit) {
        val digest = MessageDigest.getInstance("SHA-256")
        val connection = URL(url).openConnection().apply {
            connectTimeout = 30_000
            readTimeout = 30_000
        }
        val total = connection.contentLengthLong
        connection.getInputStream().use { input ->
            dest.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var lastPercent = -1
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val percent = (done * 100 / total).toInt()
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != sha256) {
            dest.delete()
            throw SecurityException("Downloaded Linux image failed its checksum (got $actual).")
        }
    }

    private fun pump(input: InputStream, into: BoundedBuffer) {
        val buf = ByteArray(8 * 1024)
        input.use {
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                into.append(String(buf, 0, n, Charsets.UTF_8))
            }
        }
    }

    /** [pump], also keeping the raw bytes in [keep] up to [MAX_SAVED_BYTES]. True when that cap cut it short. */
    private fun pumpAndKeep(input: InputStream, into: BoundedBuffer, keep: File): Boolean {
        val buf = ByteArray(8 * 1024)
        var kept = 0L
        var capped = false
        keep.outputStream().buffered().use { out ->
            input.use {
                while (true) {
                    val n = it.read(buf)
                    if (n < 0) break
                    into.append(String(buf, 0, n, Charsets.UTF_8))
                    if (kept + n <= MAX_SAVED_BYTES) {
                        out.write(buf, 0, n)
                        kept += n
                    } else {
                        capped = true
                    }
                }
            }
        }
        return capped
    }

    private fun java.lang.Process.isAliveCompat(): Boolean = runCatching { exitValue(); false }.getOrDefault(true)

    private fun pidOf(process: java.lang.Process): Int? =
        runCatching { process.javaClass.getDeclaredField("pid").apply { isAccessible = true }.getInt(process) }.getOrNull()

    /** Keeps the first and last parts of long output, which is where errors and results usually are. */
    private class BoundedBuffer(private val max: Int) {
        private val head = StringBuilder()
        private val tail = StringBuilder()
        var truncated = false
            private set

        @Synchronized
        fun append(s: String) {
            if (head.length < max / 2) {
                val take = minOf(s.length, max / 2 - head.length)
                head.append(s, 0, take)
                if (take < s.length) appendTail(s.substring(take))
            } else {
                appendTail(s)
            }
        }

        private fun appendTail(s: String) {
            tail.append(s)
            if (tail.length > max / 2) {
                truncated = true
                tail.delete(0, tail.length - max / 2)
            }
        }

        @Synchronized
        fun text(): String = if (truncated) "$head\n…[output truncated]…\n$tail" else "$head$tail"
    }

    private enum class Arch(val url: String, val sha256: String) {
        AARCH64(
            "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/alpine-minirootfs-3.24.3-aarch64.tar.gz",
            "4d3f80afaebd447c5591a63a73bde380a149fe8b2d85488cafabb2ff97551f50",
        ),
        X86_64(
            "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/x86_64/alpine-minirootfs-3.24.3-x86_64.tar.gz",
            "f8e1cafba9b1b283b5d30d06f9885c8d2bc161ffe9083ade8e2e157813a53407",
        ),
    }

    // What LinuxProbe (the health screen, docs/design/linux-health.md) reads from the host side.
    internal val appContext: Context get() = context

    /** Everything Linux keeps on the phone (the distro, its /tmp, logs and records), for measuring it; never write into it. */
    val storageDir: File get() = base
    internal val rootfsDir: File get() = rootfs
    internal val guestTmpDir: File get() = tmp
    internal val prootBinary: File get() = proot
    internal val prootLoader: File get() = File(nativeDir, "libproot-loader.so")
    internal val installedMarker: File get() = marker

    /** True when commands run on a [CommandHost] (benchmarks), where there is no distro to inspect. */
    internal val runsOnHost: Boolean get() = host != null

    /** Writes Linux's DNS servers back to the ones Locus installs with (the health screen's Reset DNS). */
    suspend fun resetDns() = withContext(Dispatchers.IO) {
        requireReady()
        writeManaged(rootfs, "etc/resolv.conf", DEFAULT_RESOLV_CONF)
    }

    companion object {
        /** Public resolvers, since Android has no resolv.conf to copy. */
        internal const val DEFAULT_RESOLV_CONF = "nameserver 1.1.1.1\nnameserver 8.8.8.8\n"

        /** The most of one command's output [run] saves to a file. */
        const val MAX_SAVED_BYTES = 8L * 1024 * 1024
        private const val ALPINE_VERSION = "3.24.3"
        private val FAKE_PROC = listOf("stat", "loadavg", "uptime", "vmstat")
    }
}
