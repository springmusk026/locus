package dev.locus.feature.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.locus.core.designsystem.UiText
import dev.locus.core.designsystem.asString
import dev.locus.core.agent.workflow.WorkflowInputException
import dev.locus.core.data.repository.WorkflowRepository
import dev.locus.core.model.Project
import dev.locus.core.model.SavedWorkflow
import dev.locus.core.model.Workflow
import dev.locus.core.ui.WorkflowDraftState
import dev.locus.core.ui.WorkflowEditorSheet
import dev.locus.core.ui.WorkflowPickerSheet
import dev.locus.core.ui.WorkflowRunSheet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the chat screen offers for workflows; null actions aren't shown. */
class WorkflowActions(
    /** Drafts a workflow from a finished task (its id) and opens the review sheet. */
    val saveFromTask: ((taskId: String) -> Unit)? = null,
    /** Opens the list of workflows to run in this chat. */
    val pick: (() -> Unit)? = null,
)

/** Which workflow sheet is open in a chat. */
sealed interface ChatWorkflowSheet {
    /** [error] says why a save failed; the review sheet shows it from [WorkflowDraftState.Ready.error]. */
    data class Editor(val state: WorkflowDraftState, val error: UiText? = null) : ChatWorkflowSheet
    data object Picker : ChatWorkflowSheet
    data class Run(val saved: SavedWorkflow, val error: UiText? = null) : ChatWorkflowSheet
}

/** Saving a chat's task as a workflow and running one in it (docs/design/workflows.md). */
@HiltViewModel
class ChatWorkflowsViewModel @Inject constructor(private val repository: WorkflowRepository) : ViewModel() {
    private val _sheet = MutableStateFlow<ChatWorkflowSheet?>(null)
    val sheet: StateFlow<ChatWorkflowSheet?> = _sheet.asStateFlow()
    val all = repository.all
    private var drafting: Job? = null

    init {
        viewModelScope.launch { repository.refresh() }
    }

    fun saveFromTask(conversationId: String, taskId: String, projectId: String?) {
        _sheet.value = ChatWorkflowSheet.Editor(WorkflowDraftState.Drafting)
        drafting?.cancel()
        drafting = viewModelScope.launch {
            val state = try {
                WorkflowDraftState.Ready(repository.draft(conversationId, taskId), projectId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _sheet.value = null
                return@launch
            }
            if (_sheet.value is ChatWorkflowSheet.Editor) _sheet.value = ChatWorkflowSheet.Editor(state)
        }
    }

    fun save(workflow: Workflow, projectId: String?, replacing: String?) {
        val ready = (_sheet.value as? ChatWorkflowSheet.Editor)?.state as? WorkflowDraftState.Ready ?: return
        viewModelScope.launch {
            try {
                repository.save(workflow, projectId, replacing)
                _sheet.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _sheet.value = ChatWorkflowSheet.Editor(
                    ready.copy(workflow = workflow, error = null),
                    error = e.message?.let(UiText::Plain) ?: UiText.Res(R.string.chat_workflow_save_failed),
                )
            }
        }
    }

    fun pick() {
        viewModelScope.launch { repository.refresh() }
        _sheet.value = ChatWorkflowSheet.Picker
    }

    fun choose(saved: SavedWorkflow) {
        _sheet.value = ChatWorkflowSheet.Run(saved)
    }

    fun run(conversationId: String, saved: SavedWorkflow, values: Map<String, String>) {
        val rendered = try {
            repository.render(saved.workflow, values)
        } catch (e: WorkflowInputException) {
            // The input's own message (core/agent), shown as it came.
            _sheet.value = ChatWorkflowSheet.Run(saved, e.message?.let(UiText::Plain))
            return
        }
        if (!repository.run(conversationId, rendered)) {
            _sheet.value = ChatWorkflowSheet.Run(saved, UiText.Res(R.string.chat_workflow_busy))
            return
        }
        _sheet.value = null
    }

    fun dismiss() {
        drafting?.cancel()
        _sheet.value = null
    }
}

/** The chat's workflow sheets, and the actions that open them. */
@Composable
internal fun rememberChatWorkflows(conversationId: String, project: Project?, viewModel: ChatWorkflowsViewModel): WorkflowActions {
    val sheet by viewModel.sheet.collectAsStateWithLifecycle()
    val all by viewModel.all.collectAsStateWithLifecycle()
    when (val s = sheet) {
        is ChatWorkflowSheet.Editor -> WorkflowEditorSheet(
            state = (s.state as? WorkflowDraftState.Ready)?.takeIf { s.error != null }?.copy(error = s.error?.asString()) ?: s.state,
            projectName = project?.name,
            onSave = { w, inProject, replacing -> viewModel.save(w, if (inProject) project?.id else null, replacing) },
            onDismiss = viewModel::dismiss,
        )
        ChatWorkflowSheet.Picker -> WorkflowPickerSheet(
            workflows = all.filter { it.projectId == null || it.projectId == project?.id }.sortedBy { if (it.projectId == null) 1 else 0 },
            projectName = project?.name,
            onPick = viewModel::choose,
            onDismiss = viewModel::dismiss,
        )
        is ChatWorkflowSheet.Run -> WorkflowRunSheet(
            workflow = s.saved.workflow,
            error = s.error?.asString(),
            onRun = { values -> viewModel.run(conversationId, s.saved, values) },
            onDismiss = viewModel::dismiss,
        )
        null -> Unit
    }
    return remember(viewModel, conversationId, project?.id) {
        WorkflowActions(
            saveFromTask = { taskId -> viewModel.saveFromTask(conversationId, taskId, project?.id) },
            pick = viewModel::pick,
        )
    }
}
