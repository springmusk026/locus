package dev.locus.core.agent

import dev.locus.core.agent.home.AgentHome
import dev.locus.core.agent.workflow.WorkflowChecks
import dev.locus.core.agent.workflow.WorkflowDraft
import dev.locus.core.agent.workflow.WorkflowInputException
import dev.locus.core.agent.workflow.WorkflowRender
import dev.locus.core.agent.workflow.WorkflowScope
import dev.locus.core.agent.workflow.WorkflowStore
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
import dev.locus.core.model.ReceiptCheck
import dev.locus.core.model.Role
import dev.locus.core.model.TaskOutcome
import dev.locus.core.model.TaskRecord
import dev.locus.core.model.ToolPolicy
import dev.locus.core.model.ToolRisk
import dev.locus.core.model.ToolSpec
import dev.locus.core.model.Verification
import dev.locus.core.model.Workflow
import dev.locus.core.model.WorkflowCheck
import dev.locus.core.model.WorkflowCheck.CheckKind
import dev.locus.core.model.WorkflowParam
import dev.locus.core.model.WorkflowParamType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reusable workflows (docs/design/workflows.md): drafting, rendering, scope, checks and storage. */
class WorkflowsTest {

    // ---- A task, as the chat keeps it ------------------------------------------------------------

    private fun msg(id: String, role: Role, at: Long, vararg parts: Part) = Message(id, "c", role, parts.toList(), at)

    private fun call(id: String, name: String, args: String) = Part.ToolCall(id, name, args)

    private fun result(id: String, name: String, content: String, decision: ApprovalDecision, isError: Boolean = false) =
        Part.ToolResult(id, name, content, isError, decision)

    /** "Run the tests in app/ and deploy https://example.dev": reads, a failing test run, a fix, a passing run, an approved deploy. */
    private val task = listOf(
        msg("u", Role.USER, 10, Part.Text("Run the tests in app/ and fix failures, then deploy to https://example.dev")),
        msg("a1", Role.ASSISTANT, 11, call("c1", "read_file", """{"path":"app/build.gradle"}"""), call("c2", "run_command", """{"command":"./gradlew test"}""")),
        msg("t1", Role.TOOL, 12, result("c1", "read_file", "…", ApprovalDecision.AUTO_ALLOWED), result("c2", "run_command", "Exit code 1\nFAILED", ApprovalDecision.AUTO_ALLOWED)),
        msg("a2", Role.ASSISTANT, 13, call("c3", "edit_file", """{"path":"app/src/A.kt"}"""), call("c4", "run_command", """{"command":"./gradlew test"}""")),
        msg("t2", Role.TOOL, 14, result("c3", "edit_file", "ok", ApprovalDecision.AUTO_ALLOWED), result("c4", "run_command", "Exit code 0", ApprovalDecision.AUTO_ALLOWED)),
        msg("a3", Role.ASSISTANT, 15, call("c5", "mcp_call", """{"connector":"vercel","tool":"deploy"}"""), call("c6", "send_email", "{}")),
        msg("t3", Role.TOOL, 16, result("c5", "mcp_call", "deployed", ApprovalDecision.USER_APPROVED), result("c6", "send_email", "denied", ApprovalDecision.USER_DENIED, isError = true)),
        msg("a4", Role.ASSISTANT, 17, Part.Text("Tests pass and it's live at https://example.dev.")),
    )

    private val runs = listOf(
        ReceiptCheck("a1", "c2", Verification.Kind.TEST, false),
        ReceiptCheck("a2", "c4", Verification.Kind.TEST, true),
    )

    private val trace = WorkflowDraft.Trace.of(task, runs, listOf("app/src/A.kt"), "c", "task1")

    // ---- Drafting -------------------------------------------------------------------------------

    @Test
    fun `the record decides tools, approval points and checks`() {
        assertEquals(listOf("read_file", "run_command", "edit_file", "connector:vercel"), WorkflowDraft.tools(trace))
        // Approved stays Ask; the declined call isn't a tool of the workflow at all.
        assertEquals(listOf("connector:vercel"), WorkflowDraft.approvals(trace))
        assertEquals(listOf(WorkflowCheck(CheckKind.TESTS_PASS, "Tests pass")), WorkflowDraft.checks(trace))
    }

    @Test
    fun `a failing last run isn't a success check`() {
        val failing = trace.copy(checks = runs.reversed())
        assertTrue(WorkflowDraft.checks(failing).isEmpty())
    }

    @Test
    fun `the helper's draft keeps only parameters it uses and the record's limits`() {
        val reply = """
            Here you go:
            ```json
            {"name": "Test and deploy", "description": "Runs tests, fixes them and deploys.",
             "goal": "Run the tests in {folder}, fix failures, then deploy to {site}",
             "parameters": [
               {"name": "folder", "label": "Folder", "type": "path", "default": "app/"},
               {"name": "site", "type": "url", "default": "https://example.dev"},
               {"name": "unused", "type": "text"},
               {"name": "Bad Name!", "type": "text"}
             ],
             "steps": ["Run the tests in {folder}", "Fix failures", "Deploy to {site} with {branch}"],
             "expected_output": "The live address",
             "checks": ["Site loads"]}
            ```
        """.trimIndent()
        val w = WorkflowDraft.draft(trace, reply, now = 5)
        assertEquals("test-and-deploy", w.id)
        assertEquals(listOf("folder", "site"), w.parameters.map { it.name })
        assertEquals(WorkflowParamType.PATH, w.parameters[0].type)
        assertEquals("app/", w.parameters[0].default)
        // A slot without a parameter is put back as words, so it's never sent as a raw {slot}.
        assertEquals("Deploy to {site} with branch", w.steps[2])
        assertEquals(listOf("connector:vercel"), w.approvals)
        assertEquals(listOf(CheckKind.TESTS_PASS, CheckKind.AGENT), w.checks.map { it.kind })
        assertEquals("The live address", w.expectedOutput)
        assertEquals("task1", w.source?.taskId)
        WorkflowStore.validate(w)
    }

    @Test
    fun `without a usable reply the draft comes from the record`() {
        for (raw in listOf(null, "Sorry, I can't.", """{"goal": ""}""")) {
            val w = WorkflowDraft.draft(trace, raw)
            assertEquals("Run the tests in app/ and fix failures, then deploy to {url}", w.goal)
            assertEquals(listOf(WorkflowParam("url", "Address", WorkflowParamType.URL, default = "https://example.dev")), w.parameters)
            assertTrue(w.steps.isNotEmpty())
            assertEquals(WorkflowDraft.tools(trace), w.tools)
            WorkflowStore.validate(w)
        }
    }

    @Test
    fun `the helper prompt shows steps as labels with their approvals`() {
        val prompt = WorkflowDraft.prompt(trace)
        assertTrue("(user approved)" in prompt, prompt)
        assertTrue("<request>" in prompt && "https://example.dev" in prompt)
    }

    // ---- Rendering ------------------------------------------------------------------------------

    private val workflow = Workflow(
        id = "test-and-deploy",
        name = "Test and deploy",
        goal = "Run the tests in {folder} and deploy to {site}",
        parameters = listOf(
            WorkflowParam("folder", type = WorkflowParamType.PATH, default = "app/"),
            WorkflowParam("site", type = WorkflowParamType.URL),
            WorkflowParam("dry_run", type = WorkflowParamType.BOOLEAN, default = "false", required = false),
        ),
        steps = listOf("Run the tests in {folder}", "Deploy to {site}"),
        tools = listOf("read_file", "run_command", "edit_file", "connector:vercel"),
        approvals = listOf("connector:vercel"),
        checks = listOf(WorkflowCheck(CheckKind.TESTS_PASS, "Tests pass"), WorkflowCheck(CheckKind.AGENT, "{site} loads")),
        expectedOutput = "The address of {site}",
    )

    @Test
    fun `rendering fills slots, defaults and the plan note`() {
        val r = WorkflowRender.render(workflow, mapOf("site" to "https://a.dev", "dry_run" to "TRUE"))
        assertEquals("Run the tests in app/ and deploy to https://a.dev", r.text)
        assertEquals(mapOf("folder" to "app/", "site" to "https://a.dev", "dry_run" to "true"), r.plan.inputs)
        assertEquals(listOf("Run the tests in app/", "Deploy to https://a.dev"), r.plan.steps)
        assertEquals("https://a.dev loads", r.plan.checks[1].label)
        assertTrue("not a script" in r.note)
        assertTrue("Check: <label> — passed" in r.note)

        val message = msg("u", Role.USER, 1, Part.Text(r.text), Part.Text(ModeNote.wrap(r.note)))
        assertEquals(r.plan, WorkflowRender.planIn(message))
        // The bubble and what the reviewer trusts are the goal alone.
        assertEquals(r.text, message.userText)
    }

    @Test
    fun `bad inputs are refused with the input named`() {
        val missing = assertFailsWith<WorkflowInputException> { WorkflowRender.render(workflow, emptyMap()) }
        assertEquals("site", missing.param)
        assertFailsWith<WorkflowInputException> { WorkflowRender.render(workflow, mapOf("site" to "example.dev")) }
        assertFailsWith<WorkflowInputException> { WorkflowRender.render(workflow, mapOf("site" to "https://a.dev", "folder" to "../etc")) }
        assertFailsWith<WorkflowInputException> { WorkflowRender.render(workflow, mapOf("site" to "https://a.dev", "dry_run" to "maybe")) }
        val choice = WorkflowParam("env", type = WorkflowParamType.CHOICE, options = listOf("staging", "prod"))
        assertFailsWith<WorkflowInputException> { WorkflowRender.inputs(listOf(choice), mapOf("env" to "dev")) }
        assertEquals(mapOf("env" to "prod"), WorkflowRender.inputs(listOf(choice), mapOf("env" to "prod")))
    }

    @Test
    fun `the active workflow lasts for its task and ends with the next request`() {
        val r = WorkflowRender.render(workflow, mapOf("site" to "https://a.dev"))
        val start = msg("u1", Role.USER, 1, Part.Text(r.text), Part.Text(ModeNote.wrap(r.note)))
        val reply = msg("a1", Role.ASSISTANT, 2, Part.Text("working"))
        val midTask = msg("u2", Role.USER, 3, Part.Text("also check lint"), Part.Text(ModeNote.wrap(ModeNote.MID_TASK)))
        val report = msg("u3", Role.USER, 4, Part.Text("[sub-agent report] researcher finished"), Part.Text(ModeNote.result("…")))
        assertNotNull(WorkflowRender.active(listOf(start, reply, midTask, report)))
        val next = msg("u4", Role.USER, 5, Part.Text("thanks, now something else"))
        assertNull(WorkflowRender.active(listOf(start, reply, next)))
        assertNull(WorkflowRender.active(listOf(reply)))
    }

    // ---- Scope ----------------------------------------------------------------------------------

    private val plan = WorkflowRender.render(workflow, mapOf("site" to "https://a.dev")).plan
    private val none = JsonObject(emptyMap())

    @Test
    fun `calls outside the declared tools and approval points ask`() {
        assertNull(WorkflowScope.escalation(plan, "run_command", none, observation = false))
        assertNull(WorkflowScope.escalation(plan, "web_search", none, observation = true), "looking is always allowed")
        assertNull(WorkflowScope.escalation(plan, "todo_write", none, observation = false))
        assertNotNull(WorkflowScope.escalation(plan, "send_email", none, observation = false))
        val vercel = buildJsonObject { put("connector", "vercel") }
        val github = buildJsonObject { put("connector", "github") }
        assertTrue(WorkflowScope.escalation(plan, "mcp_call", vercel, observation = false)!!.contains("asks before this step"))
        // An approval point asks even when the call only looks.
        assertNotNull(WorkflowScope.escalation(plan, "mcp_call", vercel, observation = true))
        assertTrue(WorkflowScope.escalation(plan, "mcp_call", github, observation = false)!!.contains("connector:github"))
        // No declared tools: no limit.
        assertNull(WorkflowScope.escalation(plan.copy(tools = emptyList(), approvals = emptyList()), "send_email", none, observation = false))
    }

    private class Probe(name: String, private val observation: Boolean = false) : Tool {
        var runs = 0
        override val risk = ToolRisk.WRITE
        override val spec = ToolSpec(name, "probe", ObjectSchema().string("x", "x", required = false).string("connector", "c", required = false).build())
        override fun describe(args: JsonObject) = "probe"
        override suspend fun isObservation(args: JsonObject) = observation
        override suspend fun execute(args: JsonObject): ToolResult = ToolResult("ran").also { runs++ }
    }

    private class Store : TranscriptStore {
        val audit = mutableListOf<AuditEntry>()
        override suspend fun append(message: Message) {}
        override suspend fun record(entry: AuditEntry) { audit += entry }
    }

    private suspend fun loop(
        tool: Probe,
        policy: ToolPolicy,
        readOnly: Boolean = false,
        asked: MutableList<ApprovalRequest> = mutableListOf(),
        args: String = "{}",
    ): Store {
        val store = Store()
        val turns = ArrayDeque(
            listOf(
                AssistantTurn("", listOf(Part.ToolCall("k", tool.spec.name, args)), StopReason.TOOL_USE, ApiFormat.OPENAI_CHAT, null),
                AssistantTurn("ok", emptyList(), StopReason.END_TURN, ApiFormat.OPENAI_CHAT, null),
            ),
        )
        val model = object : ChatModel {
            override val format = ApiFormat.OPENAI_CHAT
            override fun stream(request: ChatRequest): Flow<ChatEvent> = flow { emit(ChatEvent.Completed(turns.removeFirst())) }
        }
        var n = 0
        AgentLoop(
            model, ToolRegistry(listOf(tool)), { policy }, { r -> asked += r; true }, store,
            AgentConfig(readOnly = readOnly, escalate = { t, a -> WorkflowScope.escalation(plan, t, a) }),
            ids = { "id${n++}" },
        ).run(Conversation("c", "t", "p", "system", 0, 0), listOf(msg("u", Role.USER, 0, Part.Text("go")))).toList()
        return store
    }

    @Test
    fun `the loop asks for an allowed tool outside the workflow`() = runTest {
        val asked = mutableListOf<ApprovalRequest>()
        val store = loop(Probe("send_email"), ToolPolicy.ALLOW, asked = asked)
        assertEquals(ApprovalDecision.USER_APPROVED, store.audit.single().decision)
        assertTrue(asked.single().summary.contains("isn't one of the workflow"), asked.single().toString())
    }

    @Test
    fun `a declared tool runs as the policy says`() = runTest {
        val store = loop(Probe("run_command"), ToolPolicy.ALLOW)
        assertEquals(ApprovalDecision.AUTO_ALLOWED, store.audit.single().decision)
    }

    @Test
    fun `escalation never overrides Deny, and a background run refuses instead of asking`() = runTest {
        val denied = Probe("send_email")
        assertEquals(ApprovalDecision.POLICY_DENIED, loop(denied, ToolPolicy.DENY).audit.single().decision)
        val watched = Probe("send_email", observation = true)
        // An observation outside the list isn't escalated, so a read-only run lets it through.
        assertEquals(ApprovalDecision.AUTO_ALLOWED, loop(watched, ToolPolicy.ALLOW, readOnly = true).audit.single().decision)
        val point = Probe("mcp_call", observation = true)
        val asked = mutableListOf<ApprovalRequest>()
        assertEquals(ApprovalDecision.POLICY_DENIED, loop(point, ToolPolicy.ALLOW, readOnly = true, asked = asked, args = """{"connector":"vercel"}""").audit.single().decision)
        assertTrue(asked.isEmpty())
        assertEquals(0, denied.runs)
    }

    // ---- Checks ---------------------------------------------------------------------------------

    @Test
    fun `checks are judged from runs, changes and the reply`() {
        val checks = listOf(
            WorkflowCheck(CheckKind.TESTS_PASS, "Tests pass"),
            WorkflowCheck(CheckKind.BUILD_PASS, "Build passes"),
            WorkflowCheck(CheckKind.FILE_CHANGED, "Changelog updated", "CHANGELOG.md"),
            WorkflowCheck(CheckKind.FILE_CHANGED, "Readme updated", "README.md"),
            WorkflowCheck(CheckKind.REPLY_CONTAINS, "Gives the address", "https://a.dev"),
            WorkflowCheck(CheckKind.AGENT, "Site loads"),
            WorkflowCheck(CheckKind.AGENT, "Cache purged"),
            WorkflowCheck(CheckKind.AGENT, "Nobody said"),
        )
        val reply = "Live at https://a.dev.\n\nCheck: Site loads — passed\nCheck: cache purged — failed: no API token"
        val results = WorkflowChecks.evaluate(checks, runs, reply, listOf("docs/CHANGELOG.md")).map { it.passed }
        assertEquals(listOf(true, null, true, false, true, true, false, null), results)
        val failedRun = WorkflowChecks.evaluate(checks.take(1), runs.reversed(), reply, emptyList()).single()
        assertEquals(false, failedRun.passed)
        val why = WorkflowChecks.evaluate(listOf(checks[6]), runs, reply, emptyList()).single().note
        assertTrue("no API token" in why, why)
        assertEquals(false, WorkflowChecks.evaluate(listOf(checks[4]), runs, null, emptyList()).single().passed)
    }

    @Test
    fun `a workflow run's receipt carries its checks`() {
        val r = WorkflowRender.render(workflow, mapOf("site" to "https://a.dev"))
        val path = listOf(
            msg("u", Role.USER, 10, Part.Text(r.text), Part.Text(ModeNote.wrap(r.note))),
            msg("a1", Role.ASSISTANT, 11, call("c1", "run_command", """{"command":"./gradlew test"}""")),
            msg("t1", Role.TOOL, 12, result("c1", "run_command", "Exit code 0\nBUILD SUCCESSFUL", ApprovalDecision.AUTO_ALLOWED)),
            msg("a2", Role.ASSISTANT, 13, Part.Text("Done.\nCheck: https://a.dev loads — passed")),
        )
        val receipt = TaskReceipts.build(listOf(TaskRecord("task", "c", 10, 20, TaskOutcome.DONE)), path).single()
        val w = assertNotNull(receipt.workflow)
        assertEquals("Test and deploy", w.name)
        assertEquals(listOf(true, true), w.checks.map { it.passed })
        assertTrue("2 of 2 checks passed" in TaskReceipts.summary(receipt))
        // An ordinary task has none.
        assertNull(TaskReceipts.build(listOf(TaskRecord("task", "c", 10, 20, TaskOutcome.DONE)), path.drop(1).let { listOf(msg("u", Role.USER, 10, Part.Text("hi"))) + it }).single().workflow)
    }

    // ---- Scheduling -----------------------------------------------------------------------------

    @Test
    fun `a scheduled prompt keeps the plan and splits back`() {
        val r = WorkflowRender.render(workflow, mapOf("site" to "https://a.dev"), projectId = "p1")
        val stored = WorkflowRender.stored(r)
        val (text, note) = WorkflowRender.split(stored)
        assertEquals(r.text, text)
        assertEquals(r.note, note)
        assertEquals("p1", WorkflowRender.planInNote(note!!)?.project)
        assertEquals(r.text, WorkflowRender.visible(stored))
        // An ordinary scheduled prompt is left alone.
        assertEquals("Will it rain?" to null, WorkflowRender.split("Will it rain?"))
    }

    @Test
    fun `the schedule sheet knows what can't run unattended`() {
        val limits = WorkflowScope.unattended(workflow)
        assertEquals(listOf("connector:vercel"), limits.needYou)
        assertEquals(listOf("run_command", "edit_file"), limits.changes)
        assertFalse(limits.fullyRuns)
        assertTrue(WorkflowScope.unattended(workflow.copy(tools = listOf("read_file", "web_search", "todo_write"), approvals = emptyList())).fullyRuns)
    }

    // ---- Storage --------------------------------------------------------------------------------

    @Test
    fun `workflows round-trip through agent-home, shared and per project`() {
        val dir = Files.createTempDirectory("home").toFile()
        val store = WorkflowStore(AgentHome(dir))
        val project = "0b4e5a8e-2d0c-4a59-9a43-3f8b1c7d9e10"
        val saved = store.save(workflow, null, now = 7)
        assertEquals(7, saved.createdAt)
        assertEquals(Workflow.SCHEMA, saved.schema)
        assertEquals(saved, store.get("test-and-deploy", null))
        // The same name again gets its own file; saving over it keeps the id.
        val second = store.save(workflow, null, now = 8)
        assertEquals("test-and-deploy-2", second.id)
        assertEquals("test-and-deploy-2", store.save(second.copy(name = "Renamed"), null, replacing = "test-and-deploy-2").id)
        store.save(workflow.copy(id = "project-one"), project)
        assertTrue(dir.resolve("projects/$project/workflows/project-one.json").isFile)
        assertEquals(listOf(null, null, project), store.all().map { it.projectId })
        assertEquals(listOf(project, null, null), store.forChat(project).map { it.projectId })
        assertTrue(store.delete("test-and-deploy-2", null))
        assertEquals(2, store.all().size)
        // A file from a newer Locus with fields this one doesn't know still loads.
        dir.resolve("workflows/newer.json").writeText("""{"schema":9,"id":"newer","name":"N","goal":"g","future":{"x":1}}""")
        assertEquals("N", store.get("newer", null)?.name)
        // A file whose id doesn't match its name, or broken JSON, is skipped.
        dir.resolve("workflows/odd.json").writeText("""{"id":"other","name":"N","goal":"g"}""")
        dir.resolve("workflows/bad.json").writeText("{")
        assertFalse(store.all().any { it.workflow.id == "other" })
        assertFailsWith<IllegalArgumentException> { store.save(workflow.copy(name = " "), null) }
        assertFailsWith<IllegalArgumentException> { store.save(workflow.copy(parameters = listOf(WorkflowParam("Bad"))), null) }
    }
}
