package dev.locus.feature.tasks

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.locus.core.agent.workflow.WorkflowRender
import dev.locus.core.data.tasks.RunNowStatus
import dev.locus.core.designsystem.AddRow
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.ConfirmSheet
import dev.locus.core.designsystem.Emphasis
import dev.locus.core.designsystem.EmptyState
import dev.locus.core.designsystem.ErrorState
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconSize
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.InlineSpinner
import dev.locus.core.designsystem.InlineSwitch
import dev.locus.core.designsystem.Insets
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.LoadingState
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusFeedbackHost
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.LocusTopBar
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.ProvideLocusFeedback
import dev.locus.core.designsystem.Segmented
import dev.locus.core.designsystem.SettingsLink
import dev.locus.core.designsystem.Sizes
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.StatusLine
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.SwitchRow
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.color
import dev.locus.core.designsystem.rememberLocusFeedbackState
import dev.locus.core.designsystem.relativeTime
import dev.locus.core.model.QuietHours
import dev.locus.core.model.ScheduleIntervals
import dev.locus.core.model.ScheduledTask
import dev.locus.core.model.SettingsDestination
import dev.locus.core.model.TaskConditions
import dev.locus.core.model.TaskRunOutcome
import dev.locus.core.model.TriggerSource
import kotlinx.coroutines.launch

@Composable
fun TasksRoute(onBack: () -> Unit, onOpenChat: (String) -> Unit, viewModel: TasksViewModel = hiltViewModel()) {
    // Tasks hosts its own messages (over the app root's), so they sit above its bottom bar.
    val feedbackState = rememberLocusFeedbackState()
    ProvideLocusFeedback(feedbackState) {
        TasksScreen(onBack, onOpenChat, viewModel, snackbarHost = { LocusFeedbackHost(feedbackState) })
    }
}

/** What sheet is open over the list. */
internal sealed interface SheetTarget {
    data class New(val draft: TaskDraft? = null) : SheetTarget
    data class Edit(val task: ScheduledTask, val draft: TaskDraft) : SheetTarget
    data class Test(val task: ScheduledTask) : SheetTarget
    data class History(val task: ScheduledTask) : SheetTarget
}

/**
 * Calendar access as the screen asks for it: only when a calendar trigger or the digest needs it, once
 * through Android's prompt, then through the app's settings when Android said no.
 */
internal class CalendarAccess(val denied: Boolean, val request: () -> Unit)

@Composable
private fun rememberCalendarAccess(viewModel: TasksViewModel, onGranted: () -> Unit = {}): CalendarAccess {
    var denied by rememberSaveable { mutableStateOf(false) }
    val granted by rememberUpdatedState(onGranted)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        denied = !ok
        viewModel.refreshSetup()
        if (ok) granted()
    }
    return CalendarAccess(denied) { launcher.launch(Manifest.permission.READ_CALENDAR) }
}

@Composable
private fun TasksScreen(onBack: () -> Unit, onOpenChat: (String) -> Unit, viewModel: TasksViewModel, snackbarHost: @Composable () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var sheet by remember { mutableStateOf<SheetTarget?>(null) }
    val feedback = Locus.feedback
    val openChat by rememberUpdatedState(onOpenChat)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is TaskEvent.RunFinished -> {
                    val message = runFinishedMessage(event)
                    val chat = event.chatId?.takeIf { event.status != RunNowStatus.SKIPPED }
                    feedback.show(message.first, message.second, action = chat?.let { "Open chat" }, onAction = chat?.let { { openChat(it) } })
                }
                is TaskEvent.TestSent -> testSentMessage(event).let { (text, tone) -> feedback.show(text, tone) }
                is TaskEvent.Failed -> feedback.show(event.message, Tone.Critical)
            }
        }
    }
    val edit: (ScheduledTask) -> Unit = { task -> scope.launch { sheet = SheetTarget.Edit(task, viewModel.draftOf(task)) } }
    val reminders by viewModel.reminders.collectAsStateWithLifecycle()
    val zone = remember { java.time.ZoneId.systemDefault() }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = snackbarHost,
        topBar = {
            val tasks = (state as? TasksUiState.Ready)?.tasks.orEmpty()
            LocusTopBar(
                "Scheduled tasks",
                onBack = onBack,
                status = {
                    if (tasks.isNotEmpty()) {
                        val running = tasks.count { it.active == RunNowStatus.RUNNING }
                        val active = tasks.count { it.task.enabled }
                        when {
                            running > 0 -> StatusLine(if (running == 1) "1 running" else "$running running", Tone.Live)
                            active == 0 -> StatusLine("All paused", Tone.Neutral)
                            else -> StatusLine("$active of ${tasks.size} on", Tone.Brand)
                        }
                    }
                },
            )
        },
    ) { padding ->
        when (val s = state) {
            TasksUiState.Loading -> LoadingState(Modifier.padding(padding))
            is TasksUiState.Error -> ErrorState(
                "Couldn't load your tasks",
                s.message,
                onRetry = viewModel::retry,
                modifier = Modifier.padding(padding),
            )
            is TasksUiState.Ready -> if (s.tasks.isEmpty() && reminders.isEmpty()) {
                EmptyState(
                    "No tasks yet",
                    "A task runs on its own, on a schedule or when something happens (a calendar entry coming up, an ntfy message, new mail). " +
                        "It looks things up, read-only (it never changes or sends anything), and notifies you with a short summary.",
                    Modifier.padding(padding),
                    icon = LocusIcons.Clock,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Button(onClick = { sheet = SheetTarget.New() }) { Text("New task") }
                        TextButton(onClick = { sheet = SheetTarget.New(TaskDraft.morningDigest(viewModel.defaultConditions.value)) }) { Text("Start from the morning digest") }
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = padding.calculateTopPadding() + Spacing.sm, bottom = Spacing.xxl),
                ) {
                    remindersSection(reminders, viewModel.clock(), zone, viewModel.exactReminders()) { viewModel.cancelReminder(it.id) }
                    if (reminders.isNotEmpty()) item { dev.locus.core.designsystem.SectionLabel("Tasks") }
                    item {
                        CardGroup {
                            s.tasks.forEachIndexed { i, item ->
                                if (i > 0) GroupDivider(inset = Insets.iconRow)
                                TaskRow(
                                    item, viewModel,
                                    onEdit = { edit(item.task) },
                                    onTest = { sheet = SheetTarget.Test(item.task) },
                                    onHistory = { sheet = SheetTarget.History(item.task) },
                                    onOpenChat = onOpenChat,
                                )
                            }
                            if (s.tasks.isNotEmpty()) GroupDivider(inset = Insets.iconRow)
                            AddRow("New task") { sheet = SheetTarget.New() }
                            GroupDivider(inset = Insets.iconRow)
                            ListRow(
                                "Morning digest",
                                subtitle = "Every day at 7:00: today's calendar and what to prepare",
                                leading = { IconTile(LocusIcons.Sunrise) },
                                onClick = { sheet = SheetTarget.New(TaskDraft.morningDigest(viewModel.defaultConditions.value)) },
                            )
                        }
                    }
                    item {
                        SettingsLink(
                            SettingsDestination.Battery,
                            Modifier.padding(top = Spacing.sm),
                            hint = "Tasks run read-only and need a network connection. Android may delay runs and event checks to save battery, and Locus " +
                                "holds a run while the phone is hot or low. What waits is set in",
                        )
                    }
                }
            }
        }
    }
    when (val target = sheet) {
        null -> Unit
        is SheetTarget.New -> {
            val defaults by viewModel.defaultConditions.collectAsStateWithLifecycle()
            TaskSheet(
                initial = target.draft ?: TaskDraft(conditions = defaults),
                editing = null,
                viewModel = viewModel,
                onDismiss = { sheet = null },
                onSave = { viewModel.create(it); sheet = null },
            )
        }
        is SheetTarget.Edit -> TaskSheet(
            initial = target.draft,
            editing = target.task.title,
            viewModel = viewModel,
            onDismiss = { sheet = null },
            onSave = { viewModel.update(target.task.id, it); sheet = null },
        )
        is SheetTarget.Test -> TestEventSheet(
            target.task,
            onDismiss = { sheet = null },
            onSend = { viewModel.sendTestEvent(target.task.id, target.task.title, it); sheet = null },
        )
        is SheetTarget.History -> {
            val events by remember(target.task.id) { viewModel.history(target.task.id) }.collectAsState(initial = null)
            HistorySheet(target.task, events, onDismiss = { sheet = null }, onOpenChat = { sheet = null; onOpenChat(it) })
        }
    }
}

/** The message for a finished Run now, and its tone. */
internal fun runFinishedMessage(event: TaskEvent.RunFinished): Pair<String, Tone> = when (event.status) {
    RunNowStatus.DONE -> "“${event.title}” ran." to Tone.Live
    RunNowStatus.SKIPPED -> "“${event.title}” was already running, so it didn't run again." to Tone.Caution
    else -> "“${event.title}” didn't finish." to Tone.Critical
}

/** The line under a scheduled task's name: its schedule and how its last run went, or that it's paused. */
internal fun taskStatusLine(task: ScheduledTask, now: (Long) -> String): String {
    if (task.trigger != null) return triggerStatusLine(task, now)
    val schedule = ScheduleIntervals.label(task.intervalMinutes)
    val problem = lastRunProblem(task)
    return when {
        !task.enabled -> "$schedule · paused"
        problem != null -> "$schedule · ${problem.lowercase()} ${task.lastRunAt?.let { now(it).lowercase() }.orEmpty()}".trimEnd()
        else -> task.lastRunAt?.let { "$schedule · ran ${now(it).lowercase()}" } ?: "$schedule · not run yet"
    }
}

/** What a run in progress says on its row. */
internal fun runNowLine(status: RunNowStatus, fromEvent: Boolean = false): String = when (status) {
    RunNowStatus.QUEUED -> if (fromEvent) "Event run queued" else "Queued: starts when there's a connection"
    RunNowStatus.RUNNING -> if (fromEvent) "Running for an event" else "Running now"
    else -> ""
}

/** A task: what starts it and on/off up front; its prompt, last result and actions when opened. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskRow(
    item: TaskItem,
    viewModel: TasksViewModel,
    onEdit: () -> Unit,
    onTest: () -> Unit,
    onHistory: () -> Unit,
    onOpenChat: (String) -> Unit,
) {
    val task = item.task
    val trigger = task.trigger
    var expanded by rememberSaveable(task.id) { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val calendar = rememberCalendarAccess(viewModel) { viewModel.checkNow(task.id) }
    if (confirmDelete) {
        ConfirmSheet(
            title = "Delete “${task.title}”?",
            message = if (trigger != null) "It stops listening for events and its history goes. Chats from its earlier runs are kept." else "It stops running. Chats from its earlier runs are kept.",
            confirmLabel = "Delete",
            tone = Tone.Critical,
            onConfirm = { viewModel.delete(task.id); confirmDelete = false },
            onDismiss = { confirmDelete = false },
        )
    }
    val problem = lastRunProblem(task)
    val red = if (trigger != null) triggerHasProblem(task) else task.enabled && problem != null
    Column(Modifier.fillMaxWidth()) {
        ListRow(
            title = task.title,
            onClick = { expanded = !expanded },
            leading = { IconTile(trigger?.source?.icon() ?: LocusIcons.Clock, tint = if (task.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
            meta = {
                val run = item.active
                if (run != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        InlineSpinner(Modifier.padding(end = Spacing.xs), size = IconSize.small, color = MaterialTheme.colorScheme.primary)
                        Text(
                            runNowLine(run, fromEvent = item.run?.active != true),
                            style = LocusType.rowSupporting, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    Text(
                        taskStatusLine(task) { relativeTime(it) },
                        style = LocusType.rowSupporting,
                        color = if (red) Tone.Critical.color() else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            trailing = {
                InlineSwitch(
                    checked = task.enabled,
                    onCheckedChange = { viewModel.setEnabled(task.id, it) },
                    contentDescription = if (trigger != null) "Run “${task.title}” when its events arrive" else "Run “${task.title}” on its schedule",
                )
            },
        )
        AnimatedVisibility(expanded) {
            Column(
                Modifier.padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text(WorkflowRender.visible(task.prompt), style = LocusType.rowSupporting)
                if (trigger != null) {
                    SupportingText(triggerDetails(trigger))
                    if (task.enabled) SourceProblem(task, calendar.denied, calendar.request)
                }
                SupportingText(task.conditions.summary)
                task.lastSummary?.takeIf { it.isNotBlank() }?.let { summary ->
                    Column(
                        Modifier.fillMaxWidth()
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = Emphasis.subtleFill), MaterialTheme.shapes.medium)
                            .padding(Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        Text(
                            problem ?: "Last result",
                            style = LocusType.chip,
                            color = if (problem != null) Tone.Critical.color() else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(summary.trim(), style = LocusType.rowSupporting, maxLines = 8, overflow = TextOverflow.Ellipsis)
                    }
                }
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.End),
                ) {
                    task.lastConversationId?.let { chat ->
                        RowAction("Open last run", LocusIcons.Chats) { onOpenChat(chat) }
                    }
                    if (trigger != null) {
                        RowAction("History", LocusIcons.Activity, onClick = onHistory)
                        RowAction("Test event", LocusIcons.Send, onClick = onTest)
                    }
                    RowAction(if (item.running) "Running" else "Run now", LocusIcons.Play, enabled = !item.running) { viewModel.runNow(task.id) }
                    RowAction("Edit", LocusIcons.Edit, onClick = onEdit)
                    RowAction("Delete", LocusIcons.Trash, color = Tone.Critical.color()) { confirmDelete = true }
                }
            }
        }
    }
}

/** A text action under an opened task, with its icon (described by the label beside it). */
@Composable
private fun RowAction(
    label: String,
    icon: ImageVector,
    enabled: Boolean = true,
    color: Color = MaterialTheme.colorScheme.primary,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = Sizes.touchTarget)) {
        val tint = if (enabled) color else color.copy(alpha = Emphasis.disabled)
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.padding(end = Spacing.xs).size(IconSize.small))
        Text(label, style = LocusType.label, color = tint)
    }
}

/** "Last run failed" or "Last run stopped" when the last run didn't finish; null when it did (or hasn't run). */
internal fun lastRunProblem(task: ScheduledTask): String? = when (task.lastOutcome) {
    TaskRunOutcome.FAILED -> "Last run failed"
    TaskRunOutcome.STOPPED -> "Last run stopped"
    TaskRunOutcome.DONE, null -> null
}

/**
 * A task's name, prompt, what starts it (a schedule, or an event with its filter) and conditions: blank
 * for a new task, filled in to edit one ([editing] is its current name), or a template's.
 */
@Composable
internal fun TaskSheet(initial: TaskDraft, editing: String?, viewModel: TasksViewModel, onDismiss: () -> Unit, onSave: (TaskDraft) -> Unit) {
    var draft by remember(initial) { mutableStateOf(initial) }
    val setup by viewModel.setup.collectAsStateWithLifecycle()
    val calendar = rememberCalendarAccess(viewModel)
    LaunchedEffect(Unit) { viewModel.refreshSetup() }
    val digest = editing == null && initial.firstRunAt != null
    LocusSheet(
        title = editing?.let { "Edit “$it”" } ?: if (digest) "Morning digest" else "New task",
        subtitle = "Runs on its own, read-only, and notifies you with a short summary.",
        onDismiss = onDismiss,
        action = if (editing != null) "Save" else "Create",
        actionEnabled = draft.valid && (editing == null || draft != initial),
        onAction = { onSave(draft) },
    ) {
        OutlinedTextField(draft.title, { draft = draft.copy(title = it) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(draft.prompt, { draft = draft.copy(prompt = it) }, label = { Text("What should Locus do?") }, minLines = 3, modifier = Modifier.fillMaxWidth())
        Text("Starts", style = LocusType.cardTitle)
        Segmented(
            listOf("On a schedule", "When…"),
            selected = draft.mode.ordinal,
            onSelect = { draft = draft.copy(mode = StartMode.entries[it]) },
            modifier = Modifier.fillMaxWidth(),
            compact = true,
        )
        if (draft.mode == StartMode.SCHEDULE) {
            Text("How often", style = LocusType.cardTitle)
            Segmented(
                ScheduleIntervals.all.map { it.short },
                selected = ScheduleIntervals.indexOf(draft.intervalMinutes),
                onSelect = { draft = draft.copy(intervalMinutes = ScheduleIntervals.all[it].minutes) },
                modifier = Modifier.fillMaxWidth(),
                compact = true,
            )
            if (ScheduleIntervals.indexOf(draft.intervalMinutes) < 0) {
                SupportingText("Now ${ScheduleIntervals.label(draft.intervalMinutes).lowercase()}. Pick one to change it.")
            }
            draft.firstRunAt?.takeIf { editing == null }?.let { at ->
                SupportingText("First run at ${QuietHours.clock(at)}, then once a period. Android may run it a little later while the phone is idle.")
            }
            if (digest) {
                if (!setup.calendarAllowed) CalendarAccessBanner("The digest reads today's calendar entries.", calendar.denied, calendar.request)
                if (setup.ntfyResults) {
                    SupportingText("The digest also goes to your ntfy topic, so it reaches your other devices.")
                } else {
                    SettingsLink(SettingsDestination.Integrations, hint = "To get the digest on your other devices too, set up ntfy in")
                }
            }
        } else {
            TriggerFields(draft, setup, calendar.denied, calendar.request) { draft = it }
        }
        Text("Only run", style = LocusType.cardTitle)
        CardGroup { ConditionSwitches(draft.conditions) { draft = draft.copy(conditions = it) } }
    }
}

/** The three run conditions, as WorkManager constraints (docs/design/battery-thermal.md). */
@Composable
private fun ConditionSwitches(conditions: TaskConditions, onChange: (TaskConditions) -> Unit) {
    SwitchRow("When charging", conditions.requiresCharging, { onChange(conditions.copy(requiresCharging = it)) }, subtitle = "Waits until the phone is plugged in.")
    GroupDivider()
    SwitchRow("On Wi-Fi", conditions.unmeteredOnly, { onChange(conditions.copy(unmeteredOnly = it)) }, subtitle = "Not on mobile data or another metered network.")
    GroupDivider()
    SwitchRow(
        "When the battery isn't low",
        conditions.batteryNotLow,
        { onChange(conditions.copy(batteryNotLow = it)) },
        subtitle = "Skips the time Android shows its low-battery warning.",
    )
}
