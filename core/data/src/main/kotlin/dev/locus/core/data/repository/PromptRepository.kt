package dev.locus.core.data.repository

import dev.locus.core.agent.home.AgentHome
import dev.locus.core.agent.prompt.PromptStore
import dev.locus.core.data.di.IoDispatcher
import dev.locus.core.model.SavedPrompt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Saved prompts (docs/design/saved-prompts.md): kept in agent-home's `prompts.json`, listed in
 * Library → Skills and picked from the composer. Picking one only fills the composer; nothing here sends.
 */
@Singleton
class PromptRepository @Inject constructor(
    home: AgentHome,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    private val store = PromptStore(home)
    private val _all = MutableStateFlow<List<SavedPrompt>>(emptyList())

    /** Every saved prompt, in the user's order. */
    val all: StateFlow<List<SavedPrompt>> = _all.asStateFlow()

    suspend fun refresh() {
        _all.value = withContext(io) { runCatching { store.all() }.getOrDefault(emptyList()) }
    }

    /** Saves [prompt] (a blank id adds a new one at the end) and returns what was written. */
    suspend fun save(prompt: SavedPrompt): SavedPrompt = withContext(io) { store.save(prompt) }.also { refresh() }

    suspend fun delete(id: String) {
        withContext(io) { store.delete(id) }
        refresh()
    }

    /** Moves a prompt [by] places in the list (negative: up). */
    suspend fun move(id: String, by: Int) {
        _all.value = withContext(io) { store.move(id, by) }
    }
}
