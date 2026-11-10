package dev.locus.core.linux

import androidx.annotation.StringRes
import java.util.Locale

/**
 * A snapshot of the Linux workspace: what's installed, what it uses, and whether it works
 * (docs/design/linux-health.md). [LinuxProbe] fills it in; [LinuxHealth.checks] turns it into a
 * verdict per check with a fix the UI can run. Every figure is optional, because a broken install is
 * exactly when some of them can't be read, and "unknown" must not read as "fine".
 */
data class LinuxReport(
    val takenAt: Long,
    val alpineVersion: String? = null,
    val arch: String? = null,
    val runtime: Runtime = Runtime(),
    val roundTrip: RoundTrip? = null,
    val integrity: Integrity = Integrity(),
    val packages: List<LinuxInventory.Package>? = null,
    val storage: Storage? = null,
    val memory: Memory? = null,
    val processes: List<GuestProcess> = emptyList(),
    val services: List<LinuxEnvironment.ServiceStatus> = emptyList(),
    val phantom: Phantom = Phantom(),
    /** Null until a network check ran (it's on demand: it reaches the internet). */
    val network: Network? = null,
    /** Null until the user asked; that needs the network too. */
    val upgrades: List<LinuxInventory.Upgrade>? = null,
    /** The last Linux process Android killed in the background, until the user dismissed it ([LinuxEnvironment.androidKill]). */
    val lastKill: AndroidKillNotice? = null,
) {
    /** proot itself, read from the host side. */
    data class Runtime(val prootPresent: Boolean = true, val prootExecutable: Boolean = true, val loaderPresent: Boolean = true, val prootVersion: String? = null)

    /** A trivial command run through proot: whether Linux runs at all, and how fast it starts. */
    data class RoundTrip(val ok: Boolean, val millis: Long, val timedOut: Boolean = false, val detail: String? = null)

    /** Files without which Linux or apk can't work. [missing] are paths inside Linux (`/bin/sh`). */
    data class Integrity(val missing: List<String> = emptyList(), val apkDbReadable: Boolean = true, val resolvConfOk: Boolean = true)

    /** Bytes. [phoneFree]/[phoneTotal] are the storage Locus's files live on. */
    data class Storage(
        val linuxBytes: Long,
        val phoneFreeBytes: Long,
        val phoneTotalBytes: Long,
        /** The biggest folders inside Linux, largest first (`/usr/lib`, `/root/.npm`). */
        val biggest: List<Usage> = emptyList(),
        /** Caches that are safe to delete (re-downloaded when needed), largest first. */
        val caches: List<Usage> = emptyList(),
        /** Linux's `/tmp`, kept outside the distro by Locus. */
        val tmpBytes: Long = 0,
    ) {
        val cacheBytes: Long get() = caches.sumOf { it.bytes }
    }

    data class Usage(val path: String, val bytes: Long)

    /** The phone's memory, and what Linux's processes hold of it. */
    data class Memory(val totalBytes: Long, val availableBytes: Long, val lowMemory: Boolean = false, val thresholdBytes: Long = 0)

    /** A process Locus started in Linux (proot or a program under it), from the host's /proc. */
    data class GuestProcess(val pid: Int, val name: String, val rssBytes: Long)

    /**
     * Android 12+'s phantom process limit ([AndroidKill]). [monitorOff] is true when the restriction was
     * turned off, false when it's on, null when the phone doesn't let an app read it.
     */
    data class Phantom(val applies: Boolean = false, val monitorOff: Boolean? = null, val developerOptionsOn: Boolean? = null)

    /** DNS and HTTPS from inside Linux, to Alpine's package mirror. */
    data class Network(val dnsOk: Boolean, val httpsOk: Boolean, val millis: Long)

    val guestRssBytes: Long get() = processes.sumOf { it.rssBytes }
}

enum class HealthLevel { Ok, Warning, Problem }

/** What a check's button does; the health screen runs each one. [labelRes] is its button's text. */
enum class LinuxFix(@param:StringRes val labelRes: Int) {
    /** LinuxSetup's repair: rewrites the files Locus manages and checks Linux starts; packages stay. */
    Repair(R.string.linux_fix_repair),

    /** LinuxSetup's reinstall: Alpine from scratch, with Locus's offered tools put back; the workspace stays. */
    Reinstall(R.string.linux_fix_reinstall),
    CleanCaches(R.string.linux_fix_clean_caches),
    PhantomHelp(R.string.linux_fix_phantom_help),
    RestartService(R.string.linux_fix_restart),

    /** Starts a service that isn't running through no fault of its own (a restart ended it). */
    StartService(R.string.linux_fix_start),
    ResetDns(R.string.linux_fix_reset_dns),
    OpenTerminal(R.string.linux_fix_open_terminal),
}

/**
 * One line of the checks list. [title] and [detail] are only shown (never given to the model), so they
 * are resources in the app's language. [target] is what the fix acts on (a service's name).
 */
data class HealthCheck(
    val id: String,
    val title: EngineText,
    val level: HealthLevel,
    val detail: EngineText,
    val fix: LinuxFix? = null,
    val target: String? = null,
)

object LinuxHealth {
    private const val KB = 1024L
    private const val MB = 1024L * KB
    private const val GB = 1024L * MB

    /** Below this much free phone storage, installing anything is likely to fail. */
    const val STORAGE_PROBLEM_BYTES = 500L * MB

    /** Below this, a toolchain (build-base, nodejs, openjdk) may not fit. */
    const val STORAGE_WARNING_BYTES = 2L * GB

    /** Caches worth a nudge. */
    const val CACHE_WARNING_BYTES = 100L * MB

    /** A command that takes longer than this to start makes every tool call slow. */
    const val SLOW_ROUND_TRIP_MS = 3_000L

    /** Android 12+ kills phantom processes past 32 across all apps; Locus alone this close is a warning. */
    const val PROCESS_WARNING = 20

    /** How many missing files a damaged-files check names before "and N more". */
    private const val MISSING_NAMED = 4

    private fun res(id: Int, vararg args: Any) = EngineText.Res(id, *args)

    /** Every check for [report], worst first, OK ones last (they show what was looked at). */
    fun checks(report: LinuxReport): List<HealthCheck> {
        val out = mutableListOf<HealthCheck>()
        val rt = report.runtime
        if (!rt.prootPresent || !rt.prootExecutable || !rt.loaderPresent) {
            val detail = when {
                !rt.prootPresent -> R.string.linux_health_runtime_missing_proot
                !rt.loaderPresent -> R.string.linux_health_runtime_missing_loader
                else -> R.string.linux_health_runtime_not_executable
            }
            out += HealthCheck("runtime", res(R.string.linux_health_runtime_title), HealthLevel.Problem, res(detail))
        }
        report.roundTrip?.let { trip ->
            out += when {
                trip.timedOut -> HealthCheck(
                    "roundtrip", res(R.string.linux_health_roundtrip_timeout_title), HealthLevel.Problem,
                    res(R.string.linux_health_roundtrip_timeout_detail, trip.millis / 1000), LinuxFix.Repair,
                )
                !trip.ok -> HealthCheck(
                    "roundtrip", res(R.string.linux_health_roundtrip_failed_title), HealthLevel.Problem,
                    trip.detail?.let { res(R.string.linux_health_roundtrip_failed_detail_output, it.take(160)) }
                        ?: res(R.string.linux_health_roundtrip_failed_detail),
                    LinuxFix.Repair,
                )
                trip.millis > SLOW_ROUND_TRIP_MS -> HealthCheck(
                    "roundtrip", res(R.string.linux_health_roundtrip_slow_title), HealthLevel.Warning,
                    res(R.string.linux_health_roundtrip_slow_detail, trip.millis / 1000.0),
                )
                else -> HealthCheck("roundtrip", res(R.string.linux_health_roundtrip_ok_title), HealthLevel.Ok, res(R.string.linux_health_roundtrip_ok_detail, trip.millis))
            }
        }
        val integrity = report.integrity
        if (integrity.missing.isNotEmpty() || !integrity.apkDbReadable) {
            val missing = integrity.missing.takeIf { it.isNotEmpty() }?.let { files ->
                val named = files.take(MISSING_NAMED).joinToString(", ")
                if (files.size > MISSING_NAMED) res(R.string.linux_health_files_more, named, files.size - MISSING_NAMED) else EngineText.Plain(named)
            }
            val detail = when {
                missing != null && !integrity.apkDbReadable -> res(R.string.linux_health_files_damaged_missing_and_db, missing)
                missing != null -> res(R.string.linux_health_files_damaged_missing, missing)
                else -> res(R.string.linux_health_files_damaged_db)
            }
            out += HealthCheck("files", res(R.string.linux_health_files_damaged_title), HealthLevel.Problem, detail, LinuxFix.Reinstall)
        } else {
            out += HealthCheck("files", res(R.string.linux_health_files_ok_title), HealthLevel.Ok, res(R.string.linux_health_files_ok_detail))
        }
        report.storage?.let { s ->
            val free = bytesText(s.phoneFreeBytes)
            val caches = bytesText(s.cacheBytes)
            out += when {
                s.phoneFreeBytes < STORAGE_PROBLEM_BYTES -> HealthCheck(
                    "storage", res(R.string.linux_health_storage_full_title), HealthLevel.Problem,
                    if (s.cacheBytes > 0) res(R.string.linux_health_storage_full_detail_caches, free, caches) else res(R.string.linux_health_storage_full_detail, free),
                    LinuxFix.CleanCaches.takeIf { s.cacheBytes > 0 },
                )
                s.phoneFreeBytes < STORAGE_WARNING_BYTES -> HealthCheck(
                    "storage", res(R.string.linux_health_storage_low_title), HealthLevel.Warning,
                    if (s.cacheBytes > 0) res(R.string.linux_health_storage_low_detail_caches, free, caches) else res(R.string.linux_health_storage_low_detail, free),
                    LinuxFix.CleanCaches.takeIf { s.cacheBytes > 0 },
                )
                s.cacheBytes >= CACHE_WARNING_BYTES -> HealthCheck(
                    "storage", res(R.string.linux_health_caches_title, caches), HealthLevel.Warning,
                    res(R.string.linux_health_caches_detail), LinuxFix.CleanCaches,
                )
                else -> HealthCheck(
                    "storage", res(R.string.linux_health_storage_ok_title), HealthLevel.Ok,
                    res(R.string.linux_health_storage_ok_detail, bytesText(s.linuxBytes), free),
                )
            }
        }
        report.memory?.let { m ->
            out += if (isLowMemory(m)) {
                HealthCheck("memory", res(R.string.linux_health_memory_low_title), HealthLevel.Warning, res(R.string.linux_health_memory_low_detail, bytesText(m.availableBytes)))
            } else {
                HealthCheck(
                    "memory", res(R.string.linux_health_memory_ok_title), HealthLevel.Ok,
                    res(R.string.linux_health_memory_ok_detail, bytesText(m.availableBytes), bytesText(m.totalBytes), bytesText(report.guestRssBytes)),
                )
            }
        }
        report.services.forEach { s -> serviceCheck(s)?.let { out += it } }
        val killed = report.services.any { it.killedByAndroid } || report.lastKill != null
        val p = report.phantom
        if (p.applies) {
            out += when {
                p.monitorOff == true -> HealthCheck("phantom", res(R.string.linux_health_phantom_off_title), HealthLevel.Ok, res(R.string.linux_health_phantom_off_detail))
                killed || report.processes.size >= PROCESS_WARNING -> {
                    val limit = res(R.string.linux_health_phantom_warning_detail, report.processes.size)
                    HealthCheck(
                        "phantom", res(R.string.linux_health_phantom_warning_title), HealthLevel.Warning,
                        report.lastKill?.let { res(R.string.linux_health_two_sentences, it.stoppedSentence(), limit) } ?: limit,
                        LinuxFix.PhantomHelp,
                    )
                }
                else -> HealthCheck(
                    "phantom", res(R.string.linux_health_phantom_on_title), HealthLevel.Ok,
                    res(if (p.monitorOff == null) R.string.linux_health_phantom_on_detail_unknown else R.string.linux_health_phantom_on_detail),
                    LinuxFix.PhantomHelp,
                )
            }
        }
        if (!integrity.resolvConfOk) {
            out += HealthCheck("dns-config", res(R.string.linux_health_dns_config_title), HealthLevel.Problem, res(R.string.linux_health_dns_config_detail), LinuxFix.ResetDns)
        }
        report.network?.let { n ->
            out += when {
                !n.dnsOk -> HealthCheck("network", res(R.string.linux_health_network_dns_title), HealthLevel.Problem, res(R.string.linux_health_network_dns_detail), LinuxFix.ResetDns)
                !n.httpsOk -> HealthCheck("network", res(R.string.linux_health_network_https_title), HealthLevel.Warning, res(R.string.linux_health_network_https_detail))
                else -> HealthCheck("network", res(R.string.linux_health_network_ok_title), HealthLevel.Ok, res(R.string.linux_health_network_ok_detail, n.millis))
            }
        }
        report.upgrades?.let { u ->
            out += if (u.isEmpty()) {
                HealthCheck("upgrades", res(R.string.linux_health_upgrades_none_title), HealthLevel.Ok, res(R.string.linux_health_upgrades_none_detail, report.alpineVersion.orEmpty()))
            } else {
                HealthCheck(
                    "upgrades", EngineText.Plural(R.plurals.linux_health_upgrades_title, u.size), HealthLevel.Ok,
                    res(R.string.linux_health_upgrades_detail), LinuxFix.OpenTerminal,
                )
            }
        }
        return out.sortedByDescending { it.level }
    }

    /** "Android stopped a command while Locus was in the background." */
    private fun AndroidKillNotice.stoppedSentence(): EngineText = when (kind) {
        AndroidKillNotice.Kind.Command -> res(R.string.linux_health_phantom_killed_command)
        AndroidKillNotice.Kind.Service -> res(R.string.linux_health_phantom_killed_service, name.orEmpty())
        AndroidKillNotice.Kind.Terminal -> res(R.string.linux_health_phantom_killed_terminal)
        null -> res(R.string.linux_health_phantom_killed_other, what.replaceFirstChar(Char::lowercase))
    }

    /**
     * A service's check, or null when it needs nothing: running, or stopped by a restart of Locus or
     * the phone (or finished cleanly) without being set to start automatically. Those show on the
     * Services list with a Start button, but they aren't problems: services end with Locus.
     */
    fun serviceCheck(s: LinuxEnvironment.ServiceStatus): HealthCheck? {
        val id = "service:${s.name}"
        return when (s.state) {
            LinuxEnvironment.ServiceState.Running -> null
            LinuxEnvironment.ServiceState.Crashed -> HealthCheck(
                id, res(R.string.linux_health_service_crashed_title, s.name), HealthLevel.Problem,
                res(R.string.linux_health_service_crashed_detail, exitText(s.exitCode)), LinuxFix.RestartService, s.name,
            )
            LinuxEnvironment.ServiceState.KilledByAndroid -> HealthCheck(
                id, res(R.string.linux_health_service_killed_title, s.name), HealthLevel.Warning,
                res(R.string.linux_health_service_killed_detail), LinuxFix.RestartService, s.name,
            )
            LinuxEnvironment.ServiceState.StoppedByRestart, LinuxEnvironment.ServiceState.Finished -> if (s.autostart) HealthCheck(
                id, res(R.string.linux_health_service_not_running_title, s.name), HealthLevel.Warning,
                res(R.string.linux_health_service_not_running_detail),
                LinuxFix.StartService, s.name,
            ) else null
        }
    }

    /**
     * "code 1", or "signal 11 (SIGSEGV)" for a process a signal ended (128 + the signal, as java.lang.Process
     * reports it). This English is what the model reads (linux_service_status); screens show [exitText].
     */
    fun exitDescription(code: Int?): String = when {
        code == null -> "an error"
        code > 128 && code - 128 <= 64 -> (code - 128).let { sig -> "signal $sig" + (SIGNALS[sig]?.let { " ($it)" }.orEmpty()) }
        else -> "code $code"
    }

    /** [exitDescription] for the screen, in the app's language. */
    fun exitText(code: Int?): EngineText = when {
        code == null -> res(R.string.linux_exit_error)
        code > 128 && code - 128 <= 64 -> (code - 128).let { sig ->
            SIGNALS[sig]?.let { res(R.string.linux_exit_signal_named, sig, it) } ?: res(R.string.linux_exit_signal, sig)
        }
        else -> res(R.string.linux_exit_code, code)
    }

    private val SIGNALS = mapOf(1 to "SIGHUP", 2 to "SIGINT", 3 to "SIGQUIT", 4 to "SIGILL", 6 to "SIGABRT", 7 to "SIGBUS", 8 to "SIGFPE", 9 to "SIGKILL", 11 to "SIGSEGV", 13 to "SIGPIPE", 15 to "SIGTERM")

    /** Android says memory is low, or under a tenth of it is available. */
    fun isLowMemory(m: LinuxReport.Memory): Boolean =
        m.lowMemory || (m.totalBytes > 0 && m.availableBytes < m.totalBytes / 10)

    /** The worst level among [checks]; [HealthLevel.Ok] when there are none. */
    fun overall(checks: List<HealthCheck>): HealthLevel = checks.maxOfOrNull { it.level } ?: HealthLevel.Ok

    /** The headline: the worst check's title, or that all is well. */
    fun headline(checks: List<HealthCheck>): EngineText {
        val worst = overall(checks)
        if (worst == HealthLevel.Ok) return res(R.string.linux_health_headline_ok)
        val bad = checks.filter { it.level == worst }
        if (bad.size == 1) return bad.single().title
        val more = bad.size - 1
        val plural = if (worst == HealthLevel.Problem) R.plurals.linux_health_headline_more_problems else R.plurals.linux_health_headline_more_warnings
        return EngineText.Plural(plural, more, bad.first().title, more)
    }

    /** 1.2 GB, 340 MB, 12 KB: one decimal only below 10 of a unit. English with a dot, for logs and tests; screens use [bytesText]. */
    fun bytes(b: Long): String {
        val (unit, size) = unit(b)
        if (size == 1L) return "$b B"
        val v = b.toDouble() / size
        return if (v < 10) String.format(Locale.ROOT, "%.1f %s", v, unit) else String.format(Locale.ROOT, "%.0f %s", v, unit)
    }

    /** [bytes] for the screen: the same units, the number written the way the app's language writes it (1,5 KB). */
    fun bytesText(b: Long): EngineText {
        val (unit, size) = unit(b)
        if (size == 1L) return res(R.string.linux_bytes_b, b)
        val v = b.toDouble() / size
        val small = v < 10
        return res(
            when (unit) {
                "KB" -> if (small) R.string.linux_bytes_kb_small else R.string.linux_bytes_kb
                "MB" -> if (small) R.string.linux_bytes_mb_small else R.string.linux_bytes_mb
                "GB" -> if (small) R.string.linux_bytes_gb_small else R.string.linux_bytes_gb
                else -> if (small) R.string.linux_bytes_tb_small else R.string.linux_bytes_tb
            },
            v,
        )
    }

    private fun unit(b: Long): Pair<String, Long> =
        listOf("B" to 1L, "KB" to KB, "MB" to MB, "GB" to GB, "TB" to GB * 1024).last { b >= it.second || it.second == 1L }
}
