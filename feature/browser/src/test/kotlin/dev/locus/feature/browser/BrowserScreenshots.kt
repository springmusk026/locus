package dev.locus.feature.browser

import dev.locus.core.browser.BrowserState
import dev.locus.core.browser.PendingDialog
import dev.locus.core.browser.TabInfo
import dev.locus.core.screenshot.ScreenshotTest
import org.junit.Test

/**
 * The full-screen browser's chrome and states. The page itself is a WebView, which can't be created
 * under Robolectric, so the page shows as the screen's placeholder and these check the bar, the
 * address row, the loading line, the tab count, who drives the page (Locus browsing, Take over, Your
 * turn, Hand back) and the page's dialogs around it.
 */
class BrowserScreenshots : ScreenshotTest() {

    private val page = BrowserState(
        url = "https://www.booking.com/hotel/pt/casa-do-alfama.en-gb.html",
        title = "Casa do Alfama, Lisbon – Updated 2026 Prices",
        hasPage = true,
        canGoBack = true,
        tabs = listOf(TabInfo("Casa do Alfama, Lisbon – Updated 2026 Prices", "https://www.booking.com/hotel/pt/casa-do-alfama.en-gb.html")),
    )

    private fun shot(name: String, ui: BrowserUi, startEditing: String? = null, startHandBack: String? = null) =
        snap(name) { BrowserScreen(ui, BrowserActions(onBack = {}), startEditing = startEditing, startHandBack = startHandBack) }

    @Test fun empty() = shot("browser-empty", BrowserUi())

    @Test fun standaloneEmpty() = shot("browser-standalone-empty", BrowserUi(standalone = true))

    @Test fun standalonePage() = shot("browser-standalone-page", BrowserUi(page.copy(canGoForward = true), standalone = true))

    @Test fun page() = shot("browser-page", BrowserUi(page))

    @Test
    fun loading() = shot("browser-loading", BrowserUi(page.copy(loading = true, progress = 45, title = "", url = "https://www.cp.pt/passageiros/en/buy-tickets")))

    @Test fun editingAddress() = shot("browser-address-editing", BrowserUi(page), startEditing = "github.com/springmusk026/locus")

    @Test
    fun loadError() = shot(
        "browser-error",
        BrowserUi(page.copy(title = "", url = "https://www.bookng.com/", error = "bookng.com couldn't be reached: the address could not be found (check the spelling).")),
    )

    /** Locus is acting on the page: watch-only, with Take over. */
    @Test fun agentBrowsing() = shot("browser-agent-browsing", BrowserUi(page, agentRunning = true))

    @Test fun userInControl() = shot("browser-takeover", BrowserUi(page.copy(userControl = true), agentRunning = true))

    /** A browser call is waiting for the user to hand back (until it times out). */
    @Test fun agentWaiting() = shot("browser-takeover-waiting", BrowserUi(page.copy(userControl = true, agentWaiting = true), agentRunning = true))

    @Test fun handBack() = shot("browser-hand-back", BrowserUi(page.copy(userControl = true, agentWaiting = true), agentRunning = true), startHandBack = "")

    @Test
    fun handBackWithNote() = shot(
        "browser-hand-back-note",
        BrowserUi(page.copy(userControl = true, agentWaiting = true), agentRunning = true),
        startHandBack = "Signed in. Pick the room with the river view.",
    )

    @Test
    fun handoff() = shot(
        "browser-handoff",
        BrowserUi(
            page.copy(handoffReason = "Booking.com wants you to sign in before it shows the price for 12–19 March. Sign in, then tap Done and Locus carries on."),
            agentRunning = true,
        ),
    )

    @Test
    fun tabs() = shot(
        "browser-tabs",
        BrowserUi(
            page.copy(
                tabs = listOf(
                    page.tabs.single(),
                    TabInfo("Sign in or create an account", "https://account.booking.com/sign-in?op_token=…"),
                    TabInfo("", "about:blank"),
                ),
                activeTab = 1,
            ),
        ),
    )

    @Test
    fun confirmDialog() = shot("browser-dialog-confirm", BrowserUi(page.copy(dialog = PendingDialog("confirm", "Leave this page? The room you picked isn't held until you finish booking.", ""))))

    @Test
    fun promptDialog() = shot("browser-dialog-prompt", BrowserUi(page.copy(dialog = PendingDialog("prompt", "Enter the promo code from your email", "SPRING26"))))
}
