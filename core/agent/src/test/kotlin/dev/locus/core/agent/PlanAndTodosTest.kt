package dev.locus.core.agent

import dev.locus.core.agent.tools.Plans
import dev.locus.core.agent.tools.Todos
import dev.locus.core.agent.tools.agentControlTools
import dev.locus.core.llm.AssistantTurn
import dev.locus.core.llm.ChatEvent
import dev.locus.core.llm.ChatModel
import dev.locus.core.llm.ChatRequest
import dev.locus.core.llm.StopReason
import dev.locus.core.model.AgentControlTools
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.ApprovalDecision
import dev.locus.core.model.AuditEntry
import dev.locus.core.model.Conversation
import dev.locus.core.model.Effort
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import dev.locus.core.model.TodoItem
import dev.locus.core.model.TodoStatus
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlanAndTodosTest {
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
        val audit = mutableListOf<AuditEntry>()
        override suspend fun append(message: Message) { messages += message }
        override suspend fun record(entry: AuditEntry) { audit += entry }
    }

    private class WriteTool : Tool {
        var runs = 0
        override val risk = ToolRisk.WRITE
        override val spec = ToolSpec("write_file", "w", ObjectSchema().string("path", "p").build())
        override fun describe(args: JsonObject) = "write"
        override suspend fun execute(args: JsonObject): ToolResult { runs++; return ToolResult("ok") }
    }

    private class ReadTool(name: String = "read_file") : Tool {
        override val risk = ToolRisk.READ
        override val spec = ToolSpec(name, "r", ObjectSchema().string("path", "p", required = false).string("connector", "c", required = false).build())
        override fun describe(args: JsonObject) = "read"
        override suspend fun execute(args: JsonObject) = ToolResult("text")
    }

    private fun turn(text: String, vararg calls: Part.ToolCall) =
        ChatEvent.Completed(AssistantTurn(text, calls.toList(), if (calls.isEmpty()) StopReason.END_TURN else StopReason.TOOL_USE, ApiFormat.ANTHROPIC_MESSAGES, null))

    private var n = 0
    private var tick = 0L
    private fun loop(model: ChatModel, tools: List<Tool>, store: TranscriptStore, config: AgentConfig = AgentConfig()) =
        AgentLoop(model, ToolRegistry(tools), { ToolPolicy.ALLOW }, { true }, store, config, ids = { "id${n++}" }, clock = { 1_000L * tick++ })

    private val planArgs = """{"title":"Tidy photos","steps":["List the folder","Rename by date"],"notes":"Nothing is deleted."}"""

    @Test
    fun `plan_propose ends the turn without another model request`() = runTest {
        val model = Scripted(listOf(turn("", Part.ToolCall("p1", AgentControlTools.PLAN_PROPOSE, planArgs))))
        val store = Store()
        val events = loop(model, agentControlTools(), store).run(conversation, listOf(user)).toList()

        assertEquals(1, model.requests.size)
        assertEquals(Outcome.Completed, (events.last() as AgentEvent.Finished).outcome)
        assertEquals(listOf(Role.ASSISTANT, Role.TOOL), store.messages.map { it.role })
        // Recovery must not offer "Continue" for a plan waiting on the user.
        assertFalse(recoverTranscript(listOf(user) + store.messages).unfinished)
    }

    @Test
    fun `a failed plan_propose does not end the turn`() = runTest {
        val model = Scripted(
            listOf(turn("", Part.ToolCall("p1", AgentControlTools.PLAN_PROPOSE, """{"title":"x","steps":[]}"""))),
            listOf(turn("Sorry.")),
        )
        loop(model, agentControlTools(), Store()).run(conversation, listOf(user)).toList()
        assertEquals(2, model.requests.size)
    }

    @Test
    fun `plan mode refuses changes but lets observation and checklist tools run`() = runTest {
        val write = WriteTool()
        val store = Store()
        val config = AgentConfig(restrict = { tool, args -> ChatPolicy.denial(tool, args, planMode = true, disabled = emptySet()) })
        val model = Scripted(
            listOf(
                turn(
                    "",
                    Part.ToolCall("a", "read_file", """{"path":"x"}"""),
                    Part.ToolCall("b", "write_file", """{"path":"x"}"""),
                    Part.ToolCall("c", AgentControlTools.TODO_WRITE, """{"todos":[{"content":"Look","status":"in_progress"}]}"""),
                ),
            ),
            listOf(turn("Here is what I found.")),
        )
        loop(model, listOf(ReadTool(), write) + agentControlTools(), store, config).run(conversation, listOf(user)).toList()

        assertEquals(0, write.runs)
        val results = store.messages[1].toolResults.associateBy { it.callId }
        assertFalse(results.getValue("a").isError)
        assertTrue(results.getValue("b").isError && "plan mode" in results.getValue("b").content)
        assertEquals(ApprovalDecision.POLICY_DENIED, results.getValue("b").decision)
        assertFalse(results.getValue("c").isError)
    }

    @Test
    fun `tools switched off for a chat refuse by group and by connector`() = runTest {
        val disabled = setOf(ToolGroup.WEB.key, ToolGroup.connectorKey("linear"))
        val web = ReadTool("web_search")
        val mcp = ReadTool("mcp_call")
        fun args(connector: String? = null) = buildJsonObject { connector?.let { put("connector", it) } }
        assertNotNull(ChatPolicy.denial(web, args(), planMode = false, disabled = disabled))
        assertNotNull(ChatPolicy.denial(mcp, args("linear"), planMode = false, disabled = disabled))
        assertNull(ChatPolicy.denial(mcp, args("github"), planMode = false, disabled = disabled))
        assertNull(ChatPolicy.denial(ReadTool(), args(), planMode = false, disabled = disabled))
    }

    @Test
    fun `reasoning is kept on the turn, timed, and never sent back`() = runTest {
        val model = Scripted(
            listOf(ChatEvent.ReasoningDelta("Checking the folder."), ChatEvent.ReasoningDelta("Now reading."), turn("", Part.ToolCall("a", "read_file", """{"path":"x"}"""))),
            listOf(ChatEvent.TextDelta("Done."), turn("Done.")),
        )
        val store = Store()
        loop(model, listOf(ReadTool()), store).run(conversation, listOf(user)).toList()

        val kept = store.messages[0].reasoning
        assertNotNull(kept)
        assertEquals("Checking the folder.\n\nNow reading.", kept.content)
        assertTrue(kept.durationMs > 0)
        // The second request replays the first turn without the display-only part.
        assertTrue(model.requests[1].messages.none { m -> m.parts.any { it is Part.Reasoning } })
        assertNull(store.messages[2].reasoning)
    }

    @Test
    fun `reasoning deltas inside a sentence are joined as streamed`() {
        val sb = StringBuilder()
        listOf("Let me ", "check. ", "Then", " read.", "Okay").forEach { sb.appendReasoning(it) }
        assertEquals("Let me check. Then read.\n\nOkay", sb.toString())
    }

    @Test
    fun `todo list parses and the latest successful write wins`() {
        fun call(id: String, json: String) = Message("m$id", "c", Role.ASSISTANT, listOf(Part.ToolCall(id, AgentControlTools.TODO_WRITE, json)), 0)
        fun result(id: String, error: Boolean = false) = Message("r$id", "c", Role.TOOL, listOf(Part.ToolResult(id, AgentControlTools.TODO_WRITE, "ok", error)), 0)
        val first = """{"todos":[{"content":"A","status":"in_progress","activeForm":"Doing A"},{"content":"B","status":"pending"}]}"""
        val second = """{"todos":[{"content":"A","status":"completed"},{"content":"B","status":"in_progress","active_form":"Doing B"}]}"""
        val bad = """{"todos":[{"content":"A","status":"done"}]}"""

        assertEquals(
            listOf(TodoItem("A", TodoStatus.COMPLETED), TodoItem("B", TodoStatus.IN_PROGRESS, "Doing B")),
            Todos.latest(listOf(call("1", first), result("1"), call("2", second), result("2"), call("3", bad), result("3", error = true))),
        )
        assertEquals("Doing A", Todos.parse(first)!!.first().shown)
        assertNull(Todos.parse(bad))
        assertNull(Todos.latest(listOf(user)))
    }

    @Test
    fun `plans need steps`() {
        val plan = Plans.parse(planArgs)
        assertNotNull(plan)
        assertEquals(listOf("List the folder", "Rename by date"), plan.steps)
        assertEquals("Nothing is deleted.", plan.notes)
        assertNull(Plans.parse("""{"title":"x","steps":[]}"""))
        assertNull(Plans.parse("not json"))
    }

    @Test
    fun `effort maps per wire format`() {
        assertEquals("low", Effort.OFF.wireValue(ApiFormat.ANTHROPIC_MESSAGES))
        assertEquals("max", Effort.MAX.wireValue(ApiFormat.ANTHROPIC_MESSAGES))
        assertEquals("none", Effort.OFF.wireValue(ApiFormat.OPENAI_RESPONSES))
        assertEquals("xhigh", Effort.MAX.wireValue(ApiFormat.OPENAI_RESPONSES))
        assertNull(Effort.HIGH.wireValue(ApiFormat.OPENAI_CHAT))
        assertEquals(Effort.HIGH, Effort.fromWire("high"))
        assertEquals(Effort.OFF, Effort.fromWire("minimal"))
    }

    @Test
    fun `mode notes are hidden from the user's text`() {
        val m = Message("u", "c", Role.USER, listOf(Part.Text("tidy my photos"), Part.Text(dev.locus.core.model.ModeNote.wrap("plan"))), 0)
        assertEquals("tidy my photos", m.userText)
        assertIs<Part.Text>(m.parts[1])
    }
}
