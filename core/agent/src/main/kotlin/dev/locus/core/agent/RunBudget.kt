package dev.locus.core.agent

import dev.locus.core.model.ModelPrice
import dev.locus.core.model.TaskBudget
import dev.locus.core.model.TokenUsage

/** Which of a [TaskBudget]'s limits a task reached. */
enum class BudgetLimit { TOKENS, COST, TIME }

/** A task's spend so far against its limits, for the chat ("38k / 200k") and the pause notice. */
data class BudgetMeter(
    val tokens: Long,
    /** The current token allowance (the budget, plus an increment per Continue); null when there's no token limit. */
    val tokenLimit: Long?,
    /** Estimated cost so far; null when the chat model's prices are unknown. */
    val costUsd: Double?,
    val costLimit: Double?,
    val elapsedMs: Long,
    val timeLimitMs: Long?,
    /** Why a cost limit set for this task doesn't apply, or null. */
    val costUnavailable: String? = null,
    /** Some requests (a helper on another model) had no price, so the cost is a lower bound. */
    val costPartial: Boolean = false,
)

/**
 * Accounting for one task: one user message's turn, including the helpers it starts, which share
 * this object through [ToolCallContext.budget]. Every model request of the task is [record]ed by the
 * [AgentLoop] that made it; [exceeded] is checked only between steps, so a task never stops in the
 * middle of a tool call.
 *
 * Tokens count as [TaskBudget.budgetTokens] (cache reads at a tenth). Cost is estimated from [price],
 * and the cost limit applies only when [costKnown] (the chat model's prices are known). Time is wall
 * clock since the task started, minus the time spent [waitingForUser]. Thread-safe: helpers record
 * from their own coroutines.
 */
class RunBudget(
    val budget: TaskBudget,
    /** Prices of the model a request went to (provider id, model id), or null when unknown. */
    private val price: (providerId: String?, model: String?) -> ModelPrice? = { _, _ -> null },
    /** Whether the cost limit can apply: the chat model's prices are known. */
    val costKnown: Boolean = false,
    /** Shown when a cost limit is set but [costKnown] is false. */
    val costUnavailable: String? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Called after every change (a request recorded, Continue, a wait for the user ending). */
    private val onChange: (RunBudget) -> Unit = {},
) {
    private val lock = Any()
    private val startedAt = clock()
    private var tokens = 0L
    private var cost = 0.0
    private var costPartial = false
    private var extraTokens = 0L
    private var extraCost = 0.0
    private var extraMs = 0L
    private var waits = 0
    private var waitStartedAt = 0L
    private var waitedMs = 0L

    /** Whether any limit is set; with none, the task runs exactly as without a budget. */
    val active: Boolean get() = !budget.isOff

    /** The cost limit, when it applies. */
    private val costLimit: Double? get() = budget.costUsd?.takeIf { costKnown }

    /** Adds one model request of this task (the chat's own, or a helper's). */
    fun record(usage: TokenUsage?, providerId: String?, model: String?) {
        usage ?: return
        val p = price(providerId, model)
        synchronized(lock) {
            tokens += TaskBudget.budgetTokens(usage)
            if (p != null) cost += p.cost(usage) else costPartial = true
        }
        onChange(this)
    }

    /** The limit this task has reached, or null. Tokens are checked first, then cost, then time. */
    fun exceeded(): BudgetLimit? = synchronized(lock) {
        val tokenLimit = budget.tokens
        val dollars = costLimit
        val minutes = budget.minutes
        when {
            tokenLimit != null && tokens >= tokenLimit + extraTokens -> BudgetLimit.TOKENS
            dollars != null && cost >= dollars + extraCost -> BudgetLimit.COST
            minutes != null && elapsedLocked() >= minutes * MINUTE + extraMs -> BudgetLimit.TIME
            else -> null
        }
    }

    /** The user chose Continue: [limit] grows by one more increment of the budget's own size. */
    fun extend(limit: BudgetLimit) {
        synchronized(lock) {
            when (limit) {
                BudgetLimit.TOKENS -> budget.tokens?.let { extraTokens += it }
                BudgetLimit.COST -> budget.costUsd?.let { extraCost += it }
                BudgetLimit.TIME -> budget.minutes?.let { extraMs += it * MINUTE }
            }
        }
        onChange(this)
    }

    /**
     * Runs [block] (waiting for an approval, an answer or the budget prompt) without counting its time
     * against the time limit. Waits may overlap (a helper and the chat asking at once); time counts
     * again once none is open.
     */
    suspend fun <T> waitingForUser(block: suspend () -> T): T {
        synchronized(lock) { if (waits++ == 0) waitStartedAt = clock() }
        try {
            return block()
        } finally {
            synchronized(lock) { if (--waits == 0) waitedMs += clock() - waitStartedAt }
            onChange(this)
        }
    }

    private fun elapsedLocked(): Long {
        val open = if (waits > 0) clock() - waitStartedAt else 0L
        return (clock() - startedAt - waitedMs - open).coerceAtLeast(0)
    }

    fun meter(): BudgetMeter = synchronized(lock) {
        BudgetMeter(
            tokens = tokens,
            tokenLimit = budget.tokens?.let { it + extraTokens },
            costUsd = cost.takeIf { costKnown },
            costLimit = costLimit?.let { it + extraCost },
            elapsedMs = elapsedLocked(),
            timeLimitMs = budget.minutes?.let { it * MINUTE + extraMs },
            costUnavailable = costUnavailable.takeIf { budget.costUsd != null && !costKnown },
            costPartial = costPartial && costKnown,
        )
    }

    /** "Paused: reached the 200k-token budget for this task." */
    fun pauseMessage(limit: BudgetLimit): String = "Paused: reached the ${describe(limit)} budget for this task."

    /** "Stopped: reached the 200k-token budget for this task." */
    fun stopMessage(limit: BudgetLimit): String = "Stopped: reached the ${describe(limit)} budget for this task."

    /** "200k-token", "$2.00", "30-minute": the size of one increment of [limit]. */
    fun describe(limit: BudgetLimit): String = when (limit) {
        BudgetLimit.TOKENS -> "${compactTokens(budget.tokens ?: 0)}-token"
        BudgetLimit.COST -> "${usd(budget.costUsd ?: 0.0)} cost"
        BudgetLimit.TIME -> "${budget.minutes ?: 0}-minute"
    }

    companion object {
        const val MINUTE = 60_000L

        /** "950", "38k", "1.2M". */
        fun compactTokens(n: Long): String = when {
            n < 1_000 -> "$n"
            n < 1_000_000 -> if (n < 10_000 && n % 1_000 != 0L) String.format(java.util.Locale.US, "%.1fk", n / 1_000.0) else "${n / 1_000}k"
            else -> String.format(java.util.Locale.US, "%.1fM", n / 1_000_000.0).replace(".0M", "M")
        }

        /** "$0.42", "$12.50"; amounts under a cent show as "<$0.01". */
        fun usd(amount: Double): String = when {
            amount <= 0.0 -> "$0.00"
            amount < 0.01 -> "<$0.01"
            else -> String.format(java.util.Locale.US, "$%.2f", amount)
        }
    }
}

/** Asks the user what to do when a task reached a limit; true to continue (one more increment), false to stop. */
fun interface BudgetGate {
    suspend fun onLimit(budget: RunBudget, limit: BudgetLimit): Boolean
}

/** How a task's budget reads in the chat and in Activity. */
object BudgetText {
    /** The chip beside the context meter: "38k / 200k", else "$0.12 / $1.00", else "4m / 30m". */
    fun chip(m: BudgetMeter): String? = when {
        m.tokenLimit != null -> "${RunBudget.compactTokens(m.tokens)} / ${RunBudget.compactTokens(m.tokenLimit)}"
        m.costLimit != null && m.costUsd != null -> "${RunBudget.usd(m.costUsd)} / ${RunBudget.usd(m.costLimit)}"
        m.timeLimitMs != null -> "${minutes(m.elapsedMs)} / ${minutes(m.timeLimitMs)}"
        else -> null
    }

    /** The chip read aloud: "38k of 200k tokens used", "$0.12 of $1.00 used", "4m of 30m used". */
    fun spokenChip(m: BudgetMeter): String? = when {
        m.tokenLimit != null -> "${RunBudget.compactTokens(m.tokens)} of ${RunBudget.compactTokens(m.tokenLimit)} tokens used"
        m.costLimit != null && m.costUsd != null -> "${RunBudget.usd(m.costUsd)} of ${RunBudget.usd(m.costLimit)} used"
        m.timeLimitMs != null -> "${minutes(m.elapsedMs)} of ${minutes(m.timeLimitMs)} used"
        else -> null
    }

    /** The task's current limits: "200k tokens · $1.00 · 30m"; null when it has none. */
    fun limits(m: BudgetMeter): String? = listOfNotNull(
        m.tokenLimit?.let { "${RunBudget.compactTokens(it)} tokens" },
        m.costLimit?.let { RunBudget.usd(it) },
        m.timeLimitMs?.let { minutes(it) },
    ).takeIf { it.isNotEmpty() }?.joinToString(" · ")

    /** "201k tokens · about $0.84 · 6m 12s". */
    fun used(m: BudgetMeter): String = listOfNotNull(
        "${RunBudget.compactTokens(m.tokens)} tokens",
        m.costUsd?.let { "about ${RunBudget.usd(it)}${if (m.costPartial) " or more" else ""}" },
        duration(m.elapsedMs),
    ).joinToString(" · ")

    /** "6m 12s", "45s", "1h 05m". */
    fun duration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m ${(s % 60).toString().padStart(2, '0')}s"
            else -> "${s / 3600}h ${((s % 3600) / 60).toString().padStart(2, '0')}m"
        }
    }

    private fun minutes(ms: Long) = if (ms < 3_600_000) "${ms / 60_000}m" else "${ms / 3_600_000}h ${((ms % 3_600_000) / 60_000).toString().padStart(2, '0')}m"

    /** The Activity entry's arguments: which event, which limit, and the budget's size. */
    fun entryArgs(budget: dev.locus.core.model.TaskBudget, limit: BudgetLimit?): String = kotlinx.serialization.json.buildJsonObject {
        put("event", kotlinx.serialization.json.JsonPrimitive(if (limit == null) "summary" else "paused"))
        limit?.let { put("limit", kotlinx.serialization.json.JsonPrimitive(it.name.lowercase())) }
        budget.tokens?.let { put("tokens", kotlinx.serialization.json.JsonPrimitive(it)) }
        budget.costUsd?.let { put("cost_usd", kotlinx.serialization.json.JsonPrimitive(it)) }
        budget.minutes?.let { put("minutes", kotlinx.serialization.json.JsonPrimitive(it)) }
    }.toString()
}
