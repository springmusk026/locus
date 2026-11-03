package dev.locus.feature.workspace.linux.health

import dev.locus.core.linux.LinuxInventory
import dev.locus.core.testing.MainDispatcherRule
import dev.locus.core.testing.TestGraph
import dev.locus.core.testing.ViewModelHost
import dev.locus.core.testing.readyWithoutShell
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinuxPackagesViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val host = ViewModelHost()

    private class FakeSource(var packages: List<LinuxInventory.Package>) : LinuxPackagesSource {
        val removed = mutableListOf<String>()
        override suspend fun packages() = packages
        override suspend fun upgrades() = listOf(LinuxInventory.Upgrade("git", "2.49.0-r0", "2.49.1-r0"))
        override suspend fun uninstall(name: String) {
            removed += name
            packages = packages.filterNot { it.name == name }
        }
    }

    /** Alpine's base, two packages the user installed, and 600 dependencies. */
    private fun many(): List<LinuxInventory.Package> =
        LinuxInventory.BASE_SYSTEM.map { LinuxInventory.Package(it, "1-r0", explicit = true) } +
            listOf(
                LinuxInventory.Package("python3", "3.12.8-r1", "A high-level scripting language", explicit = true),
                LinuxInventory.Package("git", "2.49.0-r0", "Distributed version control system", explicit = true),
            ) +
            (0 until 600).map { LinuxInventory.Package("lib-$it", "1.0-r0", if (it == 7) "python helper" else "a library") }

    @Test
    fun `defaults to what the user installed, with all and search as filters`() = runTest {
        try {
            val g = TestGraph(backgroundScope)
            g.linux.readyWithoutShell()
            val source = FakeSource(many())
            val vm = host.add(LinuxPackagesViewModel(g.linux, source, filtering = main.dispatcher))
            backgroundScope.launch { vm.uiState.collect {} }

            val loaded = vm.uiState.first { it.packages != null && it.shown.isNotEmpty() }
            assertEquals(listOf("git", "python3"), loaded.shown.map { it.name }.sorted())
            assertEquals(2, loaded.userCount)

            vm.showAll(true)
            assertEquals(608, vm.uiState.first { it.all && it.shown.size > 2 }.shown.size)

            vm.setQuery("python")
            assertEquals(listOf("python3", "lib-7"), vm.uiState.first { it.query == "python" && it.shown.size == 2 }.shown.map { it.name })

            vm.showAll(false)
            assertEquals(listOf("python3"), vm.uiState.first { !it.all && it.shown.size == 1 }.shown.map { it.name })

            vm.checkUpgrades()
            assertEquals(1, vm.uiState.first { it.upgrades != null }.upgrades?.size)
        } finally {
            host.clear()
        }
    }

    @Test
    fun `only the user's own packages can be uninstalled`() = runTest {
        try {
            val g = TestGraph(backgroundScope)
            g.linux.readyWithoutShell()
            val source = FakeSource(many())
            val vm = host.add(LinuxPackagesViewModel(g.linux, source, filtering = main.dispatcher))
            backgroundScope.launch { vm.uiState.collect {} }
            vm.uiState.first { it.packages != null }

            vm.uninstall("busybox")
            vm.uninstall("lib-3")
            vm.uninstall("git")
            val done = vm.uiState.first { it.message != null }
            assertEquals("Removed git.", done.message?.text)
            assertEquals(listOf("git"), source.removed, "base and dependency packages are never removed")
            assertTrue(vm.uiState.first { it.packages?.none { p -> p.name == "git" } == true }.shown.none { it.name == "git" })
        } finally {
            host.clear()
        }
    }

    @Test
    fun `a reload never pairs the new list with the old filter`() = runTest {
        try {
            val g = TestGraph(backgroundScope)
            g.linux.readyWithoutShell()
            val source = FakeSource(many())
            // The filter only runs when the test yields, like a busy Dispatchers.Default.
            val slow = StandardTestDispatcher(testScheduler)
            val vm = host.add(LinuxPackagesViewModel(g.linux, source, filtering = slow))
            val seen = mutableListOf<LinuxPackagesUiState>()
            backgroundScope.launch { vm.uiState.collect { seen += it } }
            vm.uiState.first { it.shown.isNotEmpty() }

            vm.uninstall("git")
            val after = vm.uiState.first { it.packages?.none { p -> p.name == "git" } == true }
            assertEquals(listOf("python3"), after.shown.map { it.name })
            seen.forEach { state ->
                val names = state.packages.orEmpty().map { it.name }.toSet()
                assertTrue(state.shown.all { it.name in names }, "shown ${state.shown.map { it.name }} isn't in its own package list")
            }
        } finally {
            host.clear()
        }
    }
}
