package dev.locus.core.agent.cachesim

import dev.locus.core.agent.ColdClearing
import dev.locus.core.llm.AnthropicChatModel
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.CostBreakdown
import dev.locus.core.model.ModelPrice
import dev.locus.core.model.TokenUsage
import kotlinx.serialization.Serializable

/*
 * Offline cache simulator (docs/design/cost.md, "Offline cache simulator"). These are its data types: a
 * recorded request sequence ([SimSequence]), a policy to price it under ([CachePolicy]), and the result
 * ([SimRun]). Nothing here talks to a model or to Android; all of it is JSON-serialisable so a sequence
 * can be a small fixture file.
 */

/** One tool result a request carried, as far as clearing old results needs it: its size and whether it failed. */
@Serializable
data class SimToolResult(
    /** Characters of its text, plus [ColdClearing.IMAGE_CHARS] for each image it carried. */
    val chars: Int,
    val isError: Boolean = false,
)

/**
 * One model request of the chat, in the order they were sent. The prompt's segments are: the frozen prefix
 * ([SimSequence.prefixTokens], system prompt and tool list), the history (everything the earlier steps added,
 * as the policy keeps it) and the step's own [newTokens].
 */
@Serializable
data class SimStep(
    /** When the request was sent, in epoch milliseconds. The cache's lifetime runs from here. */
    val sentAtMs: Long,
    /** When its reply ended. Cold-cache clearing measures its 24-hour gap from here. */
    val replyAtMs: Long,
    /**
     * How much the prompt grew since the previous request's prompt: the previous reply as it is sent back,
     * plus the tool results and the user's message that came after it. For the first request, its prompt
     * less the prefix. Reasoning that isn't sent back is not in it.
     */
    val newTokens: Int,
    /** The part of [newTokens] that is the user's own text (a compaction keeps those verbatim). */
    val userTokens: Int = 0,
    /** The tool results among the new content (cold-cache clearing may clear them later). */
    val results: List<SimToolResult> = emptyList(),
    /** What the model produced, reasoning included. Billed at the output price. */
    val outputTokens: Int,
    /** The user's own message starts this step (after it the task in progress starts over). */
    val startsTurn: Boolean = false,
    /** What the provider reported for this request, as stored. Null for a what-if sequence nobody recorded. */
    val recorded: TokenUsage? = null,
)

/** A summary request that Locus itself made (a checkpoint's own request), as recorded in the usage log. */
@Serializable
data class SimRecordedSummary(val atMs: Long, val usage: TokenUsage)

/**
 * A recorded chat, reduced to what pricing it under another policy needs. Make one from stored messages with
 * [RecordedChats.sequenceOf], or from a JSON file ([SimJson]).
 */
@Serializable
data class SimSequence(
    val label: String,
    val model: String,
    val format: ApiFormat,
    /** USD per million tokens, from models.dev (`ModelCatalog`), as the app prices this model. */
    val price: ModelPrice,
    val contextWindow: Int? = null,
    /** The system prompt and tool list: frozen for the chat, so cached before everything else. */
    val prefixTokens: Int,
    val steps: List<SimStep>,
    /** What the app did when this was recorded. [CacheSimulator.run] under it should reproduce [recordedCostUsd]. */
    val recordedPolicy: CachePolicy,
    /** Summary requests Locus made, which aren't messages. Empty when none was made or none was recorded. */
    val recordedSummaries: List<SimRecordedSummary> = emptyList(),
    /** About how long the summaries were, in tokens, when the recording had any; the default for a policy that doesn't say. */
    val observedSummaryTokens: Int? = null,
    /** What was estimated rather than read, in words ([RecordedChats] fills it in). Shown with every table. */
    val approximations: List<String> = emptyList(),
) {
    /**
     * What the app's own accounting priced this sequence at: [ModelPrice.cost] of every recorded usage, as
     * receipts, budgets and the bench do. Null when a step has no recorded usage.
     */
    val recordedCostUsd: Double?
        get() = if (steps.any { it.recorded == null }) null else steps.sumOf { price.cost(it.recorded!!) } + recordedSummaries.sumOf { price.cost(it.usage) }
}

/** How long a cache entry lives after it was last written or read. Anthropic offers both (docs/design/cost.md, "Cache lifetimes"). */
@Serializable
enum class CacheTtl(val ms: Long, val label: String) {
    FIVE_MINUTES(5L * 60_000, "5 min"),
    ONE_HOUR(60L * 60_000, "1 h"),
}

/** How a provider caches prompts, from [dev.locus.core.llm.CacheStyle]. */
@Serializable
enum class CacheMode {
    /** The request marks what to cache: two breakpoints, the frozen prefix and the conversation so far. */
    EXPLICIT,

    /** The provider caches repeated prefixes by itself, for a lifetime the request doesn't choose. */
    AUTOMATIC,

    /** Nothing is cached (an on-device model, or a provider that reports no cache use). */
    NONE,
    ;

    companion object {
        /**
         * What a provider does by default: Anthropic's own format and Claude through OpenRouter (which Locus marks)
         * are [EXPLICIT]; on-device models [NONE]; every other host [AUTOMATIC] (cost.md, "OpenRouter and Chat
         * Completions caching").
         */
        fun of(format: ApiFormat, model: String): CacheMode = when {
            format == ApiFormat.ANTHROPIC_MESSAGES -> EXPLICIT
            format == ApiFormat.OPENAI_CHAT && model.startsWith("anthropic/") -> EXPLICIT
            format == ApiFormat.ON_DEVICE -> NONE
            else -> AUTOMATIC
        }
    }
}

/** Who compacts the chat. */
@Serializable
enum class CompactionKind {
    NONE,

    /** Locus summarises with its own request ([dev.locus.core.agent.Compaction]), as on every provider but Anthropic's newer models. */
    LOCUS,

    /** Anthropic summarises inside the request at an input-token trigger, billed in `usage.iterations`. */
    SERVER,
}

/**
 * Cold-cache clearing of old tool results (docs/design/cold-cache-clearing.md). The numbers default to
 * [ColdClearing]'s, so "enabled" is what the app does; change one to price a different rule.
 */
@Serializable
data class ClearingSetting(
    val enabled: Boolean = true,
    val coldAfterMs: Long = ColdClearing.COLD_AFTER_MS,
    val keepRecent: Int = ColdClearing.KEEP_RECENT,
    val minResultChars: Int = ColdClearing.MIN_RESULT_CHARS,
    val minClearedTokens: Int = ColdClearing.MIN_CLEARED_TOKENS,
) {
    /** The rule [ColdClearing] applies for [format]: null when off, and always null on Anthropic and Responses ([ColdClearing.policyFor]). */
    fun policyFor(format: ApiFormat): ColdClearing.Policy? = ColdClearing.policyFor(format, enabled)?.copy(
        coldAfterMs = coldAfterMs, keepRecent = keepRecent, minResultChars = minResultChars, minClearedTokens = minClearedTokens,
    )
}

/**
 * What to price a sequence under. The defaults are what Locus does today for a main chat; each field is one
 * thing a developer might want to change.
 */
@Serializable
data class CachePolicy(
    val label: String,
    /** Null: from the sequence's format and model ([CacheMode.of]). */
    val mode: CacheMode? = null,
    /** How long the conversation breakpoint is kept ([EXPLICIT] only). Today: an hour in a main chat, 5 minutes elsewhere. */
    val conversationTtl: CacheTtl = CacheTtl.ONE_HOUR,
    /** The frozen prefix's breakpoint ([EXPLICIT] only): an hour on Anthropic's own API. */
    val prefixTtl: CacheTtl = CacheTtl.ONE_HOUR,
    /** Anthropic's own API takes both lifetimes; a gateway or OpenRouter only gets the default, 5 minutes. */
    val firstParty: Boolean = true,
    /** How long an [AUTOMATIC] provider keeps a prefix. OpenAI: "at least 30 minutes" on GPT-5.6 and later (cold-cache-clearing.md). */
    val automaticIdleMs: Long = 30L * 60_000,
    /** Whether an [AUTOMATIC] provider bills cache writes (OpenAI from GPT-5.6, at 1.25x for 30 minutes); false: reads only. */
    val automaticWritesBilled: Boolean = false,
    /** Null: [CompactionKind.SERVER] where Anthropic offers it, else [CompactionKind.LOCUS] (and none on-device). */
    val compaction: CompactionKind? = null,
    /** Locus compaction: the share of the window to compact at ([dev.locus.core.agent.Compaction.THRESHOLD]). */
    val compactionThreshold: Double = 0.8,
    /** Compact below the model's price step ([dev.locus.core.model.PriceSteps]); false prices a chat that crosses the step. */
    val compactBelowPriceStep: Boolean = true,
    /** Compact at exactly this many prompt tokens instead (Locus: the last request's context; server: the request's input). */
    val compactionLimitTokens: Int? = null,
    /** How long a summary is. Null: [SimSequence.observedSummaryTokens], else [DEFAULT_SUMMARY_TOKENS]. */
    val summaryTokens: Int? = null,
    val coldClearing: ClearingSetting = ClearingSetting(),
) {
    companion object {
        /** cost.md, "Sample: a long Haiku 5.5 chat": a server summary "writes 4,000 tokens". */
        const val DEFAULT_SUMMARY_TOKENS = 4_000

        /** Anthropic: threshold compaction's default `trigger` is 150,000 input tokens (cost.md, "Compacting below a price step"). */
        const val SERVER_DEFAULT_TRIGGER = 150_000

        /**
         * What Locus does today for a chat on [model] in [format] ([AgentRunner], [AnthropicChatModel]): a main
         * chat keeps the conversation an hour on Anthropic's own API, compacts on Anthropic's server where the
         * model supports it and at 80% of the window (or below the price step) everywhere else, and clears old tool
         * results after a day's pause unless the provider is Anthropic or Responses.
         */
        fun today(
            format: ApiFormat,
            model: String,
            firstParty: Boolean = true,
            label: String = "Locus today",
        ): CachePolicy = CachePolicy(
            label = label,
            firstParty = firstParty,
            conversationTtl = if (firstParty) CacheTtl.ONE_HOUR else CacheTtl.FIVE_MINUTES,
            prefixTtl = if (firstParty) CacheTtl.ONE_HOUR else CacheTtl.FIVE_MINUTES,
            compaction = defaultCompaction(format, model),
        )

        /** Who compacts when a policy doesn't say. */
        fun defaultCompaction(format: ApiFormat, model: String): CompactionKind = when {
            format == ApiFormat.ON_DEVICE -> CompactionKind.NONE
            format == ApiFormat.ANTHROPIC_MESSAGES && AnthropicChatModel.compactsOnServer(model) -> CompactionKind.SERVER
            else -> CompactionKind.LOCUS
        }
    }
}

/** What one simulated request was for. */
enum class SimKind { REQUEST, SUMMARY }

/** One simulated request: its prompt by segment, the usage it would report, and what it costs. */
data class SimRequest(
    val index: Int,
    val kind: SimKind,
    val sentAtMs: Long,
    val prefixTokens: Int,
    val historyTokens: Int,
    val newTokens: Int,
    val promptTokens: Int,
    val outputTokens: Int,
    /** What the provider would report; priced with [ModelPrice.cost] like any stored usage. */
    val usage: TokenUsage,
    /** The request's own counts. A server-side summary inside it is [serverCompactionCost]. */
    val cost: CostBreakdown,
    val serverCompactionCost: CostBreakdown? = null,
    val costUsd: Double,
    /** Old tool results this request's view had cleared. */
    val clearedResults: Int = 0,
    /** The prompt is longer than the model's window: the real request would have failed or compacted. */
    val overWindow: Boolean = false,
)

/** Totals of a [SimRun], by the parts the roadmap names. All dollars. */
data class SimTotals(
    val costUsd: Double,
    val uncachedInputUsd: Double,
    val cacheReadUsd: Double,
    val cacheWriteUsd: Double,
    val outputUsd: Double,
    /** Everything compaction cost: Locus's own summary requests, and Anthropic's server-side summaries. */
    val compactionUsd: Double,
    val requests: Int,
    val summaries: Int,
    val readTokens: Long,
    val writeTokens: Long,
    val freshTokens: Long,
    val outputTokens: Long,
    val requestsOverWindow: Int,
)

/** The sequence priced under one [policy]. */
data class SimRun(
    val policy: CachePolicy,
    /** Resolved choices the table should state (a policy field that doesn't apply to the format, and why). */
    val notes: List<String>,
    val requests: List<SimRequest>,
    val totals: SimTotals,
)
