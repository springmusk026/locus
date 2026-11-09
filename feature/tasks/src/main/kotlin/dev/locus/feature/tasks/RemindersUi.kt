package dev.locus.feature.tasks

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.locus.core.designsystem.CardGroup
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
import java.time.format.DateTimeFormatter
import java.util.Locale
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

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
private val DAY = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ROOT)

/** "Today 17:00 · in 2 h", "Tomorrow 09:00", "Tue 10 Nov, 09:00 · snoozed"; "Shown at 17:00" once it has fired. */
@Composable
internal fun reminderSubtitle(r: Reminder, now: Long, zone: ZoneId): String {
    if (!r.upcoming) return stringResource(R.string.tasks_reminders_shown, Instant.ofEpochMilli(r.shownAt ?: r.at).atZone(zone).format(CLOCK))
    val at = Instant.ofEpochMilli(r.at).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val day = when (at.toLocalDate()) {
        today -> stringResource(R.string.tasks_reminders_today, at.format(CLOCK))
        today.plusDays(1) -> stringResource(R.string.tasks_reminders_tomorrow, at.format(CLOCK))
        else -> at.format(DAY)
    }
    val minutes = (r.at - now) / 60_000
    val until = when {
        minutes < 1 -> stringResource(R.string.tasks_reminders_due_now)
        minutes < 60 -> stringResource(R.string.tasks_reminders_in_minutes, minutes)
        minutes < 24 * 60 -> stringResource(R.string.tasks_reminders_in_hours, minutes / 60)
        else -> null
    }
    return listOfNotNull(day, until, stringResource(R.string.tasks_reminders_snoozed).takeIf { r.snoozes > 0 }).joinToString(" · ")
}
