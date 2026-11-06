package dev.locus.core.agent

import dev.locus.core.llm.LlmJson
import dev.locus.core.model.DiscoveryTools
import dev.locus.core.model.LocalNetwork
import dev.locus.core.model.Message
import dev.locus.core.model.ModeNote
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import dev.locus.core.model.SubagentTools
import dev.locus.core.model.ToolCapability
import kotlinx.serialization.json.JsonObject

/**
 * Run-level taint: once a run has read text someone else wrote (a web page, an email, an MCP or
 * connector reply, command output), the calls that could carry the user's data away or plant
 * instructions for later chats stop running on their own. This is the "Agents Rule of Two" applied at
 * run time: a run that holds untrusted input and private data doesn't also get an unchecked way out.
 * See docs/design/taint.md.
 *
 * It is a policy decision only. The tool list and the system prompt never change, so the provider's
 * cached prefix stays valid; a gated call asks (or goes to the reviewer with the reason), and in a
 * read-only background run, where nobody can be asked, it is refused.
 */
object Taint {
    /**
     * Tools whose results carry text someone else wrote. Any other result that holds an
     * [UntrustedContent] block counts too ([isSource]), so a new integration that marks its output is
     * covered without being listed here.
     */
    private val SOURCES = setOf(
        "fetch_url", "web_search", "web_read", "wikipedia",
        "mcp_call", "mcp_list_tools", "connector_call",
        "email_list", "email_read",
        "github_issues", "github_read",
        "run_command", "linux_service_start", "linux_service_log", "ssh_exec", "ssh_job_status",
        // A helper's report is the helper's summary of whatever it read.
        SubagentTools.SPAWN, SubagentTools.WAIT, SubagentTools.SEND,
    )

    /** Calls that write something every later chat reads (memory) or that can grow what the agent can do. */
    private val PERSISTENT = setOf("memory_save", "skill_create", "connector_create")

    /** Observations that open an address the model chose, which carries data out in its path and query. */
    private val URL_OBSERVATIONS = setOf("browser_open", "fetch_url", "web_read")

    /** Whether a call of [toolName] that returned [content] brought outside text into the run. */
    fun isSource(toolName: String, content: String): Boolean =
        toolName in SOURCES || toolName.startsWith("browser_") || UntrustedContent.isMarked(content)

    /**
     * Whether the result of [toolName] called with [args] is the workspace's own output rather than
     * outside content, for remembered approvals only (docs/design/remembered-approvals.md, "Taint"): a
     * run_command that ran one plain local command which fetches nothing ([CommandSafety.mayFetch]),
     * such as `npm test` or `ls`. Its output is what the workspace's own files and programs printed, as
     * a read_file of them is. It still counts for every other taint gate ([isSource]).
     */
    fun isLocalOutput(toolName: String, args: JsonObject?): Boolean {
        if (toolName != "run_command" || args == null) return false
        val call = ApprovalScopes.commandCall(args) ?: return false
        return !CommandSafety.mayFetch(call.command)
    }

    /**
     * The sources already in [history]: tool results, and outside text Locus added to a user turn (a helper's report, a "Run anyway" result).
     * With [skipLocal], results that are the workspace's own output ([isLocalOutput]) don't count: what
     * remembered approvals treat as outside content.
     */
    fun sourcesIn(history: List<Message>, skipLocal: Boolean = false): Set<String> = buildSet {
        // A call_tool's result is stored under that name; what it read is the tool it ran (an MCP server's
        // tool ran as mcp_call). See docs/design/tool-discovery.md.
        val ranAs = history.asSequence().flatMap { it.toolCalls }.filter { it.name == DiscoveryTools.CALL_TOOL }
            .associate { c -> c.id to DiscoveryTools.unwrap(c.name, c.argumentsJson).first.let { n -> if (DiscoveryTools.parseMcpName(n) != null) "mcp_call" else n } }
        val local = if (!skipLocal) emptySet() else history.asSequence().flatMap { it.toolCalls }
            .filter { c -> isLocalOutput(c.name, runCatching { LlmJson.parseToJsonElement(c.argumentsJson) as? JsonObject }.getOrNull()) }
            .map { it.id }.toSet()
        history.forEach { m ->
            when (m.role) {
                Role.TOOL -> m.toolResults.forEach { r ->
                    val name = if (r.toolName == DiscoveryTools.CALL_TOOL) ranAs[r.callId] ?: r.toolName else r.toolName
                    if (isSource(name, r.content) && r.callId !in local) add(name)
                }
                Role.USER -> {
                    // A run another app started through the automation intents (docs/design/android-bridge.md).
                    ModeNote.externalApp(m)?.let { add("another app ($it)") }
                    // A run an event trigger started: the event's text came from outside (docs/design/event-triggers.md).
                    ModeNote.eventSource(m)?.let { add("an event ($it)") }
                    m.parts.forEach { p ->
                        // A compaction summary of a run that had read outside text (docs/design/compaction.md).
                        if (p is Part.Checkpoint) addAll(p.untrustedSources)
                        if (p is Part.Text && ModeNote.isResult(p.text)) {
                            if ("<subagent_report" in p.text) add("a helper's report")
                            else if (UntrustedContent.isMarked(p.text)) add("a call you ran")
                        }
                    }
                }
                else -> Unit
            }
        }
    }

    /**
     * Hosts the user named in what they typed: links, and bare domains such as "bbc.co.uk". Only the
     * user's own words count (see [trustedUserMessages]); text the agent read never adds a host.
     */
    fun hostsIn(userMessages: List<String>): Set<String> = buildSet {
        userMessages.forEach { text ->
            URL.findAll(text).forEach { m -> LocalNetwork.hostOf(m.value)?.let { add(normal(it)) } }
            DOMAIN.findAll(text.lowercase()).forEach { m ->
                val host = m.value.trimEnd('.')
                if (host.substringAfterLast('.') !in FILE_EXTENSIONS) add(normal(host))
            }
        }
    }

    /**
     * Why this call must be confirmed because the run is tainted ([sources] non-empty), or null when it
     * may go ahead as usual. [trustedHosts] are the hosts the user named or set up in Locus.
     */
    suspend fun reason(tool: Tool, args: JsonObject, sources: Set<String>, trustedHosts: Set<String>): String? {
        if (sources.isEmpty()) return null
        val name = tool.spec.name
        val from = sources.sorted().let { s -> s.take(3).joinToString() + if (s.size > 3) " and ${s.size - 3} more" else "" }
        val prefix = "Tainted run: it has read outside content (from $from), which could be steering it."
        if (name in PERSISTENT) return "$prefix This would keep something for later chats."
        val observation = tool.isObservation(args)
        if (name in URL_OBSERVATIONS && observation) {
            val host = args.str("url")?.let(LocalNetwork::hostOf)?.let(::normal) ?: return "$prefix This opens an address it chose."
            if (trusted(host, trustedHosts)) return null
            return "$prefix This opens $host, which you haven't named; the address itself can carry data out."
        }
        if (!observation && ToolCapability.of(name) == ToolCapability.OUTWARD) {
            // A click or keystroke on a page open in the agent's browser. On a host the user named (or set up
            // in Locus) it falls back to the tool's own checks (SensitiveActions: submits, sign-ins, payments,
            // outward labels, password and code fields), so ordinary browsing there doesn't ask on every step.
            // The host is the browser's record of the page; nothing the page or the model says can add one.
            val page = tool.pageAction(args)
            if (page != null) {
                val host = normal(page.host)
                return if (trusted(host, trustedHosts)) null else "$prefix This acts on a page on $host, which you haven't named."
            }
            return "$prefix This sends or changes something outside the phone."
        }
        // A project linked to an SSH machine: the call copies the project's files there first.
        tool.sendsOffPhone(args)?.let { host -> return "$prefix This runs on $host and copies the project's files there first." }
        return null
    }

    /**
     * The site a "for the rest of this task" approval covers for this call ([ApprovalRequest.siteForTask]):
     * the host a URL observation opens ([urlHost]), or the host of the page a click or keystroke acts on
     * ([Tool.pageAction]). Null for any other call.
     */
    suspend fun siteOf(tool: Tool, args: JsonObject): String? = urlHost(tool, args) ?: tool.pageAction(args)?.host?.let(::normal)

    /**
     * Why text the model chose may not be typed into a page without the user, once the run has read
     * private data ([privateReads] non-empty), or null. Whatever the host, named or not: a page's fields
     * can post what is typed where others read it (a comment box, a form that mails it on), which is how a
     * hidden instruction would relay a code read from mail (docs/design/taint.md, "Typing after a private
     * read"). Clicks, options and keys carry no text the model wrote, so they don't ask here. Neither
     * "Allow site" nor a named host lifts it: the card shows the text every time.
     */
    suspend fun typingReason(tool: Tool, args: JsonObject, privateReads: Set<String>): String? {
        if (privateReads.isEmpty()) return null
        val page = tool.pageAction(args)?.takeIf { it.carriesText } ?: return null
        val from = privateReads.sorted().let { s -> s.take(3).joinToString() + if (s.size > 3) " and ${s.size - 3} more" else "" }
        return "This task has read your private data (with $from). This types text it chose into a page on ${normal(page.host)}, " +
            "which could post or send it on."
    }

    // ---- Disclosure: private data read, then an address that could carry it -----------------------

    /**
     * Tools whose results hold the user's own private data: workspace files, memory, chat history, mail,
     * the calendar, a picked contact, git diffs, and deploy settings that hold environment values.
     * Reading one isn't outside content (the run isn't tainted), but from then on an address the model
     * builds could carry what it read: see [disclosureReason] and docs/design/taint.md, "Disclosure".
     */
    private val PRIVATE_READS = setOf(
        "read_file", "search_files", "code_outline",
        "memory_read", "memory_search",
        "chat_history_search", "chat_history_read",
        "email_list", "email_read",
        "calendar_read", "contacts_pick",
        "git_inspect",
        "netlify_env", "vercel_env", "render_env", "supabase_api_keys",
        "ssh_job_status",
    )

    /**
     * Destinations whose request logs no third party can read, so a request there discloses nothing to
     * an attacker: the search pages of the search engines the agent is told to use, and Wikipedia
     * articles. Each is a host (and its subdomains) with the path prefixes allowed on it; only search
     * and article paths count, never a host's redirectors (`google.com/url?q=…`, `duckduckgo.com/l/?uddg=…`),
     * which would pass the data on to any address. Anything else is safe only because the user named it
     * or set it up ([trusted]). Kept short on purpose.
     */
    private val SAFE_DESTINATIONS: Map<String, List<String>> = mapOf(
        "duckduckgo.com" to listOf("/?", "/html/?", "/html?", "/lite/?", "/lite?"),
        "google.com" to listOf("/search?"),
        "bing.com" to listOf("/search?"),
        "wikipedia.org" to listOf("/wiki/"),
    )

    /** Whether a successful call of [toolName] read the user's private data. */
    fun isPrivateRead(toolName: String): Boolean = toolName in PRIVATE_READS

    /** The private reads already in [history] (successful results only), so they carry over to later turns. */
    fun privateReadsIn(history: List<Message>): Set<String> = buildSet {
        val ranAs = history.asSequence().flatMap { it.toolCalls }.filter { it.name == DiscoveryTools.CALL_TOOL }
            .associate { c -> c.id to DiscoveryTools.unwrap(c.name, c.argumentsJson).first }
        history.filter { it.role == Role.TOOL }.forEach { m ->
            m.toolResults.forEach { r ->
                val name = if (r.toolName == DiscoveryTools.CALL_TOOL) ranAs[r.callId] ?: r.toolName else r.toolName
                if (!r.isError && isPrivateRead(name)) add(name)
            }
        }
    }

    /** The host a URL observation opens, normalised, or null when [tool] isn't one or has no host. */
    suspend fun urlHost(tool: Tool, args: JsonObject): String? {
        if (tool.spec.name !in URL_OBSERVATIONS || !tool.isObservation(args)) return null
        return args.str("url")?.let(LocalNetwork::hostOf)?.let(::normal)
    }

    /**
     * Why this call must be confirmed because the run has read private data ([privateReads] non-empty),
     * or null. It covers what [reason] can't: an untainted run, where nothing outside steers the model
     * but the address it builds could still carry a file's contents away, whatever the chat's mode
     * (an observation passes read-only and Workspace modes, [ChatPolicy.modeDenial]).
     *
     * A URL observation asks when its host is new (not [trusted], not a [SAFE_DESTINATIONS] host, and
     * not one this run already opened, [reached]) and the address carries data: any path beyond `/`,
     * any query, any fragment (the browser's page scripts can read it). Its length doesn't matter: a
     * short value such as a PIN or one line of a file fits in a few characters.
     */
    suspend fun disclosureReason(
        tool: Tool,
        args: JsonObject,
        privateReads: Set<String>,
        trustedHosts: Set<String>,
        reached: Set<String>,
    ): String? {
        if (privateReads.isEmpty()) return null
        if (tool.spec.name !in URL_OBSERVATIONS || !tool.isObservation(args)) return null
        val from = privateReads.sorted().let { s -> s.take(3).joinToString() + if (s.size > 3) " and ${s.size - 3} more" else "" }
        val prefix = "This task has read your private data (with $from)."
        val url = args.str("url")?.trim().orEmpty()
        val host = LocalNetwork.hostOf(url)?.let(::normal) ?: return "$prefix This opens an address it chose."
        if (trusted(host, trustedHosts) || safeDestination(host, url) || host in reached) return null
        if (!carriesData(url)) return null
        return "$prefix This opens $host, which you haven't named, and the address itself could carry that data out."
    }

    /** Whether [url] has anything after its host beyond a bare `/`: a path, a query or a fragment. */
    fun carriesData(url: String): Boolean {
        val rest = afterHost(url)
        return rest.isNotEmpty() && rest != "/"
    }

    /** What follows the authority in [url]: path, query and fragment, as written. */
    private fun afterHost(url: String): String = url.substringAfter("://", url).dropWhile { it != '/' && it != '?' && it != '#' }

    private fun safeDestination(host: String, url: String): Boolean {
        val rest = afterHost(url)
        val path = if (rest.startsWith("?")) "/$rest" else rest
        return SAFE_DESTINATIONS.any { (h, prefixes) -> (host == h || host.endsWith(".$h")) && prefixes.any { path.startsWith(it) } }
    }

    /** [host] is one of [trustedHosts] or a subdomain of one; the phone's own loopback always is. */
    fun trusted(host: String, trustedHosts: Set<String>): Boolean {
        val h = normal(host)
        if (h == "localhost" || h.endsWith(".localhost") || h == "127.0.0.1" || h == "::1") return true
        return trustedHosts.any { t -> h == t || h.endsWith(".$t") }
    }

    private fun normal(host: String) = host.lowercase().trimEnd('.').removePrefix("www.")

    private val URL = Regex("https?://[^\\s<>\"'()\\[\\]]+", RegexOption.IGNORE_CASE)
    private val DOMAIN = Regex("(?<![@\\w.-])(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,24}\\b")

    /** Bare words like "notes.md" look like domains; these endings are read as file names instead. */
    private val FILE_EXTENSIONS = setOf(
        "md", "txt", "py", "kt", "kts", "js", "ts", "json", "html", "htm", "css", "csv", "pdf", "png", "jpg", "jpeg", "gif", "zip",
        "sh", "log", "yml", "yaml", "xml", "toml", "java", "rs", "go", "rb", "php", "sql", "doc", "docx", "xls", "xlsx", "mp3", "mp4",
    )
}

/**
 * The taint a run starts with, handed to the helpers it starts: what the parent run has read, the
 * hosts its user named (a helper's task is the agent's words, not the user's, so it adds none), and
 * the private data it has read ([Taint.disclosureReason]: a helper may have been told what it was).
 */
data class TaintSeed(val sources: Set<String>, val trustedHosts: Set<String>, val privateReads: Set<String> = emptySet())
