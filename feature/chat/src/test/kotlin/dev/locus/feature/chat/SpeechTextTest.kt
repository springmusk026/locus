package dev.locus.feature.chat

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpeechTextTest {

    @Test
    fun `markdown syntax is stripped and lines end as sentences`() {
        val spoken = SpeechText.prepare("## Result\n\nThis is **bold**, *italic*, ~~gone~~ and `x`.\n\n- first item\n- second item\n1. one")
        assertEquals("Result.\n\nThis is bold, italic, gone and x.\n\nfirst item.\nsecond item.\n1. one.", spoken)
    }

    @Test
    fun `code blocks are announced, not read`() {
        val spoken = SpeechText.prepare("Run this:\n\n```python\nprint('secret_code')\n```\n\nDone.")
        assertFalse("print" in spoken)
        assertEquals("Run this:\n\n${SpeechText.CODE}\n\nDone.", spoken)
    }

    @Test
    fun `an unclosed code block still counts as code`() {
        assertEquals(SpeechText.CODE, SpeechText.prepare("```\nval a = 1\nval b = 2"))
    }

    @Test
    fun `tables are announced, not read`() {
        val spoken = SpeechText.prepare("Prices:\n\n| Plan | Cost |\n|---|---|\n| Pro | 20 |\n| Max | 100 |\n\nThat's all.")
        assertFalse("Pro" in spoken)
        assertEquals("Prices:\n\n${SpeechText.TABLE}\n\nThat's all.", spoken)
    }

    @Test
    fun `links read their text and urls are not spelled out`() {
        val spoken = SpeechText.prepare("See [the docs](https://example.com/a?b=c) or https://example.org/x and <https://x.y>.")
        assertEquals("See the docs or link and link.", spoken)
        assertFalse("example" in spoken)
    }

    @Test
    fun `thinking is skipped and snake_case survives`() {
        val spoken = SpeechText.prepare("<think>internal plan</think>Use read_file here.")
        assertEquals("Use read_file here.", spoken)
    }

    @Test
    fun `quotes, rules and html go away`() {
        assertEquals("Quoted.\n\nAfter.", SpeechText.prepare("> Quoted\n\n---\n\n<b>After</b>"))
    }

    @Test
    fun `chunks stay under the limit and keep every word`() {
        val text = (1..200).joinToString(" ") { "word$it." } + "\n\n" + "x".repeat(130)
        val chunks = SpeechText.chunks(text, 100)
        assertTrue(chunks.all { it.length <= 100 })
        assertEquals(text.replace("\n", " ").split(Regex("\\s+")).joinToString(""), chunks.joinToString("").replace(" ", ""))
    }

    @Test
    fun `short text is one chunk and breaks prefer sentences`() {
        assertEquals(listOf("Hello there."), SpeechText.chunks("Hello there.", 4000))
        assertEquals(listOf("One two three.", "Four five six."), SpeechText.chunks("One two three. Four five six.", 20))
    }
}
