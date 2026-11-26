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
 * The effort record an adapter puts on a turn (docs/design/midchat-config.md) is kept on the stored
 * assistant message, and the next request of the run carries it, so the adapter can rebuild the request
 * that produced the turn.
 */
class TurnEffortRecordTest {
    private val conversation = Conversation("c", "t", "p", "system", 0, 0)
    private val record = Part.TurnEffort("low", "high")

    private class Scripted(vararg turns: AssistantTurn) : ChatModel {
        val queue = ArrayDeque(turns.toList())
        val requests = mutableListOf<ChatRequest>()
        override val format = ApiFormat.ANTHROPIC_MESSAGES
        override fun stream(request: ChatRequest): Flow<ChatEvent> = flow {
            requests += request
            emit(ChatEvent.Completed(queue.removeFirst()))
        }
    }

    private class Store : TranscriptStore {
        val messages = mutableListOf<Message>()
        override suspend fun append(message: Message) { messages += message }
        override suspend fun record(entry: AuditEntry) = Unit
    }

    private class Read : Tool {
        override val risk = ToolRisk.READ
        override val spec = ToolSpec("look", "test", ObjectSchema().build())
        override fun describe(args: JsonObject) = "look"
        override suspend fun execute(args: JsonObject) = ToolResult("seen")
    }

    private var next = 0
    private fun loop(model: ChatModel, store: TranscriptStore) =
        AgentLoop(model, ToolRegistry(listOf(Read())), { ToolPolicy.ALLOW }, ApprovalGate { true }, store, AgentConfig(), ids = { "id${next++}" })

    @Test
    fun `the record is stored on the assistant message and sent back on the next step`() = runTest {
        val call = AssistantTurn("", listOf(Part.ToolCall("t1", "look", "{}")), StopReason.TOOL_USE, ApiFormat.ANTHROPIC_MESSAGES,
            """[{"type":"tool_use","id":"t1","name":"look","input":{}}]""", turnEffort = record)
        val done = AssistantTurn("Done.", emptyList(), StopReason.END_TURN, ApiFormat.ANTHROPIC_MESSAGES, """[{"type":"text","text":"Done."}]""", turnEffort = record)
        val model = Scripted(call, done)
        val store = Store()
        loop(model, store).run(conversation, listOf(Message("u", "c", Role.USER, listOf(Part.Text("go")), 0))).toList()

        val replies = store.messages.filter { it.role == Role.ASSISTANT }
        assertEquals(2, replies.size)
        assertTrue(replies.all { m -> m.parts.filterIsInstance<Part.TurnEffort>() == listOf(record) })
        // Hidden from what the user reads: the text and the calls are what they were.
        assertEquals("Done.", replies.last().text)
        assertEquals(listOf("t1"), replies.first().toolCalls.map { it.id })
        // The second request carries the first turn with its record.
        assertEquals(listOf(record), model.requests[1].messages.first { it.role == Role.ASSISTANT }.parts.filterIsInstance<Part.TurnEffort>())
    }

    @Test
    fun `a turn without a record is stored as before`() = runTest {
        val store = Store()
        loop(Scripted(AssistantTurn("Hi.", emptyList(), StopReason.END_TURN, ApiFormat.ANTHROPIC_MESSAGES, null)), store)
            .run(conversation, listOf(Message("u", "c", Role.USER, listOf(Part.Text("go")), 0))).toList()
        assertEquals(listOf<Part>(Part.Text("Hi.")), store.messages.single { it.role == Role.ASSISTANT }.parts)
    }
}
