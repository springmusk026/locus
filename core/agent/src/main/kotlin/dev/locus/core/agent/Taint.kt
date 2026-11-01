package dev.locus.core.agent

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

    /** The sources already in [history]: tool results, and outside text Locus added to a user turn (a helper's report, a "Run anyway" result). */
    fun sourcesIn(history: List<Message>): Set<String> = buildSet {
        history.forEach { m ->
            when (m.role) {
                Role.TOOL -> m.toolResults.forEach { r -> if (isSource(r.toolName, r.content)) add(r.toolName) }
                Role.USER -> m.parts.forEach { p ->
                    // A compaction summary of a run that had read outside text (docs/design/compaction.md).
                    if (p is Part.Checkpoint) addAll(p.untrustedSources)
                    if (p is Part.Text && ModeNote.isResult(p.text)) {
                        if ("<subagent_report" in p.text) add("a helper's report")
                        else if (UntrustedContent.isMarked(p.text)) add("a call you ran")
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
        if (!observation && ToolCapability.of(name) == ToolCapability.OUTWARD) return "$prefix This sends or changes something outside the phone."
        // A project linked to an SSH machine: the call copies the project's files there first.
        tool.sendsOffPhone(args)?.let { host -> return "$prefix This runs on $host and copies the project's files there first." }
        return null
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
 * The taint a run starts with, handed to the helpers it starts: what the parent run has read, and the
 * hosts its user named (a helper's task is the agent's words, not the user's, so it adds none).
 */
data class TaintSeed(val sources: Set<String>, val trustedHosts: Set<String>)
