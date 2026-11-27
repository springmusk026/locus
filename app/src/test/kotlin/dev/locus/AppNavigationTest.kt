package dev.locus

import androidx.navigation3.runtime.NavKey
import dev.locus.core.model.SettingsDestination
import org.junit.Test
import kotlin.test.assertEquals

/** The back-stack moves behind Locus's ways in: shortcuts, the tile, widgets, notifications and Settings links. */
class AppNavigationTest {
    private fun stack(vararg keys: NavKey): MutableList<NavKey> = mutableListOf(*keys)

    /** A [AppNavigation.launch] whose new chat gets [id] at once, as ShareViewModel.newChat does once the chat exists. */
    private fun launch(backStack: MutableList<NavKey>, action: String, id: String = "new"): Int {
        var focused = 0
        AppNavigation.launch(backStack, action, newChat = { open -> open(id) }, focusAsk = { focused++ })
        return focused
    }

    @Test
    fun `the tile and the assist gesture go home with the ask bar focused`() {
        val backStack = stack(ConversationsRoute, ChatRoute("a"), WorkspaceRoute(), TerminalRoute)
        assertEquals(1, launch(backStack, LaunchActions.ASK))
        assertEquals(listOf<NavKey>(ConversationsRoute), backStack)
        // The assist gesture resolves to the same action.
        assertEquals(LaunchActions.ASK, LaunchActions.resolve(android.content.Intent.ACTION_ASSIST))
    }

    @Test
    fun `home replaces a bottom entry that isn't home`() {
        // A back stack that started on another screen (setup re-run, a restored stack) still ends on home alone.
        val backStack = stack(PersonaRoute, SettingsRoute())
        AppNavigation.home(backStack)
        assertEquals(listOf<NavKey>(ConversationsRoute), backStack)
    }

    @Test
    fun `new chat and voice shortcuts open the chat they created`() {
        val backStack = stack(ConversationsRoute)
        assertEquals(0, launch(backStack, LaunchActions.NEW_CHAT, id = "c1"))
        launch(backStack, LaunchActions.VOICE, id = "c2")
        assertEquals(listOf(ConversationsRoute, ChatRoute("c1"), ChatRoute("c2", voice = true)), backStack)
    }

    @Test
    fun `screens opened from outside aren't stacked twice`() {
        val backStack = stack(ConversationsRoute)
        repeat(2) { launch(backStack, LaunchActions.WORKSPACE) }
        assertEquals(listOf(ConversationsRoute, WorkspaceRoute()), backStack)
        repeat(2) { launch(backStack, LaunchActions.INSIGHTS) }
        repeat(2) { launch(backStack, LaunchActions.UPDATES) }
        assertEquals(
            listOf(ConversationsRoute, WorkspaceRoute(), AnalyticsRoute, SettingsRoute(SettingsDestination.About)),
            backStack,
        )
        // A Workspace opened on a file is another screen: the shortcut's plain Workspace goes on top of it.
        val onFile = stack(ConversationsRoute, WorkspaceRoute(openPath = "notes.md"))
        launch(onFile, LaunchActions.WORKSPACE)
        assertEquals(listOf(ConversationsRoute, WorkspaceRoute(openPath = "notes.md"), WorkspaceRoute()), onFile)
    }

    @Test
    fun `a notification opens its chat unless that chat is on screen`() {
        val backStack = stack(ConversationsRoute, ChatRoute("a", draft = "hi"))
        AppNavigation.openChat(backStack, "a")
        assertEquals(2, backStack.size)
        AppNavigation.openChat(backStack, "b")
        assertEquals(ChatRoute("b"), backStack.last())
        // The same chat further down the stack is opened again on top.
        AppNavigation.openChat(backStack, "a")
        assertEquals(listOf(ConversationsRoute, ChatRoute("a", draft = "hi"), ChatRoute("b"), ChatRoute("a")), backStack)
    }

    @Test
    fun `settings links open their page, and on-device models as its own screen`() {
        assertEquals(SettingsRoute(SettingsDestination.Logins), AppNavigation.settingsLink(SettingsDestination.Logins))
        assertEquals(OnDeviceModelsRoute, AppNavigation.settingsLink(SettingsDestination.OnDeviceModels))
        val backStack = stack(ConversationsRoute, ChatRoute("a"))
        repeat(2) { AppNavigation.openOnce(backStack, AppNavigation.settingsLink(SettingsDestination.About)) }
        assertEquals(listOf(ConversationsRoute, ChatRoute("a"), SettingsRoute(SettingsDestination.About)), backStack)
    }

    @Test
    fun `only Locus's own launch actions are taken`() {
        LaunchActions.ALL.forEach { assertEquals(it, LaunchActions.resolve(it)) }
        assertEquals(null, LaunchActions.resolve(null))
        assertEquals(null, LaunchActions.resolve(android.content.Intent.ACTION_MAIN))
        assertEquals(null, LaunchActions.resolve("dev.locus.action.START_CHAT"))
        // An unknown action leaves the back stack alone.
        val backStack = stack(ConversationsRoute, ChatRoute("a"))
        launch(backStack, "dev.locus.action.SOMETHING_ELSE")
        assertEquals(listOf(ConversationsRoute, ChatRoute("a")), backStack)
    }
}
