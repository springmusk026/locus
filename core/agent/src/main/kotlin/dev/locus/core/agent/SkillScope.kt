package dev.locus.core.agent

import dev.locus.core.agent.home.SkillManifest
import dev.locus.core.model.AgentControlTools
import dev.locus.core.model.LocalNetwork
import dev.locus.core.model.Message
import dev.locus.core.model.Role
import dev.locus.core.model.SubagentTools
import dev.locus.core.model.ToolCapability
import kotlinx.serialization.json.JsonObject

/** Looks up an active skill's manifest by name; null when there's no such skill. */
fun interface SkillManifests {
    suspend fun manifest(name: String): SkillManifest?
}

/** The skills a run has loaded, handed to the helpers it starts with the lookup for their manifests. */
data class SkillSeed(val active: Set<String>, val manifests: SkillManifests)

/**
 * Holds a run to the manifests of the skills it has loaded (docs/design/skill-manifests.md). Once a
 * skill that declares a manifest is active in a chat (its `skill_use` ran), a call outside what it
 * declares (a tool group it didn't list, or a host missing from its `network`) goes to Ask with "not
 * declared by skill X". Skills without a manifest add nothing, so they keep the user's normal
 * permissions.
 *
 * Like [Taint] it is a policy decision only: the tool list and the system prompt never change.
 */
object SkillScope {

    /** Tool groups a manifest's `capabilities` may name, with what each lets a skill do, in plain words. */
    val GROUPS: Map<String, String> = linkedMapOf(
        "files.read" to "Can read files in your workspace",
        "files.write" to "Can create, change and delete files in your workspace",
        "linux" to "Can run Linux commands. Commands can reach any host; the network list can't limit them",
        "web" to "Can search the web and look things up",
        "browser" to "Can use the browser, including clicking and typing on pages",
        "ssh" to "Can run commands on your SSH machines",
        "memory" to "Can read and change what Locus remembers",
        "mcp" to "Can use any MCP server you've added",
        "connectors" to "Can call any connector you've added",
        "library" to "Can draft new skills, connectors and helpers",
        "helpers" to "Can start helpers",
        "email" to "Can read and send your email",
        "github" to "Can use your GitHub account",
        "netlify" to "Can use your Netlify account, including deploys",
        "vercel" to "Can use your Vercel account, including deploys",
        "cloudflare" to "Can use your Cloudflare account, including DNS and deploys",
        "supabase" to "Can use your Supabase projects, including running SQL",
        "render" to "Can use your Render account, including deploys",
        "device" to "Can hand things to other apps on your phone (calendar, messages, maps, sharing)",
        "notify" to "Can send you notifications",
    )

    /** Groups that may be narrowed to one id: `mcp:<connector>`, `connector:<id>`, `ssh:<host alias>`. */
    val SCOPED_GROUPS = setOf("mcp", "connector", "ssh")

    /** Calls every skill may make: loading skills, and Locus's own planning and conversation tools. */
    private val BASELINE = setOf(
        "skill_use", "skill_read_file",
        AgentControlTools.TODO_WRITE, AgentControlTools.PLAN_PROPOSE, AgentControlTools.ASK_USER,
        "chat_history_search", "chat_history_read",
        SubagentTools.WAIT, SubagentTools.STATUS, SubagentTools.CANCEL,
    )

    /** Calls whose address Locus sees, so `network` applies to them. */
    private val URL_TOOLS = setOf("fetch_url", "web_read", "browser_open", "device_open_url")

    private val BY_NAME: Map<String, String> = buildMap {
        listOf("list_files", "read_file", "search_files", "find_files", "code_outline", "project_info", "git_inspect", "send_file").forEach { put(it, "files.read") }
        listOf("write_file", "edit_file", "multi_edit", "move_path", "copy_path", "delete_path", "make_directory", "git_commit").forEach { put(it, "files.write") }
        listOf("run_command").forEach { put(it, "linux") }
        listOf("web_search", "web_read", "fetch_url", "wikipedia", "weather").forEach { put(it, "web") }
        listOf("mcp_call", "mcp_list_tools").forEach { put(it, "mcp") }
        put("connector_call", "connectors")
        listOf("skill_create", "connector_create", SubagentTools.CREATE).forEach { put(it, "library") }
        listOf(SubagentTools.SPAWN, SubagentTools.SEND).forEach { put(it, "helpers") }
        put("notify_me", "notify")
    }

    private val BY_PREFIX = listOf(
        "linux_service_" to "linux", "browser_" to "browser", "ssh_" to "ssh", "sftp_" to "ssh", "memory_" to "memory",
        "email_" to "email", "github_" to "github", "netlify_" to "netlify", "vercel_" to "vercel", "cloudflare_" to "cloudflare",
        "supabase_" to "supabase", "render_" to "render", "device_" to "device",
    )

    /** Every built-in tool name, which a manifest may also list one at a time. */
    fun knownTools(): Set<String> = ToolCapability.BY_TOOL.keys

    /** Whether every skill may call [toolName] (see [BASELINE]). */
    fun isBaseline(toolName: String): Boolean = toolName in BASELINE

    /** The group a tool belongs to; null for one that can only be declared by name. */
    fun groupOf(toolName: String): String? = BY_NAME[toolName] ?: BY_PREFIX.firstOrNull { toolName.startsWith(it.first) }?.second

    /** One capability in plain words, for the review sheet. */
    fun describe(capability: String): String {
        val id = capability.substringAfter(':', "")
        return when {
            capability.startsWith("mcp:") -> "Can use the MCP server “$id”"
            capability.startsWith("connector:") -> "Can call the connector “$id”"
            capability.startsWith("ssh:") -> "Can run commands on the SSH machine “$id”"
            else -> GROUPS[capability] ?: "Can use the tool $capability"
        }
    }

    /** One `network` entry in plain words. */
    fun describeHost(host: String): String = when {
        host == "*" -> "Can contact any website"
        host.startsWith("*.") -> "Can contact ${host.removePrefix("*.")} and its subdomains"
        else -> "Can contact $host"
    }

    /** [host] is allowed by a `network` entry: "*", the same host, or "*.example.com" for it and its subdomains. */
    fun hostAllowed(host: String, network: List<String>): Boolean {
        val h = host.lowercase().trimEnd('.')
        return network.any { p ->
            p == "*" || p == h || (p.startsWith("*.") && (h == p.removePrefix("*.") || h.endsWith(p.removePrefix("*"))))
        }
    }

    /** The skills loaded in [history]: every `skill_use` that ran and returned the skill's content. */
    fun activeIn(history: List<Message>): Set<String> = buildSet {
        history.forEach { m -> if (m.role == Role.TOOL) m.toolResults.forEach { r -> if (r.toolName == "skill_use" && !r.isError) loadedName(r.content)?.let(::add) } }
    }

    /** The name in a `skill_use` result, or null for an error or other text. */
    fun loadedName(content: String): String? = LOADED.find(content)?.groupValues?.get(1)

    private val LOADED = Regex("^<skill_content name=\"([a-z0-9-]{1,64})\">")

    /**
     * What a call to [toolName] with [args] needs that [manifest] doesn't declare, in plain words, or
     * null when it's within the manifest (or the manifest is undeclared).
     */
    fun missing(toolName: String, args: JsonObject, manifest: SkillManifest): String? {
        if (!manifest.declared || isBaseline(toolName)) return null
        val caps = manifest.capabilities.toSet()
        if (toolName in URL_TOOLS) {
            val host = args.str("url")?.let(LocalNetwork::hostOf)?.lowercase()
            if (host == null || !hostAllowed(host, manifest.network)) return if (host == null) "an address it didn't list" else "the host $host"
            // Pages and fetches are covered by the host itself; the browser and other apps also need their group.
            return when (toolName) {
                "browser_open" -> if ("browser" in caps || toolName in caps) null else "the browser"
                "device_open_url" -> if ("device" in caps || toolName in caps) null else "other apps on your phone"
                else -> null
            }
        }
        if (toolName in caps) return null
        val group = groupOf(toolName)
        val scoped = when (group) {
            "mcp" -> args.str("connector")?.let { "mcp:$it" }
            "connectors" -> args.str("connector")?.let { "connector:$it" }
            "ssh" -> args.str("host")?.let { "ssh:$it" }
            else -> null
        }
        if (group != null && group in caps) return null
        if (scoped != null && scoped.lowercase() in caps) return null
        return when {
            scoped != null -> describe(scoped).replaceFirst("Can ", "").let { "permission to ${it.replaceFirstChar(Char::lowercaseChar)}" }
            group != null -> "“$group” ($toolName)"
            else -> toolName
        }
    }

    /**
     * Why [toolName] must be confirmed because an active skill doesn't declare it, or null. [skills]
     * are the run's active skills with their manifests; each declared one must cover the call (fail
     * closed: their instructions are all in the model's context, so any of them could be steering it).
     */
    fun reason(toolName: String, args: JsonObject, skills: List<Pair<String, SkillManifest>>): String? {
        val outside = skills.mapNotNull { (name, m) -> missing(toolName, args, m)?.let { name to it } }
        if (outside.isEmpty()) return null
        val names = outside.map { "“${it.first}”" }
        val who = if (names.size == 1) "skill ${names.single()}" else "skills ${names.dropLast(1).joinToString()} and ${names.last()}"
        return "Not declared by $who: its manifest doesn't include ${outside.first().second}."
    }
}
