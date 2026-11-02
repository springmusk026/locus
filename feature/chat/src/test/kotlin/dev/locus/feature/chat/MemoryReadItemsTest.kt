package dev.locus.feature.chat

import dev.locus.core.agent.home.MemoryHit
import dev.locus.core.agent.home.MemoryReads
import dev.locus.core.agent.home.MemoryRef
import dev.locus.core.model.Part
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The steps card's "Memory read" list, worked out from a turn's calls as the transcript holds them. */
class MemoryReadItemsTest {
    private fun call(name: String, args: String, result: String?, error: Boolean = false) =
        ChatItem.ToolCall("k-$name-$args", name, args, result?.let { Part.ToolResult("id", name, it, isError = error) })

    @Test
    fun `reads and search results become the card's topics`() {
        val search = MemoryReads.format(listOf(MemoryHit("東京の店", "Tokyo", "**東京**の店"), MemoryHit("coffee", "Coffee", null, general = true)))
        val calls = listOf(
            call("web_search", """{"query":"x"}""", "- not memory"),
            call("memory_search", """{"query":"東京"}""", search),
            call("memory_read", """{"topic":"東京の店"}""", "Ramen near Shinjuku."),
            call("memory_read", """{"topic":"missing"}""", "No memory topic “missing”.", error = true),
            call("memory_read", """{"topic":"running"}""", null),
        )
        assertEquals(listOf(MemoryRef("東京の店"), MemoryRef("coffee", general = true)), memoryReads(calls))
    }

    @Test
    fun `turns without memory show nothing`() {
        assertTrue(memoryReads(listOf(call("read_file", """{"path":"a"}""", "text"))).isEmpty())
    }

    @Test
    fun `topics are listed in words, general memory marked`() {
        assertEquals("coffee preferences", memoryReadLabel(MemoryRef("coffee-preferences")))
        assertEquals("नेपाली खाना · general memory", memoryReadLabel(MemoryRef("नेपाली-खाना", general = true)))
    }
}
