package dev.locus.feature.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.locus.core.data.mcp.McpTemplate
import dev.locus.core.data.repository.Library
import dev.locus.core.agent.home.AgentHome
import dev.locus.core.agent.home.SkillBundle
import dev.locus.core.agent.home.SkillReview
import dev.locus.core.data.repository.LibraryRepository
import dev.locus.core.data.repository.SkillClash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface SignInStatus {
    data class Working(val label: String) : SignInStatus
    data class Failed(val message: String, val title: String = "Couldn't sign in") : SignInStatus
}

/** Where a skill import is: fetching, failed, waiting for the user's review, or saved as a draft. */
sealed interface SkillImportStatus {
    data class Working(val label: String) : SkillImportStatus
    data class Failed(val message: String) : SkillImportStatus
    /**
     * Fetched and checked: the user reads its manifest (and, when [clash] isn't None, how it changes
     * the skill of the same name) and then saves it, renames it, or replaces the one there.
     */
    data class Review(val bundle: SkillBundle, val review: SkillReview, val clash: SkillClash) : SkillImportStatus
    data class Imported(val name: String) : SkillImportStatus
}

/** A file from a skill, opened for review. [text] is null for binary files. */
data class SkillFileView(val skill: String, val path: String, val size: Int, val text: String?)

sealed interface LibraryUiState {
    data object Loading : LibraryUiState
    data class Ready(val library: Library) : LibraryUiState
    /** Reading the library failed; [retryLoad][LibraryViewModel.retryLoad] tries again. */
    data class Failed(val message: String) : LibraryUiState
}

/**
 * The library as screen states: Ready for each read, Failed when reading throws. [reloading] (a retry)
 * shows Loading first. Cancellation is passed on, never shown as a failure.
 */
internal fun Flow<Library>.asUiStates(reloading: Boolean): Flow<LibraryUiState> =
    map<Library, LibraryUiState> { LibraryUiState.Ready(it) }
        .onStart { if (reloading) emit(LibraryUiState.Loading) }
        .catch { e ->
            if (e is CancellationException) throw e
            emit(LibraryUiState.Failed(e.message?.takeIf { it.isNotBlank() } ?: "Something went wrong reading Locus's files."))
        }

/**
 * A Library action that failed (approve, remove, save, forget…): shown as a banner. [retry] runs the
 * same action again.
 */
data class LibraryError(val title: String, val message: String, val retry: (() -> Unit)? = null)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(private val repository: LibraryRepository) : ViewModel() {

    /** Bumped by [retryLoad] to read the library again after a failure. */
    private val attempt = MutableStateFlow(0)

    val uiState: StateFlow<LibraryUiState> = attempt
        .flatMapLatest { n -> repository.library.asUiStates(reloading = n > 0) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState.Loading)

    init {
        repository.refresh()
    }

    /** Reads the library again, after [LibraryUiState.Failed]. */
    fun retryLoad() {
        attempt.value++
        repository.refresh()
    }

    private val _error = MutableStateFlow<LibraryError?>(null)

    /** The last action that failed, until it's dismissed or retried. */
    val error: StateFlow<LibraryError?> = _error.asStateFlow()

    fun dismissError() { _error.value = null }

    /** Saves a memory topic: in general memory, or with [projectId] in that project's memory. */
    fun saveMemory(topic: String, summary: String, content: String, projectId: String? = null) =
        act("Couldn't save “${label(topic)}”") { repository.saveMemory(topic, summary, content, projectId) }
    /** Forgets a memory topic from general memory, or with [projectId] from that project's memory. */
    fun forgetMemory(topic: String, projectId: String? = null) =
        act("Couldn't forget “${label(topic)}”") { repository.forgetMemory(topic, projectId) }
    /** Moves a project's memory topic to general memory, where every chat can use it. */
    fun moveMemoryToGeneral(projectId: String, topic: String) =
        act("Couldn't move “${label(topic)}”") { repository.moveMemoryToGeneral(projectId, topic) }
    fun approveSkill(name: String) = act("Couldn't approve “$name”") { repository.approveSkill(name) }
    fun rejectSkill(name: String) = act("Couldn't reject “$name”") { repository.rejectSkill(name) }
    fun removeSkill(name: String) = act("Couldn't remove “$name”") { repository.removeSkill(name) }
    fun approveConnector(id: String) = act("Couldn't approve the connector") { repository.approveConnector(id) }
    fun approveAgent(name: String) = act("Couldn't approve “$name”") { repository.approveAgent(name) }
    fun rejectAgent(name: String) = act("Couldn't reject “$name”") { repository.rejectAgent(name) }
    fun removeAgent(name: String) = act("Couldn't remove “$name”") { repository.removeAgent(name) }
    fun rejectConnector(id: String) = act("Couldn't reject the connector") { repository.rejectConnector(id) }
    fun removeConnector(id: String) = act("Couldn't remove the connector") { repository.removeConnector(id) }
    fun setSecret(connectorId: String, name: String, value: String) = act("Couldn't save the key") { repository.setConnectorSecret(name, value, connectorId) }
    /** The user confirmed where an older saved key goes (it was saved before Locus recorded that). */
    fun confirmKey(id: String) = act("Couldn't confirm the key") { repository.confirmConnectorKey(id) }
    fun addMcpServer(name: String, url: String, token: String?) = signingIn(name) { repository.addMcpServer(name, url, token) }
    /** Adds a server from the catalogue; signing in, when it needs to, shows in the same banner. */
    /** [useSavedKey]: copy the built-in integration's saved key (inside the vault; it never comes here). */
    fun addMcpTemplate(template: McpTemplate, url: String, key: String?, env: List<Pair<String, String>>, useSavedKey: Boolean = false) {
        if (template.local == null && template.signIn && key.isNullOrBlank() && !useSavedKey) {
            signingIn(template.name) { repository.addMcpTemplate(template, url, key, env) }
            return
        }
        viewModelScope.launch {
            try {
                repository.addMcpTemplate(template, url, key, env, useSavedKey)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _signIn.value = SignInStatus.Failed(e.message ?: "Couldn't add the server.", title = "Couldn't add ${template.name}")
            }
        }
    }
    /** Signs in to connector [id]; [name] is what the banner calls it. */
    fun signIn(id: String, name: String) = signingIn(name) { repository.signIn(id) }

    /** Saves a local (Linux) MCP server; [env] pairs are variable name and secret value. Nothing starts yet. */
    fun addLocalMcpServer(name: String, command: String, args: List<String>, env: List<Pair<String, String>>) = viewModelScope.launch {
        try {
            repository.addLocalMcpServer(name, command, args, env)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _signIn.value = SignInStatus.Failed(e.message ?: "Couldn't add the server.", title = "Couldn't add the server")
        }
    }
    /** Starts begun here, so Stop can cancel one that is still starting (the process is stopped too). */
    private val starts = mutableMapOf<String, kotlinx.coroutines.Job>()

    fun startLocalMcp(id: String) {
        starts[id]?.cancel()
        val job = act("Couldn't start the server") { repository.startLocalMcp(id) }
        starts[id] = job
        job.invokeOnCompletion { if (starts[id] === job) starts.remove(id) }
    }

    /** Cancels a start begun here; one a chat began finishes starting first, then stops. */
    fun stopLocalMcp(id: String) {
        starts.remove(id)?.cancel()
        act("Couldn't stop the server") { repository.stopLocalMcp(id) }
    }
    fun dismissSignIn() { _signIn.value = null }

    private val _signIn = MutableStateFlow<SignInStatus?>(null)

    /** Progress or the outcome of an MCP sign-in, shown as a banner on the Connectors tab. */
    val signInStatus: StateFlow<SignInStatus?> = _signIn

    private var signInJob: kotlinx.coroutines.Job? = null

    /** Stops waiting for the browser; the listener closes and nothing is saved. */
    fun cancelSignIn() {
        signInJob?.cancel()
        _signIn.value = null
    }

    private fun signingIn(label: String, block: suspend () -> Unit) {
        signInJob?.cancel()
        signInJob = viewModelScope.launch {
            _signIn.value = SignInStatus.Working(label)
            val result = runCatching { block() }
            if (result.exceptionOrNull() is CancellationException) return@launch
            _signIn.value = result.fold(onSuccess = { null }, onFailure = { SignInStatus.Failed(it.message ?: "Sign-in failed.") })
        }
    }

    // ---- Skill import ---------------------------------------------------------------------------

    private val _import = MutableStateFlow<SkillImportStatus?>(null)

    /** Progress or the outcome of an import, shown as a banner on the Skills tab. */
    val importStatus: StateFlow<SkillImportStatus?> = _import

    private var importJob: kotlinx.coroutines.Job? = null

    fun importSkillFromGitHub(url: String) = importing("Downloading from GitHub") { repository.fetchSkill(url) }

    fun importSkillZip(uri: Uri) = importing("Reading the zip") { repository.readSkillZip(uri) }

    /**
     * Saves the reviewed import under its own name: a new draft, or, when the name is taken, a new
     * version of that skill once approved. When its manifest grows, [acceptedNewPermissions] must be
     * true: the sheet asks for that before it enables Replace.
     */
    fun saveImport(acceptedNewPermissions: Boolean = false) {
        val review = _import.value as? SkillImportStatus.Review ?: return
        if (review.review.diff?.grown == true && !acceptedNewPermissions) return
        save(review.bundle, review.bundle.name)
    }

    /** Saves the reviewed import under [name]; check it with [renameProblem] first. */
    fun renameImport(name: String) {
        val review = _import.value as? SkillImportStatus.Review ?: return
        if (renameProblem(name.trim()) == null) save(review.bundle, name.trim())
    }

    /** Why [name] can't be used for an import, or null if it can. */
    fun renameProblem(name: String): String? = when {
        !AgentHome.SKILL_NAME.matches(name) -> "Use lowercase letters, digits and single hyphens."
        repository.skillClash(name) != SkillClash.None -> "That name is taken too."
        else -> null
    }

    fun cancelImport() {
        importJob?.cancel()
        _import.value = null
    }

    private fun importing(label: String, fetch: suspend () -> SkillBundle) {
        importJob?.cancel()
        importJob = viewModelScope.launch {
            _import.value = SkillImportStatus.Working(label)
            _import.value = try {
                val bundle = fetch()
                // Nothing is saved before the user has read what it declares (and what it changes).
                SkillImportStatus.Review(bundle, repository.reviewImport(bundle), repository.skillClash(bundle.name))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SkillImportStatus.Failed(e.message ?: "The import failed.")
            }
        }
    }

    private fun save(bundle: SkillBundle, name: String) {
        importJob = viewModelScope.launch { saveNow(bundle, name) }
    }

    private suspend fun saveNow(bundle: SkillBundle, name: String) {
        _import.value = try {
            repository.saveImportedSkill(bundle, name)
            SkillImportStatus.Imported(name)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SkillImportStatus.Failed(e.message ?: "Couldn't save the skill.")
        }
    }

    private val _file = MutableStateFlow<SkillFileView?>(null)

    /** A skill file opened for review. */
    val openFile: StateFlow<SkillFileView?> = _file

    fun openSkillFile(skill: AgentHome.Skill, path: String) = act("Couldn't open $path") {
        val bytes = repository.skillFile(skill.name, path, skill.draft) ?: throw IllegalStateException("The file isn't in the skill any more.")
        _file.value = SkillFileView(skill.name, path, bytes.size, textOf(bytes))
    }

    fun closeSkillFile() { _file.value = null }

    /**
     * Runs a Library action. A failure is shown as [error] titled [title], with a retry, instead of
     * crashing the app; cancellation (leaving the screen, Stop) is passed on, never reported.
     */
    private fun act(title: String, block: suspend () -> Unit): Job = viewModelScope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // What did change before the failure shows; nothing is left half-drawn.
            repository.refresh()
            _error.value = LibraryError(
                title = title,
                message = e.message?.takeIf { it.isNotBlank() } ?: "Something went wrong.",
                retry = { _error.value = null; act(title, block) },
            )
        }
    }

    /** A memory topic's name as Library shows it. */
    private fun label(topic: String) = topic.replace('-', ' ')

    private companion object {
        const val PREVIEW_BYTES = 64 * 1024

        /** UTF-8 text without NUL bytes is shown (the first 64 KB); anything else counts as binary. */
        fun textOf(bytes: ByteArray): String? {
            val head = bytes.copyOf(minOf(bytes.size, PREVIEW_BYTES))
            if (head.any { it == 0.toByte() }) return null
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            // A cut may split a multi-byte character; drop up to 3 trailing bytes before deciding.
            for (trim in 0..minOf(3, head.size)) {
                val text = runCatching { decoder.reset().decode(java.nio.ByteBuffer.wrap(head, 0, head.size - trim)).toString() }.getOrNull()
                if (text != null) return if (bytes.size > PREVIEW_BYTES) "$text\n…" else text
                if (bytes.size <= PREVIEW_BYTES) return null
            }
            return null
        }
    }
}
