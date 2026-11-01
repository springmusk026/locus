package dev.locus.feature.chat

import dev.locus.core.data.repository.TrackedChange
import dev.locus.core.model.AppSettings
import dev.locus.core.model.ModeNote
import dev.locus.core.model.Role
import dev.locus.core.model.ToolPolicy
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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Change tracking end to end, over the real runner and a scripted provider: a turn's file changes show
 * as one card at the end of the turn, undo puts files back (and says when they changed since), and the
 * agent is told, with the transcript only ever appended to.
 */
class ChangesFlowTest {
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
        toolPolicies = mapOf("write_file" to ToolPolicy.ALLOW, "edit_file" to ToolPolicy.ALLOW),
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
        val g = TestGraph(backgroundScope, settings)
        val chat = g.conversations.create(settings.defaultProviderId).id
        val vm = host.add(ChatViewModel(chat, g.conversations, g.settingsRepository, g.directory, g.runner, g.browser, g.workspaceRepository, g.library, g.workspace, g.onDevice, g.sentRequests, g.sideQuestions, g.changes))
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        return Fixture(g, chat, vm)
    }

    private suspend fun ChatViewModel.state(until: (ChatUiState) -> Boolean) = uiState.first { !it.loading && until(it) }

    private fun call(id: String, tool: String, args: String) = Reply(ScriptedProvider.toolCall(id, tool, args))

    /** A turn that reads notes.md, edits it, creates todo.md, then replies. */
    private fun editingTurn() = arrayOf(
        call("c1", "read_file", """{"path":"notes.md"}"""),
        call("c2", "edit_file", """{"path":"notes.md","old_text":"buy milk","new_text":"buy oat milk\nbuy bread"}"""),
        call("c3", "write_file", """{"path":"todo.md","content":"- call mum\n"}"""),
        Reply(ScriptedProvider.text("Updated your notes and added a to-do list.")),
    )

    @Test
    fun `a turn's changes show as one card at its end, and Undo all puts everything back`() = runTest {
        val f = fixture()
        val ws = f.graph.workspace
        ws.resolve("notes.md").writeText("shopping:\nbuy milk\n")
        f.graph.replies(*editingTurn(), Reply(ScriptedProvider.text("Okay.")))
        f.vm.send("tidy my notes")
        val s = f.vm.state { !it.run.running && it.items.any { i -> i is ChatItem.Changes } }

        val card = s.items.filterIsInstance<ChatItem.Changes>().single()
        // The card closes the turn, after the reply.
        assertEquals(card, s.items.last())
        assertEquals("Changed 2 files", changesTitle(card.files))
        val notes = card.files.single { it.path == "notes.md" }
        assertEquals(TrackedChange.Kind.MODIFIED, notes.kind)
        assertEquals(2 to 1, notes.added to notes.removed)
        assertTrue("-buy milk\n+buy oat milk\n+buy bread\n" in notes.diff.orEmpty(), notes.diff)
        val todo = card.files.single { it.path == "todo.md" }
        assertEquals(TrackedChange.Kind.CREATED, todo.kind)
        assertEquals("+1 −0", changeSummary(todo))
        // The turn is keyed by its first message after the user's.
        val history = f.graph.conversations.history(f.chat)
        assertEquals(history[1].id, card.turnId)

        assertFalse(f.vm.checkUndoTurn(card.turnId).changedSince)
        f.vm.undo(turnId = card.turnId)
        val undone = f.vm.state { st -> st.items.filterIsInstance<ChatItem.Changes>().singleOrNull()?.files?.all { it.undone } == true }
        assertEquals("Undid the changes to 2 files", changesTitle(undone.items.filterIsInstance<ChatItem.Changes>().single().files))
        assertEquals("shopping:\nbuy milk\n", ws.resolve("notes.md").readText())
        assertFalse(ws.resolve("todo.md").exists())
        // Nothing is lost: what the agent wrote is in the trash.
        assertTrue(ws.trashEntries().any { it.originalPath == "todo.md" })

        // The agent is told with the user's next message; the transcript is only appended to.
        f.vm.send("thanks")
        f.vm.state { !it.run.running && it.items.count { i -> i is ChatItem.User } == 2 }
        val next = f.graph.conversations.history(f.chat).last { it.role == Role.USER }
        assertEquals("thanks", next.userText)
        assertTrue(ModeNote.isNote(next.parts.last().let { (it as dev.locus.core.model.Part.Text).text }))
        assertEquals("notes.md, todo.md", ModeNote.undone(next)?.split(", ")?.sorted()?.joinToString(", "))
        val bodies = f.graph.bodies()
        assertTrue("The user undid your changes to" in bodies.last())
        assertFalse(bodies.dropLast(1).any { "The user undid" in it })
    }

    @Test
    fun `undo says when the file changed since, and still keeps the newer version in the trash`() = runTest {
        val f = fixture()
        val ws = f.graph.workspace
        ws.resolve("notes.md").writeText("shopping:\nbuy milk\n")
        f.graph.replies(*editingTurn())
        f.vm.send("tidy my notes")
        val s = f.vm.state { !it.run.running && it.items.any { i -> i is ChatItem.Changes } }
        val notes = s.items.filterIsInstance<ChatItem.Changes>().single().files.single { it.path == "notes.md" }

        ws.resolve("notes.md").appendText("my own line\n")
        val check = f.vm.checkUndo(notes.id)
        assertTrue(check.changedSince)
        f.vm.undo(ids = listOf(notes.id))
        f.vm.state { st -> st.items.filterIsInstance<ChatItem.Changes>().single().files.single { it.path == "notes.md" }.undone }
        assertEquals("shopping:\nbuy milk\n", ws.resolve("notes.md").readText())
        val kept = ws.trashEntries().first { it.originalPath == "notes.md" && it.reason.name == "MOVED_OVER" }
        assertTrue("my own line" in ws.trashPayload(kept.id)!!.readText())
        // The other file still stands and can be undone on its own.
        val todo = f.vm.uiState.value.items.filterIsInstance<ChatItem.Changes>().single().files.single { it.path == "todo.md" }
        assertFalse(todo.undone)
        assertTrue(todo.canUndo)
    }

    @Test
    fun `an undo while the agent works joins at the next step boundary as a note, inside the same turn`() = runTest {
        val f = fixture()
        val ws = f.graph.workspace
        ws.resolve("notes.md").writeText("buy milk\n")
        val held = Reply(ScriptedProvider.toolCall("c3", "read_file", """{"path":"notes.md"}"""), hold = CompletableDeferred())
        f.graph.replies(
            call("c1", "read_file", """{"path":"notes.md"}"""),
            call("c2", "edit_file", """{"path":"notes.md","old_text":"buy milk","new_text":"buy bread"}"""),
            held,
            call("c4", "write_file", """{"path":"plan.md","content":"x\n"}"""),
            Reply(ScriptedProvider.text("Done.")),
        )
        f.vm.send("tidy my notes")
        held.asked.await()
        val card = f.vm.state { it.items.any { i -> i is ChatItem.Changes } }.items.filterIsInstance<ChatItem.Changes>().single()
        f.vm.undo(ids = card.files.map { it.id })
        f.vm.state { st -> st.items.filterIsInstance<ChatItem.Changes>().single().files.all { it.undone } }
        assertEquals("buy milk\n", ws.resolve("notes.md").readText())

        held.hold!!.complete(Unit)
        val s = f.vm.state { !it.run.running && it.items.any { i -> i is ChatItem.Assistant } }
        val history = f.graph.conversations.history(f.chat)
        val note = history.single { ModeNote.isAgentNote(it) }
        assertEquals("notes.md", ModeNote.undone(note))
        // The note is in the model's transcript after the step's results, never shown as something the user typed.
        assertEquals(Role.TOOL, history[history.indexOf(note) - 1].role)
        assertEquals(1, s.items.count { it is ChatItem.User })
        // The turn goes on after the note: its later change lands in the same card.
        val after = s.items.filterIsInstance<ChatItem.Changes>().single()
        assertEquals(card.turnId, after.turnId)
        assertTrue(after.files.any { it.path == "plan.md" && !it.undone })
        // Each request starts with the one before it: nothing earlier was changed.
        val bodies = f.graph.bodies().map { kotlinx.serialization.json.Json.parseToJsonElement(it).jsonObject["messages"]!!.jsonArray }
        bodies.zipWithNext().forEach { (a, b) -> assertEquals(a, kotlinx.serialization.json.JsonArray(b.take(a.size))) }
        assertTrue("The user undid your changes to notes.md" in bodies[3].toString())
    }
}

private val kotlinx.serialization.json.JsonElement.jsonObject get() = this as kotlinx.serialization.json.JsonObject
private val kotlinx.serialization.json.JsonElement.jsonArray get() = this as kotlinx.serialization.json.JsonArray
