package dev.locus.core.terminal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One terminal tab. [number] is its label ("Shell 2"): the lowest number no other open tab has.
 * [title] is what the shell set with an OSC 0/2 escape, if anything. [exitStatus] is set once the
 * shell has ended; the tab stays open, showing its last screen, until it's closed.
 */
data class TerminalTab(val id: Int, val number: Int, val title: String? = null, val exitStatus: Int? = null) {
    val running: Boolean get() = exitStatus == null
    val label: String get() = title?.takeIf { it.isNotBlank() } ?: "Shell $number"
}

/**
 * The open tabs and which one is selected, with the session object each one owns. Free of Android
 * and the PTY, so the lifecycle is unit tested; [TerminalSessions] puts real sessions in it. Call it
 * from the main thread only (Termux's sessions report on the main thread too).
 */
class TabRegistry<S>(private val maxTabs: Int = MAX_TABS) {
    private val sessions = LinkedHashMap<Int, S>()
    private var nextId = 1

    private val _tabs = MutableStateFlow<List<TerminalTab>>(emptyList())
    val tabs: StateFlow<List<TerminalTab>> = _tabs.asStateFlow()

    private val _selected = MutableStateFlow<Int?>(null)
    val selected: StateFlow<Int?> = _selected.asStateFlow()

    val canOpen: Boolean get() = sessions.size < maxTabs

    /** Adds a tab whose session [create] makes, and selects it. Null, and nothing created, when [maxTabs] are open. */
    fun add(create: (id: Int) -> S): Int? {
        if (!canOpen) return null
        val id = nextId++
        sessions[id] = create(id)
        val used = _tabs.value.map { it.number }.toSet()
        val number = generateSequence(1) { it + 1 }.first { it !in used }
        _tabs.value = _tabs.value + TerminalTab(id, number)
        _selected.value = id
        return id
    }

    fun session(id: Int): S? = sessions[id]

    fun select(id: Int) {
        if (id in sessions) _selected.value = id
    }

    /** The shell set a title; null or blank goes back to "Shell n". */
    fun retitle(id: Int, title: String?) = update(id) { it.copy(title = title?.trim()?.take(MAX_TITLE)?.ifBlank { null }) }

    /** The shell ended with [exitStatus]. */
    fun finished(id: Int, exitStatus: Int) = update(id) { it.copy(exitStatus = exitStatus) }

    /**
     * Removes the tab and returns its session for the caller to stop. When it was selected, the tab
     * to its left is selected, or the one now in its place, or none.
     */
    fun remove(id: Int): S? {
        val session = sessions.remove(id) ?: return null
        val index = _tabs.value.indexOfFirst { it.id == id }
        val remaining = _tabs.value.filterNot { it.id == id }
        _tabs.value = remaining
        if (_selected.value == id) _selected.value = remaining.getOrNull((index - 1).coerceAtLeast(0))?.id
        return session
    }

    /** Removes every tab and returns their sessions. */
    fun removeAll(): List<S> {
        val all = sessions.values.toList()
        sessions.clear()
        _tabs.value = emptyList()
        _selected.value = null
        return all
    }

    private fun update(id: Int, change: (TerminalTab) -> TerminalTab) {
        _tabs.value = _tabs.value.map { if (it.id == id) change(it) else it }
    }

    companion object {
        /** Each tab is a proot process plus its shell; Android 12+ limits an app's child processes. */
        const val MAX_TABS = 6
        private const val MAX_TITLE = 60
    }
}
