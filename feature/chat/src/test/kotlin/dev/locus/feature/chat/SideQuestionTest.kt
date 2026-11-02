package dev.locus.feature.chat

import dev.locus.core.data.agent.SideThread
import dev.locus.core.model.AppSettings
import dev.locus.core.model.Role
import dev.locus.core.testing.MainDispatcherRule
import dev.locus.core.testing.ScriptedProvider
import dev.locus.core.testing.TestGraph
import dev.locus.core.testing.ViewModelHost
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Side questions (`/btw`) over the real runner and a scripted provider: asked while the agent works,
 * answered from a read-only copy of the chat, never stopping the task, never queued, never stored.
 */
class SideQuestionTest {
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

    private fun TestGraph.bodies(): List<String> =
        requests.filter { it.url.host == "llm.test" }.map { (it.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty() }

    private class Fixture(val graph: TestGraph, val chat: String, val vm: ChatViewModel)

    private suspend fun TestScope.fixture(): Fixture {
        val g = TestGraph(backgroundScope, quiet, dispatcher = StandardTestDispatcher(testScheduler))
        val chat = g.conversations.create(quiet.defaultProviderId).id
        val vm = host.add(ChatViewModel(chat, g.conversations, g.settingsRepository, g.directory, g.runner, g.browser, g.workspaceRepository, g.library, g.workspace, g.onDevice, g.sentRequests, g.sideQuestions, g.changes, g.compute, g.receipts))
        backgroundScope.launch { vm.uiState.collect {} }
        backgroundScope.launch { vm.aside.collect {} }
        runCurrent()
        return Fixture(g, chat, vm)
    }

    private suspend fun ChatViewModel.state(until: (ChatUiState) -> Boolean) = uiState.first { !it.loading && until(it) }
    private suspend fun ChatViewModel.side(until: (SideThread) -> Boolean) = aside.first { it != null && until(it) }!!

    private fun readNotes() = ScriptedProvider.toolCall("call_1", "read_file", """{"path":"notes.md"}""")

    @Test
    fun `a side question mid-task is answered apart, and the task carries on without it`() = runTest {
        val f = fixture()
        f.graph.workspace.resolve("notes.md").writeText("buy milk")
        // Step 1 reads the notes; step 2 is held, so the agent is mid-task when the side question goes.
        val step2 = Reply(ScriptedProvider.text("Tidied."), hold = CompletableDeferred())
        val side = Reply(ScriptedProvider.text("You asked me to tidy notes.md."))
        f.graph.replies(Reply(readNotes()), step2, side)
        f.vm.send("tidy my notes")
        step2.asked.await()

        f.vm.askAside(SideCommand.Ask("which file are you on?"))
        val answered = f.vm.side { it.current?.done == true }
        assertEquals("You asked me to tidy notes.md.", answered.current?.answer)
        assertTrue(answered.open)
        // The task wasn't stopped, and nothing was queued for it.
        val during = f.vm.state { true }
        assertTrue(during.run.running)
        assertTrue(during.run.queued.isEmpty())

        step2.hold!!.complete(Unit)
        f.vm.state { !it.run.running && it.items.filterIsInstance<ChatItem.Assistant>().isNotEmpty() }
        val history = f.graph.conversations.history(f.chat)
        assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.TOOL, Role.ASSISTANT), history.map { it.role })
        assertFalse(history.any { "which file are you on?" in it.text })

        // The side request is the held step's request plus one marked question: same prefix, same everything else.
        val (_, second, sideBody) = f.graph.bodies().map { Json.parseToJsonElement(it).jsonObject }
        val stepMessages = second.getValue("messages").jsonArray
        val sideMessages = sideBody.getValue("messages").jsonArray
        assertEquals(stepMessages, JsonArray(sideMessages.take(stepMessages.size)))
        assertEquals(stepMessages.size + 1, sideMessages.size)
        assertTrue("which file are you on?" in sideMessages.last().toString())
        assertEquals(second - "messages", sideBody - "messages")
    }

    @Test
    fun `closing hides the card, btw alone brings it back, clear forgets`() = runTest {
        val f = fixture()
        f.graph.replies(Reply(ScriptedProvider.text("First.")), Reply(ScriptedProvider.text("Second.")))
        f.vm.askAside(SideCommand.Ask("one?"))
        f.vm.side { it.current?.done == true }
        f.vm.dismissAside()
        assertFalse(f.vm.side { !it.open }.open)
        f.vm.askAside(SideCommand.Reopen)
        assertTrue(f.vm.side { it.open }.open)

        f.vm.askAside(SideCommand.Ask("two?"))
        val two = f.vm.side { it.exchanges.size == 2 && it.current?.done == true }
        assertEquals(listOf("one?"), two.earlier.map { it.question })
        // The earlier exchange is replayed with the new question.
        assertTrue("Q: one?" in f.graph.bodies().last())
        // Nothing reached the chat.
        assertTrue(f.graph.conversations.history(f.chat).isEmpty())

        f.vm.clearAside()
        assertNull(f.vm.aside.first { it == null })
    }
}
