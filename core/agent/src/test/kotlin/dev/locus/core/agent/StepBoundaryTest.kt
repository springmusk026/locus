package dev.locus.core.agent

import dev.locus.core.llm.AssistantTurn
import dev.locus.core.llm.ChatEvent
import dev.locus.core.llm.ChatModel
import dev.locus.core.llm.ChatRequest
import dev.locus.core.llm.StopReason
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.AuditEntry
import dev.locus.core.model.Conversation
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.ReviewerMode
import dev.locus.core.model.Role
import dev.locus.core.model.ToolPolicy
import dev.locus.core.model.ToolRisk
import dev.locus.core.model.ToolSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Steer and queue: what the user adds while a run works reaches the model at the next step boundary,
 * appended at the end of the transcript. The cached prefix (system prompt, tools, earlier messages)
 * never changes.
 */
class StepBoundaryTest {
    private val conversation = Conversation("c", "t", "p", "system", 0, 0)
    private val userMessage = Message("u", "c", Role.USER, listOf(Part.Text("tidy the notes folder")), 0)

    private class ScriptedModel(vararg turns: AssistantTurn) : ChatModel {
        val queue = ArrayDeque(turns.toList())
        val requests = mutableListOf<ChatRequest>()
        override val format = ApiFormat.OPENAI_CHAT
        override fun stream(request: ChatRequest): Flow<ChatEvent> = flow {
            // A snapshot: the loop's transcript keeps growing after the request is sent.
            requests += request.copy(messages = request.messages.toList())
            emit(ChatEvent.Completed(queue.removeFirst()))
        }
    }

    private class FakeTool(override val risk: ToolRisk = ToolRisk.WRITE) : Tool {
        var runs = 0
        override val spec = ToolSpec("touch", "test", ObjectSchema().string("path", "p").build())
        override fun describe(args: JsonObject) = "touch ${args.str("path")}"
        override suspend fun execute(args: JsonObject): ToolResult = ToolResult("ok").also { runs++ }
    }

    private class MemoryStore : TranscriptStore {
        val messages = mutableListOf<Message>()
        override suspend fun append(message: Message) { messages += message }
        override suspend fun record(entry: AuditEntry) = Unit
    }

    /** What the user queued, handed over (and emptied) at each boundary, as the app's queue does. */
    private class Queue {
        val waiting = mutableListOf<String>()
        var asked = 0
        private var n = 0
        fun take(): List<Message> {
            asked++
            return waiting.map { Message("q${n++}", "c", Role.USER, listOf(Part.Text(it)), 0) }.also { waiting.clear() }
        }
    }

    private fun call(id: String = "call1") =
        AssistantTurn("", listOf(Part.ToolCall(id, "touch", """{"path":"a.txt"}""")), StopReason.TOOL_USE, ApiFormat.OPENAI_CHAT, null)

    private val done = AssistantTurn("All done.", emptyList(), StopReason.END_TURN, ApiFormat.OPENAI_CHAT, null)

    private var nextId = 0
    private fun loop(
        model: ChatModel,
        store: TranscriptStore,
        queue: Queue,
        maxSteps: Int = 40,
        tool: Tool = FakeTool(),
        gate: ApprovalGate = ApprovalGate { true },
        policy: ToolPolicy = ToolPolicy.ALLOW,
        reviewer: ReviewerSetup? = null,
    ) = AgentLoop(
        model, ToolRegistry(listOf(tool)), { policy }, gate, store,
        AgentConfig(maxSteps = maxSteps, atStepBoundary = queue::take, reviewer = reviewer),
        ids = { "id${nextId++}" },
    )

    @Test
    fun `a queued message is appended after the tool results and the next request sees it`() = runTest {
        val queue = Queue()
        val tool = object : Tool by FakeTool() {
            override suspend fun execute(args: JsonObject): ToolResult {
                // The user types while the tool runs.
                queue.waiting += "also keep the drafts"
                return ToolResult("ok")
            }
        }
        val model = ScriptedModel(call(), done)
        val store = MemoryStore()
        loop(model, store, queue, tool = tool).run(conversation, listOf(userMessage)).toList()

        assertEquals(listOf(Role.ASSISTANT, Role.TOOL, Role.USER, Role.ASSISTANT), store.messages.map { it.role })
        assertEquals("also keep the drafts", store.messages[2].userText)
        val second = model.requests[1].messages
        assertEquals("also keep the drafts", second.last().userText)
        assertEquals(Role.TOOL, second[second.size - 2].role)
    }

    @Test
    fun `queued turns are appended, never inserted, and the system prompt and tools never change`() = runTest {
        val queue = Queue()
        val model = ScriptedModel(call("c1"), call("c2"), call("c3"), done)
        val store = MemoryStore()
        val tool = object : Tool by FakeTool() {
            var n = 0
            override suspend fun execute(args: JsonObject): ToolResult {
                if (n++ != 1) queue.waiting += "note $n"
                return ToolResult("ok")
            }
        }
        loop(model, store, queue, tool = tool).run(conversation, listOf(userMessage)).toList()

        assertEquals(4, model.requests.size)
        // Every request extends the one before it: a pure prefix, so the provider's cache stays valid.
        model.requests.zipWithNext().forEach { (before, after) ->
            assertEquals(before.messages, after.messages.take(before.messages.size))
            assertTrue(after.messages.size > before.messages.size)
        }
        assertEquals(setOf("system"), model.requests.map { it.system }.toSet())
        assertEquals(1, model.requests.map { it.tools }.toSet().size)
        assertEquals(1, model.requests.map { it.cacheKey }.toSet().size)
        // The stored transcript only grew, in the order things happened.
        assertEquals(
            listOf(Role.ASSISTANT, Role.TOOL, Role.USER, Role.ASSISTANT, Role.TOOL, Role.ASSISTANT, Role.TOOL, Role.USER, Role.ASSISTANT),
            store.messages.map { it.role },
        )
        assertEquals(listOf("note 1", "note 3"), store.messages.filter { it.role == Role.USER }.map { it.userText })
    }

    @Test
    fun `several queued messages keep their order`() = runTest {
        val queue = Queue().apply { waiting += listOf("first", "second", "third") }
        val model = ScriptedModel(call(), done)
        val store = MemoryStore()
        loop(model, store, queue).run(conversation, listOf(userMessage)).toList()

        assertEquals(listOf("first", "second", "third"), store.messages.filter { it.role == Role.USER }.map { it.userText })
        assertEquals(listOf("first", "second", "third"), model.requests[1].messages.takeLast(3).map { it.userText })
    }

    @Test
    fun `a run that ends before a boundary leaves the queue for the next turn`() = runTest {
        val queue = Queue().apply { waiting += "and then email it" }
        val store = MemoryStore()
        loop(ScriptedModel(done), store, queue).run(conversation, listOf(userMessage)).toList()

        assertEquals(0, queue.asked)
        assertEquals(listOf("and then email it"), queue.waiting)
        assertEquals(listOf(Role.ASSISTANT), store.messages.map { it.role })
    }

    @Test
    fun `nothing is taken after the last allowed step, so it isn't stranded unanswered`() = runTest {
        val queue = Queue().apply { waiting += "keep going" }
        val store = MemoryStore()
        val events = loop(ScriptedModel(call()), store, queue, maxSteps = 1).run(conversation, listOf(userMessage)).toList()

        assertEquals(Outcome.StepLimitReached(1), (events.last() as AgentEvent.Finished).outcome)
        assertEquals(0, queue.asked)
        assertEquals(listOf("keep going"), queue.waiting)
    }

    @Test
    fun `a pending approval doesn't block queueing - the message lands after the approved step`() = runTest {
        val queue = Queue()
        val gate = ApprovalGate {
            // The user queues while the approval card is showing, then approves.
            queue.waiting += "use the backup folder"
            true
        }
        val tool = FakeTool()
        val store = MemoryStore()
        loop(ScriptedModel(call(), done), store, queue, tool = tool, gate = gate, policy = ToolPolicy.ASK).run(conversation, listOf(userMessage)).toList()

        assertEquals(1, tool.runs)
        assertEquals(listOf(Role.ASSISTANT, Role.TOOL, Role.USER, Role.ASSISTANT), store.messages.map { it.role })
    }

    @Test
    fun `the safety reviewer counts a queued message as something the user asked for`() = runTest {
        val queue = Queue()
        val seen = mutableListOf<ReviewContext>()
        val reviewer = ReviewerSetup({ ctx -> seen += ctx; ReviewVerdict(Verdict.ALLOW, "fine") }, ReviewerMode.REVIEW_ALL_BUT_READS)
        val tool = object : Tool by FakeTool() {
            override suspend fun execute(args: JsonObject): ToolResult {
                queue.waiting += "you may delete a.txt too"
                return ToolResult("ok")
            }
        }
        loop(ScriptedModel(call("c1"), call("c2"), done), MemoryStore(), queue, tool = tool, reviewer = reviewer)
            .run(conversation, listOf(userMessage)).toList()

        assertEquals(listOf("tidy the notes folder"), seen[0].userMessages)
        assertEquals(listOf("tidy the notes folder", "you may delete a.txt too"), seen[1].userMessages)
    }
}
