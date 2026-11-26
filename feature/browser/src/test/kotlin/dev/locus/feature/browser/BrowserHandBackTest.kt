package dev.locus.feature.browser

import dev.locus.core.model.AppSettings
import dev.locus.core.testing.MainDispatcherRule
import dev.locus.core.testing.ScriptedProvider
import dev.locus.core.testing.TestGraph
import dev.locus.core.testing.ViewModelHost
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Take over and hand back follow the chat whose browser the user took, not whichever chat is focused when they
 * hand it back or leave (#340).
 */
class BrowserHandBackTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val host = ViewModelHost()

    private fun runTest(body: suspend TestScope.() -> Unit) = kotlinx.coroutines.test.runTest {
        try {
            body()
        } finally {
            host.clear()
        }
    }

    private val settings = AppSettings(
        providers = listOf(ScriptedProvider.config), defaultProviderId = ScriptedProvider.config.id,
        autoTitle = false, suggestFollowUps = false, reflectAfterTasks = false,
    )

    private class Fixture(val graph: TestGraph, val first: String, val second: String, val vm: BrowserViewModel)

    private suspend fun TestScope.fixture(): Fixture {
        val g = TestGraph(backgroundScope, settings, dispatcher = StandardTestDispatcher(testScheduler))
        val first = g.conversations.create(settings.defaultProviderId).id
        val second = g.conversations.create(settings.defaultProviderId).id
        g.browser.focus(first)
        val vm = host.add(BrowserViewModel(g.browser, g.runner))
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        return Fixture(g, first, second, vm)
    }

    @Test
    fun `hand back after switching chats releases the chat that was taken`() = runTest {
        val f = fixture()
        assertTrue(f.vm.takeOver())
        assertTrue(f.graph.browser.stateOf(f.first).value.userControl)

        // Another chat comes on screen (its browser panel opened) before the user hands back.
        f.graph.browser.focus(f.second)
        f.vm.handBack("")

        assertFalse(f.graph.browser.stateOf(f.first).value.userControl)
        assertFalse(f.graph.browser.stateOf(f.second).value.userControl)
    }

    @Test
    fun `leaving the browser after switching chats gives back the chat that was taken`() = runTest {
        val f = fixture()
        assertTrue(f.vm.takeOver())
        f.graph.browser.focus(f.second)

        host.clear()

        assertFalse(f.graph.browser.stateOf(f.first).value.userControl)
    }
}
