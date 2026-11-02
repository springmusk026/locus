package dev.locus.core.agent

import dev.locus.core.agent.home.AgentHome
import dev.locus.core.agent.home.AgentHome.PersonaFile
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PromptBudgetTest {
    private val dir: File = Files.createTempDirectory("home").toFile()
    private var now = 0L
    private val home = AgentHome(dir) { ++now }
    private val day = LocalDate.of(2026, 10, 31)

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun `estimates count characters, not bytes, and never undercount non-Latin text`() {
        assertEquals(1, TokenEstimate.of("abcd"))
        assertEquals(4, TokenEstimate.of("你好世界")) // 12 bytes in UTF-8, 4 characters
        assertTrue(TokenEstimate.of("Привет") >= 6) // at least one per Cyrillic letter
        assertEquals(1, TokenEstimate.of("😀")) // one code point, two UTF-16 chars
    }

    @Test
    fun `fit keeps whole lines and adds the note only when cutting`() {
        val text = (1..100).joinToString("\n") { "- topic-$it: something worth remembering" }
        assertEquals(text, PromptBudget.fit(text, 10_000) { "cut" })
        val cut = PromptBudget.fit(text, 100) { n -> "[$n more]" }
        assertTrue(TokenEstimate.of(cut) <= 110, "within budget plus the note")
        assertTrue(cut.lines().dropLast(1).all { it.startsWith("- topic-") && it.endsWith("remembering") })
        val kept = cut.lines().size - 1
        assertEquals("[${100 - kept} more]", cut.lines().last())
    }

    @Test
    fun `fit shortens a single overlong line instead of dropping it`() {
        val cut = PromptBudget.fit("界".repeat(500), 50) { "[cut]" }
        assertTrue(cut.startsWith("界".repeat(50)) && cut.endsWith("[cut]"))
        assertTrue(TokenEstimate.of(cut) <= 60)
    }

    @Test
    fun `a large memory index is capped with a note pointing at the memory tools`() {
        repeat(600) { home.saveMemory("topic $it", "Summary of topic $it with a few more words", "body") }
        val snap = home.snapshot()
        val index = snap.substringAfter("<memory_index>\n").substringBefore("</memory_index>")
        assertTrue(TokenEstimate.of(index) <= PromptBudget.STANDARD.memory + 40)
        assertTrue("use memory_search to find them, then memory_read" in index)
    }

    @Test
    fun `a large skill catalog is capped with a note pointing at skill_use`() {
        repeat(200) { home.draftSkill("skill-$it", "Does thing number $it in a long and detailed way, step by step.", "body", emptyMap()); home.approveSkill("skill-$it") }
        val skills = home.snapshot().substringAfter("<available_skills>\n").substringBefore("</available_skills>")
        assertTrue(TokenEstimate.of(skills) <= PromptBudget.STANDARD.skills + 40)
        assertTrue("call skill_use" in skills)
    }

    @Test
    fun `small homes give the same prompt as before budgets`() {
        home.writePersona(PersonaFile.IDENTITY, AgentHome.identityMarkdown("Juno", "calm"))
        home.saveMemory("coffee", "Takes it black", "Black, no sugar.")
        val plain = SystemPrompt.build("Be brief.", home.snapshot(), day, "- “Trip” (id 1)")
        val fitted = SystemPrompt.forNewChat("Be brief.", home::snapshot, contextWindow = 200_000, recentChats = "- “Trip” (id 1)", today = day)
        assertEquals(plain, fitted)
    }

    @Test
    fun `a small window gets a prompt within its share, still named, and the same every time`() {
        home.writePersona(PersonaFile.IDENTITY, AgentHome.identityMarkdown("Juno", "calm") + "\n" + "Long vibe text. ".repeat(300))
        home.writePersona(PersonaFile.USER, "我喜欢长篇的介绍。".repeat(400))
        repeat(400) { home.saveMemory("topic $it", "Summary of topic $it with a few more words", "body") }
        repeat(100) { home.draftSkill("skill-$it", "Does thing number $it in a long and detailed way.", "body", emptyMap()); home.approveSkill("skill-$it") }
        val recent = (1..8).joinToString("\n") { "- “Chat $it” (id $it)" }
        val build = { SystemPrompt.forNewChat("Rule. ".repeat(2_000), home::snapshot, contextWindow = 16_384, recentChats = recent, today = day) }
        val prompt = build()
        val budget = PromptBudget.forWindow(16_384)
        assertEquals((16_384 * PromptBudget.WINDOW_SHARE).toInt(), budget.total)
        assertTrue(TokenEstimate.of(prompt) <= budget.total, "estimated ${TokenEstimate.of(prompt)} > ${budget.total}")
        assertTrue(prompt.startsWith("You are Juno,"))
        assertTrue("memory_search" in prompt && "skill_use" in prompt)
        assertTrue("<recent_chats>" !in prompt, "compact prompts leave recent chats to chat_history_search")
        assertEquals(prompt, build(), "frozen: the same inputs give the same prompt")
    }

    @Test
    fun `unknown windows use the standard budget and large windows never exceed it`() {
        assertEquals(PromptBudget.STANDARD, PromptBudget.forWindow(null))
        assertEquals(PromptBudget.STANDARD.total, PromptBudget.forWindow(1_000_000).total)
        assertEquals(PromptBudget.COMPACT.memory, PromptBudget.forWindow(8_192).memory)
    }
}
