package dev.locus.feature.chat

import dev.locus.core.data.repository.ModelList
import dev.locus.core.llm.CacheStyle
import dev.locus.core.llm.CatalogModel
import dev.locus.core.llm.ModelCapabilities
import dev.locus.core.llm.Support
import dev.locus.core.llm.RemoteModel
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.ModelRef
import dev.locus.core.model.ProviderConfig
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelPickerTest {
    private val anthropic = chatProvider(ProviderConfig("a", "Anthropic", ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.com", "claude-opus-5-5"))
    private val router = chatProvider(ProviderConfig("r", "OpenRouter", ApiFormat.OPENAI_CHAT, "https://openrouter.ai/api/v1", "openai/gpt-5"))
    private val providers = listOf(anthropic, router)
    private val lists = mapOf(
        "a" to ModelList.Loaded(
            listOf(
                RemoteModel("claude-haiku-5", "Claude Haiku 5"),
                RemoteModel("claude-opus-5-5", "Claude Opus 5.5", contextWindow = 1_000_000),
                RemoteModel("claude-sonnet-5-5", "Claude Sonnet 5.5"),
            ),
        ),
        "r" to ModelList.Loaded(listOf(RemoteModel("anthropic/claude-sonnet-5-5"), RemoteModel("google/gemini-3-pro"), RemoteModel("openai/gpt-5"))),
    )
    private val inUse = ChatControls(model = "claude-opus-5-5")

    private fun build(
        query: String = "",
        favorites: List<ModelRef> = emptyList(),
        recents: List<ModelRef> = emptyList(),
        lists: Map<String, ModelList> = this.lists,
        catalog: (ChatProvider, String) -> CatalogModel? = { _, _ -> null },
        perProvider: Int = MAX_MODELS_PER_PROVIDER,
    ) = buildModelPicker(providers, lists, "a", inUse, favorites, recents, query, catalog, perProvider)

    private fun List<PickerSection>.layout() = map { s -> s.key to s.rows.map { "${it.ref.providerId}:${it.id}" } }

    @Test
    fun `favourites come first in the order starred, then recents not starred, then every provider`() {
        val sections = build(
            favorites = listOf(ModelRef("r", "google/gemini-3-pro"), ModelRef("a", "claude-haiku-5")),
            recents = listOf(ModelRef("a", "claude-haiku-5"), ModelRef("r", "openai/gpt-5")),
        )
        assertEquals(
            listOf(
                FAVORITES_KEY to listOf("r:google/gemini-3-pro", "a:claude-haiku-5"),
                RECENT_KEY to listOf("r:openai/gpt-5"),
                "a" to listOf("a:claude-opus-5-5", "a:claude-haiku-5", "a:claude-sonnet-5-5"),
                "r" to listOf("r:openai/gpt-5", "r:anthropic/claude-sonnet-5-5", "r:google/gemini-3-pro"),
            ),
            sections.layout(),
        )
        assertTrue(sections.first().rows.all { it.favorite })
    }

    @Test
    fun `favourites and recents of removed providers are left out`() {
        val sections = build(favorites = listOf(ModelRef("gone", "x")), recents = listOf(ModelRef("gone", "y")))
        assertEquals(listOf("a", "r"), sections.map { it.key })
    }

    @Test
    fun `search covers every provider, by id, display name and provider name, every word`() {
        assertEquals(
            listOf("a" to listOf("a:claude-sonnet-5-5"), "r" to listOf("r:anthropic/claude-sonnet-5-5")),
            build(query = "sonnet").layout(),
        )
        // Display name, not only the id.
        assertEquals(listOf("a" to listOf("a:claude-opus-5-5")), build(query = "Opus 5.5").layout())
        // Provider name narrows: every word must match somewhere.
        assertEquals(listOf("r" to listOf("r:anthropic/claude-sonnet-5-5")), build(query = "openrouter sonnet").layout())
        assertEquals(emptyList(), build(query = "no such model").layout())
    }

    @Test
    fun `in search, favourites lead and recent picks rise within their provider`() {
        val sections = build(
            query = "claude",
            favorites = listOf(ModelRef("r", "anthropic/claude-sonnet-5-5")),
            recents = listOf(ModelRef("a", "claude-sonnet-5-5")),
        )
        assertEquals(
            listOf(
                FAVORITES_KEY to listOf("r:anthropic/claude-sonnet-5-5"),
                "a" to listOf("a:claude-sonnet-5-5", "a:claude-opus-5-5", "a:claude-haiku-5"),
            ),
            sections.layout(),
        )
    }

    @Test
    fun `providers still loading stay in search results so nothing looks missing`() {
        val sections = build(query = "gemini", lists = lists + ("a" to ModelList.Loading))
        assertEquals(listOf("a", "r"), sections.map { it.key })
        assertEquals(ModelList.Loading, sections.first().list)
    }

    @Test
    fun `exactly the chat's model is ticked, wherever it appears`() {
        val sections = build(favorites = listOf(ModelRef("a", "claude-opus-5-5")))
        val ticked = sections.flatMap { s -> s.rows.filter { it.selected }.map { s.key to it.id } }
        assertEquals(listOf(FAVORITES_KEY to "claude-opus-5-5", "a" to "claude-opus-5-5"), ticked)
    }

    @Test
    fun `rows use the provider's display name and caps, then the catalogue's`() {
        val catalog = { _: ChatProvider, id: String ->
            when (id) {
                "claude-opus-5-5" -> CatalogModel("Catalogue Opus", 200_000, vision = true, tools = true, reasoning = true)
                "google/gemini-3-pro" -> CatalogModel("Gemini 3 Pro", 1_048_576, vision = true, tools = true)
                else -> null
            }
        }
        val rows = build(catalog = catalog).flatMap { it.rows }.associateBy { it.ref }
        val opus = rows.getValue(ModelRef("a", "claude-opus-5-5"))
        assertEquals("Claude Opus 5.5", opus.title)
        // The provider's window wins over the catalogue's.
        assertEquals(ModelCapabilities(Support.YES, Support.YES, Support.YES, Support.UNKNOWN, 1_000_000, CacheStyle.EXPLICIT), opus.caps)
        assertEquals(listOf("1M", "Tools", "Vision", "Reasoning"), capabilityChips(opus.caps))
        val gemini = rows.getValue(ModelRef("r", "google/gemini-3-pro"))
        assertEquals("Gemini 3 Pro", gemini.title)
        // The catalogue doesn't say whether it reasons; the built-in rule for Gemini 3 does.
        assertEquals(ModelCapabilities(Support.YES, Support.YES, Support.YES, Support.UNKNOWN, 1_048_576), gemini.caps)
        assertEquals("openai/gpt-5", rows.getValue(ModelRef("r", "openai/gpt-5")).title)
    }

    @Test
    fun `a long list is capped but keeps the chat's model`() {
        val many = (1..10).map { RemoteModel("m$it") } + RemoteModel("claude-zzz")
        val sections = buildModelPicker(providers, mapOf("a" to ModelList.Loaded(many)), "a", ChatControls(model = "claude-zzz"), emptyList(), emptyList(), perProvider = 4)
        val a = sections.first { it.key == "a" }
        assertEquals(listOf("claude-opus-5-5", "m1", "m2", "m3", "claude-zzz"), a.rows.map { it.id })
        assertEquals(7, a.more)
    }

    @Test
    fun `context labels read like the providers write them`() {
        assertEquals("200K", contextLabel(200_000))
        assertEquals("1M", contextLabel(1_048_576))
        assertEquals("1.5M", contextLabel(1_500_000))
        assertEquals("8K", contextLabel(8_192))
    }
}
