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
    fun tapReason(request: ApprovalRequest, words: VoiceWords = VoiceWords()): String? {
        val flags = request.flags
        return when {
            // Voice reads only the one-line summary; the note that would be saved is on the card
            // (docs/design/memory-approval.md). Whatever the reason it asks.
            isMemoryChange(request) -> words.tapMemory
            request.reviewerNote != null -> words.tapReviewer
            flags == null -> words.tapUnknown
            ApprovalFlag.MUST_CONFIRM in flags -> words.tapMustConfirm
            ApprovalFlag.TAINTED in flags -> words.tapTainted
            ApprovalFlag.PRIVATE_DATA in flags -> words.tapPrivateData
            ApprovalFlag.SKILL_SCOPE in flags || ApprovalFlag.ESCALATED in flags -> words.tapScope
            else -> null
        }
    }

    /** A memory_save or memory_forget card: what it writes is only readable on screen. */
    fun isMemoryChange(request: ApprovalRequest): Boolean = request.preview?.memory != null || request.toolName in MEMORY_TOOLS

    private val MEMORY_TOOLS = setOf("memory_save", "memory_forget")

    /** Whether voice may answer [request]. */
    fun voiceAllowed(request: ApprovalRequest, words: VoiceWords = VoiceWords()): Boolean = tapReason(request, words) == null

    /**
     * What the request would do, as speech: its summary's first line without Markdown, the tool's line
     * in the app's language when it has one ([VoiceWords.approvalLine]).
     */
    fun spokenSummary(request: ApprovalRequest, words: VoiceWords = VoiceWords()): String {
        val first = words.approvalLine(request).lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        val action = SpeechText.prepare(first, words.speech).trimEnd('.', ' ').ifBlank { request.toolName.replace('_', ' ') }
        return request.origin?.let { words.fill(words.helperWants, it.label, action) } ?: words.fill(words.locusWants, action)
    }

    /** The first question, for a request voice may answer. */
    fun askPrompt(request: ApprovalRequest, words: VoiceWords = VoiceWords()): String = words.fill(words.askPrompt, spokenSummary(request, words))

    /** For a request only a tap can answer. */
    fun tapPrompt(request: ApprovalRequest, reason: String, words: VoiceWords = VoiceWords()): String =
        words.fill(words.tapPrompt, spokenSummary(request, words), reason)

    /** Longer answers are conversation, not a yes or no: never read approval into them. */
    private const val MAX_WORDS = 6

    /**
     * Reads an answer, in English or the app's language ([answers]). While [confirming], only a confirm
     * word ("confirm", "confirmar", "确认") allows; "yes" alone is not enough, so a stray yes can't
     * approve. Any negation wins over a yes: "don't allow" denies, and "yes no" is unclear rather than
     * either. Chinese has no spaces between words, so there a word counts when it is in what was heard.
     */
    fun parse(heard: String, confirming: Boolean, answers: VoiceAnswers = VoiceAnswers()): VoiceReply {
        val words = words(heard)
        if (words.isEmpty() || words.size > MAX_WORDS) return VoiceReply.UNCLEAR
        val joined = words.joinToString(" ")
        val negated = words.any { has(it, answers.negations) }
        val yes = words.any { has(it, answers.yes) } || answers.yesPhrases.any { it in joined }
        val confirm = words.any { has(it, answers.confirms) }
        return when {
            // "don't allow", "do not confirm", "no": a negation decides, whatever else was said with it,
            // unless it comes with a plain yes word too ("yes no"), which is unclear.
            negated && (yes || confirm) && !negatesAction(words, answers) -> VoiceReply.UNCLEAR
            negated -> VoiceReply.DENY
            confirming && confirm -> VoiceReply.CONFIRM
            confirming -> VoiceReply.UNCLEAR
            yes || confirm -> VoiceReply.ALLOW
            else -> VoiceReply.UNCLEAR
        }
    }

    /** [word] is one of [set], or, in a language written without spaces, holds one. */
    private fun has(word: String, set: Set<String>): Boolean = word in set || (unspaced(word) && set.any { unspaced(it) && it in word })

    /** "don't allow", "do not approve", "not confirmed", "never allow", "不允许": a negation right before the action word. */
    private fun negatesAction(words: List<String>, answers: VoiceAnswers): Boolean {
        val action = answers.yes + answers.confirms
        return words.zipWithNext().any { (a, b) -> a in answers.negators && (b in action) } ||
            words.windowed(3).any { (a, b, c) -> a == "do" && b == "not" && (c in action) } ||
            words.any { w -> unspaced(w) && answers.negators.any { n -> unspaced(n) && action.any { unspaced(it) && "$n$it" in w } } }
    }

    private fun words(text: String): List<String> =
        VoiceAnswers.normalize(text).split(NOT_LETTERS).map { it.trim('\'') }.filter { it.isNotEmpty() }

    /** Han, kana and Hangul: written without spaces between words. */
    private fun unspaced(word: String): Boolean = word.any { Character.UnicodeScript.of(it.code) in UNSPACED }

    private val UNSPACED = setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL)
    private val NOT_LETTERS = Regex("[^\\p{L}']+")
}
