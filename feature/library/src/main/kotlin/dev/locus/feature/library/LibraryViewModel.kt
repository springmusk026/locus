package dev.locus.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.locus.core.data.repository.Library
import dev.locus.core.data.repository.LibraryRepository
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
    fun signIn(id: String) = signingIn(id) { repository.signIn(id) }

    /** Saves a local (Linux) MCP server; [env] pairs are variable name and secret value. Nothing starts yet. */
    fun addLocalMcpServer(name: String, command: String, args: List<String>, env: List<Pair<String, String>>) = launch {
        runCatching { repository.addLocalMcpServer(name, command, args, env) }
            .onFailure { _signIn.value = SignInStatus.Failed(it.message ?: "Couldn't add the server.", title = "Couldn't add the server") }
    }
    fun startLocalMcp(id: String) = launch { repository.startLocalMcp(id) }
    fun stopLocalMcp(id: String) = launch { repository.stopLocalMcp(id) }
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

    private fun launch(block: suspend () -> Unit) = viewModelScope.launch { block() }
}
