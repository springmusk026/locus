package dev.locus.core.data.mcp

import dev.locus.core.agent.ObjectSchema
import dev.locus.core.agent.Tool
import dev.locus.core.agent.ToolException
import dev.locus.core.agent.ToolResult
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
    /** The OAuth access token each cached client was built with, to notice refreshes. */
    private val tokens = mutableMapOf<String, String>()
    private val lock = Mutex()

    /**
     * Runs [block] with the connector's client. For OAuth connectors a failure is retried once on a
     * fresh connection with a refreshed token, since a cached connection keeps its old token.
     * A local server is started on demand; [idempotent] calls (listing tools) are retried once on a
     * fresh process if it crashes mid-call.
     */
    suspend fun <T> use(id: String, idempotent: Boolean = false, block: suspend (Client) -> T): T {
        home.connector(id)?.takeIf { it.isLocalMcp }?.let { return local.use(it, idempotent, block) }
        val (connector, client) = client(id)
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

    /** Drops a cached connection (or stops a local server), e.g. after the connector or its key changed. */
    suspend fun forget(id: String) {
        lock.withLock {
            tokens.remove(id)
            clients.remove(id)?.let { runCatching { it.close() } }
        }
        local.stop(id, "Stopped: its settings changed")
    }
}

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
        val tools = clients.use(args.str("connector").orEmpty(), idempotent = true) { it.listTools().tools }
        if (tools.isEmpty()) return ToolResult("This server offers no tools.")
        return ToolResult(
            tools.joinToString("\n\n") { t ->
                val props = t.inputSchema.properties?.let { p -> p.entries.joinToString { (k, v) -> "$k: ${schemaType(v)}" } }.orEmpty()
                val req = t.inputSchema.required.orEmpty().joinToString()
                val kind = when {
                    t.annotations?.readOnlyHint == true -> " [read-only]"
                    t.annotations?.destructiveHint == true -> " [destructive]"
                    else -> ""
                }
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

    override suspend fun isObservation(args: JsonObject): Boolean {
        return runCatching {
            clients.use(args.str("connector").orEmpty(), idempotent = true) { c -> c.listTools().tools.firstOrNull { it.name == args.str("tool") }?.annotations?.readOnlyHint == true }
        }.getOrDefault(false)
    }

    override suspend fun mustConfirm(args: JsonObject): String? {
        val tool = runCatching { clients.use(args.str("connector").orEmpty(), idempotent = true) { c -> c.listTools().tools.firstOrNull { it.name == args.str("tool") } } }.getOrNull()
        return when {
            tool?.annotations?.readOnlyHint == true -> null
            tool?.annotations?.destructiveHint == true -> "The server marks this tool as destructive."
            else -> "This tool may change something in ${args.str("connector")}."
        }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val arguments = (args["arguments"] as? JsonObject).orEmpty()
        val result = clients.use(args.str("connector").orEmpty()) { it.callTool(args.str("tool").orEmpty(), arguments.mapValues { v -> v.value }) }
        val text = result.content.joinToString("\n") { block -> (block as? TextContent)?.text ?: "[${block::class.simpleName}]" }
            .ifBlank { result.structuredContent?.toString().orEmpty() }
        return ToolResult(text.take(40_000).ifBlank { "(no content)" }, isError = result.isError == true)
    }

    private fun JsonObject?.orEmpty() = this ?: JsonObject(emptyMap())
}
