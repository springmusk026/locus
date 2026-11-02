package dev.locus.core.agent

import dev.locus.core.model.ApprovalScopeKind
import dev.locus.core.model.RememberedApproval
import kotlinx.serialization.json.JsonObject
import java.net.URI

/**
 * What a remembered approval for one call would cover: one tool, and one site, folder, recipient or
 * command prefix taken from the call's arguments. [label] is how the approval card names it.
 */
data class ApprovalScope(val tool: String, val kind: ApprovalScopeKind, val value: String, val label: String)

/**
 * The "don't ask again" choices an approval card may offer for a call. [always] is false when only
 * "Allow for this chat" is safe (a file at the top of the workspace would make the folder the whole
 * workspace). Null on the request: nothing may be remembered for it.
 */
data class RememberOffer(val scope: ApprovalScope, val always: Boolean)

/** The user's answer to an approval card. */
enum class ApprovalAnswer {
    DENY,
    ALLOW,

    /** Allow, and don't ask again for this scope in this chat. */
    ALLOW_FOR_CHAT,

    /** Allow, and don't ask again for this scope (in this project, if any) until the rule expires. */
    ALLOW_ALWAYS,
    ;

    val allowed: Boolean get() = this != DENY
}

/**
 * The remembered approvals a run can use, bound to its chat and project by whoever builds the run.
 * [match] finds a live rule covering the call (expired ones and those for other chats or projects
 * never match); [remember] stores the user's choice. See docs/design/remembered-approvals.md.
 */
interface ApprovalMemory {
    suspend fun match(toolName: String, args: JsonObject): RememberedApproval?

    suspend fun remember(scope: ApprovalScope, forChat: Boolean)

    /** Whether rules for [toolName] can apply in this chat at all; false: none is offered either. */
    suspend fun offers(toolName: String): Boolean = true
}

/**
 * Derives the scope of a call and checks rules against calls. Pure: which tools can be remembered,
 * and how, is decided here and nowhere else.
 *
 * - **Sites** (fetch_url with its method, web_read, browser_open, device_open_url): the URL's origin,
 *   scheme, host and port, as browsers scope site permissions. Never with credentials in the URL.
 * - **Folders** (write_file, edit_file, multi_edit, make_directory, copy_path's destination): the
 *   file's folder, relative to the workspace or the chat's project. Never outside it (absolute paths,
 *   `~`, `..`), never in `.git` or `.locus`. Deleting and moving are never remembered.
 * - **Recipients** (device_email, device_sms): exactly one address or number.
 * - **Commands** (run_command): a prefix of whole words, never for a dangerous command ([CommandSafety]).
 */
object ApprovalScopes {
    private val SITE_TOOLS = setOf("fetch_url", "web_read", "browser_open", "device_open_url")
    private val FOLDER_TOOLS = mapOf(
        "write_file" to "path", "edit_file" to "path", "multi_edit" to "path", "make_directory" to "path", "copy_path" to "to",
    )
    private val RECIPIENT_TOOLS = setOf("device_email", "device_sms")
    private const val COMMAND_TOOL = "run_command"

    /** Folders that are never remembered: git's (hooks run code) and Locus's own. */
    private val PROTECTED_SEGMENTS = setOf(".git", ".locus")

    /** The scope a remembered approval for this call would have, or null when it can't be remembered. */
    fun derive(toolName: String, args: JsonObject): ApprovalScope? = when {
        toolName in SITE_TOOLS -> site(toolName, args)
        toolName in FOLDER_TOOLS -> folder(args.str(FOLDER_TOOLS.getValue(toolName)))?.let { dir ->
            ApprovalScope(toolName, ApprovalScopeKind.FOLDER, dir, if (dir.isEmpty()) "the top folder" else "$dir/")
        }
        toolName in RECIPIENT_TOOLS -> recipient(toolName, args.str("to"))?.let { ApprovalScope(toolName, ApprovalScopeKind.RECIPIENT, it, it) }
        toolName == COMMAND_TOOL -> args.str("command")?.let(CommandSafety::rememberablePrefix)?.takeIf { args.str("cwd").let { cwd -> cwd == null || cwd.trim() == "/workspace" } }
            ?.let { ApprovalScope(toolName, ApprovalScopeKind.COMMAND, it, "`$it …`") }
        else -> null
    }

    /** The offer for a call whose [scope] is known: "Always" only for scopes narrower than the whole workspace. */
    fun offer(scope: ApprovalScope): RememberOffer = RememberOffer(scope, always = !(scope.kind == ApprovalScopeKind.FOLDER && scope.value.isEmpty()))

    /** Whether [rule] covers this call: the same tool, and the call's own scope inside the rule's. */
    fun covers(rule: RememberedApproval, toolName: String, args: JsonObject): Boolean {
        if (rule.tool != toolName) return false
        if (rule.kind == ApprovalScopeKind.COMMAND) {
            if (args.str("cwd").let { it != null && it.trim() != "/workspace" }) return false
            return CommandSafety.matches(rule.value, args.str("command") ?: return false)
        }
        val scope = derive(toolName, args) ?: return false
        if (scope.kind != rule.kind) return false
        return when (rule.kind) {
            ApprovalScopeKind.FOLDER -> rule.value.isEmpty() || scope.value == rule.value || scope.value.startsWith(rule.value + "/")
            else -> scope.value == rule.value
        }
    }

    /** The live rules of [rules] that apply in this chat and project, at [now]. */
    fun applicable(rules: List<RememberedApproval>, conversationId: String, projectId: String?, now: Long): List<RememberedApproval> =
        rules.filter { r ->
            !r.expired(now) && r.projectId == projectId && (r.conversationId == null || r.conversationId == conversationId)
        }

    private fun site(toolName: String, args: JsonObject): ApprovalScope? {
        val origin = origin(args.str("url")) ?: return null
        if (toolName == "fetch_url") {
            val method = (args.str("method") ?: "GET").trim().uppercase()
            if (method !in setOf("GET", "POST", "PUT", "PATCH", "DELETE")) return null
            return ApprovalScope(toolName, ApprovalScopeKind.SITE, "$method $origin", "$method requests to ${origin.removePrefix("https://")}")
        }
        return ApprovalScope(toolName, ApprovalScopeKind.SITE, origin, origin.removePrefix("https://"))
    }

    /** `scheme://host[:port]`, lower-cased; null for anything but plain http(s) without credentials. */
    internal fun origin(url: String?): String? {
        val u = runCatching { URI(url?.trim() ?: return null) }.getOrNull() ?: return null
        val scheme = u.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (u.rawUserInfo != null) return null
        val host = u.host?.lowercase()?.trimEnd('.')?.takeIf { it.isNotEmpty() } ?: return null
        val port = u.port.takeIf { it != -1 && !(scheme == "https" && it == 443) && !(scheme == "http" && it == 80) }
        return "$scheme://$host" + (port?.let { ":$it" } ?: "")
    }

    /**
     * The folder of a workspace-relative [path], normalized (`./a//b/c.txt` → `a/b`; `c.txt` → the
     * top folder, ""). Null when it reaches outside or into a protected folder.
     */
    internal fun folder(path: String?): String? {
        val p = path?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (p.startsWith("/") || p.startsWith("~") || '\\' in p || '\u0000' in p) return null
        val parts = p.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." } || parts.any { it in PROTECTED_SEGMENTS }) return null
        return parts.dropLast(1).joinToString("/")
    }

    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val PHONE = Regex("\\+?[0-9]{3,15}")

    /** One recipient, normalized; null for none, several, or one that doesn't look like an address. */
    internal fun recipient(toolName: String, to: String?): String? {
        val all = to.orEmpty().split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
        if (all.size != 1) return null
        val one = all.single()
        return if (toolName == "device_sms") {
            one.filterNot { it == ' ' || it == '-' || it == '(' || it == ')' || it == '.' }.takeIf { PHONE.matches(it) }
        } else {
            one.lowercase().takeIf { EMAIL.matches(it) }
        }
    }
}
