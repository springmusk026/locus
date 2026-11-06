package dev.locus.feature.chat

import dev.locus.core.model.AppSettings
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.DiscoveryTools
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.ProviderDraft
import dev.locus.core.model.ProviderDraftApi
import dev.locus.core.model.ProviderDrafts
import dev.locus.core.model.Role
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The chat's card for a provider draft (docs/design/provider-drafts.md). */
class ProviderDraftCardTest {
    private fun msg(id: String, role: Role, vararg parts: Part) = Message(id, "c", role, parts.toList(), 0)

    @Test
    fun `a drafted provider becomes a card after the reply, through call_tool too, and a refused one stays a step`() {
        val direct = """{"name":"Groq","api_type":"openai_chat","base_url":"https://api.groq.com/openai/v1"}"""
        val viaFind = """{"name":"${ProviderDrafts.TOOL}","arguments":{"name":"Ollama","api_type":"openai_chat","base_url":"http://192.168.1.10:11434/v1"}}"""
        val call = msg(
            "a1", Role.ASSISTANT,
            Part.Text("Drafted it."),
            Part.ToolCall("t1", ProviderDrafts.TOOL, direct),
            Part.ToolCall("t2", DiscoveryTools.CALL_TOOL, viaFind),
            Part.ToolCall("t3", ProviderDrafts.TOOL, """{"name":"Bad","api_type":"openai_chat","base_url":"http://evil.example"}"""),
        )
        val results = mapOf(
            "t1" to Part.ToolResult("t1", ProviderDrafts.TOOL, "Drafted provider"),
            "t2" to Part.ToolResult("t2", DiscoveryTools.CALL_TOOL, "Drafted provider"),
            "t3" to Part.ToolResult("t3", ProviderDrafts.TOOL, "base_url: …", isError = true),
        )
        val items = turnItems(listOf(call), results)
        val cards = items.filterIsInstance<ChatItem.ProviderDraft>()
        assertEquals(listOf("Groq" to "api.groq.com", "Ollama" to "192.168.1.10"), cards.map { it.name to it.host })
        // Cards come last, under the reply; every call is still a step.
        assertEquals(cards, items.takeLast(2))
        assertEquals(3, items.count { it is ChatItem.ToolCall })
    }

    @Test
    fun `the card reads the draft's state from settings, never from the transcript`() {
        val draft = ProviderDraft("draft-1", "Groq", ProviderDraftApi.OPENAI_CHAT, "https://api.groq.com/openai/v1", conversationId = "c")
        val other = draft.copy(id = "draft-2", conversationId = "elsewhere", baseUrl = "https://evil.example/v1")
        val waiting = ProviderDraftStatus.of(AppSettings(providerDrafts = listOf(draft, other)), "c")
        assertEquals(ProviderDraftStatus.State.Waiting("draft-1"), waiting.of("api.groq.com"))
        // Another chat's draft isn't this chat's to review.
        assertEquals(ProviderDraftStatus.State.Gone, waiting.of("evil.example"))
        val approved = ProviderDraftStatus.of(
            AppSettings(providers = listOf(ProviderConfig("p", "Groq", ApiFormat.OPENAI_CHAT, "https://api.groq.com/openai/v1", "m"))),
            "c",
        )
        assertEquals(ProviderDraftStatus.State.Added, approved.of("api.groq.com"))
        assertTrue(ProviderDraftStatus.of(AppSettings(), "c").of("api.groq.com") == ProviderDraftStatus.State.Gone)
    }
}
