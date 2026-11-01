package dev.locus.feature.chat

import dev.locus.core.data.agent.MessageQueue
import dev.locus.core.model.AppSettings
import dev.locus.core.model.Role
import dev.locus.core.model.ToolPolicy
import dev.locus.core.testing.MainDispatcherRule
import dev.locus.core.testing.ScriptedProvider
import dev.locus.core.testing.TestGraph
import dev.locus.core.testing.ViewModelHost
import io.ktor.client.engine.mock.respond
import io.ktor.http.content.OutgoingContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
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
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Steer and queue over the real runner (TestGraph) and a scripted provider: messages sent while the
 * agent works wait for the next step boundary, or start the next turn; they can be edited, cancelled,
 * survive a restart, and never get in the way of ask_user or approvals.
 */
class SteerAndQueueTest {
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

    /** One scripted reply; with [hold], the request waits until it's completed (the agent is mid-step). */
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

    /** The chat requests' bodies, oldest first. */
    private fun TestGraph.bodies(): List<String> =
        requests.filter { it.url.host == "llm.test" }.map { (it.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty() }

    private class Fixture(val graph: TestGraph, val chat: String, val vm: ChatViewModel)

    private suspend fun TestScope.fixture(settings: AppSettings = quiet, root: File? = null, before: suspend (TestGraph, String) -> Unit = { _, _ -> }): Fixture {
        val g = if (root == null) TestGraph(backgroundScope, settings, dispatcher = StandardTestDispatcher(testScheduler)) else TestGraph(backgroundScope, settings, root, StandardTestDispatcher(testScheduler))
        val chat = g.conversations.create(settings.defaultProviderId).id
        before(g, chat)
        val vm = host.add(ChatViewModel(chat, g.conversations, g.settingsRepository, g.directory, g.runner, g.browser, g.workspaceRepository, g.library, g.workspace, g.onDevice, g.sentRequests, g.sideQuestions, g.changes, g.compute))
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        return Fixture(g, chat, vm)
    }

    private suspend fun ChatViewModel.state(until: (ChatUiState) -> Boolean) = uiState.first { !it.loading && until(it) }

    private suspend fun Fixture.history() = graph.conversations.history(chat)

    private fun readNotes() = ScriptedProvider.toolCall("call_1", "read_file", """{"path":"notes.md"}""")

    @Test
    fun `a message sent while the agent works waits for the step boundary, then the model sees it`() = runTest {
        val f = fixture()
        f.graph.workspace.resolve("notes.md").writeText("buy milk")
        val step = Reply(readNotes(), hold = CompletableDeferred())
        f.graph.replies(step, Reply(ScriptedProvider.text("Done, and I kept the drafts.")))
        f.vm.send("tidy my notes")
        step.asked.await()

        f.vm.send("  also keep the drafts ")
        val queued = f.vm.state { it.run.queued.isNotEmpty() }
        assertTrue(queued.run.running)
        assertEquals(listOf("also keep the drafts"), queued.run.queued.map { it.text })
        // Not in the chat yet: it waits for the boundary.
        assertEquals(listOf(Role.USER), f.history().map { it.role })

        step.hold!!.complete(Unit)
        val s = f.vm.state { !it.run.running && it.items.any { i -> i is ChatItem.Assistant } }
        assertTrue(s.run.queued.isEmpty())
        val history = f.history()
        assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.TOOL, Role.USER, Role.ASSISTANT), history.map { it.role })
        assertEquals("also keep the drafts", history[3].userText)
        // Joined mid-task: the hidden note tells the agent to take it into account and carry on, and the
        // transcript shows it as told while working, inside the task.
        assertTrue(dev.locus.core.model.ModeNote.isMidTask(history[3]))
        assertTrue(s.items.filterIsInstance<ChatItem.User>().single { it.key == history[3].id }.midTask)
        // The second request carries it after the tool result, and starts with exactly the first request's messages.
        val (first, second) = f.graph.bodies().map { Json.parseToJsonElement(it).jsonObject }
        val before = first.getValue("messages").jsonArray
        val after = second.getValue("messages").jsonArray
        assertEquals(before, JsonArray(after.take(before.size)))
        assertEquals(before.size + 3, after.size) // the assistant's call, its result, the queued message
        assertTrue("also keep the drafts" in after.last().toString())
        assertTrue("It is not a new task" in after.last().toString())
        assertTrue("buy milk" in after[after.size - 2].toString())
        // Everything else (system prompt, tools, settings) is byte for byte the same.
        assertEquals(first - "messages", second - "messages")
    }

    @Test
    fun `several queued messages arrive in order, after edits and cancels`() = runTest {
        val f = fixture()
        f.graph.workspace.resolve("notes.md").writeText("buy milk")
        val step = Reply(readNotes(), hold = CompletableDeferred())
        f.graph.replies(step, Reply(ScriptedProvider.text("ok")))
        f.vm.send("tidy my notes")
        step.asked.await()

        f.vm.send("first")
        f.vm.send("second")
        f.vm.send("third")
        val three = f.vm.state { it.run.queued.size == 3 }.run.queued
        f.vm.editQueued(three[0].id, "first, edited")
        f.vm.cancelQueued(three[1].id)
        assertEquals(listOf("first, edited", "third"), f.vm.state { it.run.queued.size == 2 }.run.queued.map { it.text })

        step.hold!!.complete(Unit)
        f.vm.state { !it.run.running && it.items.any { i -> i is ChatItem.Assistant } }
        assertEquals(listOf("tidy my notes", "first, edited", "third"), f.history().filter { it.role == Role.USER }.map { it.userText })
    }

    @Test
    fun `editing a message that was already sent sends the new text instead of losing it`() = runTest {
        val f = fixture()
        f.graph.replies(Reply(ScriptedProvider.text("Hello.")), Reply(ScriptedProvider.text("Sure.")))
        f.vm.send("hi")
        f.vm.state { !it.run.running && it.items.any { i -> i is ChatItem.Assistant } }
        f.vm.editQueued("gone", "a second thought")
        f.vm.state { !it.run.running && it.items.filterIsInstance<ChatItem.Assistant>().size == 2 }
        assertEquals(listOf("hi", "a second thought"), f.history().filter { it.role == Role.USER }.map { it.userText })
    }

    @Test
    fun `a turn that ends before a boundary starts the next turn with the queued message`() = runTest {
        val f = fixture()
        val reply = Reply(ScriptedProvider.text("Here's the summary."), hold = CompletableDeferred())
        f.graph.replies(reply, Reply(ScriptedProvider.text("Emailed.")))
        f.vm.send("summarise my day")
        reply.asked.await()
        f.vm.send("then email it to me")
        f.vm.state { it.run.queued.isNotEmpty() }

        reply.hold!!.complete(Unit)
        f.vm.state { !it.run.running && it.items.filterIsInstance<ChatItem.Assistant>().size == 2 }
        assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.USER, Role.ASSISTANT), f.history().map { it.role })
        assertEquals("then email it to me", f.history()[2].userText)
        // The turn had ended, so it starts the next turn as an ordinary message, not a mid-task note.
        assertFalse(dev.locus.core.model.ModeNote.isMidTask(f.history()[2]))
        assertEquals(2, f.graph.bodies().size)
    }

    @Test
    fun `after Stop queued messages wait, then Send or Discard`() = runTest {
        val f = fixture()
        val reply = Reply(ScriptedProvider.text("never"), hold = CompletableDeferred())
        f.graph.replies(reply, Reply(ScriptedProvider.text("Got both.")))
        f.vm.send("start")
        reply.asked.await()
        f.vm.send("one more thing")
        f.vm.state { it.run.queued.isNotEmpty() }
        f.vm.stop()
        val stopped = f.vm.state { !it.run.running }
        assertEquals(listOf("one more thing"), stopped.run.queued.map { it.text })
        assertEquals(listOf("start"), f.history().filter { it.role == Role.USER }.map { it.userText })

        f.vm.resume()
        f.vm.state { !it.run.running && it.items.any { i -> i is ChatItem.Assistant } }
        assertEquals(listOf("start", "one more thing"), f.history().filter { it.role == Role.USER }.map { it.userText })
        assertTrue(f.vm.state { true }.run.queued.isEmpty())
    }

    @Test
    fun `queued messages survive a restart and are offered to send or discard`() = runTest {
        val root = java.nio.file.Files.createTempDirectory("locus-queue").toFile()
        // As if the app died with two messages queued.
        val f = fixture(root = root) { g, chat ->
            val saved = MessageQueue(File(g.context.noBackupFilesDir, "queued"))
            saved.add(chat, "first")
            saved.add(chat, "second")
        }
        val shown = f.vm.state { it.run.queued.size == 2 }
        assertFalse(shown.run.running)
        assertEquals(listOf("first", "second"), shown.run.queued.map { it.text })

        f.graph.replies(Reply(ScriptedProvider.text("On it.")))
        f.vm.resume()
        f.vm.state { !it.run.running && it.items.any { i -> i is ChatItem.Assistant } }
        assertEquals(listOf("first", "second"), f.history().filter { it.role == Role.USER }.map { it.userText })
    }

    @Test
    fun `Discard drops a recovered queue, and a message the chat already has is not offered again`() = runTest {
        var keptId = ""
        val f = fixture { g, chat ->
            val saved = MessageQueue(File(g.context.noBackupFilesDir, "queued"))
            val sent = saved.add(chat, "already sent")
            saved.add(chat, "still queued")
            keptId = sent.id
            // The app died after storing the first one but before forgetting it.
            g.conversations.append(dev.locus.core.model.Message(sent.id, chat, Role.USER, listOf(dev.locus.core.model.Part.Text(sent.text)), 1))
        }
        val shown = f.vm.state { it.run.queued.isNotEmpty() }
        assertEquals(listOf("still queued"), shown.run.queued.map { it.text })
        assertTrue(f.history().any { it.id == keptId })

        f.vm.discardQueued()
        assertTrue(f.vm.state { it.run.queued.isEmpty() }.run.queued.isEmpty())
        assertTrue(f.graph.bodies().isEmpty())
    }

    @Test
    fun `while a question is open, typing answers it, and once answered typing queues`() = runTest {
        val f = fixture()
        val after = Reply(ScriptedProvider.text("Blue it is."), hold = CompletableDeferred())
        f.graph.replies(Reply(ScriptedProvider.toolCall("q1", "ask_user", """{"question":"Which colour?"}""")), after, Reply(ScriptedProvider.text("The gate too.")))
        f.vm.send("paint the fence")
        f.vm.state { it.pendingQuestion != null }

        f.vm.send("blue")
        after.asked.await()
        // The answer went to the question, not the queue.
        assertTrue(f.vm.state { it.pendingQuestion == null }.run.queued.isEmpty())
        assertTrue("blue" in f.history().flatMap { it.toolResults }.single().content)

        f.vm.send("and the gate too")
        assertEquals(listOf("and the gate too"), f.vm.state { it.run.queued.isNotEmpty() }.run.queued.map { it.text })
        after.hold!!.complete(Unit)
        f.vm.state { !it.run.running && it.run.queued.isEmpty() && it.items.filterIsInstance<ChatItem.Assistant>().size == 2 }
    }

    @Test
    fun `a pending approval doesn't block queueing`() = runTest {
        val f = fixture(quiet.copy(toolPolicies = mapOf("read_file" to ToolPolicy.ASK)))
        f.graph.workspace.resolve("notes.md").writeText("buy milk")
        f.graph.replies(Reply(readNotes()), Reply(ScriptedProvider.text("Read it.")))
        f.vm.send("read my notes")
        val asking = f.vm.state { it.pendingApproval != null }

        f.vm.send("then delete nothing")
        assertEquals(listOf("then delete nothing"), f.vm.state { it.run.queued.isNotEmpty() }.run.queued.map { it.text })
        f.vm.answer(asking.pendingApproval!!.callId, true)
        f.vm.state { !it.run.running && it.items.any { i -> i is ChatItem.Assistant } }
        assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.TOOL, Role.USER, Role.ASSISTANT), f.history().map { it.role })
    }
}
