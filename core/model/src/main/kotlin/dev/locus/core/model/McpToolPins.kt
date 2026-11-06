package dev.locus.core.model

import kotlinx.serialization.Serializable

/**
 * One MCP tool as the user approved it (docs/design/mcp-connectors.md, "Tool pinning"): the SHA-256 of
 * its name, description, input schema and annotations, plus the description and schema themselves so a
 * later change can be shown as a diff. [hash] empty means "needs review": the pins failed their seal
 * check and nothing about this tool is trusted until the user looks again.
 */
@Serializable
data class McpToolPin(
    val hash: String,
    /** The description as the server sent it (hidden characters included; they're stripped for display). */
    val description: String = "",
    /** The input schema as canonical JSON (keys sorted), pretty-printed when shown. */
    val schema: String = "",
    /**
     * When the user approved this version (epoch ms). Chats created before then don't get it: a chat's
     * tools never change under it (docs/design/tool-discovery.md, "Frozen per chat"). 0 for the versions
     * pinned on first connect, which every chat may use.
     */
    val approvedAt: Long = 0L,
)

/**
 * The approved definition of every tool of every MCP connector (by connector id, then tool name).
 * Sealed with the tool permissions ([PolicySeal]): a change made outside Locus can't approve a tool.
 */
@Serializable
data class McpToolPins(
    val servers: Map<String, Map<String, McpToolPin>> = emptyMap(),
    /**
     * Set when the pins failed their seal check. A connector with no pins is then not trusted on first
     * use either: whatever was removed outside Locus must be approved again.
     */
    val noFirstUse: Boolean = false,
) {
    val isEmpty: Boolean get() = servers.isEmpty() && !noFirstUse

    /** Every pin kept for review only: what's left of pins that failed their seal check. */
    fun forReview(): McpToolPins = McpToolPins(
        servers = servers.mapValues { (_, tools) -> tools.mapValues { (_, pin) -> pin.copy(hash = "") } },
        noFirstUse = true,
    )
}
