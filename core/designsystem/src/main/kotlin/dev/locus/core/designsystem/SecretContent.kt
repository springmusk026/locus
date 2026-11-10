package dev.locus.core.designsystem

import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView

/**
 * How many secret fields one window shows. Its owner (the activity's [ProvideSecureWindow], or a
 * [LocusSheet], which is a window of its own) makes the window secure while [secure] is true.
 * Counted, so closing one of two secret fields doesn't uncover the other.
 */
@Stable
class SecureWindow {
    var count by mutableIntStateOf(0)
        private set
    val secure: Boolean get() = count > 0
    internal fun add() { count++ }
    internal fun remove() { count = (count - 1).coerceAtLeast(0) }
}

/** The window owner [SecretContent] reports to; null outside one (previews, tests). */
val LocalSecureWindow = staticCompositionLocalOf<SecureWindow?> { null }

/**
 * Call beside a field that shows a secret: an API key, a password, a token. While it's on screen its
 * window is secure: FLAG_SECURE (no screenshots or recordings, a blank preview in Recents, nothing for
 * Content Capture), and on Android 15+ the view is marked sensitive, so screen sharing hides it.
 */
@Composable
fun SecretContent() {
    val owner = LocalSecureWindow.current ?: return
    DisposableEffect(owner) {
        owner.add()
        onDispose { owner.remove() }
    }
}

/** The activity's [SecureWindow]: FLAG_SECURE on [window] while a secret field shows outside a sheet. */
@Composable
fun ProvideSecureWindow(window: Window, content: @Composable () -> Unit) {
    val owner = remember { SecureWindow() }
    val view = LocalView.current
    val secure = owner.secure
    LaunchedEffect(secure) {
        if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        markSensitive(view, secure)
    }
    CompositionLocalProvider(LocalSecureWindow provides owner, content = content)
}

/** On Android 15+, a sensitive view is hidden from screen sharing and recording of the screen it's on. */
internal fun markSensitive(view: View, sensitive: Boolean) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        view.contentSensitivity = if (sensitive) View.CONTENT_SENSITIVITY_SENSITIVE else View.CONTENT_SENSITIVITY_AUTO
    }
}
