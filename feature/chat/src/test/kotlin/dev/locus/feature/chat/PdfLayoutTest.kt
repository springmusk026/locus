package dev.locus.feature.chat

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PdfLayoutTest {
    /** Every character is 5 points wide, whatever the style. */
    private val measure: (String, PdfStyle) -> Float = { text, _ -> text.length * 5f }

    @Test
    fun `blocks keep code verbatim and mark speakers and steps`() {
        val blocks = PdfLayout.blocks("# Title\n\n_31 October 2026_\n\n**You:**\n\nHi **there**\n\n**Locus:**\n\n- Ran `ls`\n\n```kotlin\n  val a = 1\n\n```\n")
        assertEquals(
            listOf(
                PdfBlock("Title", PdfStyle.Title),
                PdfBlock("31 October 2026", PdfStyle.Meta),
                PdfBlock("You:", PdfStyle.Speaker),
                PdfBlock("Hi there", PdfStyle.Body),
                PdfBlock("Locus:", PdfStyle.Speaker),
                PdfBlock("• Ran ls", PdfStyle.Bullet, indent = 10f),
                PdfBlock("  val a = 1", PdfStyle.Code),
                PdfBlock("", PdfStyle.Code),
            ),
            blocks,
        )
    }

    @Test
    fun `wrapping fits the width and breaks words that are too long`() {
        val lines = PdfLayout.wrap("aaaa bbbb cccc " + "x".repeat(25), 50f, mono = false) { it.length * 5f }
        assertEquals(listOf("aaaa bbbb", "cccc", "x".repeat(10), "x".repeat(10), "x".repeat(5)), lines)
        val code = PdfLayout.wrap("    indented code line", 50f, mono = true) { it.length * 5f }
        assertEquals(listOf("    indent", "ed code li", "ne"), code)
    }

    @Test
    fun `pages never overflow and keep every line`() {
        val blocks = (1..120).map { PdfBlock("line number $it with some words", if (it % 3 == 0) PdfStyle.Code else PdfStyle.Body) }
        val pages = PdfLayout.paginate(blocks, width = 400f, height = 300f, measure = measure)
        assertTrue(pages.size > 1)
        pages.forEach { page -> assertTrue(page.all { it.y <= 300f && it.x + it.text.length * 5f <= 400f }) }
        assertEquals(120, pages.sumOf { it.size })
    }

    @Test
    fun `a speaker never ends a page`() {
        // Fill a page almost to the bottom, then a speaker and its first line.
        val filler = List(19) { PdfBlock("x", PdfStyle.Code) }
        val blocks = filler + PdfBlock("Locus:", PdfStyle.Speaker) + PdfBlock("Reply", PdfStyle.Body)
        val pages = PdfLayout.paginate(blocks, width = 400f, height = 20 * PdfStyle.Code.lineHeight, measure = measure)
        assertEquals(2, pages.size)
        assertEquals(PdfStyle.Speaker, pages[1].first().style)
    }
}
