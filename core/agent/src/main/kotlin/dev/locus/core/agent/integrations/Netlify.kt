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
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Netlify over its REST API (https://docs.netlify.com/api-and-cli-guides/api-guides/get-started-with-api/,
 * OpenAPI at https://open-api.netlify.com) with the user's personal access token (vault secret
 * `integration-netlify`).
 *
 * Deploys use the documented file-digest flow: POST /sites/{id}/deploys with `{"files": {"/path": sha1}}`,
 * then PUT /deploys/{id}/files/{path} for each SHA-1 listed in `required`. Netlify allows 3 API
 * deploys a minute and 100 a day, so the tool never retries a deploy on its own.
 */
internal fun netlifyTools(http: HttpClient, secrets: SecretResolver, workspace: Workspace?): List<Tool> {
    val api = NetlifyApi(http, secrets)
    return listOfNotNull(
        NetlifySites(api),
        NetlifyDeploys(api),
        workspace?.let { NetlifyDeploy(api, it) },
        NetlifyEnv(api),
        NetlifyEnvSet(api),
    )
}

private class NetlifyApi(private val http: HttpClient, private val secrets: SecretResolver) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun call(method: HttpMethod, path: String, params: Map<String, Any?> = emptyMap(), json: JsonElement? = null, bytes: ByteArray? = null): JsonElement {
        val token = secrets.resolve(Integrations.netlify.secret)?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ToolException("Netlify needs a personal access token. Ask the user to add one in Settings → Integrations → Netlify.")
        val response = http.request("$BASE$path") {
            this.method = method
            header("Authorization", "Bearer $token")
            params.forEach { (k, v) -> if (v != null) parameter(k, v) }
            when {
                json != null -> { contentType(ContentType.Application.Json); setBody(json.toString()) }
                bytes != null -> { contentType(ContentType.Application.OctetStream); setBody(bytes) }
            }
        }
        val text = response.bodyAsText()
        val status = response.status.value
        val parsed = if (text.isBlank()) JsonObject(emptyMap()) else runCatching { this.json.parseToJsonElement(text) }.getOrNull()
        if (status in 200..299 && parsed != null) return parsed
        val message = parsed.asObj()?.field("message") ?: text.take(300)
        throw ToolException(
            when (status) {
                401 -> "Netlify rejected the token. Ask the user to check it in Settings → Integrations → Netlify (resetting the Netlify password revokes tokens)."
                403 -> "Netlify refused: $message"
                404 -> "Not found on Netlify ($message)."
                429 -> "Netlify rate limit reached (500 requests a minute; deploys: 3 a minute, 100 a day). Wait and retry later."
                else -> "Netlify error $status: $message"
            },
        )
    }

    /** A site by ID, or by name (looked up through the list endpoint's name filter). */
    suspend fun site(ref: String): JsonObject {
        val r = ref.trim().removePrefix("https://").removeSuffix("/").removeSuffix(".netlify.app")
        if (UUID.matches(r)) return call(HttpMethod.Get, "/sites/$r").asObj() ?: throw ToolException("Unexpected response from Netlify.")
        if (!SITE_NAME.matches(r)) throw ToolException("site must be a Netlify site name (e.g. my-site) or site ID.")
        return findByName(r) ?: throw ToolException("No Netlify site named '$r'. List them with netlify_sites.")
    }

    suspend fun findByName(name: String): JsonObject? =
        call(HttpMethod.Get, "/sites", mapOf("name" to name, "filter" to "all", "per_page" to 100)).objects().firstOrNull { it.field("name") == name }

    companion object {
        const val BASE = "https://api.netlify.com/api/v1"
        val UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        val SITE_NAME = Regex("^[a-z0-9][a-z0-9-]{0,62}$")
        val DEPLOY_ID = Regex("^[A-Za-z0-9]{1,64}$")
    }
}

private val NETLIFY_ENV_KEY = Regex("^[A-Za-z_][A-Za-z0-9_]{0,255}$")
private val NETLIFY_CONTEXTS = setOf("all", "production", "deploy-preview", "branch-deploy", "dev", "dev-server")

private fun JsonObject.deployLine(): String {
    val url = field("deploy_ssl_url") ?: field("ssl_url")
    val where = field("context") ?: if (flag("draft") == true) "draft" else null
    return listOfNotNull(field("id"), field("state"), where, field("branch"), url, field("created_at")?.take(16)?.replace('T', ' '))
        .joinToString(" · ")
}

private class NetlifySites(private val api: NetlifyApi) : Tool {
    override val risk = ToolRisk.NETWORK
    override val defaultPolicy = ToolPolicy.ALLOW
    override val spec = ToolSpec(
        "netlify_sites",
        "List the user's Netlify sites with their URL and last update.",
        ObjectSchema().string("name", "Filter by site name.", required = false).integer("limit", "1–100, default 30.").build(),
    )

    override fun describe(args: JsonObject) = "List your Netlify sites"
    override suspend fun isObservation(args: JsonObject) = true

    override suspend fun execute(args: JsonObject): ToolResult {
        val sites = api.call(
            HttpMethod.Get, "/sites",
            mapOf("filter" to "all", "per_page" to (args.int("limit") ?: 30).coerceIn(1, 100), "name" to args.str("name")?.take(63)),
        ).objects()
        if (sites.isEmpty()) return ToolResult("No sites.")
        return ToolResult(
            sites.joinToString("\n") { s ->
                "- ${s.field("name")} (${s.field("id")}) · ${s.field("ssl_url") ?: s.field("url")}" +
                    (s.field("custom_domain")?.let { " · $it" } ?: "") + " · updated ${s.field("updated_at")?.take(10)}"
            },
        )
    }
}

private class NetlifyDeploys(private val api: NetlifyApi) : Tool {
    override val risk = ToolRisk.NETWORK
    override val defaultPolicy = ToolPolicy.ALLOW
    override val spec = ToolSpec(
        "netlify_deploys",
        "List a Netlify site's recent deploys, or with id show one deploy's state and error.",
        ObjectSchema()
            .string("site", "Site name or ID.")
            .string("id", "Deploy ID to inspect.", required = false)
            .integer("limit", "1–50, default 10.")
            .build(),
    )

    override fun describe(args: JsonObject) = args.str("id")?.let { "Check Netlify deploy $it" } ?: "List deploys of ${args.str("site")}"
    override suspend fun isObservation(args: JsonObject) = true

    override suspend fun execute(args: JsonObject): ToolResult {
        val id = args.str("id")?.trim()?.takeIf { it.isNotEmpty() }
        if (id != null) {
            if (!NetlifyApi.DEPLOY_ID.matches(id)) throw ToolException("id must be a Netlify deploy ID (letters and digits).")
            val d = api.call(HttpMethod.Get, "/deploys/$id").asObj() ?: throw ToolException("Unexpected response from Netlify.")
            val text = buildString {
                append(d.deployLine())
                d.field("title")?.let { append("\nTitle: $it") }
                d.field("admin_url")?.let { append("\nAdmin: $it/deploys/$id") }
            }
            val error = d.field("error_message")
            return ToolResult(text + (error?.let { "\n" + untrustedOutput("Netlify deploy", "Error: ${it.take(4_000)}") } ?: ""))
        }
        val site = api.site(args.str("site").orEmpty())
        val deploys = api.call(HttpMethod.Get, "/sites/${site.field("id")}/deploys", mapOf("per_page" to (args.int("limit") ?: 10).coerceIn(1, 50))).objects()
        if (deploys.isEmpty()) return ToolResult("No deploys yet.")
        val published = site["published_deploy"].asObj()?.field("id")
        return ToolResult(deploys.joinToString("\n") { d -> "- " + d.deployLine() + (if (d.field("id") == published) " · LIVE" else "") })
    }
}

private class NetlifyDeploy(private val api: NetlifyApi, private val workspace: Workspace) : Tool {
    override val risk = ToolRisk.NETWORK
    override val defaultPolicy = ToolPolicy.ASK
    override val spec = ToolSpec(
        "netlify_deploy",
        "Upload a workspace folder of built, static files (e.g. dist or public) to a Netlify site; nothing is built on Netlify. " +
            "Creates a draft deploy with its own URL unless production is true. Only changed files are uploaded. " +
            "Netlify allows 3 API deploys a minute and 100 a day.",
        ObjectSchema()
            .string("site", "Site name or ID.")
            .string("path", "Workspace folder to publish.")
            .boolean("production", "Publish to the live site. Defaults to false (draft URL).")
            .boolean("create_site", "Create the site with this name if it doesn't exist. Defaults to false.")
            .string("title", "Short deploy message.", required = false)
            .build(),
    )

    override fun describe(args: JsonObject) =
        "Deploy ${args.str("path")} to Netlify site ${args.str("site")}" + (if (args.bool("production") == true) " (production)" else " (draft)")

    override suspend fun mustConfirm(args: JsonObject) =
        if (args.bool("production") == true) "This replaces the live Netlify site." else "This creates a new Netlify deploy."

    override suspend fun execute(args: JsonObject): ToolResult {
        val production = args.bool("production") == true
        val folder = collectSiteFiles(workspace, args.str("path").orEmpty())
        folder.files.firstOrNull { '#' in it.path || '?' in it.path }?.let {
            throw ToolException("Netlify can't take file names with # or ?: ${it.path}. Rename it first.")
        }
        val ref = args.str("site").orEmpty().trim()
        val site = if (args.bool("create_site") == true && NetlifyApi.SITE_NAME.matches(ref)) {
            api.findByName(ref) ?: api.call(HttpMethod.Post, "/sites", json = buildJsonObject { put("name", ref) }).asObj()
                ?: throw ToolException("Unexpected response from Netlify.")
        } else {
            api.site(ref)
        }
        val siteId = site.field("id") ?: throw ToolException("Netlify returned a site without an ID.")
        val body = buildJsonObject {
            putJsonObject("files") { folder.files.forEach { put("/" + it.path, it.sha1) } }
            put("draft", !production)
        }
        val deploy = api.call(HttpMethod.Post, "/sites/$siteId/deploys", mapOf("title" to args.str("title")?.take(200)), body).asObj()
            ?: throw ToolException("Unexpected response from Netlify.")
        val deployId = deploy.field("id") ?: throw ToolException("Netlify returned a deploy without an ID.")
        val required = (deploy["required"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty().toSet()
        // Netlify needs each missing SHA-1 once; any file with that content will do.
        val uploads = folder.files.filter { it.sha1 in required }.distinctBy { it.sha1 }
        for (file in uploads) {
            val encoded = file.path.split('/').joinToString("/") { it.encodeURLPathPart() }
            api.call(HttpMethod.Put, "/deploys/$deployId/files/$encoded", bytes = file.bytes())
        }
        val final = if (uploads.isEmpty()) deploy else api.call(HttpMethod.Get, "/deploys/$deployId").asObj() ?: deploy
        val url = if (production) final.field("ssl_url") ?: site.field("ssl_url") else final.field("deploy_ssl_url")
        return ToolResult(
            buildString {
                append(if (production) "Production deploy" else "Draft deploy")
                append(" $deployId to ${site.field("name")}: ${final.field("state")}")
                url?.let { append("\nURL: $it") }
                append("\n${folder.files.size} files from ${folder.path} (${uploads.size} uploaded, the rest unchanged).")
                if (final.field("state") != "ready") append("\nNetlify is still processing; check with netlify_deploys site=${site.field("name")} id=$deployId.")
            },
        )
    }
}

private class NetlifyEnv(private val api: NetlifyApi) : Tool {
    override val risk = ToolRisk.NETWORK
    override val defaultPolicy = ToolPolicy.ALLOW
    override val spec = ToolSpec(
        "netlify_env",
        "List a Netlify site's environment variable names, the deploy contexts that have values, and whether they are secret. Values are never shown.",
        ObjectSchema().string("site", "Site name or ID.").build(),
    )

    override fun describe(args: JsonObject) = "List env variables of ${args.str("site")}"
    override suspend fun isObservation(args: JsonObject) = true

    override suspend fun execute(args: JsonObject): ToolResult {
        val site = api.site(args.str("site").orEmpty())
        val vars = api.call(HttpMethod.Get, "/accounts/${site.field("account_id")}/env", mapOf("site_id" to site.field("id"))).objects()
        if (vars.isEmpty()) return ToolResult("No environment variables.")
        return ToolResult(
            vars.joinToString("\n") { v ->
                val contexts = v["values"].objects().mapNotNull { it.field("context") }.distinct().joinToString(",")
                "- ${v.field("key")} → ${contexts.ifEmpty { "no values" }}" + (if (v.flag("is_secret") == true) " · secret" else "")
            },
        )
    }
}

private class NetlifyEnvSet(private val api: NetlifyApi) : Tool {
    override val risk = ToolRisk.NETWORK
    override val defaultPolicy = ToolPolicy.ASK
    override val spec = ToolSpec(
        "netlify_env_set",
        "Create or update an environment variable on a Netlify site for one deploy context (default all). Takes effect on the next build.",
        ObjectSchema()
            .string("site", "Site name or ID.")
            .string("key", "Variable name (letters, digits, _).")
            .string("value", "Value.")
            .string("context", "all (default), production, deploy-preview, branch-deploy, dev or dev-server.", required = false)
            .boolean("secret", "Mark as secret when creating it (readable only by code running on Netlify). Defaults to false.")
            .build(),
    )

    // The value is left out on purpose so it never appears in the approval card or activity log.
    override fun describe(args: JsonObject) = "Set ${args.str("key")} on Netlify site ${args.str("site")}"

    override suspend fun execute(args: JsonObject): ToolResult {
        val key = args.str("key")?.trim().orEmpty()
        if (!NETLIFY_ENV_KEY.matches(key)) throw ToolException("key may only contain letters, digits and _, and must not start with a digit.")
        val value = args.str("value") ?: throw ToolException("value is required.")
        val context = args.str("context")?.trim()?.lowercase()?.ifEmpty { null } ?: "all"
        if (context !in NETLIFY_CONTEXTS) throw ToolException("context must be one of ${NETLIFY_CONTEXTS.joinToString()}.")
        val site = api.site(args.str("site").orEmpty())
        val account = site.field("account_id") ?: throw ToolException("Netlify didn't say which team owns this site.")
        val params = mapOf("site_id" to site.field("id"))
        val exists = api.call(HttpMethod.Get, "/accounts/$account/env", params).objects().any { it.field("key") == key }
        if (exists) {
            api.call(HttpMethod.Patch, "/accounts/$account/env/$key", params, buildJsonObject { put("context", context); put("value", value) })
        } else {
            val body = buildJsonArray {
                addJsonObject {
                    put("key", key)
                    putJsonArray("values") { addJsonObject { put("value", value); put("context", context) } }
                    if (args.bool("secret") == true) put("is_secret", true)
                }
            }
            api.call(HttpMethod.Post, "/accounts/$account/env", params, body)
        }
        return ToolResult("${if (exists) "Updated" else "Created"} $key for context $context on ${site.field("name")}. It applies to the next deploy.")
    }
}
