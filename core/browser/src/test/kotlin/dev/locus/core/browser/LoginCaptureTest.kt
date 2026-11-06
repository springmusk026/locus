package dev.locus.core.browser

import dev.locus.core.agent.ToolException
import dev.locus.core.agent.ToolResult
import dev.locus.core.model.SiteLogin
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The save prompt's capture (what the user typed, through the page helper) and the scrubbing backstop
 * on browser results. Test values only.
 */
class LoginCaptureTest {
    private val secret = "0123456789abcdef0123456789abcdef"
    private val page = "https://accounts.example.com/login"
    private val payload = """{"u":"alice","p":"test-pass-Z9q"}"""

    @Test
    fun `a report is read only with the tab's secret, from the top page's own origin, over https`() {
        val typed = LoginCapture.read(LoginCapture.MARK + secret, payload, page, page, secret)!!
        assertEquals("https://accounts.example.com", typed.origin)
        assertEquals("alice", typed.username)
        assertEquals("test-pass-Z9q", typed.password)
        assertFalse("test-pass-Z9q" in typed.toString(), "never in a log line")
        // The page forging a report without the secret, or a helper without one (old WebView).
        assertNull(LoginCapture.read(LoginCapture.MARK + "guess", payload, page, page, secret))
        assertNull(LoginCapture.read(LoginCapture.MARK, payload, page, page, ""))
        // A frame of another origin inside the page.
        assertNull(LoginCapture.read(LoginCapture.MARK + secret, payload, "https://ads.evil.example/frame", page, secret))
        // Plain http, except the phone's loopback.
        assertNull(LoginCapture.read(LoginCapture.MARK + secret, payload, "http://example.org/", "http://example.org/", secret))
        assertNotNull(LoginCapture.read(LoginCapture.MARK + secret, payload, "http://localhost:3000/", "http://localhost:3000/", secret))
        // Garbage, an empty password, a page's own prompt.
        assertNull(LoginCapture.read(LoginCapture.MARK + secret, "{", page, page, secret))
        assertNull(LoginCapture.read(LoginCapture.MARK + secret, """{"u":"alice","p":""}""", page, page, secret))
        assertNull(LoginCapture.read("Enter the promo code", "SPRING", page, page, secret))
        assertFalse(LoginCapture.isReport("Enter the promo code"))
    }

    @Test
    fun `the offer asks only when there's something to save`() {
        val typed = LoginCapture.read(LoginCapture.MARK + secret, payload, page, page, secret)!!
        val saved = SiteLogin("l1", "https://accounts.example.com", "alice", "test-pass-Z9q")
        assertNull(LoginCapture.offer(typed, null, never = true, now = 1), "Never for this site")
        assertNull(LoginCapture.offer(typed, saved, never = false, now = 1), "exactly this login is already saved")
        val fresh = LoginCapture.offer(typed, null, never = false, now = 1)!!
        assertFalse(fresh.isUpdate)
        assertFalse("test-pass-Z9q" in fresh.toString())
        val update = LoginCapture.offer(typed, saved.copy(password = "old-pass-Q1w"), never = false, now = 1)!!
        assertTrue(update.isUpdate)
        // A password page without the username on it keeps the saved username.
        val noUser = LoginCapture.Typed(typed.origin, "", "new-pass-X2c")
        assertEquals("alice", LoginCapture.offer(noUser, saved, never = false, now = 1)!!.username)
    }

    // ---- Scrubbing ---------------------------------------------------------------------------------

    private val key = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
    private val logins = object : SiteLogins by SiteLogins.None {
        override suspend fun all() = listOf(SiteLogin("l1", "https://accounts.example.com", "alice", "test-pass-Z9q", key))
    }
    private val now = 1_111_111_111L

    @Test
    fun `every kind of browser result is scrubbed of passwords, keys and current codes`() = runBlocking {
        val code = Totp.codeFor(key, now)!!
        val next = Totp.codeFor(key, now + 30)!!
        val leaks = listOf(
            "Page: Welcome test-pass-Z9q\nURL: https://accounts.example.com/?p=test-pass-Z9q", // snapshot header and URL
            "# Your password is test-pass-Z9q", // browser_read Markdown
            "[error] console: login failed for test-pass-Z9q (app.js:12)", // browser_logs
            "1. Code $code · https://accounts.example.com (active)", // browser_tabs
            "Event: The page showed an alert: “$next”", // events
            "Text matching \"key\": … $key …", // find
        )
        leaks.forEach { text ->
            val out = scrubbingLogins(logins, { now * 1000 }) { ToolResult(text) }.content
            listOf("test-pass-Z9q", key, code, next).forEach { assertFalse(it in out, "$it leaked from: $out") }
            assertTrue(LoginScrubber.HIDDEN in out)
        }
        // Errors are scrubbed too.
        val e = assertFailsWith<ToolException> { scrubbingLogins(logins, { now * 1000 }) { throw ToolException("Typed test-pass-Z9q") } }
        assertFalse("test-pass-Z9q" in e.message!!)
        // The username isn't secret: pages say "Signed in as alice".
        assertEquals("Signed in as alice", scrubbingLogins(logins, { now * 1000 }) { ToolResult("Signed in as alice") }.content)
    }

    @Test
    fun `a closed vault scrubs nothing and breaks nothing`() = runBlocking {
        val closed = object : SiteLogins by SiteLogins.None {
            override suspend fun all(): List<SiteLogin> = throw IllegalStateException("vault closed")
        }
        assertEquals("page", scrubbingLogins(closed) { ToolResult("page") }.content)
    }

    @Test
    fun `very short values aren't scrubbed, longest first`() {
        val s = LoginScrubber(listOf("abc", "pass-1234", "pass-1234-long"))
        assertEquals("abc ${LoginScrubber.HIDDEN} ${LoginScrubber.HIDDEN}", s.scrub("abc pass-1234-long pass-1234"))
    }
}
