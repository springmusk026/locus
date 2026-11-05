package dev.locus.feature.browser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import dev.locus.core.browser.AgentBrowser
import dev.locus.core.browser.BrowserState
import dev.locus.core.browser.PendingDialog
import dev.locus.core.browser.TabInfo
import dev.locus.core.screenshot.ScreenshotTest
import dev.locus.core.testing.MainDispatcherRule
import dev.locus.core.testing.TestGraph
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Rule
import org.junit.Test

/**
 * The full-screen browser's chrome and states. The page itself is a WebView, which can't be created
 * under Robolectric, so the page shows as the screen's placeholder and these check the bar, the
 * loading line, the tab count, the hand-off banner and the page's dialogs around it.
 */
class BrowserScreenshots : ScreenshotTest() {

    @get:Rule
    val main = MainDispatcherRule()

    private val scope = CoroutineScope(SupervisorJob())

    @After
    fun tearDown() = scope.cancel()

    /**
     * A browser showing [state] for the chat on screen. Its state is only ever written by its WebViews,
     * so the test sets the focused chat's state directly.
     */
    private fun browser(state: BrowserState): BrowserViewModel {
        val g = TestGraph(scope)
        val browser = g.browser
        browser.stateOf(CHAT)
        browser.focus(CHAT)
        val field = AgentBrowser::class.java.getDeclaredField("states").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (field.get(browser) as ConcurrentHashMap<String, MutableStateFlow<BrowserState>>).getValue(CHAT).value = state
        return BrowserViewModel(browser)
    }

    private val page = BrowserState(
        url = "https://www.booking.com/hotel/pt/casa-do-alfama.en-gb.html",
        title = "Casa do Alfama, Lisbon – Updated 2026 Prices",
        // The page's WebView can't be created under Robolectric: these render in inspection mode,
        // where the screen draws a placeholder where the page goes (see PageRoute).
        hasPage = true,
        canGoBack = true,
        tabs = listOf(TabInfo("Casa do Alfama, Lisbon – Updated 2026 Prices", "https://www.booking.com/hotel/pt/casa-do-alfama.en-gb.html")),
    )

    @Test
    fun empty() = snap("browser-empty") { BrowserRoute(onBack = {}, viewModel = browser(BrowserState())) }

    @Test
    fun page() {
        val vm = browser(page)
        snap("browser-page") { PageRoute(vm) }
    }

    @Test
    fun loading() {
        val vm = browser(page.copy(loading = true, progress = 45, title = "", url = "https://www.cp.pt/passageiros/en/buy-tickets"))
        snap("browser-loading") { PageRoute(vm) }
    }

    @Test
    fun handoff() {
        val vm = browser(page.copy(handoffReason = "Booking.com wants you to sign in before it shows the price for 12–19 March. Sign in, then tap Done and Locus carries on."))
        snap("browser-handoff") { PageRoute(vm) }
    }

    @Test
    fun tabs() {
        val vm = browser(
            page.copy(
                tabs = listOf(
                    page.tabs.single(),
                    TabInfo("Sign in or create an account", "https://account.booking.com/sign-in?op_token=…"),
                    TabInfo("", "about:blank"),
                ),
                activeTab = 1,
            ),
        )
        snap("browser-tabs") { PageRoute(vm) }
    }

    @Test
    fun confirmDialog() {
        val vm = browser(page.copy(dialog = PendingDialog("confirm", "Leave this page? The room you picked isn't held until you finish booking.", "")))
        snap("browser-dialog-confirm") { PageRoute(vm) }
    }

    @Test
    fun promptDialog() {
        val vm = browser(page.copy(dialog = PendingDialog("prompt", "Enter the promo code from your email", "SPRING26")))
        snap("browser-dialog-prompt") { PageRoute(vm) }
    }

    /** The browser with a page open, in inspection mode so the screen shows its placeholder instead of a WebView. */
    @Composable
    private fun PageRoute(vm: BrowserViewModel) {
        CompositionLocalProvider(LocalInspectionMode provides true) { BrowserRoute(onBack = {}, viewModel = vm) }
    }

    private companion object {
        const val CHAT = "chat-lisbon"
    }
}
