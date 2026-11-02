package dev.locus.feature.tasks

import dev.locus.core.designsystem.LocusSheet
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.locus.core.model.ScheduledTask
import dev.locus.core.model.TaskConditions
import dev.locus.core.model.TaskRunOutcome
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.EmptyState
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.Insets
import dev.locus.core.designsystem.LoadingState
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusTopBar
import dev.locus.core.designsystem.Radius
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.Segmented
import dev.locus.core.designsystem.StatusLine
import dev.locus.core.designsystem.Tone
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.runtime.saveable.rememberSaveable
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.relativeTime
import dev.locus.core.designsystem.AddRow

private val FREQUENCIES = listOf("Hourly" to 60L, "Every 6 hours" to 360L, "Daily" to 1_440L, "Weekly" to 10_080L)

@Composable
fun TasksRoute(onBack: () -> Unit, viewModel: TasksViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            val tasks = (state as? TasksUiState.Ready)?.tasks.orEmpty()
            LocusTopBar(
                "Scheduled tasks",
                onBack = onBack,
                status = {
                    if (tasks.isNotEmpty()) {
                        val active = tasks.count { it.enabled }
                        StatusLine(if (active == 0) "All paused" else "$active of ${tasks.size} on", if (active == 0) Tone.Neutral else Tone.Brand)
                    }
                },
            )
        },
    ) { padding ->
        val tasks = (state as? TasksUiState.Ready)?.tasks
        if (tasks == null) {
            LoadingState(Modifier.padding(padding))
        } else if (tasks.isEmpty()) {
            EmptyState(
                "No scheduled tasks yet",
                "A scheduled task runs on its own, looks things up (read-only: it never changes or sends anything), and notifies you with a short summary. " +
                    "For example: “Every morning, check the weather in Kathmandu and tell me if I need an umbrella.”",
                Modifier.padding(padding),
                icon = LocusIcons.Clock,
            ) { Button(onClick = { adding = true }) { Text("New task") } }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = padding.calculateTopPadding() + Spacing.sm, bottom = Spacing.xxl),
            ) {
                item {
                    CardGroup {
                        tasks.forEachIndexed { i, task ->
                            if (i > 0) GroupDivider(inset = Insets.iconRow)
                            TaskRow(task, viewModel)
                        }
                        GroupDivider(inset = Insets.iconRow)
                        AddRow("New task") { adding = true }
                    }
                }
                item {
                    SectionNote(
                        "Tasks run read-only and need a network connection. Android may delay runs to save battery, and Locus holds a run while the phone is hot (Settings → Battery & heat).",
                        Modifier.padding(top = Spacing.sm, start = Spacing.xs),
                    )
                }
            }
        }
    }
    if (adding) {
        val defaults by viewModel.defaultConditions.collectAsStateWithLifecycle()
        AddTaskDialog(defaults, onDismiss = { adding = false }, onCreate = { t, p, i, c -> viewModel.create(t, p, i, c); adding = false })
    }
}

/** A task: schedule and on/off up front; its prompt, last result and actions when opened. */
@Composable
private fun TaskRow(task: ScheduledTask, viewModel: TasksViewModel) {
    var expanded by rememberSaveable(task.id) { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var editingConditions by remember { mutableStateOf(false) }
    if (editingConditions) {
        var conditions by remember(task.conditions) { mutableStateOf(task.conditions) }
        LocusSheet(
            title = "When “${task.title}” runs",
            subtitle = "Android holds a run until these hold, and stops one that loses them, to try again later.",
            onDismiss = { editingConditions = false },
            action = "Save",
            onAction = { viewModel.setConditions(task.id, conditions); editingConditions = false },
        ) {
            ConditionSwitches(conditions) { conditions = it }
        }
    }
    if (confirmDelete) {
        LocusSheet(
            title = "Delete “${task.title}”?",
            subtitle = "It stops running. Chats from its earlier runs are kept.",
            onDismiss = { confirmDelete = false },
            action = "Delete",
            actionTone = Tone.Critical,
            onAction = { viewModel.delete(task.id); confirmDelete = false },
        ) {}
    }
    val schedule = FREQUENCIES.firstOrNull { it.second == task.intervalMinutes }?.first ?: "Every ${task.intervalMinutes} min"
    Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = Spacing.lg, vertical = Spacing.md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(LocusIcons.Clock, tint = if (task.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f).padding(horizontal = Spacing.md)) {
                Text(task.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val problem = lastRunProblem(task)
                Text(
                    when {
                        !task.enabled -> "$schedule · paused"
                        problem != null -> "$schedule · ${problem.lowercase()} ${task.lastRunAt?.let { relativeTime(it).lowercase() }.orEmpty()}".trimEnd()
                        else -> task.lastRunAt?.let { "$schedule · ran ${relativeTime(it).lowercase()}" } ?: "$schedule · not run yet"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (task.enabled && problem != null) Locus.colors.critical else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(checked = task.enabled, onCheckedChange = { viewModel.setEnabled(task.id, it) })
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(top = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(dev.locus.core.agent.workflow.WorkflowRender.visible(task.prompt), style = MaterialTheme.typography.bodyMedium)
                Text(task.conditions.summary, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                task.lastSummary?.takeIf { it.isNotBlank() }?.let { summary ->
                    Column(
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f), RoundedCornerShape(Radius.control)).padding(Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        val problem = lastRunProblem(task)
                        Text(problem ?: "Last result", style = MaterialTheme.typography.labelMedium, color = if (problem != null) Locus.colors.critical else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(summary.trim(), style = MaterialTheme.typography.bodyMedium, maxLines = 8, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = Locus.colors.critical) }
                    TextButton(onClick = { editingConditions = true }) { Text("Conditions") }
                    TextButton(onClick = { viewModel.runNow(task.id) }) { Text("Run now") }
                }
            }
        }
    }
}

/** "Last run failed" or "Last run stopped" when the last run didn't finish; null when it did (or hasn't run). */
internal fun lastRunProblem(task: ScheduledTask): String? = when (task.lastOutcome) {
    TaskRunOutcome.FAILED -> "Last run failed"
    TaskRunOutcome.STOPPED -> "Last run stopped"
    TaskRunOutcome.DONE, null -> null
}

@Composable
private fun AddTaskDialog(defaults: TaskConditions, onDismiss: () -> Unit, onCreate: (String, String, Long, TaskConditions) -> Unit) {
    var title by remember { mutableStateOf("") }
    var conditions by remember(defaults) { mutableStateOf(defaults) }
    var prompt by remember { mutableStateOf("") }
    var interval by remember { mutableStateOf(1_440L) }
    LocusSheet(
        title = "New scheduled task",
        subtitle = "Runs on its own, read-only, and notifies you with a short summary.",
        onDismiss = onDismiss,
        action = "Create",
        actionEnabled = title.isNotBlank() && prompt.isNotBlank(),
        onAction = { onCreate(title, prompt, interval, conditions) },
    ) {
        OutlinedTextField(title, { title = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(prompt, { prompt = it }, label = { Text("What should Locus do?") }, minLines = 3, modifier = Modifier.fillMaxWidth())
        Text("How often", style = MaterialTheme.typography.labelLarge)
        Segmented(
            listOf("Hourly", "6 hours", "Daily", "Weekly"),
            selected = FREQUENCIES.indexOfFirst { it.second == interval },
            onSelect = { interval = FREQUENCIES[it].second },
            modifier = Modifier.fillMaxWidth(),
            compact = true,
        )
        Text("Only run", style = MaterialTheme.typography.labelLarge)
        ConditionSwitches(conditions) { conditions = it }
    }
}

/** The three run conditions, as WorkManager constraints (docs/design/battery-thermal.md). */
@Composable
private fun ConditionSwitches(conditions: TaskConditions, onChange: (TaskConditions) -> Unit) {
    ConditionRow("When charging", "Waits until the phone is plugged in.", conditions.requiresCharging) { onChange(conditions.copy(requiresCharging = it)) }
    ConditionRow("On Wi-Fi", "Not on mobile data or another metered network.", conditions.unmeteredOnly) { onChange(conditions.copy(unmeteredOnly = it)) }
    ConditionRow("When the battery isn't low", "Skips the time Android shows its low-battery warning.", conditions.batteryNotLow) { onChange(conditions.copy(batteryNotLow = it)) }
}

@Composable
private fun ConditionRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = Spacing.md)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}


