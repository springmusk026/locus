package dev.locus.core.agent.cachesim

import dev.locus.core.agent.ColdClearing
import dev.locus.core.agent.Compaction
import dev.locus.core.agent.CompactionSetup
import dev.locus.core.llm.AnthropicChatModel
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.AppSettings
import dev.locus.core.model.CostBreakdown
import dev.locus.core.model.HelperPrompts
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.PriceSteps
import dev.locus.core.model.Role
import dev.locus.core.model.TokenUsage
import kotlin.math.max
import kotlin.math.min

/**
 * Prices a recorded request sequence under a [CachePolicy] without calling a model (docs/design/cost.md,
 * "Offline cache simulator").
 *
 * What it reuses rather than copies: [ModelPrice.cost] and [ModelPrice.breakdown] price every request (so
 * price steps, 1-hour writes and server-side summaries are the app's own rules), [Compaction.due] decides when
 * Locus summarises, [CompactionSetup.limit] and [PriceSteps] where, [ColdClearing.view] clears old tool results
 * (over stand-in messages of the recorded sizes), and [AnthropicChatModel.compactionTrigger] sets the server
 * trigger. What it adds is the cache itself: which part of each prompt the provider would still hold.
 *
 * The cache model, per request (Anthropic's explicit breakpoints, [CacheMode.EXPLICIT]):
 * - Two entries: the frozen prefix (system prompt and tools) and the conversation so far. A request reads the
 *   longest one that is still alive, writes the rest of its prompt, and refreshes both ("a request that reads
 *   an entry cached with a 5-minute TTL restarts its 5-minute lifetime", cost.md).
 * - Written tokens are priced by their own lifetime: the part inside the prefix at the prefix's, the rest at the
 *   conversation's ([TokenUsage.cacheWrite1h] when an hour, 2x input; else `cost.cache_write`).
 * - An entry is alive for its lifetime after the request that last wrote or read it was sent.
 * [CacheMode.AUTOMATIC] providers use one idle lifetime for both and bill no writes unless the policy says so.
 *
 * What it doesn't model (so it can differ from a live bill): the provider's minimum cacheable length, the
 * 20-block lookback, cache misses that happen anyway, a model that behaves differently after a summary, and
 * the time a request takes (a request is dated from when it was sent). The model's output is taken as
 * recorded under every policy.
 */
object CacheSimulator {

    fun run(sequence: SimSequence, policy: CachePolicy): SimRun = Engine(sequence, policy).run()

    /** [Compaction]'s own request tokens: the compaction prompt every summary request adds. */
    internal val askTokens: Int by lazy { Compaction.estimateTokens(HelperPrompts.compaction(AppSettings())) }

    /** What a summary's view adds around the summary text: its header and the pointer to the full history. */
    internal val boilerplateTokens: Int by lazy { Compaction.estimateTokens(Compaction.render("chat", Part.Checkpoint("", 0))) }

    /** One entry's worth of what the provider holds. */
    private class CacheState(private val mode: CacheMode, private val prefixTtlMs: Long, private val conversationTtlMs: Long, private val writesBilled: Boolean) {
        private var prefixLen = 0
        private var prefixExpires = Long.MIN_VALUE
        private var convLen = 0
        private var convExpires = Long.MIN_VALUE

        /** The cache's counts for a request of [prompt] tokens sent at [at], with [prefix] of them frozen. [unchanged] bounds what can still match. */
        fun serve(at: Long, prompt: Int, prefix: Int, unchanged: Int, conversationTtl: Long): Triple<Int, Int, Int> {
            if (mode == CacheMode.NONE) return Triple(0, 0, 0)
            val convAlive = convLen > 0 && at <= convExpires
            val prefixAlive = prefixLen > 0 && at <= prefixExpires
            val read = min(min(if (convAlive) convLen else if (prefixAlive) prefixLen else 0, prompt), unchanged)
            val write = if (mode == CacheMode.AUTOMATIC && !writesBilled) 0 else prompt - read
            val frozen = min(prefix, prompt)
            val write1h = if (mode != CacheMode.EXPLICIT) 0 else {
                val prefixPart = max(0, frozen - read)
                val convPart = prompt - read - prefixPart
                (if (prefixTtlMs >= HOUR) prefixPart else 0) + (if (conversationTtl >= HOUR) convPart else 0)
            }
            prefixLen = frozen
            prefixExpires = at + prefixTtlMs
            convLen = prompt
            convExpires = at + conversationTtl
            return Triple(read, write, write1h)
        }

        /** The conversation entry can't be matched any more (the context was replaced); the frozen prefix still can. */
        fun forgetConversation() {
            convLen = 0
        }

        val conversationLifetimeMs: Long get() = conversationTtlMs
    }

    /** What one message of the stand-in transcript weighs in tokens, and how much of that is each tool result. */
    private class Weight(val other: Int, val results: List<Int>)

    private class Engine(private val seq: SimSequence, private val policy: CachePolicy) {
        private val price = seq.price
        private val notes = mutableListOf<String>()
        private val mode = policy.mode ?: CacheMode.of(seq.format, seq.model)
        private val clearing = policy.coldClearing.policyFor(seq.format)
        private val summaryTokens = policy.summaryTokens ?: seq.observedSummaryTokens ?: CachePolicy.DEFAULT_SUMMARY_TOKENS
        private val kind: CompactionKind
        private val setup: CompactionSetup?
        private val serverTrigger: Int

        // The stand-in transcript since the last compaction: one message per thing the model was sent, with sizes.
        private val messages = ArrayList<Message>()
        private val weights = ArrayList<Weight>()
        private var clearedBefore: Set<String> = emptySet()
        private var nextId = 0
        private var cumulativeUserTokens = 0L

        init {
            val wanted = policy.compaction ?: CachePolicy.defaultCompaction(seq.format, seq.model)
            val serverOk = seq.format == ApiFormat.ANTHROPIC_MESSAGES && AnthropicChatModel.compactsOnServer(seq.model)
            kind = when {
                seq.format == ApiFormat.ON_DEVICE -> CompactionKind.NONE.also { if (wanted != CompactionKind.NONE) notes += "On-device models don't compact; compaction is off." }
                wanted == CompactionKind.SERVER && !serverOk -> CompactionKind.LOCUS.also { notes += "Server-side compaction isn't offered for ${seq.model}; Locus's own compaction is priced instead." }
                else -> wanted
            }
            setup = if (kind == CompactionKind.LOCUS) {
                val limit = policy.compactionLimitTokens
                if (limit != null) {
                    CompactionSetup(contextWindow = null, prompt = "", ceiling = limit)
                } else {
                    CompactionSetup(
                        seq.contextWindow, "", policy.compactionThreshold,
                        ceiling = if (policy.compactBelowPriceStep) PriceSteps.ceiling(seq.model, price) else null,
                    )
                }
            } else {
                null
            }
            serverTrigger = policy.compactionLimitTokens
                ?: (if (policy.compactBelowPriceStep) AnthropicChatModel.compactionTrigger(seq.model) else null)
                ?: CachePolicy.SERVER_DEFAULT_TRIGGER
            if (kind == CompactionKind.LOCUS && setup?.limit == null) notes += "No context window or price step is known for ${seq.model}, so Locus's compaction never starts."
            if (mode == CacheMode.EXPLICIT && !policy.firstParty && (policy.conversationTtl == CacheTtl.ONE_HOUR || policy.prefixTtl == CacheTtl.ONE_HOUR)) {
                notes += "Only Anthropic's own API takes a 1-hour lifetime; through a gateway both entries are kept 5 minutes."
            }
        }

        private val prefixTtl = if (!policy.firstParty) CacheTtl.FIVE_MINUTES.ms else policy.prefixTtl.ms
        private val conversationTtl = if (!policy.firstParty) CacheTtl.FIVE_MINUTES.ms else policy.conversationTtl.ms
        private val cache = when (mode) {
            CacheMode.AUTOMATIC -> CacheState(mode, policy.automaticIdleMs, policy.automaticIdleMs, policy.automaticWritesBilled)
            else -> CacheState(mode, prefixTtl, conversationTtl, writesBilled = true)
        }
        private val summaryLifetime = if (mode == CacheMode.AUTOMATIC) policy.automaticIdleMs else CacheTtl.FIVE_MINUTES.ms

        private val out = ArrayList<SimRequest>()

        fun run(): SimRun {
            var previous: SimStep? = null
            var previousPrompt = 0
            var lastCompactedAfter = Int.MIN_VALUE / 2
            seq.steps.forEachIndexed { i, step ->
                if (step.startsTurn) lastCompactedAfter = Int.MIN_VALUE / 2
                var kept: Item = Item.of(this, step, includeResults = true)
                // Locus's own compaction, before this request (between turns, or at the step boundary of a run).
                if (kind == CompactionKind.LOCUS && previous != null && setup != null) {
                    val pending = if (step.startsTurn) 0 else step.results.sumOf { (it.chars + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN }
                    val inLoopAllowed = step.startsTurn || i - 1 > lastCompactedAfter + 1
                    if (inLoopAllowed && Compaction.due(TokenUsage(previousPrompt, previous.outputTokens), setup, pending)) {
                        val at = if (step.startsTurn) step.sentAtMs else previous.replyAtMs
                        summarise(at, if (step.startsTurn) null else kept, index = out.size)
                        lastCompactedAfter = i - 1
                        // What replaced the transcript holds the pending results already; only the user's new message follows it.
                        kept = if (step.startsTurn) Item.of(this, step, includeResults = false, onlyUser = true) else Item.EMPTY
                    }
                }
                kept.addTo(this)
                var serverCost: CostBreakdown? = null
                var serverUsage: TokenUsage? = null
                var layout = layout(ColdClearing.view(messages, clearing))
                // Anthropic's server-side summary: it runs inside this request when the prompt is over the trigger, and
                // reads the whole prompt at the input price (cost.md prices it as uncached: the upper bound).
                if (kind == CompactionKind.SERVER && seq.prefixTokens + layout.tokens > serverTrigger) {
                    serverUsage = TokenUsage(input = seq.prefixTokens + layout.tokens, output = summaryTokens)
                    serverCost = price.breakdown(serverUsage)
                    reset(step.sentAtMs, summaryTokens, pointer = false)
                    kept = if (step.startsTurn) Item.of(this, step, includeResults = false, onlyUser = true) else Item.EMPTY
                    kept.addTo(this)
                    cache.forgetConversation()
                    layout = layout(ColdClearing.view(messages, clearing))
                }
                val unchanged = layout.firstChangedOffset ?: Int.MAX_VALUE
                val prompt = seq.prefixTokens + layout.tokens
                val (read, write, write1h) = cache.serve(step.sentAtMs, prompt, seq.prefixTokens, unchanged, cache.conversationLifetimeMs)
                val usage = TokenUsage(
                    input = prompt, output = step.outputTokens, cacheRead = read, cacheWrite = write, cacheWrite1h = write1h,
                    serverCompaction = serverUsage,
                )
                val own = price.breakdown(usage.withoutCompaction)
                out += SimRequest(
                    index = out.size, kind = SimKind.REQUEST, sentAtMs = step.sentAtMs, prefixTokens = seq.prefixTokens,
                    historyTokens = (prompt - seq.prefixTokens - kept.tokens(layout)).coerceAtLeast(0), newTokens = kept.tokens(layout),
                    promptTokens = prompt, outputTokens = step.outputTokens, usage = usage, cost = own, serverCompactionCost = serverCost,
                    costUsd = price.cost(usage), clearedResults = layout.cleared.size,
                    overWindow = seq.contextWindow?.let { prompt > it } == true,
                )
                clearedBefore = layout.cleared
                // The reply ends the request; nothing of it is in the prompt until the next step's growth.
                messages += Message("sim-${nextId++}", "sim", Role.ASSISTANT, emptyList(), step.replyAtMs)
                weights += Weight(0, emptyList())
                previous = step
                previousPrompt = prompt
            }
            return SimRun(policy, notes.distinct(), out, totals())
        }

        /** Locus's summary request at [at], then the transcript is replaced by its view. [pending]: the step's results already in the transcript. */
        private fun summarise(at: Long, pending: Item?, index: Int) {
            pending?.addTo(this)
            val layout = layout(ColdClearing.view(messages, clearing))
            val prompt = seq.prefixTokens + layout.tokens + askTokens
            val (read, write, write1h) = cache.serve(at, prompt, seq.prefixTokens, layout.firstChangedOffset ?: Int.MAX_VALUE, summaryLifetime)
            val usage = TokenUsage(input = prompt, output = summaryTokens, cacheRead = read, cacheWrite = write, cacheWrite1h = write1h)
            out += SimRequest(
                index = index, kind = SimKind.SUMMARY, sentAtMs = at, prefixTokens = seq.prefixTokens,
                historyTokens = layout.tokens, newTokens = askTokens, promptTokens = prompt, outputTokens = summaryTokens,
                usage = usage, cost = price.breakdown(usage), costUsd = price.cost(usage), clearedResults = layout.cleared.size,
                overWindow = seq.contextWindow?.let { prompt > it } == true,
            )
            val keptUser = min(cumulativeUserTokens, Compaction.keptTokens(seq.contextWindow).toLong()).toInt()
            reset(at, summaryTokens + keptUser, pointer = true)
            cache.forgetConversation()
        }

        /** Replaces the transcript with one message of [tokens] (a checkpoint's view, or a server-side summary). */
        private fun reset(at: Long, tokens: Int, pointer: Boolean) {
            messages.clear()
            weights.clear()
            clearedBefore = emptySet()
            messages += Message("sim-${nextId++}", "sim", Role.USER, listOf(Part.Text("summary")), at)
            weights += Weight(tokens + if (pointer) boilerplateTokens else 0, emptyList())
        }

        /** The view's size and which results it cleared; the offset of the first prompt token that differs from the last request's. */
        private class Layout(val tokens: Int, val cleared: Set<String>, val firstChangedOffset: Int?, val itemTokens: List<Int>)

        private fun layout(view: List<Message>): Layout {
            var total = 0
            val cleared = HashSet<String>()
            var firstChanged: Int? = null
            val itemTokens = ArrayList<Int>(view.size)
            view.forEachIndexed { i, m ->
                val w = weights[i]
                var tokens = w.other
                var k = 0
                m.parts.forEach { part ->
                    if (part is Part.ToolResult) {
                        val isNote = part.content.startsWith(ColdClearing.PREFIX)
                        tokens += if (isNote) Compaction.estimateTokens(part.content) else w.results[k]
                        if (isNote) {
                            cleared += part.callId
                            if (part.callId !in clearedBefore && firstChanged == null) firstChanged = seq.prefixTokens + total + w.other
                        }
                        k++
                    }
                }
                itemTokens += tokens
                total += tokens
            }
            return Layout(total, cleared, firstChanged, itemTokens)
        }

        private fun totals(): SimTotals {
            var fresh = 0.0; var read = 0.0; var write = 0.0; var output = 0.0; var compaction = 0.0
            var freshT = 0L; var readT = 0L; var writeT = 0L; var outT = 0L; var over = 0
            out.forEach { r ->
                if (r.kind == SimKind.SUMMARY) {
                    compaction += r.costUsd
                } else {
                    fresh += r.cost.freshUsd; read += r.cost.cacheReadUsd; write += r.cost.cacheWriteUsd; output += r.cost.outputUsd
                    r.serverCompactionCost?.let { compaction += it.usd }
                }
                freshT += (r.usage.input - r.usage.cacheRead - r.usage.cacheWrite).coerceAtLeast(0)
                readT += r.usage.cacheRead
                writeT += r.usage.cacheWrite
                outT += r.usage.output + (r.usage.serverCompaction?.output ?: 0)
                if (r.overWindow) over++
            }
            return SimTotals(
                costUsd = out.sumOf { it.costUsd }, uncachedInputUsd = fresh, cacheReadUsd = read, cacheWriteUsd = write, outputUsd = output,
                compactionUsd = compaction, requests = out.count { it.kind == SimKind.REQUEST }, summaries = out.count { it.kind == SimKind.SUMMARY } +
                    out.count { it.serverCompactionCost != null },
                readTokens = readT, writeTokens = writeT, freshTokens = freshT, outputTokens = outT, requestsOverWindow = over,
            )
        }

        /** The new content of one step as stand-in messages. */
        class Item(private val newMessages: List<Pair<Message, Weight>>, private val userTokens: Int) {
            fun addTo(engine: Engine) {
                newMessages.forEach { (m, w) -> engine.messages += m; engine.weights += w }
                engine.cumulativeUserTokens += userTokens
            }

            /** What these messages weigh in [layout]'s view (they're the last ones before the reply). */
            fun tokens(layout: Layout): Int = layout.itemTokens.takeLast(newMessages.size).sum()

            companion object {
                val EMPTY = Item(emptyList(), 0)

                fun of(engine: Engine, step: SimStep, includeResults: Boolean, onlyUser: Boolean = false): Item {
                    val results = if (includeResults && !onlyUser) step.results else emptyList()
                    // A result is as long as its characters say, unless the recorded growth was smaller (a different tokenizer).
                    val raw = results.map { (it.chars + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN }
                    val room = if (onlyUser) 0 else (step.newTokens - step.userTokens).coerceAtLeast(0)
                    val scale = if (raw.sum() > room && raw.sum() > 0) room.toDouble() / raw.sum() else 1.0
                    val resultTokens = raw.map { (it * scale).toInt() }
                    val total = if (onlyUser) step.userTokens else step.newTokens
                    val other = (total - resultTokens.sum()).coerceAtLeast(0)
                    val list = ArrayList<Pair<Message, Weight>>(2)
                    fun id() = "sim-${engine.nextId++}"
                    if (step.startsTurn || onlyUser) {
                        list += Message(id(), "sim", Role.USER, listOf(Part.Text("user")), step.sentAtMs) to Weight(other, emptyList())
                        if (results.isNotEmpty()) {
                            list += toolMessage(engine, step, results, resultTokens, 0)
                        }
                    } else {
                        list += toolMessage(engine, step, results, resultTokens, other)
                    }
                    return Item(list, if (step.startsTurn || onlyUser) step.userTokens else 0)
                }

                private fun toolMessage(engine: Engine, step: SimStep, results: List<SimToolResult>, tokens: List<Int>, other: Int): Pair<Message, Weight> {
                    val parts = results.map { r ->
                        val id = "r${engine.nextId++}"
                        Part.ToolResult(id, "tool", "x".repeat(r.chars), r.isError)
                    }
                    return Message("sim-${engine.nextId++}", "sim", Role.TOOL, parts, step.sentAtMs) to Weight(other, tokens)
                }
            }
        }
    }

    private const val CHARS_PER_TOKEN = 4
    private const val HOUR = 60L * 60_000
}
