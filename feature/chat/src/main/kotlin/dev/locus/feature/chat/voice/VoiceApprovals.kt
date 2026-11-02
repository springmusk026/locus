package dev.locus.feature.chat.voice

import dev.locus.core.agent.ApprovalFlag
import dev.locus.core.agent.ApprovalRequest
import dev.locus.feature.chat.SpeechText

/** What voice mode heard in answer to an approval. */
internal enum class VoiceReply { ALLOW, DENY, CONFIRM, UNCLEAR }

/**
 * Approvals in voice mode (docs/design/voice-mode.md). Only a plain Ask-level request may be answered
 * by voice, and then only with two steps: "allow" (or "yes"), then the confirmation word "confirm".
 * Anything flagged by taint, the safety reviewer, a tool's own confirmation (payment-like actions) or
 * a scope limit, and any request whose flags are unknown, needs a tap on the screen. Pure, so it is
 * unit tested.
 */
internal object VoiceApprovals {

    /** Why [request] can't be answered by voice, said aloud and shown; null when voice may answer it. */
    fun tapReason(request: ApprovalRequest): String? {
        val flags = request.flags
        return when {
            request.reviewerNote != null -> "The safety reviewer flagged this, so it needs a tap."
            flags == null -> "This needs a tap."
            ApprovalFlag.MUST_CONFIRM in flags -> "This looks like a payment or another action that must be confirmed, so it needs a tap."
            ApprovalFlag.TAINTED in flags -> "Locus read outside content during this task, so this needs a tap."
            ApprovalFlag.SKILL_SCOPE in flags || ApprovalFlag.ESCALATED in flags -> "This goes beyond what the task was set up to do, so it needs a tap."
            else -> null
        }
    }

    /** Whether voice may answer [request]. */
    fun voiceAllowed(request: ApprovalRequest): Boolean = tapReason(request) == null

    /** What the request would do, as speech: its summary's first line without Markdown. */
    fun spokenSummary(request: ApprovalRequest): String {
        val first = request.summary.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        val action = SpeechText.prepare(first).trimEnd('.', ' ').ifBlank { request.toolName.replace('_', ' ') }
        return request.origin?.let { "${it.label} wants to: $action" } ?: "Locus wants to: $action"
    }

    /** The first question, for a request voice may answer. */
    fun askPrompt(request: ApprovalRequest): String = "${spokenSummary(request)}. Say allow, or deny."

    /** After "allow": the confirmation step. */
    const val CONFIRM_PROMPT = "To allow it, say confirm. Or say no."

    /** For a request only a tap can answer. */
    fun tapPrompt(request: ApprovalRequest, reason: String): String = "${spokenSummary(request)}. $reason Tap Allow or Deny on the screen."

    /** After too many answers that weren't clear. */
    const val UNCLEAR_TAP = "I didn't catch that. Tap Allow or Deny on the screen."
    const val UNCLEAR_AGAIN = "Sorry, say allow, or deny."
    const val UNCLEAR_CONFIRM = "Sorry, say confirm to allow it, or no."

    /** Longer answers are conversation, not a yes or no: never read approval into them. */
    private const val MAX_WORDS = 6

    /**
     * Reads an answer. While [confirming], only the word "confirm" (or "confirmed") allows; "yes" alone
     * is not enough, so a stray yes can't approve. Any negation wins over a yes: "don't allow" denies,
     * and "yes no" is unclear rather than either.
     */
    fun parse(heard: String, confirming: Boolean): VoiceReply {
        val words = words(heard)
        if (words.isEmpty() || words.size > MAX_WORDS) return VoiceReply.UNCLEAR
        val negated = words.any { it in NEGATIONS }
        val yes = words.any { it in YES } || "go ahead" in words.joinToString(" ") || "do it" in words.joinToString(" ")
        val confirm = words.any { it in CONFIRMS }
        return when {
            // "don't allow", "do not confirm", "no": a negation decides, whatever else was said with it,
            // unless it comes with a plain yes word too ("yes no"), which is unclear.
            negated && (yes || confirm) && !negatesAction(words) -> VoiceReply.UNCLEAR
            negated -> VoiceReply.DENY
            confirming && confirm -> VoiceReply.CONFIRM
            confirming -> VoiceReply.UNCLEAR
            yes || confirm -> VoiceReply.ALLOW
            else -> VoiceReply.UNCLEAR
        }
    }

    /** "don't allow", "do not approve", "not confirmed", "never allow": a negation right before the action word. */
    private fun negatesAction(words: List<String>): Boolean = words.zipWithNext().any { (a, b) ->
        a in setOf("don't", "dont", "not", "never") && (b in YES || b in CONFIRMS)
    } || words.windowed(3).any { (a, b, c) -> a == "do" && b == "not" && (c in YES || c in CONFIRMS) }

    private fun words(text: String): List<String> =
        text.lowercase().replace('’', '\'').split(Regex("[^a-z']+")).map { it.trim('\'') }.filter { it.isNotEmpty() }

    private val YES = setOf("yes", "yeah", "yep", "yup", "allow", "allowed", "approve", "approved", "ok", "okay", "sure", "accept")
    private val CONFIRMS = setOf("confirm", "confirmed")
    private val NEGATIONS = setOf("no", "nope", "nah", "deny", "denied", "don't", "dont", "not", "cancel", "stop", "reject", "never", "decline")
}
