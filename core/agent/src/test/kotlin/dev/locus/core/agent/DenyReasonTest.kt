package dev.locus.core.agent

import dev.locus.core.llm.AssistantTurn
import dev.locus.core.llm.ChatEvent
import dev.locus.core.llm.ChatModel
import dev.locus.core.llm.ChatRequest
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Deny with a reason" (docs/design/remembered-approvals.md): what the model reads for a denied call. */
class DenyReasonTest {
    private val conversation = Conversation("c", "t", "p", "system", 0, 0)
    private val userMessage = Message("u", "c", Role.USER, listOf(Part.Text("email Ana the notes")), 0)

    private class Model(vararg turns: AssistantTurn) : ChatModel {
        val queue = ArrayDeque(turns.toList())
        val requests = mutableListOf<ChatRequest>()
        override val format = ApiFormat.OPENAI_CHAT
        override fun stream(request: ChatRequest): Flow<ChatEvent> = flow {
            requests += request
            emit(ChatEvent.Completed(queue.removeFirst()))
        }
    }

    private class SendTool : Tool {
        var runs = 0
        override val risk = ToolRisk.NETWORK
        override val spec = ToolSpec("send_email", "test", ObjectSchema().string("to", "to").build())
        override fun describe(args: JsonObject) = "Email ${args.str("to")}"
        override suspend fun execute(args: JsonObject): ToolResult {
            runs++
            return ToolResult("sent")
        }
    }

    private class Store : TranscriptStore {
        val messages = mutableListOf<Message>()
        val audit = mutableListOf<AuditEntry>()
        override suspend fun append(message: Message) { messages += message }
        override suspend fun record(entry: AuditEntry) { audit += entry }
    }

    /** Answers every card with [reply]; [answer] alone (old gates) when [reply] is null. */
    private class Gate(private val reply: ApprovalReply?) : ApprovalGate {
        override suspend fun request(request: ApprovalRequest) = false
        override suspend fun reply(request: ApprovalRequest): ApprovalReply = reply ?: super.reply(request)
    }

    private val call = AssistantTurn("", listOf(Part.ToolCall("call1", "send_email", """{"to":"ana@example.com"}""")), StopReason.TOOL_USE, ApiFormat.OPENAI_CHAT, null)
    private val done = AssistantTurn("OK.", emptyList(), StopReason.END_TURN, ApiFormat.OPENAI_CHAT, null)

    private suspend fun deny(gate: ApprovalGate): Triple<Store, Model, SendTool> {
        val store = Store()
        val model = Model(call, done)
        val tool = SendTool()
        AgentLoop(model, ToolRegistry(listOf(tool)), { ToolPolicy.ASK }, gate, store, AgentConfig(), ids = { "id${ids++}" }).run(conversation, listOf(userMessage)).toList()
        return Triple(store, model, tool)
    }

    private var ids = 0

    private fun Store.result() = messages.flatMap { it.toolResults }.single()

    @Test
    fun `the reason reaches the denied call's result exactly`() = runTest {
        val (store, model, tool) = deny(Gate(ApprovalReply(ApprovalAnswer.DENY, "Don't email them, use Slack")))
        assertEquals(0, tool.runs)
        assertEquals(ApprovalDecision.USER_DENIED, store.audit.single().decision)
        val result = store.result()
        assertTrue(result.isError)
        assertEquals(
            "The user declined this action and said: \"Don't email them, use Slack\"\n" +
                "Take that into account: change course as they asked, or ask them if it's unclear how to proceed.",
            result.content,
        )
        // The next request carries it, so the model can adjust.
        assertEquals(result.content, model.requests[1].messages.last().toolResults.single().content)
    }

    @Test
    fun `without a reason the result is the old text, byte for byte`() = runTest {
        val old = "The user declined this action. Ask them how they would like to proceed."
        // A gate that only says no (notifications, Telegram, voice, every gate before this change)…
        assertEquals(old, deny(ApprovalGate { false }).first.result().content)
        // …a deny with no reason, and one whose reason is only white space, read the same.
        assertEquals(old, deny(Gate(ApprovalReply(ApprovalAnswer.DENY))).first.result().content)
        assertEquals(old, deny(Gate(ApprovalReply(ApprovalAnswer.DENY, " \n\t "))).first.result().content)
        assertEquals(old, DenyReason.DECLINED)
    }

    @Test
    fun `the request after a plain deny is unchanged by the feature`() = runTest {
        val plain = deny(ApprovalGate { false }).second.requests[1]
        val viaReply = deny(Gate(ApprovalReply(ApprovalAnswer.DENY, null))).second.requests[1]
        // What the provider gets: every message's role and parts (ids and timestamps aside), the denial's result included.
        assertEquals(plain.messages.map { it.role to it.parts }, viaReply.messages.map { it.role to it.parts })
    }

    @Test
    fun `a reason on an allowing answer is ignored`() = runTest {
        val (store, _, tool) = deny(Gate(ApprovalReply(ApprovalAnswer.ALLOW, "sure")))
        assertEquals(1, tool.runs)
        assertEquals("sent", store.result().content.substringBefore("\n"))
    }

    @Test
    fun `the reason is capped, flattened to one line and cleaned of hidden characters`() {
        val long = "a".repeat(DenyReason.MAX_CHARS + 50)
        assertEquals(DenyReason.MAX_CHARS, DenyReason.clean(long)!!.length)
        assertEquals("Use Slack, not email", DenyReason.clean("  Use Slack,\n\n not\temail  "))
        assertEquals("Use Slack", DenyReason.clean("Use​ Slack"))
        assertEquals("No", DenyReason.clean("N‮o"))
        assertNull(DenyReason.clean("   "))
        assertNull(DenyReason.clean(null))
        // Never splits a surrogate pair at the cap.
        val emoji = "a".repeat(DenyReason.MAX_CHARS - 1) + "😀"
        val cut = DenyReason.clean(emoji)!!
        assertTrue(cut.length <= DenyReason.MAX_CHARS && !Character.isHighSurrogate(cut.last()))
        // The result quotes the capped reason, not the long one.
        assertTrue(DenyReason.result(long).contains("\"" + "a".repeat(DenyReason.MAX_CHARS) + "\""))
    }
}
