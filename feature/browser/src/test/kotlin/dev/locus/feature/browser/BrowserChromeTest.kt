package dev.locus.feature.browser

import dev.locus.core.browser.BrowserState
import dev.locus.core.designsystem.SiteSecurity
import org.junit.Test
import kotlin.test.assertEquals

/** The browser's one-row chrome: what system back does, and what the address pill shows. */
class BrowserChromeTest {

    private val page = BrowserState(url = "https://www.booking.com/hotel/pt/x.html", hasPage = true, canGoBack = true)

    @Test
    fun `back ends editing first`() {
        assertEquals(BrowserBack.EndEditing, browserBack(BrowserUi(page), editing = true))
        assertEquals(BrowserBack.EndEditing, browserBack(BrowserUi(page, agentRunning = true), editing = true))
    }

    @Test
    fun `back goes back through the page's history while the user drives`() {
        assertEquals(BrowserBack.PageBack, browserBack(BrowserUi(page), editing = false))
        assertEquals(BrowserBack.PageBack, browserBack(BrowserUi(page, standalone = true), editing = false))
        // Taken over: the user drives though the agent runs.
        assertEquals(BrowserBack.PageBack, browserBack(BrowserUi(page.copy(userControl = true), agentRunning = true), editing = false))
    }

    @Test
    fun `back leaves at the first page, with nothing open, or while Locus drives`() {
        assertEquals(BrowserBack.Leave, browserBack(BrowserUi(page.copy(canGoBack = false)), editing = false))
        assertEquals(BrowserBack.Leave, browserBack(BrowserUi(BrowserState()), editing = false))
        assertEquals(BrowserBack.Leave, browserBack(BrowserUi(page, agentRunning = true), editing = false))
    }

    @Test
    fun `the pill shows the host without www`() {
        assertEquals("booking.com", displayHost("https://www.booking.com/hotel/pt/x.html?a=1"))
        assertEquals("account.booking.com", displayHost("https://account.booking.com/sign-in"))
        assertEquals("localhost", displayHost("http://localhost:8080/"))
        assertEquals("about:blank", displayHost("about:blank"))
        assertEquals("", displayHost(""))
    }

    @Test
    fun `security follows the scheme`() {
        assertEquals(SiteSecurity.Secure, siteSecurityOf("https://booking.com"))
        assertEquals(SiteSecurity.NotSecure, siteSecurityOf("http://neverssl.com"))
        assertEquals(SiteSecurity.Unknown, siteSecurityOf("about:blank"))
        assertEquals(SiteSecurity.Unknown, siteSecurityOf(""))
    }
}
