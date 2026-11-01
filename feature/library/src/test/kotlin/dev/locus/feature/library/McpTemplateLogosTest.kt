package dev.locus.feature.library

import dev.locus.core.data.mcp.McpTemplate
import dev.locus.core.data.mcp.McpTemplates
import dev.locus.core.designsystem.CONNECTOR_PLAIN_ICONS
import dev.locus.core.designsystem.connectorLogo
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpTemplateLogosTest {
    @Test
    fun `every template has a logo or an allowed plain icon`() {
        val missing = McpTemplates.all.map { it.id }.filter { connectorLogo(it) == null && it !in CONNECTOR_PLAIN_ICONS }
        assertEquals(emptyList(), missing, "Add a logo in ProviderLogos.kt (and NOTICE.md) or a plain icon")
    }

    @Test
    fun `plain icons are only for ids without a logo`() {
        CONNECTOR_PLAIN_ICONS.forEach { assertNull(connectorLogo(it), it) }
    }

    @Test
    fun `the picker shows every template once, grouped in category order`() {
        val groups = templateGroups()
        assertEquals(McpTemplates.all.map { it.id }.sorted(), groups.flatMap { (_, ts) -> ts.map { it.id } }.sorted())
        assertEquals(groups.map { it.first }, groups.map { it.first }.sortedBy { it.ordinal })
        assertTrue(groups.all { (c, ts) -> ts.all { it.category == c } })
    }

    @Test
    fun `the form needs a key only when the server can't do without one`() {
        val render = TemplateForm(McpTemplates.byId("render")!!)
        assertEquals(false, render.isValid)
        render.key = "rnd_x"
        assertEquals(true, render.isValid)
        assertEquals(true, TemplateForm(McpTemplates.byId("linear")!!).isValid) // signs in instead
        assertEquals(true, TemplateForm(McpTemplates.byId("deepwiki")!!).isValid)
        val http = TemplateForm(McpTemplates.byId("deepwiki")!!).apply { url = "http://mcp.deepwiki.com/mcp" }
        assertEquals(false, http.isValid)
        val local = TemplateForm(McpTemplates.byId("brave-local")!!)
        assertEquals(false, local.isValid)
        local.env = mapOf("BRAVE_API_KEY" to "k")
        assertEquals(true, local.isValid)
        assertEquals(McpTemplate.Category.SEARCH, local.template.category)
    }
}
