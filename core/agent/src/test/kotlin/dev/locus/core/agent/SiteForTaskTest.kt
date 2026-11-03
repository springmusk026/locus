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

/**
 * "Allow this site for the rest of this task" (docs/design/taint.md): after a private read, the first
 * image URL on a host asks as before; once the user allows the site for the task, later URLs on exactly
 * that host don't ask again in the same run. Another host still asks, and the next run asks again.
 */
class SiteForTaskTest {
    private val conversation = Conversation("c", "t", "p", "system", 0, 0)
    private fun user(text: String) = Message("u", "c", Role.USER, listOf(Part.Text(text)), 0)

    private class Scripted(turns: List<AssistantTurn>) : ChatModel {
        private val queue = ArrayDeque(turns)
        override val format = ApiFormat.OPENAI_CHAT
        override fun stream(request: ChatRequest): Flow<ChatEvent> = flow { emit(ChatEvent.Completed(queue.removeFirst())) }
    }

    private class Store : TranscriptStore {
        val audit = mutableListOf<AuditEntry>()
        override suspend fun append(message: Message) = Unit
        override suspend fun record(entry: AuditEntry) { audit += entry }
    }

    private class Fake(private val name: String, private val observation: Boolean = true) : Tool {
        var runs = 0
        override val risk = ToolRisk.READ
        override val spec = ToolSpec(name, name, ObjectSchema().string("url", "u", required = false).string("path", "p", required = false).build())
        override fun describe(args: JsonObject) = name
        override suspend fun isObservation(args: JsonObject) = observation
        override suspend fun execute(args: JsonObject) = ToolResult("ok").also { runs++ }
    }

    /** Answers each card with the next of [answers] (then [ApprovalAnswer.ALLOW]). */
    private class Gate(vararg answers: ApprovalAnswer) : ApprovalGate {
        private val queue = ArrayDeque(answers.toList())
        val asked = mutableListOf<ApprovalRequest>()
        override suspend fun request(request: ApprovalRequest) = answer(request).allowed
        override suspend fun answer(request: ApprovalRequest): ApprovalAnswer {
            asked += request
            return queue.removeFirstOrNull() ?: ApprovalAnswer.ALLOW
        }
    }

    private fun calls(vararg calls: Pair<String, String>): AssistantTurn = AssistantTurn(
        "", calls.mapIndexed { i, (name, args) -> Part.ToolCall("k$i-$name-${args.hashCode()}", name, args) }, StopReason.TOOL_USE, ApiFormat.OPENAI_CHAT, null,
    )

    private val done = AssistantTurn("done", emptyList(), StopReason.END_TURN, ApiFormat.OPENAI_CHAT, null)

    private fun url(u: String) = """{"url":"$u"}"""

    private val img1 = "https://images.unsplash.com/photo-1501785888041-af3ef285b470?w=1200"
    private val img2 = "https://images.unsplash.com/photo-1470770841072-f978cf4d019e?w=1200"
    private val img3 = "https://images.unsplash.com/photo-1441974231531-c6227db76b6e?w=1200"

    private suspend fun run(
        turns: List<AssistantTurn>,
        tools: List<Tool>,
        gate: ApprovalGate,
        readOnly: Boolean = false,
        policy: ToolPolicy = ToolPolicy.ALLOW,
    ): Store {
        val store = Store()
        var n = 0
        AgentLoop(
            Scripted(turns + done), ToolRegistry(tools), { tool -> if (tool.spec.name == "fetch_url") policy else ToolPolicy.ALLOW }, gate, store,
            AgentConfig(readOnly = readOnly),
            ids = { "id${n++}" },
        ).run(conversation, listOf(user("read notes.md and make a mood board"))).toList()
        return store
    }

    private fun Store.decisions(name: String) = audit.filter { it.toolName == name }.map { it.decision }

    @Test
    fun `without the site option every image on the host asks`() = runTest {
        // The run is tainted after the first fetch, and taint asks for every unnamed host, so "a host the run
        // already opened" alone doesn't spare the second card. That's what the demo hit.
        val gate = Gate()
        val fetch = Fake("fetch_url")
        run(listOf(calls("read_file" to "{}"), calls("fetch_url" to url(img1)), calls("fetch_url" to url(img2))), listOf(Fake("read_file"), fetch), gate)
        assertEquals(2, gate.asked.size)
        assertEquals("images.unsplash.com", gate.asked.first().siteForTask)
        assertEquals(2, fetch.runs)
    }

    @Test
    fun `a second url on the same host passes after allowing the site for the task`() = runTest {
        val gate = Gate(ApprovalAnswer.ALLOW_SITE_FOR_TASK)
        val fetch = Fake("fetch_url")
        val store = run(
            listOf(calls("read_file" to "{}"), calls("fetch_url" to url(img1)), calls("fetch_url" to url(img2)), calls("fetch_url" to url(img3))),
            listOf(Fake("read_file"), fetch), gate,
        )
        // The first one still asked, per URL, with the private-data flag and the site offer.
        val first = gate.asked.single()
        assertTrue(ApprovalFlag.PRIVATE_DATA in first.flags.orEmpty())
        assertEquals("images.unsplash.com", first.siteForTask)
        assertEquals(3, fetch.runs)
        assertEquals(
            listOf(ApprovalDecision.USER_APPROVED, ApprovalDecision.REMEMBERED_ALLOWED, ApprovalDecision.REMEMBERED_ALLOWED),
            store.decisions("fetch_url"),
        )
    }

    @Test
    fun `another host still asks`() = runTest {
        val gate = Gate(ApprovalAnswer.ALLOW_SITE_FOR_TASK, ApprovalAnswer.DENY)
        val fetch = Fake("fetch_url")
        val store = run(
            listOf(
                calls("read_file" to "{}"),
                calls("fetch_url" to url(img1)),
                calls("fetch_url" to url("https://unsplash.com/photos/abc")), // the parent domain is another host
                calls("fetch_url" to url("https://collect.example/?p=4821")),
            ),
            listOf(Fake("read_file"), fetch), gate,
        )
        assertEquals(listOf("images.unsplash.com", "unsplash.com", "collect.example"), gate.asked.map { it.siteForTask })
        assertEquals(listOf(ApprovalDecision.USER_APPROVED, ApprovalDecision.USER_DENIED, ApprovalDecision.USER_APPROVED), store.decisions("fetch_url"))
    }

    @Test
    fun `a new run asks again`() = runTest {
        val first = Gate(ApprovalAnswer.ALLOW_SITE_FOR_TASK)
        run(listOf(calls("read_file" to "{}"), calls("fetch_url" to url(img1))), listOf(Fake("read_file"), Fake("fetch_url")), first)
        assertEquals(1, first.asked.size)
        val second = Gate()
        run(listOf(calls("read_file" to "{}"), calls("fetch_url" to url(img2))), listOf(Fake("read_file"), Fake("fetch_url")), second)
        assertEquals(1, second.asked.size)
        assertEquals("images.unsplash.com", second.asked.single().siteForTask)
    }

    @Test
    fun `background runs refuse as before, with nothing to offer`() = runTest {
        val gate = Gate(ApprovalAnswer.ALLOW_SITE_FOR_TASK)
        val fetch = Fake("fetch_url")
        val store = run(listOf(calls("read_file" to "{}"), calls("fetch_url" to url(img1)), calls("fetch_url" to url(img2))), listOf(Fake("read_file"), fetch), gate, readOnly = true)
        assertTrue(gate.asked.isEmpty())
        assertEquals(0, fetch.runs)
        assertEquals(listOf(ApprovalDecision.POLICY_DENIED, ApprovalDecision.POLICY_DENIED), store.decisions("fetch_url"))
    }

    @Test
    fun `no site offer where something besides the address asks`() = runTest {
        // The user set the tool to Ask: allowing the site wouldn't stop the next card, so it isn't offered.
        val gate = Gate()
        run(listOf(calls("read_file" to "{}"), calls("fetch_url" to url(img1))), listOf(Fake("read_file"), Fake("fetch_url")), gate, policy = ToolPolicy.ASK)
        assertNull(gate.asked.single().siteForTask)
        // Without a private read or taint the card never shows at all for an Allow tool.
        val quiet = Gate()
        run(listOf(calls("fetch_url" to url(img1))), listOf(Fake("fetch_url")), quiet)
        assertTrue(quiet.asked.isEmpty())
    }

    @Test
    fun `the answer only allows the call when no site was offered`() = runTest {
        // A stray site answer on a plain card (tool set to Ask) allows that call and lifts nothing.
        val gate = Gate(ApprovalAnswer.ALLOW_SITE_FOR_TASK)
        val fetch = Fake("fetch_url")
        run(listOf(calls("read_file" to "{}"), calls("fetch_url" to url(img1)), calls("fetch_url" to url(img2))), listOf(Fake("read_file"), fetch), gate, policy = ToolPolicy.ASK)
        assertEquals(2, gate.asked.size)
        assertEquals(2, fetch.runs)
    }
}
