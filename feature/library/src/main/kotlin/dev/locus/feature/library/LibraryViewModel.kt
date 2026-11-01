package dev.locus.feature.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.locus.core.data.mcp.McpTemplate
import dev.locus.core.data.repository.Library
import dev.locus.core.agent.home.AgentHome
import dev.locus.core.agent.home.SkillBundle
import dev.locus.core.data.repository.LibraryRepository
import dev.locus.core.data.repository.SkillClash
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface SignInStatus {
    data class Working(val label: String) : SignInStatus
    data class Failed(val message: String, val title: String = "Couldn't sign in") : SignInStatus
}

/** Where a skill import is: fetching, failed, waiting on a name clash, or saved as a draft. */
sealed interface SkillImportStatus {
    data class Working(val label: String) : SkillImportStatus
    data class Failed(val message: String) : SkillImportStatus
    /** The name is taken by an active skill or a waiting draft; the user renames or replaces. */
    data class Clash(val bundle: SkillBundle, val with: SkillClash) : SkillImportStatus
    data class Imported(val name: String) : SkillImportStatus
}

/** A file from a skill, opened for review. [text] is null for binary files. */
data class SkillFileView(val skill: String, val path: String, val size: Int, val text: String?)

sealed interface LibraryUiState {
    data object Loading : LibraryUiState
    data class Ready(val library: Library) : LibraryUiState
}

@HiltViewModel
class LibraryViewModel @Inject constructor(private val repository: LibraryRepository) : ViewModel() {

    val uiState: StateFlow<LibraryUiState> = repository.library
        .map { LibraryUiState.Ready(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState.Loading)

    init {
        repository.refresh()
    }

    fun saveMemory(topic: String, summary: String, content: String) = launch { repository.saveMemory(topic, summary, content) }
    fun forgetMemory(topic: String) = launch { repository.forgetMemory(topic) }
    fun approveSkill(name: String) = launch { repository.approveSkill(name) }
    fun rejectSkill(name: String) = launch { repository.rejectSkill(name) }
    fun removeSkill(name: String) = launch { repository.removeSkill(name) }
    fun approveConnector(id: String) = launch { repository.approveConnector(id) }
    fun approveAgent(name: String) = launch { repository.approveAgent(name) }
    fun rejectAgent(name: String) = launch { repository.rejectAgent(name) }
    fun removeAgent(name: String) = launch { repository.removeAgent(name) }
    fun rejectConnector(id: String) = launch { repository.rejectConnector(id) }
    fun removeConnector(id: String) = launch { repository.removeConnector(id) }
    fun setSecret(name: String, value: String) = launch { repository.setConnectorSecret(name, value) }
    fun addMcpServer(name: String, url: String, token: String?) = signingIn(name) { repository.addMcpServer(name, url, token) }
    /** Adds a server from the catalogue; signing in, when it needs to, shows in the same banner. */
    fun addMcpTemplate(template: McpTemplate, url: String, key: String?, env: List<Pair<String, String>>) {
        if (template.local == null && template.signIn && key.isNullOrBlank()) {
            signingIn(template.name) { repository.addMcpTemplate(template, url, key, env) }
            return
        }
        launch {
            runCatching { repository.addMcpTemplate(template, url, key, env) }
                .onFailure { _signIn.value = SignInStatus.Failed(it.message ?: "Couldn't add the server.", title = "Couldn't add ${template.name}") }
        }
    }
    /** Signs in to connector [id]; [name] is what the banner calls it. */
    fun signIn(id: String, name: String) = signingIn(name) { repository.signIn(id) }

    /** Saves a local (Linux) MCP server; [env] pairs are variable name and secret value. Nothing starts yet. */
    fun addLocalMcpServer(name: String, command: String, args: List<String>, env: List<Pair<String, String>>) = launch {
        runCatching { repository.addLocalMcpServer(name, command, args, env) }
            .onFailure { _signIn.value = SignInStatus.Failed(it.message ?: "Couldn't add the server.", title = "Couldn't add the server") }
    }
    /** Starts begun here, so Stop can cancel one that is still starting (the process is stopped too). */
    private val starts = mutableMapOf<String, kotlinx.coroutines.Job>()

    fun startLocalMcp(id: String) {
        starts[id]?.cancel()
        val job = launch { repository.startLocalMcp(id) }
        starts[id] = job
        job.invokeOnCompletion { if (starts[id] === job) starts.remove(id) }
    }

    /** Cancels a start begun here; one a chat began finishes starting first, then stops. */
    fun stopLocalMcp(id: String) {
        starts.remove(id)?.cancel()
        launch { repository.stopLocalMcp(id) }
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
            if (result.exceptionOrNull() is kotlinx.coroutines.CancellationException) return@launch
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

    /** Saves the clashing import under its own name: a new version of the skill once approved. */
    fun replaceImport() {
        val clash = _import.value as? SkillImportStatus.Clash ?: return
        save(clash.bundle, clash.bundle.name)
    }

    /** Saves the clashing import under [name]; check it with [renameProblem] first. */
    fun renameImport(name: String) {
        val clash = _import.value as? SkillImportStatus.Clash ?: return
        if (renameProblem(name.trim()) == null) save(clash.bundle, name.trim())
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
            val result = runCatching { fetch() }
            if (result.exceptionOrNull() is kotlinx.coroutines.CancellationException) return@launch
            val bundle = result.getOrElse {
                _import.value = SkillImportStatus.Failed(it.message ?: "The import failed.")
                return@launch
            }
            when (val clash = repository.skillClash(bundle.name)) {
                SkillClash.None -> saveNow(bundle, bundle.name)
                else -> _import.value = SkillImportStatus.Clash(bundle, clash)
            }
        }
    }

    private fun save(bundle: SkillBundle, name: String) {
        importJob = viewModelScope.launch { saveNow(bundle, name) }
    }

    private suspend fun saveNow(bundle: SkillBundle, name: String) {
        _import.value = runCatching { repository.saveImportedSkill(bundle, name) }.fold(
            onSuccess = { SkillImportStatus.Imported(name) },
            onFailure = { SkillImportStatus.Failed(it.message ?: "Couldn't save the skill.") },
        )
    }

    private val _file = MutableStateFlow<SkillFileView?>(null)

    /** A skill file opened for review. */
    val openFile: StateFlow<SkillFileView?> = _file

    fun openSkillFile(skill: AgentHome.Skill, path: String) = launch {
        val bytes = repository.skillFile(skill.name, path, skill.draft) ?: return@launch
        _file.value = SkillFileView(skill.name, path, bytes.size, textOf(bytes))
    }

    fun closeSkillFile() { _file.value = null }

    private fun launch(block: suspend () -> Unit) = viewModelScope.launch { block() }

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
