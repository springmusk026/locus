package dev.locus.feature.library

import dev.locus.core.agent.home.MemoryAuthor
import dev.locus.core.agent.home.MemoryProvenance
import dev.locus.core.data.repository.MemoryChat
import org.junit.Test
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Library → Memory: where each note came from. */
class MemoryOriginTest {
    /** 2 Nov 2026, 14:05 UTC. */
    private val at = 1_793_628_300_000L
    private val chat = MemoryChat("c1", "Planning the trip")

    private fun view(origin: MemoryProvenance, chat: MemoryChat? = this.chat) =
        memoryOriginView(origin, chat, ZoneOffset.UTC, Locale.UK)

    @Test
    fun `saved by the agent in a chat links to that chat`() {
        val v = view(MemoryProvenance(MemoryAuthor.AGENT, at, chatId = "c1"))
        assertEquals("Saved by Locus in a chat", v.who)
        assertEquals("2 Nov 2026, 14:05", v.date)
        assertEquals(chat, v.chat)
        assertFalse(v.chatGone)
        assertNull(v.warning)
        assertEquals("by Locus", v.tag)
    }

    @Test
    fun `saved by the user has no chat and no tag`() {
        val v = view(MemoryProvenance(MemoryAuthor.USER, at))
        assertEquals("Saved by you", v.who)
        assertNull(v.chat)
        assertFalse(v.chatGone)
        assertEquals("", v.tag)
    }

    @Test
    fun `saved by the after-task reflection`() {
        val v = view(MemoryProvenance(MemoryAuthor.REFLECTION, at, chatId = "c1"))
        assertEquals("Saved by Locus after a task", v.who)
        assertEquals(chat, v.chat)
        assertEquals("after a task", v.tag)
    }

    @Test
    fun `a deleted chat is said, not linked`() {
        val v = view(MemoryProvenance(MemoryAuthor.AGENT, at, chatId = "gone"), chat = null)
        assertNull(v.chat)
        assertTrue(v.chatGone)
    }

    @Test
    fun `a note saved while reading outside content is flagged, in words a person knows`() {
        val v = view(MemoryProvenance(MemoryAuthor.AGENT, at, chatId = "c1", taintedBy = listOf("browser_open", "email_read", "fetch_url", "mcp_call")))
        assertTrue(v.warning!!.startsWith("Saved while reading outside content (a web page, an email, a connector)."), v.warning)
        assertEquals("by Locus · check", v.tag)
    }

    @Test
    fun `unknown tools are named plainly`() {
        assertEquals("web search results, some tool", taintSources(listOf("web_search", "some_tool")))
        assertEquals("outside content", taintSources(emptyList()))
    }
}
