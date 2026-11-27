package dev.locus.widget

import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.WidgetSizes

/**
 * What fits in a widget of a given size (docs/design/android-bridge.md, "Sizes"). RemoteViews can't
 * measure text, so each part has a height from [WidgetSizes] (times the font scale for text) and parts
 * are added, most useful first, while they fit. Pure, so each size's content is unit-tested.
 */
internal object WidgetLayout {
    /** At most this many lines of a run's answer, however tall the widget. */
    const val MAX_RUN_LINES = 8

    /** At most this many lines of the running task's budget. */
    private const val MAX_TASK_LINES = 2

    /**
     * The status widget: whether the next run's line ([next]) and the latest run's label ([runLabel],
     * "Morning digest · 7:02") show, and how many lines of the run's answer ([runLines], 0 for none).
     */
    data class Status(val next: Boolean, val runLabel: Boolean, val runLines: Int)

    /**
     * The status widget at [heightDp]: the status line and Ask Locus always; then the answer's first line,
     * the next run, the run's label, then more of the answer while it fits.
     */
    fun status(heightDp: Float, fontScale: Float, hasRun: Boolean, hasNext: Boolean): Status {
        val room = Room(heightDp - 2 * WidgetSizes.padding.value - WidgetSizes.statusLine.value * fontScale - WidgetSizes.button.value - Spacing.sm.value)
        val line = WidgetSizes.line.value * fontScale
        var lines = if (hasRun && room.take(line + Spacing.xs.value)) 1 else 0
        val next = hasNext && room.take(line + Spacing.xxs.value)
        val label = lines > 0 && room.take(line)
        while (lines in 1 until MAX_RUN_LINES && room.take(line)) lines++
        return Status(next, label, lines)
    }

    /**
     * The usage widget: whether its [note] ("Today"), the [tokens] under a spend, the seven-day [strip]
     * with its total, and today's [requests] show, and how many lines the running task's budget gets.
     */
    data class Usage(val note: Boolean, val tokens: Boolean, val strip: Boolean, val requests: Boolean, val taskLines: Int)

    /**
     * The usage widget at [heightDp]: the figure always; then its note, the tokens, the task's first line,
     * the week, today's requests and the task's second line while they fit.
     */
    fun usage(heightDp: Float, fontScale: Float, hasTokens: Boolean, hasWeek: Boolean, hasTask: Boolean): Usage {
        val room = Room(heightDp - 2 * WidgetSizes.padding.value - WidgetSizes.headlineLine.value * fontScale)
        val line = WidgetSizes.line.value * fontScale
        val note = room.take(line + Spacing.xxs.value)
        val tokens = hasTokens && room.take(line + Spacing.xxs.value)
        var task = if (hasTask && room.take(line + Spacing.sm.value)) 1 else 0
        val strip = hasWeek && room.take(WidgetSizes.stripHeight.value + Spacing.sm.value + line + Spacing.xxs.value)
        val requests = room.take(line + Spacing.xxs.value)
        if (task in 1 until MAX_TASK_LINES && room.take(line)) task++
        return Usage(note, tokens, strip, requests, task)
    }

    /** The height left, in dp; [take] spends [cost] of it when it's there. */
    private class Room(private var left: Float) {
        fun take(cost: Float): Boolean = (left >= cost).also { if (it) left -= cost }
    }
}
