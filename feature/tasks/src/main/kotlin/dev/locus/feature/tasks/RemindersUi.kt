package dev.locus.feature.tasks

import android.content.res.Resources
import android.text.format.DateFormat
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.DateSkeleton
import dev.locus.core.designsystem.formatDateTime
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.Insets
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.Spacing
import dev.locus.core.model.Reminder
import java.time.Instant
import java.time.ZoneId
import androidx.compose.foundation.layout.padding

/**
 * Reminders set with remind_me, in Tasks (docs/design/phone-integrations.md): those to come with Cancel,
 * then those shown in the last day with Clear. Nothing is shown when there are none.
 */
internal fun LazyListScope.remindersSection(reminders: List<Reminder>, now: Long, zone: ZoneId, exact: Boolean, onCancel: (Reminder) -> Unit) {
    if (reminders.isEmpty()) return
    item { SectionLabel(stringResource(R.string.tasks_reminders_title)) }
    item {
        CardGroup {
            reminders.forEachIndexed { i, r ->
                if (i > 0) GroupDivider(inset = Insets.iconRow)
                ListRow(
                    title = r.text,
                    subtitle = reminderSubtitle(r, now, zone),
                    titleMaxLines = 3,
                    subtitleMaxLines = 2,
                    leading = { IconTile(if (r.upcoming) LocusIcons.Clock else LocusIcons.Check) },
                    // An icon, not a text button: it doesn't grow with the font and squeeze the reminder's words.
                    trailing = {
                        IconButton(onClick = { onCancel(r) }) {
                            Icon(LocusIcons.Close, contentDescription = stringResource(if (r.upcoming) R.string.tasks_reminders_cancel else R.string.tasks_reminders_clear, r.text))
                        }
                    },
                )
            }
        }
    }
    item {
        SectionNote(
            stringResource(if (exact) R.string.tasks_reminders_note else R.string.tasks_reminders_note_inexact),
            Modifier.padding(top = Spacing.sm),
        )
    }
}

/** "Today 17:00 · in 2 h", "Tomorrow 09:00", "Tue 10 Nov, 09:00 · snoozed"; "Shown at 17:00" once it has fired. */
@Composable
internal fun reminderSubtitle(r: Reminder, now: Long, zone: ZoneId): String {
    val context = LocalContext.current
    // The composition's resources, so the words and the date follow a language change.
    return reminderSubtitle(LocalResources.current, r, now, zone, DateFormat.is24HourFormat(context))
}

/**
 * [reminderSubtitle] from [resources]: the date and time the way the app's language writes them, on
 * the phone's 12/24-hour clock ([is24Hour]; null keeps the language's own).
 */
internal fun reminderSubtitle(resources: Resources, r: Reminder, now: Long, zone: ZoneId, is24Hour: Boolean?): String {
    val locale = resources.configuration.locales[0]
    fun clock(millis: Long) = formatDateTime(millis, DateSkeleton.TIME, locale, zone, is24Hour)
    if (!r.upcoming) return resources.getString(R.string.tasks_reminders_shown, clock(r.shownAt ?: r.at))
    val at = Instant.ofEpochMilli(r.at).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val day = when (at.toLocalDate()) {
        today -> resources.getString(R.string.tasks_reminders_today, clock(r.at))
        today.plusDays(1) -> resources.getString(R.string.tasks_reminders_tomorrow, clock(r.at))
        else -> formatDateTime(r.at, DateSkeleton.WEEKDAY_DAY_MONTH_TIME, locale, zone, is24Hour)
    }
    val minutes = (r.at - now) / 60_000
    val until = when {
        minutes < 1 -> resources.getString(R.string.tasks_reminders_due_now)
        minutes < 60 -> resources.getString(R.string.tasks_reminders_in_minutes, minutes)
        minutes < 24 * 60 -> resources.getString(R.string.tasks_reminders_in_hours, minutes / 60)
        else -> null
    }
    return listOfNotNull(day, until, resources.getString(R.string.tasks_reminders_snoozed).takeIf { r.snoozes > 0 }).joinToString(" · ")
}
