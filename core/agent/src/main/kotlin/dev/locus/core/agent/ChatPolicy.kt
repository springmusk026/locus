package dev.locus.core.agent

import dev.locus.core.model.ToolGroup
import kotlinx.serialization.json.JsonObject

/**
 * Per-chat limits from the composer: plan mode and the tools menu. Tools are never removed from the
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
        if (planMode && !tool.isObservation(args)) {
            return "Not allowed in plan mode: only reading and looking tools work until the user approves a plan. " +
                "Note this step in your plan and call plan_propose when you're ready."
        }
        return null
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
