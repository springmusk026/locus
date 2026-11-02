package dev.locus.feature.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.locus.core.agent.workflow.WorkflowRender
import dev.locus.core.data.repository.SettingsRepository
import dev.locus.core.data.tasks.RunNowStatus
import dev.locus.core.data.tasks.TaskRepository
import dev.locus.core.model.ScheduledTask
import dev.locus.core.model.TaskConditions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A task as its row shows it: the task, and its Run now while that is waiting or running. */
data class TaskItem(val task: ScheduledTask, val run: RunNowStatus? = null) {
    val running: Boolean get() = run?.active == true
}

sealed interface TasksUiState {
    data object Loading : TasksUiState
    data class Ready(val tasks: List<TaskItem>) : TasksUiState
    data class Error(val message: String) : TasksUiState
}

/** What the screen tells the user once, as a feedback message. */
sealed interface TaskEvent {
    /** A Run now started from this screen ended: [status] is DONE, SKIPPED or FAILED. [chatId] is the run's chat. */
    data class RunFinished(val title: String, val status: RunNowStatus, val chatId: String?) : TaskEvent

    /** A change didn't save. */
    data class Failed(val message: String) : TaskEvent
}

/** What the task sheet edits: a new task's fields, or an existing one's. */
data class TaskDraft(
    val title: String = "",
    val prompt: String = "",
    val intervalMinutes: Long = dev.locus.core.model.ScheduleIntervals.DEFAULT.minutes,
    val conditions: TaskConditions = TaskConditions(),
) {
    val valid: Boolean get() = title.isNotBlank() && prompt.isNotBlank()

    companion object {
        /** [task]'s fields to edit; a scheduled workflow's plan stays hidden (and is kept on save). */
        fun of(task: ScheduledTask) = TaskDraft(task.title, WorkflowRender.visible(task.prompt), task.intervalMinutes, task.conditions)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TasksViewModel @Inject constructor(
    private val repository: TaskRepository,
    settings: SettingsRepository,
) : ViewModel() {
    /** What a new task starts with (Settings → Battery & heat). */
    val defaultConditions: StateFlow<TaskConditions> = settings.settings.map { it.power.taskDefaults }
        .stateIn(viewModelScope, SharingStarted.Eagerly, TaskConditions())

    private val attempt = MutableStateFlow(0)

    val uiState: StateFlow<TasksUiState> = attempt
        .flatMapLatest {
            repository.tasks
                .flatMapLatest(::withRunStatus)
                .catch { e -> emit(TasksUiState.Error(e.message ?: "The task list couldn't be read.")) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TasksUiState.Loading)

    private val events = Channel<TaskEvent>(Channel.BUFFERED)

    /** One-shot messages for the screen to show ([TaskEvent]). */
    val eventFlow: Flow<TaskEvent> = events.receiveAsFlow()

    /** Tasks whose Run now this screen is following to its end. */
    private val following = MutableStateFlow<Set<String>>(emptySet())

    /** Reads the task list again after it failed. */
    fun retry() = attempt.update { it + 1 }

    fun create(draft: TaskDraft) = mutate("Couldn't create the task.") {
        repository.create(draft.title, draft.prompt, draft.intervalMinutes, draft.conditions)
    }

    fun update(id: String, draft: TaskDraft) = mutate("Couldn't save the task.") {
        check(repository.update(id, draft.title, draft.prompt, draft.intervalMinutes, draft.conditions)) { "gone" }
    }

    fun setEnabled(id: String, enabled: Boolean) = mutate(if (enabled) "Couldn't turn the task on." else "Couldn't pause the task.") {
        repository.setEnabled(id, enabled)
    }

    fun delete(id: String) = mutate("Couldn't delete the task.") { repository.delete(id) }

    /**
     * Runs [id] now. Its row shows it queued, then running; when it ends, [eventFlow] says how (once),
     * with the run's chat to open.
     */
    fun runNow(id: String) {
        try {
            repository.runNow(id)
        } catch (e: Exception) {
            events.trySend(TaskEvent.Failed("Couldn't start the task."))
            return
        }
        if (id in following.value) return
        following.update { it + id }
        viewModelScope.launch {
            try {
                // An older run's finished work may show first, until WorkManager has the new one.
                val end = repository.runNowStatus(id).dropWhile { it?.active != true }.first { it != null && !it.active }!!
                val task = repository.tasks.first().firstOrNull { it.id == id } ?: return@launch
                events.send(TaskEvent.RunFinished(task.title, end, task.lastConversationId))
            } finally {
                following.update { it - id }
            }
        }
    }

    private fun withRunStatus(tasks: List<ScheduledTask>): Flow<TasksUiState> =
        if (tasks.isEmpty()) {
            flowOf(TasksUiState.Ready(emptyList()))
        } else {
            combine(tasks.map { task -> repository.runNowStatus(task.id).map { TaskItem(task, it?.takeIf(RunNowStatus::active)) } }) {
                TasksUiState.Ready(it.toList())
            }
        }

    private fun mutate(failure: String, block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            events.send(TaskEvent.Failed(failure))
        }
    }
}
