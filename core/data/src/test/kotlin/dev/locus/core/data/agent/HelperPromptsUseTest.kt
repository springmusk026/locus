package dev.locus.core.data.agent

import dev.locus.core.model.AppSettings
import dev.locus.core.model.Conversation
import dev.locus.core.model.HelperPrompts
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import dev.locus.core.model.ToolSpec
import kotlinx.serialization.json.JsonObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The helper prompts as they're sent, and the parsers that read whatever an edited prompt gets back. */
class HelperPromptsUseTest {
    private val history = listOf(
        Message("u1", "c", Role.USER, listOf(Part.Text("Plan a trip to Pokhara")), 1),
        Message("a1", "c", Role.ASSISTANT, listOf(Part.Text("Sure: three days?")), 2),
    )

    @Test
    fun `title and suggestion prompts use the override and keep the excerpt`() {
        val excerpt = HelperModel.excerpt(history, turns = 4, perMessage = 500)
        val s = AppSettings(titlePrompt = "Short Nepali title for:\n{chat}", suggestionsPrompt = "{chat}\n\nThree follow-ups, one per line.")
        assertEquals("Short Nepali title for:\n$excerpt", HelperModel.titlePrompt(excerpt, s))
        assertEquals("$excerpt\n\nThree follow-ups, one per line.", HelperModel.suggestionsPrompt(excerpt, s))
        // Defaults when nothing is saved, and when a saved prompt lost {chat}.
        assertEquals(HelperPrompts.title(AppSettings(), excerpt), HelperModel.titlePrompt(excerpt))
        assertTrue(excerpt in HelperModel.titlePrompt(excerpt, AppSettings(titlePrompt = "No placeholder")))
        assertTrue(excerpt in HelperModel.suggestionsPrompt(excerpt, AppSettings(suggestionsPrompt = "No placeholder")))
    }

    @Test
    fun `an edited compaction prompt changes only the last message`() {
        val conversation = Conversation("c", "Trip", null, "SYSTEM PROMPT", 0, 0)
        val tools = listOf(ToolSpec("read_file", "Reads a file", JsonObject(emptyMap())))
        val default = compactionRequest(conversation, history, tools, AppSettings(), id = "x", now = 5)
        val edited = compactionRequest(conversation, history, tools, AppSettings(compactionPrompt = "Summarise in five bullets."), id = "x", now = 5)
        // The cached prefix: system prompt, tools and history are the same objects in the same order.
        assertEquals(default.system, edited.system)
        assertEquals(default.tools, edited.tools)
        assertEquals(default.messages.dropLast(1), edited.messages.dropLast(1))
        assertEquals(history, edited.messages.dropLast(1))
        val ask = edited.messages.last()
        assertEquals(Role.USER, ask.role)
        assertEquals(HelperPrompts.COMPACTION_PREAMBLE + " Summarise in five bullets.", (ask.parts.single() as Part.Text).text)
        assertTrue((default.messages.last().parts.single() as Part.Text).text.startsWith(HelperPrompts.COMPACTION_PREAMBLE))
    }

    @Test
    fun `clean title handles the shapes an edited prompt can produce`() {
        assertEquals("Trip to Pokhara", HelperModel.cleanTitle("Trip to Pokhara"))
        assertEquals("Trip to Pokhara", HelperModel.cleanTitle("Title: \"Trip to Pokhara.\""))
        assertEquals("Trip to Pokhara", HelperModel.cleanTitle("**Title:** Trip to Pokhara"))
        assertEquals("Trip to Pokhara", HelperModel.cleanTitle("## Trip to Pokhara"))
        assertEquals("Trip to Pokhara", HelperModel.cleanTitle("Here's a title:\n\nTrip to Pokhara"))
        assertEquals("Trip to Pokhara", HelperModel.cleanTitle("<think>The user wants a trip.</think>\nTrip to Pokhara"))
        assertEquals("Trip to Pokhara", HelperModel.cleanTitle("```\nTrip to Pokhara\n```"))
        assertEquals("Trip to Pokhara", HelperModel.cleanTitle("{\"title\": \"Trip to Pokhara\"}"))
        assertEquals("Trip to Pokhara", HelperModel.cleanTitle("{\n  \"title\": \"Trip to Pokhara\"\n}"))
        assertEquals("पोखरा यात्रा", HelperModel.cleanTitle("पोखरा यात्रा"))
        // Symbols that belong to the title stay.
        assertEquals("`ls` flags", HelperModel.cleanTitle("`ls` flags"))
        assertEquals("Learning C#", HelperModel.cleanTitle("Learning C#"))
        assertEquals("__init__ files", HelperModel.cleanTitle("__init__ files"))
        assertNull(HelperModel.cleanTitle(""))
        assertNull(HelperModel.cleanTitle("  \n```\n```"))
        assertNull(HelperModel.cleanTitle("\"\""))
        val long = HelperModel.cleanTitle("A very long title that goes on and on about planning a trip to Pokhara in the spring")!!
        assertTrue(long.length <= 60)
        assertTrue("A very long title that goes on and on about planning a trip".startsWith(long))
    }

    @Test
    fun `clean suggestions handles lists, intros, json and noise`() {
        assertEquals(listOf("Book a hotel", "What about flights?", "Make it 5 days"), HelperModel.cleanSuggestions("Book a hotel\nWhat about flights?\nMake it 5 days"))
        assertEquals(
            listOf("Book a hotel", "What about flights?", "Make it 5 days"),
            HelperModel.cleanSuggestions("Here are some ideas:\n1. **Book a hotel**\n2) \"What about flights?\"\n- Make it 5 days\n- Extra one"),
        )
        assertEquals(listOf("Book a hotel", "Flights?"), HelperModel.cleanSuggestions("[\"Book a hotel\", \"Flights?\"]"))
        assertEquals(listOf("Book a hotel", "Flights?"), HelperModel.cleanSuggestions("```json\n[\n  \"Book a hotel\",\n  \"Flights?\"\n]\n```"))
        assertEquals(listOf("Book a hotel"), HelperModel.cleanSuggestions("<think>hmm</think>\nSuggestion 1: Book a hotel\nBook a hotel"))
        assertEquals(listOf("Short one"), HelperModel.cleanSuggestions("Short one\n" + "x ".repeat(60)))
        assertEquals(emptyList(), HelperModel.cleanSuggestions(""))
        assertEquals(emptyList(), HelperModel.cleanSuggestions("---\n***\n"))
    }
}
