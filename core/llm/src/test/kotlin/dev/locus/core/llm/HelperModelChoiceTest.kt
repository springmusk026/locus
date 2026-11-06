package dev.locus.core.llm

import dev.locus.core.model.ApiFormat
import dev.locus.core.model.ModelPrice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The "Automatic" helper model (docs/design/cost.md, "The helper model"). */
class HelperModelChoiceTest {
    private fun m(input: Double, output: Double, released: String?, context: Int? = 1_000_000, tools: Boolean? = true, deprecated: Boolean? = null) =
        CatalogModel(context = context, tools = tools, price = ModelPrice(input, output), released = released, deprecated = deprecated)

    private val catalog = ModelCatalogIndex(
        mapOf(
            "anthropic" to CatalogProvider(
                models = mapOf(
                    "claude-opus-5-5" to m(4.0, 20.0, "2026-09-22"),
                    "claude-sonnet-5-5" to m(2.0, 10.0, "2026-09-28"),
                    "claude-haiku-5-5" to m(0.1, 0.5, "2026-10-07"),
                    "claude-haiku-4-5" to m(1.0, 5.0, "2025-10-15"),
                    "claude-3-haiku-20240307" to m(0.25, 1.25, "2024-03-07", deprecated = true),
                ),
            ),
            "openai" to CatalogProvider(
                models = mapOf(
                    "gpt-6.1" to m(5.0, 30.0, "2026-09-01", context = 400_000),
                    "gpt-6.1-mini" to m(0.4, 1.6, "2026-09-01", context = 400_000),
                    "gpt-6.1-nano" to m(0.05, 0.4, "2026-09-01", context = 400_000),
                    "gpt-realtime-mini" to m(0.6, 2.4, "2026-10-01", context = 128_000),
                    "gpt-5-mini" to m(0.25, 2.0, "2025-08-07", context = 400_000),
                    "o4-mini" to m(1.1, 4.4, "2025-04-16", context = 200_000),
                ),
            ),
            "google" to CatalogProvider(
                models = mapOf(
                    "gemini-3-pro" to m(2.0, 12.0, "2026-05-01"),
                    "gemini-3-flash" to m(0.3, 2.5, "2026-06-01"),
                    "gemini-3-flash-lite" to m(0.1, 0.4, "2026-07-01"),
                    // Newer, but it can't call tools: skipped.
                    "gemini-3.5-flash-image" to m(0.3, 2.5, "2026-10-01", tools = false),
                ),
            ),
            "openrouter" to CatalogProvider(
                models = mapOf(
                    "anthropic/claude-opus-5.5" to m(4.0, 20.0, "2026-09-22"),
                    "anthropic/claude-haiku-5.5" to m(0.1, 0.5, "2026-10-07"),
                    "openai/gpt-6.1" to m(5.0, 30.0, "2026-09-01", context = 400_000),
                    "openai/gpt-6.1-mini" to m(0.4, 1.6, "2026-09-01", context = 400_000),
                    "google/gemini-3-flash" to m(0.3, 2.5, "2026-06-01"),
                    "mistralai/mistral-large-3" to m(2.0, 6.0, "2026-02-01", context = 128_000),
                ),
            ),
            // A provider whose newest cheap model is too small for helpers.
            "groq" to CatalogProvider(
                models = mapOf(
                    "llama-5-70b" to m(0.6, 0.8, "2026-04-01", context = 128_000),
                    "llama-5-mini" to m(0.1, 0.1, "2026-05-01", context = 32_000),
                ),
            ),
        ),
    )

    private fun pick(baseUrl: String, model: String, format: ApiFormat = ApiFormat.OPENAI_CHAT, index: ModelCatalogIndex? = catalog) =
        HelperModelChoice.automatic(format, baseUrl, model, index)

    @Test
    fun `a known cheaper model of the same provider is picked`() {
        assertEquals("claude-haiku-5-5", pick("https://api.anthropic.com", "claude-opus-5-5", ApiFormat.ANTHROPIC_MESSAGES))
        assertEquals("claude-haiku-5-5", pick("https://api.anthropic.com", "claude-sonnet-5-5", ApiFormat.ANTHROPIC_MESSAGES))
        // OpenAI: the newest mini, not the nano, a realtime model, last year's mini or another family's (o4-mini).
        assertEquals("gpt-6.1-mini", pick("https://api.openai.com/v1", "gpt-6.1", ApiFormat.OPENAI_RESPONSES))
        // Gemini: Flash, not Flash-Lite, and not a newer image model that can't call tools.
        assertEquals("gemini-3-flash", pick("https://generativelanguage.googleapis.com/v1beta/openai", "gemini-3-pro"))
        // Through OpenRouter: the same maker's cheap tier, under OpenRouter's ids.
        assertEquals("anthropic/claude-haiku-5.5", pick("https://openrouter.ai/api/v1", "anthropic/claude-opus-5.5"))
        assertEquals("openai/gpt-6.1-mini", pick("https://openrouter.ai/api/v1", "openai/gpt-6.1"))
    }

    @Test
    fun `without the catalogue, only the documented default for Claude`() {
        assertEquals("claude-haiku-5-5", pick("https://api.anthropic.com", "claude-fable-5-1", ApiFormat.ANTHROPIC_MESSAGES, index = null))
        assertEquals("anthropic/claude-haiku-5.5", pick("https://openrouter.ai/api/v1", "anthropic/claude-sonnet-5.5", index = null))
        // Others need the catalogue's prices to know what's cheaper.
        assertNull(pick("https://api.openai.com/v1", "gpt-6.1", ApiFormat.OPENAI_RESPONSES, index = null))
        assertNull(pick("https://openrouter.ai/api/v1", "openai/gpt-6.1", index = null))
        // A gateway that speaks Anthropic's format may not serve Haiku under that id.
        assertNull(pick("https://gateway.example.com", "claude-opus-5-5", ApiFormat.ANTHROPIC_MESSAGES, index = null))
    }

    @Test
    fun `an unknown provider or model falls back to the chat's own`() {
        // Local servers serve whatever is loaded.
        assertNull(pick("http://localhost:11434/v1", "qwen3:32b"))
        assertNull(pick("http://192.168.1.20:1234/v1", "llama-5-70b"))
        // A provider the catalogue doesn't list, and a model family with no cheap tier there.
        assertNull(pick("https://llm.example.com/v1", "acme-large"))
        assertNull(pick("https://openrouter.ai/api/v1", "mistralai/mistral-large-3"))
        // On-device: never.
        assertNull(pick("", "gemma-4-e2b", ApiFormat.ON_DEVICE))
    }

    @Test
    fun `a model already on a cheap tier keeps itself`() {
        assertNull(pick("https://api.anthropic.com", "claude-haiku-5-5", ApiFormat.ANTHROPIC_MESSAGES))
        // Not last year's cheaper Haiku either.
        assertNull(pick("https://api.anthropic.com", "claude-haiku-5-5", ApiFormat.ANTHROPIC_MESSAGES, index = null))
        assertNull(pick("https://api.openai.com/v1", "gpt-6.1-mini"))
        assertNull(pick("https://generativelanguage.googleapis.com/v1beta/openai", "gemini-3-flash-lite"))
    }

    @Test
    fun `a capability mismatch is skipped`() {
        // In the catalogue: Groq's only cheap tier has a 32K window, under the helper minimum.
        assertNull(pick("https://api.groq.com/openai/v1", "llama-5-70b"))
        // At negotiation, against what the provider says.
        val ok = ModelCapabilities(tools = Support.YES, contextWindow = 1_000_000)
        assertTrue(HelperModelChoice.fits("claude-haiku-5-5", ok, listedIds = null))
        assertTrue(HelperModelChoice.fits("claude-haiku-5-5", ModelCapabilities(), listedIds = null))
        assertFalse(HelperModelChoice.fits("claude-haiku-5-5", ok.copy(tools = Support.NO), listedIds = null))
        assertFalse(HelperModelChoice.fits("claude-haiku-5-5", ok.copy(contextWindow = 64_000), listedIds = null))
        // The provider's own list doesn't have it: this key can't use it.
        assertFalse(HelperModelChoice.fits("claude-haiku-5-5", ok, listedIds = listOf("claude-opus-5-5", "claude-sonnet-5-5")))
        assertTrue(HelperModelChoice.fits("claude-haiku-5-5", ok, listedIds = listOf("claude-opus-5-5", "claude-haiku-5-5")))
    }

    @Test
    fun `models dev release dates and deprecation are read`() {
        val index = ModelCatalogIndex.parseModelsDev(
            """{"anthropic":{"models":{
                "claude-haiku-5-5":{"name":"Claude Haiku 5.5","release_date":"2026-10-07","tool_call":true,"limit":{"context":1000000},"cost":{"input":0.1,"output":0.5}},
                "claude-3-haiku-20240307":{"release_date":"2024-03","status":"deprecated","tool_call":true},
                "odd":{"release_date":"soon","status":"beta"}
            }}}""",
        )
        val models = index.providers.getValue("anthropic").models
        assertEquals("2026-10-07", models.getValue("claude-haiku-5-5").released)
        assertNull(models.getValue("claude-haiku-5-5").deprecated)
        assertEquals("2024-03", models.getValue("claude-3-haiku-20240307").released)
        assertEquals(true, models.getValue("claude-3-haiku-20240307").deprecated)
        assertNull(models.getValue("odd").released)
        assertEquals(false, models.getValue("odd").deprecated)
    }
}
