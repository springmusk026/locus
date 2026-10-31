package dev.locus.feature.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.locus.core.data.tasks.TaskRepository
import dev.locus.core.model.ScheduledTask
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface TasksUiState {
    data object Loading : TasksUiState
    data class Ready(val tasks: List<ScheduledTask>) : TasksUiState
}

@HiltViewModel
class TasksViewModel @Inject constructor(private val repository: TaskRepository) : ViewModel() {
    val uiState: StateFlow<TasksUiState> = repository.tasks
        .map { TasksUiState.Ready(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TasksUiState.Loading)

    fun create(title: String, prompt: String, intervalMinutes: Long) = viewModelScope.launch { repository.create(title, prompt, intervalMinutes) }
    fun setEnabled(id: String, enabled: Boolean) = viewModelScope.launch { repository.setEnabled(id, enabled) }
    fun delete(id: String) = viewModelScope.launch { repository.delete(id) }
    fun runNow(id: String) = repository.runNow(id)
}
