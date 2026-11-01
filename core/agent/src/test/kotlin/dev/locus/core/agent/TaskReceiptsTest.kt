package dev.locus.core.agent

import dev.locus.core.model.ApprovalDecision
import dev.locus.core.model.AuditEntry
import dev.locus.core.model.Message
import dev.locus.core.model.ModelPrice
import dev.locus.core.model.Part
import dev.locus.core.model.ReceiptModel
import dev.locus.core.model.ReplyInfo
import dev.locus.core.model.Role
import dev.locus.core.model.TASK_BUDGET_ENTRY
import dev.locus.core.model.TaskOutcome
import dev.locus.core.model.TaskReceipt
import dev.locus.core.model.TaskRecord
import dev.locus.core.model.TokenUsage
import dev.locus.core.model.UsageSource
import dev.locus.core.model.Verification
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Task receipts from fixtures: every field, clean and tainted runs, fallbacks, a budget stop and a failure. */
class TaskReceiptsTest {
    private val start = 1_000_000L
    private val end = start + 252_000 // 4m 12s

    private fun msg(id: String, role: Role, at: Long, vararg parts: Part, usage: TokenUsage? = null, reply: ReplyInfo? = null) =
        Message(id, "c", role, parts.toList(), start + at, usage = usage, reply = reply)

    private fun audit(id: String, decision: ApprovalDecision, at: Long, chat: String = "c", tool: String = "write_file") =
        AuditEntry(id, chat, "call-$id", tool, "{}", decision, "", decision != ApprovalDecision.USER_APPROVED, start + at, start + at)

    private val prices = mapOf(
        "claude-x" to ModelPrice(input = 3.0, output = 15.0, cacheRead = 0.3),
        "gpt-5" to ModelPrice(input = 1.25, output = 10.0, cacheRead = 0.125),
    )

    /** A task that tested, wrote two files, asked twice and was blocked twice; a backup model wrote the reply. */
    private val path = listOf(
        msg("u1", Role.USER, 0, Part.Text("Fix the tests")),
        msg(
            "a1", Role.ASSISTANT, 60_000,
            Part.ToolCall("c1", "run_command", """{"command":"./gradlew test"}"""),
            Part.ToolCall("c2", "write_file", """{"path":"a.kt","content":"x"}"""),
            Part.ToolCall("c3", "agent_spawn", """{"type":"researcher","task":"look"}"""),
            usage = TokenUsage(input = 100_000, output = 1_000, cacheRead = 60_000),
            reply = ReplyInfo("p", "claude-x"),
        ),
        msg(
            "t1", Role.TOOL, 120_000,
            Part.ToolResult("c1", "run_command", "Exit code 0 (3.2 s)\nBUILD SUCCESSFUL"),
            Part.ToolResult("c2", "write_file", "Wrote a.kt"),
            Part.ToolResult("c3", "agent_spawn", "<subagent_report agent=\"researcher\">found it</subagent_report>"),
        ),
        msg(
            "a2", Role.ASSISTANT, 240_000, Part.Text("Fixed."),
            usage = TokenUsage(input = 120_000, output = 500, cacheRead = 100_000),
            reply = ReplyInfo("p", "gpt-5", fallbackFrom = "claude-x (Anthropic)"),
        ),
    )

    private val record = TaskRecord("t", "c", start, end, TaskOutcome.DONE)

    private val decisions = listOf(
        audit("ok", ApprovalDecision.USER_APPROVED, 61_000),
        audit("no", ApprovalDecision.USER_DENIED, 62_000),
        audit("policy", ApprovalDecision.POLICY_DENIED, 63_000),
        audit("reviewer", ApprovalDecision.REVIEWER_DENIED, 64_000),
        // A budget pause's Continue isn't an approval, and nothing outside the task counts.
        audit("budget", ApprovalDecision.USER_APPROVED, 65_000, tool = TASK_BUDGET_ENTRY),
        audit("later", ApprovalDecision.USER_APPROVED, 900_000),
        audit("other-chat", ApprovalDecision.USER_APPROVED, 61_000, chat = "other"),
        audit("auto", ApprovalDecision.AUTO_ALLOWED, 61_500),
    )

    private fun build(
        records: List<TaskRecord> = listOf(record),
        messages: List<Message> = path,
        audit: List<AuditEntry> = decisions,
        changes: Map<String, List<String>> = mapOf("a1" to listOf("a.kt", "b.kt")),
        helpers: List<TaskReceipts.Helper> = emptyList(),
        side: List<TaskReceipts.Usage> = emptyList(),
        price: (String?, String?) -> ModelPrice? = { _, model -> prices[model] },
    ): List<TaskReceipt> = TaskReceipts.build(records, messages, audit, changes, helpers, side, price)


    @Test
    fun `every field of a finished task`() {
        val r = build().single()
        assertEquals(TaskOutcome.DONE, r.outcome)
        assertEquals("4m 12s", TaskReceipts.duration(r))
        assertEquals(2, r.steps)
        assertEquals("a2", r.afterMessageId, "the receipt goes under the task's last message")
        assertEquals(220_000, r.tokens.input)
        assertEquals(1_500, r.tokens.output)
        assertEquals(72, r.tokens.cachedPercent)
        assertEquals("220k in · 1.5k out · 72% cached", TaskReceipts.tokensLabel(r))
        // claude-x: 40k fresh × $3 + 60k cached × $0.30 + 1k out × $15; gpt-5: 20k × $1.25 + 100k × $0.125 + 500 × $10.
        assertEquals(0.153 + 0.0425, r.cost!!.usd, 1e-9)
        assertFalse(r.cost!!.partial)
        assertEquals("≈$0.20", TaskReceipts.costLabel(r))
        assertEquals(2, r.files)
        assertEquals(listOf("a1"), r.changeTurns)
        assertEquals(1, r.checks.size)
        assertEquals(Verification.Kind.TEST, r.checks.single().kind)
        assertEquals("a1" to "c1", r.checks.single().messageId to r.checks.single().callId)
        assertEquals("tests passed", TaskReceipts.checksLabel(r))
        assertEquals(1, r.approvals.approved)
        assertEquals(1, r.approvals.declined)
        assertEquals(2, r.approvals.asked)
        assertEquals(2, r.approvals.blocked)
        assertEquals("2 asked · 1 you approved · 1 you declined · 2 blocked", TaskReceipts.approvalsDetail(r))
        assertEquals("Done · 4m 12s · 2 files · tests passed · 2 approvals · 2 blocked · ≈$0.20", TaskReceipts.summary(r))
    }

    @Test
    fun `undone files and other turns' changes aren't counted`() {
        val r = build(changes = mapOf("a1" to listOf("a.kt"), "elsewhere" to listOf("z.kt"))).single()
        assertEquals(1, r.files)
        assertEquals("Done · 4m 12s · 1 file · tests passed · 2 approvals · 2 blocked · ≈$0.20", TaskReceipts.summary(r))
        val none = build(changes = emptyMap()).single()
        assertEquals(0, none.files)
        assertTrue(none.changeTurns.isEmpty())
    }

    @Test
    fun `a backup model is listed as standing in for the chat's own`() {
        val r = build().single()
        assertEquals(listOf(ReceiptModel("claude-x"), ReceiptModel("gpt-5", ReceiptModel.Kind.BACKUP, "claude-x (Anthropic)")), r.models)
        assertEquals("claude-x · gpt-5 (backup for claude-x (Anthropic))", TaskReceipts.modelsLabel(r))
    }

    @Test
    fun `a clean run has no outside content`() {
        val clean = path.map { m ->
            if (m.role != Role.TOOL) m else m.copy(parts = m.parts.filterNot { it is Part.ToolResult && it.toolName != "write_file" })
        }.map { m -> if (m.id == "a1") m.copy(parts = m.parts.filterNot { it is Part.ToolCall && it.name != "write_file" }) else m }
        val r = build(messages = clean).single()
        assertFalse(r.tainted)
        assertFalse(r.taintedFromStart)
        assertTrue(r.outsideContent.isEmpty())
        assertNull(TaskReceipts.checksLabel(r))
        assertFalse("Outside content" in TaskReceipts.exportText(r))
    }

    @Test
    fun `a tainted run lists what it read, and what it started with`() {
        val r = build().single()
        // Command output and the helper's report, in plain words.
        assertEquals(listOf("command output", "helpers' reports"), r.outsideContent)
        assertFalse(r.taintedFromStart)

        val earlier = listOf(
            Message("u0", "c", Role.USER, listOf(Part.Text("Read this page")), start - 10_000),
            Message("a0", "c", Role.ASSISTANT, listOf(Part.ToolCall("w", "fetch_url", """{"url":"https://example.com"}""")), start - 9_000),
            Message("t0", "c", Role.TOOL, listOf(Part.ToolResult("w", "fetch_url", "page")), start - 8_000),
        )
        val inherited = build(messages = earlier + path).single()
        assertTrue(inherited.taintedFromStart)
        assertEquals(listOf("command output", "helpers' reports", "web pages"), inherited.outsideContent)
        assertEquals("a2", inherited.afterMessageId, "earlier messages belong to no task here")
    }

    @Test
    fun `stopped at the budget`() {
        val budget = TaskRecord("t", "c", start, end, TaskOutcome.BUDGET, "Stopped: reached the 200k-token budget for this task.")
        val r = build(records = listOf(budget)).single()
        assertEquals("Paused at budget", TaskReceipts.outcomeLabel(r))
        assertEquals("Paused at budget: reached the 200k-token budget for this task", TaskReceipts.outcomeLine(r))
        assertTrue(TaskReceipts.summary(r).startsWith("Paused at budget · 4m 12s"))
    }

    @Test
    fun `a failure says why, under the user's message when nothing else was written`() {
        val failed = TaskRecord("t", "c", start, start + 2_000, TaskOutcome.FAILED, "Quota exceeded for this key.\n\nCheck your plan.")
        val r = build(records = listOf(failed), messages = path.take(1)).single()
        assertEquals("u1", r.afterMessageId)
        assertEquals("Failed: Quota exceeded for this key", TaskReceipts.outcomeLine(r))
        assertEquals(0, r.steps)
        assertNull(TaskReceipts.costLabel(r), "no request, so no cost to state")
        assertEquals("Failed · 2s", TaskReceipts.summary(r))
    }

    @Test
    fun `stopped by the user, or by a scheduled run's limit`() {
        val byUser = build(records = listOf(record.copy(outcome = TaskOutcome.STOPPED))).single()
        assertEquals("Stopped by you", TaskReceipts.outcomeLabel(byUser))
        val limit = build(records = listOf(record.copy(outcome = TaskOutcome.STOPPED, detail = "it reached the time limit for a scheduled run"))).single()
        assertEquals("Stopped: it reached the time limit for a scheduled run", TaskReceipts.outcomeLine(limit))
    }

    @Test
    fun `cost is unknown without prices, and a floor when some are missing`() {
        val unknown = build(price = { _, _ -> null }).single()
        assertNull(unknown.cost)
        assertEquals("cost unknown", TaskReceipts.costLabel(unknown))
        assertTrue(TaskReceipts.summary(unknown).endsWith("· cost unknown"))

        val partial = build(price = { _, model -> prices[model]?.takeIf { model == "claude-x" } }).single()
        assertTrue(partial.cost!!.partial)
        assertEquals("≈$0.15+", TaskReceipts.costLabel(partial))
    }

    @Test
    fun `helpers' requests, decisions and models count toward their parent's task`() {
        val helper = TaskReceipts.Helper(
            "h", parentCallId = "c3", label = "researcher",
            requests = listOf(
                TaskReceipts.Usage("p", "claude-x", TokenUsage(input = 10_000, output = 2_000), start + 100_000),
                // After the task ended (a background helper): not this task's.
                TaskReceipts.Usage("p", "claude-x", TokenUsage(input = 99_000, output = 9_000), end + 1),
            ),
        )
        val stranger = TaskReceipts.Helper("s", parentCallId = "elsewhere", label = "coder", requests = listOf(TaskReceipts.Usage("p", "x", TokenUsage(5, 5), start + 1)))
        val r = build(
            helpers = listOf(helper, stranger),
            audit = decisions + audit("helper-ok", ApprovalDecision.USER_APPROVED, 100_000, chat = "h") + audit("stranger", ApprovalDecision.USER_APPROVED, 100_000, chat = "s"),
        ).single()
        assertEquals(listOf("researcher"), r.helpers)
        assertEquals(230_000, r.tokens.input)
        assertEquals(3_500, r.tokens.output)
        assertEquals(2, r.approvals.approved)
        assertTrue(ReceiptModel("claude-x", ReceiptModel.Kind.HELPER) in r.models)
        assertEquals(2, r.steps, "a helper's requests aren't the chat's steps")
    }

    @Test
    fun `side requests in the task's time count, with their model's role`() {
        val side = listOf(
            TaskReceipts.Usage("p", "haiku", TokenUsage(1_000, 100), start + 50_000, UsageSource.REVIEWER),
            TaskReceipts.Usage("p", "claude-x", TokenUsage(5_000, 400), start + 70_000, UsageSource.COMPACTION),
            // A title made after the task: not part of it.
            TaskReceipts.Usage("p", "haiku", TokenUsage(800, 10), end + 5_000, UsageSource.TITLE),
        )
        val r = build(side = side).single()
        assertEquals(226_000, r.tokens.input)
        assertEquals(4, r.tokens.requests)
        assertTrue(ReceiptModel("haiku", ReceiptModel.Kind.REVIEWER) in r.models)
        assertTrue(ReceiptModel("claude-x", ReceiptModel.Kind.SUMMARY) in r.models)
        assertTrue(r.cost!!.partial, "the reviewer's model has no price")
    }

    @Test
    fun `a failing check wins over passing ones`() {
        val failing = path + listOf(
            msg("a3", Role.ASSISTANT, 241_000, Part.ToolCall("c9", "run_command", """{"command":"pytest"}""")),
            msg("t3", Role.TOOL, 242_000, Part.ToolResult("c9", "run_command", "Exit code 1 (2.0 s)\nFAILED tests/test_x.py::test_a - assert", isError = true)),
        )
        val r = build(messages = failing).single()
        assertEquals("tests failed", TaskReceipts.checksLabel(r))
        assertEquals("1 passed · 1 failed", TaskReceipts.checksDetail(r))
        assertEquals("t3", r.afterMessageId)
    }

    @Test
    fun `running tasks and tasks on another branch have no receipt`() {
        assertTrue(build(records = listOf(record.copy(endedAt = null, outcome = null))).isEmpty())
        val elsewhere = TaskRecord("t2", "c", start + 500_000, start + 600_000, TaskOutcome.DONE)
        assertEquals(listOf("t"), build(records = listOf(elsewhere, record)).map { it.taskId })
    }

    @Test
    fun `two tasks split the chat by time`() {
        val second = listOf(
            msg("u2", Role.USER, 300_000, Part.Text("And now docs")),
            msg("a4", Role.ASSISTANT, 320_000, Part.Text("Done."), usage = TokenUsage(1_000, 10), reply = ReplyInfo("p", "claude-x")),
        )
        val receipts = build(
            records = listOf(record, TaskRecord("t2", "c", start + 299_000, start + 330_000, TaskOutcome.DONE)),
            messages = path + second,
        )
        assertEquals(listOf("a2", "a4"), receipts.map { it.afterMessageId })
        assertEquals(1, receipts[1].steps)
        assertEquals(0, receipts[1].approvals.asked)
        assertEquals("Done · 31s · <$0.01", TaskReceipts.summary(receipts[1]))
    }

    @Test
    fun `the export block names each field that has something to say`() {
        val text = TaskReceipts.exportText(build().single())
        assertEquals(
            """
            _Task receipt: Done · 4m 12s · 2 files · tests passed · 2 approvals · 2 blocked · ≈$0.20_
            - Steps: 2
            - Models: claude-x · gpt-5 (backup for claude-x (Anthropic))
            - Tokens: 220k in · 1.5k out · 72% cached
            - Cost: ≈$0.20 (estimate from models.dev prices)
            - Files changed: 2
            - Checks: 1 passed
            - Approvals: 2 asked · 1 you approved · 1 you declined · 2 blocked
            - Outside content read: command output, helpers' reports
            """.trimIndent(),
            text,
        )
    }

    @Test
    fun `the notification line is the one-line summary`() {
        val r = build().single()
        assertEquals(TaskReceipts.summary(r), TaskReceipts.notificationLine(r))
    }
}
