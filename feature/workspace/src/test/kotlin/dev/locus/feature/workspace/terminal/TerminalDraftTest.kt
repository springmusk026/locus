package dev.locus.feature.workspace.terminal

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerminalDraftTest {
    @Test
    fun `nothing selected gives no draft`() {
        assertNull(TerminalDraft.of(null))
        assertNull(TerminalDraft.of(" \n  \n"))
    }

    @Test
    fun `the selection goes in a code block with trailing spaces trimmed`() {
        assertEquals("From my Locus terminal:\n```\n$ make   \nerror: x\n```\n".replace("make   ", "make"), TerminalDraft.of("\n$ make   \r\nerror: x   \n"))
    }

    @Test
    fun `backticks in the text get a longer fence`() {
        val draft = TerminalDraft.of("a ``` b")!!
        assertTrue(draft.contains("````\na ``` b\n````"))
    }

    @Test
    fun `long output keeps its end`() {
        val text = (1..5_000).joinToString("\n") { "line $it" }
        val draft = TerminalDraft.of(text)!!
        assertTrue(draft.contains("line 5000"))
        assertTrue(!draft.contains("line 1\n"))
        assertTrue(draft.length < TerminalDraft.MAX_CHARS + 100)
    }
}
