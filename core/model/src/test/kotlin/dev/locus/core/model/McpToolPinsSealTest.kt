package dev.locus.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** MCP tool pins are sealed with the permissions (docs/design/mcp-connectors.md, "Tool pinning"). */
class McpToolPinsSealTest {
    private val pins = McpToolPins(
        servers = mapOf("linear" to mapOf("create_issue" to McpToolPin("sha256:aa", "Create an issue.", "{}", approvedAt = 0))),
    )

    @Test
    fun `pins are sealed, and approving a tool from outside Locus breaks the seal`() {
        val key = TestKey()
        val s = AppSettings(mcpToolPins = pins)
        assertFalse(PolicySeal.isDefault(s))
        val sealed = PolicySeal.sealed(s, key)
        assertEquals(SealCheck.VALID, PolicySeal.check(sealed, key))
        val planted = pins.copy(servers = pins.servers + ("linear" to pins.servers.getValue("linear") + ("exfil" to McpToolPin("sha256:bb"))))
        assertEquals(SealCheck.TAMPERED, PolicySeal.check(sealed.copy(mcpToolPins = planted), key))
        val changedHash = pins.copy(servers = mapOf("linear" to mapOf("create_issue" to McpToolPin("sha256:cc", "Create an issue.", "{}"))))
        assertEquals(SealCheck.TAMPERED, PolicySeal.check(sealed.copy(mcpToolPins = changedHash), key))
        // Removing a server's pins (to get a fresh "first use") breaks it too.
        assertEquals(SealCheck.TAMPERED, PolicySeal.check(sealed.copy(mcpToolPins = McpToolPins(servers = mapOf("other" to emptyMap()))), key))
        // The kept text is for the diff only: it isn't sealed.
        assertEquals(SealCheck.VALID, PolicySeal.check(sealed.copy(mcpToolPins = McpToolPins(mapOf("linear" to mapOf("create_issue" to McpToolPin("sha256:aa", "edited", "{}"))))), key))
    }

    @Test
    fun `a broken seal leaves every tool for review and turns first use off`() {
        val restricted = PolicySeal.restricted(AppSettings(mcpToolPins = pins))
        val kept = restricted.mcpToolPins
        assertTrue(kept.noFirstUse)
        assertEquals("", kept.servers.getValue("linear").getValue("create_issue").hash)
        assertEquals("Create an issue.", kept.servers.getValue("linear").getValue("create_issue").description)
    }

    @Test
    fun `settings without pins seal as before`() {
        val key = TestKey()
        val s = AppSettings(toolPolicies = mapOf("x" to ToolPolicy.ASK))
        assertEquals(PolicySeal.seal(s, key), PolicySeal.seal(s.copy(mcpToolPins = McpToolPins()), key))
        assertTrue(PolicySeal.isDefault(AppSettings()))
    }
}
