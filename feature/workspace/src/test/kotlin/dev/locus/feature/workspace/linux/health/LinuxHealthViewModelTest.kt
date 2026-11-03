package dev.locus.feature.workspace.linux.health

import dev.locus.core.linux.HealthLevel
import dev.locus.core.linux.LinuxEnvironment
import dev.locus.core.linux.LinuxHealth
import dev.locus.core.linux.LinuxFix
import dev.locus.core.linux.LinuxInventory
import dev.locus.core.linux.LinuxReport
import dev.locus.core.linux.LinuxStatus
import dev.locus.core.testing.MainDispatcherRule
import dev.locus.core.testing.TestGraph
import dev.locus.core.testing.ViewModelHost
import dev.locus.core.testing.readyWithoutShell
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxHealthViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val host = ViewModelHost()
    private val mb = 1024L * 1024

    private class FakeSource(var report: LinuxReport?) : LinuxHealthSource {
        var reads = 0
        var failWith: Exception? = null
        var freed = 0L
        override suspend fun report(): LinuxReport? {
            reads++
            failWith?.let { throw it }
            return report
        }
        override suspend fun network() = LinuxReport.Network(dnsOk = false, httpsOk = false, millis = 5)
        override suspend fun cleanCaches(): Long = freed.also { report = report?.copy(storage = report?.storage?.copy(caches = emptyList())) }
    }

    private fun report() = LinuxReport(
        takenAt = 1,
        alpineVersion = "3.24.3",
        roundTrip = LinuxReport.RoundTrip(ok = true, millis = 100),
        packages = listOf(LinuxInventory.Package("busybox", "1.37.0-r31", explicit = true)),
        storage = LinuxReport.Storage(300 * mb, 40_000 * mb, 128_000 * mb, caches = listOf(LinuxReport.Usage("/var/cache/apk", 200 * mb))),
    )

    @Test
    fun `without Linux nothing is probed`() = runTest {
        try {
            val g = TestGraph(backgroundScope)
            val source = FakeSource(report())
            val vm = host.add(LinuxHealthViewModel(g.linux, g.linuxSetup, source) {})
            backgroundScope.launch { vm.uiState.collect {} }
            val state = vm.uiState.first()
            assertEquals(LinuxStatus.NotInstalled, state.status)
            vm.refresh()
            assertNull(vm.uiState.value.report)
            assertEquals(0, source.reads)
        } finally {
            host.clear()
        }
    }

    @Test
    fun `ready Linux loads, fixes run, and results merge`() = runTest {
        try {
            val g = TestGraph(backgroundScope)
            g.linux.readyWithoutShell()
            val source = FakeSource(report()).apply { freed = 200 * mb }
            val vm = host.add(LinuxHealthViewModel(g.linux, g.linuxSetup, source) {})
            backgroundScope.launch { vm.uiState.collect {} }

            val loaded = vm.uiState.first { it.report != null && !it.loading }
            assertEquals(HealthLevel.Warning, loaded.checks.first().level)
            assertEquals(LinuxFix.CleanCaches, loaded.checks.first().fix)

            vm.cleanCaches()
            val cleaned = vm.uiState.first { it.message != null }
            assertEquals("Freed 200 MB.", cleaned.message?.text)
            vm.messageShown()
            val after = vm.uiState.first { it.busy == null && !it.refreshing && it.report?.storage?.caches?.isEmpty() == true }
            assertTrue(after.checks.none { it.level != HealthLevel.Ok }, after.checks.toString())

            vm.checkNetwork()
            val network = vm.uiState.first { it.report?.network != null }
            assertEquals(LinuxFix.ResetDns, network.checks.first().fix)

            vm.refresh()
            val refreshed = vm.uiState.first { !it.refreshing && !it.loading && it.report != null }
            assertEquals(false, refreshed.report?.network?.dnsOk, "a refresh keeps what was checked on request")
        } finally {
            host.clear()
        }
    }

    @Test
    fun `services a restart ended don't count, a crash does, and autostart is saved per service`() = runTest {
        try {
            val g = TestGraph(backgroundScope)
            g.linux.readyWithoutShell()
            fun service(name: String, lost: Boolean = false, exit: Int? = null) =
                LinuxEnvironment.ServiceStatus(name, "npx serve", running = false, lostOnRestart = lost, startedAt = 1, exitCode = exit)
            val source = FakeSource(report().copy(storage = report().storage?.copy(caches = emptyList()), services = listOf(service("web", lost = true))))
            val vm = host.add(LinuxHealthViewModel(g.linux, g.linuxSetup, source) {})
            backgroundScope.launch { vm.uiState.collect {} }

            val quiet = vm.uiState.first { it.report != null && !it.loading }
            assertEquals(HealthLevel.Ok, LinuxHealth.overall(quiet.checks), quiet.checks.toString())
            assertTrue(HealthSummary.problems(quiet.checks).isEmpty())
            assertEquals(ServiceAction.Start, serviceView(service("web", lost = true)).action)
            assertEquals("Stopped by restart", serviceView(service("web", lost = true)).label)

            source.report = source.report?.copy(services = listOf(service("web", lost = true), service("api", exit = 1)))
            vm.refresh()
            val crashed = vm.uiState.first { !it.refreshing && it.checks.any { c -> c.id == "service:api" } }
            assertEquals(listOf("service:api"), HealthSummary.problems(crashed.checks).map { it.id })
            assertEquals(HealthLevel.Problem, LinuxHealth.overall(crashed.checks))
            assertEquals("Crashed, code 1", serviceView(service("api", exit = 1)).label)
            assertEquals(ServiceAction.Restart, serviceView(service("api", exit = 1)).action)
            assertEquals("Didn't start", serviceView(service("web", lost = true).copy(autostart = true)).label)

            // There's no record for "web" in this Linux, so saving the switch says so instead of failing silently.
            vm.setAutostart("web", true)
            val message = vm.uiState.first { it.message != null }.message
            assertTrue(message?.problem == true && "no service" in message.text, message.toString())
            assertNull(vm.uiState.first { it.busy == null }.busyService)
        } finally {
            host.clear()
        }
    }

    @Test
    fun `a probe that fails shows the error, and trying again recovers`() = runTest {
        try {
            val g = TestGraph(backgroundScope)
            g.linux.readyWithoutShell()
            val source = FakeSource(report()).apply { failWith = java.io.IOException("disk read failed") }
            val vm = host.add(LinuxHealthViewModel(g.linux, g.linuxSetup, source) {})
            backgroundScope.launch { vm.uiState.collect {} }
            assertEquals("disk read failed", vm.uiState.first { it.error != null }.error)
            source.failWith = null
            vm.refresh()
            assertTrue(vm.uiState.first { it.report != null }.error == null)
        } finally {
            host.clear()
        }
    }
}
