package dev.locus.core.data.agent

import dev.locus.core.agent.AgentEvent
import dev.locus.core.data.repository.resolveCapabilities
import dev.locus.core.llm.CatalogProvider
import dev.locus.core.llm.CatalogModel
import dev.locus.core.llm.ModelCapabilities
import dev.locus.core.llm.ModelCatalogIndex
import dev.locus.core.llm.RemoteModel
import dev.locus.core.llm.Support
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.ProviderConfig
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The app side of capability checks: helpers, backup notes, and what a provider's settings contribute. */
class CapabilityChecksTest {
    @Test
    fun `a helper model known not to call tools is refused, an unknown one is used`() {
        assertFalse(servesHelper(ModelCapabilities(tools = Support.NO)))
        assertTrue(servesHelper(ModelCapabilities.UNKNOWN))
        assertTrue(servesHelper(ModelCapabilities(tools = Support.YES, vision = Support.NO)))
    }

    @Test
    fun `the progress line names skipped backups and what wasn't confirmed`() {
        assertEquals("a (A) couldn't answer, switching to b (B)…", fallbackProgress(AgentEvent.FellBack("a (A)", "b (B)", "quota")))
        assertEquals(
            "a (A) couldn't answer, switching to c (C) (skipped b (B): no vision)… Not confirmed: tool calling.",
            fallbackProgress(AgentEvent.FellBack("a (A)", "c (C)", "quota", listOf("b (B): no vision"), "tool calling")),
        )
    }

    @Test
    fun `the provider's own window counts for its default model only`() {
        val provider = ProviderConfig("p", "Proxy", ApiFormat.OPENAI_CHAT, "https://proxy.example/v1", "big-model", contextWindow = 64_000)
        val catalog = ModelCatalogIndex(mapOf("x" to CatalogProvider(models = mapOf("other-model" to CatalogModel(context = 32_000, vision = false)))))
        assertEquals(64_000, resolveCapabilities(provider, "big-model", null, catalog).contextWindow)
        val other = resolveCapabilities(provider, "other-model", listOf(RemoteModel("other-model", tools = true)), catalog)
        assertEquals(32_000, other.contextWindow)
        assertEquals(Support.YES, other.tools)
        assertEquals(Support.NO, other.vision)
    }
}
