package dev.locus.feature.settings

import dev.locus.core.agent.integrations.Integrations
import dev.locus.core.designsystem.integrationLogo
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IntegrationIconsTest {
    /** Integrations without a brand mark; IntegrationIcon draws a plain icon for these. */
    private val fallbacks = setOf("email")

    @Test
    fun `every integration has a logo or an allowed fallback`() {
        val missing = Integrations.all.map { it.id }.filter { integrationLogo(it) == null && it !in fallbacks }
        assertEquals(emptyList(), missing, "Add a logo in ProviderLogos.kt or a fallback icon in IntegrationIcon")
    }

    @Test
    fun `fallbacks are only for integrations without a logo`() {
        val ids = Integrations.all.map { it.id }.toSet()
        fallbacks.forEach { id ->
            assertTrue(id in ids, "$id is no longer an integration")
            assertEquals(null, integrationLogo(id), "$id has a logo now; drop it from the fallbacks")
        }
    }
}
