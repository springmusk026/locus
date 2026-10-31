package dev.locus.core.agent

/**
 * Marking for text from outside in tool results (search results, mail, issues, build logs): the
 * prompt-injection defence the system prompt refers to. The text could contain the closing tag itself
 * to end the block early and pose as Locus or the user, so any such tag, in any case or spacing, is
 * defused first. The browser's own wrapper (core:browser `Untrusted`) follows the same rule.
 */
object UntrustedContent {
    const val OPEN = "<untrusted_web_content>"
    const val CLOSE = "</untrusted_web_content>"

    /** [text] inside the markers, followed by [note] saying where it came from. */
    fun wrap(text: String, note: String): String = "$OPEN\n${defuse(text)}\n$CLOSE\n$note"

    fun defuse(text: String): String = TAG.replace(text) { m -> m.value.replace("<", "‹").replace(">", "›") }

    private val TAG = Regex("<\\s*/?\\s*untrusted_web_content\\s*>", RegexOption.IGNORE_CASE)
}
