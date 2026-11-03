package dev.locus.feature.workspace.linux

import dev.locus.core.data.linux.InstallEstimate
import dev.locus.core.data.linux.LinuxPackage
import dev.locus.core.data.linux.LinuxPanel
import dev.locus.core.testing.MainDispatcherRule
import dev.locus.core.testing.TestGraph
import dev.locus.core.testing.ViewModelHost
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The Linux card on a phone without Linux: the install offer and its estimate follow the ticked tools. */
class LinuxViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val host = ViewModelHost()

    @Test
    fun `not installed offers Python and Git, and ticking changes the estimate`() = runTest {
        try {
            val g = TestGraph(backgroundScope)
            var closed = 0
            val vm = host.add(LinuxViewModel(g.linuxSetup, g.linux) { closed++ })
            backgroundScope.launch { vm.uiState.collect {} }

            val offer = vm.uiState.first { it.panel is LinuxPanel.NotInstalled }
            assertEquals(setOf(LinuxPackage.Python, LinuxPackage.Git), offer.selected)
            assertEquals(InstallEstimate.of(offer.selected), assertIs<LinuxPanel.NotInstalled>(offer.panel).estimate)

            vm.toggle(LinuxPackage.Python)
            val gitOnly = vm.uiState.first { it.selected == setOf(LinuxPackage.Git) }
            assertEquals(InstallEstimate.of(setOf(LinuxPackage.Git)), assertIs<LinuxPanel.NotInstalled>(gitOnly.panel).estimate)
            assertEquals(0, closed)
        } finally {
            host.clear()
        }
    }
}
