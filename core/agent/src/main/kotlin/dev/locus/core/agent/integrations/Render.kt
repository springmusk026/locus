package dev.locus.core.agent.integrations

import dev.locus.core.agent.ObjectSchema
import dev.locus.core.agent.Tool
import dev.locus.core.agent.ToolException
import dev.locus.core.agent.ToolResult
import dev.locus.core.agent.bool
import dev.locus.core.agent.home.SecretResolver
import dev.locus.core.agent.int
import dev.locus.core.agent.str
import dev.locus.core.model.ToolPolicy
import dev.locus.core.model.ToolRisk
import dev.locus.core.model.ToolSpec
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Render over its public REST API v1 (https://api-docs.render.com, OpenAPI at
 * https://api-docs.render.com/openapi/render-public-api-1.json) with the user's API key (vault secret
 * `integration-render`).
 *
 * Lists use cursor pagination: each item is `{ "<resource>": {…}, "cursor": "…" }`, and the next page
 * starts at the last item's cursor (limit 1–100, default 20). Rate limits
 * (https://api-docs.render.com/reference/rate-limiting): deploys, suspend and resume 10 a minute per
 * service; logs 30 a minute; other writes 30 a minute; other reads 400 a minute.
 * Environment variable values are read only to be dropped; they never reach the model.
 */
internal fun renderTools(http: HttpClient, secrets: SecretResolver): List<Tool> {
    val api = RenderApi(http, secrets)
    return listOf(
        RenderServices(api),
        RenderDeploys(api),
        RenderDeploy(api),
        RenderLogs(api),
        RenderEnv(api),
        RenderEnvSet(api),
        RenderSuspend(api),
    )
}

private class RenderApi(private val http: HttpClient, private val secrets: SecretResolver) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun call(method: HttpMethod, path: String, params: List<Pair<String, Any?>> = emptyList(), json: JsonElement? = null): JsonElement {
        val key = secrets.resolve(Integrations.render.secret)?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ToolException("Render needs an API key. Ask the user to add one in Settings → Integrations → Render.")
        val response = http.request("$BASE$path") {
            this.method = method
            header("Authorization", "Bearer $key")
            header("Accept", "application/json")
            params.forEach { (k, v) -> if (v != null) parameter(k, v) }
            if (json != null) {
                contentType(ContentType.Application.Json)
                setBody(json.toString())
            }
        }
        val text = response.bodyAsText()
        val status = response.status.value
        val parsed = if (text.isBlank()) JsonObject(emptyMap()) else runCatching { this.json.parseToJsonElement(text) }.getOrNull()
        if (status in 200..299 && parsed != null) return parsed
        val obj = parsed.asObj()
        val message = (obj?.field("message") ?: text.take(300)).replace(key, "[key]").take(600)
        throw ToolException(
            when (status) {
                401 -> "Render rejected the API key. Ask the user to check it in Settings → Integrations → Render."
                403 -> if (obj?.field("id") == "insufficient_token_scope" || "scope" in message) {
                    "Render refused: the API key's scopes don't cover this ($message)."
                } else {
                    "Render refused: $message. The key's account may not have access to this service or workspace."
                }
                404 -> "Not found on Render ($message)."
                410 -> "Gone on Render ($message)."
                429 -> "Render rate limit reached" + (response.headers["Retry-After"]?.let { " (retry after $it s)" } ?: "") +
                    ". Deploys, suspend and resume: 10 a minute per service; logs: 30 a minute; other changes: 30 a minute."
                else -> "Render error $status: $message"
            },
        )
    }

    suspend fun service(id: String): JsonObject = call(HttpMethod.Get, "/services/$id").asObj() ?: throw ToolException("Unexpected response from Render.")

    /** A service ID from an ID (srv-…, crn-…) or an exact service name. */
    suspend fun serviceId(arg: String?): String {
        val s = arg?.trim().orEmpty()
        if (SERVICE_ID.matches(s)) return s
        if (s.isEmpty() || s.length > 200) throw ToolException("service must be a Render service ID (srv-…) or service name.")
        val found = call(HttpMethod.Get, "/services", listOf("name" to s, "limit" to 100, "includePreviews" to false)).objects()
            .mapNotNull { it["service"].asObj() }.filter { it.field("name") == s }
        return when (found.size) {
            1 -> found[0].field("id") ?: throw ToolException("Render returned a service without an ID.")
            0 -> throw ToolException("No Render service named '$s'. List them with render_services.")
            else -> throw ToolException("Several services are named '$s': ${found.joinToString { it.field("id").orEmpty() }}. Use the ID.")
        }
    }

    companion object {
        const val BASE = "https://api.render.com/v1"
        val SERVICE_ID = Regex("^[a-z]{3,4}-[a-z0-9]{6,40}$")
        val DEPLOY_ID = Regex("^dep-[a-z0-9]{6,40}$")
        val ENV_KEY = Regex("^[A-Za-z_][A-Za-z0-9_.-]{0,255}$")
        val COMMIT = Regex("^[0-9a-fA-F]{7,40}$")
    }
}

/** Items of a cursor-paginated list, unwrapped from `{ [wrapper]: {…}, cursor }`, and the last cursor. */
private fun JsonElement.page(wrapper: String): Pair<List<JsonObject>, String?> {
    val items = objects()
    return items.mapNotNull { it[wrapper].asObj() } to items.lastOrNull()?.field("cursor")
}

private fun more(count: Int, limit: Int, cursor: String?) =
    if (count >= limit && cursor != null) "\nMore: call again with cursor=$cursor" else ""

private fun limitArg(args: JsonObject, default: Int) = (args.int("limit") ?: default).coerceIn(1, 100)

private fun cursorArg(args: JsonObject) = args.str("cursor")?.trim()?.takeIf { it.isNotEmpty() }?.also {
    if (it.length > 200 || it.any { c -> c.isWhitespace() }) throw ToolException("cursor must be a value from an earlier result.")
}

private fun JsonObject.renderDeployLine(): String {
    val commit = this["commit"].asObj()
    return listOfNotNull(
        field("id"),
        field("status"),
        field("trigger"),
        commit?.field("id")?.take(7),
        commit?.field("message")?.lineSequence()?.firstOrNull()?.take(80),
        field("createdAt")?.take(16)?.replace('T', ' '),
        field("finishedAt")?.take(16)?.replace('T', ' ')?.let { "finished $it" },
    ).joinToString(" · ")
}

private class RenderServices(private val api: RenderApi) : Tool {
    override val risk = ToolRisk.NETWORK
    override val defaultPolicy = ToolPolicy.ALLOW
    override val spec = ToolSpec(
        "render_services",
        "List the user's Render services (web services, static sites, workers, cron jobs) with their ID, URL, repo and whether they are suspended.",
        ObjectSchema()
            .string("name", "Exact service name to filter by.", required = false)
            .integer("limit", "1–100, default 20.")
            .string("cursor", "Cursor from an earlier result, for the next page.", required = false)
            .build(),
    )

    override fun describe(args: JsonObject) = "List your Render services"
    override suspend fun isObservation(args: JsonObject) = true

    override suspend fun execute(args: JsonObject): ToolResult {
        val limit = limitArg(args, 20)
        val (services, cursor) = api.call(
            HttpMethod.Get, "/services",
            listOf("limit" to limit, "cursor" to cursorArg(args), "name" to args.str("name")?.trim()?.takeIf { it.isNotEmpty() }?.take(200)),
        ).page("service")
        if (services.isEmpty()) return ToolResult("No services.")
        return ToolResult(
            services.joinToString("\n") { s ->
                val details = s["serviceDetails"].asObj()
                listOfNotNull(
                    "- ${s.field("name")} (${s.field("id")})",
                    s.field("type"),
                    if (s.field("suspended") == "suspended") "SUSPENDED" else null,
                    details?.field("url"),
                    s.field("repo")?.let { r -> r + (s.field("branch")?.let { "@$it" } ?: "") } ?: s.field("imagePath"),
                    s.field("updatedAt")?.take(10)?.let { "updated $it" },
                ).joinToString(" · ")
            } + more(services.size, limit, cursor),
        )
    }
}

private class RenderDeploys(private val api: RenderApi) : Tool {
    override val risk = ToolRisk.NETWORK
    override val defaultPolicy = ToolPolicy.ALLOW
    override val spec = ToolSpec(
        "render_deploys",
        "List a Render service's recent deploys (status, trigger, commit), or with id show one deploy. For build output use render_logs with type=build.",
        ObjectSchema()
            .string("service", "Service ID (srv-…) or name.")
            .string("id", "Deploy ID (dep-…) to inspect.", required = false)
            .integer("limit", "1–100, default 10.")
            .string("cursor", "Cursor from an earlier result, for the next page.", required = false)
            .build(),
    )

    override fun describe(args: JsonObject) = args.str("id")?.let { "Check Render deploy $it" } ?: "List deploys of ${args.str("service")}"
    override suspend fun isObservation(args: JsonObject) = true

    override suspend fun execute(args: JsonObject): ToolResult {
        val service = api.serviceId(args.str("service"))
        val id = args.str("id")?.trim()?.takeIf { it.isNotEmpty() }
        if (id != null) {
            if (!RenderApi.DEPLOY_ID.matches(id)) throw ToolException("id must be a Render deploy ID like dep-….")
            val d = api.call(HttpMethod.Get, "/services/$service/deploys/$id").asObj() ?: throw ToolException("Unexpected response from Render.")
            // Commit messages are written by whoever pushed, so they are marked untrusted.
            return ToolResult(untrustedOutput("Render", d.renderDeployLine()))
        }
        val limit = limitArg(args, 10)
        val (deploys, cursor) = api.call(HttpMethod.Get, "/services/$service/deploys", listOf("limit" to limit, "cursor" to cursorArg(args))).page("deploy")
        if (deploys.isEmpty()) return ToolResult("No deploys yet.")
        return ToolResult(untrustedOutput("Render", deploys.joinToString("\n") { "- " + it.renderDeployLine() }) + more(deploys.size, limit, cursor))
    }
}

private class RenderDeploy(private val api: RenderApi) : Tool {
    override val risk = ToolRisk.NETWORK
    override val defaultPolicy = ToolPolicy.ASK
    override val spec = ToolSpec(
        "render_deploy",
        "Start a deploy of a Render service from its connected repo or image (the latest commit of its branch unless commit is given). " +
            "Render builds it and switches traffic when it is live. Check progress with render_deploys.",
        ObjectSchema()
            .string("service", "Service ID (srv-…) or name.")
            .string("commit", "Git commit SHA to deploy instead of the branch's latest.", required = false)
            .boolean("clear_cache", "Clear the build cache first. Defaults to false.")
            .build(),
    )

    override fun describe(args: JsonObject) =
        "Deploy Render service ${args.str("service")}" + (args.str("commit")?.let { " at ${it.take(7)}" } ?: "") +
            (if (args.bool("clear_cache") == true) " (clear cache)" else "")

    override suspend fun mustConfirm(args: JsonObject) = "This starts a new deploy of ${args.str("service")} on Render, which replaces the live version when it succeeds."

    override suspend fun execute(args: JsonObject): ToolResult {
        val commit = args.str("commit")?.trim()?.takeIf { it.isNotEmpty() }
        if (commit != null && !RenderApi.COMMIT.matches(commit)) throw ToolException("commit must be a Git commit SHA (7–40 hex characters).")
        val service = api.serviceId(args.str("service"))
        val body = buildJsonObject {
            put("clearCache", if (args.bool("clear_cache") == true) "clear" else "do_not_clear")
            commit?.let { put("commitId", it) }
        }
        val d = api.call(HttpMethod.Post, "/services/$service/deploys", json = body).asObj()
        val id = d?.field("id") ?: return ToolResult("Deploy of $service queued behind the one in progress. Check with render_deploys service=$service.")
        return ToolResult("Deploy $id started for $service: ${d.field("status") ?: "created"}.\nCheck with render_deploys service=$service id=$id, or render_logs type=build.")
    }
}

private class RenderLogs(private val api: RenderApi) : Tool {
    override val risk = ToolRisk.NETWORK
    override val defaultPolicy = ToolPolicy.ALLOW
    override val spec = ToolSpec(
        "render_logs",
        "Read a Render service's logs from the last hour, newest last. type picks app, build or request logs; text filters by content " +
            "(Render wildcards and regex). Logs are limited to 30 requests a minute.",
        ObjectSchema()
            .string("service", "Service ID (srv-…) or name.")
            .string("type", "app, build or request. Defaults to all.", required = false)
            .string("text", "Only lines matching this text.", required = false)
            .string("level", "Only this level, e.g. error.", required = false)
            .integer("limit", "Lines, 1–100. Defaults to 50.")
            .build(),
    )

    override fun describe(args: JsonObject) = "Read ${args.str("type")?.let { "$it " }.orEmpty()}logs of ${args.str("service")}"
    override suspend fun isObservation(args: JsonObject) = true

    override suspend fun execute(args: JsonObject): ToolResult {
        val type = args.str("type")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (type != null && type !in setOf("app", "build", "request")) throw ToolException("type must be app, build or request.")
        val service = api.service(api.serviceId(args.str("service")))
        val owner = service.field("ownerId") ?: throw ToolException("Render didn't say which workspace owns this service.")
        val limit = (args.int("limit") ?: 50).coerceIn(1, 100)
        val result = api.call(
            HttpMethod.Get, "/logs",
            listOf(
                "ownerId" to owner,
                "resource" to service.field("id"),
                "direction" to "backward",
                "limit" to limit,
                "type" to type,
                "text" to args.str("text")?.trim()?.takeIf { it.isNotEmpty() }?.take(200),
                "level" to args.str("level")?.trim()?.takeIf { it.isNotEmpty() }?.take(40),
            ),
        ).asObj() ?: throw ToolException("Unexpected response from Render.")
        val lines = result["logs"].objects().map { l ->
            val level = l["labels"].objects().firstOrNull { it.field("name") == "level" }?.field("value")
            listOfNotNull(l.field("timestamp")?.take(19)?.replace('T', ' '), level, l.field("message")).joinToString(" ")
        }.reversed()
        if (lines.isEmpty()) return ToolResult("No logs in the last hour" + (type?.let { " of type $it" } ?: "") + ".")
        val earlier = if (result.flag("hasMore") == true) "…(earlier lines exist; narrow with text or level)\n" else ""
        return ToolResult(untrustedOutput("Render log", earlier + tail(lines, limit)))
    }
}

private class RenderEnv(private val api: RenderApi) : Tool {
    override val risk = ToolRisk.NETWORK
    override val defaultPolicy = ToolPolicy.ALLOW
    override val spec = ToolSpec(
        "render_env",
        "List the environment variable names set directly on a Render service (not environment groups). Values are never shown.",
        ObjectSchema().string("service", "Service ID (srv-…) or name.").build(),
    )

    override fun describe(args: JsonObject) = "List env variables of ${args.str("service")}"
    override suspend fun isObservation(args: JsonObject) = true

    override suspend fun execute(args: JsonObject): ToolResult {
        val service = api.serviceId(args.str("service"))
        val names = mutableListOf<String>()
        var cursor: String? = null
        // Only keys are kept; the values in each reply are dropped here.
        for (page in 1..10) {
            val (vars, next) = api.call(HttpMethod.Get, "/services/$service/env-vars", listOf("limit" to 100, "cursor" to cursor)).page("envVar")
            names += vars.mapNotNull { it.field("key") }
            if (vars.size < 100 || next == null || next == cursor) break
            cursor = next
        }
        if (names.isEmpty()) return ToolResult("No environment variables set on the service.")
        return ToolResult(names.joinToString("\n") { "- $it" })
    }
}

private class RenderEnvSet(private val api: RenderApi) : Tool {
    override val risk = ToolRisk.NETWORK
    override val defaultPolicy = ToolPolicy.ASK
    override val spec = ToolSpec(
        "render_env_set",
        "Create or update an environment variable on a Render service. It is saved without a deploy; running instances get it on the next deploy (render_deploy).",
        ObjectSchema()
            .string("service", "Service ID (srv-…) or name.")
            .string("key", "Variable name (letters, digits, _ . -; not starting with a digit).")
            .string("value", "Value.")
            .build(),
    )

    // The value is left out on purpose so it never appears in the approval card or activity log.
    override fun describe(args: JsonObject) = "Set ${args.str("key")} on Render service ${args.str("service")}"

    override suspend fun execute(args: JsonObject): ToolResult {
        val key = args.str("key")?.trim().orEmpty()
        if (!RenderApi.ENV_KEY.matches(key)) throw ToolException("key may only contain letters, digits, _ . and -, and must not start with a digit.")
        val value = args.str("value") ?: throw ToolException("value is required.")
        if (value.length > 65_536) throw ToolException("value is too long.")
        val service = api.serviceId(args.str("service"))
        api.call(HttpMethod.Put, "/services/$service/env-vars/$key", json = buildJsonObject { put("value", value) })
        return ToolResult("Set $key on $service. It applies from the next deploy.")
    }
}

private class RenderSuspend(private val api: RenderApi) : Tool {
    override val risk = ToolRisk.NETWORK
    override val defaultPolicy = ToolPolicy.ASK
    override val spec = ToolSpec(
        "render_suspend",
        "Suspend a Render service (takes it offline until resumed) or resume a suspended one.",
        ObjectSchema()
            .string("service", "Service ID (srv-…) or name.")
            .string("action", "suspend or resume.")
            .build(),
    )

    override fun describe(args: JsonObject) =
        "${if (args.str("action")?.trim()?.lowercase() == "resume") "Resume" else "Suspend"} Render service ${args.str("service")}"

    override suspend fun mustConfirm(args: JsonObject) =
        if (args.str("action")?.trim()?.lowercase() == "resume") {
            "This starts ${args.str("service")} again on Render, and Render bills it while it runs."
        } else {
            "This takes ${args.str("service")} offline on Render until it is resumed."
        }

    override suspend fun execute(args: JsonObject): ToolResult {
        val action = args.str("action")?.trim()?.lowercase()
        if (action != "suspend" && action != "resume") throw ToolException("action must be suspend or resume.")
        val service = api.serviceId(args.str("service"))
        api.call(HttpMethod.Post, "/services/$service/$action")
        return ToolResult(if (action == "suspend") "Suspended $service." else "Resuming $service; Render starts a deploy to bring it back.")
    }
}
