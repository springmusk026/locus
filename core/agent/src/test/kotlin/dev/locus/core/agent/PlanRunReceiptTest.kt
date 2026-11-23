package dev.locus.core.agent

import dev.locus.core.model.AgentControlTools
import dev.locus.core.model.Message
import dev.locus.core.model.ModelPrice
import dev.locus.core.model.ModeNote
import dev.locus.core.model.Part
import dev.locus.core.model.ReceiptCost
import dev.locus.core.model.ReplyInfo
import dev.locus.core.model.Role
import dev.locus.core.model.TaskOutcome
import dev.locus.core.model.TaskRecord
import dev.locus.core.model.TokenUsage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Plan mode's "Run with" (docs/design/plan-run.md): the receipt of the task that carried out an approved plan shows
 * what planning cost on its model too, and both together.
 */
class PlanRunReceiptTest {
    private fun msg(id: String, role: Role, at: Long, vararg parts: Part, usage: TokenUsage? = null, reply: ReplyInfo? = null) =
        Message(id, "c", role, parts.toList(), at, usage = usage, reply = reply)

    private val prices = mapOf(
        "claude-opus-5-5" to ModelPrice(input = 5.0, output = 25.0),
        "claude-haiku-5-5" to ModelPrice(input = 1.0, output = 5.0),
    )

    private val planUsage = TokenUsage(input = 10_000, output = 1_000)
    private val runUsage = TokenUsage(input = 20_000, output = 2_000)

    /** Plan mode: the user asks, opus proposes a plan; the user approves; haiku carries it out. */
    private fun path(approval: String = AgentControlTools.PLAN_APPROVED) = listOf(
        msg("u1", Role.USER, 1_000, Part.Text("Tidy my files"), Part.Text(ModeNote.wrap(ModeNote.PLAN_MODE))),
        msg(
            "a1", Role.ASSISTANT, 2_000, Part.ToolCall("p1", AgentControlTools.PLAN_PROPOSE, """{"title":"Tidy","steps":["List","Rename"]}"""),
            usage = planUsage, reply = ReplyInfo("anthropic", "claude-opus-5-5"),
        ),
        msg("t1", Role.TOOL, 2_100, Part.ToolResult("p1", AgentControlTools.PLAN_PROPOSE, "Shown to the user.")),
        msg("u2", Role.USER, 10_000, Part.Text(approval)),
        msg("a2", Role.ASSISTANT, 11_000, Part.Text("Renamed everything."), usage = runUsage, reply = ReplyInfo("anthropic", "claude-haiku-5-5")),
    )

    private val records = listOf(
        TaskRecord("plan", "c", 1_000, 3_000, TaskOutcome.DONE),
        TaskRecord("run", "c", 10_000, 12_000, TaskOutcome.DONE),
    )

    private fun build(messages: List<Message> = path()) =
        TaskReceipts.build(records, messages, price = { _, model -> prices[model] })

    @Test
    fun `the run's receipt carries the plan's models and cost, and both together`() {
        val (planning, running) = build()
        assertNull(planning.plan, "the planning task itself has no plan of its own")
        val plan = assertNotNull(running.plan)
        assertEquals(listOf("claude-opus-5-5"), plan.models)
        assertEquals(1, plan.requests)
        // opus: 10k × $5 + 1k × $25; haiku: 20k × $1 + 2k × $5.
        assertEquals(0.075, assertNotNull(plan.cost).usd, 1e-9)
        assertEquals(listOf("claude-haiku-5-5"), running.models.map { it.name })
        assertEquals(0.03, assertNotNull(running.cost).usd, 1e-9)
        val total = assertNotNull(TaskReceipts.withPlanCost(running))
        assertEquals(0.105, total.usd, 1e-9)
        assertEquals(false, total.partial)
    }

    @Test
    fun `a task that doesn't start with the approval has no plan`() {
        val (_, running) = build(path(approval = "Change the plan: rename only the photos"))
        assertNull(running.plan)
        assertNull(TaskReceipts.withPlanCost(running))
    }

    @Test
    fun `an unknown price on one side makes the total a floor`() {
        val (_, running) = TaskReceipts.build(records, path(), price = { _, model -> prices[model].takeIf { model == "claude-haiku-5-5" } })
        val plan = assertNotNull(running.plan)
        assertNull(plan.cost)
        val total = assertNotNull(TaskReceipts.withPlanCost(running))
        assertEquals(ReceiptCost(0.03, partial = true), total)
        assertTrue(total.partial)
    }
}
