package dev.locus.core.agent

import dev.locus.core.model.AgentControlTools
import dev.locus.core.model.DiscoveryTools
import dev.locus.core.model.Part
import dev.locus.core.model.SubagentTools
import dev.locus.core.model.ToolRisk
import dev.locus.core.model.ToolSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.math.ln

/**
 * Tool discovery (docs/design/tool-discovery.md). A chat created with it declares a small core set of
 * tools plus [DiscoveryTools.FIND_TOOLS] and [DiscoveryTools.CALL_TOOL], and nothing else, on every
 * provider. The model finds the rest with find_tools (BM25 over names, descriptions and arguments) and
 * runs one with call_tool, which [ToolRegistry.resolve] turns into that tool's own call before the
 * agent loop checks anything: policy, approval, taint, the reviewer, budgets and change tracking all
 * see the real tool, exactly as if the model had called it directly. The declared list never changes
 * within a chat, so the provider's cached prefix stays valid.
 */
object ToolDiscovery {
    /**
     * The tools a v1 chat declares up front. Chosen by evidence, not taste (see the design doc):
     * - every tool the frozen system prompt tells the agent to use by name for its everyday work
     *   (files and code, run_command, web search and reading, memory, checklists and plans, ask_user,
     *   helpers, chat history), so the prompt never points at a tool the model can't see;
     * - the tools Locus already chose as the essential set for on-device models (`OnDeviceText`);
     * - browser_ask_user, the hand-over for passwords and payments, which must never be a search away;
     * - the MCP and connector meta-tools named in the prompt's connector list.
     * Everything else is deferred: integrations, phone apps, SSH, the rarer browser actions, and tools
     * used once in a while (git_commit, memory_forget, skill_create, connector_create, agent_create).
     * Changing this set means a new version: a chat keeps the set it was created with.
     */
    val CORE_V1: Set<String> = setOf(
        // Workspace files and code.
        "list_files", "read_file", "search_files", "find_files", "write_file", "edit_file", "multi_edit",
        "move_path", "copy_path", "delete_path", "make_directory", "code_outline", "project_info", "send_file",
        // Linux.
        "run_command", "linux_service_start", "linux_service_log", "git_inspect",
        // Web.
        "web_search", "web_read", "fetch_url",
        // The browser's everyday actions, and the hand-over for secrets.
        "browser_open", "browser_snapshot", "browser_click", "browser_type", "browser_read", "browser_ask_user",
        // Memory and skills.
        "memory_read", "memory_search", "memory_save", "skill_use", "skill_read_file",
        // Checklists, plans, questions.
        AgentControlTools.TODO_WRITE, AgentControlTools.PLAN_PROPOSE, AgentControlTools.ASK_USER,
        // Helpers.
        SubagentTools.SPAWN, SubagentTools.WAIT, SubagentTools.SEND, SubagentTools.STATUS, SubagentTools.CANCEL,
        // Earlier chats.
        "chat_history_search", "chat_history_read",
        // Connectors and MCP servers the prompt lists.
        "connector_call", "mcp_list_tools", "mcp_call",
        // Discovery itself.
        DiscoveryTools.FIND_TOOLS, DiscoveryTools.CALL_TOOL,
    )

    /** The core set of [version]; a version this build doesn't know (made by a newer one) falls back to [CORE_V1]. */
    fun core(version: String?): Set<String>? = when (version) {
        null, "" -> null
        else -> CORE_V1
    }

    /**
     * The note added to a new chat's system prompt when it finds tools on demand, frozen with it. Anthropic
     * suggests telling the model which kinds of tools it can search for (tool search tool docs, "Optimization tips").
     */
    const val PROMPT_NOTE =
        "Tools on demand: only the tools you use most are loaded. The rest, including the browser actions beyond open, snapshot, " +
            "click, type and read, device_* (the phone's own apps), weather and wikipedia, notify_me, git_commit, skill_create, " +
            "connector_create, the integrations (GitHub, Vercel, Netlify, Cloudflare, Supabase, Render, email, SSH) and the tools of " +
            "the user's MCP servers, are found with find_tools and run with call_tool, which works exactly like calling the tool " +
            "directly. Search before telling the user you can't do something."

    const val DEFAULT_RESULTS = 5
    const val MAX_RESULTS = 10

    /**
     * What the deferred tools cover, for find_tools' description: the groups present, in a fixed
     * order, so a chat's description (part of its cached prefix) never changes.
     */
    fun categories(deferred: Collection<String>): String {
        val names = deferred.toSet()
        val groups = GROUPS.filter { (_, test) -> names.any(test) }.map { it.first }
        val listed = names.filter { n -> GROUPS.none { (_, test) -> test(n) } }.sorted()
        return (groups + listed).joinToString(", ")
    }

    private val GROUPS: List<Pair<String, (String) -> Boolean>> = listOf(
        "more browser actions (scroll, tabs, forms, keys, uploads, dialogs, saving pages, waiting, back, logs)" to { n: String -> n.startsWith("browser_") },
        "phone apps (calendar, alarms, timers, SMS and email drafts, calls, directions, sharing, opening apps and links)" to { n: String -> n.startsWith("device_") },
        "notify_me (push notifications)" to { n: String -> n == "notify_me" },
        "weather and Wikipedia" to { n: String -> n == "weather" || n == "wikipedia" },
        "GitHub" to { n: String -> n.startsWith("github_") },
        "Vercel" to { n: String -> n.startsWith("vercel_") },
        "Netlify" to { n: String -> n.startsWith("netlify_") },
        "Cloudflare" to { n: String -> n.startsWith("cloudflare_") },
        "Supabase" to { n: String -> n.startsWith("supabase_") },
        "Render" to { n: String -> n.startsWith("render_") },
        "email (list, read, send)" to { n: String -> n.startsWith("email_") },
        "SSH and SFTP on the user's machines" to { n: String -> n.startsWith("ssh_") || n.startsWith("sftp_") },
        "Linux services and git commits" to { n: String -> n.startsWith("linux_service_") || n == "git_commit" },
        "memory_forget, skill_create, connector_create, agent_create" to { n: String ->
            n == "memory_forget" || n == "skill_create" || n == "connector_create" || n == SubagentTools.CREATE
        },
    )
}

/**
 * Tools discovery offers beyond the registry's own: an MCP server's, under names
 * [DiscoveryTools.mcpName] that [route] maps back onto `mcp_call`, so they run under mcp_call's policy.
 */
interface DiscoverableTools {
    /** Every tool on offer now, each with its full schema. Never throws: sources that can't be reached go in [Listing.problems]. */
    suspend fun list(): Listing

    /** The registered tool and arguments that run [name] with [arguments]; null when [name] isn't one of these. */
    fun route(name: String, arguments: JsonObject): Pair<String, JsonObject>?

    /**
     * [tools] are written by third parties (an MCP server's own descriptions), so find_tools marks them
     * as outside text, which also taints the run as `mcp_list_tools` does.
     */
    data class Listing(val tools: List<ToolSpec>, val problems: List<String> = emptyList(), val source: String = "MCP servers")
}

/** A tool call after [ToolRegistry.resolve]: what really runs, or why it can't. */
data class ResolvedCall(
    /** The call as the model made it; its result keeps this id and name. */
    val original: Part.ToolCall,
    /** The call that runs: [original] itself, or the tool call_tool named, with its arguments. */
    val call: Part.ToolCall,
    /** Set when [original] is a call_tool that names nothing runnable; [call] is then [original]. */
    val error: String? = null,
)

/**
 * Okapi BM25 over each tool's name, description and argument names and descriptions, the fields
 * Anthropic's server-side search reads too. Names count three times; a query that is exactly a tool's
 * name puts that tool first.
 */
class ToolIndex(private val tools: List<ToolSpec>) {
    private val docs: List<Map<String, Int>> = tools.map { spec -> terms(document(spec)).groupingBy { it }.eachCount() }
    private val lengths = docs.map { d -> d.values.sum() }
    private val average = lengths.average().takeIf { it > 0 } ?: 1.0
    private val frequency: Map<String, Int> = buildMap { docs.forEach { d -> d.keys.forEach { t -> put(t, (get(t) ?: 0) + 1) } } }

    /** Up to [limit] tools for [query], best first; none when nothing matches. */
    fun search(query: String, limit: Int): List<ToolSpec> {
        val exact = query.trim().lowercase()
        val wanted = terms(query).distinct()
        val n = tools.size.toDouble()
        return tools.indices.map { i ->
            var score = 0.0
            for (t in wanted) {
                val tf = docs[i][t] ?: continue
                val df = frequency[t] ?: continue
                val idf = ln(1 + (n - df + 0.5) / (df + 0.5))
                score += idf * (tf * (K1 + 1)) / (tf + K1 * (1 - B + B * lengths[i] / average))
            }
            if (tools[i].name.lowercase() == exact) score += EXACT_NAME
            i to score
        }.filter { it.second > 0.0 }
            .sortedWith(compareByDescending<Pair<Int, Double>> { it.second }.thenBy { tools[it.first].name })
            .take(limit)
            .map { tools[it.first] }
    }

    companion object {
        private const val K1 = 1.2
        private const val B = 0.75
        private const val EXACT_NAME = 100.0

        private fun document(spec: ToolSpec): String = buildString {
            repeat(3) { append(spec.name.replace('_', ' ')).append(' ') }
            append(spec.description).append(' ')
            appendArguments(spec.inputSchema)
        }

        private fun StringBuilder.appendArguments(schema: JsonObject) {
            (schema["properties"] as? JsonObject)?.forEach { (name, value) ->
                append(name.replace('_', ' ')).append(' ')
                (value as? JsonObject)?.let { p ->
                    (p["description"] as? JsonPrimitive)?.contentOrNull?.let { append(it).append(' ') }
                    appendArguments(p)
                    (p["items"] as? JsonObject)?.let { appendArguments(it) }
                }
            }
        }

        /** Lowercase words, split on anything but letters and digits, stop words dropped, plural and -ing endings trimmed. */
        fun terms(text: String): List<String> =
            text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() && it !in STOP }.map(::stem)

        private fun stem(word: String): String = when {
            word.length > 5 && word.endsWith("ing") -> word.dropLast(3)
            word.length > 4 && word.endsWith("ies") -> word.dropLast(3) + "y"
            word.length > 4 && word.endsWith("es") && (word.endsWith("ches") || word.endsWith("shes") || word.endsWith("xes") || word.endsWith("sses")) -> word.dropLast(2)
            word.length > 3 && word.endsWith("s") && !word.endsWith("ss") -> word.dropLast(1)
            else -> word
        }

        private val STOP = setOf(
            "a", "an", "the", "and", "or", "of", "to", "in", "on", "for", "with", "by", "is", "it", "be", "as", "at", "from", "this",
            "that", "use", "used", "can", "my", "me", "i", "you", "your", "do", "does", "tool", "tools",
        )
    }
}

/**
 * find_tools: searches the tools this chat didn't load (and its MCP servers' tools) and returns the
 * best matches with their full schemas, to run with call_tool.
 */
class FindTools(private val deferred: List<ToolSpec>, private val extras: DiscoverableTools?) : Tool {
    override val risk = ToolRisk.READ

    override val spec = ToolSpec(
        DiscoveryTools.FIND_TOOLS,
        "Search for tools that aren't loaded yet, by what you want to do (\"deploy a site to Netlify\", \"set an alarm\", " +
            "\"list GitHub issues\"). Returns the best matches with their full argument schemas; run one with call_tool. " +
            "Not loaded until you find them: ${ToolDiscovery.categories(deferred.map { it.name })}" +
            (if (extras != null) ", and the tools of the user's MCP servers" else "") + ". " +
            "Search again with other words if nothing fits; a tool you've found stays usable for the rest of the chat.",
        ObjectSchema()
            .string("query", "What you want to do, in a few words, or a tool's exact name.")
            .integer("limit", "How many tools to return (default ${ToolDiscovery.DEFAULT_RESULTS}, at most ${ToolDiscovery.MAX_RESULTS}).")
            .build(),
    )

    override fun describe(args: JsonObject) = "Look for tools: ${args.str("query").orEmpty()}"

    override suspend fun execute(args: JsonObject): ToolResult {
        val query = args.str("query")?.trim().orEmpty()
        if (query.isEmpty()) throw ToolException("Say what you want to do in `query`.")
        val limit = (args.int("limit") ?: ToolDiscovery.DEFAULT_RESULTS).coerceIn(1, ToolDiscovery.MAX_RESULTS)
        val listing = extras?.list()
        val outside = listing?.tools.orEmpty().map { it.name }.toSet()
        val found = ToolIndex(deferred + listing?.tools.orEmpty()).search(query, limit)
        val problems = listing?.problems.orEmpty().takeIf { it.isNotEmpty() }?.let { "\n\nNot searched: ${it.joinToString("; ")}" }.orEmpty()
        if (found.isEmpty()) {
            return ToolResult("No tool matches “$query”. Try other words; what isn't loaded: ${ToolDiscovery.categories(deferred.map { it.name })}.$problems")
        }
        val (theirs, ours) = found.partition { it.name in outside }
        val header = "${found.size} tool${if (found.size == 1) "" else "s"} for “$query”, best first. Run one with call_tool " +
            "{\"name\": \"<tool>\", \"arguments\": {…}}; its arguments follow the schema shown."
        val body = buildString {
            append(header)
            ours.forEach { append("\n\n").append(render(it)) }
            if (theirs.isNotEmpty()) {
                append("\n\n")
                append(UntrustedContent.wrap(theirs.joinToString("\n\n") { render(it) }, UntrustedContent.note("tool descriptions from ${listing?.source ?: "MCP servers"}")))
            }
            append(problems)
        }
        return ToolResult(body)
    }

    private fun render(spec: ToolSpec): String =
        "<tool name=\"${spec.name}\">\n${spec.description.trim()}\narguments: ${spec.inputSchema}\n</tool>"
}

/**
 * call_tool: runs a tool find_tools returned. Its call never reaches [execute]: [ToolRegistry.resolve]
 * replaces it with the named tool's own call before anything is checked.
 */
class CallTool : Tool {
    // Never judged as itself; the strictest values are only the safe side should anything look.
    override val risk = ToolRisk.EXECUTE
    override val runsAlone = true

    override val spec = ToolSpec(
        DiscoveryTools.CALL_TOOL,
        "Run a tool you found with find_tools (or any tool by its exact name). It runs exactly like a direct call: the same " +
            "permissions, approvals and checks apply.",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("name") {
                    put("type", "string")
                    put("description", "The tool's exact name, as find_tools showed it.")
                }
                putJsonObject("arguments") {
                    put("type", "object")
                    put("description", "The tool's arguments, following the schema find_tools showed.")
                }
            }
            putJsonArray("required") { add(JsonPrimitive("name")) }
            put("additionalProperties", false)
        },
    )

    override fun describe(args: JsonObject) = "Run ${args.str("name").orEmpty()}"

    override suspend fun execute(args: JsonObject): ToolResult =
        ToolResult.error("call_tool couldn't run ${args.str("name").orEmpty()}: it only works inside a chat's agent loop.")
}

/** The tool call_tool names, and its arguments as an object (a JSON string holding one is accepted too). */
internal fun callToolTarget(args: JsonObject): Pair<String?, JsonObject?> {
    val name = args.str("name")?.trim()?.takeIf { it.isNotEmpty() }
    val arguments = when (val a: JsonElement? = args["arguments"]) {
        null, is kotlinx.serialization.json.JsonNull -> JsonObject(emptyMap())
        is JsonObject -> a
        is JsonPrimitive -> a.contentOrNull?.let { s -> runCatching { dev.locus.core.llm.LlmJson.parseToJsonElement(s) as? JsonObject }.getOrNull() }
        is JsonArray -> null
    }
    return name to arguments
}
