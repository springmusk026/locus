package dev.locus.feature.library

import dev.locus.core.agent.integrations.Integrations
import dev.locus.core.data.mcp.McpTemplates
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The template sheet's "Use the key saved for …" choice: off by default, says where the key goes, and holds no key. */
class SavedKeyFormTest {
    @Test
    fun `off until tapped, then valid without a pasted key while the host is the template's`() {
        val form = TemplateForm(McpTemplates.byId("render")!!)
        assertFalse(form.useSavedKey)
        assertFalse(form.isValid) // Render needs a key
        form.useSavedKey = true
        assertTrue(form.isValid)
        assertEquals("", form.key)
        form.url = "https://mcp.render.com/mcp?x=1"
        assertTrue(form.isValid)
        form.url = "https://elsewhere.example/mcp"
        assertTrue(form.savedKeyBlocked)
        assertFalse(form.isValid)
    }

    @Test
    fun `the confirmation names the host and how the key is sent`() {
        val github = McpTemplates.byId("github")!!
        val note = savedKeyNote(Integrations.github, github)
        assertTrue("GitHub key will also be sent to api.githubcopilot.com" in note, note)
        assertTrue("a bearer token" in note)
        val exa = savedKeyNote(Integrations.exa, McpTemplates.byId("exa")!!)
        assertTrue("mcp.exa.ai, as the x-api-key header" in exa, exa)
    }
}
