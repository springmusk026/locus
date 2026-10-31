package dev.locus.core.agent.integrations

import dev.locus.core.agent.ObjectSchema
import dev.locus.core.agent.Tool
import dev.locus.core.agent.ToolException
import dev.locus.core.agent.ToolResult
import dev.locus.core.agent.bool
import dev.locus.core.agent.home.SecretResolver
import dev.locus.core.agent.int
import dev.locus.core.agent.str
import dev.locus.core.agent.tools.Workspace
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Vercel over its REST API (https://vercel.com/docs/rest-api) with the user's access token (vault
 * secret `integration-vercel`). The vault value is the token, optionally followed by a space and a
 * team ID (`team_…`) for a full-account token that should act on a team; team- and project-scoped
 * tokens need no ID because Vercel infers it.
 *
 * Deploying from the workspace follows the documented file flow: create the deployment with each
 * file's SHA-1 (POST /v13/deployments); if Vercel answers `missing_files`, upload exactly those
 * (POST /v2/files with x-vercel-digest) and create it again.
 */
internal fun vercelTools(http: HttpClient, secrets: SecretResolver, workspace: Workspace?): List<Tool> {
    val api = VercelApi(http, secrets)
    return listOfNotNull(
        VercelProjects(api),
        VercelDeployments(api),
        workspace?.let { VercelDeploy(api, it) },
        VercelEnv(api),
        VercelEnvSet(api),
    )
}

/** A Vercel response whose status the caller inspects (used where an error is part of the flow). */
private class VercelReply(val status: Int, val body: JsonElement?, val text: String) {
    val error get() = body.asObj()?.get("error").asObj()
    val code get() = error?.field("code")
    val missing get() = (error?.get("missing") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
}

private class VercelApi(private val http: HttpClient, private val secrets: SecretResolver) {
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun credentials(): Pair<String, String?> {
        val value = secrets.resolve(Integrations.vercel.secret)?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ToolException("Vercel needs an access token. Ask the user to add one in Settings → Integrations → Vercel.")
        val parts = value.split(Regex("\\s+"))
        return parts[0] to parts.getOrNull(1)?.takeIf { TEAM.matches(it) }
    }

    suspend fun send(
        method: HttpMethod,
        path: String,
        params: Map<String, Any?> = emptyMap(),
        json: JsonElement? = null,
        bytes: ByteArray? = null,
        headers: Map<String, String> = emptyMap(),
    ): VercelReply {
        val (token, team) = credentials()
        val response = http.request("$BASE$path") {
            this.method = method
            header("Authorization", "Bearer $token")
            headers.forEach { (k, v) -> header(k, v) }
            team?.let { parameter("teamId", it) }
            params.forEach { (k, v) -> if (v != null) parameter(k, v) }
            when {
                json != null -> { contentType(ContentType.Application.Json); setBody(json.toString()) }
                bytes != null -> { contentType(ContentType.Application.OctetStream); setBody(bytes) }
            }
        }
        val text = response.bodyAsText()
        val parsed = if (text.isBlank()) JsonObject(emptyMap()) else runCatching { this.json.parseToJsonElement(text) }.getOrNull()
        return VercelReply(response.status.value, parsed, text)
    }

    suspend fun call(
        method: HttpMethod,
        path: String,
        params: Map<String, Any?> = emptyMap(),
        json: JsonElement? = null,
        bytes: ByteArray? = null,
        headers: Map<String, String> = emptyMap(),
    ): JsonElement = check(send(method, path, params, json, bytes, headers))

    fun check(reply: VercelReply): JsonElement {
        val status = reply.status
        if (status in 200..299 && reply.body != null) return reply.body
        val code = reply.code
        val message = reply.error?.field("message") ?: reply.text.take(300)
        throw ToolException(
            when {
                status == 401 -> "Vercel rejected the token. Ask the user to check it in Settings → Integrations → Vercel."
                status == 403 -> "Vercel refused: $message. The token's scope may not cover this. A full-account token acting on a team needs the team ID after the token (\"TOKEN team_…\")."
                status == 404 -> "Not found on Vercel ($message)."
                status == 429 || code == "rate_limited" -> "Vercel rate limit reached ($message). Wait and retry later."
                status == 402 -> "Vercel plan limit: $message"
                else -> "Vercel error $status: $message"
            },
        )
    }

    companion object {
        const val BASE = "https://api.vercel.com"
        val TEAM = Regex("^team_[A-Za-z0-9]+$")
    }
}

private val PROJECT = Regex("^[A-Za-z0-9._-]{1,100}$")
private val DEPLOYMENT = Regex("^[A-Za-z0-9._-]{1,200}$")
private val ENV_KEY = Regex("^[A-Za-z_][A-Za-z0-9_]{0,255}$")
private val VERCEL_TARGETS = setOf("production", "preview", "development")

private fun projectArg(args: JsonObject, key: String = "project"): String {
    val p = args.str(key)?.trim().orEmpty()
    if (!PROJECT.matches(p)) throw ToolException("$key must be a Vercel project name or ID (letters, digits, . _ -).")
    return p
}

private fun JsonObject.deploymentLine(): String {
    val state = field("readyState") ?: field("state")
    val url = field("url")?.let { "https://$it" }
    val target = field("target") ?: "preview"
    val created = millisToText(longField("createdAt") ?: longField("created"))
    return listOfNotNull(field("uid") ?: field("id"), state, target, url, created).joinToString(" · ")
}

private class VercelProjects(private val api: VercelApi) : Tool {
    override val risk = ToolRisk.NETWORK
    override val defaultPolicy = ToolPolicy.ALLOW
    override val spec = ToolSpec(
        "vercel_projects",
        "List the user's Vercel projects with their latest deployment.",
        ObjectSchema()
            .string("search", "Filter by project name.", required = false)
            .integer("limit", "1–50, default 20.")
            .build(),
    )

    override fun describe(args: JsonObject) = "List your Vercel projects"
    override suspend fun isObservation(args: JsonObject) = true

    override suspend fun execute(args: JsonObject): ToolResult {
        val result = api.call(
            HttpMethod.Get, "/v10/projects",
            mapOf("limit" to (args.int("limit") ?: 20).coerceIn(1, 50), "search" to args.str("search")?.take(100)),
        )
        // The response is either a bare array or { projects, pagination }.
        val projects = (result as? JsonArray ?: result.asObj()?.get("projects")).objects()
        if (projects.isEmpty()) return ToolResult("No projects.")
        return ToolResult(
            projects.joinToString("\n") { p ->
                val latest = p["latestDeployments"].objects().firstOrNull()
                "- ${p.field("name")} (${p.field("id")})" + (p.field("framework")?.let { " · $it" } ?: "") +
                    (latest?.let { d -> " · latest: ${d.field("readyState")}${d.field("target")?.let { " $it" }.orEmpty()} ${d.field("url")?.let { "https://$it" }.orEmpty()}" } ?: " · no deployments")
            },
        )
    }
}

private class VercelDeployments(private val api: VercelApi) : Tool {
    override val risk = ToolRisk.NETWORK
    override val defaultPolicy = ToolPolicy.ALLOW
    override val spec = ToolSpec(
        "vercel_deployments",
        "List recent Vercel deployments (optionally for one project), or with id show one deployment's status. " +
            "Set logs to also get the end of its build log, e.g. to see why it failed.",
        ObjectSchema()
            .string("project", "Project name or ID to filter by.", required = false)
            .string("id", "Deployment ID (dpl_…) or its hostname.", required = false)
            .boolean("logs", "With id: include the last build log lines. Defaults to false.")
            .integer("limit", "Deployments to list, 1–50 (default 10); with logs, log lines (default 60, max 300).")
            .build(),
    )

    override fun describe(args: JsonObject) = args.str("id")?.let { "Check Vercel deployment $it" } ?: "List Vercel deployments"
    override suspend fun isObservation(args: JsonObject) = true

    override suspend fun execute(args: JsonObject): ToolResult {
        val id = args.str("id")?.trim()?.removePrefix("https://")?.trimEnd('/')
        if (id.isNullOrEmpty()) {
            val project = args.str("project")?.takeIf { it.isNotBlank() }?.let { projectArg(args) }
            val list = api.call(HttpMethod.Get, "/v7/deployments", mapOf("projectId" to project, "limit" to (args.int("limit") ?: 10).coerceIn(1, 50)))
                .asObj()?.get("deployments").objects()
            if (list.isEmpty()) return ToolResult("No deployments.")
            return ToolResult(list.joinToString("\n") { d -> "- ${d.field("name")}: ${d.deploymentLine()}" })
        }
        if (!DEPLOYMENT.matches(id)) throw ToolException("id must be a deployment ID like dpl_… or its hostname.")
        val d = api.call(HttpMethod.Get, "/v13/deployments/$id").asObj() ?: throw ToolException("Unexpected response from Vercel.")
        val summary = buildString {
            append("${d.field("name")}: ${d.deploymentLine()}")
            d.field("inspectorUrl")?.let { append("\nInspect: $it") }
            (d["alias"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.takeIf { it.isNotEmpty() }?.let { append("\nAliases: ${it.take(5).joinToString { a -> "https://$a" }}") }
        }
        val error = listOfNotNull(d.field("errorCode"), d.field("errorMessage")).joinToString(": ").takeIf { it.isNotEmpty() }
        if (args.bool("logs") != true) {
            return ToolResult(summary + (error?.let { "\n" + untrustedOutput("Vercel build", "Error: $it") } ?: ""))
        }
        val lines = (args.int("limit") ?: 60).coerceIn(10, 300)
        val events = api.call(HttpMethod.Get, "/v3/deployments/$id/events", mapOf("direction" to "backward", "limit" to lines)).objects()
        val log = events.mapNotNull { e -> e.field("text") ?: e["payload"].asObj()?.field("text") }.reversed()
        val body = listOfNotNull(error?.let { "Error: $it" }, log.takeIf { it.isNotEmpty() }?.let { tail(it, lines) } ?: "No build log yet.").joinToString("\n")
        return ToolResult("$summary\n" + untrustedOutput("Vercel build", body))
    }
}

private class VercelDeploy(private val api: VercelApi, private val workspace: Workspace) : Tool {
    override val risk = ToolRisk.NETWORK
    override val defaultPolicy = ToolPolicy.ASK
    override val spec = ToolSpec(
        "vercel_deploy",
        "Deploy a workspace folder to Vercel (Vercel builds it if it detects a framework; a plain folder is served as a static site), " +
            "or redeploy an existing deployment with redeploy_id. Creates a preview unless production is true. " +
            "A new project name creates the project. Returns the URL; check progress with vercel_deployments.",
        ObjectSchema()
            .string("project", "Project name (lowercase letters, digits, -).")
            .string("path", "Workspace folder to upload (e.g. site or app/dist). Not needed with redeploy_id.", required = false)
            .string("redeploy_id", "Deployment ID to rebuild instead of uploading files.", required = false)
            .boolean("production", "Deploy to production (the live domains). Defaults to false (preview URL).")
            .string("framework", "Optional framework preset for the project's first deploy, e.g. nextjs, vite, astro; \"static\" for none.", required = false)
            .build(),
    )

    override fun describe(args: JsonObject) =
        (if (args.str("redeploy_id") != null) "Redeploy ${args.str("redeploy_id")}" else "Deploy ${args.str("path")}") +
            " to Vercel project ${args.str("project")}" + (if (args.bool("production") == true) " (production)" else " (preview)")

    override suspend fun mustConfirm(args: JsonObject) =
        if (args.bool("production") == true) "This publishes to the live production site on Vercel." else "This creates a new Vercel deployment."

    override suspend fun execute(args: JsonObject): ToolResult {
        val project = projectArg(args)
        val production = args.bool("production") == true
        val redeploy = args.str("redeploy_id")?.trim()?.takeIf { it.isNotEmpty() }
        if (redeploy != null && !DEPLOYMENT.matches(redeploy)) throw ToolException("redeploy_id must be a deployment ID like dpl_….")
        val framework = args.str("framework")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (framework != null && !Regex("^[a-z0-9-]{1,40}$").matches(framework)) throw ToolException("framework must be a Vercel preset name like nextjs, or static.")
        val folder = if (redeploy == null) {
            collectSiteFiles(workspace, args.str("path") ?: throw ToolException("Give path (a workspace folder) or redeploy_id."))
        } else null

        fun body() = buildJsonObject {
            put("name", project)
            if (production) put("target", "production")
            redeploy?.let { put("deploymentId", it) }
            folder?.let { f ->
                putJsonArray("files") {
                    f.files.forEach { file -> addJsonObject { put("file", file.path); put("sha", file.sha1); put("size", file.size) } }
                }
            }
            framework?.let { fw -> putJsonObject("projectSettings") { if (fw == "static") put("framework", JsonNull) else put("framework", fw) } }
        }

        val params = mapOf("skipAutoDetectionConfirmation" to 1)
        var uploaded = 0
        var reply = api.send(HttpMethod.Post, "/v13/deployments", params, body())
        if (reply.code == "missing_files" && folder != null) {
            val bySha = folder.files.associateBy { it.sha1 }
            val missing = reply.missing.ifEmpty { bySha.keys.toList() }.mapNotNull { bySha[it] }
            for (file in missing) {
                api.call(HttpMethod.Post, "/v2/files", bytes = file.bytes(), headers = mapOf("x-vercel-digest" to file.sha1))
                uploaded++
            }
            reply = api.send(HttpMethod.Post, "/v13/deployments", params, body())
        }
        val created = api.check(reply).asObj() ?: throw ToolException("Unexpected response from Vercel.")

        val url = created.field("url")?.let { "https://$it" }
        return ToolResult(
            buildString {
                append(if (production) "Production deployment" else "Preview deployment")
                append(" ${created.field("id")} created: ${created.field("readyState") ?: "QUEUED"}")
                url?.let { append("\nURL: $it") }
                created.field("inspectorUrl")?.let { append("\nInspect: $it") }
                folder?.let { append("\n${it.files.size} files from ${it.path} (${uploaded} uploaded, the rest already on Vercel).") }
                append("\nIt builds in the background; check with vercel_deployments id=${created.field("id")} (logs=true if it fails).")
            },
        )
    }
}

private class VercelEnv(private val api: VercelApi) : Tool {
    override val risk = ToolRisk.NETWORK
    override val defaultPolicy = ToolPolicy.ALLOW
    override val spec = ToolSpec(
        "vercel_env",
        "List a Vercel project's environment variable names, types and targets. Values are never shown.",
        ObjectSchema().string("project", "Project name or ID.").build(),
    )

    override fun describe(args: JsonObject) = "List env variables of ${args.str("project")}"
    override suspend fun isObservation(args: JsonObject) = true

    override suspend fun execute(args: JsonObject): ToolResult {
        val project = projectArg(args)
        val envs = api.call(HttpMethod.Get, "/v10/projects/$project/env").asObj()?.get("envs").objects()
        if (envs.isEmpty()) return ToolResult("No environment variables.")
        return ToolResult(
            envs.joinToString("\n") { e ->
                val targets = (e["target"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.joinToString(",")
                    ?: e.field("target")
                "- ${e.field("key")} (${e.field("type")}) → ${targets ?: "custom environment"}" + (e.field("gitBranch")?.let { " · branch $it" } ?: "")
            },
        )
    }
}

private class VercelEnvSet(private val api: VercelApi) : Tool {
    override val risk = ToolRisk.NETWORK
    override val defaultPolicy = ToolPolicy.ASK
    override val spec = ToolSpec(
        "vercel_env_set",
        "Create or update an environment variable on a Vercel project. By default it is a Secret (write-only: nobody can read it back); " +
            "set secret=false for readable config such as public URLs. " +
            "Takes effect on the next deployment.",
        ObjectSchema()
            .string("project", "Project name or ID.")
            .string("key", "Variable name (letters, digits, _).")
            .string("value", "Value.")
            .string("targets", "Comma-separated: production, preview, development. Defaults to all three.", required = false)
            .boolean("secret", "Write-only Secret (default true). false stores a readable Config value.")
            .build(),
    )

    // The value is deliberately left out so it never lands in the approval card or activity log.
    override fun describe(args: JsonObject) = "Set ${args.str("key")} on Vercel project ${args.str("project")}"

    override suspend fun execute(args: JsonObject): ToolResult {
        val project = projectArg(args)
        val key = args.str("key")?.trim().orEmpty()
        if (!ENV_KEY.matches(key)) throw ToolException("key may only contain letters, digits and _, and must not start with a digit.")
        val value = args.str("value") ?: throw ToolException("value is required.")
        if (value.length > 65_536) throw ToolException("Value is over Vercel's 65,536 character limit.")
        val targets = args.str("targets")?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.ifEmpty { null }
            ?: VERCEL_TARGETS.toList()
        targets.firstOrNull { it !in VERCEL_TARGETS }?.let { throw ToolException("Unknown target '$it'. Use production, preview or development.") }
        // Vercel's "sensitive" type is shown as Secret; "encrypted" is a readable Config value.
        val secret = args.bool("secret") != false
        val body = buildJsonObject {
            put("key", key)
            put("value", value)
            put("type", if (secret) "sensitive" else "encrypted")
            putJsonArray("target") { targets.forEach { add(it) } }
        }
        api.call(HttpMethod.Post, "/v10/projects/$project/env", mapOf("upsert" to "true"), body)
        return ToolResult("Set $key (${if (secret) "secret" else "config"}) for ${targets.joinToString(", ")} on $project. Redeploy for it to take effect.")
    }
}
