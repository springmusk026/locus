package dev.locus.core.data.mcp

import dev.locus.core.agent.ObjectSchema
import dev.locus.core.agent.Tool
import dev.locus.core.agent.ToolException
import dev.locus.core.agent.ToolResult
import dev.locus.core.agent.UntrustedContent
import dev.locus.core.agent.home.AgentHome
import dev.locus.core.agent.home.Connector
import dev.locus.core.agent.home.SecretResolver
import dev.locus.core.agent.str
import dev.locus.core.model.ToolPolicy
import dev.locus.core.model.ToolRisk
import dev.locus.core.model.ToolSpec
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool as McpTool
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** One MCP client per connector, connected on first use and reused. */
@Singleton
class McpClients @Inject constructor(
    private val home: AgentHome,
    private val secrets: SecretResolver,
    private val signIn: McpSignIn,
    /** Local (stdio) servers, run in Linux; the same two tools reach them. */
    val local: LocalMcpServers,
) {
    // Separate from the app's main client: the MCP transport needs Ktor's SSE plugin.
    private val http = HttpClient(OkHttp) {
        install(SSE)
        engine { config { readTimeout(5, TimeUnit.MINUTES) } }
    }
    private val clients = mutableMapOf<String, Client>()
    /** Each connector's last tool list, so approvals don't ask the server again on every call. */
    private val toolLists = ConcurrentHashMap<String, List<McpTool>>()
    /** The OAuth access token each cached client was built with, to notice refreshes. */
    private val tokens = mutableMapOf<String, String>()
    private val lock = Mutex()

    private val _health = MutableStateFlow(RemoteMcpHealth())

    /**
     * What Locus last saw of each remote server in this run: whether connecting and listing tools
     * worked. Used to pick, when a chat is created, whether to rely on a vendor's server (see
     * [McpPreference]). Local servers report through [LocalMcpServers.health] instead.
     */
    val health: StateFlow<RemoteMcpHealth> = _health

    private fun noteHealth(id: String, failure: Throwable?) {
        if (failure is kotlinx.coroutines.CancellationException) return
        _health.update { h ->
            if (failure == null) h.copy(working = h.working + id, failing = h.failing - id)
            else h.copy(working = h.working - id, failing = h.failing + (id to (failure.message ?: "Couldn't connect.")))
        }
    }

    /**
     * Runs [block] with the connector's client. For OAuth connectors a failure is retried once on a
     * fresh connection with a refreshed token, since a cached connection keeps its old token.
     * A local server is started on demand; [idempotent] calls (listing tools) are retried once on a
     * fresh process if it crashes mid-call.
     */
    suspend fun <T> use(id: String, idempotent: Boolean = false, block: suspend (Client) -> T): T {
        home.connector(id)?.takeIf { it.isLocalMcp }?.let { return local.use(it, idempotent, block) }
        // A server that can't be reached or refuses its credentials counts as failing until it answers again.
        val (connector, client) = runCatching { client(id) }.onFailure { noteHealth(id, it) }.getOrThrow()
        return try {
            block(client)
        } catch (e: Exception) {
            // Only auth failures: retrying after any other error could run a write twice.
            if (e is kotlinx.coroutines.CancellationException || connector.auth.type != "oauth" || !isAuthFailure(e)) throw e
            forget(id)
            signIn.forceRefresh(connector.auth.secret) ?: throw ToolException("Sign-in for “$id” expired. Ask the user to sign in again under Library → Connectors.")
            block(client(id).second)
        }
    }

    suspend fun client(id: String): Pair<Connector, Client> = lock.withLock {
        val connector = home.connector(id)?.takeIf { it.kind == "mcp" && it.local == null } ?: throw ToolException("No active MCP connector “$id”.")
        // Refreshes a token that's about to expire; a new token means a new connection.
        val token = if (connector.auth.type == "oauth") oauthToken(connector) else null
        clients[id]?.let { cached ->
            if (token == null || tokens[id] == token) return@withLock connector to cached
            clients.remove(id)?.let { runCatching { it.close() } }
        }
        val client = try {
            connect(connector, token)
        } catch (e: Exception) {
            // An OAuth token the server no longer accepts: refresh once and retry.
            val refreshed = (if (connector.auth.type == "oauth") signIn.forceRefresh(connector.auth.secret) else null)
                ?: throw ToolException("Could not connect to ${connector.host}: ${e.message}")
            runCatching { connect(connector, refreshed) }.getOrElse { throw ToolException("Could not connect to ${connector.host}: ${it.message}") }
                .also { tokens[id] = refreshed }
        }
        clients[id] = client
        if (token != null && id !in tokens) tokens[id] = token
        connector to client
    }

    private fun isAuthFailure(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.any { t -> t.message.orEmpty().let { "401" in it || "nauthorized" in it || "invalid_token" in it } }

    private suspend fun oauthToken(connector: Connector): String =
        runCatching { signIn.accessToken(connector.auth.secret) }.getOrNull()
            ?: throw ToolException("“${connector.id}” needs the user to sign in. Ask them to tap Sign in under Library → Connectors.")

    private suspend fun connect(connector: Connector, oauthToken: String?): Client {
        val secret = when (connector.auth.type) {
            "none" -> null
            "oauth" -> oauthToken
            else -> secrets.resolve(connector.auth.secret)
                ?: throw ToolException("“${connector.id}” needs its key. Ask the user to add it in Library → Connectors.")
        }
        val transport = StreamableHttpClientTransport(http, connector.baseUrl) {
            when (connector.auth.type) {
                "bearer", "oauth" -> header("Authorization", "Bearer $secret")
                "header" -> header(connector.auth.name, secret)
                "query" -> parameter(connector.auth.name, secret)
            }
        }
        val client = Client(Implementation(name = "locus", version = "0.1.0"))
        client.connect(transport)
        return client
    }

    /** The connector's tools, fetched once and kept until [forget] or a [refresh]. */
    suspend fun tools(id: String, refresh: Boolean = false): List<McpTool> {
        if (!refresh) toolLists[id]?.let { return it }
        return runCatching { use(id, idempotent = true) { it.listTools().tools } }
            .also { noteHealth(id, it.exceptionOrNull()) }
            .getOrThrow()
            .also { toolLists[id] = it }
    }

    /** Lists [id]'s tools afresh to see whether it works now; the outcome lands in [health]. */
    suspend fun check(id: String): Boolean = runCatching { tools(id, refresh = true) }
        .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        .isSuccess

    /**
     * Whether [id] is a vendor's own server from the template catalogue ([McpTemplates.official]),
     * so its tool annotations are trusted.
     */
    fun trusted(id: String): Boolean = home.connector(id)?.let { McpTemplates.official(it) != null } ?: false

    /** Drops a cached connection (or stops a local server), e.g. after the connector or its key changed. */
    suspend fun forget(id: String) {
        toolLists.remove(id)
        // Its settings changed: what we saw of it before no longer says anything.
        _health.update { h -> h.copy(working = h.working - id, failing = h.failing - id, generation = h.generation + 1) }
        lock.withLock {
            tokens.remove(id)
            clients.remove(id)?.let { runCatching { it.close() } }
        }
        local.stop(id, "Stopped: its settings changed")
    }
}

/**
 * Remote MCP servers that answered (listed their tools) and ones whose last attempt failed, with why,
 * since Locus started. [generation] moves when a connector changes, so screens re-read it.
 */
data class RemoteMcpHealth(
    val working: Set<String> = emptySet(),
    val failing: Map<String, String> = emptyMap(),
    val generation: Int = 0,
)

fun mcpTools(clients: McpClients): List<Tool> = listOf(McpListTools(clients), McpCall(clients))

private class McpListTools(private val clients: McpClients) : Tool {
    override val risk = ToolRisk.READ
    override val spec = ToolSpec(
        "mcp_list_tools",
        "List the tools an MCP connector offers, with their arguments. Call before mcp_call.",
        ObjectSchema().string("connector", "MCP connector id from <available_connectors>.").build(),
    )

    override fun describe(args: JsonObject) = "List ${args.str("connector")} tools"

    override suspend fun execute(args: JsonObject): ToolResult {
        val id = args.str("connector").orEmpty()
        val tools = clients.tools(id, refresh = true)
        if (tools.isEmpty()) return ToolResult("This server offers no tools.")
        val trusted = clients.trusted(id)
        return ToolResult(
            tools.joinToString("\n\n") { t ->
                val props = t.inputSchema.properties?.let { p -> p.entries.joinToString { (k, v) -> "$k: ${schemaType(v)}" } }.orEmpty()
                val req = t.inputSchema.required.orEmpty().joinToString()
                val kind = McpToolRisk.of(t.name, t.annotations, trusted).label?.let { " [$it]" }.orEmpty()
                "${t.name}$kind — ${t.description.orEmpty().take(400)}\n  args: ${props.ifEmpty { "none" }}" + if (req.isNotEmpty()) "\n  required: $req" else ""
            },
        )
    }

    private fun schemaType(v: JsonElement) = (v as? JsonObject)?.get("type")?.toString()?.trim('"') ?: "any"
}

private class McpCall(private val clients: McpClients) : Tool {
    override val risk = ToolRisk.NETWORK
    override val defaultPolicy = ToolPolicy.ALLOW
    override val spec = ToolSpec(
        "mcp_call",
        "Call a tool on an MCP connector. Tools the server doesn't mark read-only ask the user first.",
        ObjectSchema()
            .string("connector", "MCP connector id.")
            .string("tool", "Tool name from mcp_list_tools.")
            .obj("arguments", "Tool arguments.")
            .build(),
    )

    override fun describe(args: JsonObject) = "${args.str("connector")} → ${args.str("tool")}"

    override suspend fun isObservation(args: JsonObject): Boolean = risk(args).isObservation

    override suspend fun mustConfirm(args: JsonObject): String? = risk(args).confirmReason(args.str("connector").orEmpty())

    /** From the cached tool list; a server that can't be reached counts as not listing the tool. */
    private suspend fun risk(args: JsonObject): McpToolRisk {
        val id = args.str("connector").orEmpty()
        val name = args.str("tool").orEmpty()
        // A tool missing from the cached list may be new: look once more before calling it missing.
        val tool = runCatching { clients.tools(id) }.getOrNull()?.firstOrNull { it.name == name }
            ?: runCatching { clients.tools(id, refresh = true) }.getOrNull()?.firstOrNull { it.name == name }
        return McpToolRisk.of(name, tool?.annotations, clients.trusted(id), found = tool != null)
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val arguments = (args["arguments"] as? JsonObject).orEmpty()
        val result = clients.use(args.str("connector").orEmpty()) { it.callTool(args.str("tool").orEmpty(), arguments.mapValues { v -> v.value }) }
        val text = result.content.joinToString("\n") { block -> (block as? TextContent)?.text ?: "[${block::class.simpleName}]" }
            .ifBlank { result.structuredContent?.toString().orEmpty() }
        // What an MCP server returns is written by the service and the people using it (issues, pages,
        // messages), so it's marked like web pages (see UntrustedContent).
        val server = args.str("connector").orEmpty()
        return ToolResult(
            UntrustedContent.wrap(text.take(40_000).ifBlank { "(no content)" }, UntrustedContent.note("from the MCP server “$server”")),
            isError = result.isError == true,
        )
    }

    private fun JsonObject?.orEmpty() = this ?: JsonObject(emptyMap())
}
