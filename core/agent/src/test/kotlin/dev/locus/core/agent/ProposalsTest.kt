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
import dev.locus.core.model.ModeNote
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Proposals (docs/design/needs-you.md): in a scheduled or triggered run, a call to a proposable tool that would
 * be refused is recorded with its exact arguments instead, and the model reads that it was proposed. Every other
 * call is refused as before, and chats are unchanged. Approving runs exactly that call once, through the same
 * checks with today's settings.
 */
class ProposalsTest {
    private val conversation = Conversation("c", "t", "p", "system", 0, 0)
    private val ask = Message("u", "c", Role.USER, listOf(Part.Text("Reply to the landlord if he wrote")), 0)

    private class Scripted(turns: List<AssistantTurn>) : ChatModel {
        private val queue = ArrayDeque(turns)
        override val format = ApiFormat.OPENAI_CHAT
        override fun stream(request: ChatRequest): Flow<ChatEvent> = flow { emit(ChatEvent.Completed(queue.removeFirst())) }
    }

    private class Store : TranscriptStore {
        val messages = mutableListOf<Message>()
        val audit = mutableListOf<AuditEntry>()
        override suspend fun append(message: Message) { messages += message }
        override suspend fun record(entry: AuditEntry) { audit += entry }
    }

    private class Fake(
        private val name: String,
        override val risk: ToolRisk = ToolRisk.NETWORK,
        private val observation: Boolean = false,
        private val confirm: String? = null,
        private val connected: Boolean = false,
    ) : Tool {
        var runs = 0
        override val spec = ToolSpec(name, name, ObjectSchema().string("to", "t", required = false).string("body", "b", required = false).build())
        override fun describe(args: JsonObject) = "Send email to ${args.str("to")}"
        override suspend fun isObservation(args: JsonObject) = observation
        override suspend fun mustConfirm(args: JsonObject) = confirm
        override suspend fun asksFirst(args: JsonObject) = connected
        override suspend fun execute(args: JsonObject) = ToolResult("Sent to ${args.str("to")}").also { runs++ }
    }

    private class Gate(private val answer: Boolean) : ApprovalGate {
        val asked = mutableListOf<ApprovalRequest>()
        override suspend fun request(request: ApprovalRequest) = answer.also { asked += request }
    }

    private class Sink(private val refuse: String? = null) : ProposalSink {
        val drafts = mutableListOf<ProposalDraft>()
        override suspend fun propose(draft: ProposalDraft): ProposalReceipt {
            refuse?.let { return ProposalReceipt.Refused(it) }
            val duplicate = drafts.any { it.toolName == draft.toolName && it.argumentsJson == draft.argumentsJson }
            if (!duplicate) drafts += draft
            return ProposalReceipt.Recorded("p${drafts.size}", duplicate)
        }
    }

    private val args = """{"to":"landlord@example.com","body":"The boiler is fixed."}"""

    private fun calls(vararg calls: Pair<String, String>) = AssistantTurn(
        "", calls.mapIndexed { i, (name, a) -> Part.ToolCall("k$i-$name", name, a) }, StopReason.TOOL_USE, ApiFormat.OPENAI_CHAT, null,
    )

    private val done = AssistantTurn("I proposed the reply.", emptyList(), StopReason.END_TURN, ApiFormat.OPENAI_CHAT, null)

    private suspend fun run(
        turn: AssistantTurn,
        tools: List<Tool>,
        sink: ProposalSink?,
        gate: Gate = Gate(answer = true),
        readOnly: Boolean = true,
        unattended: Boolean = true,
        depth: Int = 0,
        policy: ToolPolicy = ToolPolicy.ASK,
        history: List<Message> = listOf(ask),
    ): Store {
        val store = Store()
        var n = 0
        AgentLoop(
            Scripted(listOf(turn, done)), ToolRegistry(tools), { policy }, gate, store,
            AgentConfig(readOnly = readOnly, unattended = unattended, depth = depth, proposals = sink), ids = { "id${n++}" },
        ).run(conversation, history).toList()
        return store
    }

    private fun Store.result() = messages.flatMap { it.toolResults }.single()

    @Test
    fun `a scheduled run's email becomes a proposal with its exact arguments, and nothing is sent`() = runTest {
        val send = Fake("email_send", confirm = "Sends a real email.")
        val sink = Sink()
        val gate = Gate(answer = true)
        val store = run(calls("email_send" to args), listOf(send), sink, gate)
        assertEquals(0, send.runs)
        assertTrue(gate.asked.isEmpty(), "nobody is asked in a background run")
        val draft = sink.drafts.single()
        assertEquals("email_send", draft.toolName)
        assertEquals(args, draft.argumentsJson)
        assertEquals("c", draft.conversationId)
        assertEquals("k0-email_send", draft.callId)
        assertEquals("Send email to landlord@example.com", draft.summary)
        assertTrue(ApprovalFlag.MUST_CONFIRM in draft.flags)
        val result = store.result()
        assertFalse(result.isError)
        assertEquals(ApprovalDecision.PROPOSED, result.decision)
        assertTrue("Not run yet" in result.content && "Needs you" in result.content, result.content)
        assertEquals(ApprovalDecision.PROPOSED, store.audit.single().decision)
    }

    @Test
    fun `a tool set to Allow is proposed too, never run unattended`() = runTest {
        val write = Fake("write_file", ToolRisk.WRITE)
        val sink = Sink()
        run(calls("write_file" to """{"to":"notes.md"}"""), listOf(write), sink, policy = ToolPolicy.ALLOW)
        assertEquals(0, write.runs)
        assertEquals("write_file", sink.drafts.single().toolName)
    }

    @Test
    fun `a tainted run's proposal carries what it read and says so`() = runTest {
        val event = Message("u", "c", Role.USER, listOf(Part.Text("Answer new mail"), Part.Text(ModeNote.wrap(EventNote.of("mail", "Pay now", SecretScrubber())))), 0)
        val sink = Sink()
        run(calls("email_send" to args), listOf(Fake("email_send")), sink, history = listOf(event))
        val draft = sink.drafts.single()
        assertTrue(ApprovalFlag.TAINTED in draft.flags)
        assertEquals(setOf("an event (mail)"), draft.taintSources)
        assertTrue(draft.reason.orEmpty().isNotBlank())
    }

    @Test
    fun `tools that aren't proposable are still refused, and nothing is recorded`() = runTest {
        val deploy = Fake("netlify_deploy")
        val sink = Sink()
        val store = run(calls("netlify_deploy" to args), listOf(deploy), sink)
        assertEquals(0, deploy.runs)
        assertTrue(sink.drafts.isEmpty())
        assertEquals(ApprovalDecision.POLICY_DENIED, store.result().decision)
        assertTrue(store.result().isError)
    }

    @Test
    fun `a tool the user turned off is refused, not proposed`() = runTest {
        val sink = Sink()
        val store = run(calls("email_send" to args), listOf(Fake("email_send")), sink, policy = ToolPolicy.DENY)
        assertTrue(sink.drafts.isEmpty())
        assertEquals(ApprovalDecision.POLICY_DENIED, store.result().decision)
    }

    @Test
    fun `a write to the user's connected folder is refused, not proposed`() = runTest {
        val sink = Sink()
        val store = run(calls("write_file" to """{"to":"Documents/a.txt"}"""), listOf(Fake("write_file", ToolRisk.WRITE, connected = true)), sink)
        assertTrue(sink.drafts.isEmpty())
        assertEquals(ApprovalDecision.POLICY_DENIED, store.result().decision)
    }

    @Test
    fun `without a sink, a Read-only chat, or in a helper, the call is refused as before`() = runTest {
        for ((sink, unattended, depth) in listOf(Triple(null, true, 0), Triple(Sink(), false, 0), Triple(Sink(), true, 1))) {
            val send = Fake("email_send")
            val store = run(calls("email_send" to args), listOf(send), sink, unattended = unattended, depth = depth)
            assertEquals(0, send.runs)
            assertEquals(ApprovalDecision.POLICY_DENIED, store.result().decision)
            assertTrue((sink as Sink?)?.drafts.isNullOrEmpty())
        }
    }

    @Test
    fun `an interactive chat still asks and runs, and proposes nothing`() = runTest {
        val send = Fake("email_send")
        val sink = Sink()
        val gate = Gate(answer = true)
        val store = run(calls("email_send" to args), listOf(send), sink, gate, readOnly = false, unattended = false)
        assertEquals(1, send.runs)
        assertEquals(1, gate.asked.size)
        assertTrue(sink.drafts.isEmpty())
        assertEquals(ApprovalDecision.USER_APPROVED, store.result().decision)
    }

    @Test
    fun `one run proposes at most five actions, and a repeat isn't counted twice`() = runTest {
        val sink = Sink()
        val many = (0 until 7).map { i -> "email_send" to """{"to":"p$i@example.com"}""" }.toTypedArray()
        val store = run(calls(*many), listOf(Fake("email_send")), sink)
        assertEquals(Proposals.MAX_PER_RUN, sink.drafts.size)
        val decisions = store.messages.flatMap { it.toolResults }.map { it.decision }
        assertEquals(List(5) { ApprovalDecision.PROPOSED } + List(2) { ApprovalDecision.POLICY_DENIED }, decisions)

        val again = Sink()
        val twice = run(calls("email_send" to args, "email_send" to args), listOf(Fake("email_send")), again)
        assertEquals(1, again.drafts.size)
        assertTrue("already waiting" in twice.messages.flatMap { it.toolResults }.last().content)
    }

    @Test
    fun `a sink that refuses (too many waiting) refuses the call with its reason`() = runTest {
        val store = run(calls("email_send" to args), listOf(Fake("email_send")), Sink(refuse = "50 proposals are already waiting for the user."))
        val result = store.result()
        assertEquals(ApprovalDecision.POLICY_DENIED, result.decision)
        assertTrue("50 proposals" in result.content)
    }

    // ---- Approving: runApproved

    private suspend fun approve(tool: Fake, gate: Gate, policy: ToolPolicy = ToolPolicy.ASK, restrict: CallCheck? = null, history: List<Message> = listOf(ask)): Pair<ApprovedCall, Store> {
        val store = Store()
        val loop = AgentLoop(
            Scripted(emptyList()), ToolRegistry(listOf(tool)), { policy }, gate, store,
            AgentConfig(restrict = restrict, escalate = { _, _ -> "Proposed; approved in Needs you." }), ids = { "a" },
        )
        return loop.runApproved("c", Part.ToolCall("k0-email_send", "email_send", args), history) to store
    }

    @Test
    fun `approving runs exactly the proposed call once, as the user's approval`() = runTest {
        val send = Fake("email_send", confirm = "Sends a real email.")
        val gate = Gate(answer = true)
        val (ran, store) = approve(send, gate)
        assertEquals(1, send.runs)
        assertTrue(ran.ran)
        assertEquals(ApprovalDecision.USER_APPROVED, ran.decision)
        assertTrue("Sent to landlord@example.com" in ran.content)
        assertEquals(args, gate.asked.single().argumentsJson)
        assertEquals(ApprovalDecision.USER_APPROVED, store.audit.single().decision)
        // Nothing is appended for the call: it was answered when it was proposed.
        assertTrue(store.messages.isEmpty())
    }

    @Test
    fun `approving checks again with today's settings, so a tool turned off since doesn't run`() = runTest {
        val send = Fake("email_send")
        val (ran, _) = approve(send, Gate(answer = true), policy = ToolPolicy.DENY)
        assertEquals(0, send.runs)
        assertFalse(ran.ran)
        assertEquals(ApprovalDecision.POLICY_DENIED, ran.decision)

        val (limited, _) = approve(Fake("email_send"), Gate(answer = true), restrict = { _, _ -> "Plan mode is on." })
        assertFalse(limited.ran)
    }

    @Test
    fun `a gate that refuses (the call now needs more than the card showed) runs nothing`() = runTest {
        val send = Fake("email_send")
        val (ran, _) = approve(send, Gate(answer = false))
        assertEquals(0, send.runs)
        assertEquals(ApprovalDecision.USER_DENIED, ran.decision)
    }

    @Test
    fun `approving a tainted chat's proposal still asks with the taint shown`() = runTest {
        val event = Message("u", "c", Role.USER, listOf(Part.Text("Answer new mail"), Part.Text(ModeNote.wrap(EventNote.of("mail", "hi", SecretScrubber())))), 0)
        val gate = Gate(answer = true)
        approve(Fake("email_send"), gate, history = listOf(event))
        assertTrue(ApprovalFlag.TAINTED in gate.asked.single().flags.orEmpty())
    }
}
