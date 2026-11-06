package dev.locus.core.model

import kotlinx.serialization.Serializable
import kotlin.math.ceil

/**
 * Limits on one task: everything one user message sets off, the helpers it starts included. A null
 * limit is off; all three null is no budget at all (the default, so nothing stops by surprise).
 *
 * Reaching a limit pauses the task at the next step boundary (never in the middle of a tool call) and
 * asks the user; Continue grants one more increment of the same size.
 */
@Serializable
data class TaskBudget(
    /** Budget tokens ([budgetTokens]) summed over every model request of the task. */
    val tokens: Long? = null,
    /** Estimated cost in US dollars, from models.dev prices; it only applies when the chat model's prices are known. */
    val costUsd: Double? = null,
    /** Wall-clock minutes, not counting the time the task waits for the user. */
    val minutes: Int? = null,
) {
    val isOff: Boolean get() = tokens == null && costUsd == null && minutes == null

    companion object {
        val OFF = TaskBudget()

        /**
         * Cache reads count at a tenth. Anthropic bills a cache read at 0.1x the input price, and OpenAI,
         * Google and DeepSeek at 0.1x to 0.25x (models.dev `cost.cache_read`), so a long agent loop
         * re-reading the same cached prefix every step isn't counted as if it were all new input, while
         * it still isn't free (excluding reads would let a huge cached context look like nothing).
         */
        const val CACHE_READ_WEIGHT = 0.1

        /**
         * What one request costs against a token budget: uncached input (cache writes included) and
         * output (reasoning included) in full, plus cache reads at [CACHE_READ_WEIGHT]. [TokenUsage.input]
         * is the whole prompt, so the cached part is taken out of it first. A summary the provider wrote
         * inside the request ([TokenUsage.serverCompaction]) counts the same way.
         */
        fun budgetTokens(usage: TokenUsage): Long = usage.billedParts.sumOf { part ->
            val read = part.cacheRead.coerceAtLeast(0).toLong()
            val fresh = (part.input.toLong() - read).coerceAtLeast(0)
            fresh + part.output.coerceAtLeast(0) + ceil(read * CACHE_READ_WEIGHT).toLong()
        }

        // ---- Per-chat override, kept in the chat's restriction set --------------------------------

        private const val PREFIX = "budget:"
        private const val OFF_KEY = "budget:off"
        private const val TOKENS = "budget:tokens:"
        private const val USD = "budget:usd:"
        private const val MINUTES = "budget:minutes:"

        /** Whether [key] of a chat's restriction set is a budget entry rather than a tool switch. */
        fun isBudgetKey(key: String) = key.startsWith(PREFIX)

        /**
         * The chat's own budget, or null when it uses the default from Settings. Stored as keys in the
         * conversation's restriction set (the `disabledTools` column), like the per-chat mode, so no
         * schema change was needed; "budget:off" is an explicit "no budget for this chat".
         */
        fun overrideIn(restrictions: Set<String>): TaskBudget? {
            val keys = restrictions.filter(::isBudgetKey)
            if (keys.isEmpty()) return null
            if (OFF_KEY in keys) return OFF
            fun value(prefix: String) = keys.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)
            return TaskBudget(
                tokens = value(TOKENS)?.toLongOrNull()?.takeIf { it > 0 },
                costUsd = value(USD)?.toDoubleOrNull()?.takeIf { it > 0 },
                minutes = value(MINUTES)?.toIntOrNull()?.takeIf { it > 0 },
            )
        }

        /** [restrictions] with [budget] as the chat's own (null: back to the default from Settings). */
        fun withOverride(restrictions: Set<String>, budget: TaskBudget?): Set<String> {
            val rest = restrictions.filterNot(::isBudgetKey).toSet()
            if (budget == null) return rest
            if (budget.isOff) return rest + OFF_KEY
            return rest + listOfNotNull(
                budget.tokens?.let { "$TOKENS$it" },
                budget.costUsd?.let { "$USD$it" },
                budget.minutes?.let { "$MINUTES$it" },
            )
        }

        /** The budget a chat runs with: its own, else the default. */
        fun effective(default: TaskBudget, restrictions: Set<String>): TaskBudget = overrideIn(restrictions) ?: default
    }
}

/**
 * A model's prices in US dollars per million tokens, from models.dev (`cost.input`, `cost.output`,
 * `cost.cache_read`, `cost.cache_write`). Models priced by prompt length list each step in
 * `cost.tiers` (`{"tier": {"type": "context", "size": 100000}, "input": …}`: the prices for a prompt
 * over `size` tokens), read into [tiers]; older entries only have `cost.context_over_200k`, read into
 * [over200k]. Missing cache prices are charged at the input price.
 */
@Serializable
data class ModelPrice(
    val input: Double,
    val output: Double,
    val cacheRead: Double? = null,
    val cacheWrite: Double? = null,
    /** The prices for a prompt over 200K tokens, when they differ and no [tiers] are listed. */
    val over200k: ModelPrice? = null,
    /** Prices by prompt length, lowest step first; empty when the price doesn't depend on it. */
    val tiers: List<PriceTier> = emptyList(),
) {
    /**
     * Where the price first goes up, in prompt tokens (a request whose prompt is over it pays more), or
     * null when it never does. Anthropic: "A request's prompt length counts all of its input tokens,
     * including cache reads and cache writes" (pricing, long context pricing).
     */
    val priceStep: Int? get() = tiers.minOfOrNull { it.above } ?: over200k?.let { LONG_CONTEXT }

    /** The prices one request pays, by the length of its whole prompt. */
    fun forPrompt(promptTokens: Int): ModelPrice =
        tiers.filter { promptTokens > it.above }.maxByOrNull { it.above }?.price
            ?: over200k?.takeIf { tiers.isEmpty() && promptTokens > LONG_CONTEXT }
            ?: this

    /**
     * The estimated cost of one request. [TokenUsage.input] is the whole prompt, cached parts included,
     * and picks the price step. models.dev's cache-write price is the short one (Anthropic's 5 minutes,
     * OpenAI's 30); writes kept for an hour ([TokenUsage.cacheWrite1h], Anthropic) cost 2x the input price
     * and OpenAI's 12-hour ones ([TokenUsage.cacheWrite12h]) 2.5x. A server-side summary billed with the
     * request ([TokenUsage.serverCompaction]) is priced as a request of its own, at its own step.
     */
    fun cost(usage: TokenUsage): Double = usage.billedParts.sumOf(::costOfOne)

    private fun costOfOne(usage: TokenUsage): Double {
        val p = forPrompt(usage.input)
        val read = usage.cacheRead.coerceAtLeast(0).toDouble()
        val write = usage.cacheWrite.coerceAtLeast(0).toDouble()
        val write1h = usage.cacheWrite1h.coerceAtLeast(0).toDouble().coerceAtMost(write)
        val write12h = usage.cacheWrite12h.coerceAtLeast(0).toDouble().coerceAtMost(write - write1h)
        val fresh = (usage.input - read - write).coerceAtLeast(0.0)
        return (
            fresh * p.input + read * (p.cacheRead ?: p.input) + (write - write1h - write12h) * (p.cacheWrite ?: p.input) +
                write1h * p.input * ONE_HOUR_WRITE + write12h * p.input * TWELVE_HOUR_WRITE + usage.output.coerceAtLeast(0) * p.output
            ) / 1_000_000.0
    }

    private companion object {
        const val LONG_CONTEXT = 200_000

        /** A 1-hour cache write's price, as a multiple of base input (Anthropic prompt caching). */
        const val ONE_HOUR_WRITE = 2.0

        /** A 12-hour cache write's price, as a multiple of base input (OpenAI prompt caching, GPT-5.6 and later). */
        const val TWELVE_HOUR_WRITE = 2.5
    }
}

/** The prices for a prompt of more than [above] tokens. */
@Serializable
data class PriceTier(val above: Int, val price: ModelPrice)
