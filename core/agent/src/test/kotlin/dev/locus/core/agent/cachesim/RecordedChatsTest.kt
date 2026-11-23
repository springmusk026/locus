package dev.locus.core.agent.cachesim

import dev.locus.core.agent.Compaction
import dev.locus.core.agent.RunBudget
import dev.locus.core.llm.ModelCatalogIndex
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.Message
import dev.locus.core.model.ModelPrice
import dev.locus.core.model.Part
import dev.locus.core.model.ReplyInfo
import dev.locus.core.model.Role
import dev.locus.core.model.TaskBudget
import dev.locus.core.model.TokenUsage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Stored messages become a sequence the simulator prices, and the price is the app's own. */
class RecordedChatsTest {
    private val price = ModelPrice(4.0, 20.0, 0.2, 5.0)
    private var n = 0

    private fun user(text: String, at: Long) = Message("m${n++}", "c", Role.USER, listOf(Part.Text(text)), at)

    private fun tool(chars: Int, at: Long) = Message("m${n++}", "c", Role.TOOL, listOf(Part.ToolResult("call${n++}", "read_file", "x".repeat(chars))), at)

    private fun reply(usage: TokenUsage, at: Long, tookMs: Long) = Message(
        "m${n++}", "c", Role.ASSISTANT, listOf(Part.Text("ok"), Part.ToolCall("call$n", "read_file", "{}")), at,
        usage = usage, reply = ReplyInfo(model = "claude-opus-5-5", totalMs = tookMs),
    )

    /** The chat of the opus-pauses fixture, as the app would have stored it (1-hour conversation cache). */
    private fun storedChat(): List<Message> = listOf(
        user("u".repeat(8_000), 0),
        reply(TokenUsage(12_000, 500, 0, 12_000, cacheWrite1h = 12_000), 20_000, 20_000),
        tool(10_000, 30_000),
        reply(TokenUsage(15_000, 400, 12_000, 3_000, cacheWrite1h = 3_000), 40_000, 10_000),
        user("u".repeat(2_400), 30_000 + 11 * 60_000),
        reply(TokenUsage(16_000, 300, 15_000, 1_000, cacheWrite1h = 1_000), 30_000 + 11 * 60_000 + 8_000, 8_000),
        tool(6_800, 30_000 + 91 * 60_000),
        reply(TokenUsage(18_000, 200, 0, 18_000, cacheWrite1h = 18_000), 30_000 + 91 * 60_000 + 6_000, 6_000),
    )

    @Test
    fun `a stored chat becomes the sequence it was`() {
        val seq = RecordedChats.sequenceOf("chat", storedChat(), "claude-opus-5-5", ApiFormat.ANTHROPIC_MESSAGES, price, 1_000_000)
        assertEquals(10_000, seq.prefixTokens)
        assertEquals(listOf(2_000, 3_000, 1_000, 2_000), seq.steps.map { it.newTokens })
        assertEquals(listOf(2_000, 0, 600, 0), seq.steps.map { it.userTokens })
        assertEquals(listOf(true, false, true, false), seq.steps.map { it.startsTurn })
        assertEquals(listOf(0, 1, 0, 1), seq.steps.map { it.results.size })
        assertEquals(listOf(10_000, 6_800), seq.steps.flatMap { it.results }.map { it.chars })
        // Dated from when the request went out: the stored time less how long it took.
        assertEquals(listOf(0L, 30_000L, 30_000L + 11 * 60_000, 30_000L + 91 * 60_000), seq.steps.map { it.sentAtMs })
        assertTrue(seq.approximations.any { "system prompt and tool list" in it }, "the prefix is an estimate and says so")
    }

    @Test
    fun `the simulator under the recorded policy reproduces what the app's accounting priced`() {
        val seq = RecordedChats.sequenceOf(
            "chat", storedChat(), "claude-opus-5-5", ApiFormat.ANTHROPIC_MESSAGES, price, 1_000_000,
            recordedPolicy = CachePolicy.today(ApiFormat.ANTHROPIC_MESSAGES, "claude-opus-5-5").copy(compaction = CompactionKind.NONE),
        )
        val run = CacheSimulator.run(seq, seq.recordedPolicy)
        seq.steps.forEachIndexed { i, s -> assertEquals(s.recorded, run.requests[i].usage, "request $i") }
        val budget = RunBudget(TaskBudget.OFF, price = { _, _ -> price }, costKnown = true)
        storedChat().forEach { budget.record(it.usage, "p", "claude-opus-5-5") }
        assertEquals(budget.meter().costUsd!!, run.totals.costUsd, 1e-12)
        assertEquals(0.3054, run.totals.costUsd, 1e-9)
    }

    @Test
    fun `a known prefix isn't estimated`() {
        val seq = RecordedChats.sequenceOf("chat", storedChat(), "claude-opus-5-5", ApiFormat.ANTHROPIC_MESSAGES, price, prefixTokens = 9_000)
        assertEquals(9_000, seq.prefixTokens)
        assertEquals(3_000, seq.steps.first().newTokens)
        assertTrue(seq.approximations.none { "system prompt and tool list" in it })
    }

    @Test
    fun `a request that follows a checkpoint is estimated from the view, not from the old prompt`() {
        val checkpoint = Message(
            "cp", "c", Role.USER, listOf(Part.Checkpoint("s".repeat(4_000), 6, recentUserMessages = listOf("u".repeat(400)))), 50_000,
        )
        val messages = storedChat().take(4) + checkpoint + listOf(
            user("u".repeat(800), 60_000),
            reply(TokenUsage(10_500, 100, 10_000, 500), 61_000, 1_000),
        )
        val seq = RecordedChats.sequenceOf("chat", messages, "claude-opus-5-5", ApiFormat.ANTHROPIC_MESSAGES, price)
        val after = seq.steps.last()
        val view = Compaction.estimateTokens(Compaction.render("c", checkpoint.parts.single() as Part.Checkpoint))
        // The prompt is 10,500 tokens: the 10,000-token prefix, the view of the checkpoint, and whatever the view doesn't explain.
        assertEquals((10_500 - 10_000 - view).coerceAtLeast(0), after.newTokens)
        assertEquals(1_000, seq.observedSummaryTokens, "the summary's own length, 4,000 characters at 4 a token")
        assertTrue(seq.approximations.any { "follow a summary" in it }, seq.approximations.toString())
    }

    @Test
    fun `a sequence survives its JSON`() {
        val seq = RecordedChats.sequenceOf("chat", storedChat(), "claude-opus-5-5", ApiFormat.ANTHROPIC_MESSAGES, price, 1_000_000)
        assertEquals(seq, SimJson.decode(SimJson.encode(seq)))
    }

    @Test
    fun `the price is the catalogue's, tiers included`() {
        val index = ModelCatalogIndex.parseModelsDev(
            """{"anthropic":{"models":{"claude-haiku-5-5":{"cost":{"input":0.1,"output":0.5,"cache_read":0.01,"cache_write":0.125,
              "tiers":[{"tier":{"type":"context","size":100000},"input":0.5,"output":2.5,"cache_read":0.05,"cache_write":0.625}]}}}}}""",
        )
        val catalogPrice = index.lookup("https://api.anthropic.com", ApiFormat.ANTHROPIC_MESSAGES, "claude-haiku-5-5")?.price
        assertNotNull(catalogPrice)
        assertEquals(100_000, catalogPrice.priceStep)
        val seq = RecordedChats.sequenceOf("chat", storedChat(), "claude-haiku-5-5", ApiFormat.ANTHROPIC_MESSAGES, catalogPrice)
        // 18,000-token prompts are under the step, so the first tier prices them.
        val run = CacheSimulator.run(seq, seq.recordedPolicy)
        assertEquals(storedChat().filter { it.usage != null }.sumOf { catalogPrice.cost(it.usage!!) }, run.totals.costUsd, 1e-12)
    }
}
