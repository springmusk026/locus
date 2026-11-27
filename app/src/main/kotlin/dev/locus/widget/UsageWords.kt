package dev.locus.widget

import android.content.res.Resources
import dev.locus.R
import dev.locus.core.data.widget.TaskBudgetFigures
import dev.locus.core.data.widget.UsageFigure
import dev.locus.core.data.widget.UsageNote
import dev.locus.core.designsystem.compactCount
import dev.locus.core.designsystem.usdText

/**
 * The usage widget's words in the app's language, from core/data's figures (docs/design/android-bridge.md,
 * "The usage widget"). Numbers and money follow the language too ("1,2 M tokens", "1,24 US$").
 */
internal object UsageWords {
    /** "Today", "Today · cost unknown", "Today · prices off". */
    fun note(res: Resources, note: UsageNote): String = res.getString(
        when (note) {
            UsageNote.TODAY -> R.string.widget_usage_today
            UsageNote.COST_UNKNOWN -> R.string.widget_usage_cost_unknown
            UsageNote.PRICES_OFF -> R.string.widget_usage_prices_off
        },
    )

    /** "$0.42", "$1.50+", "<$0.01", "1.2M tokens", "No usage yet". */
    fun figure(res: Resources, figure: UsageFigure): String = when (figure) {
        is UsageFigure.Spend -> money(res, figure.usd, figure.floor)
        is UsageFigure.Tokens -> tokens(res, figure.tokens)
        UsageFigure.Nothing -> res.getString(R.string.widget_usage_nothing)
    }

    /** "52.7M tokens". */
    fun tokens(res: Resources, n: Long): String =
        res.getQuantityString(R.plurals.widget_usage_tokens, n.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), compactCount(res, n))

    /** "312 requests". */
    fun requests(res: Resources, n: Int): String = res.getQuantityString(R.plurals.widget_usage_requests, n, n)

    /** "7 days · $140", "7 days · 310M tokens". */
    fun week(res: Resources, total: UsageFigure): String = res.getString(R.string.widget_usage_week, figure(res, total))

    /** "Task: 12k of 50k tokens · $0.08 of $0.50", "Task paused: 50k of 50k tokens". */
    fun task(res: Resources, task: TaskBudgetFigures): String {
        val tokens = task.tokenLimit?.let { res.getString(R.string.widget_usage_of_tokens, compactCount(res, task.tokens), compactCount(res, it)) }
            ?: tokens(res, task.tokens)
        val spent = task.usd?.let { usd ->
            val shown = money(res, usd, task.usdFloor)
            task.usdLimit?.let { res.getString(R.string.widget_usage_of, shown, usdText(res, it)) } ?: shown
        }
        val parts = spent?.let { res.getString(R.string.widget_usage_parts, tokens, it) } ?: tokens
        return res.getString(if (task.paused) R.string.widget_usage_task_paused else R.string.widget_usage_task, parts)
    }

    private fun money(res: Resources, usd: Double, floor: Boolean): String =
        usdText(res, usd).let { if (floor) res.getString(dev.locus.core.designsystem.R.string.designsystem_money_or_more, it) else it }
}
