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
import dev.locus.core.data.di.IoDispatcher
import dev.locus.core.data.repository.WorkspaceRepository
import kotlinx.coroutines.CoroutineDispatcher
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
    /** The trash entry the [message] can undo (a delete just made), offered as Undo on the snackbar. */
    val undoTrashId: String? = null,
    val loading: Boolean = true,
    /** A file opened from elsewhere (Home, a chat's sent file) that is still being read; the screen shows it loading. */
    val opening: String? = null,
    /** Workspace → Connected folders (docs/design/connected-folders.md), each with how it's doing (null while checking). */
    val connected: List<ConnectedFolderRow> = emptyList(),
    /** Projects a connected folder can be linked to. */
    val projects: List<dev.locus.core.model.Project> = emptyList(),
    /** A folder just picked, waiting for its name before it's connected. */
    val pendingConnect: PendingConnect? = null,
    /** The connected folder whose sheet is open. */
    val managing: String? = null,
    /** Why the open connected folder can't be listed (its grant was lost, it's gone); null when it can. */
    val folderError: String? = null,
) {
    /** The connected folder [folder] is in, or null for a workspace folder. */
    val connectedFolder: dev.locus.core.model.ConnectedFolder?
        get() = folder.split('/').takeIf { it.size >= 2 && it[0] == dev.locus.core.model.ConnectedFolders.PREFIX }
            ?.let { parts -> connected.firstOrNull { it.folder.name == parts[1] }?.folder }
}

/** A connected folder and how it's doing; [status] is null until checked. */
data class ConnectedFolderRow(val folder: dev.locus.core.model.ConnectedFolder, val status: dev.locus.core.data.folders.FolderStatus? = null)

/** What the picker returned and the name being typed; [reconnectId] when it replaces a folder's lost grant. */
data class PendingConnect(val picked: dev.locus.core.data.folders.PickedFolder, val suggestedName: String)

@HiltViewModel
class WorkspaceViewModel @Inject constructor(
    private val repository: WorkspaceRepository,
    private val workspace: Workspace,
    /**
     * Where files are read. Injected rather than Dispatchers.IO so a test's reads run on its scheduler:
     * with the real IO pool, the load resumed on a worker thread under the test's unconfined Main and
     * raced the test thread (the "opening" state was already cleared before it was checked).
     */
    @param:IoDispatcher private val io: CoroutineDispatcher,
    /** At critical heat, running Linux services to suggest stopping; null in tests that don't need it. */
    private val linuxHeat: dev.locus.core.data.device.LinuxHeatAdvice? = null,
    /** Connected folders (docs/design/connected-folders.md); null in tests that don't need them. */
    private val folders: dev.locus.core.data.folders.ConnectedFolderRepository? = null,
    /** The projects a connected folder can be linked to; null in tests that don't need them. */
    private val projects: dev.locus.core.data.repository.ProjectRepository? = null,
) : ViewModel() {

    /** Services to suggest stopping while the phone is critically hot (never stopped without the user). */
    val heatServices: StateFlow<List<String>> = linuxHeat?.suggestion ?: MutableStateFlow(emptyList())

    fun stopServicesForHeat() = linuxHeat?.stopAll()

    fun dismissHeatServices() = linuxHeat?.dismiss()

    private val _uiState = MutableStateFlow(WorkspaceUiState())
    val uiState: StateFlow<WorkspaceUiState> = _uiState.asStateFlow()

    init {
        refresh()
        folders?.let { repo ->
            viewModelScope.launch {
                repo.folders.collect { list ->
                    _uiState.update { s -> s.copy(connected = list.map { f -> ConnectedFolderRow(f, s.connected.firstOrNull { it.folder == f }?.status) }) }
                    checkFolders()
                }
            }
        }
        projects?.let { repo -> viewModelScope.launch { repo.all.collect { list -> _uiState.update { it.copy(projects = list) } } } }
    }

    /** Asks each connected folder's provider how it's doing (one query each). */
    private fun checkFolders() {
        val repo = folders ?: return
        viewModelScope.launch {
            val rows = _uiState.value.connected.map { row -> row.copy(status = runCatching { repo.status(row.folder) }.getOrNull()) }
            _uiState.update { s -> s.copy(connected = s.connected.map { r -> rows.firstOrNull { it.folder.id == r.folder.id } ?: r }) }
        }
    }

    fun refresh() = launchSafely {
        val folder = _uiState.value.folder
        val inConnected = repository.isConnected(folder)
        val entries = try {
            repository.list(folder)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException || !inConnected) throw e
            // A connected folder that can't be listed shows why in place of its files.
            _uiState.update { it.copy(entries = emptyList(), folderError = forUser(e.message), loading = false) }
            checkFolders()
            return@launchSafely
        }
        val trash = repository.trash()
        _uiState.update { it.copy(entries = entries, trash = trash, loading = false, folderError = null) }
    }

    /** Opens connected folder [folder] in the browser. */
    fun openConnected(folder: dev.locus.core.model.ConnectedFolder) {
        openedFromOutside = false
        _uiState.update { it.copy(folder = dev.locus.core.model.ConnectedFolders.pathOf(folder), loading = true, folderError = null, entries = emptyList()) }
        refresh()
    }

    /** The folder picker returned [uri]: name it before connecting, or, for [reconnectId], point that folder at it. */
    fun picked(uri: String, reconnectId: String? = null) = launchSafely {
        val repo = folders ?: return@launchSafely
        if (reconnectId != null) {
            repo.reconnect(reconnectId, uri)
            _uiState.update { it.copy(message = "Reconnected.") }
            checkFolders()
            refresh()
            return@launchSafely
        }
        val picked = repo.describe(uri)
        val taken = _uiState.value.connected.map { it.folder.name }
        _uiState.update { it.copy(pendingConnect = PendingConnect(picked, dev.locus.core.model.ConnectedFolders.suggest(picked.displayName, taken))) }
    }

    fun cancelConnect() = _uiState.update { it.copy(pendingConnect = null) }

    /** Connects the picked folder as [name]. */
    fun connect(name: String, readOnly: Boolean) = launchSafely {
        val pending = _uiState.value.pendingConnect ?: return@launchSafely
        val folder = folders?.connect(pending.picked.uri, name.trim(), readOnly, pending.picked.source) ?: return@launchSafely
        _uiState.update { it.copy(pendingConnect = null, message = "Connected ${folder.name}. Locus sees it as folders/${folder.name}.") }
    }

    fun manage(id: String?) = _uiState.update { it.copy(managing = id) }

    /** Saves a connected folder's switches and project links. */
    fun saveFolder(folder: dev.locus.core.model.ConnectedFolder) = launchSafely { folders?.save(folder) }

    /** Disconnects [id]: Locus loses access; the files stay. */
    fun disconnect(id: String) = launchSafely {
        val name = _uiState.value.connected.firstOrNull { it.folder.id == id }?.folder?.name
        folders?.remove(id)
        val leaving = _uiState.value.connectedFolder?.id == id
        _uiState.update { it.copy(managing = null, message = "Disconnected $name. Its files are untouched.", folder = if (leaving) "." else it.folder) }
        if (leaving) refresh()
    }

    fun open(entry: WorkspaceEntry) {
        // A file picked from the list closes back to the list, whatever opened the screen.
        openedFromOutside = false
        if (entry.isDirectory) {
            _uiState.update { it.copy(folder = entry.path) }
            refresh()
        } else if (repository.isConnected(entry.path)) {
            // A connected folder's file: the viewers read a copy; Open with… and Share use the provider's address.
            launchSafely {
                val (copy, uri) = repository.viewConnected(entry.path)
                _uiState.update { it.copy(openFile = FileLoader.load(entry.path, copy, io).copy(modified = entry.modified, contentUri = uri)) }
            }
        } else {
            launchSafely { _uiState.update { it.copy(openFile = FileLoader.load(entry.path, workspace.resolve(entry.path), io)) } }
        }
    }

    /** Set while a file opened from outside (Home's Recent files) is shown: closing it leaves the screen. */
    private var openedFromOutside = false

    /** Whether [openFromOutside] has run: it acts once per screen, not again after a rotation. */
    private var outsideHandled = false

    /**
     * Opens [path] (relative to the workspace) in the file viewer, as a tap on its row would, for a screen
     * opened on a file from elsewhere (Home's Recent files). Back then returns where it came from rather than
     * to the folder list. A file that has since been moved or deleted leaves the list showing, with a message.
     */
    fun openFromOutside(path: String) {
        if (outsideHandled) return
        outsideHandled = true
        _uiState.update { it.copy(opening = path) }
        launchSafely {
            try {
                val file = runCatching { workspace.resolve(path) }.getOrNull()
                if (file == null || !file.isFile) {
                    _uiState.update { it.copy(message = "${path.substringAfterLast('/')} isn't in the workspace any more. It may have been moved or deleted.") }
                    return@launchSafely
                }
                val loaded = FileLoader.load(path, file, io)
                openedFromOutside = true
                _uiState.update { it.copy(openFile = loaded) }
            } finally {
                _uiState.update { it.copy(opening = null) }
            }
        }
    }

    /** Returns false when already at the root (or closing a file opened from outside), so the screen can pop instead. */
    fun up(): Boolean {
        val state = _uiState.value
        when {
            state.openFile != null && openedFromOutside -> {
                openedFromOutside = false
                return false
            }
            state.openFile != null -> _uiState.update { it.copy(openFile = null) }
            state.showTrash -> _uiState.update { it.copy(showTrash = false) }
            state.folder != "." -> {
                // Up from a connected folder's top goes back to the workspace, where it's listed.
                val parent = state.folder.substringBeforeLast('/', ".").let { if (it == dev.locus.core.model.ConnectedFolders.PREFIX && state.connectedFolder != null) "." else it }
                _uiState.update { it.copy(folder = parent, folderError = null) }
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
        openedFromOutside = false
        val entry = repository.delete(path)
        val what = if (entry.folderId != null) "Deleted from the connected folder; a copy is in Trash." else "Moved to trash. You can restore it from Trash."
        _uiState.update { it.copy(openFile = null, message = what, undoTrashId = entry.id) }
        refresh()
    }

    fun restore(id: String) = launchSafely {
        val path = repository.restore(id)
        _uiState.update { it.copy(message = "Restored $path.", undoTrashId = null) }
        refresh()
    }

    fun purge(id: String) = launchSafely {
        repository.purge(id)
        refresh()
    }

    fun messageShown() = _uiState.update { it.copy(message = null, undoTrashId = null) }

    private fun launchSafely(block: suspend () -> Unit) = viewModelScope.launch {
        runCatching { block() }.onFailure { e -> _uiState.update { it.copy(message = e.message ?: "Something went wrong.", loading = false) } }
    }
}

/** The file layer's message (written for the agent) as the person reads it, beside a Reconnect button. */
internal fun forUser(message: String?): String =
    (message ?: "This folder can't be read.")
        .replace("Ask the user to reconnect it in Workspace → Connected folders.", "Tap Reconnect and pick the folder again.")
