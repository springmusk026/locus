package dev.locus.feature.workspace.linux.health

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import dev.locus.core.linux.EngineText
import dev.locus.core.linux.HealthCheck
import dev.locus.core.linux.HealthLevel
import dev.locus.core.linux.LinuxFix
import dev.locus.core.linux.LinuxHealth
import dev.locus.core.linux.LinuxInventory
import dev.locus.core.linux.LinuxReport
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Runs on Robolectric so the lines are checked in the module's English strings. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HealthSummaryTest {
    private val res: Resources get() = ApplicationProvider.getApplicationContext<Context>().resources
    private val gb = 1024L * 1024 * 1024
    private val mb = 1024L * 1024

    @Test
    fun `problems come first with their fix, passed checks fold, and memory has its own section`() {
        val report = LinuxReport(
            takenAt = 0,
            roundTrip = LinuxReport.RoundTrip(ok = true, millis = 120),
            storage = LinuxReport.Storage(400 * mb, 300 * mb, 128 * gb, caches = listOf(LinuxReport.Usage("/var/cache/apk", 50 * mb))),
            memory = LinuxReport.Memory(totalBytes = 8 * gb, availableBytes = 300 * mb),
            integrity = LinuxReport.Integrity(resolvConfOk = false),
        )
        val checks = LinuxHealth.checks(report)
        val problems = HealthSummary.problems(checks)
        assertEquals(setOf("storage", "dns-config"), problems.map { it.id }.toSet())
        assertTrue(problems.all { it.fix != null }, "every problem on the page has a working fix button")
        assertEquals(HealthLevel.Problem, problems.first().level)
        assertTrue(problems.none { it.id == "memory" } && HealthSummary.passed(checks).none { it.id == "memory" })
        assertEquals(listOf("files", "roundtrip"), HealthSummary.passed(checks).map { it.id }.sorted())
    }

    @Test
    fun `problems are worst first even when given out of order`() {
        val checks = listOf(
            HealthCheck("a", EngineText.Plain("A"), HealthLevel.Warning, EngineText.Plain("")),
            HealthCheck("b", EngineText.Plain("B"), HealthLevel.Ok, EngineText.Plain("")),
            HealthCheck("c", EngineText.Plain("C"), HealthLevel.Problem, EngineText.Plain(""), LinuxFix.Repair),
        )
        assertEquals(listOf("c", "a"), HealthSummary.problems(checks).map { it.id })
    }

    @Test
    fun `long lists show the top few until Show all`() {
        val folders = (1..12).map { LinuxReport.Usage("/usr/lib/$it", it * mb) }
        assertEquals(HealthSummary.TOP_FEW, HealthSummary.visible(folders, expanded = false).size)
        assertEquals(folders, HealthSummary.visible(folders, expanded = true))
        assertTrue(HealthSummary.hasMore(folders))
        val few = folders.take(HealthSummary.TOP_FEW)
        assertFalse(HealthSummary.hasMore(few), "no Show all when everything already fits")
        assertEquals(few, HealthSummary.visible(few, expanded = false))
    }

    @Test
    fun `packages are one line of counts, never the list`() {
        val pkgs = LinuxInventory.BASE_SYSTEM.map { LinuxInventory.Package(it, "1-r0", explicit = true) } +
            listOf(LinuxInventory.Package("python3", "3.12.8-r1", explicit = true), LinuxInventory.Package("git", "2.49.0-r0", explicit = true)) +
            (0 until 500).map { LinuxInventory.Package("lib-$it", "1.0-r0") }
        assertEquals("2 you installed · ${pkgs.size} total", HealthSummary.packagesLine(res, pkgs))
        assertEquals("Can't read apk's package database", HealthSummary.packagesLine(res, null))
    }

    @Test
    fun `memory is a warning only when it's low`() {
        assertNull(HealthSummary.memoryWarning(res, LinuxReport.Memory(8 * gb, 3 * gb)))
        assertNull(HealthSummary.memoryWarning(res, null))
        val low = assertNotNull(HealthSummary.memoryWarning(res, LinuxReport.Memory(8 * gb, 500 * mb)))
        assertEquals("500 MB of 8.0 GB available. Android may stop Linux programs, especially while Locus is in the background.", low)
        assertNotNull(HealthSummary.memoryWarning(res, LinuxReport.Memory(8 * gb, 3 * gb, lowMemory = true)), "Android's own low-memory flag counts")
    }

    @Test
    fun `processes are one line`() {
        assertEquals("None running", HealthSummary.processesLine(res, emptyList()))
        assertEquals(
            "2 running · 84 MB",
            HealthSummary.processesLine(res, listOf(LinuxReport.GuestProcess(1, "proot", 4 * mb), LinuxReport.GuestProcess(2, "node", 80 * mb))),
        )
    }
}
