package dev.locus.core.agent

import dev.locus.core.agent.tools.agentControlTools
import dev.locus.core.llm.AssistantTurn
import dev.locus.core.llm.ChatEvent
import dev.locus.core.llm.ChatModel
import dev.locus.core.llm.ChatRequest
import dev.locus.core.llm.StopReason
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.ApprovalDecision
import dev.locus.core.model.AuditEntry
import dev.locus.core.model.ChatScopeSpec
import dev.locus.core.model.Conversation
import dev.locus.core.model.MemoryScope
import dev.locus.core.model.Message
import dev.locus.core.model.ModeNote
import dev.locus.core.model.Part
import dev.locus.core.model.ProjectRule
import dev.locus.core.model.ProjectRules
import dev.locus.core.model.Role
import dev.locus.core.model.RuleNote
import dev.locus.core.model.RuleTrigger
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
 * Project rules in the agent loop (docs/design/rules.md): each trigger fires exactly when it should, a block
 * refuses before the command runs, notes are bounded and appended (cache-safe), no Linux means no runs, and
 * every run is audited.
 */
class ProjectRulesTest {
    private val conversation = Conversation("c", "t", "p", "system", 0, 0)
    private val ask = Message("u", "c", Role.USER, listOf(Part.Text("fix the parser")), 0)
    private val scope = ChatScopeSpec("proj", "projects/app", MemoryScope.PROJECT_AND_GLOBAL)

    private class ScriptedModel(vararg turns: AssistantTurn) : ChatModel {
        val queue = ArrayDeque(turns.toList())
        val requests = mutableListOf<ChatRequest>()
        override val format = ApiFormat.OPENAI_CHAT
        override fun stream(request: ChatRequest): Flow<ChatEvent> = flow {
            requests += request.copy(messages = request.messages.toList())
            emit(ChatEvent.Completed(queue.removeFirst()))
        }
    }

    private class FakeShell : Tool {
        val ran = mutableListOf<String>()
        override val risk = ToolRisk.EXECUTE
        override val spec = ToolSpec("run_command", "test", ObjectSchema().string("command", "c").build())
        override fun describe(args: JsonObject) = "run ${args.str("command")}"
        override suspend fun execute(args: JsonObject): ToolResult {
            ran += args.str("command").orEmpty()
            return ToolResult("Exit code 0 (0.1 s).\nok")
        }
    }

    /** edit_file that fails for paths starting with "bad". */
    private class FakeEdit(name: String = "edit_file") : Tool {
        override val risk = ToolRisk.WRITE
        override val spec = ToolSpec(name, "test", ObjectSchema().string("path", "p").build())
        override fun describe(args: JsonObject) = "edit ${args.str("path")}"
        override suspend fun execute(args: JsonObject) =
            if (args.str("path").orEmpty().startsWith("bad")) ToolResult.error("No such text in the file.") else ToolResult("Edited.")
    }

    private class FakeHooks(var rules: List<ProjectRule>, var ready: Boolean = true, val exits: ArrayDeque<Int?> = ArrayDeque(), val output: String = "out") : ProjectRuleHooks {
        val runs = mutableListOf<Pair<ProjectRule, List<String>>>()
        var scopeSeen: ChatScopeSpec? = null
        override suspend fun rules() = rules.filter { it.active }
        override suspend fun canRun() = ready
        override suspend fun run(rule: ProjectRule, files: List<String>): RuleOutcome {
            runs += rule to files
            scopeSeen = ChatScope.current()
            return RuleOutcome(exits.removeFirstOrNull() ?: 0, output, 250)
        }
    }

    private class MemoryStore : TranscriptStore {
        val messages = mutableListOf<Message>()
        val audit = mutableListOf<AuditEntry>()
        override suspend fun append(message: Message) { messages += message }
        override suspend fun record(entry: AuditEntry) { audit += entry }
    }

    private var n = 0
    private fun calls(vararg calls: Pair<String, String>) =
        AssistantTurn("", calls.map { (name, args) -> Part.ToolCall("call${n++}", name, args) }, StopReason.TOOL_USE, ApiFormat.OPENAI_CHAT, null)

    private fun run(cmd: String) = "run_command" to """{"command":"$cmd"}"""
    private fun edit(path: String, tool: String = "edit_file") = tool to """{"path":"$path"}"""
    private fun done(text: String = "All done.") = AssistantTurn(text, emptyList(), StopReason.END_TURN, ApiFormat.OPENAI_CHAT, null)

    private val lint = ProjectRule("l", "ruff", RuleTrigger.AFTER_EDIT, pattern = "*.py", command = "ruff check .")
    private val tests = ProjectRule("t", "tests", RuleTrigger.BEFORE_FINISH, command = "npm test")
    private val noPush = ProjectRule("b", "No push to main", RuleTrigger.BEFORE_COMMAND, pattern = "git push*main", message = "never git push to main")

    private var ids = 0
    private fun loop(
        model: ChatModel,
        store: TranscriptStore,
        hooks: ProjectRuleHooks?,
        shell: FakeShell = FakeShell(),
        config: (ProjectRuleHooks?) -> AgentConfig = { AgentConfig(rules = it, scope = scope) },
        policy: ToolPolicy = ToolPolicy.ALLOW,
    ) = AgentLoop(
        model, ToolRegistry(agentControlTools() + shell + FakeEdit() + FakeEdit("write_file")), { policy }, ApprovalGate { false }, store, config(hooks),
        ids = { "id${ids++}" },
    )

    private fun ruleNotes(store: MemoryStore) = store.messages.filter { RuleNote.runs(it).isNotEmpty() }

    // ---- After an edit.

    @Test
    fun `after a successful edit to a matching file the rule runs once, with the files, and its note follows the results`() = runTest {
        val hooks = FakeHooks(listOf(lint), exits = ArrayDeque(listOf(1)))
        val store = MemoryStore()
        val model = ScriptedModel(calls(edit("app/a.py"), edit("/workspace/b.py"), edit("README.md")), done())
        loop(model, store, hooks).run(conversation, listOf(ask)).toList()

        assertEquals(listOf(lint to listOf("app/a.py", "b.py")), hooks.runs)
        assertEquals(scope, hooks.scopeSeen, "runs in the project's scope, so Linux mounts the project's folder")
        val note = ruleNotes(store).single()
        // Appended right after the step's tool results, as a note from Locus.
        assertEquals(Role.TOOL, store.messages[store.messages.indexOf(note) - 1].role)
        assertTrue(ModeNote.isAgentNote(note))
        val (info, output) = RuleNote.runs(note).single()
        assertEquals("ruff", info.name)
        assertEquals(1, info.exitCode)
        assertTrue("app/a.py, b.py" in note.parts.first().let { (it as Part.Text).text })
        assertTrue(output!!.contains(UntrustedContent.OPEN) && RuleNote.LOCAL_OUTPUT in output)
        // The model saw it in its next request.
        assertEquals(note.id, model.requests.last().messages.last().id)
    }

    @Test
    fun `failed, denied or unmatched edits don't fire after-edit rules`() = runTest {
        val hooks = FakeHooks(listOf(lint))
        val store = MemoryStore()
        loop(ScriptedModel(calls(edit("bad.py"), edit("notes.txt")), done()), store, hooks).run(conversation, listOf(ask)).toList()
        assertTrue(hooks.runs.isEmpty())
        assertTrue(ruleNotes(store).isEmpty())

        // Denied: the user refused the edit (Ask, and the gate says no).
        val denied = MemoryStore()
        loop(ScriptedModel(calls(edit("a.py")), done()), denied, hooks, policy = ToolPolicy.ASK).run(conversation, listOf(ask)).toList()
        assertTrue(hooks.runs.isEmpty())
    }

    @Test
    fun `write_file fires it too, and a call made through call_tool counts as the tool it ran`() = runTest {
        val hooks = FakeHooks(listOf(lint))
        loop(ScriptedModel(calls(edit("w.py", tool = "write_file")), done()), MemoryStore(), hooks).run(conversation, listOf(ask)).toList()
        assertEquals(listOf("w.py"), hooks.runs.single().second)
    }

    @Test
    fun `off and suspended rules never fire`() = runTest {
        val hooks = FakeHooks(listOf(lint.copy(enabled = false), tests.copy(suspended = true)))
        val store = MemoryStore()
        loop(ScriptedModel(calls(edit("a.py")), done()), store, hooks).run(conversation, listOf(ask)).toList()
        assertTrue(hooks.runs.isEmpty())
        assertTrue(ruleNotes(store).isEmpty())
    }

    @Test
    fun `without Linux, command rules are inactive and nothing is said`() = runTest {
        val hooks = FakeHooks(listOf(lint, tests), ready = false)
        val store = MemoryStore()
        val model = ScriptedModel(calls(edit("a.py")), done())
        loop(model, store, hooks).run(conversation, listOf(ask)).toList()
        assertTrue(hooks.runs.isEmpty())
        assertTrue(ruleNotes(store).isEmpty())
        assertTrue(store.audit.none { it.toolName == ProjectRules.ENTRY })
        assertEquals(2, model.requests.size)
    }

    // ---- Before finishing.

    @Test
    fun `a failing before-finishing rule sends the agent back at most twice per task`() = runTest {
        val hooks = FakeHooks(listOf(tests), exits = ArrayDeque(listOf(1, 1, 1, 1)))
        val store = MemoryStore()
        val model = ScriptedModel(calls(edit("a.txt")), done("Done."), done("Fixed it."), done("Fixed it again."), done("never asked"))
        loop(model, store, hooks).run(conversation, listOf(ask)).toList()
        assertEquals(3, hooks.runs.size, "the first finish, then after each of the two extra rounds")
        assertEquals(4, model.requests.size, "the edit, the first finish and two extra rounds; never a fifth")
        val notes = ruleNotes(store)
        assertEquals(3, notes.size)
        assertFalse(RuleNote.runs(notes[0]).single().first.finalRound)
        assertFalse(RuleNote.runs(notes[1]).single().first.finalRound)
        assertTrue(RuleNote.runs(notes[2]).single().first.finalRound)
        assertEquals(3, RuleNote.finishRounds(store.messages))
        // The transcript ends on the final note: the run ended, it didn't loop.
        assertEquals(notes[2].id, store.messages.last().id)

        // Another run of the same task doesn't start over.
        val again = MemoryStore()
        val more = ScriptedModel(done("Still done."))
        loop(more, again, hooks).run(conversation, listOf(ask) + store.messages).toList()
        assertEquals(3, hooks.runs.size)
    }

    @Test
    fun `a rule that failed and then passes gets a short note, and the run ends`() = runTest {
        val hooks = FakeHooks(listOf(tests), exits = ArrayDeque(listOf(1, 0)))
        val store = MemoryStore()
        val model = ScriptedModel(calls(edit("a.txt")), done(), done("Fixed."))
        loop(model, store, hooks).run(conversation, listOf(ask)).toList()
        assertEquals(3, model.requests.size)
        val last = RuleNote.runs(ruleNotes(store).last()).single().first
        assertTrue(last.nowPasses && last.passed)
    }

    @Test
    fun `a passing rule adds nothing, and a task that did nothing doesn't run it`() = runTest {
        val hooks = FakeHooks(listOf(tests))
        val store = MemoryStore()
        loop(ScriptedModel(calls(edit("a.txt")), done()), store, hooks).run(conversation, listOf(ask)).toList()
        assertEquals(1, hooks.runs.size)
        assertTrue(ruleNotes(store).isEmpty())

        val idle = FakeHooks(listOf(tests))
        loop(ScriptedModel(done("Just an answer.")), MemoryStore(), idle).run(conversation, listOf(ask)).toList()
        assertTrue(idle.runs.isEmpty())
    }

    @Test
    fun `before-finishing rules skip helpers, read-only runs, Read-only mode and the last allowed step`() = runTest {
        for (config in listOf<(ProjectRuleHooks?) -> AgentConfig>(
            { AgentConfig(rules = it, depth = 1) },
            { AgentConfig(rules = it, chatReadOnly = { true }) },
            { AgentConfig(rules = it, maxSteps = 2) },
        )) {
            val hooks = FakeHooks(listOf(tests), exits = ArrayDeque(listOf(1)))
            loop(ScriptedModel(calls(edit("a.txt")), done()), MemoryStore(), hooks, config = config).run(conversation, listOf(ask)).toList()
            assertTrue(hooks.runs.isEmpty())
        }
        val readOnly = FakeHooks(listOf(tests))
        loop(ScriptedModel(calls(run("ls")), done()), MemoryStore(), readOnly, config = { AgentConfig(rules = it, readOnly = true) }).run(conversation, listOf(ask)).toList()
        assertTrue(readOnly.runs.isEmpty())
    }

    @Test
    fun `rules come before the completion check, which still gets its turn`() = runTest {
        val hooks = FakeHooks(listOf(tests), exits = ArrayDeque(listOf(1, 0)))
        val store = MemoryStore()
        val model = ScriptedModel(calls(run("pytest")), calls(edit("a.py")), done(), done("Fixed."), done("Unverified: I didn't rerun pytest."))
        loop(model, store, hooks, config = { AgentConfig(rules = it, completionCheck = true) }).run(conversation, listOf(ask)).toList()
        assertEquals(1, store.messages.count { CompletionCheck.openItemsIn(it) != null })
        assertEquals(5, model.requests.size)
    }

    // ---- Before a command: block.

    @Test
    fun `a block rule refuses the call with its message and the command never runs`() = runTest {
        val hooks = FakeHooks(listOf(noPush))
        val shell = FakeShell()
        val store = MemoryStore()
        loop(ScriptedModel(calls(run("cd app && git push origin main"), run("git status")), done()), store, hooks, shell).run(conversation, listOf(ask)).toList()
        assertEquals(listOf("git status"), shell.ran)
        val refused = store.messages.first { it.role == Role.TOOL }.toolResults.first()
        assertTrue(refused.isError)
        assertEquals(ApprovalDecision.POLICY_DENIED, refused.decision)
        assertTrue("No push to main" in refused.content && "never git push to main" in refused.content)
        assertTrue(store.audit.any { it.toolName == "run_command" && it.decision == ApprovalDecision.POLICY_DENIED })
    }

    @Test
    fun `block rules need no Linux, and a call through call_tool is checked as the tool it runs`() = runTest {
        val hooks = FakeHooks(listOf(noPush), ready = false)
        val shell = FakeShell()
        val call = "call_tool" to """{"name":"run_command","arguments":{"command":"git push origin main"}}"""
        loop(ScriptedModel(calls(call), done()), MemoryStore(), hooks, shell).run(conversation, listOf(ask)).toList()
        assertTrue(shell.ran.isEmpty())
    }

    @Test
    fun `a helper inherits its parent's block rules`() = runTest {
        val hooks = FakeHooks(listOf(noPush))
        val shell = FakeShell()
        val parent = RunScope(rules = hooks)
        loop(ScriptedModel(calls(run("git push origin main")), done()), MemoryStore(), null, shell, config = { AgentConfig(inherited = parent, depth = 1) })
            .run(conversation, listOf(ask)).toList()
        assertTrue(shell.ran.isEmpty())
    }

    // ---- Cache safety, bounds, audit, taint.

    @Test
    fun `each request before a rule note is a byte-identical prefix of the next`() = runTest {
        val hooks = FakeHooks(listOf(lint, tests), exits = ArrayDeque(listOf(1, 1, 0)))
        val model = ScriptedModel(calls(edit("a.py")), done(), done("Fixed."))
        loop(model, MemoryStore(), hooks).run(conversation, listOf(ask)).toList()
        assertEquals(3, model.requests.size)
        model.requests.zipWithNext().forEach { (a, b) ->
            assertEquals(a.system, b.system)
            assertEquals(a.tools, b.tools)
            assertEquals(a.messages, b.messages.take(a.messages.size))
        }
    }

    @Test
    fun `a long output is cut to its start and end`() = runTest {
        val long = "HEAD" + "x".repeat(20_000) + "TAIL-ERROR"
        val hooks = FakeHooks(listOf(lint), output = long)
        val store = MemoryStore()
        loop(ScriptedModel(calls(edit("a.py")), done()), store, hooks).run(conversation, listOf(ask)).toList()
        val output = RuleNote.runs(ruleNotes(store).single()).single().second!!
        assertTrue(output.length < RuleHooks.MAX_OUTPUT + 1_000)
        assertTrue("HEAD" in output && "TAIL-ERROR" in output && "characters cut" in output)
    }

    @Test
    fun `a long rule output counts toward compaction, which runs before the next request instead of an overflow`() = runTest {
        val hooks = FakeHooks(listOf(lint), exits = ArrayDeque(listOf(1)), output = "E501 line too long\n".repeat(400))
        val store = MemoryStore()
        val step = calls(edit("a.py")).copy(usage = dev.locus.core.model.TokenUsage(1_000, 50))
        val summary = AssistantTurn("Fixed the parser in a.py; ruff still reports long lines to wrap.", emptyList(), StopReason.END_TURN, ApiFormat.OPENAI_CHAT, null)
        val model = ScriptedModel(step, summary, done())
        // A window of 2,000: due past 1,600. The step's own result is a few tokens; the rule's note is about 1,000.
        val events = loop(model, store, hooks, config = { AgentConfig(rules = it, scope = scope, compaction = CompactionSetup(2_000, prompt = "Summarise the chat.")) })
            .run(conversation, listOf(ask)).toList()

        assertEquals("Summarise the chat.", model.requests[1].messages.last().text, "the request after the step is the summary")
        assertTrue(events.any { it is AgentEvent.Compacted })
        assertTrue(model.requests[2].messages.first().text.startsWith(Compaction.HEADER))
    }

    @Test
    fun `every run is audited with its rule, trigger, command and files`() = runTest {
        val hooks = FakeHooks(listOf(lint), exits = ArrayDeque(listOf(2)))
        val store = MemoryStore()
        loop(ScriptedModel(calls(edit("a.py")), done()), store, hooks).run(conversation, listOf(ask)).toList()
        val entry = store.audit.single { it.toolName == ProjectRules.ENTRY }
        assertTrue(entry.isError)
        assertEquals(ApprovalDecision.AUTO_ALLOWED, entry.decision)
        assertTrue("\"rule\":\"ruff\"" in entry.argumentsJson && "\"trigger\":\"after_edit\"" in entry.argumentsJson && "a.py" in entry.argumentsJson)
        assertTrue(entry.resultPreview.startsWith("Exit code 2"))
    }

    @Test
    fun `rule output taints like run_command output, local for remembered approvals only when the command fetches nothing`() {
        val local = RuleRun(lint, listOf("a.py"), RuleOutcome(0, "ok", 1), RuleNote.header(lint, 0, 1, listOf("a.py")))
        val fetching = lint.copy(name = "deps", command = "npm install && npm test")
        val remote = RuleRun(fetching, emptyList(), RuleOutcome(1, "err", 1), RuleNote.header(fetching, 1, 1, emptyList()))
        val history = listOf(ask, Message("n", "c", Role.USER, local.parts + remote.parts, 0))
        assertEquals(setOf(RuleHooks.taintLabel("ruff"), RuleHooks.taintLabel("deps")), Taint.sourcesIn(history))
        assertEquals(setOf(RuleHooks.taintLabel("deps")), Taint.sourcesIn(history, skipLocal = true))
        // Matches how a plain local run_command is classified.
        assertTrue(local.local == Taint.isLocalOutput("run_command", kotlinx.serialization.json.buildJsonObject { put("command", kotlinx.serialization.json.JsonPrimitive(lint.command)) }))
    }

    @Test
    fun `no tool can create, change or delete rules`() {
        val registry = ToolRegistry(agentControlTools())
        assertTrue(registry.specs.none { "rule" in it.name.lowercase() })
    }
}
