package dev.locus.core.agent

import dev.locus.core.model.ChatMode
import dev.locus.core.model.ProjectPolicy
import dev.locus.core.model.ToolCapability
import dev.locus.core.model.ToolGroup
import kotlinx.serialization.json.JsonObject

/**
 * Per-chat limits from the composer: the chat's mode, plan mode and the tools menu. Tools are never removed from the
 * request (that would break the cached prefix); a limited call is refused at run time instead, with a
 * reason the model can act on.
 */
object ChatPolicy {
    /** Why [tool] may not run with [args] in this chat, or null when it may. */
    suspend fun denial(tool: Tool, args: JsonObject, planMode: Boolean, disabled: Set<String>, temporary: Boolean = false): String? {
        val name = tool.spec.name
        temporaryDenial(name, temporary)?.let { return it }
        ToolGroup.of(name)?.takeIf { it.key in disabled }?.let {
            return "Not allowed: the user switched off ${it.label} for this chat. Don't call it again: answer the user without it, or ask them to turn it on."
        }
        if (name in ToolGroup.CONNECTOR_TOOLS) {
            args.str("connector")?.takeIf { ToolGroup.connectorKey(it) in disabled }?.let {
                return "Not allowed: the user switched off the connector “$it” for this chat. Don't call it again: answer the user without it, or ask them to turn it on."
            }
        }
        modeDenial(ChatMode.of(disabled), tool, args)?.let { return it }
        if (planMode && !tool.isObservation(args)) {
            return "Not allowed in plan mode: only reading and looking tools work until the user approves a plan. " +
                "Note this step in your plan and call plan_propose when you're ready."
        }
        return null
    }

    /**
     * What a temporary chat ([dev.locus.core.model.Conversation.temporary]) refuses: everything that reads
     * or writes memory, other chats, or what Locus learns for later (skills, connectors, helper types).
     * Refused at run time like the rest of this object, so a temporary chat declares exactly the tools a
     * normal chat does and its cached prefix is the same shape (docs/design/temporary-chats.md).
     */
    val TEMPORARY_DENIED: Set<String> = setOf(
        "memory_read", "memory_search", "memory_save", "memory_forget",
        "chat_history_search", "chat_history_read",
        "skill_create", "connector_create", dev.locus.core.model.SubagentTools.CREATE, dev.locus.core.model.ProviderDrafts.TOOL,
    )

    /** Why a temporary chat may not call [name], or null when it may (or the chat isn't temporary). */
    fun temporaryDenial(name: String, temporary: Boolean): String? {
        if (!temporary || name !in TEMPORARY_DENIED) return null
        return "Not allowed: this is a temporary chat, so memory, past chats and saving skills, connectors or helper types are off. " +
            "Don't call $name again; answer from this conversation alone. Files you write still go to the workspace."
    }

    /**
     * The line a temporary chat's system prompt carries, set when the chat is created (its prompt never
     * changes afterwards). It replaces the memory index and the recent chats, which such a chat leaves out.
     */
    const val TEMPORARY_PROMPT_NOTE: String =
        "<temporary_chat>This is a temporary chat. You can't read or save memory, read the user's other chats, or create " +
            "skills, connectors or helper types here: those tools are refused. The chat is deleted when the user leaves it. " +
            "Files you write still go to the workspace and stay there.</temporary_chat>"

    /**
     * Why a project chat may not make this call: a connector or MCP server outside the project's
     * allow-list ([ProjectPolicy.allowedConnectors]). Null outside projects or when allowed. Refused at
     * run time, so the chat's tool list stays the same (docs/design/projects.md).
     */
    fun projectDenial(tool: Tool, args: JsonObject, project: ProjectPolicy?): String? {
        if (project == null || tool.spec.name !in ToolGroup.CONNECTOR_TOOLS) return null
        val id = args.str("connector") ?: return null
        if (project.allowsConnector(id)) return null
        return "Not allowed: the connector “$id” isn't enabled for this project. Don't retry; continue without it, or ask the user to " +
            "allow it in the project's permissions."
    }

    /**
     * Why [mode] refuses this call, or null when it allows it. Only ever refuses: what it allows still
     * goes through the user's Allow / Ask / Deny and [Tool.mustConfirm].
     *
     * - Read-only allows observations only, exactly what a read-only background run allows.
     * - Workspace also allows changes that stay on the phone ([ToolCapability.LOCAL]).
     *
     * An observation that opens an address (browser_open, a GET fetch_url, web_read) passes here in
     * every mode, but it isn't "nothing leaves the phone": its path and query are sent. Once the run
     * has read private data, [Taint.disclosureReason] makes such a call to a new host ask, in these
     * modes as in Full, so "read-only" can't be used to read a file and then quietly put it in a URL.
     */
    suspend fun modeDenial(mode: ChatMode, tool: Tool, args: JsonObject): String? {
        if (mode == ChatMode.FULL || tool.isObservation(args)) return null
        val name = tool.spec.name
        return when (mode) {
            ChatMode.READ_ONLY ->
                "Not allowed: this chat is read-only, so only reading and looking tools work and $name would change something. " +
                    "Don't retry or work around it; if the task needs it, ask the user to switch the chat to Workspace or Full mode."
            ChatMode.WORKSPACE -> if (ToolCapability.of(name) == ToolCapability.LOCAL) {
                tool.sendsOffPhone(args)?.let { host ->
                    "Not allowed: this chat is in Workspace mode, so nothing may leave the phone, but this project runs $name on $host " +
                        "and copies its files there. Don't retry or work around it; ask the user to switch the chat to Full mode, or to " +
                        "run the project's commands on this phone again in the project's settings."
                }
            } else {
                "Not allowed: this chat is in Workspace mode, so changes may stay inside the workspace and Linux but $name reaches " +
                    "outside the phone (sending, posting, deploying, remote machines, websites or other apps). Don't retry or work " +
                    "around it; if the task needs it, ask the user to switch the chat to Full mode."
            }
            ChatMode.FULL -> null
        }
    }
}

/**
 * Joins streamed reasoning deltas. Anthropic progress updates arrive as separate thinking blocks with
 * nothing between them ("Checking the file.Now reading…"); a delta that starts a new sentence right
 * after one ended, with no space, is a new block, so it gets its own paragraph.
 */
fun StringBuilder.appendReasoning(delta: String) {
    if (delta.isEmpty()) return
    if (isNotEmpty()) {
        val end = last()
        val start = delta.first()
        if ((end == '.' || end == '!' || end == '?' || end == ':') && start.isUpperCase()) append("\n\n")
    }
    append(delta)
}
