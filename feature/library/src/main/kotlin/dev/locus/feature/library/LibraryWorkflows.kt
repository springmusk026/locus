package dev.locus.feature.library

import androidx.compose.runtime.mutableLongStateOf
import dev.locus.core.designsystem.shortRes
import dev.locus.core.ui.workflowToolName
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.locus.core.agent.workflow.WorkflowInputException
import dev.locus.core.data.repository.ProjectRepository
import dev.locus.core.data.repository.WorkflowRepository
import dev.locus.core.designsystem.Banner
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.ExpandableRow
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.Insets
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.UiText
import dev.locus.core.designsystem.asString
import dev.locus.core.designsystem.relativeTime
import dev.locus.core.model.SavedWorkflow
import dev.locus.core.model.TaskBudget
import dev.locus.core.model.Workflow
import dev.locus.core.ui.WorkflowDraftState
import dev.locus.core.ui.WorkflowEditorSheet
import dev.locus.core.ui.RunWorkflowSheet
import dev.locus.core.ui.isBuiltInResearch
import dev.locus.core.ui.shownWorkflow
import dev.locus.core.ui.checkHow
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Which workflow sheet Library has open. */
sealed interface LibraryWorkflowSheet {
    /** [lacksTools]: a new chat's model can't call tools, so the sheet explains instead of running. */
    data class Run(val saved: SavedWorkflow, val error: UiText? = null, val lacksTools: Boolean = false) : LibraryWorkflowSheet
    data class Edit(val saved: SavedWorkflow, val error: UiText? = null) : LibraryWorkflowSheet

    /** A copy of a built-in workflow to edit and save as the user's own; the built-in one stays as it is. */
    data class Duplicate(val saved: SavedWorkflow, val error: UiText? = null) : LibraryWorkflowSheet
    data class Remove(val saved: SavedWorkflow) : LibraryWorkflowSheet
    data class Schedule(val saved: SavedWorkflow, val error: UiText? = null) : LibraryWorkflowSheet
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
        // A run starts a new chat on the default model: say so up front if that model can't call tools.
        if (sheet is LibraryWorkflowSheet.Run) {
            viewModelScope.launch {
                val lacks = repository.lacksTools(null)
                (_sheet.value as? LibraryWorkflowSheet.Run)?.takeIf { it.saved == sheet.saved }?.let { _sheet.value = it.copy(lacksTools = lacks) }
            }
        }
    }

    /** Runs [saved] in a new chat (its project's, for a project workflow) and opens it; [budget] is the research workflow's. */
    fun run(saved: SavedWorkflow, values: Map<String, String>, onOpenChat: (String) -> Unit, budget: TaskBudget? = null) {
        val rendered = try {
            repository.render(saved, values, budget)
        } catch (e: WorkflowInputException) {
            _sheet.value = LibraryWorkflowSheet.Run(saved, e.message?.let(UiText::Plain), (_sheet.value as? LibraryWorkflowSheet.Run)?.lacksTools == true)
            return
        }
        _sheet.value = null
        viewModelScope.launch {
            try {
                onOpenChat(repository.runInNewChat(rendered, saved.projectId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _sheet.value = LibraryWorkflowSheet.Run(saved, e.message?.let(UiText::Plain) ?: UiText.Res(R.string.library_workflows_start_failed))
            }
        }
    }

    /** Saves an edited copy of a built-in workflow as a new workflow of the user's own. */
    fun saveCopy(saved: SavedWorkflow, workflow: Workflow) {
        viewModelScope.launch {
            try {
                repository.save(workflow, projectId = null, replacing = null)
                _sheet.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _sheet.value = LibraryWorkflowSheet.Duplicate(saved.copy(workflow = workflow), e.message?.let(UiText::Plain) ?: UiText.Res(R.string.library_workflows_save_failed))
            }
        }
    }

    fun save(saved: SavedWorkflow, workflow: Workflow) {
        viewModelScope.launch {
            try {
                repository.save(workflow, saved.projectId, replacing = saved.workflow.id)
                _sheet.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _sheet.value = LibraryWorkflowSheet.Edit(saved.copy(workflow = workflow), e.message?.let(UiText::Plain) ?: UiText.Res(R.string.library_workflows_save_failed))
            }
        }
    }

    /** Schedules [saved] with fixed [values]; it then shows in Tasks. */
    fun schedule(saved: SavedWorkflow, values: Map<String, String>, intervalMinutes: Long, onScheduled: () -> Unit) {
        try {
            repository.render(saved, values)
        } catch (e: WorkflowInputException) {
            _sheet.value = LibraryWorkflowSheet.Schedule(saved, e.message?.let(UiText::Plain))
            return
        }
        _sheet.value = null
        viewModelScope.launch {
            try {
                repository.schedule(saved, values, intervalMinutes)
                onScheduled()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _sheet.value = LibraryWorkflowSheet.Schedule(saved, e.message?.let(UiText::Plain) ?: UiText.Res(R.string.library_workflows_schedule_failed))
            }
        }
    }

    private val _error = MutableStateFlow<UiText?>(null)

    /** Why the last remove failed, shown above the workflows until dismissed. */
    val error: StateFlow<UiText?> = _error.asStateFlow()

    fun dismissError() { _error.value = null }

    fun remove(saved: SavedWorkflow) {
        _sheet.value = null
        viewModelScope.launch {
            try {
                repository.delete(saved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = UiText.Res(
                    R.string.library_workflows_remove_failed,
                    saved.workflow.name,
                    e.message?.let(UiText::Plain) ?: UiText.Res(R.string.library_workflows_something_wrong),
                )
            }
        }
    }
}

/** The workflows card in Library → Skills, with its sheets. */
@Composable
internal fun WorkflowsCard(viewModel: LibraryWorkflowsViewModel, onOpenChat: (String) -> Unit) {
    val all by viewModel.all.collectAsStateWithLifecycle()
    val names by viewModel.projectNames.collectAsStateWithLifecycle()
    val sheet by viewModel.sheet.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    when (val s = sheet) {
        is LibraryWorkflowSheet.Run -> RunWorkflowSheet(
            s.saved, s.error?.asString(),
            lacksTools = s.lacksTools,
            onRun = { values, budget -> viewModel.run(s.saved, values, onOpenChat, budget) },
            onDismiss = { viewModel.open(null) },
            actionLabel = stringResource(R.string.library_workflows_run_in_chat),
        )
        is LibraryWorkflowSheet.Duplicate -> {
            // The copy starts from the built-in workflow's own (English) plan, named in the app's language.
            val copyName = stringResource(R.string.library_workflows_copy_name, shownWorkflow(s.saved).name)
            val copy = remember(s.saved) { s.saved.workflow.copy(name = copyName, createdAt = 0, updatedAt = 0) }
            WorkflowEditorSheet(
                WorkflowDraftState.Ready(copy, projectId = null, replacing = null, error = s.error?.asString()),
                projectName = null,
                onSave = { w, _, _ -> viewModel.saveCopy(s.saved, w) },
                onDismiss = { viewModel.open(null) },
            )
        }
        is LibraryWorkflowSheet.Edit -> WorkflowEditorSheet(
            WorkflowDraftState.Ready(s.saved.workflow, s.saved.projectId, replacing = s.saved.workflow.id, error = s.error?.asString()),
            projectName = s.saved.projectId?.let { names[it] },
            onSave = { w, _, _ -> viewModel.save(s.saved, w) },
            onDismiss = { viewModel.open(null) },
        )
        is LibraryWorkflowSheet.Schedule -> ScheduleSheet(s, viewModel)
        is LibraryWorkflowSheet.Remove -> ConfirmRemove(
            title = stringResource(R.string.library_workflows_remove_title, s.saved.workflow.name),
            body = stringResource(R.string.library_workflows_remove_body),
            action = stringResource(R.string.library_action_remove),
            onDismiss = { viewModel.open(null) },
            onConfirm = { viewModel.remove(s.saved) },
        )
        null -> Unit
    }
    if (all.isEmpty() && error == null) return
    Column {
        error?.let { message ->
            Banner(message.asString(), Tone.Critical, Modifier.padding(bottom = Spacing.sm)) {
                TextButton(onClick = viewModel::dismissError) { Text(stringResource(R.string.library_action_dismiss)) }
            }
        }
        if (all.isNotEmpty()) CardGroup {
            all.forEachIndexed { i, saved ->
                if (i > 0) GroupDivider(inset = Insets.iconRow)
                WorkflowRow(saved, saved.projectId?.let { names[it] ?: stringResource(R.string.library_workflows_a_project) }, viewModel)
            }
        }
    }
}

@Composable
private fun WorkflowRow(saved: SavedWorkflow, project: String?, viewModel: LibraryWorkflowsViewModel) {
    // A built-in workflow shows its translated name, inputs and phases; a saved one, the user's own words.
    val w = shownWorkflow(saved)
    ExpandableRow(
        title = w.name,
        subtitle = w.description.ifBlank { w.goal },
        icon = if (saved.builtIn) LocusIcons.Search else LocusIcons.Play,
        iconDescription = stringResource(if (saved.builtIn) R.string.library_workflows_icon_built_in else if (project != null) R.string.library_workflows_icon_project else R.string.library_workflows_icon),
        meta = listOfNotNull(
            if (saved.builtIn) stringResource(R.string.library_workflows_built_in) else null,
            project,
            w.updatedAt.takeIf { it > 0 }?.let { relativeTime(it) },
        ).joinToString(" · ").ifEmpty { null },
    ) {
        if (!saved.builtIn) Text(w.goal, style = LocusType.rowSupporting)
        if (w.parameters.isNotEmpty()) {
            Text(stringResource(R.string.library_workflows_inputs, w.parameters.joinToString { "${it.title} (${it.type.name.lowercase()})" }), style = LocusType.supporting)
        }
        w.steps.forEachIndexed { i, step -> Text("${i + 1}. $step", style = LocusType.supporting) }
        if (w.approvals.isNotEmpty()) {
            Text(stringResource(R.string.library_workflows_asks_before, w.approvals.map { workflowToolName(it) }.distinct().joinToString()), style = LocusType.supporting, color = Locus.colors.onCautionContainer)
        }
        w.checks.forEach { c -> SupportingText(stringResource(R.string.library_workflows_check, c.label, checkHow(c))) }
        // Four actions: they wrap on a narrow phone instead of squeezing.
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (saved.builtIn) {
                // Built in: it can't be removed or changed, only copied into a workflow of the user's own.
                TextButton(onClick = { viewModel.open(LibraryWorkflowSheet.Duplicate(saved)) }) { Text(stringResource(R.string.library_workflows_duplicate)) }
            } else {
                TextButton(onClick = { viewModel.open(LibraryWorkflowSheet.Remove(saved)) }) { Text(stringResource(R.string.library_action_remove), color = Locus.colors.critical) }
                TextButton(onClick = { viewModel.open(LibraryWorkflowSheet.Edit(saved)) }) { Text(stringResource(R.string.library_action_edit)) }
            }
            TextButton(onClick = { viewModel.open(LibraryWorkflowSheet.Schedule(saved)) }) { Text(stringResource(R.string.library_workflows_schedule)) }
            TextButton(onClick = { viewModel.open(LibraryWorkflowSheet.Run(saved)) }) { Text(stringResource(R.string.library_workflows_run)) }
        }
    }
}

/** The intervals a scheduled workflow can run at: the same as Tasks'. */
private val INTERVALS = dev.locus.core.model.ScheduleIntervals.all

/**
 * "Run on a schedule": fixed inputs and an interval. Scheduled runs are read-only with nobody to ask,
 * so the sheet says up front which of the workflow's steps can't happen unattended.
 */
@Composable
private fun ScheduleSheet(s: LibraryWorkflowSheet.Schedule, viewModel: LibraryWorkflowsViewModel) {
    var interval by remember { mutableLongStateOf(dev.locus.core.model.ScheduleIntervals.DEFAULT.minutes) }
    val feedback = Locus.feedback
    val scheduledMessage = stringResource(R.string.library_workflows_scheduled)
    val limits = remember(s.saved.workflow) { dev.locus.core.agent.workflow.WorkflowScope.unattended(s.saved.workflow) }
    val research = isBuiltInResearch(s.saved)
    RunWorkflowSheet(
        s.saved, s.error?.asString(),
        // A scheduled run starts a chat on the default model, like Run; the research sheet says what it can't do unattended.
        lacksTools = false,
        scheduled = true,
        onRun = { values, _ ->
            viewModel.schedule(s.saved, values, interval) { feedback.show(scheduledMessage, Tone.Live) }
        },
        onDismiss = { viewModel.open(null) },
        actionLabel = stringResource(R.string.library_workflows_schedule),
        extra = {
            Text(stringResource(R.string.library_workflows_how_often), style = LocusType.cardTitle)
            dev.locus.core.designsystem.Segmented(
                INTERVALS.map { stringResource(it.shortRes) },
                selected = dev.locus.core.model.ScheduleIntervals.indexOf(interval),
                onSelect = { interval = INTERVALS[it].minutes },
                modifier = Modifier.fillMaxWidth(),
                compact = true,
            )
            // The research sheet says in its own words what a scheduled run can't do.
            if (research) return@RunWorkflowSheet
            val lines = buildList {
                add(stringResource(R.string.library_workflows_schedule_read_only))
                if (limits.needYou.isNotEmpty()) add(stringResource(R.string.library_workflows_schedule_need_you, limits.needYou.map { workflowToolName(it) }.distinct().joinToString()))
                if (limits.changes.isNotEmpty()) add(stringResource(R.string.library_workflows_schedule_changes, limits.changes.map { workflowToolName(it) }.distinct().joinToString()))
                if (limits.noTools) add(stringResource(R.string.library_workflows_schedule_no_tools))
                add(stringResource(if (limits.fullyRuns) R.string.library_workflows_schedule_only_reads else R.string.library_workflows_schedule_run_from_chat))
            }
            Text(
                lines.joinToString(" "),
                style = LocusType.supporting,
                color = if (limits.fullyRuns) MaterialTheme.colorScheme.onSurfaceVariant else Locus.colors.onCautionContainer,
            )
        },
    )
}
