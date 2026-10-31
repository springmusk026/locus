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
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
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
 * Cloudflare over its v4 API (https://developers.cloudflare.com/api/) with a user API token (vault
 * secret `integration-cloudflare`). The vault value is the token, optionally followed by a space and
 * the account ID that Pages calls use; without it the token's only account is used.
 *
 * Covers DNS (zones, records) and Cloudflare Pages (projects, deployments, build logs, and starting a
 * Git-based deployment). Pages Direct Upload is not implemented: it needs a separate upload JWT and
 * Cloudflare's own content hashing, which the agent can do more reliably with `wrangler` in Linux.
 */
internal fun cloudflareTools(http: HttpClient, secrets: SecretResolver): List<Tool> {
    val api = CloudflareApi(http, secrets)
    return listOf(CloudflareZones(api), CloudflareDns(api), CloudflareDnsSet(api), CloudflareDnsDelete(api), CloudflarePages(api), CloudflarePagesDeploy(api))
}

private class CloudflareApi(private val http: HttpClient, private val secrets: SecretResolver) {
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun credentials(): Pair<String, String?> {
        val value = secrets.resolve(Integrations.cloudflare.secret)?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ToolException("Cloudflare needs an API token. Ask the user to add one in Settings → Integrations → Cloudflare.")
        val parts = value.split(Regex("\\s+"))
        return parts[0] to parts.getOrNull(1)?.takeIf { HEX32.matches(it) }
    }

    /** Returns the whole envelope (`result`, `result_info`) of a successful call. */
    suspend fun call(method: HttpMethod, path: String, params: Map<String, Any?> = emptyMap(), json: JsonElement? = null, form: MultiPartFormDataContent? = null): JsonObject {
        val (token, _) = credentials()
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
        val parsed = runCatching { this.json.parseToJsonElement(text) }.getOrNull().asObj()
        if (status in 200..299 && parsed != null && parsed.flag("success") != false) return parsed
        val message = parsed?.get("errors").objects().joinToString("; ") { e -> "${e.field("message")} (${e.field("code")})" }.ifEmpty { text.take(300) }
        throw ToolException(
            when (status) {
                401 -> "Cloudflare rejected the token ($message). Ask the user to check it in Settings → Integrations → Cloudflare."
                403 -> "Cloudflare refused: $message. The token may lack the permission for this (DNS needs Zone → DNS Edit; Pages needs Account → Cloudflare Pages Edit)."
                404 -> "Not found on Cloudflare ($message)."
                429 -> "Cloudflare rate limit reached (1,200 requests per 5 minutes). Wait a few minutes and retry."
                else -> "Cloudflare error $status: $message"
            },
        )
    }

    suspend fun accountId(): String {
        credentials().second?.let { return it }
        val accounts = call(HttpMethod.Get, "/accounts", mapOf("per_page" to 5))["result"].objects()
        return when (accounts.size) {
            1 -> accounts.single().field("id") ?: throw ToolException("Cloudflare returned an account without an ID.")
            0 -> throw ToolException("The Cloudflare token can't see any account. It needs an Account permission such as Cloudflare Pages Read.")
            else -> throw ToolException(
                "The token can see several Cloudflare accounts (${accounts.joinToString { "${it.field("name")}: ${it.field("id")}" }}). " +
                    "Ask the user to add the account ID after the token in Settings → Integrations → Cloudflare.",
            )
        }
    }

    /** A zone ID from an ID or a domain name. */
    suspend fun zone(ref: String?): Pair<String, String> {
        val r = ref?.trim()?.lowercase()?.removeSuffix(".").orEmpty()
        if (HEX32.matches(r)) {
            val z = call(HttpMethod.Get, "/zones/$r")["result"].asObj()
            return r to (z?.field("name") ?: r)
        }
        if (!DOMAIN.matches(r)) throw ToolException("zone must be a domain such as example.com, or a zone ID.")
        val z = call(HttpMethod.Get, "/zones", mapOf("name" to r))["result"].objects().firstOrNull()
            ?: throw ToolException("No Cloudflare zone named $r is visible to the token. List zones with cloudflare_zones.")
        return (z.field("id") ?: throw ToolException("Cloudflare returned a zone without an ID.")) to r
    }

    companion object {
        const val BASE = "https://api.cloudflare.com/client/v4"
        val HEX32 = Regex("^[0-9a-f]{32}$")
        val DOMAIN = Regex("^(?=.{1,253}$)([a-z0-9_](?:[a-z0-9_-]{0,61}[a-z0-9])?\\.)+[a-z0-9-]{2,63}$")
    }
}

private val RECORD_TYPES = setOf("A", "AAAA", "CNAME", "TXT", "MX")
private val PAGES_PROJECT = Regex("^[a-z0-9][a-z0-9-]{0,62}$")
private val PAGES_DEPLOYMENT = Regex("^[A-Za-z0-9-]{1,64}$")
private val RECORD_ID = Regex("^[0-9a-f]{32}$")

/** "@", "www" or "www.example.com" → the full name inside [zone]. */
private fun recordName(name: String?, zone: String): String {
    val n = name?.trim()?.lowercase()?.removeSuffix(".").orEmpty()
    val full = when {
        n.isEmpty() || n == "@" -> zone
        n == zone || n.endsWith(".$zone") -> n
        else -> "$n.$zone"
    }
    if (!Regex("^[a-z0-9_*.-]{1,253}$").matches(full) || ".." in full) throw ToolException("Invalid record name '$name'.")
    return full
}

private fun JsonObject.recordLine(): String {
    val ttl = longField("ttl")?.let { if (it == 1L) "auto" else "${it}s" }
    return "- ${field("type")} ${field("name")} → ${field("content")}" +
        (field("priority")?.let { " (priority $it)" } ?: "") +
        (if (flag("proxied") == true) " · proxied" else "") + " · ttl $ttl · id ${field("id")}" +
        (field("comment")?.let { " · “${it.take(80)}”" } ?: "")
}

private class CloudflareZones(private val api: CloudflareApi) : Tool {
    override val risk = ToolRisk.NETWORK
    override val defaultPolicy = ToolPolicy.ALLOW
    override val spec = ToolSpec("cloudflare_zones", "List the domains (zones) on the user's Cloudflare account.", ObjectSchema().integer("limit", "1–50, default 20.").build())

    override fun describe(args: JsonObject) = "List your Cloudflare domains"
    override suspend fun isObservation(args: JsonObject) = true

    override suspend fun execute(args: JsonObject): ToolResult {
        val zones = api.call(HttpMethod.Get, "/zones", mapOf("per_page" to (args.int("limit") ?: 20).coerceIn(5, 50)))["result"].objects()
        if (zones.isEmpty()) return ToolResult("No zones visible to this token.")
        return ToolResult(zones.joinToString("\n") { z -> "- ${z.field("name")} · ${z.field("status")} · ${z["plan"].asObj()?.field("name") ?: "?"} · id ${z.field("id")}" })
    }
}

private class CloudflareDns(private val api: CloudflareApi) : Tool {
    override val risk = ToolRisk.NETWORK
    override val defaultPolicy = ToolPolicy.ALLOW
    override val spec = ToolSpec(
        "cloudflare_dns",
        "List DNS records of a Cloudflare zone, optionally only one type or name.",
        ObjectSchema()
            .string("zone", "Domain (example.com) or zone ID.")
            .string("type", "Only this record type, e.g. A, CNAME, TXT.", required = false)
            .string("name", "Only this name: @, a subdomain such as www, or the full name.", required = false)
            .build(),
    )

    override fun describe(args: JsonObject) = "List DNS records of ${args.str("zone")}"
    override suspend fun isObservation(args: JsonObject) = true

    override suspend fun execute(args: JsonObject): ToolResult {
        val (zoneId, zoneName) = api.zone(args.str("zone"))
        val type = args.str("type")?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        if (type != null && !Regex("^[A-Z]{1,10}$").matches(type)) throw ToolException("Invalid record type.")
        val name = args.str("name")?.takeIf { it.isNotBlank() }?.let { recordName(it, zoneName) }
        val records = api.call(HttpMethod.Get, "/zones/$zoneId/dns_records", mapOf("type" to type, "name.exact" to name, "per_page" to 100))["result"].objects()
        if (records.isEmpty()) return ToolResult("No matching records in $zoneName.")
        // TXT values are free text that third parties (verification services) often supply.
        return ToolResult(untrustedOutput("DNS record", "$zoneName:\n" + records.joinToString("\n") { it.recordLine() }))
    }
}

private class CloudflareDnsSet(private val api: CloudflareApi) : Tool {
    override val risk = ToolRisk.NETWORK
    override val defaultPolicy = ToolPolicy.ASK
    override val spec = ToolSpec(
        "cloudflare_dns_set",
        "Create a DNS record in a Cloudflare zone, or change one: with record_id that record is updated; an existing CNAME on the same name is replaced; " +
            "otherwise a new record is added (names can hold several A, AAAA, TXT or MX records). Types: A, AAAA, CNAME, TXT, MX.",
        ObjectSchema()
            .string("zone", "Domain (example.com) or zone ID.")
            .string("type", "A, AAAA, CNAME, TXT or MX.")
            .string("name", "@ for the domain itself, a subdomain such as www, or the full name.")
            .string("content", "IPv4/IPv6 address, target host name, or text.")
            .boolean("proxied", "Route through Cloudflare (A, AAAA, CNAME only). Defaults to false.")
            .integer("ttl", "Seconds (60–86400); 1 or empty means automatic.")
            .integer("priority", "MX priority (default 10).")
            .string("record_id", "Update this record instead of matching by type and name.", required = false)
            .string("comment", "Note stored on the record.", required = false)
            .build(),
    )

    override fun describe(args: JsonObject) =
        "Set DNS ${args.str("type")?.uppercase()} ${args.str("name")} → ${args.str("content")?.take(80)} in ${args.str("zone")}"

    override suspend fun mustConfirm(args: JsonObject) =
        if (args.str("record_id") != null) "This changes an existing DNS record; a wrong value can take the site or email offline." else null

    override suspend fun execute(args: JsonObject): ToolResult {
        val type = args.str("type")?.trim()?.uppercase().orEmpty()
        if (type !in RECORD_TYPES) throw ToolException("type must be one of ${RECORD_TYPES.joinToString()}.")
        val content = args.str("content")?.trim().orEmpty()
        if (content.isEmpty() || content.length > 4_096) throw ToolException("content is required (up to 4096 characters).")
        val (zoneId, zoneName) = api.zone(args.str("zone"))
        val name = recordName(args.str("name"), zoneName)
        val proxied = args.bool("proxied") == true
        if (proxied && type !in setOf("A", "AAAA", "CNAME")) throw ToolException("Only A, AAAA and CNAME records can be proxied.")
        val ttl = args.int("ttl")?.takeIf { it != 1 }?.coerceIn(60, 86_400) ?: 1
        val body = buildJsonObject {
            put("type", type)
            put("name", name)
            put("content", content)
            put("ttl", ttl)
            if (type in setOf("A", "AAAA", "CNAME")) put("proxied", proxied)
            if (type == "MX") put("priority", (args.int("priority") ?: 10).coerceIn(0, 65_535))
            args.str("comment")?.takeIf { it.isNotBlank() }?.let { put("comment", it.take(100)) }
        }
        val explicit = args.str("record_id")?.trim()?.takeIf { it.isNotEmpty() }
        if (explicit != null && !RECORD_ID.matches(explicit)) throw ToolException("record_id must be a 32-character Cloudflare record ID.")
        // A name can hold only one CNAME, so an existing one is replaced. A/AAAA (round robin), TXT and MX
        // often have several records per name, so those are added unless record_id says which to change.
        val target = explicit ?: run {
            if (type != "CNAME") return@run null
            api.call(HttpMethod.Get, "/zones/$zoneId/dns_records", mapOf("type" to type, "name.exact" to name))["result"].objects()
                .singleOrNull()?.field("id")
        }
        val record = if (target != null) {
            api.call(HttpMethod.Patch, "/zones/$zoneId/dns_records/$target", json = body)
        } else {
            api.call(HttpMethod.Post, "/zones/$zoneId/dns_records", json = body)
        }["result"].asObj() ?: throw ToolException("Unexpected response from Cloudflare.")
        return ToolResult("${if (target != null) "Updated" else "Created"} ${record.recordLine().removePrefix("- ")}")
    }
}

private class CloudflareDnsDelete(private val api: CloudflareApi) : Tool {
    override val risk = ToolRisk.NETWORK
    override val defaultPolicy = ToolPolicy.ASK
    override val spec = ToolSpec(
        "cloudflare_dns_delete",
        "Delete one DNS record from a Cloudflare zone by its ID (get IDs from cloudflare_dns). This can't be undone.",
        ObjectSchema().string("zone", "Domain (example.com) or zone ID.").string("record_id", "Record ID.").build(),
    )

    override fun describe(args: JsonObject) = "Delete DNS record ${args.str("record_id")} from ${args.str("zone")}"
    override suspend fun mustConfirm(args: JsonObject) = "Deleting a DNS record can take a site or email offline and can't be undone."

    override suspend fun execute(args: JsonObject): ToolResult {
        val id = args.str("record_id")?.trim().orEmpty()
        if (!RECORD_ID.matches(id)) throw ToolException("record_id must be a 32-character Cloudflare record ID.")
        val (zoneId, zoneName) = api.zone(args.str("zone"))
        val record = api.call(HttpMethod.Get, "/zones/$zoneId/dns_records/$id")["result"].asObj()
        api.call(HttpMethod.Delete, "/zones/$zoneId/dns_records/$id")
        return ToolResult("Deleted from $zoneName: ${record?.recordLine()?.removePrefix("- ") ?: id}")
    }
}

private class CloudflarePages(private val api: CloudflareApi) : Tool {
    override val risk = ToolRisk.NETWORK
    override val defaultPolicy = ToolPolicy.ALLOW
    override val spec = ToolSpec(
        "cloudflare_pages",
        "Cloudflare Pages: without project, list projects; with project, list its recent deployments; " +
            "with deployment_id too, show the end of that deployment's build log.",
        ObjectSchema()
            .string("project", "Pages project name.", required = false)
            .string("deployment_id", "Deployment ID for its build log.", required = false)
            .integer("limit", "Deployments to list (1–25, default 10) or log lines (default 60, max 300).")
            .build(),
    )

    override fun describe(args: JsonObject) = args.str("project")?.let { "Check Pages project $it" } ?: "List Cloudflare Pages projects"
    override suspend fun isObservation(args: JsonObject) = true

    override suspend fun execute(args: JsonObject): ToolResult {
        val account = api.accountId()
        val project = args.str("project")?.trim()?.takeIf { it.isNotEmpty() }
        if (project == null) {
            val projects = api.call(HttpMethod.Get, "/accounts/$account/pages/projects")["result"].objects()
            if (projects.isEmpty()) return ToolResult("No Pages projects.")
            return ToolResult(
                projects.joinToString("\n") { p ->
                    val latest = p["latest_deployment"].asObj()
                    "- ${p.field("name")} · https://${p.field("subdomain")} · production branch ${p.field("production_branch") ?: "?"}" +
                        (latest?.let { " · latest: ${it["latest_stage"].asObj()?.let { s -> "${s.field("name")} ${s.field("status")}" }} ${it.field("environment")}" } ?: "")
                },
            )
        }
        if (!PAGES_PROJECT.matches(project)) throw ToolException("project must be a Pages project name.")
        val deployment = args.str("deployment_id")?.trim()?.takeIf { it.isNotEmpty() }
        if (deployment != null) {
            if (!PAGES_DEPLOYMENT.matches(deployment)) throw ToolException("Invalid deployment_id.")
            val lines = (args.int("limit") ?: 60).coerceIn(10, 300)
            val log = api.call(HttpMethod.Get, "/accounts/$account/pages/projects/$project/deployments/$deployment/history/logs")["result"].asObj()
            val text = log?.get("data").objects().mapNotNull { it.field("line") }
            return ToolResult(untrustedOutput("Cloudflare Pages build", if (text.isEmpty()) "No build log." else tail(text, lines)))
        }
        val deployments = api.call(HttpMethod.Get, "/accounts/$account/pages/projects/$project/deployments", mapOf("per_page" to (args.int("limit") ?: 10).coerceIn(1, 25)))["result"].objects()
        if (deployments.isEmpty()) return ToolResult("No deployments yet.")
        return ToolResult(
            deployments.joinToString("\n") { d ->
                val stage = d["latest_stage"].asObj()
                val branch = d["deployment_trigger"].asObj()?.get("metadata").asObj()?.field("branch")
                listOfNotNull(
                    "- ${d.field("id")}", d.field("environment"), branch, stage?.let { "${it.field("name")} ${it.field("status")}" },
                    d.field("url"), d.field("created_on")?.take(16)?.replace('T', ' '),
                ).joinToString(" · ")
            },
        )
    }
}

private class CloudflarePagesDeploy(private val api: CloudflareApi) : Tool {
    override val risk = ToolRisk.NETWORK
    override val defaultPolicy = ToolPolicy.ASK
    override val spec = ToolSpec(
        "cloudflare_pages_deploy",
        "Start a new build and deployment of a Git-connected Cloudflare Pages project from the head of a branch " +
            "(default: the production branch, which updates the live site). Doesn't upload workspace files.",
        ObjectSchema()
            .string("project", "Pages project name.")
            .string("branch", "Git branch to build. Defaults to the production branch.", required = false)
            .build(),
    )

    override fun describe(args: JsonObject) = "Deploy Pages project ${args.str("project")}" + (args.str("branch")?.let { " from $it" } ?: " (production)")
    override suspend fun mustConfirm(args: JsonObject) = "This builds and deploys the Cloudflare Pages project" + (if (args.str("branch") == null) ", updating the live site." else ".")

    override suspend fun execute(args: JsonObject): ToolResult {
        val project = args.str("project")?.trim().orEmpty()
        if (!PAGES_PROJECT.matches(project)) throw ToolException("project must be a Pages project name.")
        val branch = args.str("branch")?.trim()?.takeIf { it.isNotEmpty() }
        if (branch != null && !Regex("^[A-Za-z0-9._/-]{1,200}$").matches(branch)) throw ToolException("Invalid branch name.")
        val account = api.accountId()
        val form = MultiPartFormDataContent(formData { branch?.let { append("branch", it) } })
        val d = api.call(HttpMethod.Post, "/accounts/$account/pages/projects/$project/deployments", form = form)["result"].asObj()
            ?: throw ToolException("Unexpected response from Cloudflare.")
        val stage = d["latest_stage"].asObj()
        return ToolResult(
            "Started deployment ${d.field("id")} (${d.field("environment")}): ${stage?.field("name")} ${stage?.field("status")}\nURL: ${d.field("url")}\n" +
                "Check progress with cloudflare_pages project=$project (deployment_id=${d.field("id")} for the build log).",
        )
    }
}
