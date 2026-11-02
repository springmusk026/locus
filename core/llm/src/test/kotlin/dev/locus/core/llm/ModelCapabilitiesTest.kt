package dev.locus.core.llm

import dev.locus.core.model.ApiFormat
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import dev.locus.core.model.TokenUsage
import dev.locus.core.model.ToolSpec
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelCapabilitiesTest {
    private val router = "https://openrouter.ai/api/v1"

    @Test
    fun `the provider's list wins, then the catalogue, then the rules, field by field`() {
        // The provider says no vision; the catalogue says yes: the provider wins.
        val caps = ModelCapabilityResolver.resolve(
            ApiFormat.OPENAI_CHAT, router, "anthropic/claude-sonnet-5-5",
            listed = RemoteModel("anthropic/claude-sonnet-5-5", vision = false, contextWindow = 200_000),
            catalog = CatalogModel(context = 1_000_000, vision = true, tools = false, structuredOutput = true),
        )
        assertEquals(Support.NO, caps.vision)
        assertEquals(200_000, caps.contextWindow)
        // The provider doesn't say about tools or structured output; the catalogue does.
        assertEquals(Support.NO, caps.tools)
        assertEquals(Support.YES, caps.structuredOutput)
        // Nobody but the Claude rule knows it reasons.
        assertEquals(Support.YES, caps.reasoning)
        assertEquals(CacheStyle.EXPLICIT, caps.caching)
    }

    @Test
    fun `the user's window for the provider beats what the provider lists`() {
        val caps = ModelCapabilityResolver.resolve(
            ApiFormat.OPENAI_CHAT, "https://llm.example/v1", "my-model",
            listed = RemoteModel("my-model", contextWindow = 8_000),
            configuredContext = 32_000,
        )
        assertEquals(32_000, caps.contextWindow)
    }

    @Test
    fun `an unknown model stays unknown in every field`() {
        val caps = ModelCapabilityResolver.resolve(ApiFormat.OPENAI_CHAT, "https://llm.example/v1", "mystery-7b")
        assertEquals(ModelCapabilities.UNKNOWN, caps)
        // Unknown is never "no": it serves anything, but says what it couldn't confirm.
        val fit = caps.fit(ModelRequirements(tools = true, images = true, contextTokens = 100_000))
        assertTrue(fit.usable)
        assertEquals(listOf(Need.TOOLS, Need.VISION, Need.CONTEXT), fit.unconfirmed)
        assertEquals("tool calling, vision, context window", fit.unconfirmedLabel)
    }

    @Test
    fun `the catalogue's false is a known no, its absence unknown`() {
        val caps = ModelCapabilityResolver.fromCatalog(CatalogModel(tools = false, vision = null))
        assertEquals(Support.NO, caps.tools)
        assertEquals(Support.UNKNOWN, caps.vision)
        assertEquals(Support.YES, Support.UNKNOWN.or(Support.YES))
        assertEquals(Support.NO, Support.NO.or(Support.YES))
    }

    @Test
    fun `built-in rules cover well-known families only`() {
        fun rule(id: String, format: ApiFormat = ApiFormat.OPENAI_CHAT, base: String = "https://api.openai.com/v1") = CapabilityRules.of(format, base, id)
        assertEquals(Support.NO, rule("gpt-3.5-turbo").vision)
        assertEquals(Support.YES, rule("gpt-4o-mini").vision)
        assertEquals(Support.YES, rule("gpt-5.2").reasoning)
        assertEquals(Support.NO, rule("o1-mini").tools)
        assertEquals(Support.NO, rule("deepseek-chat", base = "https://api.deepseek.com").vision)
        assertEquals(Support.YES, rule("claude-opus-5-5", ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.com").reasoning)
        assertEquals(Support.NO, rule("claude-3-5-haiku-latest", ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.com").reasoning)
        assertEquals(Support.YES, rule("models/gemini-2.5-flash", base = "https://generativelanguage.googleapis.com/v1beta/openai").reasoning)
        assertEquals(CacheStyle.AUTOMATIC, rule("gpt-5.2").caching)
        assertEquals(ModelCapabilities.UNKNOWN, rule("llama-3.3-70b", base = "https://api.groq.com/openai/v1"))
        val phone = rule("gemma-4-e2b", ApiFormat.ON_DEVICE, "")
        assertEquals(CapabilityRules.ON_DEVICE_CONTEXT, phone.contextWindow)
        assertEquals(CacheStyle.NONE, phone.caching)
    }

    @Test
    fun `OpenRouter's supported parameters and modalities are read as the provider's own report`() {
        val entry = LlmJson.parseToJsonElement(
            """{"id":"openai/gpt-x","context_length":128000,"architecture":{"input_modalities":["text"]},
               "supported_parameters":["max_tokens","include_reasoning","response_format"]}""",
        ) as JsonObject
        val m = ModelLister.remoteModel(entry)!!
        assertEquals(128_000, m.contextWindow)
        assertEquals(false, m.tools)
        assertEquals(false, m.vision)
        assertEquals(true, m.reasoning)
        assertEquals(false, m.structuredOutput)
    }

    @Test
    fun `Anthropic's capabilities object is read, and a plain list reports nothing`() {
        val claude = ModelLister.remoteModel(
            LlmJson.parseToJsonElement(
                """{"id":"claude-x","max_input_tokens":1000000,"capabilities":{"image_input":{"supported":true},"thinking":{"supported":false},"structured_outputs":{"supported":true}}}""",
            ) as JsonObject,
        )!!
        assertEquals(RemoteModel("claude-x", "claude-x", 1_000_000, tools = null, vision = true, reasoning = false, structuredOutput = true), claude)
        val plain = ModelLister.remoteModel(LlmJson.parseToJsonElement("""{"id":"gpt-x"}""") as JsonObject)!!
        assertNull(plain.tools)
        assertNull(plain.vision)
    }

    private fun msg(role: Role, vararg parts: Part, usage: TokenUsage? = null) = Message("m${parts.hashCode()}", "c", role, parts.toList(), 0, usage = usage)
    private val image = Part.Attachment("cat.png", "image/png", "/x/cat.png", "cat.png", 1_000)
    private val pdf = Part.Attachment("a.pdf", "application/pdf", "/x/a.pdf", "a.pdf", 1_000)
    private val tool = ToolSpec("read", "Reads a file", JsonObject(emptyMap()))

    @Test
    fun `requirements come from the request - tools declared, images sent, its size`() {
        val plain = ModelRequirements.of(ChatRequest("system", listOf(msg(Role.USER, Part.Text("hi"))), tools = emptyList()))
        assertFalse(plain.tools)
        assertFalse(plain.images)
        assertFalse(plain.reasoning)

        val request = ChatRequest("s", listOf(msg(Role.USER, Part.Text("look"), image)), tools = listOf(tool))
        val needs = ModelRequirements.of(request, reasoning = true)
        assertTrue(needs.tools)
        assertTrue(needs.images)
        assertTrue(needs.reasoning)
        assertTrue(needs.contextTokens >= ModelRequirements.IMAGE_TOKENS)

        // A PDF isn't an image; a pending image counts before it's sent.
        assertFalse(ModelRequirements.of(ChatRequest("s", listOf(msg(Role.USER, pdf)), emptyList())).images)
        assertTrue(ModelRequirements.of(ChatRequest("s", emptyList(), emptyList()), pending = listOf(image)).images)
    }

    @Test
    fun `the context estimate starts from the provider's last count`() {
        val messages = listOf(
            msg(Role.USER, Part.Text("x".repeat(400_000))),
            msg(Role.ASSISTANT, Part.Text("ok"), usage = TokenUsage(input = 120_000, output = 1_000)),
            msg(Role.USER, Part.Text("y".repeat(4_000))),
        )
        assertEquals(121_000 + 1_000, ModelRequirements.estimateTokens(ChatRequest("s", messages, emptyList())))
        // Without any count: everything, about four characters a token.
        assertEquals(1 + 100_000, ModelRequirements.estimateTokens(ChatRequest("abcd", messages.take(1), emptyList())))
    }

    @Test
    fun `a window smaller than the context plus headroom is too small`() {
        val caps = ModelCapabilities(contextWindow = 128_000)
        assertTrue(caps.fit(ModelRequirements(contextTokens = 100_000)).usable)
        val fit = caps.fit(ModelRequirements(contextTokens = 125_000))
        assertFalse(fit.usable)
        assertEquals(listOf(Need.CONTEXT), fit.lacks)
        assertEquals("context too small (128K, needs ~133K)", fit.reason)
        // A small window keeps most of its room: the margin is at most an eighth of it.
        assertTrue(ModelCapabilities(contextWindow = 16_384).fit(ModelRequirements(contextTokens = 12_000)).usable)
        // A small chat says nothing about an unknown window.
        assertTrue(ModelCapabilities().fit(ModelRequirements(contextTokens = 2_000)).unconfirmed.isEmpty())
    }

    @Test
    fun `a missing soft need never makes a model unusable`() {
        val fit = ModelCapabilities(reasoning = Support.NO, vision = Support.NO).fit(ModelRequirements(reasoning = true))
        assertTrue(fit.usable)
        assertEquals(listOf(Need.REASONING), fit.lacks)
        assertNull(fit.reason)
        val blocked = ModelCapabilities(reasoning = Support.NO, vision = Support.NO).fit(ModelRequirements(images = true, reasoning = true))
        assertEquals("no vision", blocked.reason)
    }
}
