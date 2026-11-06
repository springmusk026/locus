package dev.locus.feature.tasks

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.Modifier
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
    item { SectionLabel("Reminders") }
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
                            Icon(LocusIcons.Close, contentDescription = if (r.upcoming) "Cancel reminder: ${r.text}" else "Clear reminder: ${r.text}")
                        }
                    },
                )
            }
        }
    }
    item {
        SectionNote(
            "A reminder is a notification from Locus; nothing runs when it shows." +
                if (exact) "" else " Without exact timing (Settings → Phone) it can show up to 10 minutes late.",
            Modifier.padding(top = Spacing.sm),
        )
    }
}

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
private val DAY = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ROOT)

/** "Today 17:00 · in 2 h", "Tomorrow 09:00", "Tue 10 Nov, 09:00 · snoozed"; "Shown at 17:00" once it has fired. */
internal fun reminderSubtitle(r: Reminder, now: Long, zone: ZoneId): String {
    if (!r.upcoming) return "Shown at ${Instant.ofEpochMilli(r.shownAt ?: r.at).atZone(zone).format(CLOCK)}"
    val at = Instant.ofEpochMilli(r.at).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val day = when (at.toLocalDate()) {
        today -> "Today ${at.format(CLOCK)}"
        today.plusDays(1) -> "Tomorrow ${at.format(CLOCK)}"
        else -> at.format(DAY)
    }
    val minutes = (r.at - now) / 60_000
    val until = when {
        minutes < 1 -> "due now"
        minutes < 60 -> "in $minutes min"
        minutes < 24 * 60 -> "in ${minutes / 60} h"
        else -> null
    }
    return listOfNotNull(day, until, "snoozed".takeIf { r.snoozes > 0 }).joinToString(" · ")
}
