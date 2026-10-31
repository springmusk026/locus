package dev.locus.core.agent

import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.Role

/** What to do with a transcript whose run may have been cut off by the process dying. */
data class Recovery(
    /** Results to append for tool calls that never got one (providers reject unanswered calls). */
    val missingResults: List<Part.ToolResult>,
    /** True when the agent never finished its last turn, so the user can be offered "Continue". */
    val unfinished: Boolean,
)

/**
 * [resultFor] may give a better answer for a particular dangling call than the generic one, e.g. an
 * `agent_spawn` whose helper was cut off with it (see [Subagents.interruptedResult]).
 */
fun recoverTranscript(history: List<Message>, resultFor: (Part.ToolCall) -> String? = { null }): Recovery {
    // A compaction checkpoint is bookkeeping, not a request waiting for an answer.
    val last = history.lastOrNull { m -> m.parts.none { it is Part.Checkpoint } } ?: return Recovery(emptyList(), unfinished = false)
    val answered = history.flatMap { it.toolResults }.map { it.callId }.toSet()
    val missing = history.flatMap { it.toolCalls }.filter { it.id !in answered }
        .map { Part.ToolResult(it.id, it.name, resultFor(it) ?: interruptedResult(it), isError = true) }
    // A turn that ended on a proposed plan is finished: it waits for the user, not the model.
    val waitsForUser = last.role == Role.TOOL && last.toolResults.any { it.toolName in TURN_ENDING_TOOLS && !it.isError }
    val finished = (last.role == Role.ASSISTANT && last.toolCalls.isEmpty()) || waitsForUser
    return Recovery(missing, unfinished = !finished)
}

/** The generic answer for a call cut off by the process dying; a question left open says it wasn't answered. */
private fun interruptedResult(call: Part.ToolCall): String =
    if (call.name == dev.locus.core.model.AgentControlTools.ASK_USER) dev.locus.core.agent.tools.AskUser.RESTARTED
    else "Not run: Locus was closed before this ran."

/** Tools with [Tool.endsTurn]; listed here because recovery sees only the transcript. */
internal val TURN_ENDING_TOOLS = setOf(dev.locus.core.model.AgentControlTools.PLAN_PROPOSE)
