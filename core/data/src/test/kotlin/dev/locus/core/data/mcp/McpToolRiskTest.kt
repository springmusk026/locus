package dev.locus.core.data.mcp

import dev.locus.core.data.mcp.McpToolRisk.ADDITIVE
import dev.locus.core.data.mcp.McpToolRisk.DESTRUCTIVE
import dev.locus.core.data.mcp.McpToolRisk.MISSING
import dev.locus.core.data.mcp.McpToolRisk.READ
import dev.locus.core.data.mcp.McpToolRisk.UNKNOWN
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How MCP tool annotations (spec 2025-03-26+) become approvals. */
class McpToolRiskTest {
    private fun hints(readOnly: Boolean? = null, destructive: Boolean? = null) =
        ToolAnnotations(readOnlyHint = readOnly, destructiveHint = destructive)

    @Test
    fun `read-only tools run without asking and count as observations`() {
        val risk = McpToolRisk.of("search_docs", hints(readOnly = true), trusted = false)
        assertEquals(READ, risk)
        assertTrue(risk.isObservation)
        assertNull(risk.confirmReason("docs"))
    }

    @Test
    fun `no hints follow the spec defaults - not read-only and possibly destructive`() {
        assertEquals(UNKNOWN, McpToolRisk.of("list_things", null, trusted = true))
        assertEquals(UNKNOWN, McpToolRisk.of("list_things", ToolAnnotations(), trusted = true))
        assertNotNull(UNKNOWN.confirmReason("x"))
        assertFalse(UNKNOWN.isObservation)
    }

    @Test
    fun `destructiveHint false means additive, true means destructive`() {
        assertEquals(ADDITIVE, McpToolRisk.of("create_issue", hints(readOnly = false, destructive = false), trusted = true))
        assertEquals(DESTRUCTIVE, McpToolRisk.of("update_issue", hints(readOnly = false, destructive = true), trusted = true))
        assertEquals(DESTRUCTIVE, McpToolRisk.of("update_issue", hints(destructive = true), trusted = false))
        assertTrue("adds or changes" in ADDITIVE.confirmReason("linear")!!)
    }

    @Test
    fun `a deleting name is destructive whatever the hints say, on any server`() {
        listOf("delete_repository", "dropTable", "remove-member", "projects.destroy", "purge_cache").forEach { name ->
            assertEquals(DESTRUCTIVE, McpToolRisk.of(name, hints(readOnly = true, destructive = false), trusted = true), name)
        }
    }

    @Test
    fun `contradicting hints get the safer reading`() {
        assertEquals(DESTRUCTIVE, McpToolRisk.of("sync", hints(readOnly = true, destructive = true), trusted = true))
    }

    @Test
    fun `an unknown server's read-only claim on a writing tool is not trusted`() {
        assertEquals(UNKNOWN, McpToolRisk.of("send_email", hints(readOnly = true), trusted = false))
        assertEquals(UNKNOWN, McpToolRisk.of("createPullRequest", hints(readOnly = true), trusted = false))
        // The vendor's own server is believed.
        assertEquals(READ, McpToolRisk.of("execute_sql", hints(readOnly = true), trusted = true))
    }

    @Test
    fun `a tool the server doesn't list asks first`() {
        assertEquals(MISSING, McpToolRisk.of("whatever", hints(readOnly = true), trusted = true, found = false))
        assertNotNull(MISSING.confirmReason("x"))
    }

    @Test
    fun `names split into words across conventions`() {
        assertEquals(listOf("get", "pull", "request", "files"), McpToolRisk.words("getPullRequest_files"))
        assertEquals(listOf("resolve", "library", "id"), McpToolRisk.words("resolve-library-id"))
        assertEquals(listOf("projects", "list"), McpToolRisk.words("projects.list"))
    }
}
