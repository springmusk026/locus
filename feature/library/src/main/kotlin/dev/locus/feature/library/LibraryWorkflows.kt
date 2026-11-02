package dev.locus.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.locus.core.agent.workflow.WorkflowInputException
import dev.locus.core.data.repository.ProjectRepository
import dev.locus.core.data.repository.WorkflowRepository
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.ExpandableRow
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.Insets
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.relativeTime
import dev.locus.core.model.SavedWorkflow
import dev.locus.core.model.Workflow
import dev.locus.core.ui.WorkflowDraftState
import dev.locus.core.ui.WorkflowEditorSheet
import dev.locus.core.ui.WorkflowRunSheet
import dev.locus.core.ui.checkHow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Which workflow sheet Library has open. */
sealed interface LibraryWorkflowSheet {
    data class Run(val saved: SavedWorkflow, val error: String? = null) : LibraryWorkflowSheet
    data class Edit(val saved: SavedWorkflow, val error: String? = null) : LibraryWorkflowSheet
    data class Remove(val saved: SavedWorkflow) : LibraryWorkflowSheet
    data class Schedule(val saved: SavedWorkflow, val error: String? = null) : LibraryWorkflowSheet
}

/** Library's workflows: run one in a new chat, edit or remove it (docs/design/workflows.md). */
@HiltViewModel
class LibraryWorkflowsViewModel @Inject constructor(
    private val repository: WorkflowRepository,
    projects: ProjectRepository,
) : ViewModel() {
    val all: StateFlow<List<SavedWorkflow>> = repository.all
    val projectNames: StateFlow<Map<String, String>> = projects.all.map { list -> list.associate { it.id to it.name } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
    private val _sheet = MutableStateFlow<LibraryWorkflowSheet?>(null)
    val sheet: StateFlow<LibraryWorkflowSheet?> = _sheet.asStateFlow()

    init {
        viewModelScope.launch { repository.refresh() }
    }

    fun open(sheet: LibraryWorkflowSheet?) {
        _sheet.value = sheet
    }

    /** Runs [saved] in a new chat (its project's, for a project workflow) and opens it. */
    fun run(saved: SavedWorkflow, values: Map<String, String>, onOpenChat: (String) -> Unit) {
        val rendered = try {
            repository.render(saved.workflow, values)
        } catch (e: WorkflowInputException) {
            _sheet.value = LibraryWorkflowSheet.Run(saved, e.message)
            return
        }
        _sheet.value = null
        viewModelScope.launch { onOpenChat(repository.runInNewChat(rendered, saved.projectId)) }
    }

    fun save(saved: SavedWorkflow, workflow: Workflow) {
        viewModelScope.launch {
            try {
                repository.save(workflow, saved.projectId, replacing = saved.workflow.id)
                _sheet.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _sheet.value = LibraryWorkflowSheet.Edit(saved.copy(workflow = workflow), e.message ?: "Couldn't save the workflow.")
            }
        }
    }

    /** Schedules [saved] with fixed [values]; it then shows in Tasks. */
    fun schedule(saved: SavedWorkflow, values: Map<String, String>, intervalMinutes: Long, onScheduled: () -> Unit) {
        try {
            repository.render(saved.workflow, values)
        } catch (e: WorkflowInputException) {
            _sheet.value = LibraryWorkflowSheet.Schedule(saved, e.message)
            return
        }
        _sheet.value = null
        viewModelScope.launch {
            repository.schedule(saved, values, intervalMinutes)
            onScheduled()
        }
    }

    fun remove(saved: SavedWorkflow) {
        _sheet.value = null
        viewModelScope.launch { repository.delete(saved) }
    }
}

/** The workflows card in Library → Skills, with its sheets. */
@Composable
internal fun WorkflowsCard(viewModel: LibraryWorkflowsViewModel, onOpenChat: (String) -> Unit) {
    val all by viewModel.all.collectAsStateWithLifecycle()
    val names by viewModel.projectNames.collectAsStateWithLifecycle()
    val sheet by viewModel.sheet.collectAsStateWithLifecycle()
    when (val s = sheet) {
        is LibraryWorkflowSheet.Run -> WorkflowRunSheet(
            s.saved.workflow, s.error,
            onRun = { values -> viewModel.run(s.saved, values, onOpenChat) },
            onDismiss = { viewModel.open(null) },
            actionLabel = "Run in a new chat",
        )
        is LibraryWorkflowSheet.Edit -> WorkflowEditorSheet(
            WorkflowDraftState.Ready(s.saved.workflow, s.saved.projectId, replacing = s.saved.workflow.id, error = s.error),
            projectName = s.saved.projectId?.let { names[it] },
            onSave = { w, _, _ -> viewModel.save(s.saved, w) },
            onDismiss = { viewModel.open(null) },
        )
        is LibraryWorkflowSheet.Schedule -> ScheduleSheet(s, viewModel)
        is LibraryWorkflowSheet.Remove -> ConfirmRemove(
            title = "Remove “${s.saved.workflow.name}”?",
            body = "Its file is deleted from this phone. Chats that ran it keep their history.",
            action = "Remove",
            onDismiss = { viewModel.open(null) },
            onConfirm = { viewModel.remove(s.saved) },
        )
        null -> Unit
    }
    if (all.isEmpty()) return
    CardGroup {
        all.forEachIndexed { i, saved ->
            if (i > 0) GroupDivider(inset = Insets.iconRow)
            WorkflowRow(saved, saved.projectId?.let { names[it] ?: "A project" }, viewModel)
        }
    }
}

@Composable
private fun WorkflowRow(saved: SavedWorkflow, project: String?, viewModel: LibraryWorkflowsViewModel) {
    val w = saved.workflow
    ExpandableRow(
        title = w.name,
        subtitle = w.description.ifBlank { w.goal },
        icon = LocusIcons.Play,
        meta = listOfNotNull(project, w.updatedAt.takeIf { it > 0 }?.let { relativeTime(it) }).joinToString(" · ").ifEmpty { null },
    ) {
        Text(w.goal, style = MaterialTheme.typography.bodyMedium)
        if (w.parameters.isNotEmpty()) {
            Text("Inputs: " + w.parameters.joinToString { "${it.title} (${it.type.name.lowercase()})" }, style = MaterialTheme.typography.bodySmall)
        }
        w.steps.forEachIndexed { i, step -> Text("${i + 1}. $step", style = MaterialTheme.typography.bodySmall) }
        if (w.approvals.isNotEmpty()) {
            Text("Asks before: ${w.approvals.joinToString()}", style = MaterialTheme.typography.bodySmall, color = Locus.colors.caution)
        }
        w.checks.forEach { c -> Text("Check: ${c.label} · ${checkHow(c)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        // Four actions: they wrap on a narrow phone instead of squeezing.
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { viewModel.open(LibraryWorkflowSheet.Remove(saved)) }) { Text("Remove", color = Locus.colors.critical) }
            TextButton(onClick = { viewModel.open(LibraryWorkflowSheet.Edit(saved)) }) { Text("Edit") }
            TextButton(onClick = { viewModel.open(LibraryWorkflowSheet.Schedule(saved)) }) { Text("Schedule") }
            TextButton(onClick = { viewModel.open(LibraryWorkflowSheet.Run(saved)) }) { Text("Run") }
        }
    }
}

/** The intervals a scheduled workflow can run at (WorkManager's minimum is 15 minutes). */
private val INTERVALS = listOf("Hourly" to 60L, "Every 6 h" to 360L, "Daily" to 1_440L)

/**
 * "Run on a schedule": fixed inputs and an interval. Scheduled runs are read-only with nobody to ask,
 * so the sheet says up front which of the workflow's steps can't happen unattended.
 */
@Composable
private fun ScheduleSheet(s: LibraryWorkflowSheet.Schedule, viewModel: LibraryWorkflowsViewModel) {
    var interval by remember { mutableStateOf(INTERVALS[2].second) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val limits = remember(s.saved.workflow) { dev.locus.core.agent.workflow.WorkflowScope.unattended(s.saved.workflow) }
    WorkflowRunSheet(
        s.saved.workflow, s.error,
        onRun = { values ->
            viewModel.schedule(s.saved, values, interval) {
                android.widget.Toast.makeText(context, "Scheduled. It's in Tasks.", android.widget.Toast.LENGTH_SHORT).show()
            }
        },
        onDismiss = { viewModel.open(null) },
        actionLabel = "Schedule",
        extra = {
            Text("How often", style = MaterialTheme.typography.titleSmall)
            dev.locus.core.designsystem.Segmented(
                INTERVALS.map { it.first },
                selected = INTERVALS.indexOfFirst { it.second == interval },
                onSelect = { interval = INTERVALS[it].second },
                modifier = Modifier.fillMaxWidth(),
            )
            val lines = buildList {
                add("Scheduled runs are read-only and nobody is there to approve anything.")
                if (limits.needYou.isNotEmpty()) add("Needs your approval, so it's refused on a schedule: ${limits.needYou.joinToString()}.")
                if (limits.changes.isNotEmpty()) add("Changes things, so it's refused on a schedule: ${limits.changes.joinToString()}.")
                add(if (limits.fullyRuns) "Everything this workflow does only reads, so it can run unattended." else "Run it from a chat to do those steps.")
            }
            Text(
                lines.joinToString(" "),
                style = MaterialTheme.typography.bodySmall,
                color = if (limits.fullyRuns) MaterialTheme.colorScheme.onSurfaceVariant else Locus.colors.caution,
            )
        },
    )
}
