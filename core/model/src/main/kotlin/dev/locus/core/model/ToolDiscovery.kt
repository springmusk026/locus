package dev.locus.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Names shared by the agent, the adapters and the UI for tool discovery (docs/design/tool-discovery.md).
 * A chat created with discovery declares a small core set of tools plus [FIND_TOOLS] and [CALL_TOOL];
 * the model finds the rest with [FIND_TOOLS] and runs one with [CALL_TOOL], which the agent loop
 * unwraps into the named tool's own call before any policy is checked.
 */
object DiscoveryTools {
    const val FIND_TOOLS = "find_tools"
    const val CALL_TOOL = "call_tool"

    /** The version of the core set every new chat freezes ([Conversation.toolDiscovery]). */
    const val CURRENT = "v1"

    /** Names of tools an MCP server offers through discovery: `mcp__<connector>__<tool>`. */
    const val MCP_PREFIX = "mcp__"

    fun mcpName(connector: String, tool: String) = "$MCP_PREFIX$connector$MCP_SEPARATOR$tool"

    /** The connector and tool of an [mcpName], or null for any other name. */
    fun parseMcpName(name: String): Pair<String, String>? {
        if (!name.startsWith(MCP_PREFIX)) return null
        val rest = name.removePrefix(MCP_PREFIX)
        val at = rest.indexOf(MCP_SEPARATOR)
        if (at <= 0 || at + MCP_SEPARATOR.length >= rest.length) return null
        return rest.substring(0, at) to rest.substring(at + MCP_SEPARATOR.length)
    }

    private const val MCP_SEPARATOR = "__"

    /**
     * The tool and arguments a call really runs: [CALL_TOOL]'s `name` and `arguments` (an object, or a
     * JSON string holding one), anything else as it is. For labels and the activity log; the agent loop
     * resolves calls itself, with errors the model can act on.
     */
    fun unwrap(name: String, argumentsJson: String): Pair<String, String> {
        if (name != CALL_TOOL) return name to argumentsJson
        val args = runCatching { Json.parseToJsonElement(argumentsJson) as JsonObject }.getOrNull() ?: return name to argumentsJson
        val inner = (args["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return name to argumentsJson
        val innerArgs = when (val a = args["arguments"]) {
            is JsonObject -> a.toString()
            is JsonPrimitive -> a.contentOrNull?.takeIf { it.trimStart().startsWith("{") } ?: "{}"
            else -> "{}"
        }
        return inner to innerArgs
    }
}
