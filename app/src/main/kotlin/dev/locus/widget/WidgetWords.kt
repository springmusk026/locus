package dev.locus.widget

import android.content.res.Resources
import android.icu.text.DateFormat
import android.icu.text.DisplayContext
import dev.locus.R
import dev.locus.core.data.widget.WidgetNext
import dev.locus.core.data.widget.WidgetRun
import dev.locus.core.data.widget.WidgetRunResult
import dev.locus.core.data.widget.WidgetSays
import dev.locus.core.designsystem.localizedToolActivity
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale

/**
 * The status widget's words in the app's language, from core/data's figures (docs/design/android-bridge.md
 * and localization.md): the status line, the latest and next scheduled runs, and their times, which follow
 * the language and the phone's 12/24-hour setting ("18:00", "jue 7:02"). The usage widget's are [UsageWords].
 */
internal object WidgetWords {
    private val Resources.locale: Locale get() = configuration.locales[0]

    /** "Idle", "Working: running a command", "1 approval, 2 questions waiting"… */
    fun status(res: Resources, says: WidgetSays): String = when (says) {
        WidgetSays.Idle -> res.getString(R.string.widget_idle)
        WidgetSays.BudgetPaused -> res.getString(R.string.widget_budget_paused)
        WidgetSays.Scheduled -> res.getString(R.string.widget_scheduled)
        is WidgetSays.Helpers -> res.getString(R.string.widget_working_on, res.getQuantityString(R.plurals.widget_helpers, says.count, says.count))
        is WidgetSays.Working -> says.tool?.let { tool ->
            res.getString(R.string.widget_working_on, localizedToolActivity(res, tool).replaceFirstChar { it.lowercase(res.locale) })
        } ?: res.getString(R.string.widget_working)
        is WidgetSays.Waiting -> waiting(res, says)
    }

    private fun waiting(res: Resources, says: WidgetSays.Waiting): String {
        val approvals = res.getQuantityString(R.plurals.widget_approvals, says.approvals, says.approvals)
        val questions = res.getQuantityString(R.plurals.widget_questions, says.questions, says.questions)
        return when {
            says.approvals > 0 && says.questions > 0 -> res.getString(R.string.widget_waiting_both, approvals, questions)
            says.approvals > 0 -> res.getString(R.string.widget_waiting, approvals)
            else -> res.getString(R.string.widget_waiting, questions)
        }
    }

    /** "Morning digest · 7:02"; "Scheduled task · 7:02" without a title (Lock Locus on, or none left after cleaning). */
    fun runLabel(res: Resources, run: WidgetRun, time: String): String =
        res.getString(R.string.widget_run_label, run.title ?: res.getString(R.string.widget_run_untitled), time)

    /** The run's answer, or what happened when there's none to show: "Failed", "Stopped", "Done", "Open Locus to read it." */
    fun runText(res: Resources, run: WidgetRun): String = run.text ?: res.getString(
        when {
            run.result == WidgetRunResult.FAILED -> R.string.widget_run_failed
            run.result == WidgetRunResult.STOPPED -> R.string.widget_run_stopped
            run.hidden -> R.string.widget_run_hidden
            else -> R.string.widget_run_done
        },
    )

    /**
     * "Next: Evening recap · 18:00"; "Next task: 18:00" without a title; "… · tomorrow 2:46 PM or later" when
     * the time is only the earliest Android may run it ([WidgetNext.earliest]).
     */
    fun next(res: Resources, next: WidgetNext, time: String): String {
        val shown = if (next.earliest) res.getString(R.string.widget_time_or_later, time) else time
        return next.title?.let { res.getString(R.string.widget_next, it, shown) } ?: res.getString(R.string.widget_next_untitled, shown)
    }

    /** How far a time is from now decides how it's written: a time, a weekday and time, or a date. */
    enum class When { TIME, TOMORROW, YESTERDAY, WEEKDAY_TIME, DATE }

    /** Today: the time; the day after or before: "tomorrow" or "yesterday" and the time; within a week: weekday and time; else the date. */
    fun whenOf(at: Long, now: Long, zone: ZoneId): When {
        val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val days = ChronoUnit.DAYS.between(today, day)
        return when {
            days == 0L -> When.TIME
            days == 1L -> When.TOMORROW
            days == -1L -> When.YESTERDAY
            kotlin.math.abs(days) < WEEK_DAYS -> When.WEEKDAY_TIME
            else -> When.DATE
        }
    }

    /**
     * [at] written for the widget ([whenOf]): "7:02 AM" or "18:00" as the phone's 12/24-hour setting says
     * ([is24Hour]), "tomorrow 7:00 AM", "yesterday 2:46 PM", "Mon 07:00", or "Nov 21". The widget is
     * redrawn at local midnight, so "tomorrow" never goes stale.
     */
    fun time(res: Resources, at: Long, now: Long, zone: ZoneId, is24Hour: Boolean): String {
        val clock = if (is24Hour) "Hm" else "hm"
        val style = whenOf(at, now, zone)
        val skeleton = when (style) {
            When.TIME, When.TOMORROW, When.YESTERDAY -> clock
            When.WEEKDAY_TIME -> "EEE$clock"
            When.DATE -> "MMMd"
        }
        val format = DateFormat.getInstanceForSkeleton(skeleton, res.locale)
        format.timeZone = android.icu.util.TimeZone.getTimeZone(zone.id)
        format.setContext(DisplayContext.CAPITALIZATION_FOR_STANDALONE)
        val text = format.format(Date(at))
        return when (style) {
            When.TOMORROW -> res.getString(R.string.widget_time_tomorrow, text)
            When.YESTERDAY -> res.getString(R.string.widget_time_yesterday, text)
            else -> text
        }
    }

    private const val WEEK_DAYS = 7
}
