package dev.locus.feature.chat

import dev.locus.core.browser.PageRecord
import dev.locus.core.browser.PickedElement
import dev.locus.core.model.AppSettings
import dev.locus.core.model.ModeNote
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import dev.locus.core.testing.MainDispatcherRule
import dev.locus.core.testing.ScriptedProvider
import dev.locus.core.testing.ScriptedProvider.script
import dev.locus.core.testing.TestGraph
import dev.locus.core.testing.ViewModelHost
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The browser's two chat-facing parts (docs/design/browser.md, "Element picker" and "The page as it was"),
 * over the real repositories and runner: an element picked in the browser goes with the next message as
 * outside text and shows under it, and a past browser step's page comes back from Locus's own record
 * without touching the transcript.
 */
class BrowserPagesChatTest {
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

    private val quiet = AppSettings(
        providers = listOf(ScriptedProvider.config), defaultProviderId = ScriptedProvider.config.id,
        autoTitle = false, suggestFollowUps = false, reflectAfterTasks = false,
    )

    private class Fixture(val graph: TestGraph, val chat: String, val vm: ChatViewModel)

    private suspend fun TestScope.fixture(): Fixture {
        val g = TestGraph(backgroundScope, quiet, dispatcher = StandardTestDispatcher(testScheduler))
        val chat = g.conversations.create(quiet.defaultProviderId).id
        val vm = host.add(ChatViewModel(chat, g.conversations, g.settingsRepository, g.directory, g.runner, g.browser, g.workspaceRepository, g.library, g.workspace, g.onDevice, g.sentRequests, g.sideQuestions, g.changes, g.compute, g.receipts))
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        return Fixture(g, chat, vm)
    }

    private val reserve = PickedElement(
        ref = "e57", role = "button", name = "Reserve", tag = "button", text = "Reserve now",
        url = "https://www.booking.com/hotel/x", title = "Hotel X",
    )

    @Test
    fun `a picked element waits in the composer and goes with the next message as outside text`() = runTest {
        val f = fixture()
        assertTrue(f.graph.browser.attachPick(f.chat, reserve))
        val waiting = f.vm.uiState.first { it.pendingPick != null }
        assertEquals("e57", waiting.pendingPick?.ref)

        f.graph.script(ScriptedProvider.text("Clicking it."))
        f.vm.send("book this one")
        val s = f.vm.uiState.first { st -> !st.run.running && st.items.any { it is ChatItem.Assistant } }

        // Shown under the user's words, and gone from the composer.
        val user = s.items.filterIsInstance<ChatItem.User>().single()
        assertEquals("book this one", user.text)
        assertEquals("button “Reserve” · booking.com", user.picked)
        assertNull(s.pendingPick)
        assertNull(f.graph.browser.attachedPicks.value[f.chat])

        // Stored as a result part after the typed text: never the user's own words.
        val stored = f.graph.conversations.history(f.chat).first { it.role == Role.USER }
        assertEquals("book this one", stored.userText)
        val note = stored.parts.filterIsInstance<Part.Text>().single { ModeNote.isResult(it.text) }.text
        assertTrue(ModeNote.PICKED_ELEMENT_OPEN in note && "Its ref is e57" in note)
        assertTrue(ModeNote.hasPickedElement(stored))

        // And it reached the model in the request.
        val body = (f.graph.requests.last().body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
        assertTrue("Its ref is e57" in body)
    }

    @Test
    fun `a pick alone can be sent, and the composer's cross drops it`() = runTest {
        val f = fixture()
        f.graph.browser.attachPick(f.chat, reserve)
        f.vm.uiState.first { it.pendingPick != null }
        f.vm.removePick()
        assertNull(f.vm.uiState.first { it.pendingPick == null }.pendingPick)

        f.graph.browser.attachPick(f.chat, reserve)
        f.vm.uiState.first { it.pendingPick != null }
        f.graph.script(ScriptedProvider.text("Which one?"))
        f.vm.send("")
        val s = f.vm.uiState.first { st -> !st.run.running && st.items.any { it is ChatItem.Assistant } }
        val user = s.items.filterIsInstance<ChatItem.User>().single()
        assertEquals("", user.text)
        assertNotNull(user.picked)
    }

    @Test
    fun `a past browser step's page comes back from the record, untouched in the transcript`() = runTest {
        val f = fixture()
        f.graph.browserPages.save(f.chat, "call_1", PageRecord("https://shop.example/", "Shop", 5L, "Page: Shop\nURL: https://shop.example/\n\n[e1] button \"Buy\""), jpeg = byteArrayOf(1, 2))
        val kept = assertIs<PageView.Kept>(f.vm.pageAt("call_1", "anything"))
        assertEquals("Shop", kept.record.title)
        assertNotNull(kept.picture)

        // Nothing kept: what the step's own result said about the page.
        val missing = assertIs<PageView.NotKept>(f.vm.pageAt("call_2", "<untrusted_web_content>\nPage: Old\nURL: https://old.example/\n</untrusted_web_content>"))
        assertEquals("Old", missing.title)
        assertEquals("https://old.example/", missing.url)
        assertEquals(PageView.NotKept(null, null), f.vm.pageAt("call_3", "No page is open."))
        // Reading it adds nothing to the chat.
        assertTrue(f.graph.conversations.history(f.chat).isEmpty())
    }

    @Test
    fun `deleting a chat deletes the pages it kept`() = runTest {
        val f = fixture()
        f.graph.browserPages.save(f.chat, "call_1", PageRecord("https://shop.example/", "Shop", 5L, "text"))
        f.graph.conversations.delete(f.chat)
        // Deleted in the background, off the caller's thread: wait for that deletion itself.
        f.graph.browserPages.awaitForgetting()
        assertFalse(f.graph.browserPages.has(f.chat, "call_1"))
    }
}
