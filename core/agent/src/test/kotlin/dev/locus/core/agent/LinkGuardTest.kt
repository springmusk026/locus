package dev.locus.core.agent

import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Which link taps in a reply open at once and which ask first (docs/design/taint.md, "Links in replies"). */
class LinkGuardTest {
    private val named = setOf("bbc.co.uk", "github.com")

    @Test
    fun `a plain link in a clean reply opens in one tap`() {
        assertNull(LinkGuard.check("https://example.org/docs/intro", outsideContent = false, namedHosts = emptySet()))
        assertNull(LinkGuard.check("https://example.org/search?q=kotlin+flows", outsideContent = false, namedHosts = emptySet()))
        assertNull(LinkGuard.check("mailto:me@example.org", outsideContent = false, namedHosts = emptySet()))
    }

    @Test
    fun `after outside content an unnamed host asks, a named one doesn't`() {
        val unnamed = LinkGuard.check("https://evil.example/collect/4821", outsideContent = true, namedHosts = named)!!
        assertEquals(setOf(LinkGuard.Reason.OUTSIDE_CONTENT), unnamed.reasons)
        assertEquals("evil.example", unnamed.host)
        // Hosts the user named, their subdomains and www. stay one tap.
        assertNull(LinkGuard.check("https://www.bbc.co.uk/news/world", outsideContent = true, namedHosts = named))
        assertNull(LinkGuard.check("https://gist.github.com/someone/abc", outsideContent = true, namedHosts = named))
        // A look-alike isn't the named host.
        assertEquals(setOf(LinkGuard.Reason.OUTSIDE_CONTENT), LinkGuard.check("https://bbc.co.uk.evil.example/", true, named)!!.reasons)
        // The phone's own servers are always fine.
        assertNull(LinkGuard.check("http://localhost:3000/", outsideContent = true, namedHosts = emptySet()))
    }

    @Test
    fun `a long query or fragment asks even in a clean reply and on a named host`() {
        val data = "a".repeat(LinkGuard.DATA_CHARS + 1)
        val query = LinkGuard.check("https://github.com/x?d=$data", outsideContent = false, namedHosts = named)!!
        assertEquals(setOf(LinkGuard.Reason.LONG_DATA), query.reasons)
        assertEquals(data.length + 2, query.dataChars)
        assertEquals(setOf(LinkGuard.Reason.LONG_DATA), LinkGuard.check("https://github.com/x#$data", false, named)!!.reasons)
        // Exactly at the limit is fine.
        assertNull(LinkGuard.check("https://github.com/x?" + "b".repeat(LinkGuard.DATA_CHARS), false, named))
        // Both reasons at once.
        assertEquals(
            setOf(LinkGuard.Reason.LONG_DATA, LinkGuard.Reason.OUTSIDE_CONTENT),
            LinkGuard.check("https://evil.example/?d=$data", true, named)!!.reasons,
        )
    }

    @Test
    fun `links that aren't web links always ask`() {
        val intent = LinkGuard.check("intent://scan/#Intent;scheme=zxing;end", false, named)!!
        assertEquals(true, LinkGuard.Reason.NOT_WEB in intent.reasons)
        assertEquals(null, intent.host)
        assertEquals(setOf(LinkGuard.Reason.NOT_WEB), LinkGuard.check("file:///sdcard/x", false, named)!!.reasons)
        // A mail address on a domain the user named, from a tainted reply: fine; elsewhere it asks.
        assertNull(LinkGuard.check("mailto:news@bbc.co.uk", true, named))
        assertEquals(setOf(LinkGuard.Reason.OUTSIDE_CONTENT), LinkGuard.check("mailto:x@evil.example", true, named)!!.reasons)
    }

    private fun msg(id: String, role: Role, vararg parts: Part) = Message(id, "c", role, parts.toList(), createdAt = 0)

    @Test
    fun `replies after the first outside read count, earlier ones don't`() {
        val messages = listOf(
            msg("u1", Role.USER, Part.Text("hi")),
            msg("a1", Role.ASSISTANT, Part.Text("Hello! See https://example.org")),
            msg("u2", Role.USER, Part.Text("read bbc.co.uk")),
            msg("a2", Role.ASSISTANT, Part.ToolCall("t1", "web_read", "{}")),
            msg("r1", Role.TOOL, Part.ToolResult("t1", "web_read", UntrustedContent.wrap("page", "(note)"))),
            msg("a3", Role.ASSISTANT, Part.Text("The page says …")),
            msg("u3", Role.USER, Part.Text("thanks")),
            msg("a4", Role.ASSISTANT, Part.Text("You're welcome")),
        )
        assertEquals(setOf("a3", "a4"), LinkGuard.repliesAfterOutsideContent(messages))
        assertEquals(setOf("bbc.co.uk"), LinkGuard.namedHosts(messages, emptyList()))
        assertEquals(setOf("bbc.co.uk", "api.anthropic.com", "mcp.linear.app"), LinkGuard.namedHosts(messages, listOf("https://api.anthropic.com/v1", "https://mcp.linear.app/mcp")))
    }
}
