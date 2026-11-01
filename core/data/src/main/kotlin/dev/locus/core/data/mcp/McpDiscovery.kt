package dev.locus.core.data.mcp

import dev.locus.core.agent.DiscoverableTools
import dev.locus.core.agent.home.AgentHome
import dev.locus.core.model.DiscoveryTools
import dev.locus.core.model.ToolSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The tools of the user's MCP servers, for find_tools and call_tool in a chat with tool discovery
 * (docs/design/tool-discovery.md). Each is offered as `mcp__<connector>__<tool>` with its compact schema
 * and runs as the `mcp_call` it maps to, so mcp_call's own risk, confirmation, schema check and taint apply.
 */
class McpDiscovery(
    /** The connectors' tool lists (cached per connector), by connector id. */
    private val tools: suspend (String) -> List<io.modelcontextprotocol.kotlin.sdk.types.Tool>,
    private val trusted: (String) -> Boolean,
    private val servers: () -> List<Server>,
    /** A connector's tool list if it was already fetched; never asks the server. */
    private val alreadyListed: (String) -> List<io.modelcontextprotocol.kotlin.sdk.types.Tool>? = { null },
) : DiscoverableTools {
    constructor(clients: McpClients, home: AgentHome) : this(
        tools = { id -> clients.tools(id) },
        trusted = clients::trusted,
        servers = { home.connectors().filter { it.kind == "mcp" }.map { Server(it.id, it.name) } },
        alreadyListed = clients::cachedTools,
    )

    /**
     * The tools of servers Locus has already listed, as real tools with their own names and schemas for
     * provider-native search. Servers not listed yet are still found through find_tools.
     */
    override fun cached(): List<ToolSpec> = runCatching { servers() }.getOrDefault(emptyList()).flatMap { server ->
        val trustedServer = trusted(server.id)
        alreadyListed(server.id).orEmpty().map { t -> spec(server, t, trustedServer) }
    }

    private fun spec(server: Server, t: io.modelcontextprotocol.kotlin.sdk.types.Tool, trustedServer: Boolean): ToolSpec {
        val kind = McpToolRisk.of(t.name, t.annotations, trustedServer).label?.let { " [$it]" }.orEmpty()
        return ToolSpec(DiscoveryTools.mcpName(server.id, t.name), "${server.name}: ${McpSchemas.description(t)}$kind", McpSchemas.compact(t))
    }

    data class Server(val id: String, val name: String)

    override suspend fun list(): DiscoverableTools.Listing {
        val problems = mutableListOf<String>()
        val found = runCatching { servers() }.getOrDefault(emptyList()).flatMap { server ->
            val listed = try {
                withTimeout(LIST_TIMEOUT_MS) { tools(server.id) }
            } catch (e: TimeoutCancellationException) {
                problems += "“${server.id}” (it took too long to list its tools)"
                emptyList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problems += "“${server.id}” (${e.message ?: "couldn't connect"})"
                emptyList()
            }
            val trustedServer = trusted(server.id)
            listed.map { t -> spec(server, t, trustedServer) }
        }
        return DiscoverableTools.Listing(found, problems, source = "the user's MCP servers")
    }

    override fun route(name: String, arguments: JsonObject): Pair<String, JsonObject>? {
        val (connector, tool) = DiscoveryTools.parseMcpName(name) ?: return null
        return "mcp_call" to buildJsonObject {
            put("connector", connector)
            put("tool", tool)
            put("arguments", arguments)
        }
    }

    private companion object {
        /** A server that takes longer than this to list its tools is left out of that search, and named. */
        const val LIST_TIMEOUT_MS = 15_000L
    }
}
