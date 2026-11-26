package dev.locus.core.data.repository

import dev.locus.core.agent.home.MemoryAuthor
import dev.locus.core.agent.home.MemoryTrust
import dev.locus.core.model.AppSettings
import dev.locus.core.model.Role
import dev.locus.core.model.Part
import dev.locus.core.testing.ScriptedProvider
import dev.locus.core.testing.ScriptedProvider.script
import dev.locus.core.testing.TestGraph
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The memory index of flagged notes (docs/design/taint.md, "Memory notes from outside content") goes
 * into new chats' prompts marked; a chat that already exists keeps the prompt it was created with, byte
 * for byte, and sends it unchanged, so its cached prefix stays valid.
 */
class FlaggedMemoryPromptTest {
    private val settings = AppSettings(
        providers = listOf(ScriptedProvider.config), defaultProviderId = ScriptedProvider.config.id,
        autoTitle = false, suggestFollowUps = false,
    )
    private val provider = ScriptedProvider.config.id

    private fun TestGraph.lastBody(): String =
        (requests.last { it.url.host == "llm.test" }.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()

    private suspend fun TestGraph.turn(chat: String, text: String) {
        val before = conversations.history(chat).count { it.role == Role.ASSISTANT && it.parts.none { p -> p is Part.ToolCall } }
        runner.send(chat, text)
        conversations.observeMessages(chat).first { m -> m.count { it.role == Role.ASSISTANT && it.parts.none { p -> p is Part.ToolCall } } > before }
    }

    @Test
    fun `an existing chat's prompt is unchanged after a note becomes flagged`() = runTest {
        val g = TestGraph(backgroundScope, settings)
        g.home.saveMemory("coffee", "Flat white with oat milk", "Flat white.", by = MemoryAuthor.USER)
        g.script(ScriptedProvider.text("one"), ScriptedProvider.text("two"))
        val old = g.conversations.create(provider)
        assertTrue("- [coffee](coffee.md) — Flat white with oat milk" in old.systemPrompt)
        g.turn(old.id, "hi")
        val firstBody = g.lastBody()

        // The note is replaced by a run that had read a web page: it's flagged now.
        g.home.saveMemory("coffee", "Flat white with oat milk", "Flat white.", by = MemoryAuthor.AGENT, chatId = "x", taintedBy = listOf("web_read"))

        val stored = assertNotNull(g.conversations.get(old.id))
        assertEquals(old.systemPrompt, stored.systemPrompt)
        g.turn(old.id, "again")
        // The second request starts with exactly the same system prompt as the first.
        assertTrue(MemoryTrust.INDEX_NOTE !in g.lastBody())
        assertFalse(MemoryTrust.INDEX_MARK in firstBody || MemoryTrust.INDEX_MARK in g.lastBody())

        // A new chat gets the marked index.
        val fresh = g.conversations.create(provider)
        assertTrue(MemoryTrust.INDEX_NOTE in fresh.systemPrompt)
        assertTrue("- [coffee](coffee.md) — (from outside content) Flat white with oat milk" in fresh.systemPrompt)
        assertEquals(old.systemPrompt, assertNotNull(g.conversations.get(old.id)).systemPrompt)
    }
}
