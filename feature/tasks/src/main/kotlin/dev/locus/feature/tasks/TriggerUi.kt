package dev.locus.feature.tasks

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import dev.locus.core.designsystem.IconSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import dev.locus.core.data.tasks.triggers.TriggerRules
import dev.locus.core.designsystem.Banner
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.ChipFlow
import dev.locus.core.designsystem.ChipVariant
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.InlineSpinner
import dev.locus.core.designsystem.Insets
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusChip
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.SettingsLinkIn
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.SwitchRow
import dev.locus.core.designsystem.TextArea
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.color
import dev.locus.core.designsystem.relativeTime
import dev.locus.core.model.QuietHours
import dev.locus.core.model.ScheduledTask
import dev.locus.core.model.TaskRunOutcome
import dev.locus.core.model.TaskTrigger
import dev.locus.core.model.TriggerDecision
import dev.locus.core.model.TriggerEventRecord
import dev.locus.core.model.TriggerHealth
import dev.locus.core.model.TriggerSource

/** The icon for a trigger's source (the schedule's is the clock). */
internal fun TriggerSource.icon(): ImageVector = when (this) {
    TriggerSource.CALENDAR -> LocusIcons.Calendar
    TriggerSource.NTFY -> LocusIcons.Server
    TriggerSource.MAIL -> LocusIcons.Mail
    TriggerSource.APP -> LocusIcons.Plug
}

/** The source picker's short labels, in [TriggerSource] order. */
internal val SOURCE_LABELS = listOf("Calendar", "ntfy", "Mail", "App")

/** What each source waits for, and how quickly Locus notices (docs/design/event-triggers.md, "Latency"). */
internal fun sourceHelp(source: TriggerSource): String = when (source) {
    TriggerSource.CALENDAR -> "Runs before an entry in your phone's calendar starts. Locus reads the calendar every 15 minutes and plans a run for the right time; " +
        "Android can hold it back a few minutes while the phone is idle."
    TriggerSource.NTFY -> "Runs when a message arrives on an ntfy topic. Anything that can send a web request can post to one (a server, a script, a webhook), " +
        "on ntfy.sh or your own server. Locus checks every 15 minutes, or later while the phone is idle; it keeps no connection open."
    TriggerSource.MAIL -> "Runs when mail arrives in the agent's own mailbox (forward or filter mail to it). Locus looks for new mail every 15 minutes, " +
        "or later while the phone is idle, reading only the sender and subject."
    TriggerSource.APP -> "Runs when another app (Tasker, Automate, a script) sends Locus the event below. It arrives at once; Locus's automation settings and the list below decide who may send it."
}

/** The line under an event-triggered task's name: what it waits for and how its source is. */
internal fun triggerStatusLine(task: ScheduledTask, now: (Long) -> String): String {
    val trigger = task.trigger ?: return ""
    val source = SOURCE_LABELS[trigger.source.ordinal]
    return when {
        !task.enabled -> "$source · paused"
        task.triggerHealth == TriggerHealth.NO_PERMISSION -> "$source · no access"
        task.triggerHealth == TriggerHealth.NOT_SET_UP -> "$source · not set up"
        task.triggerHealth == TriggerHealth.OFFLINE -> "$source · offline"
        task.triggerHealth == TriggerHealth.ERROR -> "$source · check failed"
        lastRunProblem(task) != null -> "$source · ${lastRunProblem(task)!!.lowercase()}"
        task.lastRunAt != null -> "$source · ran ${now(task.lastRunAt!!).lowercase()}"
        trigger.source == TriggerSource.APP -> "$source · waiting for an event"
        task.triggerCheckedAt != null -> "$source · checked ${now(task.triggerCheckedAt!!).lowercase()}"
        else -> "$source · not checked yet"
    }
}

/** Whether the row's status line is a problem (shown in red). */
internal fun triggerHasProblem(task: ScheduledTask): Boolean = task.enabled && task.trigger != null &&
    (task.triggerHealth in setOf(TriggerHealth.NO_PERMISSION, TriggerHealth.NOT_SET_UP, TriggerHealth.OFFLINE, TriggerHealth.ERROR) || lastRunProblem(task) != null)

/** The tone of a source problem's banner. */
internal fun TriggerHealth.tone(): Tone = when (this) {
    TriggerHealth.OFFLINE -> Tone.Caution
    TriggerHealth.NO_PERMISSION, TriggerHealth.NOT_SET_UP, TriggerHealth.ERROR -> Tone.Critical
    TriggerHealth.OK, TriggerHealth.WAITING -> Tone.Neutral
}

/** Opens Locus's page in Android's app settings, where a permission denied for good can be allowed. */
internal fun appSettingsIntent(packageName: String) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/**
 * Calendar access, asked for only when a calendar trigger or the morning digest needs it. [denied] is
 * true once Android said no, so the banner sends the user to the app's settings instead of asking again.
 */
@Composable
internal fun CalendarAccessBanner(reason: String, denied: Boolean, onAllow: () -> Unit) {
    val context = LocalContext.current
    Banner(
        if (denied) "$reason Android said no; allow Calendar for Locus in its app settings." else reason,
        Tone.Caution,
        title = "Calendar access needed",
        actionsBelow = true,
    ) {
        if (denied) {
            TextButton(onClick = { context.startActivity(appSettingsIntent(context.packageName)) }) { Text("Open app settings") }
        } else {
            TextButton(onClick = onAllow) { Text("Allow calendar access") }
        }
    }
}

/** The trigger part of the task sheet: source, filter, rate limit and quiet hours. */
@Composable
internal fun TriggerFields(
    draft: TaskDraft,
    setup: TasksViewModel.Setup,
    calendarDenied: Boolean,
    onAllowCalendar: () -> Unit,
    onChange: (TaskDraft) -> Unit,
) {
    val trigger = draft.trigger
    fun set(t: TaskTrigger) = onChange(draft.copy(trigger = t))
    Text("When", style = LocusType.cardTitle)
    ChoiceChips(SOURCE_LABELS, trigger.source.ordinal) { set(trigger.copy(source = TriggerSource.entries[it])) }
    SupportingText(sourceHelp(trigger.source))
    when (trigger.source) {
        TriggerSource.CALENDAR -> {
            if (!setup.calendarAllowed) CalendarAccessBanner("Locus needs to read your calendar to see entries coming up.", calendarDenied, onAllowCalendar)
            Text("How long before it starts", style = LocusType.cardTitle)
            ChoiceChips(listOf("At start", "10 min", "30 min", "1 hour"), TaskTrigger.LEADS.indexOf(trigger.minutesBefore)) {
                set(trigger.copy(minutesBefore = TaskTrigger.LEADS[it]))
            }
            OutlinedTextField(
                trigger.contains, { set(trigger.copy(contains = it)) },
                label = { Text("Mentions (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Words in the title, place or notes, e.g. “standup”. Empty for every entry.") },
            )
            if (setup.calendars.isNotEmpty()) {
                Text("Calendar", style = LocusType.cardTitle)
                ChipFlow {
                    LocusChip("Any", variant = ChipVariant.Filter, selected = trigger.calendar.isBlank(), onClick = { set(trigger.copy(calendar = "")) })
                    setup.calendars.forEach { name ->
                        LocusChip(name, variant = ChipVariant.Filter, selected = trigger.calendar.equals(name, ignoreCase = true), onClick = { set(trigger.copy(calendar = name)) })
                    }
                }
            }
        }
        TriggerSource.NTFY -> {
            OutlinedTextField(
                trigger.topicUrl, { set(trigger.copy(topicUrl = it.trim())) },
                label = { Text("Topic address") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("https://ntfy.sh/a-long-secret-name") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                supportingText = { Text("Not the topic task results go to. A long, random name keeps others from posting to it.") },
            )
            OutlinedTextField(
                draft.token, { onChange(draft.copy(token = it, clearToken = false)) },
                label = { Text(if (draft.hasToken && !draft.clearToken) "Access token (saved)" else "Access token (optional)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                placeholder = { Text(if (draft.hasToken && !draft.clearToken) "Leave empty to keep it" else "tk_…") },
                supportingText = { Text("Kept in Locus's encrypted vault and sent only to this server.") },
            )
            if (draft.hasToken && !draft.clearToken && draft.token.isBlank()) {
                TextButton(onClick = { onChange(draft.copy(clearToken = true)) }) { Text("Remove the saved token", color = Locus.colors.critical) }
            }
            Text("Lowest priority", style = LocusType.cardTitle)
            val priorities = listOf(1, 3, 4, 5)
            ChoiceChips(listOf("Any", "Default", "High", "Urgent"), priorities.indexOf(trigger.minPriority).coerceAtLeast(0)) {
                set(trigger.copy(minPriority = priorities[it]))
            }
            OutlinedTextField(
                trigger.contains, { set(trigger.copy(contains = it)) },
                label = { Text("Mentions (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Words in the title, message or tags. Empty for every message.") },
            )
        }
        TriggerSource.MAIL -> {
            if (!setup.mailbox) {
                val message = "The agent's mailbox isn't set up with an IMAP server. Add it in Settings → Integrations → Email."
                Banner(message, Tone.Caution, title = "Mailbox needed", actionsBelow = true) { SettingsLinkIn(message) }
            }
            OutlinedTextField(
                trigger.from, { set(trigger.copy(from = it)) },
                label = { Text("From (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Part of the sender's name or address, e.g. “@github.com”.") },
            )
            OutlinedTextField(
                trigger.contains, { set(trigger.copy(contains = it)) },
                label = { Text("Subject mentions (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        }
        TriggerSource.APP -> {
            OutlinedTextField(
                trigger.eventName, { set(trigger.copy(eventName = it.trim())) },
                label = { Text("Event name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("backup-failed") },
                supportingText = { Text("Empty takes any event name.") },
            )
            OutlinedTextField(
                draft.apps, { onChange(draft.copy(apps = it)) },
                label = { Text("Allowed apps (optional)") }, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("net.dinglisch.android.taskerm") },
                supportingText = { Text("Package names, separated by commas. Android must say which app sent the event for it to count.") },
            )
            SupportingText(
                "The app sends an activity intent with action dev.locus.action.EVENT, the extra dev.locus.extra.EVENT set to the event name " +
                    "and, optionally, dev.locus.extra.TEXT saying what happened.",
            )
            SettingsLinkIn("Who may send it is set in Settings → Automation.")
        }
    }
    Text("At most one run every", style = LocusType.cardTitle)
    ChoiceChips(listOf("5 min", "15 min", "1 hour", "6 hours"), TaskTrigger.GAPS.indexOf(trigger.minGapMinutes)) {
        set(trigger.copy(minGapMinutes = TaskTrigger.GAPS[it]))
    }
    SupportingText("Events in between are skipped and listed in the task's history. Events found in one check run together, once.")
    CardGroup {
        SwitchRow("Quiet hours", draft.quietOn, { onChange(draft.copy(quietOn = it)) }, subtitle = "Events in these hours start nothing.")
    }
    if (draft.quietOn) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            OutlinedTextField(
                draft.quietFrom, { onChange(draft.copy(quietFrom = it)) },
                label = { Text("From") }, singleLine = true, modifier = Modifier.weight(1f),
                isError = QuietHours.parse(draft.quietFrom) == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedTextField(
                draft.quietTo, { onChange(draft.copy(quietTo = it)) },
                label = { Text("To") }, singleLine = true, modifier = Modifier.weight(1f),
                isError = QuietHours.parse(draft.quietTo) == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
    }
    draft.problem?.let { Text(it, style = LocusType.rowSupporting, color = Locus.colors.critical) }
}

/** One choice of a few, as filter chips that wrap (a segmented row clips at large font sizes). */
@Composable
private fun ChoiceChips(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    ChipFlow {
        options.forEachIndexed { i, label -> LocusChip(label, variant = ChipVariant.Filter, selected = i == selected, onClick = { onSelect(i) }) }
    }
}

/** "Send a test event": text that goes through the task's trigger like a real event. */
@Composable
internal fun TestEventSheet(task: ScheduledTask, onDismiss: () -> Unit, onSend: (String) -> Unit) {
    val trigger = task.trigger ?: return
    var text by remember { mutableStateOf(trigger.contains.ifBlank { "" }) }
    LocusSheet(
        title = "Send a test event",
        subtitle = "Goes through “${task.title}”'s filter as a ${trigger.source.noun}. If it matches, the task runs now, even when paused, rate-limited or in quiet hours.",
        onDismiss = onDismiss,
        action = "Send",
        onAction = { onSend(text) },
    ) {
        TextArea(text, { text = it }, placeholder = when (trigger.source) {
            TriggerSource.CALENDAR -> "The entry's title, e.g. Team standup"
            TriggerSource.NTFY -> "The message, e.g. Backup failed on homelab"
            TriggerSource.MAIL -> "The subject, e.g. Your invoice is ready"
            TriggerSource.APP -> "What happened, e.g. Phone left home Wi-Fi"
        })
        SupportingText("The run reads it as untrusted outside text, like a real event: it can look things up but can't send, save to memory or open addresses you didn't name.")
    }
}

/** How an event in the history is shown: its tone and what happened. */
internal fun eventLine(event: TriggerEventRecord): Pair<String, Tone> = when {
    event.decision.ran && event.outcome == TaskRunOutcome.DONE -> "${event.decision.label} · done" to Tone.Live
    event.decision.ran && event.outcome == TaskRunOutcome.FAILED -> "${event.decision.label} · failed" to Tone.Critical
    event.decision.ran && event.outcome == TaskRunOutcome.STOPPED -> "${event.decision.label} · stopped" to Tone.Caution
    event.decision.ran -> (if (event.conversationId != null) "Running" else "Queued") to Tone.Brand
    event.decision == TriggerDecision.FAILED -> event.decision.label to Tone.Critical
    event.decision == TriggerDecision.NO_MATCH -> event.decision.label to Tone.Neutral
    else -> event.decision.label to Tone.Caution
}

/** What a task's trigger received: each event, whether it ran and why not, newest first. */
@Composable
internal fun HistorySheet(task: ScheduledTask, events: List<TriggerEventRecord>?, onDismiss: () -> Unit, onOpenChat: (String) -> Unit) {
    LocusSheet(
        title = "History",
        subtitle = "What reached “${task.title}” and what it did. The latest ${dev.locus.core.data.tasks.triggers.TriggerEngine.KEEP} events are kept.",
        onDismiss = onDismiss,
    ) {
        when {
            events == null -> Row(Modifier.fillMaxWidth().padding(Spacing.lg)) { InlineSpinner() }
            events.isEmpty() -> SupportingText(
                if (task.trigger?.source == TriggerSource.APP) "Nothing yet. Events show here as another app sends them, whether or not they start a run."
                else "Nothing yet. Events show here as Locus finds them, whether or not they start a run.",
            )
            else -> CardGroup {
                events.forEachIndexed { i, event ->
                    if (i > 0) GroupDivider(inset = Insets.iconRow)
                    val line = eventLine(event).first
                    val chat = event.conversationId
                    ListRow(
                        title = event.title,
                        onClick = chat?.let { { onOpenChat(it) } },
                        leading = { EventGlyph(event) },
                        titleMaxLines = 2,
                        subtitleMaxLines = 6,
                        subtitle = listOfNotNull("$line · ${relativeTime(event.receivedAt).lowercase()}", event.detail?.takeIf { it.isNotBlank() && !(event.decision.ran && event.outcome == null) }).joinToString("\n"),
                    )
                }
            }
        }
    }
}

/** What an event's history row leads with: how it went, not where it came from (every event of a task has the same source). */
internal enum class EventMark { DONE, RUNNING, SKIPPED, FAILED }

internal fun eventMark(event: TriggerEventRecord): EventMark = when {
    event.decision == TriggerDecision.FAILED -> EventMark.FAILED
    !event.decision.ran -> EventMark.SKIPPED
    event.outcome == null -> EventMark.RUNNING
    event.outcome == TaskRunOutcome.DONE -> EventMark.DONE
    event.outcome == TaskRunOutcome.STOPPED -> EventMark.SKIPPED
    else -> EventMark.FAILED
}

@Composable
private fun EventGlyph(event: TriggerEventRecord) {
    when (eventMark(event)) {
        EventMark.RUNNING -> Box(Modifier.size(IconSize.tile), contentAlignment = Alignment.Center) {
            InlineSpinner(color = Tone.Brand.color(), contentDescription = "Running")
        }
        EventMark.DONE -> IconTile(LocusIcons.Check, tint = Tone.Live.color(), contentDescription = "Ran")
        EventMark.SKIPPED -> IconTile(LocusIcons.Skip, contentDescription = "Skipped")
        EventMark.FAILED -> IconTile(LocusIcons.Error, tint = Tone.Critical.color(), contentDescription = "Failed")
    }
}

/** The Run now / test / history messages, and their tone. */
internal fun testSentMessage(event: TaskEvent.TestSent): Pair<String, Tone> = when (event.decision) {
    null -> "The test didn't match “${event.title}”" + (event.reason?.let { ": $it." } ?: ".") to Tone.Caution
    TriggerDecision.TEST -> "The test matched: “${event.title}” is starting." to Tone.Live
    TriggerDecision.FAILED -> "The test matched, but Locus couldn't start the run." to Tone.Critical
    else -> "The test was recorded: ${event.decision.label.lowercase()}." to Tone.Caution
}

/** A short summary of the trigger, for the opened row. */
internal fun triggerDetails(trigger: TaskTrigger): String = buildString {
    append(trigger.summary)
    append(". At most one run every ").append(TriggerRules.gapLabel(trigger.minGapMinutes))
    trigger.quietHours?.let { append("; quiet ").append(it.label) }
    if (trigger.allowedApps.isNotEmpty()) append("; only from ").append(trigger.allowedApps.joinToString(", "))
    append('.')
}

/** A source problem on an opened task, with what to do about it. */
@Composable
internal fun SourceProblem(task: ScheduledTask, calendarDenied: Boolean, onAllowCalendar: () -> Unit) {
    val message = task.triggerMessage ?: return
    if (task.triggerHealth == TriggerHealth.NO_PERMISSION) {
        CalendarAccessBanner(message, calendarDenied, onAllowCalendar)
        return
    }
    Banner(
        message,
        task.triggerHealth.tone(),
        title = when (task.triggerHealth) {
            TriggerHealth.OFFLINE -> "Source unreachable"
            TriggerHealth.NOT_SET_UP -> "Not set up"
            else -> "The last check failed"
        },
        actionsBelow = true,
    ) { SettingsLinkIn(message) }
}
