package dev.locus.core.agent

import dev.locus.core.model.ApprovalDecision
import dev.locus.core.model.DiscoveryTools
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import dev.locus.core.model.SubagentTools
import dev.locus.core.model.ToolCapability
import dev.locus.core.model.ToolRisk
import dev.locus.core.model.startsTurn
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Rewind here (docs/design/rewind.md): where a rewind to a message cuts the chat's shown branch, which later
 * turns' file changes to put back, and what ran after the point that Locus can't take back.
 *
 * The conversation side is a branch: the chat's leaf moves back to the point and the old continuation stays in
 * the tree (docs/design/branching.md). Files go back through change tracking's undo, turn by turn
 * (docs/design/change-tracking.md), so only what the file tools changed is restored.
 */
object Rewind {
    /** Why Rewind here isn't offered on a message. */
    enum class Unavailable {
        /** The chat's first message: nothing comes before it to go back to (Edit it instead). */
        FIRST_MESSAGE,

        /** Nothing comes after this reply. */
        NOTHING_AFTER,

        /** The message isn't on the shown branch any more. */
        NOT_ON_BRANCH,
    }

    /**
     * A rewind of [path] (the shown branch) to a message: the first [keep] messages stay, [leaf] becomes the
     * branch's end, and [laterTurns] are the change-tracking turn ids of everything after it, oldest first.
     * [draft] is the text of a user message rewound to (it goes back into the composer), else null.
     */
    data class Cut(
        val keep: Int,
        val leaf: String,
        val laterTurns: List<String>,
        val later: List<Message>,
        val draft: String? = null,
        val attachments: List<Part.Attachment> = emptyList(),
    )

    /** [cut], or why there's none. */
    data class Plan(val cut: Cut?, val unavailable: Unavailable? = null)

    /**
     * Rewinding [path] to [messageId]. To a message the user sent: back to just before it, and its text goes back
     * into the composer to send again or change (as Claude Code's rewind does). To a reply: back to the end of its
     * turn, so the turn and its changes stay and everything after goes.
     */
    fun plan(path: List<Message>, messageId: String): Plan {
        val at = path.indexOfFirst { it.id == messageId }
        if (at < 0) return Plan(null, Unavailable.NOT_ON_BRANCH)
        val message = path[at]
        val keep = if (message.role == Role.USER) {
            if (at == 0) return Plan(null, Unavailable.FIRST_MESSAGE)
            at
        } else {
            (at + 1 until path.size).firstOrNull { path[it].startsTurn() } ?: return Plan(null, Unavailable.NOTHING_AFTER)
        }
        val later = path.drop(keep)
        val draft = if (message.role == Role.USER && message.parts.none { it is Part.Checkpoint }) message.userText else null
        return Plan(
            Cut(
                keep = keep,
                leaf = path[keep - 1].id,
                laterTurns = turnIds(path, keep),
                later = later,
                draft = draft,
                attachments = if (draft != null) message.attachments else emptyList(),
            ),
        )
    }

    /**
     * The change-tracking turn ids of [path] from [from] on: for each message that starts a turn, the message
     * after it (as `AgentLoop` keys a turn's changes). Oldest first.
     */
    fun turnIds(path: List<Message>, from: Int): List<String> =
        (from until path.size).filter { path[it].startsTurn() }.mapNotNull { path.getOrNull(it + 1)?.id }.distinct()

    // ---- What can't be rewound ------------------------------------------------------------------------

    /** Kinds of action that ran after the point and stay done. */
    enum class EffectKind {
        /** Shell commands: run_command, Linux, SSH, code that ran. */
        COMMAND,

        /** Calls that reached a service outside the phone: connectors, mail, deploys, web requests that send. */
        OUTSIDE,

        /** Helpers that worked: their file edits are put back with the turn, anything else they did stays. */
        HELPER,

        /** Other changes outside the workspace files: memory, the browser, the phone, git commits. */
        OTHER,
    }

    /** One call that ran after the point: its kind, tool and a short label ("npm install"). */
    data class Effect(val kind: EffectKind, val tool: String, val label: String) {
        /** How the agent's note names it: "run_command: npm install". */
        val note: String get() = if (label == tool) tool else "$tool: $label"
    }

    /** The workspace file tools whose changes change tracking records, so a rewind puts them back. */
    val TRACKED_FILE_TOOLS = setOf("write_file", "edit_file", "multi_edit", "move_path", "copy_path", "delete_path", "make_directory")

    /** Decisions under which a call didn't run. */
    private val NOT_RUN = setOf(
        ApprovalDecision.USER_DENIED, ApprovalDecision.POLICY_DENIED, ApprovalDecision.REVIEWER_DENIED, ApprovalDecision.INVALID_INPUT,
    )

    /**
     * What ran in [later] that a rewind can't take back, oldest first: every call that ran (it has a result that
     * says so) and isn't a tracked file tool or a read. [riskOf] is the chat's tool's risk by name, null for one
     * it doesn't know (counted as another change).
     */
    fun effects(later: List<Message>, riskOf: (String) -> ToolRisk?): List<Effect> {
        val results = later.filter { it.role == Role.TOOL }.flatMap { it.toolResults }.associateBy { it.callId }
        return later.flatMap { m ->
            when (m.role) {
                // A call the safety reviewer blocked and the user ran anyway: its result rides in a note.
                Role.USER -> listOfNotNull(dev.locus.core.model.ModeNote.ranAnyway(m)?.let { Effect(EffectKind.OTHER, RAN_ANYWAY, it.take(MAX_LABEL)) }) +
                    // A project rule's command ran in Linux (docs/design/rules.md): its changes aren't tracked either.
                    dev.locus.core.model.RuleNote.runs(m).map { (info, _) -> Effect(EffectKind.COMMAND, dev.locus.core.model.ProjectRules.ENTRY, info.name.take(MAX_LABEL)) }
                Role.ASSISTANT -> m.toolCalls.mapNotNull { call ->
                    val decision = results[call.id]?.decision ?: return@mapNotNull null
                    if (decision in NOT_RUN) return@mapNotNull null
                    // A call_tool runs the tool it names (docs/design/tool-discovery.md): classify and label that one.
                    val (name, arguments) = DiscoveryTools.unwrap(call.name, call.argumentsJson)
                    val ran = if (name == call.name) call else call.copy(name = name, argumentsJson = arguments)
                    classify(ran, riskOf(name))?.let { Effect(it, name, label(ran)) }
                }
                Role.TOOL -> emptyList()
            }
        }
    }

    /** The tool name an [Effect] of a call the user ran anyway carries. */
    const val RAN_ANYWAY = "run anyway"

    private fun classify(call: Part.ToolCall, risk: ToolRisk?): EffectKind? = when {
        call.name in TRACKED_FILE_TOOLS -> null
        call.name == SubagentTools.SPAWN || call.name == SubagentTools.SEND -> EffectKind.HELPER
        SubagentTools.isAgentTool(call.name) -> null
        // A call_tool that named no tool ran nothing.
        call.name == DiscoveryTools.CALL_TOOL -> null
        // A tool found through discovery that an MCP server offers: outside the phone.
        DiscoveryTools.parseMcpName(call.name) != null -> EffectKind.OUTSIDE
        // A plain read of a web page leaves nothing behind.
        call.name == "fetch_url" && args(call)?.string("method")?.uppercase().let { it == null || it == "GET" || it == "HEAD" } -> null
        risk == ToolRisk.READ -> null
        // A web search, the weather or a mailbox read reaches a service but changes nothing there.
        risk == ToolRisk.NETWORK && ToolCapability.of(call.name) == ToolCapability.READ -> null
        risk == ToolRisk.EXECUTE -> EffectKind.COMMAND
        risk == ToolRisk.NETWORK -> EffectKind.OUTSIDE
        else -> EffectKind.OTHER
    }

    private fun label(call: Part.ToolCall): String {
        val a = args(call) ?: return call.name
        val text = listOf("command", "name", "url", "path", "to", "subject").firstNotNullOfOrNull { a.string(it)?.takeIf(String::isNotBlank) } ?: return call.name
        val line = text.lineSequence().first().trim()
        return if (line.length > MAX_LABEL) line.take(MAX_LABEL - 1) + "…" else line
    }

    private const val MAX_LABEL = 120

    private fun args(call: Part.ToolCall): JsonObject? =
        runCatching { kotlinx.serialization.json.Json.parseToJsonElement(call.argumentsJson).jsonObject }.getOrNull()

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
