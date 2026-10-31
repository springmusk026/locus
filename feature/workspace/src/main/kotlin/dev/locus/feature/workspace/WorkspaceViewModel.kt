package dev.locus.feature.workspace

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.locus.core.agent.tools.TrashEntry
import dev.locus.core.agent.tools.Workspace
import dev.locus.feature.workspace.preview.FileLoader
import dev.locus.feature.workspace.preview.OpenFile
import dev.locus.core.data.repository.WorkspaceEntry
import dev.locus.core.data.repository.WorkspaceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WorkspaceUiState(
    val folder: String = ".",
    val entries: List<WorkspaceEntry> = emptyList(),
    val openFile: OpenFile? = null,
    val showTrash: Boolean = false,
    val trash: List<TrashEntry> = emptyList(),
    val message: String? = null,
    val loading: Boolean = true,
)

@HiltViewModel
class WorkspaceViewModel @Inject constructor(
    private val repository: WorkspaceRepository,
    private val workspace: Workspace,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WorkspaceUiState())
    val uiState: StateFlow<WorkspaceUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() = launchSafely {
        val folder = _uiState.value.folder
        val entries = repository.list(folder)
        val trash = repository.trash()
        _uiState.update { it.copy(entries = entries, trash = trash, loading = false) }
    }

    fun open(entry: WorkspaceEntry) {
        if (entry.isDirectory) {
            _uiState.update { it.copy(folder = entry.path) }
            refresh()
        } else {
            launchSafely { _uiState.update { it.copy(openFile = FileLoader.load(entry.path, workspace.resolve(entry.path))) } }
        }
    }

    /** Returns false when already at the root, so the screen can pop instead. */
    fun up(): Boolean {
        val state = _uiState.value
        when {
            state.openFile != null -> _uiState.update { it.copy(openFile = null) }
            state.showTrash -> _uiState.update { it.copy(showTrash = false) }
            state.folder != "." -> {
                _uiState.update { it.copy(folder = state.folder.substringBeforeLast('/', ".")) }
                refresh()
            }
            else -> return false
        }
        return true
    }

    fun showTrash(show: Boolean) = _uiState.update { it.copy(showTrash = show) }

    fun import(uris: List<Uri>) = launchSafely {
        uris.forEach { repository.import(it, _uiState.value.folder) }
        _uiState.update { it.copy(message = "Imported ${uris.size} file(s).") }
        refresh()
    }

    fun export(path: String, uri: Uri) = launchSafely {
        repository.export(path, uri)
        _uiState.update { it.copy(message = "Exported ${path.substringAfterLast('/')}.") }
    }

    fun delete(path: String) = launchSafely {
        repository.delete(path)
        _uiState.update { it.copy(openFile = null, message = "Moved to trash. You can restore it from Trash.") }
        refresh()
    }

    fun restore(id: String) = launchSafely {
        val path = repository.restore(id)
        _uiState.update { it.copy(message = "Restored $path.") }
        refresh()
    }

    fun purge(id: String) = launchSafely {
        repository.purge(id)
        refresh()
    }

    fun messageShown() = _uiState.update { it.copy(message = null) }

    private fun launchSafely(block: suspend () -> Unit) = viewModelScope.launch {
        runCatching { block() }.onFailure { e -> _uiState.update { it.copy(message = e.message ?: "Something went wrong.", loading = false) } }
    }
}
