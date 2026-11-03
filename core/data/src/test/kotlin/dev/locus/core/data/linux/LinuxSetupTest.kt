package dev.locus.core.data.linux

import dev.locus.core.linux.CommandResult
import dev.locus.core.linux.LinuxStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The shared install use case: install, packages, failures, cancel, repair, remove, and the job hand-off. */
class LinuxSetupTest {

    /** Linux as LinuxSetup sees it: installs on request, answers apk commands from [installed]. */
    private class FakeLinux(initial: LinuxStatus = LinuxStatus.NotInstalled) : LinuxOps {
        override val status = MutableStateFlow(initial)
        val installed = mutableSetOf<String>()
        val commands = mutableListOf<String>()
        var installError: Exception? = null
        var updateFails = false
        var failingApk: String? = null
        var repairError: Exception? = null

        /** When set, ensureInstalled waits here, so a test can look at (or cancel) a run in progress. */
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun ensureInstalled() {
            status.value = LinuxStatus.Installing("Downloading Alpine Linux 3.24.3", 0.5f)
            gate?.await()
            installError?.let { status.value = LinuxStatus.Failed(it.message!!); throw it }
            status.value = LinuxStatus.Ready
        }

        override suspend fun run(command: String, timeoutMs: Long, maxChars: Int): CommandResult {
            commands += command
            return when {
                command.startsWith("apk info") -> CommandResult(0, installed.joinToString("\n") { "ok:$it" }, timedOut = false, truncated = false)
                command == "apk update" -> CommandResult(if (updateFails) 1 else 0, "fetch failed", timedOut = false, truncated = false)
                command.startsWith("apk add") -> {
                    val names = command.removePrefix("apk add --no-progress ").split(" ")
                    if (failingApk in names) CommandResult(1, "ERROR: unable to select packages", timedOut = false, truncated = false)
                    else CommandResult(0, "OK", timedOut = false, truncated = false).also { installed += names }
                }
                else -> error("unexpected command $command")
            }
        }

        override suspend fun uninstall() {
            installed.clear()
            status.value = LinuxStatus.NotInstalled
        }

        override suspend fun repair(): String {
            repairError?.let { throw it }
            return "3.24.3"
        }
    }

    private fun TestScope.setup(linux: FakeLinux, background: LinuxSetup.Background? = null) =
        LinuxSetup(linux, backgroundScope as CoroutineScope, background)

    @Test
    fun `install adds Linux then each package, and ends ready`() = runTest {
        val linux = FakeLinux()
        val setup = setup(linux)
        setup.install(listOf(LinuxPackage.Git, LinuxPackage.Python))
        assertTrue(setup.state.value.running)
        assertEquals(LinuxOperation.Install, setup.state.value.operation)
        runCurrent()

        val s = setup.state.value
        assertFalse(s.running)
        assertEquals(LinuxStatus.Ready, linux.status.value)
        assertEquals(PackageState.Installed, s.packages[LinuxPackage.Python])
        assertEquals(PackageState.Installed, s.packages[LinuxPackage.Git])
        assertEquals("Installed Python, Git.", s.message)
        assertNull(s.error)
        // apk update once, then one apk add per package, in the offered order.
        assertEquals(listOf("apk update", "apk add --no-progress python3 py3-pip", "apk add --no-progress git"), linux.commands.filter { !it.startsWith("apk info") })
    }

    @Test
    fun `packages already there are not installed again`() = runTest {
        val linux = FakeLinux(LinuxStatus.Ready).apply { installed += "git" }
        val setup = setup(linux)
        setup.install(listOf(LinuxPackage.Git))
        runCurrent()
        assertEquals(PackageState.Installed, setup.state.value.packages[LinuxPackage.Git])
        assertTrue(linux.commands.none { it.startsWith("apk add") || it == "apk update" })
    }

    @Test
    fun `a failed download is an error, and Try again installs`() = runTest {
        val linux = FakeLinux().apply { installError = java.io.IOException("Unable to resolve host dl-cdn.alpinelinux.org") }
        val setup = setup(linux)
        setup.install(emptyList())
        runCurrent()
        assertFalse(setup.state.value.running)
        assertEquals("Unable to resolve host dl-cdn.alpinelinux.org", setup.state.value.error)
        assertTrue(linux.status.value is LinuxStatus.Failed)

        linux.installError = null
        setup.install(emptyList())
        runCurrent()
        assertNull(setup.state.value.error)
        assertEquals("Linux is ready.", setup.state.value.message)
        assertEquals(LinuxStatus.Ready, linux.status.value)
    }

    @Test
    fun `apk update failing marks every package failed`() = runTest {
        val linux = FakeLinux(LinuxStatus.Ready).apply { updateFails = true }
        val setup = setup(linux)
        setup.install(listOf(LinuxPackage.Curl, LinuxPackage.Git))
        runCurrent()
        val s = setup.state.value
        assertTrue(s.packages[LinuxPackage.Curl] is PackageState.Failed)
        assertTrue(s.packages[LinuxPackage.Git] is PackageState.Failed)
        assertTrue(s.error!!.startsWith("Couldn't reach Alpine's package servers."))
    }

    @Test
    fun `one package failing doesn't stop the others`() = runTest {
        val linux = FakeLinux(LinuxStatus.Ready).apply { failingApk = "nodejs" }
        val setup = setup(linux)
        setup.install(listOf(LinuxPackage.Node, LinuxPackage.Curl))
        runCurrent()
        val s = setup.state.value
        val failed = assertNotNull(s.packages[LinuxPackage.Node] as? PackageState.Failed)
        assertTrue("unable to select packages" in failed.detail)
        assertEquals(PackageState.Installed, s.packages[LinuxPackage.Curl])
        assertEquals("1 of 2 couldn't be installed. See below; you can retry.", s.error)
    }

    @Test
    fun `cancel stops an install and nothing is left waiting`() = runTest {
        val linux = FakeLinux().apply { gate = CompletableDeferred() }
        val setup = setup(linux)
        setup.install(listOf(LinuxPackage.Python))
        runCurrent()
        assertTrue(setup.state.value.cancellable)
        assertEquals(PackageState.Queued, setup.state.value.packages[LinuxPackage.Python])

        setup.cancel()
        runCurrent()
        val s = setup.state.value
        assertFalse(s.running)
        assertTrue(s.cancelled)
        assertEquals(PackageState.Unknown, s.packages[LinuxPackage.Python])
        assertNull(s.error)
        // A new install can start after a cancel.
        linux.gate = null
        setup.install(emptyList())
        runCurrent()
        assertEquals(LinuxStatus.Ready, linux.status.value)
    }

    @Test
    fun `install hands the request to the background job, which runs it`() = runTest {
        val started = mutableListOf<LinuxSetupRequest>()
        var cancelled = 0
        val linux = FakeLinux()
        val setup = setup(linux, object : LinuxSetup.Background {
            override fun start(request: LinuxSetupRequest) { started += request }
            override fun cancel() { cancelled++ }
        })
        setup.install(setOf(LinuxPackage.Curl, LinuxPackage.Python))
        // Shown as running at once, before the job starts; a second tap does nothing.
        assertTrue(setup.state.value.running)
        setup.install(setOf(LinuxPackage.Git))
        assertEquals(listOf(LinuxSetupRequest(listOf(LinuxPackage.Python, LinuxPackage.Curl))), started)

        // The job calls execute, in the same process: the state the screens read follows it.
        setup.execute(started.single())
        assertFalse(setup.state.value.running)
        assertEquals(PackageState.Installed, setup.state.value.packages[LinuxPackage.Curl])

        // Cancelling a job that never started still resets the card.
        setup.install(emptyList(), reinstall = true)
        setup.cancel()
        assertEquals(1, cancelled)
        assertFalse(setup.state.value.running)
        assertTrue(setup.state.value.cancelled)
    }

    @Test
    fun `reinstall removes Linux first and installs the packages again`() = runTest {
        val linux = FakeLinux(LinuxStatus.Ready).apply { installed += listOf("git", "curl") }
        val setup = setup(linux)
        setup.install(listOf(LinuxPackage.Git), reinstall = true)
        assertEquals(LinuxOperation.Reinstall, setup.state.value.operation)
        runCurrent()
        assertEquals(LinuxStatus.Ready, linux.status.value)
        assertEquals(setOf("git"), linux.installed)
        assertEquals("Linux was reinstalled with Git.", setup.state.value.message)
    }

    @Test
    fun `repair reports the release, or why it couldn't`() = runTest {
        val linux = FakeLinux(LinuxStatus.Ready)
        val setup = setup(linux)
        setup.repair()
        assertEquals(LinuxOperation.Repair, setup.state.value.operation)
        assertFalse(setup.state.value.cancellable)
        runCurrent()
        assertEquals("Repaired. Alpine Linux 3.24.3 starts normally.", setup.state.value.message)

        linux.repairError = IllegalStateException("Linux's own files are damaged or missing. Reinstall it to fix this.")
        setup.repair()
        runCurrent()
        assertEquals("Linux's own files are damaged or missing. Reinstall it to fix this.", setup.state.value.error)
        assertFalse(setup.state.value.running)
    }

    @Test
    fun `remove uninstalls and forgets the packages`() = runTest {
        val linux = FakeLinux(LinuxStatus.Ready).apply { installed += "git" }
        val setup = setup(linux)
        setup.refreshPackages()
        runCurrent()
        assertEquals(setOf(LinuxPackage.Git), setup.state.value.installedPackages)

        setup.remove()
        runCurrent()
        assertEquals(LinuxStatus.NotInstalled, linux.status.value)
        assertTrue(setup.state.value.packages.isEmpty())
        assertEquals("Linux was removed. Your workspace files are still here.", setup.state.value.message)
        setup.outcomeShown()
        assertNull(setup.state.value.message)
    }
}
