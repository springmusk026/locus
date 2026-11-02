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
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Supabase over the Management API (https://supabase.com/docs/reference/api/introduction, OpenAPI at
 * https://api.supabase.com/api/v1-json) with the user's personal access token (vault secret
 * `integration-supabase`). The API allows 120 requests a minute per user, project and endpoint, and
 * its list endpoints are not paginated.
 *
 * SQL goes two ways. `supabase_query` uses POST /v1/projects/{ref}/database/query/read-only, which
 * runs as `supabase_read_only_user`, and [SqlCheck] refuses anything that could leave read-only mode
 * first, so it may be allowed without asking. `supabase_sql` uses POST /v1/projects/{ref}/database/query
 * and always confirms. API key values are never returned to the model.
 */
internal fun supabaseTools(http: HttpClient, secrets: SecretResolver, workspace: Workspace?): List<Tool> {
    val api = SupabaseApi(http, secrets)
    return listOfNotNull(
        SupabaseProjects(api),
        SupabaseQuery(api),
        SupabaseSql(api),
        SupabaseFunctions(api),
        workspace?.let { SupabaseFunctionDeploy(api, it) },
        SupabaseApiKeys(api),
        SupabaseBranches(api),
    )
}

private class SupabaseApi(private val http: HttpClient, private val secrets: SecretResolver) {
    private val json = Json { ignoreUnknownKeys = true }

    /** [permission] names what a scoped token needs for this call, for the 403 message. */
    suspend fun call(
        method: HttpMethod,
        path: String,
        permission: String,
        params: Map<String, Any?> = emptyMap(),
        json: JsonElement? = null,
        form: MultiPartFormDataContent? = null,
    ): JsonElement {
        val token = secrets.resolve(Integrations.supabase.secret)?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ToolException("Supabase needs a personal access token. Ask the user to add one in Settings → Integrations → Supabase.")
        val response = http.request("$BASE$path") {
            this.method = method
            header("Authorization", "Bearer $token")
            params.forEach { (k, v) -> if (v != null) parameter(k, v) }
            when {
                json != null -> { contentType(ContentType.Application.Json); setBody(json.toString()) }
                form != null -> setBody(form)
            }
        }
        val text = response.bodyAsText()
        val status = response.status.value
        val parsed = if (text.isBlank()) JsonObject(emptyMap()) else runCatching { this.json.parseToJsonElement(text) }.getOrNull()
        if (status in 200..299 && parsed != null) return parsed
        val obj = parsed.asObj()
        val message = (obj?.field("message") ?: obj?.field("error") ?: text.take(300)).replace(token, "[token]").take(600)
        throw ToolException(
            when (status) {
                401 -> "Supabase rejected the token (wrong, expired or revoked). Ask the user to check it in Settings → Integrations → Supabase."
                403 -> "Supabase refused: $message. The token needs $permission for this project; a scoped token can be edited or replaced in the Supabase dashboard (Account → Access Tokens)."
                404 -> "Not found on Supabase ($message)."
                402 -> "Supabase plan limit: $message"
                429 -> "Supabase rate limit reached (120 requests a minute per endpoint). Wait a minute and retry."
                else -> "Supabase error $status: $message"
            },
        )
    }

    suspend fun projects(): List<JsonObject> = call(HttpMethod.Get, "/v1/projects", "Projects → Read").objects()

    /** A project ref from a ref or a project name. */
    suspend fun ref(arg: String?): String {
        val p = arg?.trim().orEmpty()
        if (REF.matches(p)) return p
        if (p.isEmpty() || p.length > 256) throw ToolException("project must be a Supabase project ref (20 lowercase letters) or project name.")
        val matches = projects().filter { it.field("name").equals(p, ignoreCase = true) }
        return when (matches.size) {
            1 -> matches[0].field("ref") ?: throw ToolException("Supabase returned a project without a ref.")
            0 -> throw ToolException("No Supabase project named '$p'. List them with supabase_projects.")
            else -> throw ToolException("Several projects are named '$p': ${matches.joinToString { it.field("ref").orEmpty() }}. Use the ref.")
        }
    }

    companion object {
        const val BASE = "https://api.supabase.com"
        val REF = Regex("^[a-z]{20}$")
        val SLUG = Regex("^[A-Za-z][A-Za-z0-9_-]{0,127}$")
    }
}

private fun oneLine(text: String, max: Int) = text.replace(Regex("\\s+"), " ").trim().let { if (it.length > max) it.take(max) + "…" else it }

private class SupabaseProjects(private val api: SupabaseApi) : Tool {
    override val risk = ToolRisk.NETWORK
    override val defaultPolicy = ToolPolicy.ALLOW
    override val spec = ToolSpec(
        "supabase_projects",
        "List the user's Supabase projects with their ref, region, status (INACTIVE means paused) and Postgres version.",
        ObjectSchema().build(),
    )

    override fun describe(args: JsonObject) = "List your Supabase projects"
    override suspend fun isObservation(args: JsonObject) = true

    override suspend fun execute(args: JsonObject): ToolResult {
        val projects = api.projects()
        if (projects.isEmpty()) return ToolResult("No projects.")
        return ToolResult(
            projects.joinToString("\n") { p ->
                val db = p["database"].asObj()
                listOfNotNull(
                    "- ${p.field("name")} (${p.field("ref")})",
                    p.field("status"),
                    p.field("region"),
                    db?.field("version")?.let { "Postgres $it" },
                    p.field("organization_slug")?.let { "org $it" },
                    p.field("created_at")?.take(10)?.let { "created $it" },
                ).joinToString(" · ")
            },
        )
    }
}

/** Rows from the query endpoints (a JSON array of row objects) as one JSON object per line. */
private fun rowsText(result: JsonElement, maxRows: Int): String {
    val rows = result as? JsonArray ?: return oneLine(result.toString(), 4_000)
    if (rows.isEmpty()) return "No rows."
    val shown = rows.take(maxRows).map { oneLine(it.toString(), 2_000) }
    var budget = 20_000
    val kept = shown.takeWhile { line -> (budget - line.length - 1).also { budget = it } >= 0 }
    val more = rows.size - kept.size
    return "${rows.size} row${if (rows.size == 1) "" else "s"}:\n" + kept.joinToString("\n") +
        (if (more > 0) "\n…($more more rows not shown; add a LIMIT or select fewer columns)" else "")
}

private class SupabaseQuery(private val api: SupabaseApi) : Tool {
    override val risk = ToolRisk.NETWORK
    override val defaultPolicy = ToolPolicy.ALLOW
    override val spec = ToolSpec(
        "supabase_query",
        "Run one read-only SQL statement (SELECT, WITH, EXPLAIN, SHOW) on a Supabase project's database as its read-only role. " +
            "Schema-qualify tables (public.todos). Use \$1, \$2 with params for values. To change data or schema, use supabase_sql.",
        ObjectSchema()
            .string("project", "Project ref or name.")
            .string("sql", "One read-only statement.")
            .string("params", "JSON array of values for \$1, \$2…, e.g. [\"abc\", 3].", required = false)
            .integer("max_rows", "Rows to return, 1–500. Defaults to 100.")
            .build(),
    )

    override fun describe(args: JsonObject) = "Query ${args.str("project")}: ${oneLine(args.str("sql").orEmpty(), 120)}"
    override suspend fun isObservation(args: JsonObject) = true

    override suspend fun execute(args: JsonObject): ToolResult {
        val sql = args.str("sql").orEmpty()
        if (sql.length > 100_000) throw ToolException("sql is too long.")
        SqlCheck.readOnlyProblem(sql)?.let { throw ToolException("$it Use supabase_sql for anything that isn't a plain read (it asks the user first).") }
        val ref = api.ref(args.str("project"))
        val body = buildJsonObject {
            put("query", sql)
            sqlParams(args)?.let { put("parameters", it) }
        }
        val result = api.call(HttpMethod.Post, "/v1/projects/$ref/database/query/read-only", "Database → Read", json = body)
        return ToolResult(untrustedOutput("Supabase database", rowsText(result, (args.int("max_rows") ?: 100).coerceIn(1, 500))))
    }
}

private fun sqlParams(args: JsonObject): JsonArray? {
    val raw = args["params"] ?: return null
    if (raw is JsonArray) return raw
    val text = args.str("params")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching { Json.parseToJsonElement(text) as JsonArray }.getOrElse { throw ToolException("params must be a JSON array, e.g. [\"abc\", 3].") }
}

private class SupabaseSql(private val api: SupabaseApi) : Tool {
    override val risk = ToolRisk.NETWORK
    override val defaultPolicy = ToolPolicy.ASK
    override val spec = ToolSpec(
        "supabase_sql",
        "Run SQL that changes a Supabase project's database (INSERT, UPDATE, DELETE, CREATE, ALTER, migrations…) with full rights. " +
            "The user confirms every call. Prefer supabase_query for reads. Returns any rows the statement produces.",
        ObjectSchema()
            .string("project", "Project ref or name.")
            .string("sql", "SQL to run. Several statements run in order.")
            .string("params", "JSON array of values for \$1, \$2…", required = false)
            .build(),
    )

    // The approval card shows the SQL itself so the user sees exactly what runs.
    override fun describe(args: JsonObject) = "Run SQL on ${args.str("project")}: ${oneLine(args.str("sql").orEmpty(), 400)}"

    override suspend fun mustConfirm(args: JsonObject): String {
        val words = SqlCheck.destructiveWords(args.str("sql").orEmpty())
        return if (words.isNotEmpty()) {
            "This SQL can delete or overwrite data in ${args.str("project")} (${words.joinToString(", ")}). It can't be undone."
        } else {
            "This SQL runs with full rights and can change the database of ${args.str("project")}."
        }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val sql = args.str("sql").orEmpty()
        if (sql.isBlank()) throw ToolException("sql is required.")
        if (sql.length > 1_000_000) throw ToolException("sql is too long; split it.")
        val ref = api.ref(args.str("project"))
        val body = buildJsonObject {
            put("query", sql)
            sqlParams(args)?.let { put("parameters", it) }
            put("read_only", false)
        }
        val result = api.call(HttpMethod.Post, "/v1/projects/$ref/database/query", "Database → Read-write", json = body)
        val rows = result as? JsonArray
        if (rows == null || rows.isEmpty()) return ToolResult("Done.")
        return ToolResult("Done.\n" + untrustedOutput("Supabase database", rowsText(rows, 100)))
    }
}

private class SupabaseFunctions(private val api: SupabaseApi) : Tool {
    override val risk = ToolRisk.NETWORK
    override val defaultPolicy = ToolPolicy.ALLOW
    override val spec = ToolSpec(
        "supabase_functions",
        "List a Supabase project's Edge Functions with their status, version and whether they require a JWT.",
        ObjectSchema().string("project", "Project ref or name.").build(),
    )

    override fun describe(args: JsonObject) = "List Edge Functions of ${args.str("project")}"
    override suspend fun isObservation(args: JsonObject) = true

    override suspend fun execute(args: JsonObject): ToolResult {
        val ref = api.ref(args.str("project"))
        val functions = api.call(HttpMethod.Get, "/v1/projects/$ref/functions", "Edge Functions → Read").objects()
        if (functions.isEmpty()) return ToolResult("No Edge Functions.")
        return ToolResult(
            functions.joinToString("\n") { f ->
                listOfNotNull(
                    "- ${f.field("slug")}",
                    f.field("status"),
                    f.field("version")?.let { "v$it" },
                    if (f.flag("verify_jwt") == false) "public (no JWT)" else "JWT required",
                    millisToText(f.longField("updated_at"))?.let { "updated $it" },
                ).joinToString(" · ") + "\n  https://$ref.supabase.co/functions/v1/${f.field("slug")}"
            },
        )
    }
}

private class SupabaseFunctionDeploy(private val api: SupabaseApi, private val workspace: Workspace) : Tool {
    override val risk = ToolRisk.NETWORK
    override val defaultPolicy = ToolPolicy.ASK
    override val spec = ToolSpec(
        "supabase_function_deploy",
        "Deploy a workspace folder as a Supabase Edge Function (Deno/TypeScript); Supabase bundles it. Creates the function if it doesn't exist. " +
            "The folder must hold everything the entrypoint imports (relative imports outside it won't resolve). Goes live immediately.",
        ObjectSchema()
            .string("project", "Project ref or name.")
            .string("slug", "Function name in the URL (letters, digits, - and _; starts with a letter).")
            .string("path", "Workspace folder of the function, e.g. supabase/functions/hello.")
            .string("entrypoint", "Entry file inside the folder. Defaults to index.ts.", required = false)
            .boolean("verify_jwt", "Require a valid JWT to call it. Defaults to true.")
            .build(),
    )

    override fun describe(args: JsonObject) = "Deploy ${args.str("path")} as Edge Function ${args.str("slug")} on ${args.str("project")}"

    override suspend fun mustConfirm(args: JsonObject) =
        "This deploys Edge Function ${args.str("slug")} to ${args.str("project")} and it goes live immediately" +
            if (args.bool("verify_jwt") == false) ", callable by anyone without a key." else "."

    override suspend fun execute(args: JsonObject): ToolResult {
        val slug = args.str("slug")?.trim().orEmpty()
        if (!SupabaseApi.SLUG.matches(slug)) throw ToolException("slug must start with a letter and use only letters, digits, - and _.")
        val folder = collectSiteFiles(workspace, args.str("path").orEmpty(), maxFiles = 500, maxBytes = 20L * 1024 * 1024)
        val entry = args.str("entrypoint")?.trim()?.removePrefix("./")?.takeIf { it.isNotEmpty() } ?: "index.ts"
        if (folder.files.none { it.path == entry }) throw ToolException("No $entry in ${folder.path}. Set entrypoint to the function's main file.")
        folder.files.firstOrNull { f -> f.path.any { it == '"' || it == '\\' || it.isISOControl() } }?.let {
            throw ToolException("Rename ${it.path}: file names can't contain quotes, backslashes or control characters.")
        }
        val ref = api.ref(args.str("project"))
        val verifyJwt = args.bool("verify_jwt") != false
        val metadata = buildJsonObject {
            put("entrypoint_path", entry)
            put("name", slug)
            put("verify_jwt", verifyJwt)
        }
        val form = MultiPartFormDataContent(
            formData {
                append("metadata", metadata.toString())
                folder.files.forEach { f ->
                    append(
                        "file", f.bytes(),
                        Headers.build {
                            append(HttpHeaders.ContentType, "application/octet-stream")
                            append(HttpHeaders.ContentDisposition, "form-data; name=\"file\"; filename=\"${f.path}\"")
                        },
                    )
                }
            },
        )
        val fn = api.call(HttpMethod.Post, "/v1/projects/$ref/functions/deploy", "Edge Functions → Read-write", params = mapOf("slug" to slug), form = form).asObj()
            ?: throw ToolException("Unexpected response from Supabase.")
        return ToolResult(
            "Deployed ${fn.field("slug") ?: slug} (version ${fn.field("version") ?: "?"}, ${fn.field("status") ?: "unknown status"}) from ${folder.path} " +
                "(${folder.files.size} files).\nURL: https://$ref.supabase.co/functions/v1/$slug" +
                (if (verifyJwt) "\nCallers need a JWT (for example the project's publishable or anon key) in the Authorization header." else ""),
        )
    }
}

private class SupabaseApiKeys(private val api: SupabaseApi) : Tool {
    override val risk = ToolRisk.NETWORK
    override val defaultPolicy = ToolPolicy.ALLOW
    override val spec = ToolSpec(
        "supabase_api_keys",
        "List a Supabase project's API key names and types (publishable, secret, legacy). Key values are never shown; " +
            "the user copies them from the dashboard link this returns.",
        ObjectSchema().string("project", "Project ref or name.").build(),
    )

    override fun describe(args: JsonObject) = "List API keys of ${args.str("project")}"
    override suspend fun isObservation(args: JsonObject) = true

    override suspend fun execute(args: JsonObject): ToolResult {
        val ref = api.ref(args.str("project"))
        // No `reveal` parameter, and only names and types are read from the reply: values never reach the model.
        val keys = api.call(HttpMethod.Get, "/v1/projects/$ref/api-keys", "API Keys → Read").objects()
        val link = "Values: https://supabase.com/dashboard/project/$ref/settings/api-keys"
        if (keys.isEmpty()) return ToolResult("No API keys.\n$link")
        return ToolResult(
            keys.joinToString("\n") { k ->
                listOfNotNull("- ${k.field("name")}", k.field("type"), k.field("id")?.let { "id $it" }, k.field("description")?.takeIf { it.isNotBlank() }?.let { oneLine(it, 120) })
                    .joinToString(" · ")
            } + "\n$link",
        )
    }
}

private class SupabaseBranches(private val api: SupabaseApi) : Tool {
    override val risk = ToolRisk.NETWORK
    override val defaultPolicy = ToolPolicy.ALLOW
    override val spec = ToolSpec(
        "supabase_branches",
        "List a Supabase project's database branches (preview and persistent) with their own project ref, Git branch and status.",
        ObjectSchema().string("project", "Project ref or name.").build(),
    )

    override fun describe(args: JsonObject) = "List branches of ${args.str("project")}"
    override suspend fun isObservation(args: JsonObject) = true

    override suspend fun execute(args: JsonObject): ToolResult {
        val ref = api.ref(args.str("project"))
        val branches = api.call(HttpMethod.Get, "/v1/projects/$ref/branches", "Branches → Read").objects()
        if (branches.isEmpty()) return ToolResult("No branches (branching may be off for this project).")
        return ToolResult(
            branches.joinToString("\n") { b ->
                listOfNotNull(
                    "- ${b.field("name")} (${b.field("project_ref")})",
                    if (b.flag("is_default") == true) "default" else null,
                    if (b.flag("persistent") == true) "persistent" else "preview",
                    b.field("git_branch")?.let { "git $it" },
                    b.field("preview_project_status"),
                    b.field("pr_number")?.let { "PR #$it" },
                ).joinToString(" · ")
            },
        )
    }
}
