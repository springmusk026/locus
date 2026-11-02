package dev.locus.core.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.annotation.MainThread
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.locus.core.linux.LinuxEnvironment
import dev.locus.core.linux.LinuxStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The user's interactive terminal sessions (docs/design/terminal.md). Each tab is a real PTY
 * (Termux's libtermux) running a login shell through proot with exactly the agent's isolation
 * flags ([LinuxEnvironment.terminalLaunch]).
 *
 * This is the user typing, not the agent: nothing here is visible to the model, logged, or saved.
 * Agent tools keep their own run_command path. Sessions live as long as the app process, so they
 * survive leaving the Terminal screen; [closeAll] stops them when the app is closed, and they end
 * with the process if Android kills it.
 */
@Singleton
class TerminalSessions @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val linux: LinuxEnvironment,
) {
    private val registry = TabRegistry<TerminalSession>()
    val tabs: StateFlow<List<TerminalTab>> = registry.tabs
    val selected: StateFlow<Int?> = registry.selected
    val linuxStatus: StateFlow<LinuxStatus> = linux.status
    val canOpen: Boolean get() = registry.canOpen

    private val prefs = context.getSharedPreferences("terminal", Context.MODE_PRIVATE)
    private val _fontSize = MutableStateFlow(prefs.getInt(KEY_FONT, DEFAULT_FONT_SP).coerceIn(MIN_FONT_SP, MAX_FONT_SP))
    /** Text size in sp, shared by every tab and kept across restarts. */
    val fontSize: StateFlow<Int> = _fontSize.asStateFlow()

    /** The visible terminal view's redraw hook; set by the screen showing a session. */
    @Volatile
    var onScreenUpdated: ((TerminalSession) -> Unit)? = null

    fun setFontSize(sp: Int) {
        val clamped = sp.coerceIn(MIN_FONT_SP, MAX_FONT_SP)
        _fontSize.value = clamped
        prefs.edit().putInt(KEY_FONT, clamped).apply()
    }

    /**
     * Opens a tab running a login shell in /workspace and selects it. The process starts when the
     * view first knows its size. Throws [dev.locus.core.linux.LinuxNotInstalledException] when Linux
     * isn't installed, and [IllegalStateException] when [TabRegistry.MAX_TABS] are open.
     */
    suspend fun open(): Int {
        check(registry.canOpen) { "At most ${TabRegistry.MAX_TABS} terminals can be open. Close one first." }
        val launch = linux.terminalLaunch()
        return withContext(Dispatchers.Main) {
            registry.add { id ->
                TerminalSession(
                    launch.executable,
                    launch.hostCwd,
                    launch.argv.toTypedArray(),
                    launch.environment.map { (k, v) -> "$k=$v" }.toTypedArray(),
                    SCROLLBACK_ROWS,
                    Client(id),
                )
            } ?: error("At most ${TabRegistry.MAX_TABS} terminals can be open. Close one first.")
        }
    }

    fun session(id: Int): TerminalSession? = registry.session(id)

    @MainThread
    fun select(id: Int) = registry.select(id)

    /** Closes a tab, killing its shell and everything started from it. */
    @MainThread
    fun close(id: Int) {
        registry.remove(id)?.finishIfRunning()
    }

    /** Stops every session; called when the app is closed. */
    @MainThread
    fun closeAll() {
        registry.removeAll().forEach { it.finishIfRunning() }
    }

    /** Callbacks from one session, on the main thread. Terminal text is never logged. */
    private inner class Client(private val id: Int) : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            onScreenUpdated?.invoke(changedSession)
        }

        override fun onTitleChanged(changedSession: TerminalSession) = registry.retitle(id, changedSession.title)

        override fun onSessionFinished(finishedSession: TerminalSession) {
            registry.finished(id, finishedSession.exitStatus)
            onScreenUpdated?.invoke(finishedSession)
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
            if (text.isNullOrEmpty()) return
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Terminal", text))
        }

        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return
            val text = (0 until clip.itemCount).joinToString("") { clip.getItemAt(it).coerceToText(context) }
            if (text.isNotEmpty()) session?.emulator?.paste(text)
        }

        override fun onBell(session: TerminalSession) = Unit
        override fun onColorsChanged(session: TerminalSession) {
            onScreenUpdated?.invoke(session)
        }
        override fun onTerminalCursorStateChange(state: Boolean) = Unit
        override fun getTerminalCursorStyle(): Int? = null

        override fun logError(tag: String?, message: String?) { Log.e(LOG_TAG, "$tag: $message") }
        override fun logWarn(tag: String?, message: String?) = Unit
        override fun logInfo(tag: String?, message: String?) = Unit
        override fun logDebug(tag: String?, message: String?) = Unit
        override fun logVerbose(tag: String?, message: String?) = Unit
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.e(LOG_TAG, "$tag: $message", e) }
        override fun logStackTrace(tag: String?, e: Exception?) { Log.e(LOG_TAG, tag, e) }
    }

    companion object {
        /** Lines kept above the screen per tab (Termux's default is 2,000; its maximum 50,000). */
        const val SCROLLBACK_ROWS = 5_000
        const val DEFAULT_FONT_SP = 13
        const val MIN_FONT_SP = 8
        const val MAX_FONT_SP = 28
        private const val KEY_FONT = "font_sp"
        private const val LOG_TAG = "LocusTerminal"
    }
}
