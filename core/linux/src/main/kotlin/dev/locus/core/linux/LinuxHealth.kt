package dev.locus.core.linux

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
    /** Null until the user (or the agent) asked; that needs the network too. */
    val upgrades: List<LinuxInventory.Upgrade>? = null,
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

/** What a check's button does. The screen runs each one; the agent is only told about them. */
enum class LinuxFix(val label: String) {
    /** WP-5a's repair: reconfigure Linux without losing packages (docs/design/linux-health.md#fixes). */
    Repair("Repair"),
    CleanCaches("Clean caches"),
    PhantomHelp("How to fix"),
    RestartService("Restart"),
    ResetDns("Reset DNS"),
    OpenTerminal("Open terminal"),
}

/** One line of the checks list. [target] is what the fix acts on (a service's name). */
data class HealthCheck(
    val id: String,
    val title: String,
    val level: HealthLevel,
    val detail: String,
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

    /** Every check for [report], worst first, OK ones last (they show what was looked at). */
    fun checks(report: LinuxReport): List<HealthCheck> {
        val out = mutableListOf<HealthCheck>()
        val rt = report.runtime
        when {
            !rt.prootPresent || !rt.prootExecutable || !rt.loaderPresent -> out += HealthCheck(
                "runtime", "Linux runtime is missing", HealthLevel.Problem,
                "This copy of Locus can't run its Linux runtime (proot${if (!rt.prootPresent) " isn't there" else if (!rt.loaderPresent) "'s loader isn't there" else " can't be executed"}). " +
                    "Reinstall Locus from the same source.",
            )
            else -> Unit
        }
        report.roundTrip?.let { trip ->
            out += when {
                trip.timedOut -> HealthCheck("roundtrip", "Linux doesn't respond", HealthLevel.Problem, "A test command didn't finish in ${trip.millis / 1000} s. Repair rewrites Linux's settings without touching your packages.", LinuxFix.Repair)
                !trip.ok -> HealthCheck("roundtrip", "Linux can't run commands", HealthLevel.Problem, "A test command failed${trip.detail?.let { ": ${it.take(160)}" }.orEmpty()}. Repair rewrites Linux's settings without touching your packages.", LinuxFix.Repair)
                trip.millis > SLOW_ROUND_TRIP_MS -> HealthCheck("roundtrip", "Commands are slow to start", HealthLevel.Warning, "A test command took ${seconds(trip.millis)}. The phone may be busy, hot or in battery saver.")
                else -> HealthCheck("roundtrip", "Runs commands", HealthLevel.Ok, "A test command took ${trip.millis} ms.")
            }
        }
        val integrity = report.integrity
        if (integrity.missing.isNotEmpty() || !integrity.apkDbReadable) {
            val what = buildList {
                if (integrity.missing.isNotEmpty()) add("missing ${integrity.missing.take(4).joinToString(", ")}${if (integrity.missing.size > 4) " and ${integrity.missing.size - 4} more" else ""}")
                if (!integrity.apkDbReadable) add("the package database can't be read")
            }.joinToString("; ")
            out += HealthCheck("files", "Linux's system files are damaged", HealthLevel.Problem, "Linux is $what. Repair puts them back.", LinuxFix.Repair)
        } else {
            out += HealthCheck("files", "System files intact", HealthLevel.Ok, "Key files and the package database are in place.")
        }
        report.storage?.let { s ->
            out += when {
                s.phoneFreeBytes < STORAGE_PROBLEM_BYTES -> HealthCheck(
                    "storage", "Phone storage is almost full", HealthLevel.Problem,
                    "${bytes(s.phoneFreeBytes)} free. Installing packages will fail.${cacheHint(s)}",
                    LinuxFix.CleanCaches.takeIf { s.cacheBytes > 0 },
                )
                s.phoneFreeBytes < STORAGE_WARNING_BYTES -> HealthCheck(
                    "storage", "Phone storage is low", HealthLevel.Warning,
                    "${bytes(s.phoneFreeBytes)} free. Larger packages (compilers, Java, Node) may not fit.${cacheHint(s)}",
                    LinuxFix.CleanCaches.takeIf { s.cacheBytes > 0 },
                )
                s.cacheBytes >= CACHE_WARNING_BYTES -> HealthCheck(
                    "storage", "Caches use ${bytes(s.cacheBytes)}", HealthLevel.Warning,
                    "Package and download caches can be deleted; they're downloaded again when needed.", LinuxFix.CleanCaches,
                )
                else -> HealthCheck("storage", "Enough storage", HealthLevel.Ok, "Linux uses ${bytes(s.linuxBytes)}; ${bytes(s.phoneFreeBytes)} free on the phone.")
            }
        }
        report.memory?.let { m ->
            val lowShare = m.totalBytes > 0 && m.availableBytes < m.totalBytes / 10
            out += if (m.lowMemory || lowShare) {
                HealthCheck("memory", "Phone is low on memory", HealthLevel.Warning, "${bytes(m.availableBytes)} available. Android may stop Linux programs, especially while Locus is in the background.")
            } else {
                HealthCheck("memory", "Enough memory", HealthLevel.Ok, "${bytes(m.availableBytes)} of ${bytes(m.totalBytes)} available; Linux programs use ${bytes(report.guestRssBytes)}.")
            }
        }
        report.services.forEach { s ->
            when {
                s.killedByAndroid -> out += HealthCheck("service:${s.name}", "Android stopped “${s.name}”", HealthLevel.Warning, "It was killed while Locus was in the background (phantom process limit).", LinuxFix.RestartService, s.name)
                s.lostOnRestart -> out += HealthCheck("service:${s.name}", "“${s.name}” stopped when Locus restarted", HealthLevel.Warning, "Services end with Locus; start it again to bring it back.", LinuxFix.RestartService, s.name)
                !s.running -> out += HealthCheck("service:${s.name}", "“${s.name}” exited", HealthLevel.Warning, "Its log may say why.", LinuxFix.RestartService, s.name)
            }
        }
        val killed = report.services.any { it.killedByAndroid }
        val p = report.phantom
        if (p.applies) {
            out += when {
                p.monitorOff == true -> HealthCheck("phantom", "Background limit is off", HealthLevel.Ok, "Child process restrictions are turned off, so Android won't stop Linux for that reason.")
                killed || report.processes.size >= PROCESS_WARNING -> HealthCheck(
                    "phantom", "Android may stop Linux in the background", HealthLevel.Warning,
                    "Android 12+ stops apps' extra processes (${report.processes.size} running for Locus now). You can turn this off.", LinuxFix.PhantomHelp,
                )
                else -> HealthCheck(
                    "phantom", "Background limit is on", HealthLevel.Ok,
                    "Android 12+ may stop Linux programs while Locus is in the background${if (p.monitorOff == null) " (this phone doesn't let Locus check the setting)" else ""}.", LinuxFix.PhantomHelp,
                )
            }
        }
        if (!integrity.resolvConfOk) {
            out += HealthCheck("dns-config", "No DNS server set", HealthLevel.Problem, "Linux's /etc/resolv.conf names no nameserver, so it can't reach the internet.", LinuxFix.ResetDns)
        }
        report.network?.let { n ->
            out += when {
                !n.dnsOk -> HealthCheck("network", "Linux can't look up names", HealthLevel.Problem, "DNS failed inside Linux. If the phone is online, reset Linux's DNS servers.", LinuxFix.ResetDns)
                !n.httpsOk -> HealthCheck("network", "Can't reach Alpine's mirror", HealthLevel.Warning, "Names resolve but dl-cdn.alpinelinux.org didn't answer over HTTPS. A VPN, captive portal or firewall may block it.")
                else -> HealthCheck("network", "Internet works", HealthLevel.Ok, "DNS and HTTPS to Alpine's mirror in ${n.millis} ms.")
            }
        }
        report.upgrades?.let { u ->
            out += if (u.isEmpty()) HealthCheck("upgrades", "Packages up to date", HealthLevel.Ok, "No newer versions in Alpine ${report.alpineVersion ?: ""}'s repositories.".replace("  ", " "))
            else HealthCheck("upgrades", "${u.size} update${if (u.size == 1) "" else "s"} available", HealthLevel.Ok, "Run `apk upgrade` in the terminal to install them.", LinuxFix.OpenTerminal)
        }
        return out.sortedByDescending { it.level }
    }

    /** The worst level among [checks]; [HealthLevel.Ok] when there are none. */
    fun overall(checks: List<HealthCheck>): HealthLevel = checks.maxOfOrNull { it.level } ?: HealthLevel.Ok

    /** The headline: the worst check's title, or that all is well. */
    fun headline(checks: List<HealthCheck>): String {
        val worst = overall(checks)
        if (worst == HealthLevel.Ok) return "Linux is working"
        val bad = checks.filter { it.level == worst }
        return if (bad.size == 1) bad.single().title else "${bad.first().title} (+${bad.size - 1} more)"
    }

    /**
     * What `linux_info` returns: a few lines the agent can act on, with no ids or decoration. Package
     * lists are left to [packageLines] so the summary stays small.
     */
    fun summary(report: LinuxReport, checks: List<HealthCheck> = checks(report)): String = buildString {
        val level = overall(checks)
        append("Linux: Alpine ${report.alpineVersion ?: "?"}${report.arch?.let { " $it" }.orEmpty()} under proot${report.runtime.prootVersion?.let { " $it" }.orEmpty()}")
        report.roundTrip?.let { append(if (it.ok) ", runs commands (${it.millis} ms to start)" else ", NOT running commands") }
        appendLine(".")
        append("Health: ${level.name}")
        val notOk = checks.filter { it.level != HealthLevel.Ok }
        if (notOk.isNotEmpty()) append(" — " + notOk.joinToString("; ") { "${it.title}: ${it.detail}" })
        appendLine()
        report.storage?.let { s ->
            append("Storage: Linux uses ${bytes(s.linuxBytes)}")
            if (s.cacheBytes > 0) append(" (caches ${bytes(s.cacheBytes)})")
            appendLine("; phone has ${bytes(s.phoneFreeBytes)} free of ${bytes(s.phoneTotalBytes)}.")
        }
        report.memory?.let { m ->
            appendLine("Memory: phone has ${bytes(m.availableBytes)} available of ${bytes(m.totalBytes)}; ${report.processes.size} Linux process${if (report.processes.size == 1) "" else "es"} use ${bytes(report.guestRssBytes)}.")
        }
        report.packages?.let { pkgs ->
            val explicit = pkgs.filter { it.explicit }
            append("Packages: ${pkgs.size} installed")
            if (explicit.isNotEmpty()) append("; asked for: " + explicit.joinToString(", ") { it.name })
            appendLine(".")
        }
        if (report.services.isNotEmpty()) {
            appendLine("Services: " + report.services.joinToString("; ") { s ->
                "${s.name} " + when {
                    s.running -> "running"
                    s.killedByAndroid -> "killed by Android"
                    s.lostOnRestart -> "stopped (Locus restarted)"
                    else -> "exited"
                }
            } + ".")
        }
        report.network?.let { appendLine("Network: DNS ${if (it.dnsOk) "ok" else "failing"}, HTTPS to the package mirror ${if (it.httpsOk) "ok" else "failing"}.") }
        report.upgrades?.let { u -> appendLine(if (u.isEmpty()) "Upgrades: none." else "Upgrades: ${u.size} (" + u.take(10).joinToString(", ") { "${it.name} ${it.installed}→${it.available}" } + if (u.size > 10) ", …)." else ").") }
    }.trimEnd()

    /**
     * Each of [names] as installed (with its version) or not, for the agent to check before `apk add`.
     * A name matches a package exactly, or a command it may provide through the common `py3-`
     * prefix (asking for `pip` finds `py3-pip`).
     */
    fun packageLines(packages: List<LinuxInventory.Package>, names: List<String>): String {
        val byName = packages.associateBy { it.name.lowercase() }
        return names.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("\n") { raw ->
            val n = raw.lowercase()
            val hit = byName[n] ?: byName["py3-$n"]
            if (hit != null) "${raw}: installed (${hit.name} ${hit.version})" else "${raw}: not installed"
        }
    }

    private fun cacheHint(s: LinuxReport.Storage) = if (s.cacheBytes > 0) " Caches in Linux use ${bytes(s.cacheBytes)}." else ""

    private fun seconds(ms: Long) = String.format(Locale.ROOT, "%.1f s", ms / 1000.0)

    /** 1.2 GB, 340 MB, 12 KB: one decimal only below 10 of a unit. */
    fun bytes(b: Long): String {
        val units = listOf("B" to 1L, "KB" to KB, "MB" to MB, "GB" to GB, "TB" to GB * 1024)
        val (unit, size) = units.last { b >= it.second || it.second == 1L }
        if (size == 1L) return "$b B"
        val v = b.toDouble() / size
        return if (v < 10) String.format(Locale.ROOT, "%.1f %s", v, unit) else String.format(Locale.ROOT, "%.0f %s", v, unit)
    }

}
