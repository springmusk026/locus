package dev.locus.feature.tasks

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.annotation.StringRes
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import dev.locus.core.data.automation.AutomationContract
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
import dev.locus.core.designsystem.SettingsLink
import dev.locus.core.designsystem.SettingsLinkIn
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.SwitchRow
import dev.locus.core.designsystem.TextArea
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.UiText
import dev.locus.core.designsystem.asString
import dev.locus.core.designsystem.color
import dev.locus.core.designsystem.inlineRes
import dev.locus.core.designsystem.labelRes
import dev.locus.core.designsystem.relativeTime
import dev.locus.core.model.QuietHours
import dev.locus.core.model.ScheduledTask
import dev.locus.core.model.SettingsDestination
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

/** The source's short label, as the source picker and a row's status line show it. */
@StringRes
internal fun TriggerSource.labelRes(): Int = when (this) {
    TriggerSource.CALENDAR -> R.string.tasks_trigger_source_calendar
    TriggerSource.NTFY -> R.string.tasks_trigger_source_ntfy
    TriggerSource.MAIL -> R.string.tasks_trigger_source_mail
    TriggerSource.APP -> R.string.tasks_trigger_source_app
}

/** What one event from the source is called in a sentence ("… as a calendar entry"). */
@StringRes
internal fun TriggerSource.nounRes(): Int = when (this) {
    TriggerSource.CALENDAR -> R.string.tasks_trigger_noun_calendar
    TriggerSource.NTFY -> R.string.tasks_trigger_noun_ntfy
    TriggerSource.MAIL -> R.string.tasks_trigger_noun_mail
    TriggerSource.APP -> R.string.tasks_trigger_noun_app
}

/** What each source waits for, and how quickly Locus notices (docs/design/event-triggers.md, "Latency"). */
@Composable
internal fun sourceHelp(source: TriggerSource): String = stringResource(
    when (source) {
        TriggerSource.CALENDAR -> R.string.tasks_trigger_help_calendar
        TriggerSource.NTFY -> R.string.tasks_trigger_help_ntfy
        TriggerSource.MAIL -> R.string.tasks_trigger_help_mail
        TriggerSource.APP -> R.string.tasks_trigger_help_app
    },
)

/** The line under an event-triggered task's name: what it waits for and how its source is. */
internal fun triggerStatusLine(task: ScheduledTask, now: (Long) -> String): UiText {
    val trigger = task.trigger ?: return UiText.Plain("")
    val source = UiText.Res(trigger.source.labelRes())
    return when {
        !task.enabled -> UiText.Res(R.string.tasks_status_paused, source)
        task.triggerHealth == TriggerHealth.NO_PERMISSION -> UiText.Res(R.string.tasks_status_no_access, source)
        task.triggerHealth == TriggerHealth.NOT_SET_UP -> UiText.Res(R.string.tasks_status_not_set_up, source)
        task.triggerHealth == TriggerHealth.OFFLINE -> UiText.Res(R.string.tasks_status_offline, source)
        task.triggerHealth == TriggerHealth.ERROR -> UiText.Res(R.string.tasks_status_check_failed, source)
        task.lastOutcome == TaskRunOutcome.FAILED -> UiText.Res(R.string.tasks_status_last_failed, source)
        task.lastOutcome == TaskRunOutcome.STOPPED -> UiText.Res(R.string.tasks_status_last_stopped, source)
        task.lastRunAt != null -> UiText.Res(R.string.tasks_status_ran, source, now(task.lastRunAt!!).lowercase())
        trigger.source == TriggerSource.APP -> UiText.Res(R.string.tasks_status_waiting, source)
        task.triggerCheckedAt != null -> UiText.Res(R.string.tasks_status_checked, source, now(task.triggerCheckedAt!!).lowercase())
        else -> UiText.Res(R.string.tasks_status_not_checked, source)
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
        if (denied) stringResource(R.string.tasks_trigger_calendar_denied, reason) else reason,
        Tone.Caution,
        title = stringResource(R.string.tasks_trigger_calendar_banner_title),
        actionsBelow = true,
    ) {
        if (denied) {
            TextButton(onClick = { context.startActivity(appSettingsIntent(context.packageName)) }) { Text(stringResource(R.string.tasks_trigger_open_app_settings)) }
        } else {
            TextButton(onClick = onAllow) { Text(stringResource(R.string.tasks_trigger_allow_calendar)) }
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
    Text(stringResource(R.string.tasks_trigger_when), style = LocusType.cardTitle)
    val sourceLabels = TriggerSource.entries.map { stringResource(it.labelRes()) }
    ChoiceChips(sourceLabels, trigger.source.ordinal) { set(trigger.copy(source = TriggerSource.entries[it])) }
    SupportingText(sourceHelp(trigger.source))
    when (trigger.source) {
        TriggerSource.CALENDAR -> {
            if (!setup.calendarAllowed) CalendarAccessBanner(stringResource(R.string.tasks_trigger_calendar_reason), calendarDenied, onAllowCalendar)
            Text(stringResource(R.string.tasks_trigger_calendar_lead), style = LocusType.cardTitle)
            ChoiceChips(
                listOf(
                    stringResource(R.string.tasks_trigger_calendar_lead_start),
                    stringResource(R.string.tasks_trigger_calendar_lead_10m),
                    stringResource(R.string.tasks_trigger_calendar_lead_30m),
                    stringResource(R.string.tasks_trigger_calendar_lead_1h),
                ),
                TaskTrigger.LEADS.indexOf(trigger.minutesBefore)) {
                set(trigger.copy(minutesBefore = TaskTrigger.LEADS[it]))
            }
            OutlinedTextField(
                trigger.contains, { set(trigger.copy(contains = it)) },
                label = { Text(stringResource(R.string.tasks_trigger_calendar_mentions)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text(stringResource(R.string.tasks_trigger_calendar_mentions_help)) },
            )
            if (setup.calendars.isNotEmpty()) {
                Text(stringResource(R.string.tasks_trigger_calendar_which), style = LocusType.cardTitle)
                ChipFlow {
                    LocusChip(stringResource(R.string.tasks_trigger_calendar_any), variant = ChipVariant.Filter, selected = trigger.calendar.isBlank(), onClick = { set(trigger.copy(calendar = "")) })
                    setup.calendars.forEach { name ->
                        LocusChip(name, variant = ChipVariant.Filter, selected = trigger.calendar.equals(name, ignoreCase = true), onClick = { set(trigger.copy(calendar = name)) })
                    }
                }
            }
        }
        TriggerSource.NTFY -> {
            OutlinedTextField(
                trigger.topicUrl, { set(trigger.copy(topicUrl = it.trim())) },
                label = { Text(stringResource(R.string.tasks_trigger_ntfy_topic)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.tasks_trigger_ntfy_topic_placeholder)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                supportingText = { Text(stringResource(R.string.tasks_trigger_ntfy_topic_help)) },
            )
            OutlinedTextField(
                draft.token, { onChange(draft.copy(token = it, clearToken = false)) },
                label = { Text(stringResource(if (draft.hasToken && !draft.clearToken) R.string.tasks_trigger_ntfy_token_saved else R.string.tasks_trigger_ntfy_token_optional)) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                placeholder = { Text(stringResource(if (draft.hasToken && !draft.clearToken) R.string.tasks_trigger_ntfy_token_keep else R.string.tasks_trigger_ntfy_token_placeholder)) },
                supportingText = { Text(stringResource(R.string.tasks_trigger_ntfy_token_help)) },
            )
            if (draft.hasToken && !draft.clearToken && draft.token.isBlank()) {
                TextButton(onClick = { onChange(draft.copy(clearToken = true)) }) { Text(stringResource(R.string.tasks_trigger_ntfy_token_remove), color = Locus.colors.critical) }
            }
            Text(stringResource(R.string.tasks_trigger_ntfy_priority), style = LocusType.cardTitle)
            val priorities = listOf(1, 3, 4, 5)
            ChoiceChips(
                listOf(
                    stringResource(R.string.tasks_trigger_ntfy_priority_any),
                    stringResource(R.string.tasks_trigger_ntfy_priority_default),
                    stringResource(R.string.tasks_trigger_ntfy_priority_high),
                    stringResource(R.string.tasks_trigger_ntfy_priority_urgent),
                ),
                priorities.indexOf(trigger.minPriority).coerceAtLeast(0)) {
                set(trigger.copy(minPriority = priorities[it]))
            }
            OutlinedTextField(
                trigger.contains, { set(trigger.copy(contains = it)) },
                label = { Text(stringResource(R.string.tasks_trigger_ntfy_mentions)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text(stringResource(R.string.tasks_trigger_ntfy_mentions_help)) },
            )
        }
        TriggerSource.MAIL -> {
            if (!setup.mailbox) {
                val message = stringResource(R.string.tasks_trigger_mail_no_mailbox)
                Banner(message, Tone.Caution, title = stringResource(R.string.tasks_trigger_mail_banner_title), actionsBelow = true) {
                    SettingsLinkIn(message, destination = SettingsDestination.Integrations)
                }
            }
            OutlinedTextField(
                trigger.from, { set(trigger.copy(from = it)) },
                label = { Text(stringResource(R.string.tasks_trigger_mail_from)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text(stringResource(R.string.tasks_trigger_mail_from_help)) },
            )
            OutlinedTextField(
                trigger.contains, { set(trigger.copy(contains = it)) },
                label = { Text(stringResource(R.string.tasks_trigger_mail_subject)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        }
        TriggerSource.APP -> {
            OutlinedTextField(
                trigger.eventName, { set(trigger.copy(eventName = it.trim())) },
                label = { Text(stringResource(R.string.tasks_trigger_app_event)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.tasks_trigger_app_event_placeholder)) },
                supportingText = { Text(stringResource(R.string.tasks_trigger_app_event_help)) },
            )
            OutlinedTextField(
                draft.apps, { onChange(draft.copy(apps = it)) },
                label = { Text(stringResource(R.string.tasks_trigger_app_allowed)) }, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.tasks_trigger_app_allowed_placeholder)) },
                supportingText = { Text(stringResource(R.string.tasks_trigger_app_allowed_help)) },
            )
            SupportingText(
                stringResource(
                    R.string.tasks_trigger_app_intent,
                    AutomationContract.ACTION_EVENT,
                    AutomationContract.EXTRA_EVENT,
                    AutomationContract.EXTRA_TEXT,
                ),
            )
            // Who may send it is set in Settings → Automation.
            SettingsLink(SettingsDestination.Automation)
        }
    }
    Text(stringResource(R.string.tasks_trigger_gap_title), style = LocusType.cardTitle)
    ChoiceChips(
        listOf(
            stringResource(R.string.tasks_trigger_gap_5m),
            stringResource(R.string.tasks_trigger_gap_15m),
            stringResource(R.string.tasks_trigger_gap_1h),
            stringResource(R.string.tasks_trigger_gap_6h),
        ),
        TaskTrigger.GAPS.indexOf(trigger.minGapMinutes)) {
        set(trigger.copy(minGapMinutes = TaskTrigger.GAPS[it]))
    }
    SupportingText(stringResource(R.string.tasks_trigger_gap_help))
    CardGroup {
        SwitchRow(
            stringResource(R.string.tasks_trigger_quiet_title),
            draft.quietOn,
            { onChange(draft.copy(quietOn = it)) },
            subtitle = stringResource(R.string.tasks_trigger_quiet_subtitle),
        )
    }
    if (draft.quietOn) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            OutlinedTextField(
                draft.quietFrom, { onChange(draft.copy(quietFrom = it)) },
                label = { Text(stringResource(R.string.tasks_trigger_quiet_from)) }, singleLine = true, modifier = Modifier.weight(1f),
                isError = QuietHours.parse(draft.quietFrom) == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedTextField(
                draft.quietTo, { onChange(draft.copy(quietTo = it)) },
                label = { Text(stringResource(R.string.tasks_trigger_quiet_to)) }, singleLine = true, modifier = Modifier.weight(1f),
                isError = QuietHours.parse(draft.quietTo) == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
    }
    draft.problem?.let { Text(it.asString(), style = LocusType.rowSupporting, color = Locus.colors.critical) }
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
        title = stringResource(R.string.tasks_test_title),
        subtitle = stringResource(R.string.tasks_test_subtitle, task.title, stringResource(trigger.source.nounRes())),
        onDismiss = onDismiss,
        action = stringResource(R.string.tasks_test_send),
        onAction = { onSend(text) },
    ) {
        TextArea(text, { text = it }, placeholder = when (trigger.source) {
            TriggerSource.CALENDAR -> stringResource(R.string.tasks_test_placeholder_calendar)
            TriggerSource.NTFY -> stringResource(R.string.tasks_test_placeholder_ntfy)
            TriggerSource.MAIL -> stringResource(R.string.tasks_test_placeholder_mail)
            TriggerSource.APP -> stringResource(R.string.tasks_test_placeholder_app)
        })
        SupportingText(stringResource(R.string.tasks_test_note))
    }
}

/** How an event in the history is shown: its tone and what happened. */
@Composable
internal fun eventLine(event: TriggerEventRecord): Pair<String, Tone> {
    val decision = stringResource(event.decision.labelRes)
    return when {
        event.decision.ran && event.outcome == TaskRunOutcome.DONE -> stringResource(R.string.tasks_history_event_done, decision) to Tone.Live
        event.decision.ran && event.outcome == TaskRunOutcome.FAILED -> stringResource(R.string.tasks_history_event_failed, decision) to Tone.Critical
        event.decision.ran && event.outcome == TaskRunOutcome.STOPPED -> stringResource(R.string.tasks_history_event_stopped, decision) to Tone.Caution
        event.decision.ran -> stringResource(if (event.conversationId != null) R.string.tasks_history_event_running else R.string.tasks_history_event_queued) to Tone.Brand
        event.decision == TriggerDecision.FAILED -> decision to Tone.Critical
        event.decision == TriggerDecision.NO_MATCH -> decision to Tone.Neutral
        else -> decision to Tone.Caution
    }
}

/** What a task's trigger received: each event, whether it ran and why not, newest first. */
@Composable
internal fun HistorySheet(task: ScheduledTask, events: List<TriggerEventRecord>?, onDismiss: () -> Unit, onOpenChat: (String) -> Unit) {
    LocusSheet(
        title = stringResource(R.string.tasks_history_title),
        subtitle = stringResource(R.string.tasks_history_subtitle, task.title, dev.locus.core.data.tasks.triggers.TriggerEngine.KEEP),
        onDismiss = onDismiss,
    ) {
        when {
            events == null -> Row(Modifier.fillMaxWidth().padding(Spacing.lg)) { InlineSpinner() }
            events.isEmpty() -> SupportingText(
                stringResource(if (task.trigger?.source == TriggerSource.APP) R.string.tasks_history_empty_app else R.string.tasks_history_empty),
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
                        subtitle = listOfNotNull(stringResource(R.string.tasks_history_event_when, line, relativeTime(event.receivedAt).lowercase()), event.detail?.takeIf { it.isNotBlank() && !(event.decision.ran && event.outcome == null) }).joinToString("\n"),
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
            InlineSpinner(color = Tone.Brand.color(), contentDescription = stringResource(R.string.tasks_history_mark_running))
        }
        EventMark.DONE -> IconTile(LocusIcons.Check, tint = Tone.Live.color(), contentDescription = stringResource(R.string.tasks_history_mark_ran))
        EventMark.SKIPPED -> IconTile(LocusIcons.Skip, contentDescription = stringResource(R.string.tasks_history_mark_skipped))
        EventMark.FAILED -> IconTile(LocusIcons.Error, tint = Tone.Critical.color(), contentDescription = stringResource(R.string.tasks_history_mark_failed))
    }
}

/** The Run now / test / history messages, and their tone. */
internal fun testSentMessage(event: TaskEvent.TestSent): Pair<UiText, Tone> = when (event.decision) {
    // The reason is core's, in English.
    null -> event.reason.let { reason ->
        if (reason != null) UiText.Res(R.string.tasks_test_unmatched_reason, event.title, reason) else UiText.Res(R.string.tasks_test_unmatched, event.title)
    } to Tone.Caution
    TriggerDecision.TEST -> UiText.Res(R.string.tasks_test_matched, event.title) to Tone.Live
    TriggerDecision.FAILED -> UiText.Res(R.string.tasks_test_matched_failed) to Tone.Critical
    else -> UiText.Res(R.string.tasks_test_recorded, UiText.Res(event.decision.inlineRes)) to Tone.Caution
}

/** A short summary of the trigger, for the opened row. */
@Composable
internal fun triggerDetails(trigger: TaskTrigger): String {
    val gap = TriggerRules.gapLabel(trigger.minGapMinutes)
    val quiet = trigger.quietHours?.label
    val apps = trigger.allowedApps.takeIf { it.isNotEmpty() }?.joinToString(", ")
    return when {
        quiet != null && apps != null -> stringResource(R.string.tasks_trigger_details_quiet_apps, trigger.summary, gap, quiet, apps)
        quiet != null -> stringResource(R.string.tasks_trigger_details_quiet, trigger.summary, gap, quiet)
        apps != null -> stringResource(R.string.tasks_trigger_details_apps, trigger.summary, gap, apps)
        else -> stringResource(R.string.tasks_trigger_details, trigger.summary, gap)
    }
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
        title = stringResource(
            when (task.triggerHealth) {
                TriggerHealth.OFFLINE -> R.string.tasks_trigger_problem_unreachable
                TriggerHealth.NOT_SET_UP -> R.string.tasks_trigger_problem_not_set_up
                else -> R.string.tasks_trigger_problem_check_failed
            },
        ),
        actionsBelow = true,
    ) { SettingsLinkIn(message) }
}
