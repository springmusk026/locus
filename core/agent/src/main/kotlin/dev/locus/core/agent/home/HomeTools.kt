package dev.locus.core.agent.home

import dev.locus.core.agent.ObjectSchema
import dev.locus.core.agent.Tool
import dev.locus.core.agent.ToolException
import dev.locus.core.agent.ToolResult
import dev.locus.core.agent.str
import dev.locus.core.model.ToolPolicy
import dev.locus.core.model.ToolRisk
import dev.locus.core.model.ToolSpec
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** Evaluates JavaScript in an isolated sandbox (no network, files or secrets) and returns the result string. */
fun interface ScriptEngine {
    suspend fun eval(code: String): String
}

/** Looks up a stored credential by name. Values go straight into requests, never to the model. */
fun interface SecretResolver {
    suspend fun resolve(name: String): String?
}

fun homeTools(home: AgentHome, http: HttpClient, secrets: SecretResolver, scripts: ScriptEngine? = null): List<Tool> = listOf(
    MemorySave(home), MemoryRead(home), MemorySearch(home), MemoryForget(home),
    SkillUse(home), SkillReadFile(home), SkillCreate(home),
    ConnectorCall(home, http, secrets, scripts), ConnectorCreate(home),
)

// ---- Memory -------------------------------------------------------------------------------------

private class MemorySave(private val home: AgentHome) : Tool {
    override val risk = ToolRisk.WRITE
    override val defaultPolicy = ToolPolicy.ALLOW
    override val spec = ToolSpec(
        "memory_save",
        "Remember something for future conversations: the user's preferences, facts about them or their projects, corrections they gave you, " +
            "and lessons from tasks (what worked, what failed). Saving to an existing topic replaces it, so read it first and merge. " +
            "Don't save secrets, passwords or things that are easy to look up again.",
        ObjectSchema()
            .string("topic", "Short topic name, e.g. \"coffee preferences\" or \"home server\".")
            .string("summary", "One line for the memory index, shown at the start of every chat.")
            .string("content", "The full note in Markdown.")
            .build(),
    )

    override fun describe(args: JsonObject) = "Remember “${args.str("topic")}”"

    override suspend fun execute(args: JsonObject): ToolResult {
        val replaced = home.saveMemory(args.str("topic").orEmpty(), args.str("summary").orEmpty(), args.str("content").orEmpty())
        return ToolResult(if (replaced) "Updated memory “${AgentHome.slug(args.str("topic").orEmpty())}”." else "Saved to memory as “${AgentHome.slug(args.str("topic").orEmpty())}”.")
    }
}

private class MemoryRead(private val home: AgentHome) : Tool {
    override val risk = ToolRisk.READ
    override val spec = ToolSpec(
        "memory_read",
        "Read a memory topic listed in <memory_index>.",
        ObjectSchema().string("topic", "Topic name from the index.").build(),
    )

    override fun describe(args: JsonObject) = "Recall “${args.str("topic")}”"

    override suspend fun execute(args: JsonObject): ToolResult =
        home.readMemory(args.str("topic").orEmpty())?.let { ToolResult(it) } ?: ToolResult.error("No memory topic “${args.str("topic")}”.")
}

private class MemorySearch(private val home: AgentHome) : Tool {
    override val risk = ToolRisk.READ
    override val spec = ToolSpec(
        "memory_search",
        "Search all memory topics by keywords when the index doesn't show what you need.",
        ObjectSchema().string("query", "Keywords.").build(),
    )

    override fun describe(args: JsonObject) = "Search memory for “${args.str("query")}”"

    override suspend fun execute(args: JsonObject): ToolResult {
        val hits = home.searchMemory(args.str("query").orEmpty())
        return ToolResult(if (hits.isEmpty()) "No matches." else hits.joinToString("\n") { (topic, line) -> "- $topic: $line" })
    }
}

private class MemoryForget(private val home: AgentHome) : Tool {
    override val risk = ToolRisk.WRITE
    override val defaultPolicy = ToolPolicy.ALLOW
    override val spec = ToolSpec(
        "memory_forget",
        "Delete a memory topic, e.g. when the user asks you to forget something or it is outdated.",
        ObjectSchema().string("topic", "Topic name.").build(),
    )

    override fun describe(args: JsonObject) = "Forget “${args.str("topic")}”"

    override suspend fun execute(args: JsonObject): ToolResult =
        if (home.forgetMemory(args.str("topic").orEmpty())) ToolResult("Forgotten.") else ToolResult.error("No such topic.")
}

// ---- Skills -------------------------------------------------------------------------------------

private class SkillUse(private val home: AgentHome) : Tool {
    override val risk = ToolRisk.READ
    override val spec = ToolSpec(
        "skill_use",
        "Load the full instructions of a skill listed in <available_skills>. Do this before starting a task the skill covers, then follow it.",
        ObjectSchema().string("name", "Skill name.").build(),
    )

    override fun describe(args: JsonObject) = "Use skill “${args.str("name")}”"

    override suspend fun execute(args: JsonObject): ToolResult {
        // The prompt's catalog may be cut to fit a small model; the error lists every skill.
        val skill = home.skill(args.str("name").orEmpty())
            ?: return ToolResult.error("No active skill “${args.str("name")}”. Active skills: ${home.skills().joinToString { it.name }.ifEmpty { "none" }}.")
        val files = if (skill.files.isEmpty()) "" else "\n\nBundled files (read with skill_read_file): ${skill.files.joinToString()}"
        return ToolResult("<skill_content name=\"${skill.name}\">\n${skill.body}\n</skill_content>$files")
    }
}

private class SkillReadFile(private val home: AgentHome) : Tool {
    override val risk = ToolRisk.READ
    override val spec = ToolSpec(
        "skill_read_file",
        "Read a file bundled with a skill (references, templates, scripts).",
        ObjectSchema().string("name", "Skill name.").string("path", "File path inside the skill.").build(),
    )

    override fun describe(args: JsonObject) = "Read ${args.str("path")} from skill “${args.str("name")}”"

    override suspend fun execute(args: JsonObject): ToolResult =
        home.skillFile(args.str("name").orEmpty(), args.str("path").orEmpty())?.let { ToolResult(it) }
            ?: ToolResult.error("No such file in that skill.")
}

private class SkillCreate(private val home: AgentHome) : Tool {
    override val risk = ToolRisk.WRITE
    override val defaultPolicy = ToolPolicy.ALLOW
    override val spec = ToolSpec(
        "skill_create",
        "Propose a reusable skill: step-by-step instructions for a kind of task, so next time it goes faster and better. " +
            "Use when the user asks, or after a task you had to work out that is likely to come up again. " +
            "It is saved as a draft; the user reviews it under Library → Skills before it becomes active. " +
            "Proposing a skill with an existing name drafts a new version of it.",
        ObjectSchema()
            .string("name", "lowercase-with-hyphens, max 64 chars, e.g. \"weekly-expense-report\".")
            .string("description", "What it does and when to use it (shown in the skill list, max 1024 chars).")
            .string("instructions", "The skill body in Markdown: when to use it, steps, tools to use, pitfalls, an example.")
            .obj("files", "Optional extra files, {\"path\": \"content\"}, e.g. {\"template.md\": \"...\"}.")
            .build(),
    )

    override fun describe(args: JsonObject) = "Draft skill “${args.str("name")}”"

    override suspend fun execute(args: JsonObject): ToolResult {
        val files = (args["files"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }?.toMap().orEmpty()
        try {
            home.draftSkill(args.str("name").orEmpty(), args.str("description").orEmpty(), args.str("instructions").orEmpty(), files)
        } catch (e: IllegalArgumentException) {
            throw ToolException(e.message.orEmpty())
        }
        return ToolResult("Drafted skill “${args.str("name")}”. Tell the user it is waiting for their review in Library → Skills; it is not active yet.")
    }
}

// ---- Connectors ---------------------------------------------------------------------------------

private class ConnectorCall(
    private val home: AgentHome,
    private val http: HttpClient,
    private val secrets: SecretResolver,
    private val scripts: ScriptEngine?,
) : Tool {
    override val risk = ToolRisk.NETWORK
    override val defaultPolicy = ToolPolicy.ALLOW
    override val spec = ToolSpec(
        "connector_call",
        "Call an operation of a connector listed in <available_connectors>. Credentials are added automatically. " +
            "Operations that change data ask the user first.",
        ObjectSchema()
            .string("connector", "Connector id.")
            .string("operation", "Operation name.")
            .obj("arguments", "Parameters for the operation, by name.")
            .build(),
    )

    override fun describe(args: JsonObject) = "${args.str("connector")} → ${args.str("operation")}"

    override suspend fun isObservation(args: JsonObject) = operation(args)?.sideEffect == "read"

    override suspend fun mustConfirm(args: JsonObject): String? {
        val op = operation(args) ?: return null
        return when (op.sideEffect) {
            "read" -> null
            "destructive" -> "This deletes or irreversibly changes data in ${args.str("connector")}."
            else -> "This changes data in ${args.str("connector")}."
        }
    }

    private suspend fun runScript(connector: Connector, expression: String): JsonObject? {
        if (connector.script.isBlank()) return null
        val engine = scripts ?: throw ToolException("Scripts aren't available on this phone, so “${connector.id}” can't run.")
        val result = try {
            engine.eval(connector.script + "\n;JSON.stringify((function(){ return $expression; })())")
        } catch (e: Exception) {
            throw ToolException("The connector's script failed: ${e.message}")
        }
        // The sandbox returns the expression's string value, here the JSON we stringified.
        return runCatching { kotlinx.serialization.json.Json.parseToJsonElement(result) as? JsonObject }.getOrNull()
    }

    private fun operation(args: JsonObject) = home.connector(args.str("connector").orEmpty())?.operations?.firstOrNull { it.name == args.str("operation") }

    override suspend fun execute(args: JsonObject): ToolResult {
        val connector = home.connector(args.str("connector").orEmpty())
            ?: throw ToolException("No active connector “${args.str("connector")}”. Active: ${home.connectors().joinToString { it.id }.ifEmpty { "none" }}.")
        val op = connector.operations.firstOrNull { it.name == args.str("operation") }
            ?: throw ToolException("No operation “${args.str("operation")}”. Available: ${connector.operations.joinToString { it.name }}")
        val values = (args["arguments"] as? JsonObject).orEmpty()
        op.params.filter { it.required && values[it.name] == null }.takeIf { it.isNotEmpty() }?.let {
            throw ToolException("Missing: ${it.joinToString { p -> p.name }}")
        }
        // A connector script may reshape the request; it never sees credentials or the network.
        val overrides = runScript(connector, "typeof request === 'function' ? request(${q(op.name)}, ${JsonObject(values)}) : null")
        var path = overrides?.str("path") ?: op.path
        op.params.filter { it.location == "path" }.forEach { p ->
            val v = (values[p.name] as? JsonPrimitive)?.contentOrNull.orEmpty()
            path = path.replace("{${p.name}}", java.net.URLEncoder.encode(v, "UTF-8"))
        }
        val url = connector.baseUrl.trimEnd('/') + "/" + path.trimStart('/')
        // Requests may only go to the connector's own host, whatever the arguments say.
        if (url.substringAfter("://").substringBefore('/').substringBefore(':') != connector.host) throw ToolException("Blocked: request left ${connector.host}.")
        val secret = if (connector.auth.type == "none") null else secrets.resolve(connector.auth.secret)
            ?: throw ToolException("The connector needs the secret “${connector.auth.secret}”. Ask the user to add it in Library → Connectors.")

        // A failed request is reported with the secret taken out: Ktor's timeout errors quote the full
        // URL, which carries the secret for query auth.
        val (response, raw) = try {
            val response = http.request(url) {
                method = HttpMethod.parse((overrides?.str("method") ?: op.method).uppercase())
                when (connector.auth.type) {
                    "bearer" -> header("Authorization", "Bearer $secret")
                    "header" -> header(connector.auth.name, secret)
                    "query" -> parameter(connector.auth.name, secret)
                }
                op.params.filter { it.location == "query" }.forEach { p -> (values[p.name] as? JsonPrimitive)?.contentOrNull?.let { parameter(p.name, it) } }
                (overrides?.get("query") as? JsonObject)?.forEach { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { parameter(k, it) } }
                val body = overrides?.get("body") ?: op.params.filter { it.location == "body" }.mapNotNull { p -> values[p.name]?.let { p.name to it } }.toMap()
                    .takeIf { it.isNotEmpty() }?.let { JsonObject(it) }
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body.toString())
                }
            }
            response to response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ToolException("${e::class.simpleName}: ${scrub(e.message.orEmpty(), secret)}")
        }
        val engine = scripts
        val shaped = if (connector.script.isNotBlank() && engine != null) {
            runCatching {
                engine.eval(connector.script + "\n;(function(){ return typeof response === 'function' ? String(response(${q(op.name)}, ${response.status.value}, ${q(raw)})) : null; })()")
            }.getOrNull()?.takeIf { it != "null" } ?: raw
        } else raw
        val text = shaped.let { if (it.length > 30_000) it.take(30_000) + "\n…[truncated]" else it }
        // A connector must never echo its own credential back into the conversation.
        val safe = if (secret != null && secret.length >= 6) scrub(text, secret) else text
        return ToolResult("HTTP ${response.status.value}\n$safe", isError = response.status.value >= 400)
    }
}

/** [text] with [secret] replaced, as typed and as it appears %-encoded in a URL. */
internal fun scrub(text: String, secret: String?): String {
    if (secret.isNullOrEmpty()) return text
    return listOf(secret, java.net.URLEncoder.encode(secret, "UTF-8"), java.net.URLEncoder.encode(secret, "UTF-8").replace("+", "%20"))
        .distinct().fold(text) { acc, form -> acc.replace(form, "[secret]") }
}

private class ConnectorCreate(private val home: AgentHome) : Tool {
    override val risk = ToolRisk.WRITE
    override val defaultPolicy = ToolPolicy.ALLOW
    override val spec = ToolSpec(
        "connector_create",
        "Propose a connector: either an MCP server (when the user gives an MCP server URL or says \"MCP\") or an HTTP API (read its documentation first, e.g. with fetch_url). " +
            "MCP shape: {\"id\":\"github\",\"kind\":\"mcp\",\"name\":\"GitHub\",\"description\":\"…\",\"baseUrl\":\"https://example.com/mcp\",\"auth\":{\"type\":\"bearer\",\"secret\":\"github-token\"},\"operations\":[]} " +
            "(auth type none if the server needs no token). HTTP API shape follows. " +
            "Optionally add \"script\": JavaScript defining request(operation, args) returning {method, path, query, body} overrides and/or response(operation, status, text) returning the text to show; it runs sandboxed without network or secrets. " +
            "It is saved as a draft; the user reviews it in Library → Connectors and enters any API key there, so you never see it. " +
            "JSON shape: {\"id\":\"todoist\",\"name\":\"Todoist\",\"description\":\"…\",\"baseUrl\":\"https://api.todoist.com/rest/v2\"," +
            "\"auth\":{\"type\":\"bearer|header|query|none\",\"name\":\"header or query param name if needed\",\"secret\":\"todoist-token\"}," +
            "\"operations\":[{\"name\":\"list_tasks\",\"description\":\"…\",\"method\":\"GET\",\"path\":\"/tasks\"," +
            "\"params\":[{\"name\":\"project_id\",\"location\":\"query\",\"required\":false,\"description\":\"…\"}],\"sideEffect\":\"read|write|destructive\"}]}",
        ObjectSchema().string("connector_json", "The connector definition as JSON.").build(),
    )

    override fun describe(args: JsonObject) = "Draft a connector"

    override suspend fun execute(args: JsonObject): ToolResult {
        val connector = try {
            AgentHome.json.decodeFromString(Connector.serializer(), args.str("connector_json").orEmpty()).copy(createdBy = "agent")
        } catch (e: Exception) {
            throw ToolException("Invalid connector JSON: ${e.message}")
        }
        try {
            home.draftConnector(connector)
        } catch (e: IllegalArgumentException) {
            throw ToolException(e.message.orEmpty())
        }
        return ToolResult(
            (if (connector.kind == "mcp") "Drafted MCP server connector “${connector.id}” (${connector.host}). " else "Drafted connector “${connector.id}” (${connector.operations.size} operations, host ${connector.host}). ") +
                "Tell the user to review it in Library → Connectors" + if (connector.auth.type != "none") " and add the secret “${connector.auth.secret}” there." else ".",
        )
    }
}

private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())

private fun q(s: String) = JsonPrimitive(s).toString()
