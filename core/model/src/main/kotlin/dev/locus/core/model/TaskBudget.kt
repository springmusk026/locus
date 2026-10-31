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
         * is the whole prompt, so the cached part is taken out of it first.
         */
        fun budgetTokens(usage: TokenUsage): Long {
            val read = usage.cacheRead.coerceAtLeast(0).toLong()
            val fresh = (usage.input.toLong() - read).coerceAtLeast(0)
            return fresh + usage.output.coerceAtLeast(0) + ceil(read * CACHE_READ_WEIGHT).toLong()
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
 * `cost.cache_read`, `cost.cache_write`, and `cost.context_over_200k` for models that charge more
 * past 200K tokens of prompt). Missing cache prices are charged at the input price.
 */
@Serializable
data class ModelPrice(
    val input: Double,
    val output: Double,
    val cacheRead: Double? = null,
    val cacheWrite: Double? = null,
    /** The prices for a prompt over 200K tokens, when they differ. */
    val over200k: ModelPrice? = null,
) {
    /** The estimated cost of one request. [TokenUsage.input] is the whole prompt, cached parts included. */
    fun cost(usage: TokenUsage): Double {
        val p = over200k?.takeIf { usage.input > LONG_CONTEXT } ?: this
        val read = usage.cacheRead.coerceAtLeast(0).toDouble()
        val write = usage.cacheWrite.coerceAtLeast(0).toDouble()
        val fresh = (usage.input - read - write).coerceAtLeast(0.0)
        return (fresh * p.input + read * (p.cacheRead ?: p.input) + write * (p.cacheWrite ?: p.input) + usage.output.coerceAtLeast(0) * p.output) / 1_000_000.0
    }

    private companion object {
        const val LONG_CONTEXT = 200_000
    }
}
