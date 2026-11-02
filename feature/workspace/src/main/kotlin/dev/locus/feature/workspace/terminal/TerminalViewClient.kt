package dev.locus.feature.workspace.terminal

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/**
 * Connects Termux's [TerminalView] to the Terminal screen: the extra-keys row's one-shot Ctrl and
 * Alt, pinch to change the font size, tap for the keyboard, and Enter to close a finished tab.
 * Nothing typed is logged.
 */
internal class LocusTerminalViewClient(
    private val onFontStep: (bigger: Boolean) -> Unit,
    private val onCloseFinished: (TerminalSession) -> Unit,
) : TerminalViewClient {
    var view: TerminalView? = null

    /** Ctrl and Alt from the extra-keys row: on for the next key only. */
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)

    /** Whether text is selected in the view (for "Ask Locus about this"). */
    var selecting by mutableStateOf(false)
        private set

    override fun onScale(scale: Float): Float {
        // Termux's approach: step the font size once the pinch passes ±10%, then start over.
        if (scale < 0.9f || scale > 1.1f) {
            onFontStep(scale > 1f)
            return 1f
        }
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent?) {
        val v = view ?: return
        v.requestFocus()
        v.context.getSystemService(InputMethodManager::class.java)?.showSoftInput(v, 0)
    }

    override fun shouldBackButtonBeMappedToEscape() = false

    // A password-style input type: no suggestions or autocorrect, and keyboards don't learn what's typed.
    override fun shouldEnforceCharBasedInput() = true
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun isTerminalViewSelected() = true

    override fun copyModeChanged(copyMode: Boolean) {
        selecting = copyMode
    }

    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean {
        if (session != null && !session.isRunning && (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
            onCloseFinished(session)
            return true
        }
        return false
    }

    override fun onKeyUp(keyCode: Int, e: KeyEvent?) = false
    override fun onLongPress(event: MotionEvent?) = false

    override fun readControlKey(): Boolean = ctrl.also { ctrl = false }
    override fun readAltKey(): Boolean = alt.also { alt = false }
    override fun readShiftKey() = false
    override fun readFnKey() = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?) = false
    override fun onEmulatorSet() = Unit

    override fun logError(tag: String?, message: String?) = Unit
    override fun logWarn(tag: String?, message: String?) = Unit
    override fun logInfo(tag: String?, message: String?) = Unit
    override fun logDebug(tag: String?, message: String?) = Unit
    override fun logVerbose(tag: String?, message: String?) = Unit
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
    override fun logStackTrace(tag: String?, e: Exception?) = Unit
}
