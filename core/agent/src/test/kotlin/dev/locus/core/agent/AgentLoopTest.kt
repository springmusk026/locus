package dev.locus.core.agent

import dev.locus.core.llm.AssistantTurn
import dev.locus.core.llm.ChatEvent
import dev.locus.core.llm.ChatModel
import dev.locus.core.llm.ChatRequest
import dev.locus.core.llm.INVALID_INPUT_KEY
import dev.locus.core.llm.LlmException
import dev.locus.core.llm.StopReason
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.ApprovalDecision
import dev.locus.core.model.AuditEntry
import dev.locus.core.model.Conversation
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import dev.locus.core.model.ToolPolicy
import dev.locus.core.model.ToolRisk
import dev.locus.core.model.ToolSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AgentLoopTest {
    private val conversation = Conversation("c", "t", "p", "system", 0, 0)
    private val userMessage = Message("u", "c", Role.USER, listOf(Part.Text("go")), 0)

    /** Replies with the queued turns in order; records each request. */
    private class ScriptedModel(vararg turns: AssistantTurn) : ChatModel {
        val queue = ArrayDeque(turns.toList())
        val requests = mutableListOf<ChatRequest>()
        var failuresBeforeSuccess = 0
        override val format = ApiFormat.OPENAI_CHAT
        override fun stream(request: ChatRequest): Flow<ChatEvent> = flow {
            requests += request
            if (failuresBeforeSuccess > 0) {
                failuresBeforeSuccess--
                throw LlmException("overloaded", 529, retryable = true)
            }
            val turn = queue.removeFirst()
            if (turn.text.isNotEmpty()) emit(ChatEvent.TextDelta(turn.text))
            emit(ChatEvent.Completed(turn))
        }
    }

    private class FakeTool(override val risk: ToolRisk, name: String = "touch") : Tool {
        var runs = 0
        override val spec = ToolSpec(name, "test", ObjectSchema().string("path", "p").build())
        override fun describe(args: JsonObject) = "touch ${args.str("path")}"
        override suspend fun execute(args: JsonObject): ToolResult {
            runs++
            return ToolResult("ok")
        }
    }

    private class MemoryStore : TranscriptStore {
        val messages = mutableListOf<Message>()
        val audit = mutableListOf<AuditEntry>()
        override suspend fun append(message: Message) { messages += message }
        override suspend fun record(entry: AuditEntry) { audit += entry }
    }

    private fun call(args: String = """{"path":"a.txt"}""", name: String = "touch") =
        AssistantTurn("", listOf(Part.ToolCall("call1", name, args)), StopReason.TOOL_USE, ApiFormat.OPENAI_CHAT, null)

    private val done = AssistantTurn("All done.", emptyList(), StopReason.END_TURN, ApiFormat.OPENAI_CHAT, null)

    private var nextId = 0
    private fun loop(model: ChatModel, tool: Tool, policy: ToolPolicy, store: TranscriptStore, gate: ApprovalGate = ApprovalGate { true }, readOnly: Boolean = false) =
        AgentLoop(model, ToolRegistry(listOf(tool)), { policy }, gate, store, AgentConfig(readOnly = readOnly), ids = { "id${nextId++}" })

    @Test
    fun `allowed tool runs and the loop continues to a final answer`() = runTest {
        val tool = FakeTool(ToolRisk.READ)
        val store = MemoryStore()
        val events = loop(ScriptedModel(call(), done), tool, ToolPolicy.ALLOW, store).run(conversation, listOf(userMessage)).toList()

        assertEquals(1, tool.runs)
        assertEquals(listOf(Role.ASSISTANT, Role.TOOL, Role.ASSISTANT), store.messages.map { it.role })
        assertEquals(ApprovalDecision.AUTO_ALLOWED, store.audit.single().decision)
        assertEquals(Outcome.Completed, (events.last() as AgentEvent.Finished).outcome)
    }

    @Test
    fun `ask policy waits for the user and a denial is reported to the model`() = runTest {
        val tool = FakeTool(ToolRisk.WRITE)
        val store = MemoryStore()
        val model = ScriptedModel(call(), done)
        loop(model, tool, ToolPolicy.ASK, store, gate = { false }).run(conversation, listOf(userMessage)).toList()

        assertEquals(0, tool.runs)
        assertEquals(ApprovalDecision.USER_DENIED, store.audit.single().decision)
        val result = store.messages[1].toolResults.single()
        assertTrue(result.isError && "declined" in result.content)
        // The second request carries the denial so the model can react to it.
        assertEquals(Role.TOOL, model.requests[1].messages.last().role)
    }

    @Test
    fun `read-only runs never execute write tools even when allowed`() = runTest {
        val tool = FakeTool(ToolRisk.WRITE)
        val store = MemoryStore()
        loop(ScriptedModel(call(), done), tool, ToolPolicy.ALLOW, store, readOnly = true).run(conversation, listOf(userMessage)).toList()
        assertEquals(0, tool.runs)
        assertEquals(ApprovalDecision.POLICY_DENIED, store.audit.single().decision)
    }

    @Test
    fun `invalid or schema-violating input is rejected without running`() = runTest {
        val tool = FakeTool(ToolRisk.READ)
        val store = MemoryStore()
        val badJson = call("""{"$INVALID_INPUT_KEY":"{\"path\": \"a"}""")
        val wrongType = call("""{"path": 5}""")
        loop(ScriptedModel(badJson, wrongType, done), tool, ToolPolicy.ALLOW, store).run(conversation, listOf(userMessage)).toList()

        assertEquals(0, tool.runs)
        assertEquals(listOf(ApprovalDecision.INVALID_INPUT, ApprovalDecision.INVALID_INPUT), store.audit.map { it.decision })
        assertTrue("INVALID_JSON" in store.messages[1].toolResults.single().content)
        assertTrue("type string" in store.messages[3].toolResults.single().content)
    }

    @Test
    fun `tool calls cut off by max_tokens are answered but not run`() = runTest {
        val tool = FakeTool(ToolRisk.READ)
        val store = MemoryStore()
        val truncated = call().copy(stopReason = StopReason.MAX_TOKENS)
        loop(ScriptedModel(truncated, done), tool, ToolPolicy.ALLOW, store).run(conversation, listOf(userMessage)).toList()
        assertEquals(0, tool.runs)
        assertTrue(store.messages[1].toolResults.single().isError)
    }

    @Test
    fun `refusal ends the run without running tools`() = runTest {
        val tool = FakeTool(ToolRisk.READ)
        val store = MemoryStore()
        val refused = call().copy(stopReason = StopReason.REFUSAL, refusalDetail = "cyber")
        val events = loop(ScriptedModel(refused), tool, ToolPolicy.ALLOW, store).run(conversation, listOf(userMessage)).toList()
        assertEquals(0, tool.runs)
        assertEquals(Outcome.Refused("cyber"), (events.last() as AgentEvent.Finished).outcome)
        assertEquals(Role.TOOL, store.messages.last().role)
    }

    @Test
    fun `transient errors are retried`() = runTest {
        val model = ScriptedModel(done).apply { failuresBeforeSuccess = 2 }
        val events = loop(model, FakeTool(ToolRisk.READ), ToolPolicy.ALLOW, MemoryStore()).run(conversation, listOf(userMessage)).toList()
        assertEquals(2, events.count { it is AgentEvent.Retrying })
        assertEquals(Outcome.Completed, (events.last() as AgentEvent.Finished).outcome)
    }

    @Test
    fun `cancelling during an approval still answers every tool call`() = runTest {
        val store = MemoryStore()
        val never = CompletableDeferred<Boolean>()
        val job = launch { loop(ScriptedModel(call()), FakeTool(ToolRisk.WRITE), ToolPolicy.ASK, store, gate = { never.await() }).run(conversation, listOf(userMessage)).toList() }
        advanceUntilIdle()
        job.cancel()
        advanceUntilIdle()

        val calls = store.messages.flatMap { it.toolCalls }.map { it.id }
        val answered = store.messages.flatMap { it.toolResults }.map { it.callId }
        assertEquals(calls, answered)
    }

    @Test
    fun `read-only runs still allow tools that only observe`() = runTest {
        val tool = object : Tool by FakeTool(ToolRisk.NETWORK) {
            override suspend fun isObservation(args: JsonObject) = true
        }
        val store = MemoryStore()
        loop(ScriptedModel(call(), done), tool, ToolPolicy.ALLOW, store, readOnly = true).run(conversation, listOf(userMessage)).toList()
        assertEquals(ApprovalDecision.AUTO_ALLOWED, store.audit.single().decision)
    }

    @Test
    fun `a tool can force confirmation of a risky call even when allowed`() = runTest {
        val tool = object : Tool by FakeTool(ToolRisk.WRITE) {
            override suspend fun mustConfirm(args: JsonObject) = "Looks like a payment."
        }
        val asked = mutableListOf<ApprovalRequest>()
        val store = MemoryStore()
        loop(ScriptedModel(call(), done), tool, ToolPolicy.ALLOW, store, gate = { asked += it; false }).run(conversation, listOf(userMessage)).toList()
        assertEquals(ApprovalDecision.USER_DENIED, store.audit.single().decision)
        assertTrue("payment" in asked.single().summary)
    }

    @Test
    fun `step limit stops runaway loops`() = runTest {
        val model = ScriptedModel(*Array(5) { call() })
        val agent = AgentLoop(model, ToolRegistry(listOf(FakeTool(ToolRisk.READ))), { ToolPolicy.ALLOW }, { true }, MemoryStore(), AgentConfig(maxSteps = 3), ids = { "id${nextId++}" })
        val outcome = (agent.run(conversation, listOf(userMessage)).toList().last() as AgentEvent.Finished).outcome
        assertIs<Outcome.StepLimitReached>(outcome)
    }

    @Test
    fun `replies record the model that answered and how long it took, never sent back`() = runTest {
        var now = 0L
        // Each step: request sent at t, first token 300ms later, finished 1s after that.
        val model = object : ChatModel {
            val queue = ArrayDeque(listOf(call(), done))
            val requests = mutableListOf<ChatRequest>()
            override val format = ApiFormat.OPENAI_CHAT
            override fun stream(request: ChatRequest): Flow<ChatEvent> = flow {
                requests += request
                val turn = queue.removeFirst()
                now += 300
                emit(ChatEvent.ReasoningDelta("hmm"))
                now += 1_000
                if (turn.text.isNotEmpty()) emit(ChatEvent.TextDelta(turn.text))
                emit(ChatEvent.Completed(turn))
            }
        }
        val store = MemoryStore()
        val config = AgentConfig(providerId = "prov", model = "big-model")
        AgentLoop(model, ToolRegistry(listOf(FakeTool(ToolRisk.READ))), { ToolPolicy.ALLOW }, { true }, store, config, ids = { "id${nextId++}" }, clock = { now })
            .run(conversation, listOf(userMessage)).toList()

        val replies = store.messages.filter { it.role == Role.ASSISTANT }
        assertEquals(2, replies.size)
        replies.forEach { assertEquals(dev.locus.core.model.ReplyInfo("prov", "big-model", 300, 1_300), it.reply) }
        assertTrue(store.messages.filter { it.role != Role.ASSISTANT }.all { it.reply == null })
        // What the provider gets is unchanged: the same messages, with reasoning stripped as before.
        assertEquals(store.messages.take(2).map { it.forModel() }, model.requests[1].messages.drop(1))
    }

    @Test
    fun `without a configured model the conversation's own is recorded, and retries aren't timed`() = runTest {
        var now = 0L
        val model = object : ChatModel {
            var failures = 1
            override val format = ApiFormat.OPENAI_CHAT
            override fun stream(request: ChatRequest): Flow<ChatEvent> = flow {
                now += 5_000
                if (failures-- > 0) throw LlmException("overloaded", 529, retryable = true)
                now += 700
                emit(ChatEvent.Completed(done))
            }
        }
        val store = MemoryStore()
        val helperChat = conversation.copy(providerId = "p2", modelOverride = "small-model")
        AgentLoop(model, ToolRegistry(emptyList()), { ToolPolicy.ALLOW }, { true }, store, AgentConfig(), ids = { "id${nextId++}" }, clock = { now })
            .run(helperChat, listOf(userMessage)).toList()

        // No deltas: the first token is the whole turn. The failed attempt and the back-off don't count.
        assertEquals(dev.locus.core.model.ReplyInfo("p2", "small-model", 5_700, 5_700), store.messages.single().reply)
    }
}
