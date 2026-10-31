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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.locus.core.model.ScheduledTask
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.EmptyState
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconTile
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
        val tasks = (state as? TasksUiState.Ready)?.tasks ?: return@Scaffold
        if (tasks.isEmpty()) {
            EmptyState(
                "Let Locus keep an eye on things",
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
                            if (i > 0) GroupDivider(inset = Spacing.lg + 36.dp + Spacing.md)
                            TaskRow(task, viewModel)
                        }
                        GroupDivider(inset = Spacing.lg + 36.dp + Spacing.md)
                        Row(
                            Modifier.fillMaxWidth().clickable { adding = true }.padding(horizontal = Spacing.lg, vertical = Spacing.md),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconTile(LocusIcons.Add, tint = MaterialTheme.colorScheme.primary)
                            Text("New task", Modifier.padding(start = Spacing.md), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                item { SectionNote("Tasks run read-only and need a network connection. Android may delay runs to save battery.", Modifier.padding(top = Spacing.sm, start = Spacing.xs)) }
            }
        }
    }
    if (adding) AddTaskDialog(onDismiss = { adding = false }, onCreate = { t, p, i -> viewModel.create(t, p, i); adding = false })
}

/** A task: schedule and on/off up front; its prompt, last result and actions when opened. */
@Composable
private fun TaskRow(task: ScheduledTask, viewModel: TasksViewModel) {
    var expanded by rememberSaveable(task.id) { mutableStateOf(false) }
    val schedule = FREQUENCIES.firstOrNull { it.second == task.intervalMinutes }?.first ?: "Every ${task.intervalMinutes} min"
    Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = Spacing.lg, vertical = Spacing.md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(LocusIcons.Clock, tint = if (task.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f).padding(horizontal = Spacing.md)) {
                Text(task.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (!task.enabled) "$schedule · paused" else task.lastRunAt?.let { "$schedule · ran ${relativeTime(it).lowercase()}" } ?: "$schedule · not run yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(checked = task.enabled, onCheckedChange = { viewModel.setEnabled(task.id, it) })
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(top = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(task.prompt, style = MaterialTheme.typography.bodyMedium)
                task.lastSummary?.takeIf { it.isNotBlank() }?.let { summary ->
                    Column(
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f), RoundedCornerShape(Radius.control)).padding(Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        Text("Last result", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(summary.trim(), style = MaterialTheme.typography.bodyMedium, maxLines = 8, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { viewModel.delete(task.id) }) { Text("Delete", color = Locus.colors.critical) }
                    TextButton(onClick = { viewModel.runNow(task.id) }) { Text("Run now") }
                }
            }
        }
    }
}

@Composable
private fun AddTaskDialog(onDismiss: () -> Unit, onCreate: (String, String, Long) -> Unit) {
    var title by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var interval by remember { mutableStateOf(1_440L) }
    LocusSheet(
        title = "New scheduled task",
        subtitle = "Runs on its own, read-only, and notifies you with a short summary.",
        onDismiss = onDismiss,
        action = "Create",
        actionEnabled = title.isNotBlank() && prompt.isNotBlank(),
        onAction = { onCreate(title, prompt, interval) },
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
    }
}


