package dev.locus.core.data.linux

import dev.locus.core.linux.AndroidKillNotice
import dev.locus.core.linux.LinuxEnvironment
import dev.locus.core.linux.LinuxStatus
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The Linux card's state machine: which state each status and run lead to. */
class LinuxPanelTest {
    private val idle = LinuxSetupState()
    private val facts = LinuxFacts(version = "3.24.3", diskBytes = 9_000_000)

    private fun panel(status: LinuxStatus, setup: LinuxSetupState = idle, f: LinuxFacts = facts, selected: Set<LinuxPackage> = emptySet()) =
        linuxPanel(status, setup, f, selected)

    @Test
    fun `not installed offers an estimate for the ticked packages`() {
        val bare = assertIs<LinuxPanel.NotInstalled>(panel(LinuxStatus.NotInstalled))
        assertEquals(InstallEstimate(4, 9, 1), bare.estimate)
        assertTrue("Usually under a minute" in bare.estimate.summary)

        val withTools = assertIs<LinuxPanel.NotInstalled>(panel(LinuxStatus.NotInstalled, selected = setOf(LinuxPackage.Python, LinuxPackage.Git)))
        assertEquals(20, withTools.estimate.downloadMb)
        assertEquals(54, withTools.estimate.diskMb)
        assertEquals(2, withTools.estimate.minutes)
        assertTrue("About 20 MB to download, 54 MB on the phone." in withTools.estimate.summary)
    }

    @Test
    fun `adding tools to an installed system doesn't count the base`() {
        assertEquals(InstallEstimate(1, 1, 1), InstallEstimate.of(listOf(LinuxPackage.Curl), linuxInstalled = true))
    }

    @Test
    fun `downloading shows the step and its fraction, and can be cancelled`() {
        val running = idle.copy(running = true, operation = LinuxOperation.Install, phase = "Installing Alpine Linux…")
        val working = assertIs<LinuxPanel.Working>(panel(LinuxStatus.Installing("Downloading Alpine Linux 3.24.3", 0.4f), running))
        assertEquals("Downloading Alpine Linux 3.24.3", working.step)
        assertEquals(0.4f, working.fraction)
        assertTrue(working.cancellable)

        // Unpacking has no fraction: the meter is indeterminate.
        assertEquals(null, assertIs<LinuxPanel.Working>(panel(LinuxStatus.Installing("Unpacking"), running)).fraction)
    }

    @Test
    fun `installing packages on a ready system is still working, with package progress`() {
        val run = idle.copy(running = true, operation = LinuxOperation.Install, phase = "Installing Git (2 of 2)…", progress = 0.5f)
        val working = assertIs<LinuxPanel.Working>(panel(LinuxStatus.Ready, run))
        assertEquals("Installing Git (2 of 2)…", working.step)
        assertEquals(0.5f, working.fraction)
    }

    @Test
    fun `a reinstall stays working while Linux is briefly not installed`() {
        val run = idle.copy(running = true, operation = LinuxOperation.Reinstall, phase = "Removing the old Linux system…")
        val working = assertIs<LinuxPanel.Working>(panel(LinuxStatus.NotInstalled, run))
        assertEquals(LinuxOperation.Reinstall, working.operation)
        assertTrue(working.cancellable)
    }

    @Test
    fun `repair and remove can't be cancelled`() {
        for (op in listOf(LinuxOperation.Repair, LinuxOperation.Remove)) {
            val working = assertIs<LinuxPanel.Working>(panel(LinuxStatus.Ready, idle.copy(running = true, operation = op, phase = "…")))
            assertFalse(working.cancellable, "$op")
        }
    }

    @Test
    fun `installed shows version, space, services and Android's kill`() {
        val services = listOf(
            LinuxEnvironment.ServiceStatus("web", "npx serve", running = true, lostOnRestart = false, startedAt = 1),
            LinuxEnvironment.ServiceStatus("old", "sleep 9", running = false, lostOnRestart = true, startedAt = 0),
        )
        val kill = AndroidKillNotice("Service api", 5)
        val installed = assertIs<LinuxPanel.Installed>(panel(LinuxStatus.Ready, f = facts.copy(services = services, killed = kill)))
        assertEquals("3.24.3", installed.version)
        assertEquals(9_000_000, installed.diskBytes)
        assertEquals(listOf("web"), installed.running.map { it.name })
        assertEquals(kill, installed.killed)
    }

    @Test
    fun `a failed install is Failed with its reason`() {
        assertEquals(LinuxPanel.Failed("Checksum mismatch"), panel(LinuxStatus.Failed("Checksum mismatch")))
        // A run that failed before Linux said anything still fails the card.
        assertEquals(LinuxPanel.Failed("Unsupported CPU: armeabi-v7a"), panel(LinuxStatus.NotInstalled, idle.copy(error = "Unsupported CPU: armeabi-v7a")))
    }

    @Test
    fun `a cancelled install goes back to not installed`() {
        assertIs<LinuxPanel.NotInstalled>(panel(LinuxStatus.NotInstalled, idle.copy(cancelled = true, message = "Cancelled.")))
    }

    @Test
    fun `an unavailable phone wins over everything`() {
        val run = idle.copy(running = true, operation = LinuxOperation.Install)
        assertEquals(LinuxPanel.Unavailable("No proot for this CPU"), panel(LinuxStatus.Unavailable("No proot for this CPU"), run))
    }
}
