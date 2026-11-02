package dev.locus.core.agent

import dev.locus.core.agent.home.AgentHome
import dev.locus.core.agent.home.SecretResolver
import dev.locus.core.agent.home.homeTools
import dev.locus.core.agent.integrations.emailTools
import dev.locus.core.agent.integrations.integrationTools
import dev.locus.core.agent.integrations.openDataTools
import dev.locus.core.agent.tools.Workspace
import dev.locus.core.agent.tools.agentControlTools
import dev.locus.core.agent.tools.deliveryTools
import dev.locus.core.agent.tools.subagentTools
import dev.locus.core.agent.tools.webTools
import dev.locus.core.agent.tools.workspaceTools
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
import dev.locus.core.model.ToolCapability
import dev.locus.core.model.ToolGroup
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CapabilityModesTest {
    // ---- Classification -------------------------------------------------------------------------

    @Test
    fun `every tool built in this module has a capability`() {
        // The Android-backed tools (browser, Linux, SSH, phone, MCP, history) are checked by ToolCatalogCapabilityTest.
        val dir = Files.createTempDirectory("caps").toFile()
        val ws = Workspace(File(dir, "workspace"), File(dir, "trash"))
        val home = AgentHome(File(dir, "home"))
        val http = HttpClient(MockEngine { respond("{}") })
        val secrets = SecretResolver { null }
        val tools = workspaceTools(ws) + webTools(http) + homeTools(home, http, secrets) + integrationTools(http, secrets, ws) +
            emailTools(secrets) + openDataTools(http) + agentControlTools() + deliveryTools(ws) + subagentTools(home) { error("unused") }
        assertEquals(emptyList(), tools.map { it.spec.name }.filterNot { it in ToolCapability.BY_TOOL })
    }

    @Test
    fun `an unclassified tool is treated as outward`() {
        assertEquals(ToolCapability.OUTWARD, ToolCapability.of("some_new_tool"))
        assertEquals(ToolCapability.LOCAL, ToolCapability.of("write_file"))
        assertEquals(ToolCapability.READ, ToolCapability.of("read_file"))
    }

    // ---- Stored with the chat's other limits ----------------------------------------------------

    @Test
    fun `the mode is kept beside switched-off tools, and Full stores nothing`() {
        val off = setOf(ToolGroup.WEB.key)
        assertEquals(ChatMode.FULL, ChatMode.of(off))
        val ro = ChatMode.withMode(off, ChatMode.READ_ONLY)
        assertEquals(ChatMode.READ_ONLY, ChatMode.of(ro))
        assertTrue(ToolGroup.WEB.key in ro)
        val ws = ChatMode.withMode(ro, ChatMode.WORKSPACE)
        assertEquals(setOf(ToolGroup.WEB.key, "mode:workspace"), ws)
        assertEquals(off, ChatMode.withMode(ws, ChatMode.FULL))
        // If two were ever stored, the narrower wins.
        assertEquals(ChatMode.READ_ONLY, ChatMode.of(setOf("mode:workspace", "mode:read_only")))
    }

    // ---- Allow / deny matrix --------------------------------------------------------------------

    private class Fake(name: String, override val risk: ToolRisk, private val observes: (JsonObject) -> Boolean = { risk == ToolRisk.READ }) : Tool {
        var runs = 0
        override val spec = ToolSpec(name, "test", ObjectSchema().string("method", "m", required = false).build())
        override fun describe(args: JsonObject) = spec.name
        override suspend fun isObservation(args: JsonObject) = observes(args)
        override suspend fun execute(args: JsonObject): ToolResult { runs++; return ToolResult("ok") }
    }

    private val none = buildJsonObject { }
    private val post = buildJsonObject { put("method", "POST") }

    private val readFile = Fake("read_file", ToolRisk.READ)
    private val writeFile = Fake("write_file", ToolRisk.WRITE)
    private val runCommand = Fake("run_command", ToolRisk.EXECUTE)
    private val memorySave = Fake("memory_save", ToolRisk.WRITE)
    private val emailSend = Fake("email_send", ToolRisk.NETWORK)
    private val deploy = Fake("netlify_deploy", ToolRisk.NETWORK)
    private val sshExec = Fake("ssh_exec", ToolRisk.EXECUTE)
    private val click = Fake("browser_click", ToolRisk.WRITE)
    private val intent = Fake("device_sms", ToolRisk.WRITE)
    private val fetch = Fake("fetch_url", ToolRisk.NETWORK) { (it["method"]?.toString()?.trim('"') ?: "GET") == "GET" }
    private val unknown = Fake("brand_new_tool", ToolRisk.WRITE)

    private suspend fun allowed(mode: ChatMode, tool: Tool, args: JsonObject = none) =
        ChatPolicy.denial(tool, args, planMode = false, disabled = ChatMode.withMode(emptySet(), mode)) == null

    @Test
    fun `read-only allows only observations`() = runTest {
        val m = ChatMode.READ_ONLY
        assertTrue(allowed(m, readFile))
        assertTrue(allowed(m, fetch), "a GET is a read")
        for (t in listOf(writeFile, runCommand, memorySave, emailSend, deploy, sshExec, click, intent, unknown)) assertFalse(allowed(m, t), t.spec.name)
        assertFalse(allowed(m, fetch, post))
    }

    @Test
    fun `workspace allows reads and changes on the phone, nothing outward`() = runTest {
        val m = ChatMode.WORKSPACE
        for (t in listOf(readFile, writeFile, runCommand, memorySave)) assertTrue(allowed(m, t), t.spec.name)
        assertTrue(allowed(m, fetch))
        for (t in listOf(emailSend, deploy, sshExec, click, intent, unknown)) assertFalse(allowed(m, t), t.spec.name)
        assertFalse(allowed(m, fetch, post), "a POST is a network write")
    }

    @Test
    fun `full refuses nothing`() = runTest {
        for (t in listOf(readFile, writeFile, runCommand, memorySave, emailSend, deploy, sshExec, click, intent, unknown)) assertTrue(allowed(ChatMode.FULL, t), t.spec.name)
        assertTrue(allowed(ChatMode.FULL, fetch, post))
    }

    @Test
    fun `refusals tell the agent why and to ask the user`() = runTest {
        val ro = ChatPolicy.modeDenial(ChatMode.READ_ONLY, writeFile, none)
        assertNotNull(ro)
        assertTrue("this chat is read-only" in ro && "ask the user to switch the chat" in ro && "write_file" in ro)
        val ws = ChatPolicy.modeDenial(ChatMode.WORKSPACE, emailSend, none)
        assertNotNull(ws)
        assertTrue("Workspace mode" in ws && "email_send" in ws && "switch the chat to Full mode" in ws)
        assertNull(ChatPolicy.modeDenial(ChatMode.FULL, emailSend, none))
    }

    @Test
    fun `a switched-off group still refuses in Full mode, and in a narrower mode`() = runTest {
        val web = Fake("web_search", ToolRisk.READ)
        assertNotNull(ChatPolicy.denial(web, none, false, setOf(ToolGroup.WEB.key)))
        assertNotNull(ChatPolicy.denial(web, none, false, ChatMode.withMode(setOf(ToolGroup.WEB.key), ChatMode.READ_ONLY)))
    }

    // ---- Through the loop: modes narrow, never widen --------------------------------------------

    private val conversation = Conversation("c", "t", "p", "system", 0, 0)
    private val user = Message("u", "c", Role.USER, listOf(Part.Text("go")), 0)

    private class Scripted(private vararg val steps: List<ChatEvent>) : ChatModel {
        val requests = mutableListOf<ChatRequest>()
        private var i = 0
        override val format = ApiFormat.ANTHROPIC_MESSAGES
        override fun stream(request: ChatRequest): Flow<ChatEvent> = flow {
            requests += request
            steps[i++].forEach { emit(it) }
        }
    }

    private class Store : TranscriptStore {
        val messages = mutableListOf<Message>()
        override suspend fun append(message: Message) { messages += message }
        override suspend fun record(entry: AuditEntry) = Unit
    }

    private fun turn(vararg calls: Part.ToolCall) =
        ChatEvent.Completed(AssistantTurn("", calls.toList(), if (calls.isEmpty()) StopReason.END_TURN else StopReason.TOOL_USE, ApiFormat.ANTHROPIC_MESSAGES, null))

    private class Run(val results: Map<String, Part.ToolResult>, val asked: List<String>, val requests: List<ChatRequest>)

    private suspend fun run(tools: List<Tool>, mode: () -> ChatMode, policy: (Tool) -> ToolPolicy, vararg calls: Part.ToolCall): Run {
        val asked = mutableListOf<String>()
        val store = Store()
        val model = Scripted(listOf(turn(*calls)), listOf(turn()))
        var id = 0
        AgentLoop(
            model, ToolRegistry(tools), policy, { asked += it.toolName; true }, store,
            AgentConfig(restrict = { tool, args -> ChatPolicy.denial(tool, args, false, ChatMode.withMode(emptySet(), mode())) }),
            ids = { "id${id++}" },
        ).run(conversation, listOf(user)).toList()
        return Run(store.messages.flatMap { it.toolResults }.associateBy { it.callId }, asked, model.requests)
    }

    @Test
    fun `a user Deny or Ask is never widened by a mode`() = runTest {
        val r = run(
            listOf(writeFile, runCommand), { ChatMode.WORKSPACE },
            { if (it.spec.name == "write_file") ToolPolicy.DENY else ToolPolicy.ASK },
            Part.ToolCall("a", "write_file", "{}"), Part.ToolCall("b", "run_command", "{}"),
        )
        assertEquals(ApprovalDecision.POLICY_DENIED, r.results.getValue("a").decision)
        assertEquals(0, writeFile.runs)
        assertEquals(listOf("run_command"), r.asked, "Ask still asks")
        assertEquals(ApprovalDecision.USER_APPROVED, r.results.getValue("b").decision)
    }

    @Test
    fun `calls that must be confirmed still ask in a narrower mode`() = runTest {
        val save = object : Tool by Fake("browser_save", ToolRisk.WRITE) {
            override suspend fun mustConfirm(args: JsonObject) = "Saving a page."
        }
        val r = run(listOf(save), { ChatMode.WORKSPACE }, { ToolPolicy.ALLOW }, Part.ToolCall("a", "browser_save", "{}"))
        assertEquals(listOf("browser_save"), r.asked)
    }

    @Test
    fun `a mode refusal reaches the model and the tool list stays the same`() = runTest {
        val tools = listOf(readFile, writeFile, emailSend)
        val full = run(tools, { ChatMode.FULL }, { ToolPolicy.ALLOW }, Part.ToolCall("a", "read_file", "{}"))
        val ro = run(tools, { ChatMode.READ_ONLY }, { ToolPolicy.ALLOW }, Part.ToolCall("a", "read_file", "{}"), Part.ToolCall("b", "write_file", "{}"))
        val refused = ro.results.getValue("b")
        assertTrue(refused.isError && "read-only" in refused.content)
        assertEquals(ApprovalDecision.POLICY_DENIED, refused.decision)
        assertFalse(ro.results.getValue("a").isError)
        // Prompt caching: same system prompt and tools whatever the mode.
        assertEquals(full.requests.first().tools, ro.requests.first().tools)
        assertEquals(full.requests.first().system, ro.requests.first().system)
    }

    @Test
    fun `switching mode mid-run applies to the next call`() = runTest {
        var mode = ChatMode.FULL
        val flip = object : Tool by Fake("read_file", ToolRisk.READ) {
            override suspend fun execute(args: JsonObject): ToolResult { mode = ChatMode.READ_ONLY; return ToolResult("ok") }
        }
        val r = run(listOf(flip, writeFile), { mode }, { ToolPolicy.ALLOW }, Part.ToolCall("a", "read_file", "{}"), Part.ToolCall("b", "write_file", "{}"))
        assertTrue(r.results.getValue("b").isError)
        assertEquals(0, writeFile.runs)
    }
}
