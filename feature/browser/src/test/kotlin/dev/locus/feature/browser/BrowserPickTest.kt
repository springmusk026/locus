package dev.locus.feature.browser

import dev.locus.core.browser.BrowserState
import dev.locus.core.browser.PendingDialog
import dev.locus.core.model.AppSettings
import dev.locus.core.testing.MainDispatcherRule
import dev.locus.core.testing.ScriptedProvider
import dev.locus.core.testing.TestGraph
import dev.locus.core.testing.ViewModelHost
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** When "Pick an element" is offered, and what back does while picking (docs/design/browser.md, "Element picker"). */
class BrowserPickTest {
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

    private val page = BrowserState(url = "https://www.booking.com/hotel/pt/x.html", hasPage = true, canGoBack = true)

    @Test
    fun `a chat's page the user can drive can be picked on`() {
        assertTrue(BrowserUi(page).canPick)
        // Taken over: the user drives though the agent runs.
        assertTrue(BrowserUi(page.copy(userControl = true), agentRunning = true).canPick)
        // Locus's own hand-over ("Your turn").
        assertTrue(BrowserUi(page.copy(handoffReason = "Sign in"), agentRunning = true).canPick)
    }

    @Test
    fun `not while Locus browses, without a page, in the user's own browsing or behind a dialog`() {
        assertFalse(BrowserUi(page, agentRunning = true).canPick)
        assertFalse(BrowserUi(BrowserState()).canPick)
        assertFalse(BrowserUi(page, standalone = true).canPick)
        assertFalse(BrowserUi(page.copy(dialog = PendingDialog("confirm", "Leave?", ""))).canPick)
    }

    @Test
    fun `back ends picking before anything else`() {
        assertEquals(BrowserBack.EndPick, browserBack(BrowserUi(page, pick = PickMode()), editing = true))
        assertEquals(BrowserBack.EndPick, browserBack(BrowserUi(page, pick = PickMode()), editing = false))
        assertEquals(BrowserBack.PageBack, browserBack(BrowserUi(page), editing = false))
    }

    @Test
    fun `with no page open, picking doesn't start and nothing is attached`() = runTest {
        val settings = AppSettings(providers = listOf(ScriptedProvider.config), defaultProviderId = ScriptedProvider.config.id)
        val g = TestGraph(backgroundScope, settings, dispatcher = StandardTestDispatcher(testScheduler))
        val chat = g.conversations.create(settings.defaultProviderId).id
        g.browser.focus(chat)
        val vm = host.add(BrowserViewModel(g.browser, g.runner))
        runCurrent()
        vm.startPick()
        runCurrent()
        assertNull(vm.uiState.value.pick)
        var done: Boolean? = null
        vm.attachPick { done = it }
        runCurrent()
        // Nothing selected: nothing to attach, and nothing waits in the chat's composer.
        assertNull(done)
        assertNull(g.browser.attachedPicks.value[chat])
    }
}
