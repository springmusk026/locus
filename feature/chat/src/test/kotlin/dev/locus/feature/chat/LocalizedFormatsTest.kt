package dev.locus.feature.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.locus.core.designsystem.localizedToolLabel
import dev.locus.core.model.Conversation
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #320: Share chat was English whatever the language, and the chat list's months, ChatGPT's reset time
 * and Compare's prices used fixed formats. Each in Spanish, Portuguese and Chinese.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalizedFormatsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val utc = ZoneOffset.UTC
    private val started = 1_793_448_000_000 // 31 October 2026, 12:00 UTC
    private val tuesday = 1_794_301_500_000 // 10 November 2026, 09:05 UTC

    private fun plain(s: String) = s.replace(' ', ' ').replace(' ', ' ')
    private fun msg(id: String, role: Role, vararg parts: Part) = Message(id, "c", role, parts.toList(), 0)

    private val chat = listOf(
        msg("u1", Role.USER, Part.Text("Write fib"), Part.Attachment("notes.txt", "text/plain", "/x/notes.txt", "uploads/notes.txt", 3)),
        msg(
            "a1", Role.ASSISTANT,
            Part.ToolCall("c1", "run_command", """{"command":"python3 fib.py"}"""),
            Part.ToolCall("c2", "browser_type", """{"ref":"e3","text":"hunter2"}"""),
        ),
        msg("t1", Role.TOOL, Part.ToolResult("c1", "run_command", "0 1 1 2 3")),
        msg("a2", Role.ASSISTANT, Part.Text("Here it is.")),
    )

    private fun export(title: String = "Fibonacci") = ChatExport.markdown(title, started, transcriptItems(chat), utc, exportWords(context))
    private fun ran() = localizedToolLabel(context.resources, "run_command", """{"command":"python3 fib.py"}""", done = true)

    private fun section(millis: Long, now: Long = tuesday): String {
        val row = ConversationRow(Conversation("c", "Old chat", "p", "", millis, millis), running = false, needsApproval = false)
        return chatSections(listOf(row), includePinned = false, now = now, locale = context.resources.configuration.locales[0], zone = utc)
            .single().first.asString(context.resources)
    }

    @Test
    fun `in English the export reads as it always did`() {
        assertEquals(ChatExport.markdown("Fibonacci", started, transcriptItems(chat), utc).substringAfter("_\n"), export().substringAfter("_\n"))
        assertTrue(export().startsWith("# Fibonacci\n\n_October 31, 2026"), export())
        assertEquals("$0.012", estimateLabel(context.resources, 0.0123))
        assertEquals("< $0.001", estimateLabel(context.resources, 0.0004))
    }

    @Test
    @Config(qualifiers = "es")
    fun `in Spanish`() {
        val md = plain(export())
        assertTrue(md.startsWith("# Fibonacci\n\n_31 de octubre de 2026, 12:00 · exportado desde Locus_\n"), md)
        assertTrue("**Tú:**\n\nWrite fib\n\nAdjuntos: `notes.txt`" in md, md)
        assertTrue("**Locus:**\n\n- ${ran()}\n- Escribir en la página (no se ejecutó)\n\nHere it is." in md, md)
        assertFalse("Ran " in md || "You:" in md || "Attached" in md, md)
        assertFalse("hunter2" in md, "what was typed into a page stays out in every language")
        assertEquals("Chat", export(title = " ").lineSequence().first().removePrefix("# "))
        assertEquals("0,012 US$", plain(estimateLabel(context.resources, 0.0123)))
        assertEquals("< 0,001 US$", plain(estimateLabel(context.resources, 0.0004)))
        assertEquals("noviembre de 2025", section(tuesday - 365L * 24 * 3_600_000))
    }

    @Test
    @Config(qualifiers = "pt")
    fun `in Portuguese`() {
        val md = plain(export())
        assertTrue(md.startsWith("# Fibonacci\n\n_31 de outubro de 2026 às 12:00 · exportado do Locus_\n"), md)
        assertTrue("**Você:**\n\nWrite fib\n\nAnexos: `notes.txt`" in md, md)
        assertTrue("- Digitar na página (não executado)" in md, md)
        assertEquals("US$ 2,06", plain(estimateLabel(context.resources, 2.06)))
        assertEquals("setembro", section(tuesday - 60L * 24 * 3_600_000))
    }

    @Test
    @Config(qualifiers = "b+zh+Hans")
    fun `in Chinese`() {
        val md = plain(export())
        assertTrue(md.startsWith("# Fibonacci\n\n_2026年10月31日 12:00 · 从 Locus 导出_\n"), md)
        assertTrue("**你：**\n\nWrite fib\n\n附件：`notes.txt`" in md, md)
        assertTrue("- 在页面中输入（未运行）" in md, md)
        assertEquals("对话", export(title = "").lineSequence().first().removePrefix("# "))
        // The issue's case: month then day with 日, not "11月 10".
        assertEquals("2025年11月", section(tuesday - 365L * 24 * 3_600_000))
        assertEquals("US$0.012", plain(estimateLabel(context.resources, 0.0123)))
    }

    @Test
    @Config(qualifiers = "es")
    fun `ChatGPT's reset time is in the language's order`() {
        val later = (tuesday + 2 * 3_600_000) / 1_000
        val tomorrow = (tuesday + 26 * 3_600_000) / 1_000
        assertEquals("11:05", plain(resetTime(context.resources, later, utc, tuesday)!!))
        assertEquals("11 nov, 11:05", plain(resetTime(context.resources, tomorrow, utc, tuesday)!!))
    }

    @Test
    @Config(qualifiers = "b+zh+Hans")
    fun `ChatGPT's reset time in Chinese`() {
        val tomorrow = (tuesday + 26 * 3_600_000) / 1_000
        assertEquals("11月11日 11:05", plain(resetTime(context.resources, tomorrow, utc, tuesday)!!))
    }

    @Test
    fun `the English chat list keeps its month names`() {
        assertEquals("November 2025", section(tuesday - 365L * 24 * 3_600_000))
        assertEquals(Locale.US, context.resources.configuration.locales[0])
    }
}
