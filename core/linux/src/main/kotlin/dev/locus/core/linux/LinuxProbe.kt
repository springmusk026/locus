package dev.locus.core.linux

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Reads the Linux workspace's health and inventory for the health and packages screens
 * (docs/design/linux-health.md). Most of it is read from the host side, without starting Linux:
 * apk's database, the distro's files and sizes, `/proc` for Locus's Linux processes, and the phone's
 * memory and storage, so it still answers when Linux itself is broken. Only the round trip (one
 * `echo`), the network check and the upgrade check start a process in Linux; the last two reach the
 * internet, so they run only when asked. Never installs anything and changes nothing except through
 * [cleanCaches] and [LinuxEnvironment.resetDns], which the user taps.
 */
class LinuxProbe(
    private val linux: LinuxEnvironment,
    private val device: DeviceFacts = AndroidDeviceFacts(linux.appContext),
) {
    /** What the phone says about itself; replaced in tests. */
    interface DeviceFacts {
        fun memory(): LinuxReport.Memory?
        fun storage(): Pair<Long, Long>?
        fun phantom(): LinuxReport.Phantom
        fun processes(): List<LinuxReport.GuestProcess>
    }

    /**
     * Everything that needs no network. Null when Linux isn't installed (the screen shows its install
     * state instead). Cancelling the caller stops the disk walk between files.
     */
    suspend fun report(roundTrip: Boolean = true): LinuxReport? {
        if (!linux.isInstalled || linux.runsOnHost) return null
        val rootfs = linux.rootfsDir
        return withContext(Dispatchers.IO) {
            val world = read(File(rootfs, "etc/apk/world"))?.let(LinuxInventory::parseWorld).orEmpty()
            val db = read(File(rootfs, "lib/apk/db/installed"))
            val packages = db?.let { LinuxInventory.parseInstalledDb(it, world) }
            val arch = read(File(rootfs, "etc/apk/arch"))?.trim()?.takeIf { it.isNotEmpty() }
            val integrity = integrity(rootfs, arch, db, packages)
            val runtime = LinuxReport.Runtime(
                prootPresent = linux.prootBinary.exists(),
                prootExecutable = linux.prootBinary.canExecute(),
                loaderPresent = linux.prootLoader.exists(),
                prootVersion = prootVersion(),
            )
            val (free, total) = device.storage() ?: (0L to 0L)
            val usage = DiskUsage.measure(rootfs, CACHE_DIRS)
            val storage = LinuxReport.Storage(
                linuxBytes = usage.total,
                phoneFreeBytes = free,
                phoneTotalBytes = total,
                biggest = usage.biggest,
                caches = usage.caches,
                tmpBytes = DiskUsage.measure(linux.guestTmpDir, emptyList()).total,
            ).takeIf { total > 0 }
            // Read before the round trip, so its own short-lived proot isn't counted.
            val processes = device.processes()
            LinuxReport(
                takenAt = System.currentTimeMillis(),
                alpineVersion = LinuxInventory.parseOsVersion(read(File(rootfs, "etc/os-release")), read(File(rootfs, "etc/alpine-release"))),
                arch = arch,
                runtime = runtime,
                roundTrip = if (roundTrip && runtime.prootPresent) roundTrip() else null,
                integrity = integrity,
                packages = packages,
                storage = storage,
                memory = device.memory(),
                processes = processes,
                services = linux.serviceStatuses(),
                lastKill = linux.androidKill.value,
                phantom = device.phantom(),
            )
        }
    }

    /** One `echo` through proot: whether Linux runs, and how long a command takes to start. */
    private suspend fun roundTrip(): LinuxReport.RoundTrip {
        val started = System.nanoTime()
        return try {
            val r = linux.run("echo $ROUND_TRIP_MARK", timeoutMs = ROUND_TRIP_TIMEOUT_MS, maxChars = 2_000)
            val ms = (System.nanoTime() - started) / 1_000_000
            LinuxReport.RoundTrip(
                ok = !r.timedOut && r.exitCode == 0 && r.output.contains(ROUND_TRIP_MARK),
                millis = ms,
                timedOut = r.timedOut,
                detail = r.output.trim().takeIf { it.isNotEmpty() && !it.contains(ROUND_TRIP_MARK) } ?: r.exitCode?.let { "exit code $it" },
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            LinuxReport.RoundTrip(ok = false, millis = (System.nanoTime() - started) / 1_000_000, detail = e.message ?: e.toString())
        }
    }

    /**
     * Just the installed packages, from apk's database on the host side (no process, no disk walk), for
     * the packages screen. Null when Linux isn't installed or the database can't be read.
     */
    suspend fun packages(): List<LinuxInventory.Package>? {
        if (!linux.isInstalled || linux.runsOnHost) return null
        val rootfs = linux.rootfsDir
        return withContext(Dispatchers.IO) {
            val world = read(File(rootfs, "etc/apk/world"))?.let(LinuxInventory::parseWorld).orEmpty()
            read(File(rootfs, "lib/apk/db/installed"))?.let { LinuxInventory.parseInstalledDb(it, world) }?.takeIf { it.isNotEmpty() }
        }
    }

    /**
     * `apk del` [name], for a package the user installed (the packages screen's Uninstall, behind a
     * confirmation). Refuses Alpine's own base packages and anything that isn't a plain package name;
     * throws with apk's words when it fails.
     */
    suspend fun uninstall(name: String) {
        require(LinuxInventory.isValidName(name)) { "“$name” isn't a package name." }
        require(name !in LinuxInventory.BASE_SYSTEM) { "$name is part of Alpine itself and can't be removed here." }
        val r = linux.run("apk del $name 2>&1", timeoutMs = 120_000, maxChars = 8_000)
        if (r.timedOut) throw IOException("Removing $name took longer than 2 minutes.")
        if (r.exitCode != 0) throw IOException(r.output.trim().lines().takeLast(3).joinToString(" ").ifBlank { "apk del exited with ${r.exitCode}." })
    }

    /** DNS and HTTPS from inside Linux to Alpine's mirror. Reaches the internet, so only on request. */
    suspend fun network(): LinuxReport.Network {
        val started = System.nanoTime()
        val r = linux.run(NETWORK_SCRIPT, timeoutMs = 25_000, maxChars = 2_000)
        return parseNetwork(r.output, (System.nanoTime() - started) / 1_000_000)
    }

    /**
     * Packages with newer versions: refreshes apk's index (network) and lists them. Throws with apk's
     * own words when the index can't be fetched.
     */
    suspend fun upgrades(): List<LinuxInventory.Upgrade> {
        val r = linux.run(UPGRADES_SCRIPT, timeoutMs = 120_000, maxChars = 60_000)
        if (r.timedOut) throw IOException("Checking for updates took longer than 2 minutes.")
        val marker = r.output.indexOf(UPDATE_FAILED)
        if (marker >= 0) throw IOException("Couldn't refresh the package index: ${r.output.substring(0, marker).trim().lines().takeLast(3).joinToString(" ")}".trim())
        return LinuxInventory.parseUpgrades(r.output)
    }

    /**
     * Deletes the contents of the caches in [CACHE_DIRS] (apk's, pip's, npm's…), all re-downloaded when
     * needed, and returns the bytes freed. Done from the host side so it works when Linux doesn't; a
     * cache folder that is a symlink, or leads out of the distro, is left alone.
     */
    suspend fun cleanCaches(): Long = withContext(Dispatchers.IO) {
        DiskUsage.clean(linux.rootfsDir, CACHE_DIRS)
    }

    private fun integrity(rootfs: File, arch: String?, db: String?, packages: List<LinuxInventory.Package>?): LinuxReport.Integrity {
        val required = REQUIRED_FILES + listOfNotNull(arch?.let { "lib/ld-musl-$it.so.1" })
        val missing = required.filterNot { Files.exists(File(rootfs, it).toPath(), LinkOption.NOFOLLOW_LINKS) }.map { "/$it" }
        return LinuxReport.Integrity(
            missing = missing + listOfNotNull("(install marker)".takeIf { !linux.installedMarker.exists() }),
            apkDbReadable = db != null && !packages.isNullOrEmpty(),
            resolvConfOk = LinuxInventory.hasNameserver(read(File(rootfs, "etc/resolv.conf"))),
        )
    }

    /** proot's `--version`, run directly (it exits before starting anything). Null if it can't say in time. */
    private fun prootVersion(): String? = runCatching {
        val p = ProcessBuilder(linux.prootBinary.path, "--version").redirectErrorStream(true).start()
        p.outputStream.close()
        if (!p.waitFor(3, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            return@runCatching null
        }
        LinuxInventory.parseProotVersion(p.inputStream.bufferedReader().readText().take(4_000))
    }.getOrNull()

    private fun read(file: File): String? = runCatching { if (file.isFile) file.readText() else null }.getOrNull()

    companion object {
        private const val ROUND_TRIP_MARK = "locus-health-ok"
        private const val ROUND_TRIP_TIMEOUT_MS = 20_000L
        private const val UPDATE_FAILED = "@@locus-update-failed"

        /** Paths inside Linux without which commands or apk can't work. */
        internal val REQUIRED_FILES = listOf("bin/busybox", "bin/sh", "etc/alpine-release", "etc/apk/repositories", "lib/apk/db/installed", "usr/bin/env")

        /** Caches that are always safe to empty: each tool downloads again what it needs. */
        val CACHE_DIRS = listOf(
            "var/cache/apk", "root/.cache/pip", "root/.npm/_cacache", "root/.cache/go-build",
            "root/.cache/yarn", "root/.cargo/registry/cache", "root/.gradle/caches",
        )

        /** BusyBox's nslookup and wget, each with its own timeout; prints `dns=<code>` and `https=<code>`. */
        internal const val NETWORK_SCRIPT =
            "timeout 6 nslookup dl-cdn.alpinelinux.org >/dev/null 2>&1; echo dns=\$?; " +
                "timeout 10 wget -q -T 8 --spider https://dl-cdn.alpinelinux.org/alpine/ >/dev/null 2>&1; echo https=\$?"

        internal const val UPGRADES_SCRIPT = "apk update -q 2>&1 || echo $UPDATE_FAILED; apk list -u 2>/dev/null"

        internal fun parseNetwork(output: String, millis: Long): LinuxReport.Network {
            fun code(key: String) = Regex("""(?m)^$key=(\d+)""").find(output)?.groupValues?.get(1)?.toIntOrNull()
            return LinuxReport.Network(dnsOk = code("dns") == 0, httpsOk = code("https") == 0, millis = millis)
        }
    }
}

/** Sizes on disk, walked without following symlinks. Kept free of Android so it's tested on a temp folder. */
internal object DiskUsage {
    data class Result(val total: Long, val biggest: List<LinuxReport.Usage>, val caches: List<LinuxReport.Usage>)

    /**
     * The total size under [root], its biggest folders two levels down (`/usr/lib`, `/root/.npm`), and the
     * size of each of [caches] (paths relative to [root]). One walk; between files it checks the
     * coroutine is still wanted.
     */
    suspend fun measure(root: File, caches: List<String>, top: Int = 6): Result {
        if (!root.isDirectory) return Result(0, emptyList(), emptyList())
        val context = coroutineContext
        val base = root.toPath()
        var total = 0L
        val byDir = HashMap<String, Long>()
        val byCache = HashMap<String, Long>()
        val cachePrefixes = caches.map { it.trimEnd('/') }
        Files.walkFileTree(base, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                context.ensureActive()
                if (!attrs.isRegularFile) return FileVisitResult.CONTINUE
                val size = attrs.size()
                total += size
                val rel = base.relativize(file).toString().replace(File.separatorChar, '/')
                val parts = rel.split('/')
                if (parts.size > 2) byDir.merge("/" + parts.take(2).joinToString("/"), size, Long::plus)
                else if (parts.size == 2) byDir.merge("/" + parts[0], size, Long::plus)
                cachePrefixes.firstOrNull { rel.startsWith("$it/") }?.let { byCache.merge(it, size, Long::plus) }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        })
        return Result(
            total,
            byDir.entries.sortedByDescending { it.value }.take(top).map { LinuxReport.Usage(it.key, it.value) },
            byCache.entries.filter { it.value > 0 }.sortedByDescending { it.value }.map { LinuxReport.Usage("/" + it.key, it.value) },
        )
    }

    /** Empties each of [caches] under [root] (keeping the folder itself) and returns the bytes removed. */
    fun clean(root: File, caches: List<String>): Long {
        val rootReal = runCatching { root.toPath().toRealPath() }.getOrNull() ?: return 0
        var freed = 0L
        caches.forEach { rel ->
            val dir = root.toPath().resolve(rel)
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return@forEach
            // A symlink anywhere on the way (root/.cache → /workspace/...) would lead elsewhere: skip it.
            val real = runCatching { dir.toRealPath() }.getOrNull() ?: return@forEach
            if (real != rootReal.resolve(rel).normalize() || !real.startsWith(rootReal)) return@forEach
            Files.walk(dir).use { paths ->
                paths.sorted(Comparator.reverseOrder()).filter { it != dir }.forEach { p ->
                    runCatching {
                        val attrs = Files.readAttributes(p, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                        Files.delete(p)
                        if (attrs.isRegularFile) freed += attrs.size()
                    }
                }
            }
        }
        return freed
    }
}

/** The phone's side of the report, from Android's APIs and the host's /proc. */
internal class AndroidDeviceFacts(private val context: Context) : LinuxProbe.DeviceFacts {
    override fun memory(): LinuxReport.Memory? {
        val am = context.getSystemService(ActivityManager::class.java)
        val info = am?.let { ActivityManager.MemoryInfo().also(it::getMemoryInfo) }
        if (info != null && info.totalMem > 0) return LinuxReport.Memory(info.totalMem, info.availMem, info.lowMemory, info.threshold)
        val m = runCatching { LinuxInventory.parseMeminfo(File("/proc/meminfo").readText()) }.getOrNull() ?: return null
        return LinuxReport.Memory(m.totalBytes, m.availableBytes)
    }

    override fun storage(): Pair<Long, Long>? = runCatching {
        val fs = StatFs(context.filesDir.path)
        fs.availableBytes to fs.totalBytes
    }.getOrNull()

    /**
     * Android 12L+ reads the phantom process monitor's flag from global settings first, then from the
     * developer option's system property (agnostic-apollo's Android-Docs, "phantom processes"). Either
     * may be closed to apps; then it's unknown rather than guessed.
     */
    override fun phantom(): LinuxReport.Phantom {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return LinuxReport.Phantom(applies = false)
        val resolver = context.contentResolver
        val setting = runCatching { Settings.Global.getString(resolver, PHANTOM_FLAG) }.getOrNull()
            ?: getprop("persist.sys.fflag.override.$PHANTOM_FLAG")
        val monitorOff = when (setting?.trim()?.lowercase()) {
            "false", "0" -> true
            "true", "1" -> false
            else -> null
        }
        val devOptions = runCatching { Settings.Global.getInt(resolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED) == 1 }.getOrNull()
        return LinuxReport.Phantom(applies = true, monitorOff = monitorOff, developerOptionsOn = devOptions)
    }

    override fun processes(): List<LinuxReport.GuestProcess> =
        GuestProcesses.list(File("/proc"), android.os.Process.myUid(), context.packageName)

    private fun getprop(key: String): String? = runCatching {
        val p = ProcessBuilder("/system/bin/getprop", key).redirectErrorStream(true).start()
        if (!p.waitFor(2, TimeUnit.SECONDS)) { p.destroyForcibly(); return@runCatching null }
        p.inputStream.bufferedReader().readText().trim().takeIf { it.isNotEmpty() }
    }.getOrNull()

    private companion object {
        const val PHANTOM_FLAG = "settings_enable_monitor_phantom_procs"
    }
}

/**
 * Locus's Linux processes, read from the host's /proc: every process of the app's [uid] that isn't
 * one of Locus's own (the same test as [AppProcesses]). These are what Android counts against the
 * phantom process limit.
 */
internal object GuestProcesses {
    fun list(procRoot: File, uid: Int, packageName: String): List<LinuxReport.GuestProcess> =
        procRoot.listFiles().orEmpty().mapNotNull { dir ->
            val pid = dir.name.toIntOrNull() ?: return@mapNotNull null
            val status = runCatching { File(dir, "status").readText() }.getOrNull() ?: return@mapNotNull null
            val owner = status.lineSequence().firstOrNull { it.startsWith("Uid:") }?.removePrefix("Uid:")?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toIntOrNull()
            if (owner != uid) return@mapNotNull null
            val cmdline = runCatching { String(File(dir, "cmdline").readBytes(), Charsets.UTF_8) }.getOrNull().orEmpty()
            val first = cmdline.substringBefore('\u0000')
            if (first.isEmpty() || first == packageName || first.startsWith("$packageName:")) return@mapNotNull null
            LinuxReport.GuestProcess(pid, LinuxInventory.processName(cmdline), LinuxInventory.parseRss(status) ?: 0)
        }.sortedByDescending { it.rssBytes }
}
