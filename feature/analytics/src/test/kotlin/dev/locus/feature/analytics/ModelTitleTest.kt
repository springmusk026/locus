package dev.locus.feature.analytics

import dev.locus.core.data.repository.ModelList
import dev.locus.core.data.repository.ModelUsage
import dev.locus.core.llm.RemoteModel
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelTitleTest {
    private fun usage(model: String, providerId: String? = "p1") =
        ModelUsage(providerId, "My proxy", "https://proxy.example/v1", model, onDevice = false, tokens = 1, requests = 1)

    @Test
    fun `uses the name the provider gives the model, as the picker does`() {
        val listed = mapOf("p1" to ModelList.Loaded(listOf(RemoteModel("gemini-3.8-flash-high", "Gemini 3.8 Flash (High)"))))
        assertEquals("Gemini 3.8 Flash (High)", modelTitle(usage("gemini-3.8-flash-high"), null, listed))
    }

    @Test
    fun `falls back to the id when nothing names it`() {
        val listed = mapOf("p1" to ModelList.Loaded(listOf(RemoteModel("raw-model"))))
        assertEquals("raw-model", modelTitle(usage("raw-model"), null, listed))
        assertEquals("raw-model", modelTitle(usage("raw-model", providerId = null), null, emptyMap()))
    }

    @Test
    fun `a row without a model shows the provider`() {
        assertEquals("My proxy", modelTitle(usage(""), null, emptyMap()))
    }
}
