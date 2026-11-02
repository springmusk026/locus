package dev.locus.core.terminal

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TextStyle
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What Locus's terminal relies on from the vendored emulator (Termux's own tests cover the rest):
 * the sequences full-screen programs and coding CLIs send, the replies they wait for, paste, and
 * resizing when the keyboard opens or the font size changes.
 */
class LocusTerminalBehaviourTest {
    private class Output : TerminalOutput() {
        val written = ByteArrayOutputStream()
        val titles = mutableListOf<String?>()
        override fun write(data: ByteArray, offset: Int, count: Int) = written.write(data, offset, count)
        override fun titleChanged(oldTitle: String?, newTitle: String?) { titles += newTitle }
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() = Unit
        fun take(): String = written.toString(Charsets.UTF_8.name()).also { written.reset() }
    }

    private val output = Output()
    private val emulator = TerminalEmulator(output, 20, 5, 10, 20, 100, null)

    private fun feed(s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        emulator.append(bytes, bytes.size)
    }

    private fun line(row: Int) = emulator.screen.getSelectedText(0, row, 20, row).trimEnd()

    @Test
    fun `cursor movement and erase`() {
        feed("hello world\u001b[1;7H\u001b[KWORLD")
        assertEquals("hello WORLD", line(0))
        feed("\u001b[3;2Hx")
        assertEquals(2, emulator.cursorRow)
        assertEquals(2, emulator.cursorCol)
        feed("\u001b[2J")
        assertEquals("", line(0))
    }

    @Test
    fun `utf-8 and wide characters take their cells`() {
        feed("añ漢b")
        assertEquals("añ漢b", line(0))
        assertEquals(5, emulator.cursorCol)
    }

    @Test
    fun `256-colour and truecolour SGR`() {
        feed("\u001b[38;5;196mR\u001b[38;2;10;20;30mT\u001b[0mN")
        val screen = emulator.screen
        assertEquals(196, TextStyle.decodeForeColor(screen.getStyleAt(0, 0)))
        assertEquals(0xff0a141e.toInt(), TextStyle.decodeForeColor(screen.getStyleAt(0, 1)))
        assertEquals(TextStyle.COLOR_INDEX_FOREGROUND, TextStyle.decodeForeColor(screen.getStyleAt(0, 2)))
    }

    @Test
    fun `the alternate screen keeps the shell's screen`() {
        feed("prompt$ ")
        feed("\u001b[?1049h")
        assertTrue(emulator.isAlternateBufferActive)
        feed("full screen app")
        feed("\u001b[?1049l")
        assertFalse(emulator.isAlternateBufferActive)
        assertEquals("prompt$", line(0))
    }

    @Test
    fun `cursor position and device attribute reports are answered`() {
        feed("\u001b[2;4H\u001b[6n")
        assertEquals("\u001b[2;4R", output.take())
        feed("\u001b[c")
        assertTrue(output.take().startsWith("\u001b[?"))
    }

    @Test
    fun `the OSC title reaches the session`() {
        feed("\u001b]0;vim notes.md\u0007")
        assertEquals("vim notes.md", emulator.title)
        assertEquals(listOf<String?>("vim notes.md"), output.titles)
    }

    @Test
    fun `paste is bracketed when asked and never carries escapes`() {
        emulator.paste("a\u001b[31mb\nc")
        assertEquals("a[31mb\rc", output.take())
        feed("\u001b[?2004h")
        emulator.paste("x")
        assertEquals("\u001b[200~x\u001b[201~", output.take())
    }

    @Test
    fun `lines scrolled off the top stay in the scrollback`() {
        repeat(8) { feed("line $it\r\n") }
        assertEquals(4, emulator.screen.activeTranscriptRows)
        assertEquals("line 0", emulator.screen.getSelectedText(0, -4, 20, -4).trimEnd())
    }

    @Test
    fun `resizing keeps the text and the cursor on it`() {
        feed("first\r\nsecond\r\nthird")
        emulator.resize(10, 3, 10, 20)
        assertEquals(10, emulator.mColumns)
        assertEquals(3, emulator.mRows)
        assertEquals("third", line(emulator.cursorRow))
        assertEquals(5, emulator.cursorCol)
        emulator.resize(30, 8, 10, 20)
        assertEquals("first", line(0))
        assertEquals("third", line(emulator.cursorRow))
    }

    @Test
    fun `narrowing rewraps a long line`() {
        feed("abcdefghijklmnop")
        emulator.resize(8, 5, 10, 20)
        assertEquals("abcdefgh", line(0))
        assertEquals("ijklmnop", line(1))
    }
}
