package dev.locus.core.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ModelChoiceTest {
    private val provider = ProviderConfig("p", "P", ApiFormat.OPENAI_CHAT, "https://x", model = "default-1")

    @Test
    fun `effective model is the trimmed override, else the default`() {
        assertEquals("default-1", provider.effectiveModel(null))
        assertEquals("default-1", provider.effectiveModel("  "))
        assertEquals("other", provider.effectiveModel(" other "))
    }

    @Test
    fun `picking the default stores no override`() {
        assertNull(provider.overrideFor("default-1"))
        assertNull(provider.overrideFor(" default-1 "))
        assertNull(provider.overrideFor(null))
        assertEquals("other", provider.overrideFor(" other"))
    }

    private val a = ModelRef("p", "a")
    private val b = ModelRef("p", "b")
    private val c = ModelRef("q", "a")

    @Test
    fun `favourites toggle and keep the order they were starred in`() {
        val starred = ModelRefs.toggle(ModelRefs.toggle(emptyList(), b), a)
        assertEquals(listOf(b, a), starred)
        assertEquals(listOf(a), ModelRefs.toggle(starred, b))
    }

    @Test
    fun `recents put the latest first, once, and keep a few`() {
        var recent = emptyList<ModelRef>()
        listOf(a, b, c, a).forEach { recent = ModelRefs.pushRecent(recent, it) }
        assertEquals(listOf(a, c, b), recent)
        assertEquals(listOf(a, c), ModelRefs.pushRecent(recent, a, max = 2))
    }

    @Test
    fun `settings saved before favourites existed still load`() {
        val old = Json { ignoreUnknownKeys = true }.decodeFromString(AppSettings.serializer(), """{"providers":[],"maxStepsPerTurn":40}""")
        assertEquals(emptyList(), old.favoriteModels)
        assertEquals(emptyList(), old.recentModels)
        assertFalse(old.modelCatalog)
    }
}
