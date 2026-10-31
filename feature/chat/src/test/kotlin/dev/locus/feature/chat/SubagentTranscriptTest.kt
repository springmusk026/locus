package dev.locus.feature.chat

import dev.locus.core.model.Message
import dev.locus.core.model.ModeNote
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubagentTranscriptTest {
    private fun msg(id: String, role: Role, vararg parts: Part) = Message(id, "agent-1", role, parts.toList(), 0)

    @Test
    fun `a continued helper's transcript shows a divider before each follow-up`() {
        val entries = transcriptEntries(
            listOf(
                msg("t", Role.USER, Part.Text("Find flights.")),
                msg("a1", Role.ASSISTANT, Part.Text("## Result\nThree flights.")),
                msg("f", Role.USER, Part.Text("Also BOM."), Part.Text(ModeNote.wrap("A follow-up from the main agent…"))),
                msg("a2", Role.ASSISTANT, Part.Text("## Result\nBOM too.")),
            ),
        )
        assertEquals(
            listOf(
                Entry.Task("t", "Find flights."),
                Entry.Said("a1", "## Result\nThree flights."),
                Entry.Continued("f:continued"),
                Entry.Task("f", "Also BOM.", followUp = true),
                Entry.Said("a2", "## Result\nBOM too."),
            ),
            entries,
        )
    }

    @Test
    fun `a helper that was never continued has no divider`() {
        val entries = transcriptEntries(listOf(msg("t", Role.USER, Part.Text("Find flights.")), msg("a1", Role.ASSISTANT, Part.Text("Done."))))
        assertTrue(entries.none { it is Entry.Continued })
    }
}
