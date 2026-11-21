package dev.locus.core.agent

import dev.locus.core.model.HiddenText

/**
 * The user's answer to an approval: [answer], and for a denial the short [reason] they typed on the
 * in-app card, or null. A reason on an allowing answer is ignored.
 */
data class ApprovalReply(val answer: ApprovalAnswer, val reason: String? = null)

/**
 * A reason the user gives when they deny a call ("Don't email them, use Slack"), passed to the model in
 * that call's result so it can take another route. It is the user's own words, typed in Locus, so it is
 * read as the user speaking, not as outside content: it adds no taint and no untrusted-data wrapper.
 * Only the in-app approval card offers it (docs/design/remembered-approvals.md, "Deny with a reason").
 */
object DenyReason {
    /** The longest reason kept, in characters: a sentence or two, not a new task. */
    const val MAX_CHARS = 300

    /** What the model reads for a denied call without a reason: unchanged, so existing requests stay byte-identical. */
    const val DECLINED = "The user declined this action. Ask them how they would like to proceed."

    /**
     * [text] as the model gets it: hidden characters out, every run of white space (line breaks included)
     * one space, trimmed, and cut to [MAX_CHARS] without splitting a surrogate pair. Null when nothing is left.
     */
    fun clean(text: String?): String? {
        if (text == null) return null
        val flat = HiddenText.strip(text).replace(SPACES, " ").trim()
        if (flat.isEmpty()) return null
        if (flat.length <= MAX_CHARS) return flat
        var end = MAX_CHARS
        if (Character.isHighSurrogate(flat[end - 1])) end--
        return flat.substring(0, end).trimEnd()
    }

    /** The denied call's result: exactly [DECLINED] without a reason, else the user's words. */
    fun result(reason: String?): String {
        val said = clean(reason) ?: return DECLINED
        return "The user declined this action and said: \"$said\"\nTake that into account: change course as they asked, or ask them if it's unclear how to proceed."
    }

    private val SPACES = Regex("\\s+")
}
