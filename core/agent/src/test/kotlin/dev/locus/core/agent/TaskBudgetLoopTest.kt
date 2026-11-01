package dev.locus.core.agent

import dev.locus.core.llm.AssistantTurn
import dev.locus.core.llm.ChatEvent
import dev.locus.core.llm.ChatModel
import dev.locus.core.llm.ChatRequest
import dev.locus.core.llm.StopReason
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.AuditEntry
import dev.locus.core.model.Conversation
import dev.locus.core.model.Message
import dev.locus.core.model.ModelPrice
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import dev.locus.core.model.TaskBudget
import dev.locus.core.model.TokenUsage
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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskBudgetLoopTest {
    private val conversation = Conversation("c", "t", "p", "system", 0, 0)
    private val user = Message("u", "c", Role.USER, listOf(Part.Text("go")), 0)

    /** Every turn calls the tool (until [turns] run out, then it answers), each reporting [usage]. */
    private class Model(private val turns: Int, private val usage: TokenUsage, private val onRequest: () -> Unit = {}) : ChatModel {
        val requests = mutableListOf<ChatRequest>()
        override val format = ApiFormat.OPENAI_CHAT
        override fun stream(request: ChatRequest): Flow<ChatEvent> = flow {
            onRequest()
            requests += request
            val n = requests.size
            val calls = if (n <= turns) listOf(Part.ToolCall("call$n", "work", "{}"), Part.ToolCall("call${n}b", "work", "{}")) else emptyList()
            emit(ChatEvent.Completed(AssistantTurn(if (calls.isEmpty()) "Done." else "", calls, if (calls.isEmpty()) StopReason.END_TURN else StopReason.TOOL_USE, ApiFormat.OPENAI_CHAT, null, usage = usage)))
        }
    }

    private class Work(private val onRun: () -> Unit = {}) : Tool {
        var runs = 0
        override val risk = ToolRisk.READ
        override val spec = ToolSpec("work", "test", ObjectSchema().build())
        override fun describe(args: JsonObject) = "work"
        override suspend fun execute(args: JsonObject): ToolResult {
            runs++
            onRun()
            return ToolResult("ok")
        }
    }

    private class Store : TranscriptStore {
        val messages = mutableListOf<Message>()
        override suspend fun append(message: Message) { messages += message }
        override suspend fun record(entry: AuditEntry) = Unit
    }

    private var ids = 0
    private fun loop(model: ChatModel, tool: Tool, store: TranscriptStore, budget: RunBudget?, gate: BudgetGate? = null) = AgentLoop(
        model, ToolRegistry(listOf(tool)), { ToolPolicy.ALLOW }, { true }, store,
        AgentConfig(budget = budget, budgetGate = gate, providerId = "p", model = "m"),
        ids = { "id${ids++}" },
    )

    private val tenK = TokenUsage(input = 8_000, output = 2_000)

    @Test
    fun `usage adds up across steps and the run pauses only between steps, never mid-tool`() = runTest {
        val tool = Work()
        val store = Store()
        val budget = RunBudget(TaskBudget(tokens = 25_000))
        val asked = mutableListOf<Pair<Int, BudgetLimit>>()
        val events = loop(Model(turns = 10, usage = tenK), tool, store, budget, BudgetGate { _, limit -> asked += tool.runs to limit; false })
            .run(conversation, listOf(user)).toList()

        // Three requests (30k) went out before the check: 10k and 20k were under 25k.
        assertEquals(30_000, budget.meter().tokens)
        // Asked once, after both calls of the third step had run.
        assertEquals(listOf(6 to BudgetLimit.TOKENS), asked)
        // Every call has its result: the transcript ends on a tool message, valid to continue.
        assertEquals(Role.TOOL, store.messages.last().role)
        assertEquals(store.messages.flatMap { it.toolCalls }.map { it.id }, store.messages.flatMap { it.toolResults }.map { it.callId })
        val outcome = assertIs<Outcome.BudgetStopped>((events.last() as AgentEvent.Finished).outcome)
        assertEquals(BudgetLimit.TOKENS, outcome.limit)
        assertEquals("Stopped: reached the 25k-token budget for this task.", outcome.message)
        assertEquals("Paused: reached the 25k-token budget for this task.", budget.pauseMessage(BudgetLimit.TOKENS))
    }

    @Test
    fun `Continue grants one more increment of the same size`() = runTest {
        val budget = RunBudget(TaskBudget(tokens = 25_000))
        var answers = 0
        val model = Model(turns = 10, usage = tenK)
        val events = loop(model, Work(), Store(), budget, BudgetGate { _, _ -> answers++ == 0 }).run(conversation, listOf(user)).toList()
        // 30k: Continue (allowance 50k); 40k, 50k: paused again and stopped.
        assertEquals(2, answers)
        assertEquals(5, model.requests.size)
        assertEquals(50_000, budget.meter().tokenLimit)
        assertIs<Outcome.BudgetStopped>((events.last() as AgentEvent.Finished).outcome)
    }

    @Test
    fun `a run that finishes under its budget never asks`() = runTest {
        val budget = RunBudget(TaskBudget(tokens = 1_000_000, minutes = 60))
        var asked = false
        val events = loop(Model(turns = 2, usage = tenK), Work(), Store(), budget, BudgetGate { _, _ -> asked = true; true }).run(conversation, listOf(user)).toList()
        assertFalse(asked)
        assertEquals(Outcome.Completed, (events.last() as AgentEvent.Finished).outcome)
        assertEquals(30_000, budget.meter().tokens)
    }

    @Test
    fun `with budgets off nothing changes, and no server budget is sent`() = runTest {
        val model = Model(turns = 3, usage = tenK)
        val store = Store()
        val off = RunBudget(TaskBudget.OFF)
        val events = loop(model, Work(), store, off, BudgetGate { _, _ -> error("never asked") }).run(conversation, listOf(user)).toList()
        assertEquals(Outcome.Completed, (events.last() as AgentEvent.Finished).outcome)
        assertTrue(model.requests.all { it.taskBudget == null })
        val none = Model(turns = 3, usage = tenK)
        loop(none, Work(), Store(), null).run(conversation, listOf(user)).toList()
        assertEquals(model.requests.map { it.copy(messages = emptyList()) }, none.requests.map { it.copy(messages = emptyList()) })
    }

    @Test
    fun `the server budget is the configured size on every request, also after Continue`() = runTest {
        val model = Model(turns = 6, usage = tenK)
        val budget = RunBudget(TaskBudget(tokens = 25_000))
        loop(model, Work(), Store(), budget, BudgetGate { _, _ -> true }).run(conversation, listOf(user)).toList()
        assertEquals(setOf(25_000L), model.requests.map { it.taskBudget }.toSet())
        assertEquals(7, model.requests.size)
    }

    @Test
    fun `cost is estimated from prices and its limit applies only when the chat model's prices are known`() = runTest {
        val price = ModelPrice(input = 5.0, output = 25.0)
        // Each request: 8k input ($0.04) + 2k output ($0.05).
        val known = RunBudget(TaskBudget(costUsd = 0.2), price = { _, _ -> price }, costKnown = true)
        var limit: BudgetLimit? = null
        loop(Model(turns = 10, usage = tenK), Work(), Store(), known, BudgetGate { _, l -> limit = l; false }).run(conversation, listOf(user)).toList()
        assertEquals(BudgetLimit.COST, limit)
        assertEquals(0.27, known.meter().costUsd!!, 1e-9)
        assertEquals("Paused: reached the $0.20 cost budget for this task.", known.pauseMessage(BudgetLimit.COST))

        val unknown = RunBudget(TaskBudget(costUsd = 0.2), costKnown = false, costUnavailable = "No price for m.")
        val events = loop(Model(turns = 4, usage = tenK), Work(), Store(), unknown, BudgetGate { _, _ -> error("never asked") }).run(conversation, listOf(user)).toList()
        assertEquals(Outcome.Completed, (events.last() as AgentEvent.Finished).outcome)
        assertNull(unknown.meter().costUsd)
        assertNull(unknown.meter().costLimit)
        assertEquals("No price for m.", unknown.meter().costUnavailable)
    }

    @Test
    fun `time counts wall clock but not waiting for the user`() = runTest {
        var now = 0L
        val budget = RunBudget(TaskBudget(minutes = 10), clock = { now })
        now = 4 * RunBudget.MINUTE
        budget.waitingForUser { now += 30 * RunBudget.MINUTE }
        assertNull(budget.exceeded())
        assertEquals(4 * RunBudget.MINUTE, budget.meter().elapsedMs)
        now += 6 * RunBudget.MINUTE
        assertEquals(BudgetLimit.TIME, budget.exceeded())
        budget.extend(BudgetLimit.TIME)
        assertNull(budget.exceeded())
        assertEquals(20 * RunBudget.MINUTE, budget.meter().timeLimitMs)
    }

    @Test
    fun `a long step overruns the time limit and the pause waits for it to end`() = runTest {
        var now = 0L
        val budget = RunBudget(TaskBudget(minutes = 1), clock = { now })
        val tool = Work(onRun = { now += 2 * RunBudget.MINUTE })
        val asked = mutableListOf<Int>()
        loop(Model(turns = 5, usage = tenK), tool, Store(), budget, BudgetGate { _, _ -> asked += tool.runs; false }).run(conversation, listOf(user)).toList()
        // Both calls of the first step ran (four minutes), then it stopped.
        assertEquals(listOf(2), asked)
    }

    @Test
    fun `token counts and money read compactly`() {
        assertEquals("950", RunBudget.compactTokens(950))
        assertEquals("1.5k", RunBudget.compactTokens(1_500))
        assertEquals("38k", RunBudget.compactTokens(38_400))
        assertEquals("200k", RunBudget.compactTokens(200_000))
        assertEquals("1M", RunBudget.compactTokens(1_000_000))
        assertEquals("1.2M", RunBudget.compactTokens(1_200_000))
        assertEquals("<$0.01", RunBudget.usd(0.004))
        assertEquals("$0.42", RunBudget.usd(0.42))
    }

    @Test
    fun `the budget chip reads aloud in words and the sheet lists the limits`() {
        val meter = BudgetMeter(tokens = 38_400, tokenLimit = 200_000, costUsd = 0.12, costLimit = 1.0, elapsedMs = 0, timeLimitMs = 30 * RunBudget.MINUTE)
        assertEquals("38k of 200k tokens used", BudgetText.spokenChip(meter))
        assertEquals("200k tokens · \$1.00 · 30m", BudgetText.limits(meter))
        val timeOnly = BudgetMeter(tokens = 0, tokenLimit = null, costUsd = null, costLimit = null, elapsedMs = 4 * RunBudget.MINUTE, timeLimitMs = 30 * RunBudget.MINUTE)
        assertEquals("4m of 30m used", BudgetText.spokenChip(timeOnly))
        assertNull(BudgetText.limits(timeOnly.copy(timeLimitMs = null)))
    }
}
