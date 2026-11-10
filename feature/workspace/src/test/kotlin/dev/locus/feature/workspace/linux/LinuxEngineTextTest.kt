package dev.locus.feature.workspace.linux

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import dev.locus.core.data.device.LinuxHeatAdvice
import dev.locus.core.data.linux.InstallEstimate
import dev.locus.core.data.linux.LinuxPackage
import dev.locus.core.linux.AndroidKill
import dev.locus.core.linux.AndroidKillNotice
import dev.locus.core.linux.HealthLevel
import dev.locus.core.linux.LinuxEnvironment
import dev.locus.core.linux.LinuxFix
import dev.locus.core.linux.LinuxHealth
import dev.locus.core.linux.LinuxInventory
import dev.locus.core.linux.LinuxReport
import dev.locus.core.linux.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.assertEquals

/**
 * The Linux text the workspace shows from core/linux and core/data, rendered from resources in English,
 * reads exactly as the English those modules wrote before it moved into resources
 * (docs/design/localization.md, "Text from the engine"). [OldEnglish] is that code, kept here as the
 * reference.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LinuxEngineTextTest {
    private val res: Resources get() = ApplicationProvider.getApplicationContext<Context>().resources
    private val gb = 1024L * 1024 * 1024
    private val mb = 1024L * 1024

    @Test
    fun `every fix has a label, the same words as before`() {
        val before = mapOf(
            LinuxFix.Repair to "Repair", LinuxFix.Reinstall to "Reinstall", LinuxFix.CleanCaches to "Clean caches",
            LinuxFix.PhantomHelp to "How to fix", LinuxFix.RestartService to "Restart", LinuxFix.StartService to "Start",
            LinuxFix.ResetDns to "Reset DNS", LinuxFix.OpenTerminal to "Open terminal",
        )
        assertEquals(LinuxFix.entries.toSet(), before.keys)
        LinuxFix.entries.forEach { assertEquals(before[it], res.getString(it.labelRes)) }
    }

    @Test
    fun `every check and headline reads as before`() {
        val reports = reports()
        reports.forEach { report ->
            val now = LinuxHealth.checks(report)
            val old = OldEnglish.checks(report)
            assertEquals(old.map { it.id }, now.map { it.id }, "$report")
            now.zip(old).forEach { (n, o) ->
                assertEquals(o.title, n.title.asString(res), "${n.id} title")
                assertEquals(o.detail, n.detail.asString(res), "${n.id} detail")
                assertEquals(o.fix, n.fix?.let { res.getString(it.labelRes) }, "${n.id} fix")
            }
            assertEquals(OldEnglish.headline(old), LinuxHealth.headline(now).asString(res), "headline of $report")
        }
        // The lines the old tests checked, word for word.
        val crashed = healthy().copy(services = listOf(service(exitCode = 1)))
        assertEquals("“api” crashed", LinuxHealth.headline(LinuxHealth.checks(crashed)).asString(res))
        val broken = healthy().copy(
            roundTrip = LinuxReport.RoundTrip(ok = false, millis = 40, detail = "proot error"),
            integrity = LinuxReport.Integrity(missing = listOf("/bin/sh"), apkDbReadable = false),
        )
        assertEquals("Linux can't run commands, and 1 more problem", LinuxHealth.headline(LinuxHealth.checks(broken)).asString(res))
        assertEquals("Linux is working", LinuxHealth.headline(LinuxHealth.checks(healthy())).asString(res))
    }

    @Test
    fun `exit codes and sizes read as before`() {
        (listOf<Int?>(null) + (-1..300)).forEach { assertEquals(LinuxHealth.exitDescription(it), LinuxHealth.exitText(it).asString(res), "$it") }
        val sizes = listOf(0L, 1, 1023, 1024, 1536, 10 * 1024 - 1, 10 * 1024, 999 * 1024, mb, 9 * mb + 512 * 1024, 400 * mb, 1023 * mb, gb, 2 * gb, 15 * gb, 1024 * gb, 12 * 1024 * gb)
        sizes.forEach { assertEquals(LinuxHealth.bytes(it), LinuxHealth.bytesText(it).asString(res), "$it") }
    }

    @Test
    fun `sizes write the number the way the language does`() {
        val spanish = ApplicationProvider.getApplicationContext<Context>().let { c ->
            c.createConfigurationContext(android.content.res.Configuration(c.resources.configuration).apply { setLocale(Locale.forLanguageTag("es")) }).resources
        }
        assertEquals("1,5 KB", LinuxHealth.bytesText(1536).asString(spanish))
        assertEquals("400 MB", LinuxHealth.bytesText(400 * mb).asString(spanish))
    }

    @Test
    fun `Android's kill and Linux's status read as the English the model gets`() {
        assertEquals(AndroidKill.REASON, AndroidKill.reasonText.asString(res))
        assertEquals(AndroidKill.FIX, AndroidKill.fixText.asString(res))
        assertEquals(AndroidKill.FIX_LEAD, AndroidKill.fixLeadText.asString(res))
        assertEquals("A command", AndroidKillNotice("A command", 0, AndroidKillNotice.Kind.Command).whatText.asString(res))
        assertEquals("Service web", AndroidKillNotice("Service web", 0, AndroidKillNotice.Kind.Service, "web").whatText.asString(res))
        assertEquals("A terminal", AndroidKillNotice("A terminal", 0, AndroidKillNotice.Kind.Terminal).whatText.asString(res))
        assertEquals("Downloading Alpine Linux 3.24.3", dev.locus.core.linux.EngineText.Res(R.string.linux_status_downloading, "3.24.3").asString(res))
        assertEquals("Unpacking", res.getString(R.string.linux_status_unpacking))
        assertEquals("This build doesn't include the Linux runtime for armeabi-v7a.", res.getString(R.string.linux_status_no_runtime, "armeabi-v7a"))
    }

    @Test
    fun `setup, packages and the heat banner read as before`() {
        val notes = mapOf(LinuxPackage.Python to "python3 and pip", LinuxPackage.Git to "version control", LinuxPackage.Node to "node and npm", LinuxPackage.Curl to "HTTP from the shell")
        assertEquals(LinuxPackage.entries.toSet(), notes.keys)
        LinuxPackage.entries.forEach { assertEquals(notes[it], res.getString(it.noteRes)) }
        assertEquals("About 4 MB to download, 9 MB on the phone. Usually under a minute on Wi-Fi.", InstallEstimate(4, 9, 1).summary.asString(res))
        assertEquals("About 20 MB to download, 54 MB on the phone. Usually about 2 minutes on Wi-Fi.", InstallEstimate(20, 54, 2).summary.asString(res))
        (1..6).forEach { n ->
            val names = (1..n).map { "svc$it" }
            assertEquals(LinuxHeatAdvice.message(names), LinuxHeatAdvice.messageText(names).asString(res))
        }
        fun s(id: Int, vararg args: Any) = dev.locus.core.linux.EngineText.Res(id, *args).asString(res)
        assertEquals("Installed Python, Git.", s(dev.locus.core.data.R.string.data_linux_installed, "Python, Git"))
        assertEquals("Installing Git (2 of 2)…", s(dev.locus.core.data.R.string.data_linux_phase_installing_package, "Git", 2, 2))
        assertEquals("1 of 2 couldn't be installed. See below; you can retry.", s(dev.locus.core.data.R.string.data_linux_packages_failed, 1, 2))
        assertEquals("Repaired. Alpine Linux 3.24.3 starts normally.", s(dev.locus.core.data.R.string.data_linux_repaired, "3.24.3"))
        assertEquals(
            "Couldn't reach Alpine's package servers. Check the internet connection and try again.\nfetch failed",
            s(dev.locus.core.data.R.string.data_linux_with_output, dev.locus.core.linux.EngineText.Res(dev.locus.core.data.R.string.data_linux_update_failed, dev.locus.core.linux.EngineText.Res(dev.locus.core.data.R.string.data_linux_update_unreachable)), "fetch failed"),
        )
        assertEquals("apk exited with 1", s(dev.locus.core.data.R.string.data_linux_package_exit, "1"))
    }

    // ---- Reports that reach every branch of the checks ----------------------------------------

    private fun healthy() = LinuxReport(
        takenAt = 0,
        alpineVersion = "3.24.3",
        runtime = LinuxReport.Runtime(prootVersion = "5.4.0"),
        roundTrip = LinuxReport.RoundTrip(ok = true, millis = 180),
        storage = LinuxReport.Storage(linuxBytes = 400 * mb, phoneFreeBytes = 40 * gb, phoneTotalBytes = 128 * gb),
        memory = LinuxReport.Memory(totalBytes = 8 * gb, availableBytes = 3 * gb),
        processes = listOf(LinuxReport.GuestProcess(1, "proot", 4 * mb), LinuxReport.GuestProcess(2, "node server.js", 80 * mb)),
        phantom = LinuxReport.Phantom(applies = true, monitorOff = false),
    )

    private fun service(exitCode: Int? = null, running: Boolean = false, lost: Boolean = false, killed: Boolean = false, autostart: Boolean = false) =
        LinuxEnvironment.ServiceStatus("api", "python3 app.py", running, lost, startedAt = 1, killed, exitCode, autostart)

    private fun reports(): List<LinuxReport> {
        val h = healthy()
        val caches = listOf(LinuxReport.Usage("/var/cache/apk", 150 * mb))
        return listOf(
            h,
            h.copy(phantom = LinuxReport.Phantom(applies = true, monitorOff = null)),
            h.copy(phantom = LinuxReport.Phantom(applies = true, monitorOff = true)),
            h.copy(phantom = LinuxReport.Phantom(applies = false)),
            h.copy(runtime = LinuxReport.Runtime(prootPresent = false)),
            h.copy(runtime = LinuxReport.Runtime(loaderPresent = false)),
            h.copy(runtime = LinuxReport.Runtime(prootExecutable = false)),
            h.copy(roundTrip = LinuxReport.RoundTrip(ok = false, millis = 20_500, timedOut = true)),
            h.copy(roundTrip = LinuxReport.RoundTrip(ok = false, millis = 40, detail = "proot error: execve(\"/bin/sh\"): No such file " + "x".repeat(200))),
            h.copy(roundTrip = LinuxReport.RoundTrip(ok = false, millis = 40)),
            h.copy(roundTrip = LinuxReport.RoundTrip(ok = true, millis = 4_250)),
            h.copy(roundTrip = null),
            h.copy(integrity = LinuxReport.Integrity(missing = listOf("/bin/sh", "/lib/apk/db/installed"), apkDbReadable = false)),
            h.copy(integrity = LinuxReport.Integrity(missing = listOf("/a", "/b", "/c", "/d", "/e", "/f"))),
            h.copy(integrity = LinuxReport.Integrity(missing = listOf("/a", "/b", "/c", "/d"))),
            h.copy(integrity = LinuxReport.Integrity(apkDbReadable = false)),
            h.copy(integrity = LinuxReport.Integrity(resolvConfOk = false)),
            h.copy(storage = LinuxReport.Storage(400 * mb, 300 * mb, 128 * gb)),
            h.copy(storage = LinuxReport.Storage(400 * mb, 300 * mb, 128 * gb, caches = caches)),
            h.copy(storage = LinuxReport.Storage(400 * mb, gb + 300 * mb, 128 * gb)),
            h.copy(storage = LinuxReport.Storage(400 * mb, gb, 128 * gb, caches = caches)),
            h.copy(storage = LinuxReport.Storage(400 * mb, 40 * gb, 128 * gb, caches = caches)),
            h.copy(memory = LinuxReport.Memory(8 * gb, 500 * mb)),
            h.copy(services = listOf(service(exitCode = 1))),
            h.copy(services = listOf(service(exitCode = 139))),
            h.copy(services = listOf(service(exitCode = null).copy(name = "web"), service(exitCode = 2))),
            h.copy(services = listOf(service(exitCode = 137, killed = true))),
            h.copy(services = listOf(service(lost = true, autostart = true))),
            h.copy(services = listOf(service(exitCode = 0, autostart = true), service(exitCode = 0, autostart = true).copy(name = "web"))),
            h.copy(lastKill = AndroidKillNotice("A command", 1)),
            h.copy(lastKill = AndroidKillNotice("A command", 1, AndroidKillNotice.Kind.Command)),
            h.copy(lastKill = AndroidKillNotice("Service web", 1, AndroidKillNotice.Kind.Service, "web")),
            h.copy(lastKill = AndroidKillNotice("A terminal", 1, AndroidKillNotice.Kind.Terminal)),
            h.copy(processes = (1..25).map { LinuxReport.GuestProcess(it, "sh", mb) }),
            h.copy(network = LinuxReport.Network(dnsOk = false, httpsOk = false, millis = 6_000)),
            h.copy(network = LinuxReport.Network(dnsOk = true, httpsOk = false, millis = 6_000)),
            h.copy(network = LinuxReport.Network(dnsOk = true, httpsOk = true, millis = 640)),
            h.copy(upgrades = emptyList()),
            h.copy(upgrades = emptyList(), alpineVersion = null),
            h.copy(upgrades = listOf(LinuxInventory.Upgrade("busybox", "1", "2"))),
            h.copy(upgrades = listOf(LinuxInventory.Upgrade("busybox", "1", "2"), LinuxInventory.Upgrade("musl", "1", "2"))),
            h.copy(storage = LinuxReport.Storage(400 * mb, 300 * mb, 128 * gb), memory = LinuxReport.Memory(8 * gb, 500 * mb), network = LinuxReport.Network(false, false, 1)),
        )
    }

    /** The English core/linux wrote before its text moved into resources (LinuxHealth, 2026-11-10). */
    private object OldEnglish {
        data class Check(val id: String, val title: String, val level: HealthLevel, val detail: String, val fix: String? = null)

        fun checks(report: LinuxReport): List<Check> {
            val out = mutableListOf<Check>()
            val rt = report.runtime
            if (!rt.prootPresent || !rt.prootExecutable || !rt.loaderPresent) out += Check(
                "runtime", "Linux runtime is missing", HealthLevel.Problem,
                "This copy of Locus can't run its Linux runtime (proot${if (!rt.prootPresent) " isn't there" else if (!rt.loaderPresent) "'s loader isn't there" else " can't be executed"}). " +
                    "Reinstall Locus from the same source.",
            )
            report.roundTrip?.let { trip ->
                out += when {
                    trip.timedOut -> Check("roundtrip", "Linux doesn't respond", HealthLevel.Problem, "A test command didn't finish in ${trip.millis / 1000} s. Repair rewrites Linux's settings without touching your packages.", "Repair")
                    !trip.ok -> Check("roundtrip", "Linux can't run commands", HealthLevel.Problem, "A test command failed${trip.detail?.let { ": ${it.take(160)}" }.orEmpty()}. Repair rewrites Linux's settings without touching your packages.", "Repair")
                    trip.millis > LinuxHealth.SLOW_ROUND_TRIP_MS -> Check("roundtrip", "Commands are slow to start", HealthLevel.Warning, "A test command took ${String.format(Locale.ROOT, "%.1f s", trip.millis / 1000.0)}. The phone may be busy, hot or in battery saver.")
                    else -> Check("roundtrip", "Runs commands", HealthLevel.Ok, "A test command took ${trip.millis} ms.")
                }
            }
            val integrity = report.integrity
            if (integrity.missing.isNotEmpty() || !integrity.apkDbReadable) {
                val what = buildList {
                    if (integrity.missing.isNotEmpty()) add("missing ${integrity.missing.take(4).joinToString(", ")}${if (integrity.missing.size > 4) " and ${integrity.missing.size - 4} more" else ""}")
                    if (!integrity.apkDbReadable) add("the package database can't be read")
                }.joinToString("; ")
                out += Check("files", "Linux's system files are damaged", HealthLevel.Problem, "Linux is $what. Only a reinstall puts them back; your workspace files stay.", "Reinstall")
            } else {
                out += Check("files", "System files intact", HealthLevel.Ok, "Key files and the package database are in place.")
            }
            report.storage?.let { s ->
                val cacheHint = if (s.cacheBytes > 0) " Caches in Linux use ${LinuxHealth.bytes(s.cacheBytes)}." else ""
                out += when {
                    s.phoneFreeBytes < LinuxHealth.STORAGE_PROBLEM_BYTES -> Check("storage", "Phone storage is almost full", HealthLevel.Problem, "${LinuxHealth.bytes(s.phoneFreeBytes)} free. Installing packages will fail.$cacheHint", "Clean caches".takeIf { s.cacheBytes > 0 })
                    s.phoneFreeBytes < LinuxHealth.STORAGE_WARNING_BYTES -> Check("storage", "Phone storage is low", HealthLevel.Warning, "${LinuxHealth.bytes(s.phoneFreeBytes)} free. Larger packages (compilers, Java, Node) may not fit.$cacheHint", "Clean caches".takeIf { s.cacheBytes > 0 })
                    s.cacheBytes >= LinuxHealth.CACHE_WARNING_BYTES -> Check("storage", "Caches use ${LinuxHealth.bytes(s.cacheBytes)}", HealthLevel.Warning, "Package and download caches can be deleted; they're downloaded again when needed.", "Clean caches")
                    else -> Check("storage", "Enough storage", HealthLevel.Ok, "Linux uses ${LinuxHealth.bytes(s.linuxBytes)}; ${LinuxHealth.bytes(s.phoneFreeBytes)} free on the phone.")
                }
            }
            report.memory?.let { m ->
                out += if (LinuxHealth.isLowMemory(m)) {
                    Check("memory", "Phone is low on memory", HealthLevel.Warning, "${LinuxHealth.bytes(m.availableBytes)} available. Android may stop Linux programs, especially while Locus is in the background.")
                } else {
                    Check("memory", "Enough memory", HealthLevel.Ok, "${LinuxHealth.bytes(m.availableBytes)} of ${LinuxHealth.bytes(m.totalBytes)} available; Linux programs use ${LinuxHealth.bytes(report.guestRssBytes)}.")
                }
            }
            report.services.forEach { s -> service(s)?.let { out += it } }
            val killed = report.services.any { it.killedByAndroid } || report.lastKill != null
            val p = report.phantom
            if (p.applies) {
                out += when {
                    p.monitorOff == true -> Check("phantom", "Background limit is off", HealthLevel.Ok, "Child process restrictions are turned off, so Android won't stop Linux for that reason.")
                    killed || report.processes.size >= LinuxHealth.PROCESS_WARNING -> Check(
                        "phantom", "Android may stop Linux in the background", HealthLevel.Warning,
                        (report.lastKill?.let { "Android stopped ${it.what.replaceFirstChar(Char::lowercase)} while Locus was in the background. " }.orEmpty()) +
                            "Android 12+ stops apps' extra processes (${report.processes.size} running for Locus now). You can turn this off.",
                        "How to fix",
                    )
                    else -> Check("phantom", "Background limit is on", HealthLevel.Ok, "Android 12+ may stop Linux programs while Locus is in the background${if (p.monitorOff == null) " (this phone doesn't let Locus check the setting)" else ""}.", "How to fix")
                }
            }
            if (!integrity.resolvConfOk) out += Check("dns-config", "No DNS server set", HealthLevel.Problem, "Linux's /etc/resolv.conf names no nameserver, so it can't reach the internet.", "Reset DNS")
            report.network?.let { n ->
                out += when {
                    !n.dnsOk -> Check("network", "Linux can't look up names", HealthLevel.Problem, "DNS failed inside Linux. If the phone is online, reset Linux's DNS servers.", "Reset DNS")
                    !n.httpsOk -> Check("network", "Can't reach Alpine's mirror", HealthLevel.Warning, "Names resolve but dl-cdn.alpinelinux.org didn't answer over HTTPS. A VPN, captive portal or firewall may block it.")
                    else -> Check("network", "Internet works", HealthLevel.Ok, "DNS and HTTPS to Alpine's mirror in ${n.millis} ms.")
                }
            }
            report.upgrades?.let { u ->
                out += if (u.isEmpty()) Check("upgrades", "Packages up to date", HealthLevel.Ok, "No newer versions in Alpine ${report.alpineVersion ?: ""}'s repositories.".replace("  ", " "))
                else Check("upgrades", "${u.size} update${if (u.size == 1) "" else "s"} available", HealthLevel.Ok, "Run `apk upgrade` in the terminal to install them.", "Open terminal")
            }
            return out.sortedByDescending { it.level }
        }

        private fun service(s: LinuxEnvironment.ServiceStatus): Check? {
            val id = "service:${s.name}"
            return when (s.state) {
                LinuxEnvironment.ServiceState.Running -> null
                LinuxEnvironment.ServiceState.Crashed -> Check(id, "“${s.name}” crashed", HealthLevel.Problem, "It exited with ${LinuxHealth.exitDescription(s.exitCode)}. Its log may say why; ask the agent to check it.", "Restart")
                LinuxEnvironment.ServiceState.KilledByAndroid -> Check(id, "Android stopped “${s.name}”", HealthLevel.Warning, "It was killed while Locus was in the background (phantom process limit).", "Restart")
                LinuxEnvironment.ServiceState.StoppedByRestart, LinuxEnvironment.ServiceState.Finished -> if (s.autostart) Check(
                    id, "“${s.name}” isn't running", HealthLevel.Warning,
                    "It's set to start when Locus opens, but it isn't running. Start it, or turn off Start when Locus opens under Services.", "Start",
                ) else null
            }
        }

        fun headline(checks: List<Check>): String {
            val worst = checks.maxOfOrNull { it.level } ?: HealthLevel.Ok
            if (worst == HealthLevel.Ok) return "Linux is working"
            val bad = checks.filter { it.level == worst }
            if (bad.size == 1) return bad.single().title
            val more = bad.size - 1
            val kind = if (worst == HealthLevel.Problem) "problem" else "warning"
            return "${bad.first().title}, and $more more $kind${if (more == 1) "" else "s"}"
        }
    }
}
