package dev.locus

import androidx.navigation3.runtime.NavKey
import dev.locus.core.model.SettingsDestination

/**
 * The back-stack moves MainActivity makes for Locus's ways in (docs/design/android-bridge.md): a
 * launcher shortcut, the tile, the assist gesture, a widget, a notification and a "Settings → …" link.
 * Kept out of the composable so app/src/test can check them on a plain list.
 */
internal object AppNavigation {
    /** Home alone: everything above it goes, and home replaces the bottom entry when that isn't home (setup). */
    fun home(backStack: MutableList<NavKey>) {
        while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
        if (backStack.firstOrNull() != ConversationsRoute) {
            backStack.add(0, ConversationsRoute)
            while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
        }
    }

    /** Opens [route] unless it is already on top, so a second tap doesn't stack the same screen twice. */
    fun openOnce(backStack: MutableList<NavKey>, route: NavKey) {
        if (backStack.lastOrNull() != route) backStack.add(route)
    }

    /** A notification's chat, unless that chat is already on screen. */
    fun openChat(backStack: MutableList<NavKey>, conversationId: String) {
        if ((backStack.lastOrNull() as? ChatRoute)?.conversationId != conversationId) backStack.add(ChatRoute(conversationId))
    }

    /**
     * What a [LaunchActions] action opens. [newChat] creates a chat and calls back with its id (it
     * needs the data layer); [focusAsk] asks home's ask bar to take focus. They only open screens.
     */
    fun launch(
        backStack: MutableList<NavKey>,
        action: String,
        newChat: (open: (conversationId: String) -> Unit) -> Unit,
        focusAsk: () -> Unit,
    ) {
        when (action) {
            LaunchActions.NEW_CHAT -> newChat { id -> backStack.add(ChatRoute(id)) }
            // Voice mode in a new chat (docs/design/voice-mode.md).
            LaunchActions.VOICE -> newChat { id -> backStack.add(ChatRoute(id, voice = true)) }
            LaunchActions.ASK -> {
                home(backStack)
                focusAsk()
            }
            LaunchActions.WORKSPACE -> openOnce(backStack, WorkspaceRoute())
            LaunchActions.INSIGHTS -> openOnce(backStack, AnalyticsRoute)
            LaunchActions.UPDATES -> openOnce(backStack, SettingsRoute(SettingsDestination.About))
        }
    }

    /** Where a "Settings → …" link goes: On-device models is a screen of its own, not a page inside Settings. */
    fun settingsLink(page: SettingsDestination): NavKey =
        if (page == SettingsDestination.OnDeviceModels) OnDeviceModelsRoute else SettingsRoute(page)
}
