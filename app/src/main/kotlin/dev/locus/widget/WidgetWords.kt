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

    /** "Next: Evening recap · 18:00"; "Next task: 18:00" without a title. */
    fun next(res: Resources, next: WidgetNext, time: String): String =
        next.title?.let { res.getString(R.string.widget_next, it, time) } ?: res.getString(R.string.widget_next_untitled, time)

    /** How far a time is from now decides how it's written: a time, a weekday and time, or a date. */
    enum class When { TIME, WEEKDAY_TIME, DATE }

    /** Within the same day (or, ahead, the next 24 hours): the time; within a week: weekday and time; else the date. */
    fun whenOf(at: Long, now: Long, zone: ZoneId): When {
        val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val days = kotlin.math.abs(ChronoUnit.DAYS.between(today, day))
        return when {
            day == today || (at > now && at - now < DAY_MS) -> When.TIME
            days < WEEK_DAYS -> When.WEEKDAY_TIME
            else -> When.DATE
        }
    }

    /**
     * [at] written for the widget ([whenOf]): "7:02" or "18:00" as the phone's 12/24-hour setting says
     * ([is24Hour]), "Mon 07:00", or "Nov 21".
     */
    fun time(res: Resources, at: Long, now: Long, zone: ZoneId, is24Hour: Boolean): String {
        val locale = res.locale
        val clock = if (is24Hour) "Hm" else "hm"
        val skeleton = when (whenOf(at, now, zone)) {
            When.TIME -> clock
            When.WEEKDAY_TIME -> "EEE$clock"
            When.DATE -> "MMMd"
        }
        val format = DateFormat.getInstanceForSkeleton(skeleton, locale)
        format.timeZone = android.icu.util.TimeZone.getTimeZone(zone.id)
        format.setContext(DisplayContext.CAPITALIZATION_FOR_STANDALONE)
        return format.format(Date(at))
    }

    private const val DAY_MS = 24 * 60 * 60 * 1000L
    private const val WEEK_DAYS = 7
}
