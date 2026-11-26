package dev.locus.core.agent

import dev.locus.core.model.ApprovalDecision
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
 *
 * [audited] is what the audit log holds for the dangling calls (call id to decision), after the process
 * died: a call gets its entry when it finishes, and a turn's calls start in order, so a call with an entry
 * finished (it ran, or was refused), and the first call of a turn without one is the one that was running
 * (it may have taken effect), and the calls after it never started. Null when that isn't known (a branch
 * switch, a rewind, a fork): every dangling call then reads as not run, as before.
 */
fun recoverTranscript(
    history: List<Message>,
    audited: Map<String, ApprovalDecision>? = null,
    resultFor: (Part.ToolCall) -> String? = { null },
): Recovery {
    // A compaction checkpoint is bookkeeping, not a request waiting for an answer.
    val last = history.lastOrNull { m -> m.parts.none { it is Part.Checkpoint } } ?: return Recovery(emptyList(), unfinished = false)
    val answered = history.flatMap { it.toolResults }.map { it.callId }.toSet()
    val missing = history.flatMap { m ->
        val calls = m.toolCalls
        // The turn's first call without an audit entry: the one running when Locus was closed.
        val running = audited?.let { a -> calls.firstOrNull { it.id !in answered && it.id !in a }?.id }
        calls.filter { it.id !in answered }.map { call ->
            val text = resultFor(call) ?: when {
                call.name == dev.locus.core.model.AgentControlTools.ASK_USER || audited == null -> interruptedResult(call)
                call.id in audited -> if (audited.getValue(call.id) in AgentLoop.RAN) RAN_BEFORE_CLOSE else interruptedResult(call)
                call.id == running -> CLOSED_WHILE_RUNNING
                else -> interruptedResult(call)
            }
            Part.ToolResult(call.id, call.name, text, isError = true)
        }
    }
    // A turn that ended on a proposed plan is finished: it waits for the user, not the model.
    val waitsForUser = last.role == Role.TOOL && last.toolResults.any { it.toolName in TURN_ENDING_TOOLS && !it.isError }
    val finished = (last.role == Role.ASSISTANT && last.toolCalls.isEmpty()) || waitsForUser
    return Recovery(missing, unfinished = !finished)
}

/** The generic answer for a call cut off by the process dying; a question left open says it wasn't answered. */
private fun interruptedResult(call: Part.ToolCall): String =
    if (call.name == dev.locus.core.model.AgentControlTools.ASK_USER) dev.locus.core.agent.tools.AskUser.RESTARTED
    else "Not run: Locus was closed before this ran."

/** A dangling call the audit log says ran: only its result was lost. */
internal const val RAN_BEFORE_CLOSE =
    "Locus was closed after this ran, before its result was saved. It did run: check what it did instead of running it again."

/** The dangling call that was running when the process died: it may have taken effect. */
internal const val CLOSED_WHILE_RUNNING =
    "Locus was closed while this was running; it may have completed. Check before running it again."

/** Tools with [Tool.endsTurn]; listed here because recovery sees only the transcript. */
internal val TURN_ENDING_TOOLS = setOf(dev.locus.core.model.AgentControlTools.PLAN_PROPOSE)
