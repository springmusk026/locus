package dev.locus.feature.chat

import dev.locus.core.model.AppSettings
import dev.locus.core.model.ApprovalDecision
import dev.locus.core.model.ModeNote
import dev.locus.core.model.ReviewerMode
import dev.locus.core.model.Role
import dev.locus.core.model.ToolPolicy
import dev.locus.core.model.startsTurn
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * "Run anyway" on a call the safety reviewer blocked, over the real runner (TestGraph) and a scripted
 * provider whose reviewer denies every write: while the task runs, the call runs at the next step
 * boundary and its result joins that task; when the chat is idle, it runs now and starts a turn.
 */
class RunAnywayTest {
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

    private val reviewed = AppSettings(
        providers = listOf(ScriptedProvider.config), defaultProviderId = ScriptedProvider.config.id,
        autoTitle = false, suggestFollowUps = false, reflectAfterTasks = false,
        reviewerMode = ReviewerMode.REVIEW_ASKS,
        // Set to Ask, so the reviewer's deny blocks it (an allowed call would only be passed on to the user).
        toolPolicies = mapOf("write_file" to ToolPolicy.ASK),
    )

    private class Reply(val body: String, val hold: CompletableDeferred<Unit>? = null) {
        val asked = CompletableDeferred<Unit>()
    }

    private fun OutgoingContent.text() = (this as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()

    private fun isReview(body: String) = "safety reviewer for an AI agent" in body

    /** Chat requests get [replies] in order; the reviewer denies everything it's asked. */
    private fun TestGraph.replies(vararg replies: Reply) {
        val queue = ArrayDeque(replies.toList())
        http = { request ->
            if (request.url.host != "llm.test") {
                respond("", HttpStatusCode.NotFound)
            } else if (isReview(request.body.text())) {
                respond(ScriptedProvider.text("""{"verdict":"deny","reason":"not part of the task"}"""), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
            } else {
                val next = synchronized(queue) { queue.removeFirstOrNull() } ?: error("No scripted reply left")
                next.asked.complete(Unit)
                next.hold?.await()
                respond(next.body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
            }
        }
    }

    /** The chat requests' bodies (not the reviewer's), oldest first. */
    private fun TestGraph.bodies(): List<String> =
        requests.filter { it.url.host == "llm.test" }.map { it.body.text() }.filterNot(::isReview)

    private class Fixture(val graph: TestGraph, val chat: String, val vm: ChatViewModel)

    private suspend fun TestScope.fixture(): Fixture {
        val g = TestGraph(backgroundScope, reviewed, dispatcher = StandardTestDispatcher(testScheduler))
        val chat = g.conversations.create(reviewed.defaultProviderId).id
        val vm = host.add(ChatViewModel(chat, g.conversations, g.settingsRepository, g.directory, g.runner, g.browser, g.workspaceRepository, g.library, g.workspace, g.onDevice, g.sentRequests, g.sideQuestions, g.changes, g.compute, g.receipts))
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        return Fixture(g, chat, vm)
    }

    private suspend fun ChatViewModel.state(until: (ChatUiState) -> Boolean) = uiState.first { !it.loading && until(it) }

    private suspend fun Fixture.history() = graph.conversations.history(chat)

    private fun writePlan() = ScriptedProvider.toolCall("c1", "write_file", """{"path":"plan.md","content":"step one\n"}""")

    private fun readNotes() = ScriptedProvider.toolCall("c2", "read_file", """{"path":"notes.md"}""")

    @Test
    fun `while the task runs, the call runs at the next step boundary and the next request sees its result`() = runTest {
        val f = fixture()
        f.graph.workspace.resolve("notes.md").writeText("buy milk")
        val second = Reply(readNotes(), hold = CompletableDeferred())
        f.graph.replies(Reply(writePlan()), second, Reply(ScriptedProvider.text("Done.")))
        f.vm.send("tidy my notes")
        second.asked.await()

        val blocked = f.vm.state { it.run.reviewerBlocks.isNotEmpty() }
        assertTrue(blocked.run.running)
        f.vm.runAnyway("c1")
        assertTrue(f.vm.state { it.run.reviewerBlocks.singleOrNull()?.queued == true }.run.running)
        // Not run yet: it waits for the step boundary.
        assertFalse(f.graph.workspace.resolve("plan.md").exists())

        second.hold!!.complete(Unit)
        val done = f.vm.state { !it.run.running && it.items.any { i -> i is ChatItem.Assistant } }
        assertEquals("step one\n", f.graph.workspace.resolve("plan.md").readText())
        assertTrue(done.run.reviewerBlocks.isEmpty())

        // One task: the result joined it as a note from Locus, not a new user message or turn.
        val history = f.history()
        assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.TOOL, Role.ASSISTANT, Role.TOOL, Role.USER, Role.ASSISTANT), history.map { it.role })
        val note = history[5]
        assertTrue(ModeNote.isAgentNote(note))
        assertFalse(note.startsTurn())
        assertNotNull(ModeNote.ranAnyway(note))
        assertEquals("", note.userText)
        assertEquals(1, done.items.filterIsInstance<ChatItem.User>().size)
        assertTrue(done.items.filterIsInstance<ChatItem.Note>().any { it.text.startsWith("You ran the blocked call anyway") })

        // The third request (same run) carries it after the step's result, and starts with exactly the second's messages.
        val (_, before, after) = f.graph.bodies().map { Json.parseToJsonElement(it).jsonObject.getValue("messages").jsonArray }
        assertEquals(before, JsonArray(after.take(before.size)))
        assertEquals(before.size + 3, after.size) // the read call, its result, the note
        assertTrue("overrode the safety reviewer" in after.last().toString())

        // The change belongs to this turn (its changes card), and the audit log has the override.
        val changes = f.graph.changes.observe(f.chat).first { it.isNotEmpty() }
        assertEquals(listOf("plan.md"), changes.map { it.path })
        assertEquals(history[1].id, changes.single().turnId)
        val audit = f.graph.conversations.observeAudit().first().filter { it.callId == "c1" }
        assertTrue(audit.any { it.decision == ApprovalDecision.USER_APPROVED && it.toolName == "write_file" })
    }

    @Test
    fun `when the chat is idle, the call runs now and starts a turn`() = runTest {
        val f = fixture()
        f.graph.replies(Reply(writePlan()), Reply(ScriptedProvider.text("I couldn't write the plan.")), Reply(ScriptedProvider.text("Thanks, the plan is written.")))
        f.vm.send("write a plan")
        f.vm.state { !it.run.running && it.run.reviewerBlocks.isNotEmpty() && it.items.any { i -> i is ChatItem.Assistant } }

        f.vm.runAnyway("c1")
        f.vm.state { !it.run.running && it.items.filterIsInstance<ChatItem.Assistant>().size == 2 }
        assertEquals("step one\n", f.graph.workspace.resolve("plan.md").readText())
        val history = f.history()
        assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.TOOL, Role.ASSISTANT, Role.USER, Role.ASSISTANT), history.map { it.role })
        val ran = history[4]
        assertTrue(ran.startsTurn())
        assertTrue(ran.userText.startsWith("Run anyway: "))
        assertTrue("overrode the safety reviewer" in f.graph.bodies().last())
    }

    @Test
    fun `stopping before the step boundary offers the call again instead of running it`() = runTest {
        val f = fixture()
        f.graph.workspace.resolve("notes.md").writeText("buy milk")
        val second = Reply(readNotes(), hold = CompletableDeferred())
        f.graph.replies(Reply(writePlan()), second)
        f.vm.send("tidy my notes")
        second.asked.await()
        f.vm.state { it.run.reviewerBlocks.isNotEmpty() }
        f.vm.runAnyway("c1")
        f.vm.state { it.run.reviewerBlocks.singleOrNull()?.queued == true }

        f.vm.stop()
        val stopped = f.vm.state { !it.run.running }
        assertEquals(listOf(false), stopped.run.reviewerBlocks.map { it.queued })
        assertFalse(f.graph.workspace.resolve("plan.md").exists())
    }
}
