package dev.locus.feature.chat

import dev.locus.core.browser.PickedElement
import dev.locus.core.data.repository.ChatDrafts
import dev.locus.core.model.AppSettings
import dev.locus.core.testing.MainDispatcherRule
import dev.locus.core.testing.ScriptedProvider
import dev.locus.core.testing.TestGraph
import dev.locus.core.testing.ViewModelHost
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Deleting a chat from the list (#338) stops what runs in it and leaves nothing about it behind, and a chat's
 * unsent text shows in its row as "Draft:" (#319). The real runner and browser over TestGraph.
 */
class DeleteChatTest {
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

    private class Reply(val body: String, val hold: CompletableDeferred<Unit>? = null) {
        val asked = CompletableDeferred<Unit>()
    }

    private fun TestGraph.replies(vararg replies: Reply) {
        val queue = ArrayDeque(replies.toList())
        http = { request ->
            if (request.url.host == "llm.test") {
                val next = synchronized(queue) { queue.removeFirstOrNull() } ?: error("No scripted reply left")
                next.asked.complete(Unit)
                next.hold?.await()
                respond(next.body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
            } else {
                respond("", HttpStatusCode.NotFound)
            }
        }
    }

    private class Fixture(val graph: TestGraph, val drafts: ChatDrafts, val vm: ConversationsViewModel)

    private fun TestScope.fixture(): Fixture {
        val g = TestGraph(backgroundScope, settings, dispatcher = StandardTestDispatcher(testScheduler))
        val drafts = ChatDrafts(dir = null)
        val vm = host.add(
            ConversationsViewModel(
                g.conversations, g.settingsRepository, g.directory, g.runner, g.library, g.tasks, g.workspaceRepository,
                browser = g.browser, drafts = drafts,
            ),
        )
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        return Fixture(g, drafts, vm)
    }

    private suspend fun ConversationsViewModel.ready(until: (ConversationsUiState.Ready) -> Boolean = { true }) =
        uiState.first { it is ConversationsUiState.Ready && until(it) } as ConversationsUiState.Ready

    private val pick = PickedElement(ref = "e3", role = "button", name = "Reserve", tag = "button", url = "https://shop.example/", title = "Shop")

    @Test
    fun `deleting a running chat stops its run and forgets it everywhere`() = runTest {
        val f = fixture()
        val g = f.graph
        g.workspace.resolve("notes.md").writeText("buy milk")
        val step = Reply(ScriptedProvider.toolCall("call_1", "read_file", """{"path":"notes.md"}"""), hold = CompletableDeferred())
        val next = Reply(ScriptedProvider.text("Read it."))
        g.replies(step, next)
        val chat = g.conversations.create(settings.defaultProviderId).id
        g.runner.send(chat, "read my notes")
        step.asked.await()
        assertTrue(g.runner.states.value[chat]?.running == true)
        // The user had the chat's browser and picked an element on its page.
        g.browser.focus(chat)
        assertTrue(g.browser.takeOver(chat))
        assertTrue(g.browser.attachPick(chat, pick))
        f.drafts.set(chat, "and the receipts")

        f.vm.delete(chat)
        runCurrent()
        step.hold!!.complete(Unit)
        advanceUntilIdle()

        // The run stopped: its next step never asked the model, and the rows are gone.
        assertFalse(next.asked.isCompleted)
        assertNull(g.conversations.get(chat))
        // Nothing about the chat is kept.
        assertFalse(chat in g.runner.states.value)
        assertFalse(chat in g.browser.attachedPicks.value)
        assertFalse(g.browser.stateOf(chat).value.userControl)
        assertTrue(g.browser.addressBarHosts(chat).isEmpty())
        assertNull(f.drafts.get(chat))
    }

    @Test
    fun `a chat's unsent text shows in its row, on one line`() = runTest {
        val f = fixture()
        val chat = f.graph.conversations.create(settings.defaultProviderId).id
        f.graph.conversations.appendUserMessage(chat, "hello")
        assertNull(f.vm.ready { it.rows.isNotEmpty() }.rows.single().draft)

        f.drafts.set(chat, "and then\nthe receipts")
        assertEquals("and then\nthe receipts", f.vm.ready { it.rows.single().draft != null }.rows.single().draft)

        f.drafts.set(chat, "  ")
        assertNull(f.vm.ready { it.rows.single().draft == null }.rows.single().draft)
    }
}
