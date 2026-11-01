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
    suspend fun denial(tool: Tool, args: JsonObject, planMode: Boolean, disabled: Set<String>): String? {
        val name = tool.spec.name
        ToolGroup.of(name)?.takeIf { it.key in disabled }?.let {
            return "Not allowed: the user switched off ${it.label} for this chat. Don't retry; continue without it or ask the user."
        }
        if (name in ToolGroup.CONNECTOR_TOOLS) {
            args.str("connector")?.takeIf { ToolGroup.connectorKey(it) in disabled }?.let {
                return "Not allowed: the user switched off the connector “$it” for this chat. Don't retry; continue without it or ask the user."
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
     */
    suspend fun modeDenial(mode: ChatMode, tool: Tool, args: JsonObject): String? {
        if (mode == ChatMode.FULL || tool.isObservation(args)) return null
        val name = tool.spec.name
        return when (mode) {
            ChatMode.READ_ONLY ->
                "Not allowed: this chat is read-only, so only reading and looking tools work and $name would change something. " +
                    "Don't retry or work around it; if the task needs it, ask the user to switch the chat to Workspace or Full mode."
            ChatMode.WORKSPACE -> if (ToolCapability.of(name) == ToolCapability.LOCAL) {
                null
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
