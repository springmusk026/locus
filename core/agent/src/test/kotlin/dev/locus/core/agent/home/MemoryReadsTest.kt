package dev.locus.core.agent.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Which memory topics a turn read, from the transcript as it is (the steps card's "Memory read"). */
class MemoryReadsTest {
    @Test
    fun `a successful memory_read reads its topic, by file name`() {
        assertEquals(listOf(MemoryRef("coffee-preferences")), MemoryReads.of("memory_read", """{"topic":"Coffee preferences"}""", "Flat white.", isError = false))
        assertEquals(listOf(MemoryRef("नेपाली-खाना")), MemoryReads.of("memory_read", """{"topic":"नेपाली खाना"}""", "दाल भात", isError = false))
    }

    @Test
    fun `failed, unfinished and other calls read nothing`() {
        assertTrue(MemoryReads.of("memory_read", """{"topic":"x"}""", "No memory topic “x”.", isError = true).isEmpty())
        assertTrue(MemoryReads.of("memory_read", """{"topic":"x"}""", null, isError = false).isEmpty())
        assertTrue(MemoryReads.of("memory_read", "not json", "text", isError = false).isEmpty())
        assertTrue(MemoryReads.of("read_file", """{"topic":"x"}""", "text", isError = false).isEmpty())
        assertTrue(MemoryReads.of("memory_search", """{"query":"x"}""", "No matches.", isError = false).isEmpty())
    }

    @Test
    fun `search results round-trip through the format the model sees`() {
        val hits = listOf(
            MemoryHit("coffee", "How the user takes coffee", "Flat white with **oat** milk"),
            MemoryHit("東京の店", "", null),
            MemoryHit("home-server", "Pi 5 — on the LAN", "- not a topic line", general = true),
        )
        val text = MemoryReads.format(hits)
        assertEquals(
            """
            - coffee — How the user takes coffee
              Flat white with **oat** milk
            - 東京の店
            - home-server (general memory) — Pi 5 — on the LAN
              - not a topic line
            """.trimIndent(),
            text,
        )
        assertEquals(
            listOf(MemoryRef("coffee"), MemoryRef("東京の店"), MemoryRef("home-server", general = true)),
            MemoryReads.of("memory_search", """{"query":"coffee"}""", text, isError = false),
        )
        assertEquals("No matches.", MemoryReads.format(emptyList()))
    }

    @Test
    fun `results in the older format are read too`() {
        val old = "- coffee: Flat white, no sugar.\n- garden: (general memory) Raised beds"
        assertEquals(
            listOf(MemoryRef("coffee"), MemoryRef("garden", general = true)),
            MemoryReads.of("memory_search", "{}", old, isError = false),
        )
    }

    @Test
    fun `a turn's reads are listed once each, in order`() {
        val calls = listOf(
            MemoryReads.Call("memory_search", """{"query":"coffee"}""", "- coffee — Coffee\n- tea — Tea", false),
            MemoryReads.Call("memory_read", """{"topic":"coffee"}""", "Flat white.", false),
            MemoryReads.Call("web_search", """{"query":"coffee"}""", "- results", false),
        )
        assertEquals(listOf(MemoryRef("coffee"), MemoryRef("tea")), MemoryReads.inCalls(calls))
    }
}
