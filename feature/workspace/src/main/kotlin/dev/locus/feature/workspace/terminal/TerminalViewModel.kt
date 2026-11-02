package dev.locus.feature.workspace.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.locus.core.linux.LinuxNotInstalledException
import dev.locus.core.linux.LinuxStatus
import dev.locus.core.terminal.TerminalSessions
import dev.locus.core.terminal.TerminalTab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The Terminal screen. The sessions live in [TerminalSessions] (app-wide), not here, so they keep
 * running when this screen closes; this ViewModel only drives the tabs.
 */
@HiltViewModel
class TerminalViewModel @Inject constructor(val sessions: TerminalSessions) : ViewModel() {
    val tabs: StateFlow<List<TerminalTab>> = sessions.tabs
    val selected: StateFlow<Int?> = sessions.selected
    val fontSize: StateFlow<Int> = sessions.fontSize
    val linuxStatus: StateFlow<LinuxStatus> = sessions.linuxStatus

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var opening = false

    /** Opens the first tab when the screen opens with none (and Linux is installed). */
    fun openIfNone() {
        if (tabs.value.isEmpty() && linuxStatus.value == LinuxStatus.Ready) open()
    }

    fun open() {
        if (opening) return
        opening = true
        viewModelScope.launch {
            try {
                sessions.open()
            } catch (e: LinuxNotInstalledException) {
                _message.value = e.unavailableReason ?: "Install Linux first, in setup."
            } catch (e: IllegalStateException) {
                _message.value = e.message
            } finally {
                opening = false
            }
        }
    }

    fun select(id: Int) = sessions.select(id)
    fun close(id: Int) = sessions.close(id)
    fun setFontSize(sp: Int) = sessions.setFontSize(sp)
    fun messageShown() { _message.value = null }
}
