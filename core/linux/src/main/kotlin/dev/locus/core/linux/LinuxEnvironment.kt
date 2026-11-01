package dev.locus.core.linux

import android.content.Context
import android.os.Build
import android.os.Process
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
    data class Installing(val step: String) : LinuxStatus
    data object Ready : LinuxStatus
    data class Unavailable(val reason: String) : LinuxStatus
    data class Failed(val message: String) : LinuxStatus
}

/**
 * A command needed Linux, but it isn't installed (Locus never downloads it without the user asking)
 * or can't run on this phone ([unavailableReason]), so a program that needs it can't start.
 */
class LinuxNotInstalledException(val unavailableReason: String? = null) :
    IllegalStateException(unavailableReason ?: "Locus's Linux system isn't installed.")

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
     */
    suspend fun ensureInstalled() = installLock.withLock {
        if (_status.value == LinuxStatus.Ready) return@withLock
        (_status.value as? LinuxStatus.Unavailable)?.let { throw IllegalStateException(it.reason) }
        withContext(Dispatchers.IO) {
            try {
                val arch = when (Build.SUPPORTED_ABIS.firstOrNull()) {
                    "arm64-v8a" -> Arch.AARCH64
                    "x86_64" -> Arch.X86_64
                    else -> throw IllegalStateException("Unsupported CPU: ${Build.SUPPORTED_ABIS.joinToString()}")
                }
                _status.value = LinuxStatus.Installing("Downloading Alpine Linux $ALPINE_VERSION")
                val archive = File(base, "rootfs.tar.gz")
                base.mkdirs()
                download(arch.url, archive, arch.sha256)

                _status.value = LinuxStatus.Installing("Unpacking")
                rootfs.deleteRecursively()
                rootfs.mkdirs()
                GZIPInputStream(archive.inputStream().buffered()).use { Tar.extract(it, rootfs) }
                archive.delete()

                configure()
                marker.writeText(ALPINE_VERSION)
                _status.value = LinuxStatus.Ready
            } catch (e: Exception) {
                _status.value = LinuxStatus.Failed(e.message ?: e.toString())
                throw e
            }
        }
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
            services.keys.toList().forEach { name -> services.remove(name)?.let { stop(it.process) } }
            piped.toList().forEach { stopPiped(it) }
            records.clear()
            base.deleteRecursively()
        }
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
                val process = ProcessBuilder(prootArgs(cwd) + listOf("/bin/sh", "-c", command))
                    .redirectErrorStream(true)
                    .apply { environment().apply { clear(); putAll(prootEnvironment()) } }
                    .start()
                process.outputStream.close()
                val output = BoundedBuffer(maxChars)
                coroutineScope {
                    val reader = async(Dispatchers.IO) { spool?.let { pumpAndKeep(process.inputStream, output, it) } ?: pump(process.inputStream, output) }
                    val exited = withTimeoutOrNull(timeoutMs) {
                        while (process.isAliveCompat()) kotlinx.coroutines.delay(100)
                        true
                    } ?: false
                    if (!exited) stop(process)
                    val capped = reader.await() == true
                    val result = CommandResult(if (exited) process.exitValue() else null, redactor.scrub(output.text()), timedOut = !exited, truncated = output.truncated)
                    if (spool == null || saveFullOutputTo == null || !output.truncated) return@coroutineScope result
                    saveFullOutputTo.parentFile?.mkdirs()
                    saveFullOutputTo.writeText(redactor.scrub(spool.readText()))
                    result.copy(savedTo = saveFullOutputTo, savedBytes = saveFullOutputTo.length(), savedCapped = capped)
                }
            } finally {
                spool?.delete()
            }
        }
    }

    /** A long-running process started with [startService], e.g. a dev server. */
    class Service(val name: String, val command: String, val process: java.lang.Process, val log: File, val startedAt: Long)

    private val services = java.util.concurrent.ConcurrentHashMap<String, Service>()
    private val records = ServiceRecords(File(base, "services.json"))

    /**
     * A service as the agent should see it: running, exited, or [lostOnRestart] (started before Locus
     * last restarted, which ended it; see [ServiceRecords]).
     */
    data class ServiceStatus(val name: String, val command: String, val running: Boolean, val lostOnRestart: Boolean, val startedAt: Long)

    /** Every service started since it was last stopped, including ones a restart of Locus ended. */
    fun serviceStatuses(): List<ServiceStatus> {
        val live = services.values.map { ServiceStatus(it.name, it.command, isAlive(it), lostOnRestart = false, it.startedAt) }
        val lost = records.all().filter { !services.containsKey(it.name) }.map { ServiceStatus(it.name, it.command, running = false, lostOnRestart = true, it.startedAt) }
        return (live + lost).sortedBy { it.startedAt }
    }

    /**
     * Starts [command] in the background and keeps it running until [stopService] or until Locus
     * exits. Output goes to a log file; use [serviceLog] to read it.
     */
    suspend fun startService(name: String, command: String, cwd: String = "/workspace"): Service {
        requireReady()
        return withContext(Dispatchers.IO) {
            linkSharedLibraries()
            tmp.mkdirs()
            workspace.mkdirs()
            services.remove(name)?.let { stop(it.process) }
            val log = File(base, "logs/$name.log").apply { parentFile?.mkdirs(); delete() }
            val process = ProcessBuilder(prootArgs(cwd) + listOf("/bin/sh", "-c", command))
                .redirectErrorStream(true)
                .redirectOutput(log)
                .apply { environment().apply { clear(); putAll(prootEnvironment()) } }
                .start()
            process.outputStream.close()
            Service(name, command, process, log, System.currentTimeMillis()).also {
                services[name] = it
                records.add(ServiceRecords.Record(name, command, cwd, it.startedAt))
            }
        }
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
        withContext(Dispatchers.IO) { stop(service.process) }
        return true
    }

    /** Secret values given to piped programs, scrubbed from command output and service logs. */
    val redactor = OutputRedactor()

    private val piped: MutableSet<java.lang.Process> = java.util.concurrent.ConcurrentHashMap.newKeySet()

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
            ProcessBuilder(prootArgs(cwd) + listOf("/bin/sh", "-c", command))
                .apply { environment().apply { clear(); putAll(extraEnv.filterKeys { it !in base }); putAll(base) } }
                .start()
                .also { piped += it }
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
        }
    }

    private fun prootArgs(cwd: String): List<String> = buildList {
        add(proot.path)
        add("--kill-on-exit")
        add("-0")
        add("--link2symlink")
        add("-L")
        add("--sysvipc")
        add("-r"); add(rootfs.path)
        listOf("/dev", "/proc", "/sys").forEach { add("-b"); add(it) }
        add("-b"); add("/dev/urandom:/dev/random")
        add("-b"); add("/proc/self/fd:/dev/fd")
        FAKE_PROC.forEach { name -> add("-b"); add("${File(fakes, name).path}:/proc/$name") }
        add("-b"); add("${tmp.path}:/tmp")
        add("-b"); add("${workspace.path}:/workspace")
        add("-w"); add(cwd)
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
        val shipped = File(nativeDir, "libtalloc.so")
        if (!shipped.exists()) return
        val alias = File(libDir, "libtalloc.so.2")
        if (alias.exists() && alias.length() == shipped.length()) return
        libDir.mkdirs()
        shipped.copyTo(alias, overwrite = true)
    }

    private fun configure() {
        File(rootfs, "etc/resolv.conf").writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        File(rootfs, "etc/hosts").writeText("127.0.0.1 localhost\n::1 localhost\n")
        File(rootfs, "root").mkdirs()
        File(rootfs, "workspace").mkdirs()
        File(rootfs, "etc/profile.d").mkdirs()
        File(rootfs, "etc/motd").writeText("")
        fakes.mkdirs()
        FAKE_PROC.forEach { name ->
            context.assets.open("proc-fakes/$name").use { input -> File(fakes, name).outputStream().use { input.copyTo(it) } }
        }
    }

    private fun download(url: String, dest: File, sha256: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        URL(url).openStream().use { input ->
            dest.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                    out.write(buf, 0, n)
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

    companion object {
        /** The most of one command's output [run] saves to a file. */
        const val MAX_SAVED_BYTES = 8L * 1024 * 1024
        private const val ALPINE_VERSION = "3.24.3"
        private val FAKE_PROC = listOf("stat", "loadavg", "uptime", "vmstat")
    }
}
