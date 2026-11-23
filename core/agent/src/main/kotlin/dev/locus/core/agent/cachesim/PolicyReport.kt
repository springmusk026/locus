package dev.locus.core.agent.cachesim

import dev.locus.core.agent.ColdClearing
import dev.locus.core.model.ApiFormat
import java.util.Locale

/**
 * The comparison table a developer reads: one row per policy, the totals the roadmap names, and what was
 * approximated. Plain text (Markdown table), so it prints in a test log and pastes into an issue.
 */
object PolicyReport {

    /**
     * The policies worth comparing for [sequence], skipping the ones that can't apply to its provider: the
     * recorded policy first, then each change on its own (the other fields stay as recorded).
     */
    fun variants(sequence: SimSequence): List<CachePolicy> {
        val base = sequence.recordedPolicy
        val mode = base.mode ?: CacheMode.of(sequence.format, sequence.model)
        val out = mutableListOf(base.copy(label = "${base.label} (recorded)"))
        if (mode == CacheMode.EXPLICIT && base.firstParty) {
            out += base.copy(label = "Conversation cache 5 min", conversationTtl = CacheTtl.FIVE_MINUTES)
            out += base.copy(label = "Conversation cache 1 h", conversationTtl = CacheTtl.ONE_HOUR)
        }
        if (mode == CacheMode.AUTOMATIC) {
            out += base.copy(label = "Provider keeps a prefix 5 min", automaticIdleMs = 5L * 60_000)
            out += base.copy(label = "Provider keeps a prefix 24 h", automaticIdleMs = 24L * 60 * 60_000)
        }
        if (sequence.format != ApiFormat.ON_DEVICE) {
            out += base.copy(label = "No compaction", compaction = CompactionKind.NONE)
            val kind = base.compaction ?: CachePolicy.defaultCompaction(sequence.format, sequence.model)
            if (kind == CompactionKind.LOCUS) {
                out += base.copy(label = "Compact at 50% of the window", compactionThreshold = 0.5, compactBelowPriceStep = false)
                if (sequence.price.priceStep != null || dev.locus.core.model.PriceSteps.documented(sequence.model) != null) {
                    out += base.copy(label = "Compact at 80%, ignoring the price step", compactBelowPriceStep = false)
                }
            }
            if (kind == CompactionKind.SERVER) {
                out += base.copy(label = "Server trigger at the 150K default", compactBelowPriceStep = false)
                out += base.copy(label = "Server trigger at 60K", compactionLimitTokens = 60_000)
            }
        }
        if (ColdClearing.policyFor(sequence.format, enabled = true) != null) {
            out += base.copy(label = "Cold clearing off", coldClearing = base.coldClearing.copy(enabled = false))
            out += base.copy(label = "Cold clearing on", coldClearing = base.coldClearing.copy(enabled = true))
        }
        return out.distinctBy { it.copy(label = "") }
    }

    /** [sequence] priced under each of [policies] (default: [variants]), as a Markdown table with its caveats. */
    fun table(sequence: SimSequence, policies: List<CachePolicy> = variants(sequence)): String {
        val runs = policies.map { CacheSimulator.run(sequence, it) }
        val reference = runs.first().totals.costUsd
        return buildString {
            val span = sequence.steps.lastOrNull()?.let { it.replyAtMs - sequence.steps.first().sentAtMs } ?: 0L
            appendLine("### ${sequence.label}")
            appendLine()
            appendLine(
                "${sequence.model} (${sequence.format}), ${sequence.steps.size} requests over ${duration(span)}, " +
                    "prefix ${grouped(sequence.prefixTokens)} tokens, window ${sequence.contextWindow?.let(::grouped) ?: "unknown"}. Prices per million tokens: " +
                    "input ${sequence.price.input}, output ${sequence.price.output}, cache read ${sequence.price.cacheRead ?: "= input"}, " +
                    "cache write ${sequence.price.cacheWrite ?: "= input"}.",
            )
            appendLine()
            appendLine("| Policy | Total | vs first | Uncached input | Cache read | Cache write | Output | Compaction | Requests | Summaries |")
            appendLine("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
            runs.forEach { run ->
                val t = run.totals
                val delta = if (reference > 0) "%+.1f%%".format(Locale.ROOT, (t.costUsd / reference - 1) * 100) else "n/a"
                appendLine(
                    "| ${run.policy.label}${if (t.requestsOverWindow > 0) " (${t.requestsOverWindow} over the window)" else ""} | ${usd(t.costUsd)} | $delta | ${usd(t.uncachedInputUsd)} | " +
                        "${usd(t.cacheReadUsd)} | ${usd(t.cacheWriteUsd)} | ${usd(t.outputUsd)} | ${usd(t.compactionUsd)} | ${t.requests} | ${t.summaries} |",
                )
            }
            sequence.recordedCostUsd?.let { recorded ->
                val simulated = CacheSimulator.run(sequence, sequence.recordedPolicy).totals.costUsd
                appendLine()
                appendLine("Check: the app's accounting priced the recorded usage at ${usd(recorded)}; the simulator under the recorded policy gives ${usd(simulated)}.")
            }
            val notes = (sequence.approximations + runs.flatMap { it.notes }).distinct().toMutableList()
            if (ColdClearing.policyFor(sequence.format, enabled = true) == null) {
                notes += "Cold-cache clearing never applies to ${sequence.format} (docs/design/cold-cache-clearing.md), so no row turns it on."
            }
            if (notes.isNotEmpty()) {
                appendLine()
                appendLine("Approximations:")
                notes.forEach { appendLine("- $it") }
            }
            appendLine("- Output tokens are taken as recorded under every policy; a different compaction or cache setting could change what the model writes.")
            appendLine("- Not modelled: the provider's minimum cacheable length, its lookback window, and cache misses a provider adds on its own.")
        }
    }

    /** One policy's requests, one row each: for finding where a policy's cost comes from. */
    fun requests(run: SimRun): String = buildString {
        appendLine("| # | Kind | Prefix | History | New | Prompt | Read | Written (1 h) | Output | Cost |")
        appendLine("|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|")
        run.requests.forEach { r ->
            appendLine(
                "| ${r.index} | ${r.kind.name.lowercase()} | ${r.prefixTokens} | ${r.historyTokens} | ${r.newTokens} | ${r.promptTokens} | " +
                    "${r.usage.cacheRead} | ${r.usage.cacheWrite} (${r.usage.cacheWrite1h}) | ${r.outputTokens} | ${usd(r.costUsd)} |",
            )
        }
    }

    fun usd(amount: Double): String = "$" + "%.4f".format(Locale.ROOT, amount)

    private fun grouped(n: Int): String = "%,d".format(Locale.ROOT, n)

    private fun duration(ms: Long): String = when {
        ms < 120_000 -> "${ms / 1000} s"
        ms < 2 * 3_600_000 -> "${ms / 60_000} min"
        else -> "%.1f h".format(Locale.ROOT, ms / 3_600_000.0)
    }
}
