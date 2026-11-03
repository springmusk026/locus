package dev.locus.core.linux

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxHealthTest {
    private val gb = 1024L * 1024 * 1024
    private val mb = 1024L * 1024

    private val pkgs = listOf(
        LinuxInventory.Package("busybox", "1.37.0-r31", explicit = true),
        LinuxInventory.Package("python3", "3.12.8-r1", explicit = true),
        LinuxInventory.Package("py3-pip", "25.1-r0"),
        LinuxInventory.Package("musl", "1.2.6-r3"),
    )

    private fun healthy() = LinuxReport(
        takenAt = 0,
        alpineVersion = "3.24.3",
        arch = "aarch64",
        runtime = LinuxReport.Runtime(prootVersion = "5.4.0"),
        roundTrip = LinuxReport.RoundTrip(ok = true, millis = 180),
        packages = pkgs,
        storage = LinuxReport.Storage(linuxBytes = 400 * mb, phoneFreeBytes = 40 * gb, phoneTotalBytes = 128 * gb),
        memory = LinuxReport.Memory(totalBytes = 8 * gb, availableBytes = 3 * gb),
        processes = listOf(LinuxReport.GuestProcess(1, "proot", 4 * mb), LinuxReport.GuestProcess(2, "node server.js", 80 * mb)),
        services = listOf(LinuxEnvironment.ServiceStatus("web", "npx serve", running = true, lostOnRestart = false, startedAt = 1)),
        phantom = LinuxReport.Phantom(applies = true, monitorOff = null),
    )

    private fun check(report: LinuxReport, id: String) = LinuxHealth.checks(report).single { it.id == id }

    @Test
    fun `a healthy install is OK with each check listed`() {
        val checks = LinuxHealth.checks(healthy())
        assertEquals(HealthLevel.Ok, LinuxHealth.overall(checks))
        assertEquals("Linux is working", LinuxHealth.headline(checks))
        assertEquals(setOf("roundtrip", "files", "storage", "memory", "phantom"), checks.map { it.id }.toSet())
        // Even when fine, the phantom limit offers its explanation.
        assertEquals(LinuxFix.PhantomHelp, check(healthy(), "phantom").fix)
    }

    @Test
    fun `broken runtime, round trip and files are problems that repair`() {
        val r = healthy().copy(
            roundTrip = LinuxReport.RoundTrip(ok = false, millis = 40, detail = "proot error: execve(\"/bin/sh\"): No such file"),
            integrity = LinuxReport.Integrity(missing = listOf("/bin/sh", "/lib/apk/db/installed"), apkDbReadable = false),
        )
        val checks = LinuxHealth.checks(r)
        assertEquals(HealthLevel.Problem, LinuxHealth.overall(checks))
        assertEquals(LinuxFix.Repair, check(r, "roundtrip").fix)
        assertEquals(LinuxFix.Reinstall, check(r, "files").fix, "repair only rewrites what Locus manages")
        assertTrue("/bin/sh" in check(r, "files").detail && "package database" in check(r, "files").detail)
        assertEquals("Linux can't run commands (+1 more)", LinuxHealth.headline(checks))
        assertEquals(HealthLevel.Problem, checks.first().level, "worst first")
        assertEquals(HealthLevel.Problem, check(healthy().copy(roundTrip = LinuxReport.RoundTrip(ok = false, millis = 20_000, timedOut = true)), "roundtrip").level)
        assertEquals(HealthLevel.Warning, check(healthy().copy(roundTrip = LinuxReport.RoundTrip(ok = true, millis = 4_200)), "roundtrip").level)
        val runtime = check(healthy().copy(runtime = LinuxReport.Runtime(prootExecutable = false)), "runtime")
        assertEquals(HealthLevel.Problem, runtime.level)
        assertNull(runtime.fix, "a missing runtime is fixed by reinstalling the app, not by a button here")
    }

    @Test
    fun `storage thresholds and the cache fix`() {
        val caches = listOf(LinuxReport.Usage("/var/cache/apk", 150 * mb))
        fun storage(free: Long, c: List<LinuxReport.Usage> = emptyList()) =
            check(healthy().copy(storage = LinuxReport.Storage(400 * mb, free, 128 * gb, caches = c)), "storage")
        assertEquals(HealthLevel.Problem, storage(300 * mb).level)
        assertNull(storage(300 * mb).fix, "no cache to clean, so no button that would do nothing")
        assertEquals(LinuxFix.CleanCaches, storage(300 * mb, caches).fix)
        assertEquals(HealthLevel.Warning, storage(1 * gb).level)
        assertEquals(HealthLevel.Warning, storage(40 * gb, caches).level)
        assertEquals(LinuxFix.CleanCaches, storage(40 * gb, caches).fix)
        assertEquals(HealthLevel.Ok, storage(40 * gb, listOf(LinuxReport.Usage("/var/cache/apk", 2 * mb))).level)
    }

    @Test
    fun `memory, services and the phantom limit`() {
        assertEquals(HealthLevel.Warning, check(healthy().copy(memory = LinuxReport.Memory(8 * gb, 500 * mb)), "memory").level)
        assertEquals(HealthLevel.Warning, check(healthy().copy(memory = LinuxReport.Memory(8 * gb, 3 * gb, lowMemory = true)), "memory").level)

        val killed = healthy().copy(services = listOf(LinuxEnvironment.ServiceStatus("web", "npx serve", running = false, lostOnRestart = false, startedAt = 1, killedByAndroid = true)))
        val service = check(killed, "service:web")
        assertEquals(LinuxFix.RestartService, service.fix)
        assertEquals("web", service.target)
        assertEquals(HealthLevel.Warning, check(killed, "phantom").level, "a kill makes the limit a warning")
        val commandKilled = check(healthy().copy(lastKill = AndroidKillNotice("A command", 1)), "phantom")
        assertEquals(HealthLevel.Warning, commandKilled.level)
        assertTrue(commandKilled.detail.startsWith("Android stopped a command while Locus was in the background."), commandKilled.detail)

        val lost = healthy().copy(services = listOf(LinuxEnvironment.ServiceStatus("api", "python3 app.py", running = false, lostOnRestart = true, startedAt = 1)))
        assertEquals(LinuxFix.RestartService, check(lost, "service:api").fix)

        assertEquals(HealthLevel.Ok, check(healthy().copy(phantom = LinuxReport.Phantom(applies = true, monitorOff = true)), "phantom").level)
        assertNull(check(healthy().copy(phantom = LinuxReport.Phantom(applies = true, monitorOff = true)), "phantom").fix)
        assertTrue(LinuxHealth.checks(healthy().copy(phantom = LinuxReport.Phantom(applies = false))).none { it.id == "phantom" }, "Android 11 and older have no limit")
        val many = healthy().copy(processes = (1..LinuxHealth.PROCESS_WARNING).map { LinuxReport.GuestProcess(it, "sh", mb) })
        assertEquals(HealthLevel.Warning, check(many, "phantom").level)
    }

    @Test
    fun `network and dns`() {
        assertEquals(LinuxFix.ResetDns, check(healthy().copy(network = LinuxReport.Network(dnsOk = false, httpsOk = false, millis = 6_000)), "network").fix)
        assertEquals(HealthLevel.Warning, check(healthy().copy(network = LinuxReport.Network(dnsOk = true, httpsOk = false, millis = 6_000)), "network").level)
        assertEquals(HealthLevel.Ok, check(healthy().copy(network = LinuxReport.Network(dnsOk = true, httpsOk = true, millis = 600)), "network").level)
        val noDns = healthy().copy(integrity = LinuxReport.Integrity(resolvConfOk = false))
        assertEquals(LinuxFix.ResetDns, check(noDns, "dns-config").fix)
        assertTrue(LinuxHealth.checks(healthy()).none { it.id == "network" }, "not checked unless asked")
    }

    @Test
    fun `upgrades are information with the terminal as the way to apply them`() {
        val r = healthy().copy(upgrades = listOf(LinuxInventory.Upgrade("busybox", "1.37.0-r31", "1.37.0-r32")))
        assertEquals("1 update available", check(r, "upgrades").title)
        assertEquals(LinuxFix.OpenTerminal, check(r, "upgrades").fix)
        assertEquals("Packages up to date", check(healthy().copy(upgrades = emptyList()), "upgrades").title)
    }

    @Test
    fun `sizes read naturally`() {
        assertEquals("0 B", LinuxHealth.bytes(0))
        assertEquals("1.5 KB", LinuxHealth.bytes(1536))
        assertEquals("400 MB", LinuxHealth.bytes(400 * mb))
        assertEquals("2.0 GB", LinuxHealth.bytes(2 * gb))
    }
}
