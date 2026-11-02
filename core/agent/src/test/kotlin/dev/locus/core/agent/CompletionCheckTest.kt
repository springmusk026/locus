package dev.locus.core.agent

import dev.locus.core.agent.tools.agentControlTools
import dev.locus.core.llm.AssistantTurn
import dev.locus.core.llm.ChatEvent
import dev.locus.core.llm.ChatModel
import dev.locus.core.llm.ChatRequest
import dev.locus.core.llm.StopReason
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.AuditEntry
import dev.locus.core.model.Conversation
import dev.locus.core.model.Message
import dev.locus.core.model.ModeNote
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import dev.locus.core.model.ToolPolicy
import dev.locus.core.model.ToolRisk
import dev.locus.core.model.ToolSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The evidence-based completion check ([CompletionCheck]): a reply with no tool calls while
 * something concrete is open gets one hidden note, appended at the end, and one more step.
 */
class CompletionCheckTest {
    private val conversation = Conversation("c", "t", "p", "system", 0, 0)
    private val ask = Message("u", "c", Role.USER, listOf(Part.Text("fix the parser")), 0)

    private class ScriptedModel(vararg turns: AssistantTurn) : ChatModel {
        val queue = ArrayDeque(turns.toList())
        val requests = mutableListOf<ChatRequest>()
        var beforeReply: (() -> Unit)? = null
        override val format = ApiFormat.OPENAI_CHAT
        override fun stream(request: ChatRequest): Flow<ChatEvent> = flow {
            requests += request.copy(messages = request.messages.toList())
            if (queue.size == 1) beforeReply?.invoke()
            emit(ChatEvent.Completed(queue.removeFirst()))
        }
    }

    /** `run_command` with scripted status lines, in order. */
    private class FakeShell(vararg outputs: String) : Tool {
        val outputs = ArrayDeque(outputs.toList())
        override val risk = ToolRisk.WRITE
        override val spec = ToolSpec("run_command", "test", ObjectSchema().string("command", "c").build())
        override fun describe(args: JsonObject) = "run ${args.str("command")}"
        override suspend fun execute(args: JsonObject): ToolResult {
            val out = outputs.removeFirst()
            return ToolResult(out, isError = !out.startsWith("Exit code 0"))
        }
    }

    private class FakeEdit : Tool {
        override val risk = ToolRisk.WRITE
        override val spec = ToolSpec("edit_file", "test", ObjectSchema().string("path", "p").build())
        override fun describe(args: JsonObject) = "edit ${args.str("path")}"
        override suspend fun execute(args: JsonObject) = ToolResult("Edited.")
    }

    private class MemoryStore : TranscriptStore {
        val messages = mutableListOf<Message>()
        override suspend fun append(message: Message) { messages += message }
        override suspend fun record(entry: AuditEntry) = Unit
    }

    private var n = 0
    private fun calls(vararg calls: Pair<String, String>) =
        AssistantTurn("", calls.map { (name, args) -> Part.ToolCall("call${n++}", name, args) }, StopReason.TOOL_USE, ApiFormat.OPENAI_CHAT, null)

    private fun todos(vararg items: Pair<String, String>) = "todo_write" to
        """{"todos":[${items.joinToString(",") { (c, s) -> """{"content":"$c","status":"$s"}""" }}]}"""

    private fun run(cmd: String) = "run_command" to """{"command":"$cmd"}"""
    private fun edit(path: String) = "edit_file" to """{"path":"$path"}"""
    private fun done(text: String = "All done.") = AssistantTurn(text, emptyList(), StopReason.END_TURN, ApiFormat.OPENAI_CHAT, null)

    private var ids = 0
    private fun loop(
        model: ChatModel,
        store: TranscriptStore,
        shell: Tool = FakeShell(),
        config: AgentConfig = AgentConfig(completionCheck = true),
    ) = AgentLoop(
        model, ToolRegistry(agentControlTools() + shell + FakeEdit()), { ToolPolicy.ALLOW }, ApprovalGate { true }, store, config,
        ids = { "id${ids++}" },
    )

    private fun notes(store: MemoryStore) = store.messages.filter { CompletionCheck.openItemsIn(it) != null }

    @Test
    fun `open todos get one hidden note and one more step`() = runTest {
        val model = ScriptedModel(calls(todos("Read the parser" to "completed", "Fix the bug" to "in_progress", "Add a test" to "pending")), done(), done("Fixed; the test isn't written yet."))
        val store = MemoryStore()
        val events = loop(model, store).run(conversation, listOf(ask)).toList()

        val note = notes(store).single()
        assertEquals(2, CompletionCheck.openItemsIn(note))
        assertTrue(ModeNote.isAgentNote(note), "hidden from the chat like Locus's other notes")
        assertTrue("Fix the bug" in note.text && "Add a test" in note.text)
        assertTrue("Read the parser" !in note.text)
        assertEquals(3, model.requests.size)
        assertEquals(note.id, model.requests.last().messages.last().id)
        assertEquals(Outcome.Completed, (events.last() as AgentEvent.Finished).outcome)
    }

    @Test
    fun `a failed last command is open`() = runTest {
        val model = ScriptedModel(calls(run("python parse.py")), done(), done("It still fails with a KeyError."))
        val store = MemoryStore()
        loop(model, store, FakeShell("Exit code 1 (0.2 s).\nKeyError: 'x'")).run(conversation, listOf(ask)).toList()

        val note = notes(store).single()
        assertEquals(1, CompletionCheck.openItemsIn(note))
        assertTrue("exited with code 1" in note.text && "python parse.py" in note.text)
    }

    @Test
    fun `a command that failed and then passed is not open`() = runTest {
        val model = ScriptedModel(calls(run("python parse.py")), calls(run("python parse.py")), done())
        val store = MemoryStore()
        loop(model, store, FakeShell("Exit code 1 (0.2 s).\nboom", "Exit code 0 (0.2 s).\nok")).run(conversation, listOf(ask)).toList()
        assertTrue(notes(store).isEmpty())
    }

    @Test
    fun `edits after the last test are open, edits before it aren't`() = runTest {
        val store = MemoryStore()
        val model = ScriptedModel(calls(edit("a.py")), calls(run("pytest")), calls(edit("b.py")), done(), done("b.py isn't tested yet."))
        loop(model, store, FakeShell("Exit code 0 (1.0 s).\n3 passed")).run(conversation, listOf(ask)).toList()

        val note = notes(store).single()
        assertEquals(1, CompletionCheck.openItemsIn(note))
        assertTrue("b.py" in note.text)
        assertTrue("a.py" !in note.text)
    }

    @Test
    fun `edits in a chat where no test or build ran are not open`() = runTest {
        val store = MemoryStore()
        val model = ScriptedModel(calls(edit("notes.md")), calls(run("ls")), done())
        loop(model, store, FakeShell("Exit code 0 (0.1 s).\nnotes.md")).run(conversation, listOf(ask)).toList()
        assertTrue(notes(store).isEmpty())
    }

    @Test
    fun `a test in an earlier task still counts for this task's edits`() = runTest {
        val earlier = listOf(
            Message("u0", "c", Role.USER, listOf(Part.Text("run the tests")), 0),
            Message("a0", "c", Role.ASSISTANT, listOf(Part.ToolCall("t0", "run_command", """{"command":"./gradlew test"}""")), 0),
            Message("r0", "c", Role.TOOL, listOf(Part.ToolResult("t0", "run_command", "Exit code 0 (9.0 s).\nBUILD SUCCESSFUL")), 0),
            Message("a1", "c", Role.ASSISTANT, listOf(Part.Text("They pass.")), 0),
        )
        val store = MemoryStore()
        val model = ScriptedModel(calls(edit("src/Main.kt")), done(), done("Not rebuilt."))
        loop(model, store).run(conversation, earlier + ask).toList()
        assertTrue("src/Main.kt" in notes(store).single().text)
    }

    @Test
    fun `nothing open means no note`() = runTest {
        val store = MemoryStore()
        val model = ScriptedModel(calls(todos("Fix the bug" to "completed")), done())
        loop(model, store).run(conversation, listOf(ask)).toList()
        assertTrue(notes(store).isEmpty())
        assertEquals(2, model.requests.size)
    }

    @Test
    fun `at most once per task, even when it ends with items still open`() = runTest {
        val model = ScriptedModel(calls(todos("Fix the bug" to "pending")), done(), done("Still not fixed."))
        val store = MemoryStore()
        val events = loop(model, store).run(conversation, listOf(ask)).toList()
        assertEquals(1, notes(store).size)
        assertEquals(3, model.requests.size)
        assertTrue(events.last() is AgentEvent.Finished)

        // A later run of the same task (its history already holds the note) doesn't ask again.
        val again = ScriptedModel(done("Still open."))
        val more = MemoryStore()
        loop(again, more).run(conversation, listOf(ask) + store.messages).toList()
        assertTrue(notes(more).isEmpty())
        assertTrue(CompletionCheck.alreadyAsked(listOf(ask) + store.messages))

        // A new task from the user starts over.
        val next = Message("u2", "c", Role.USER, listOf(Part.Text("now the lexer")), 0)
        assertFalse(CompletionCheck.alreadyAsked(listOf(ask) + store.messages + next))
    }

    @Test
    fun `a message told mid-task doesn't start a new task`() {
        val note = Message("n", "c", Role.USER, listOf(Part.Text(ModeNote.wrap(CompletionCheck.note(CompletionCheck.Open(failedCommand = CompletionCheck.FailedCommand("make", 2)))))), 0)
        val steer = Message("s", "c", Role.USER, listOf(Part.Text("also the docs"), Part.Text(ModeNote.wrap(ModeNote.MID_TASK))), 0)
        assertTrue(CompletionCheck.alreadyAsked(listOf(ask, note, steer)))
    }

    @Test
    fun `skipped in read-only runs`() = runTest {
        val store = MemoryStore()
        val model = ScriptedModel(calls(todos("Fix the bug" to "pending")), done())
        loop(model, store, config = AgentConfig(completionCheck = true, readOnly = true)).run(conversation, listOf(ask)).toList()
        assertTrue(notes(store).isEmpty())
    }

    @Test
    fun `skipped when the chat is in Read-only mode`() = runTest {
        val store = MemoryStore()
        val model = ScriptedModel(calls(todos("Fix the bug" to "pending")), done())
        loop(model, store, config = AgentConfig(completionCheck = true, chatReadOnly = { true })).run(conversation, listOf(ask)).toList()
        assertTrue(notes(store).isEmpty())
    }

    @Test
    fun `off unless the run turns it on, as scheduled runs and helpers don't`() = runTest {
        val store = MemoryStore()
        val model = ScriptedModel(calls(todos("Fix the bug" to "pending")), done())
        loop(model, store, config = AgentConfig()).run(conversation, listOf(ask)).toList()
        assertTrue(notes(store).isEmpty())
        val helper = MemoryStore()
        loop(ScriptedModel(calls(todos("Fix the bug" to "pending")), done()), helper, config = AgentConfig(completionCheck = true, depth = 1)).run(conversation, listOf(ask)).toList()
        assertTrue(notes(helper).isEmpty())
    }

    @Test
    fun `skipped in plan mode`() = runTest {
        val planAsk = Message("u", "c", Role.USER, listOf(Part.Text("fix the parser"), Part.Text(ModeNote.wrap(ModeNote.PLAN_MODE))), 0)
        val store = MemoryStore()
        loop(ScriptedModel(calls(todos("Fix the bug" to "pending")), done()), store).run(conversation, listOf(planAsk)).toList()
        assertTrue(notes(store).isEmpty())
    }

    @Test
    fun `skipped on the last allowed step`() = runTest {
        val store = MemoryStore()
        loop(ScriptedModel(calls(todos("Fix the bug" to "pending")), done()), store, config = AgentConfig(completionCheck = true, maxSteps = 2)).run(conversation, listOf(ask)).toList()
        assertTrue(notes(store).isEmpty())
    }

    @Test
    fun `skipped when the user stopped the task`() = runTest {
        val store = MemoryStore()
        val model = ScriptedModel(calls(todos("Fix the bug" to "pending")), done())
        val job = launch {
            loop(model, store).run(conversation, listOf(ask)).toList()
        }
        // Stop arrives while the final reply streams.
        model.beforeReply = { job.cancel(CancellationException("Stop")) }
        job.join()
        assertTrue(notes(store).isEmpty())
        assertEquals(2, model.requests.size)
    }

    @Test
    fun `the note is appended, so every request's prefix is unchanged`() = runTest {
        val model = ScriptedModel(calls(todos("Fix the bug" to "pending")), done(), done("Not fixed yet."))
        val store = MemoryStore()
        loop(model, store).run(conversation, listOf(ask)).toList()

        val reqs = model.requests
        assertEquals(3, reqs.size)
        for (i in 1 until reqs.size) {
            val before = reqs[i - 1]
            val after = reqs[i]
            assertEquals(before.system, after.system)
            assertEquals(before.tools, after.tools)
            assertEquals(before.messages, after.messages.take(before.messages.size), "request $i only appends")
        }
        // The note follows the reply that ended the turn, as a user-role note.
        val last = reqs.last().messages
        assertEquals(Role.ASSISTANT, last[last.size - 2].role)
        assertEquals(Role.USER, last.last().role)
        assertEquals("", last.last().userText)
    }

    @Test
    fun `a refusal is never nudged`() = runTest {
        val store = MemoryStore()
        val refusal = AssistantTurn("", emptyList(), StopReason.REFUSAL, ApiFormat.OPENAI_CHAT, null)
        loop(ScriptedModel(calls(todos("Fix the bug" to "pending")), refusal), store).run(conversation, listOf(ask)).toList()
        assertTrue(notes(store).isEmpty())
    }

    @Test
    fun `the note names how many items, for the receipt`() {
        val open = CompletionCheck.Open(
            todos = listOf(dev.locus.core.model.TodoItem("a", dev.locus.core.model.TodoStatus.PENDING)),
            failedCommand = CompletionCheck.FailedCommand("npm test", 1),
            unverifiedEdits = listOf("x.ts"),
        )
        val m = Message("n", "c", Role.USER, listOf(Part.Text(ModeNote.wrap(CompletionCheck.note(open)))), 0)
        assertEquals(3, CompletionCheck.openItemsIn(m))
        assertNull(CompletionCheck.openItemsIn(ask))
    }
}
