package dev.locus.feature.chat

import dev.locus.core.data.repository.ModelList
import dev.locus.core.llm.CatalogModel
import dev.locus.core.llm.ModelCapabilities
import dev.locus.core.llm.ModelRequirements
import dev.locus.core.llm.RemoteModel
import dev.locus.core.llm.Support
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.Effort
import dev.locus.core.model.Message
import dev.locus.core.model.ModelRef
import dev.locus.core.model.Part
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.Role
import dev.locus.core.model.TokenUsage
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a chat needs from a model, the warning before sending, and how the picker marks models. */
class ModelFitTest {
    private val image = Part.Attachment("cat.png", "image/png", "/x/cat.png", "cat.png", 10)
    private fun msg(id: String, role: Role, vararg parts: Part, usage: TokenUsage? = null) = Message(id, "c", role, parts.toList(), 0, usage = usage)

    @Test
    fun `a chat needs tools always, vision for images it sends, and reasoning only for an effort it chose`() {
        val plain = chatNeeds(listOf(msg("u", Role.USER, Part.Text("hi"))))
        assertTrue(plain.tools)
        assertFalse(plain.images)
        assertFalse(plain.reasoning)

        assertTrue(chatNeeds(listOf(msg("u", Role.USER, Part.Text("see"), image))).images)
        // An image attached in the composer counts before it's sent.
        assertTrue(plain.withComposer(listOf(image), chosenEffort = null).images)
        assertTrue(plain.withComposer(emptyList(), Effort.HIGH).reasoning)
        assertFalse(plain.withComposer(emptyList(), Effort.OFF).reasoning)
    }

    @Test
    fun `images before a compaction checkpoint no longer count`() {
        val messages = listOf(
            msg("u0", Role.USER, Part.Text("see"), image),
            msg("a0", Role.ASSISTANT, Part.Text("a cat")),
            msg("cp", Role.USER, Part.Checkpoint("They showed a cat.", replacedMessages = 2)),
            msg("u1", Role.USER, Part.Text("thanks")),
        )
        assertFalse(chatNeeds(messages).images)
    }

    @Test
    fun `the context size starts from the last reply's reported usage`() {
        val needs = chatNeeds(listOf(msg("u", Role.USER, Part.Text("x")), msg("a", Role.ASSISTANT, Part.Text("y"), usage = TokenUsage(150_000, 1_000))))
        assertEquals(151_000, needs.contextTokens)
    }

    private val ref = ModelRef("o", "gpt-5.2")
    private val vision = ModelCapabilities(tools = Support.YES, vision = Support.YES, contextWindow = 400_000)

    @Test
    fun `no warning when the model can take it, or nothing says it can't`() {
        val needs = ModelRequirements(tools = true, images = true)
        assertNull(sendWarning(needs, vision, "gpt-5.2", emptyList()))
        // Unknown never blocks a send.
        assertNull(sendWarning(needs, ModelCapabilities.UNKNOWN, "mystery", emptyList()))
        // Neither does a soft need: a model that doesn't reason still answers.
        assertNull(sendWarning(ModelRequirements(reasoning = true), ModelCapabilities(reasoning = Support.NO), "gpt-4o", emptyList()))
    }

    @Test
    fun `images for a model without vision warn and suggest one known to see them`() {
        val needs = ModelRequirements(tools = true, images = true)
        val blind = ModelCapabilities(tools = Support.YES, vision = Support.NO)
        val candidates = listOf(
            Triple(ModelRef("d", "deepseek-chat"), "deepseek-chat (DeepSeek)", blind),
            Triple(ModelRef("x", "mystery"), "mystery (Local)", ModelCapabilities.UNKNOWN),
            Triple(ref, "GPT-5.2 (OpenAI)", vision),
        )
        val warning = assertNotNull(sendWarning(needs, blind, "deepseek-chat", candidates, text = "what's this?"))
        assertEquals("deepseek-chat can't take this message", warning.title)
        assertEquals("no vision", warning.reason)
        // Not the blind one, and not the one nobody knows about: the one known to see.
        assertEquals(ref, warning.suggestion)
        assertEquals("GPT-5.2 (OpenAI)", warning.suggestionTitle)
        assertEquals("what's this?", warning.text)
        assertTrue("GPT-5.2 (OpenAI) can take it" in warning.body)
    }

    @Test
    fun `a context past the window warns, and with no known-good model there's no suggestion`() {
        val needs = ModelRequirements(tools = true, contextTokens = 300_000)
        val small = ModelCapabilities(tools = Support.YES, contextWindow = 128_000)
        val warning = assertNotNull(sendWarning(needs, small, "gpt-4o", listOf(Triple(ModelRef("x", "y"), "y (X)", ModelCapabilities.UNKNOWN))))
        assertTrue(warning.reason.startsWith("context too small (128K"))
        assertNull(warning.suggestion)
        assertTrue("Pick a model that can" in warning.body)
    }

    @Test
    fun `the picker marks models that can't serve the chat instead of hiding them`() {
        val anthropic = chatProvider(ProviderConfig("a", "Anthropic", ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.com", "claude-opus-5-5"))
        val local = chatProvider(ProviderConfig("l", "Local", ApiFormat.OPENAI_CHAT, "http://192.168.1.5:1234/v1", "qwen"))
        val lists = mapOf(
            "a" to ModelList.Loaded(listOf(RemoteModel("claude-opus-5-5"), RemoteModel("claude-2.1"))),
            "l" to ModelList.Loaded(listOf(RemoteModel("qwen"))),
        )
        val sections = buildModelPicker(
            listOf(anthropic, local), lists, "a", ChatControls(model = "claude-opus-5-5"), emptyList(), emptyList(),
            catalog = { _: ChatProvider, _: String -> null as CatalogModel? },
            needs = ModelRequirements(tools = true, images = true),
        )
        val rows = sections.flatMap { it.rows }.associateBy { it.id }
        assertEquals("Can't use here: no tool calling, no vision", cantUseLabel(rows.getValue("claude-2.1").fit))
        assertNull(cantUseLabel(rows.getValue("claude-opus-5-5").fit))
        // A local model nobody knows anything about stays usable, with a note.
        assertNull(cantUseLabel(rows.getValue("qwen").fit))
        assertEquals("Tool calling, vision not confirmed", unconfirmedLabel(rows.getValue("qwen").fit))
    }
}
