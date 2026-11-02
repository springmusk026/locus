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
import dev.locus.core.model.ReviewerMode
import dev.locus.core.model.Role
import dev.locus.core.model.ToolGroup
import dev.locus.core.model.ToolPolicy
import dev.locus.core.model.ToolRisk
import dev.locus.core.model.ToolSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The order in which a call is let through or stopped: chat limits, then Deny (and read-only), then
 * a forced confirmation, then Ask/Allow, with the reviewer only for what's left. Earlier rules win
 * and are never second-guessed by later ones.
 */
class PolicyPrecedenceTest {
    private val conversation = Conversation("c", "t", "p", "system", 0, 0)
    private val user = Message("u", "c", Role.USER, listOf(Part.Text("go")), 0)

    private class Scripted(vararg turns: AssistantTurn) : ChatModel {
        private val queue = ArrayDeque(turns.toList())
        override val format = ApiFormat.OPENAI_CHAT
        override fun stream(request: ChatRequest): Flow<ChatEvent> = flow { emit(ChatEvent.Completed(queue.removeFirst())) }
    }

    private class Store : TranscriptStore {
        val messages = mutableListOf<Message>()
        val audit = mutableListOf<AuditEntry>()
        override suspend fun append(message: Message) { messages += message }
        override suspend fun record(entry: AuditEntry) { audit += entry }
    }

    /** A tool that counts what the loop asked it. */
    private class Probe(
        name: String = "probe",
        override val risk: ToolRisk = ToolRisk.WRITE,
        private val observation: Boolean = false,
        private val confirm: String? = null,
        private val result: () -> ToolResult = { ToolResult("ran") },
        private val refuse: String? = null,
    ) : Tool {
        var runs = 0
        var confirmChecks = 0
        var precheckChat: String? = null
        override suspend fun precheck(args: JsonObject): String? {
            precheckChat = kotlinx.coroutines.currentCoroutineContext()[ToolCallContext]?.conversationId
            return refuse
        }
        override val spec = ToolSpec(name, "probe", ObjectSchema().string("x", "x", required = false).build())
        override fun describe(args: JsonObject) = "probe"
        override suspend fun isObservation(args: JsonObject) = observation
        override suspend fun mustConfirm(args: JsonObject): String? = confirm.also { confirmChecks++ }
        override suspend fun execute(args: JsonObject): ToolResult = result().also { runs++ }
    }

    private class CountingGate(private val answer: Boolean) : ApprovalGate {
        val asked = mutableListOf<ApprovalRequest>()
        override suspend fun request(request: ApprovalRequest) = answer.also { asked += request }
    }

    private class CountingReviewer : ToolReviewer {
        var calls = 0
        override suspend fun review(ctx: ReviewContext): ReviewVerdict {
            calls++
            return ReviewVerdict(Verdict.ALLOW, "fine", "reviewer")
        }
    }

    private fun call(name: String = "probe") =
        AssistantTurn("", listOf(Part.ToolCall("k", name, "{}")), StopReason.TOOL_USE, ApiFormat.OPENAI_CHAT, null)

    private val done = AssistantTurn("ok", emptyList(), StopReason.END_TURN, ApiFormat.OPENAI_CHAT, null)

    private suspend fun run(
        tool: Probe,
        policy: ToolPolicy,
        gate: CountingGate = CountingGate(true),
        readOnly: Boolean = false,
        restrict: (suspend (Tool, JsonObject) -> String?)? = null,
        reviewer: ToolReviewer? = null,
        maxResultChars: Int = 60_000,
    ): Store {
        val store = Store()
        var n = 0
        AgentLoop(
            Scripted(call(tool.spec.name), done), ToolRegistry(listOf(tool)), { policy }, gate, store,
            AgentConfig(
                readOnly = readOnly, restrict = restrict, maxResultChars = maxResultChars,
                reviewer = reviewer?.let { ReviewerSetup(it, ReviewerMode.REVIEW_ALL_BUT_READS, allowOutward = true, trustedMessages = listOf("go")) },
            ),
            ids = { "id${n++}" },
        ).run(conversation, listOf(user)).toList()
        return store
    }

    private val Store.decision get() = audit.single().decision
    private val Store.result get() = messages.first { it.role == Role.TOOL }.toolResults.single()

    @Test
    fun `a call that can only fail is refused before anyone is asked`() = runTest {
        val tool = Probe(refuse = "Read it first.")
        val gate = CountingGate(true)
        val store = run(tool, ToolPolicy.ASK, gate)
        assertEquals(ApprovalDecision.INVALID_INPUT, store.decision)
        assertEquals("Read it first.", store.result.content)
        assertTrue(store.result.isError)
        assertTrue(gate.asked.isEmpty())
        assertEquals(0, tool.runs)
        assertEquals("c", tool.precheckChat)

        // Deny still comes first: the precheck isn't consulted for a tool that's off.
        val denied = Probe(refuse = "Read it first.")
        assertEquals(ApprovalDecision.POLICY_DENIED, run(denied, ToolPolicy.DENY).decision)
        assertNull(denied.precheckChat)

        val fine = Probe()
        run(fine, ToolPolicy.ALLOW)
        assertEquals(1, fine.runs)
    }

    @Test
    fun `deny wins over a forced confirmation, which isn't even consulted`() = runTest {
        val tool = Probe(confirm = "This sends money.")
        val gate = CountingGate(true)
        val store = run(tool, ToolPolicy.DENY, gate)
        assertEquals(ApprovalDecision.POLICY_DENIED, store.decision)
        assertEquals(0, tool.runs)
        assertEquals(0, tool.confirmChecks)
        assertTrue(gate.asked.isEmpty())
        assertTrue("turned this tool off" in store.result.content)
    }

    @Test
    fun `a forced confirmation turns allow into ask and shows the reason`() = runTest {
        val tool = Probe(confirm = "This sends money.")
        val gate = CountingGate(false)
        val store = run(tool, ToolPolicy.ALLOW, gate)
        assertEquals(ApprovalDecision.USER_DENIED, store.decision)
        assertEquals(0, tool.runs)
        assertTrue("This sends money." in gate.asked.single().summary)
    }

    @Test
    fun `chat limits refuse before policy, approval or the reviewer`() = runTest {
        val tool = Probe(confirm = "x")
        val gate = CountingGate(true)
        val reviewer = CountingReviewer()
        val store = run(tool, ToolPolicy.ALLOW, gate, restrict = { _, _ -> "Not allowed in plan mode." }, reviewer = reviewer)
        assertEquals(ApprovalDecision.POLICY_DENIED, store.decision)
        assertEquals("Not allowed in plan mode.", store.result.content)
        assertEquals(0, tool.runs)
        assertEquals(0, tool.confirmChecks)
        assertTrue(gate.asked.isEmpty())
        assertEquals(0, reviewer.calls)
    }

    @Test
    fun `read-only runs never ask, so an observation that needs confirming is refused`() = runTest {
        // browser_open is an observation but confirms a URL that carries a lot of data (possible exfiltration).
        val tool = Probe(risk = ToolRisk.READ, observation = true, confirm = "This address carries a lot of data in it.")
        val gate = CountingGate(true)
        val store = run(tool, ToolPolicy.ALLOW, gate, readOnly = true)
        assertTrue(gate.asked.isEmpty(), "a background run asked the user")
        assertEquals(ApprovalDecision.POLICY_DENIED, store.decision)
        assertEquals(0, tool.runs)
        assertTrue("read-only" in store.result.content)
    }

    @Test
    fun `read-only runs run allowed observations and refuse ones set to Ask`() = runTest {
        val tool = Probe(risk = ToolRisk.READ, observation = true)
        val gate = CountingGate(true)
        val store = run(tool, ToolPolicy.ASK, gate, readOnly = true)
        // Nobody is there to answer, so an observation the user set to Ask doesn't run either.
        assertTrue(gate.asked.isEmpty())
        assertEquals(ApprovalDecision.POLICY_DENIED, store.decision)
        assertEquals(0, tool.runs)

        val allowed = Probe(risk = ToolRisk.READ, observation = true)
        assertEquals(ApprovalDecision.AUTO_ALLOWED, run(allowed, ToolPolicy.ALLOW, CountingGate(false), readOnly = true).decision)
        assertEquals(1, allowed.runs)
    }

    @Test
    fun `unknown tools are rejected without asking anyone`() = runTest {
        val gate = CountingGate(true)
        val store = Store()
        AgentLoop(Scripted(call("no_such_tool"), done), ToolRegistry(listOf(Probe())), { ToolPolicy.ALLOW }, gate, store, ids = { "x${store.messages.size}${store.audit.size}" })
            .run(conversation, listOf(user)).toList()
        assertEquals(ApprovalDecision.INVALID_INPUT, store.decision)
        assertTrue("Unknown tool" in store.result.content)
        assertTrue(gate.asked.isEmpty())
    }

    @Test
    fun `tool failures become error results with a readable message`() = runTest {
        val expected = run(Probe(result = { throw ToolException("No such file: a.txt") }), ToolPolicy.ALLOW)
        assertEquals("No such file: a.txt", expected.result.content)
        assertTrue(expected.result.isError)
        val crash = run(Probe(result = { throw IllegalStateException("boom") }), ToolPolicy.ALLOW)
        assertEquals("IllegalStateException: boom", crash.result.content)
    }

    @Test
    fun `long results are cut for the model and the audit keeps a short preview`() = runTest {
        val store = run(Probe(result = { ToolResult("x".repeat(5_000)) }), ToolPolicy.ALLOW, maxResultChars = 1_000)
        // Nothing could be saved (no spill): the start and end, and a note that the middle is gone.
        val content = store.result.content
        assertTrue(content.startsWith("x".repeat(600)) && content.endsWith("x".repeat(300)), content)
        assertTrue("4,000 characters cut" in content && "wasn't saved" in content, content)
        assertEquals(500, store.audit.single().resultPreview.length)
    }

    @Test
    fun `switched-off groups are refused before plan mode, and connectors by their own key`() = runTest {
        val web = Probe("web_search", ToolRisk.READ, observation = true)
        val write = Probe("write_file")
        val mcp = Probe("mcp_call", ToolRisk.NETWORK)
        fun args(connector: String? = null) = buildJsonObject { connector?.let { put("connector", it) } }
        val off = setOf(ToolGroup.WEB.key, ToolGroup.connectorKey("linear"))

        assertTrue(ChatPolicy.denial(web, args(), planMode = true, disabled = off)!!.contains("switched off Web search"))
        assertNull(ChatPolicy.denial(web, args(), planMode = true, disabled = emptySet()))
        assertNotNull(ChatPolicy.denial(write, args(), planMode = true, disabled = emptySet()))
        assertNull(ChatPolicy.denial(write, args(), planMode = false, disabled = off))
        assertTrue(ChatPolicy.denial(mcp, args("linear"), planMode = false, disabled = off)!!.contains("linear"))
        // A connector id that merely looks like a group key doesn't switch the group off, and vice versa.
        assertNull(ChatPolicy.denial(mcp, args("group:web"), planMode = false, disabled = setOf(ToolGroup.connectorKey("other"))))
        assertNull(ChatPolicy.denial(Probe("connector_call", ToolRisk.NETWORK), args(), planMode = false, disabled = off))
    }
}
