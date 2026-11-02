package dev.locus.core.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HelperPromptsTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `settings saved before helper prompts existed load with the defaults`() {
        val old = json.decodeFromString(AppSettings.serializer(), """{"providers":[],"autoTitle":false,"suggestFollowUps":true,"modelCatalog":true}""")
        assertNull(old.titlePrompt)
        assertNull(old.suggestionsPrompt)
        assertNull(old.compactionPrompt)
        assertFalse(old.autoTitle)
        HelperPrompt.entries.forEach { assertEquals(HelperPrompts.default(it), HelperPrompts.template(old, it)) }
    }

    @Test
    fun `overrides survive a save and load`() {
        val s = AppSettings(titlePrompt = "Name it: {chat}", compactionPrompt = "Summarise briefly.")
        val back = json.decodeFromString(AppSettings.serializer(), json.encodeToString(AppSettings.serializer(), s))
        assertEquals("Name it: {chat}", back.titlePrompt)
        assertNull(back.suggestionsPrompt)
        assertEquals("Summarise briefly.", back.compactionPrompt)
    }

    @Test
    fun `null means the default, a valid override wins`() {
        val s = AppSettings(suggestionsPrompt = "Three ideas for the user, one per line.\n{chat}")
        assertEquals(HelperPrompts.default(HelperPrompt.TITLE), HelperPrompts.template(s, HelperPrompt.TITLE))
        assertEquals("Three ideas for the user, one per line.\n{chat}", HelperPrompts.template(s, HelperPrompt.SUGGESTIONS))
        assertTrue(HelperPrompts.isCustom(s, HelperPrompt.SUGGESTIONS))
        assertFalse(HelperPrompts.isCustom(s, HelperPrompt.TITLE))
    }

    @Test
    fun `an override without the chat placeholder is never sent`() {
        val s = AppSettings(titlePrompt = "Write a title.", suggestionsPrompt = "   ")
        assertEquals(HelperPrompts.default(HelperPrompt.TITLE), HelperPrompts.template(s, HelperPrompt.TITLE))
        assertEquals(HelperPrompts.default(HelperPrompt.SUGGESTIONS), HelperPrompts.template(s, HelperPrompt.SUGGESTIONS))
        assertTrue("User: hi" in HelperPrompts.title(s, "User: hi"))
    }

    @Test
    fun `defaults are valid and carry the chat`() {
        HelperPrompt.entries.forEach { p ->
            val check = HelperPrompts.check(p, HelperPrompts.default(p))
            assertTrue(check.ok, "$p: ${check.errors}")
            assertEquals(emptyList(), check.warnings)
        }
        assertTrue("<chat>\nUser: hi\n</chat>" in HelperPrompts.title(AppSettings(), "User: hi"))
        assertTrue("<chat>\nUser: hi\n</chat>" in HelperPrompts.suggestions(AppSettings(), "User: hi"))
    }

    @Test
    fun `missing required placeholder, empty and too long are errors`() {
        assertFalse(HelperPrompts.check(HelperPrompt.TITLE, "Write a short title.").ok)
        assertTrue(HelperPrompts.check(HelperPrompt.TITLE, "Write a short title.").errors.single().startsWith("Add {chat}"))
        assertFalse(HelperPrompts.check(HelperPrompt.SUGGESTIONS, "Ideas please").ok)
        assertFalse(HelperPrompts.check(HelperPrompt.COMPACTION, "  ").ok)
        assertFalse(HelperPrompts.check(HelperPrompt.TITLE, "{chat}" + "x".repeat(HelperPrompts.MAX_LENGTH)).ok)
        // Compaction needs no placeholder: the chat itself comes before it.
        assertTrue(HelperPrompts.check(HelperPrompt.COMPACTION, "Summarise in five bullets.").ok)
    }

    @Test
    fun `unknown placeholders warn without blocking`() {
        val check = HelperPrompts.check(HelperPrompt.TITLE, "Title in {language}: {chat}")
        assertTrue(check.ok)
        assertEquals(listOf("{language} isn't a placeholder here, so it's sent as typed."), check.warnings)
        assertEquals(1, HelperPrompts.check(HelperPrompt.COMPACTION, "Summarise {chat}").warnings.size)
        // Braces that aren't placeholder-shaped (JSON, code) are left alone.
        assertEquals(emptyList(), HelperPrompts.check(HelperPrompt.TITLE, "Reply as {\"title\": \"…\"}. {chat}").warnings)
    }

    @Test
    fun `render fills every slot once and never rescans the excerpt`() {
        assertEquals("a X b X", HelperPrompts.render("a {chat} b {chat}", mapOf("chat" to "X")))
        assertEquals("User: type {chat} and \$1", HelperPrompts.render("{chat}", mapOf("chat" to "User: type {chat} and \$1")))
        assertEquals("keep {other}", HelperPrompts.render("keep {other}", mapOf("chat" to "X")))
    }

    @Test
    fun `prompts are built with the override`() {
        val s = AppSettings(titlePrompt = "Titre en français : {chat}", suggestionsPrompt = "Ideas:\n{chat}\nEnd", compactionPrompt = "Summarise in bullets.")
        assertEquals("Titre en français : User: salut", HelperPrompts.title(s, "User: salut"))
        assertEquals("Ideas:\nUser: hi\nEnd", HelperPrompts.suggestions(s, "User: hi"))
        assertEquals(HelperPrompts.COMPACTION_PREAMBLE + " Summarise in bullets.", HelperPrompts.compaction(s))
    }

    @Test
    fun `the default compaction message is unchanged`() {
        val before = "[Automatic context summary, not from the user] The conversation is getting long. Do not call any tools and do not continue the task. " +
            "Write a summary that will replace everything above in your context, so a fresh you can carry on without loss. Use these sections:\n" +
            "## Goal: what the user wants overall\n## Context: facts, preferences and constraints the user gave\n## Done so far: decisions and results, with exact values " +
            "(paths, commands, URLs, numbers, ids)\n## Problems: errors hit and how they were solved\n## Open: what's unfinished and the next step\n" +
            "Be specific and complete; leave out pleasantries."
        assertEquals(before, HelperPrompts.compaction(AppSettings()))
    }

    @Test
    fun `normalize stores null for blank or default text`() {
        assertNull(HelperPrompts.normalize(HelperPrompt.TITLE, null))
        assertNull(HelperPrompts.normalize(HelperPrompt.TITLE, "  \n"))
        assertNull(HelperPrompts.normalize(HelperPrompt.TITLE, HelperPrompts.default(HelperPrompt.TITLE) + "\n"))
        assertEquals("Name it {chat}", HelperPrompts.normalize(HelperPrompt.TITLE, " Name it {chat}\n"))
        val set = HelperPrompts.withOverride(AppSettings(), HelperPrompt.COMPACTION, "x")
        assertEquals("x", set.compactionPrompt)
        assertNull(HelperPrompts.withOverride(set, HelperPrompt.COMPACTION, null).compactionPrompt)
    }
}
