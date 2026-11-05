package dev.locus.core.agent

import dev.locus.core.model.ApprovalDecision
import dev.locus.core.model.Part

/**
 * Stops a run whose model keeps calling tools that can't run, instead of letting it spin
 * (docs/design/model-capabilities.md, "Small models and tools").
 *
 * A chat's tool list is fixed when it's created, so a tool the user switched off stays declared and is
 * refused when called ([ChatPolicy]). Large models read the refusal and answer; small on-device ones
 * often call again, and again: a 0.8B model made 26 refused calls in a row on a one-line question. The
 * refusal can't remove the tool (that would break the prompt cache), so the loop has to end the turn.
 *
 * Two rules, checked once a step's calls all have results:
 *
 * - **Refused steps.** [REFUSED_STEPS] steps in a row in which every call was refused before it ran:
 *   a chat or project limit, Deny, read-only ([ApprovalDecision.POLICY_DENIED]), or a tool the chat
 *   doesn't have. Each refusal already told the model to answer without that tool, so a model that has
 *   ignored it twice won't stop on its own. Counted by step, not by call, so a model that calls three
 *   switched-off tools side by side and then answers isn't stopped.
 * - **Repeated failures.** The same call (tool and arguments) failing the same way [REPEATED_FAILURES]
 *   times in a row. Gemini CLI's loop detection stops at 5 identical consecutive calls
 *   (`TOOL_CALL_LOOP_THRESHOLD`, packages/core/src/services/loopDetectionService.ts); here only failures
 *   count, because polling a job or a helper with the same call is how waiting works.
 *
 * The user's own answers never count: a call they declined, or one the safety reviewer blocked (the
 * reviewer has its own breaker, which hands the decision to the user).
 */
internal class ToolLoopGuard {
    private var refusedSteps = 0
    private var lastFailure: Triple<String, String, String>? = null
    private var repeats = 0

    /** Records one step's [calls] and their [results]; returns why to stop, or null to carry on. */
    fun afterStep(calls: List<Part.ToolCall>, results: List<Part.ToolResult>): ToolLoop? {
        if (results.isEmpty()) return null
        val byId = calls.associateBy { it.id }
        refusedSteps = if (results.all(::refused)) refusedSteps + 1 else 0
        for (r in results) {
            val call = byId[r.callId]
            val key = if (r.isError && call != null) Triple(call.name, call.argumentsJson.trim(), r.content) else null
            repeats = if (key != null && key == lastFailure) repeats + 1 else if (key != null) 1 else 0
            lastFailure = key
        }
        val tool = results.last().toolName
        return when {
            refusedSteps >= REFUSED_STEPS -> ToolLoop(ToolLoop.Kind.REFUSED, tool, refusedSteps)
            repeats >= REPEATED_FAILURES -> ToolLoop(ToolLoop.Kind.REPEATED, tool, repeats)
            else -> null
        }
    }

    companion object {
        const val REFUSED_STEPS = 3
        const val REPEATED_FAILURES = 5

        /** Refused before it ran, by a rule rather than by the user or the reviewer. */
        fun refused(r: Part.ToolResult): Boolean =
            r.decision == ApprovalDecision.POLICY_DENIED ||
                (r.decision == ApprovalDecision.INVALID_INPUT && r.content.startsWith(UNKNOWN_TOOL))

        /** How a call of a tool the chat doesn't have is answered (see [AgentLoop]). */
        const val UNKNOWN_TOOL = "Unknown tool"
    }
}

/** Why [ToolLoopGuard] stopped a run: [count] refused steps or identical failures, the last on [tool]. */
data class ToolLoop(val kind: Kind, val tool: String, val count: Int) {
    enum class Kind { REFUSED, REPEATED }

    /** What the chat shows. */
    val message: String get() = when (kind) {
        Kind.REFUSED ->
            "Stopped: this model kept calling tools it can't use in this chat ($count times in a row). " +
                "Switch to a larger model, or turn tools on in the tools menu."
        Kind.REPEATED ->
            "Stopped: this model repeated the same failing call to $tool $count times. " +
                "Rephrase the request, or switch to a larger model."
    }
}
