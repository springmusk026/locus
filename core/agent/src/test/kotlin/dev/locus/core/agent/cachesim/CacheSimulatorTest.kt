package dev.locus.core.agent.cachesim

import dev.locus.core.agent.Compaction
import dev.locus.core.agent.RunBudget
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.ModelPrice
import dev.locus.core.model.PriceTier
import dev.locus.core.model.TaskBudget
import dev.locus.core.model.TokenUsage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The cache simulator against the app's own accounting. The fixtures under `src/test/resources/cachesim` are
 * synthetic: their per-request usage was worked out by hand from the documented cache rules (docs/design/cost.md),
 * not captured from a provider, so these tests check the simulator and the pricing rules agree with each other and
 * with `ModelPrice.cost` and `RunBudget`, the code receipts, budgets and the bench price with.
 */
class CacheSimulatorTest {
    private fun fixture(name: String): SimSequence = SimJson.decode(javaClass.getResource("/cachesim/$name.json")!!.readText())

    private val fixtures = listOf("opus-pauses", "haiku-server-compaction", "gpt-cold-clearing")

    // ---- Against the recorded accounting ----------------------------------------------------------

    @Test
    fun `under the recorded policy every request reproduces what the provider reported`() {
        for (name in fixtures) {
            val seq = fixture(name)
            val run = CacheSimulator.run(seq, seq.recordedPolicy)
            assertEquals(seq.steps.size, run.requests.size, name)
            seq.steps.forEachIndexed { i, step -> assertEquals(step.recorded, run.requests[i].usage, "$name request $i") }
        }
    }

    @Test
    fun `the simulated total equals what the app's accounting priced the recorded usage at`() {
        for (name in fixtures) {
            val seq = fixture(name)
            val simulated = CacheSimulator.run(seq, seq.recordedPolicy).totals.costUsd
            // The way receipts, task budgets and the bench price a request: RunBudget over ModelPrice.cost.
            val budget = RunBudget(TaskBudget.OFF, price = { _, _ -> seq.price }, costKnown = true)
            seq.steps.forEach { budget.record(it.recorded, "p", seq.model) }
            assertEquals(budget.meter().costUsd!!, simulated, 1e-12, name)
            assertEquals(seq.recordedCostUsd!!, simulated, 1e-12, name)
        }
    }

    @Test
    fun `the hand-worked totals of the fixtures`() {
        // Worked out in dollars per million tokens, see the comments in each fixture's test below.
        assertEquals(0.3054, CacheSimulator.run(fixture("opus-pauses"), fixture("opus-pauses").recordedPolicy).totals.costUsd, 1e-9)
        assertEquals(0.0528, CacheSimulator.run(fixture("haiku-server-compaction"), fixture("haiku-server-compaction").recordedPolicy).totals.costUsd, 1e-9)
        assertEquals(0.1461225, CacheSimulator.run(fixture("gpt-cold-clearing"), fixture("gpt-cold-clearing").recordedPolicy).totals.costUsd, 1e-9)
    }

    @Test
    fun `the parts add up to the total`() {
        for (name in fixtures) {
            val seq = fixture(name)
            val t = CacheSimulator.run(seq, seq.recordedPolicy).totals
            assertEquals(t.costUsd, t.uncachedInputUsd + t.cacheReadUsd + t.cacheWriteUsd + t.outputUsd + t.compactionUsd, 1e-9, name)
        }
    }

    // ---- Cache lifetimes ----------------------------------------------------------------------------

    @Test
    fun `a 5-minute conversation cache reads the prefix after a pause and rewrites the conversation`() {
        val seq = fixture("opus-pauses")
        val run = CacheSimulator.run(seq, seq.recordedPolicy.copy(label = "5 min", conversationTtl = CacheTtl.FIVE_MINUTES))
        val u = run.requests.map { it.usage }
        // First request: the 10,000-token prefix is written for an hour, the other 2,000 for 5 minutes.
        assertEquals(TokenUsage(12_000, 500, 0, 12_000, cacheWrite1h = 10_000), u[0])
        // 30 s later the conversation entry is alive.
        assertEquals(TokenUsage(15_000, 400, 12_000, 3_000, cacheWrite1h = 0), u[1])
        // 11 minutes later it has expired; the prefix (1 hour) is still read, and the rest is written again.
        assertEquals(TokenUsage(16_000, 300, 10_000, 6_000, cacheWrite1h = 0), u[2])
        // 80 minutes later nothing is left.
        assertEquals(TokenUsage(18_000, 200, 0, 18_000, cacheWrite1h = 10_000), u[3])
        // 100,000 + 25,400 + 38,000 + 124,000 microdollars at the per-million prices: $4 input, $5 write, $8 hour write, $0.20 read, $20 output.
        assertEquals(0.2874, run.totals.costUsd, 1e-9)
    }

    @Test
    fun `a gateway keeps both entries 5 minutes and says so`() {
        val seq = fixture("opus-pauses")
        val run = CacheSimulator.run(seq, seq.recordedPolicy.copy(label = "gateway", firstParty = false))
        assertEquals(0, run.requests.sumOf { it.usage.cacheWrite1h })
        assertTrue(run.notes.any { "1-hour" in it }, run.notes.toString())
    }

    @Test
    fun `an automatic provider bills no writes unless the policy says it does`() {
        val seq = fixture("gpt-cold-clearing")
        val free = CacheSimulator.run(seq, seq.recordedPolicy)
        assertEquals(0L, free.totals.writeTokens)
        val priced = seq.copy(price = seq.price.copy(cacheWrite = 1.5625))
        val billed = CacheSimulator.run(priced, priced.recordedPolicy.copy(label = "writes billed", automaticWritesBilled = true))
        assertTrue(billed.totals.writeTokens > 0)
        assertTrue(billed.totals.costUsd > CacheSimulator.run(priced, priced.recordedPolicy).totals.costUsd)
        // Everything the first request sent is new, so all of it is written.
        val first = billed.requests.first().usage
        assertEquals(first.input, first.cacheWrite)
    }

    @Test
    fun `a shorter idle lifetime on an automatic provider costs more`() {
        val seq = longChat(ApiFormat.OPENAI_CHAT, "gpt-5.5", gapMs = 10 * 60_000L)
        val long = CacheSimulator.run(seq, seq.recordedPolicy.copy(label = "30 min"))
        val short = CacheSimulator.run(seq, seq.recordedPolicy.copy(label = "5 min", automaticIdleMs = 5 * 60_000L))
        assertTrue(short.totals.costUsd > long.totals.costUsd)
        assertEquals(0L, short.totals.readTokens)
    }

    @Test
    fun `nothing is read when the provider has no cache`() {
        val seq = fixture("opus-pauses")
        val run = CacheSimulator.run(seq, seq.recordedPolicy.copy(label = "none", mode = CacheMode.NONE))
        assertEquals(0L, run.totals.readTokens)
        assertEquals(0L, run.totals.writeTokens)
        assertEquals(seq.steps.sumOf { it.recorded!!.input.toLong() }, run.totals.freshTokens)
    }

    // ---- Compaction -------------------------------------------------------------------------------

    @Test
    fun `server compaction triggers at the model's own ceiling and bills the summary inside the request`() {
        val seq = fixture("haiku-server-compaction")
        val run = CacheSimulator.run(seq, seq.recordedPolicy)
        val r = run.requests[7]
        assertEquals(TokenUsage(92_000, 4_000), r.usage.serverCompaction)
        assertEquals(16_000, r.promptTokens)
        assertEquals(12_000, r.usage.cacheRead)
        assertEquals(1, run.totals.summaries)
        // With the API's default 150,000 trigger the prompt crosses the 100,000 step first and is summarised once, at 152,000.
        val unceiled = CacheSimulator.run(seq, seq.recordedPolicy.copy(label = "150K", compactBelowPriceStep = false))
        assertEquals(1, unceiled.totals.summaries)
        assertTrue(unceiled.requests.any { it.promptTokens > 100_000 })
        assertTrue(unceiled.totals.costUsd > run.totals.costUsd, "the 5x step costs more than the summary")
    }

    @Test
    fun `no compaction lets the prompt pass the window and says how often`() {
        val seq = fixture("haiku-server-compaction").copy(contextWindow = 60_000)
        val run = CacheSimulator.run(seq, seq.recordedPolicy.copy(label = "off", compaction = CompactionKind.NONE))
        assertTrue(run.totals.requestsOverWindow > 0)
    }

    @Test
    fun `Locus compaction summarises the cached prompt and the next request starts from the summary`() {
        val seq = growingChat(window = 50_000)
        val policy = CachePolicy(label = "Locus", compaction = CompactionKind.LOCUS)
        val run = CacheSimulator.run(seq, policy)
        val summary = run.requests.single { it.kind == SimKind.SUMMARY }
        assertEquals(4, summary.index)
        // The last request's context (34,000 + 500) plus the 7,500 tokens of results about to be added is over 80% of 50,000.
        val before = run.requests[3]
        assertEquals(34_000, before.promptTokens)
        // The summary request is the next turn's prompt plus the compaction prompt, and reads the cache for what was already there.
        assertEquals(before.promptTokens, summary.usage.cacheRead)
        assertEquals(34_000 + 9_000 + CacheSimulator.askTokens, summary.promptTokens)
        assertEquals(summary.promptTokens - 34_000, summary.usage.cacheWrite)
        assertEquals(0, summary.usage.cacheWrite1h, "a summary request keeps the 5-minute lifetime")
        assertEquals(CachePolicy.DEFAULT_SUMMARY_TOKENS, summary.outputTokens)
        // The next request is the frozen prefix, then one message: the summary, the user's words kept word for word, and the pointer.
        val next = run.requests[5]
        assertEquals(5_000 + CachePolicy.DEFAULT_SUMMARY_TOKENS + 2_000 + CacheSimulator.boilerplateTokens, next.promptTokens)
        assertEquals(5_000, next.usage.cacheRead, "only the frozen prefix is still cached")
        assertEquals(1, run.totals.summaries)
        assertEquals(summary.costUsd, run.totals.compactionUsd, 1e-12)
    }

    @Test
    fun `without compaction the same chat never summarises`() {
        val run = CacheSimulator.run(growingChat(window = 50_000), CachePolicy(label = "off", compaction = CompactionKind.NONE))
        assertEquals(0, run.totals.summaries)
        assertEquals(0.0, run.totals.compactionUsd, 0.0)
    }

    @Test
    fun `Locus compaction stays below the price step`() {
        val price = ModelPrice(
            0.10, 0.50, 0.01, 0.125,
            tiers = listOf(PriceTier(100_000, ModelPrice(0.50, 2.50, 0.05, 0.625))),
        )
        val seq = growingChat(window = 1_000_000, steps = 14, price = price, model = "claude-haiku-5-5")
        val below = CacheSimulator.run(seq, CachePolicy(label = "below", compaction = CompactionKind.LOCUS))
        val ignoring = CacheSimulator.run(seq, CachePolicy(label = "ignoring", compaction = CompactionKind.LOCUS, compactBelowPriceStep = false))
        assertTrue(below.requests.none { it.promptTokens > 100_000 })
        assertTrue(ignoring.requests.any { it.promptTokens > 100_000 })
        assertTrue(below.totals.summaries > 0)
        assertTrue(below.totals.costUsd < ignoring.totals.costUsd)
    }

    @Test
    fun `an explicit limit overrides the window`() {
        val seq = growingChat(window = 1_000_000)
        val run = CacheSimulator.run(seq, CachePolicy(label = "20K", compaction = CompactionKind.LOCUS, compactionLimitTokens = 20_000))
        assertTrue(run.totals.summaries > 0)
    }

    @Test
    fun `server compaction is only offered where Anthropic offers it`() {
        val seq = fixture("gpt-cold-clearing")
        val run = CacheSimulator.run(seq, seq.recordedPolicy.copy(label = "server", compaction = CompactionKind.SERVER))
        assertTrue(run.notes.any { "Server-side compaction isn't offered" in it }, run.notes.toString())
    }

    // ---- Cold-cache clearing ------------------------------------------------------------------------

    @Test
    fun `after a pause of more than a day the old tool results are cleared and the prompt shrinks`() {
        val seq = fixture("gpt-cold-clearing")
        val on = CacheSimulator.run(seq, seq.recordedPolicy)
        val off = CacheSimulator.run(seq, seq.recordedPolicy.copy(label = "off", coldClearing = ClearingSetting(enabled = false)))
        // Twelve 12,000-character results (3,000 tokens each); the newest five stay, seven become 20-token notes.
        assertEquals(7, on.requests[13].clearedResults)
        assertEquals(44_800, off.requests[13].promptTokens)
        assertEquals(44_800 - 7 * (3_000 - 20), on.requests[13].promptTokens)
        assertTrue(on.totals.costUsd < off.totals.costUsd)
        // Every later request keeps them cleared, so the cache written after the gap keeps hitting.
        assertEquals(7, on.requests[15].clearedResults)
        assertEquals(on.requests[14].promptTokens, on.requests[15].usage.cacheRead)
    }

    @Test
    fun `clearing doesn't apply to Anthropic and the table says so`() {
        val seq = fixture("opus-pauses")
        val run = CacheSimulator.run(seq, seq.recordedPolicy.copy(label = "clear", coldClearing = ClearingSetting(enabled = true)))
        assertTrue(run.requests.all { it.clearedResults == 0 })
        assertTrue("Cold-cache clearing never applies to ANTHROPIC_MESSAGES" in PolicyReport.table(seq))
    }

    @Test
    fun `clearing inside a cache's lifetime keeps what comes before the first cleared result`() {
        // Cold after 10 minutes on a provider that holds a prefix for a day: the cache survives the pause, and the
        // clearing rewrites everything from the first cleared result on.
        val steps = buildList {
            add(SimStep(0, 1_000, 1_000, 1_000, emptyList(), 100, startsTurn = true))
            for (k in 1..12) add(SimStep(k * 5_000L, k * 5_000L + 1_000, 3_100, 0, listOf(SimToolResult(12_000)), 100))
            add(SimStep(12 * 5_000L + 11 * 60_000L, 12 * 5_000L + 11 * 60_000L + 1_000, 600, 500, emptyList(), 100, startsTurn = true))
        }
        val seq = SimSequence(
            "clearing", "gpt-5.5", ApiFormat.OPENAI_CHAT, ModelPrice(1.25, 10.0, 0.125), 400_000, 1_000, steps,
            CachePolicy(label = "today", compaction = CompactionKind.NONE, automaticIdleMs = 24L * 3_600_000),
        )
        val run = CacheSimulator.run(seq, seq.recordedPolicy.copy(coldClearing = ClearingSetting(coldAfterMs = 10 * 60_000L)))
        val last = run.requests.last()
        assertEquals(7, last.clearedResults)
        // The prefix (1,000), the first user message (1,000) and the first call's 100 tokens come before result 1.
        assertEquals(2_100, last.usage.cacheRead)
    }

    // ---- Helpers ----------------------------------------------------------------------------------

    /** A chat of one user message and then [steps] tool steps, each adding 9,000 tokens of which 30,000 characters are a result. */
    private fun growingChat(
        window: Int,
        steps: Int = 8,
        price: ModelPrice = ModelPrice(4.0, 20.0, 0.2, 5.0),
        model: String = "claude-opus-5-5",
    ): SimSequence {
        val list = buildList {
            add(SimStep(0, 5_000, 2_000, 2_000, emptyList(), 500, startsTurn = true))
            for (i in 1 until steps) add(SimStep(i * 10_000L, i * 10_000L + 5_000, 9_000, 0, listOf(SimToolResult(30_000)), 500))
        }
        return SimSequence("growing", model, ApiFormat.ANTHROPIC_MESSAGES, price, window, 5_000, list, CachePolicy(label = "today", compaction = CompactionKind.NONE))
    }

    /** Twelve steps [gapMs] apart. */
    private fun longChat(format: ApiFormat, model: String, gapMs: Long): SimSequence {
        val list = List(12) { i -> SimStep(i * gapMs, i * gapMs + 1_000, 2_000, if (i == 0) 2_000 else 0, emptyList(), 200, startsTurn = i == 0) }
        return SimSequence("long", model, format, ModelPrice(1.25, 10.0, 0.125), 400_000, 4_000, list, CachePolicy(label = "today", compaction = CompactionKind.NONE))
    }

    @Test
    fun `the summary and boilerplate sizes come from the app's own prompts`() {
        assertNotNull(CacheSimulator.askTokens)
        assertTrue(CacheSimulator.askTokens > 20, "the compaction prompt is more than a line")
        assertTrue(Compaction.estimateTokens(Compaction.HEADER) <= CacheSimulator.boilerplateTokens)
    }
}
