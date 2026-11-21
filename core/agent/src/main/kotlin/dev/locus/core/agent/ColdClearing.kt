package dev.locus.core.agent

import dev.locus.core.llm.ModelRequirements
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import dev.locus.core.model.startsTurn

/**
 * Clears old tool results from what a request sends, only where the provider's prompt cache has surely
 * expired (docs/design/cold-cache-clearing.md). Storage and the chat stay as they are: this is a view of
 * the transcript, applied where a request's messages are built.
 *
 * A *cold boundary* is a message that came more than [Policy.coldAfterMs] after the last assistant reply
 * before it. At each boundary, oldest first and cumulatively, the tool results before it are cleared when
 * there's enough to be worth a cache rewrite. The boundaries come from stored timestamps only, so the view
 * of a transcript is a pure function of it (and of the policy): every later request, in this run or after a
 * restart, sends the same bytes for the same messages, and the cache written after a boundary keeps hitting.
 *
 * Never for Anthropic ([policyFor]): on current Claude models an edited earlier `tool_result` invalidates the
 * thinking blocks after it, and Locus compacts on Anthropic's server there anyway.
 */
object ColdClearing {
    /**
     * How long a gap must be before Locus treats every provider's prompt cache for the chat as gone. OpenAI
     * keeps prompt caches up to 24 hours (`prompt_cache_retention: "24h"` on models before GPT-5.6, at least
     * 30 minutes on 5.6 and later); DeepSeek-style disk caches can last hours to days. 24 hours is past all of
     * them. Measured from the previous reply's end, while a cache's lifetime runs from its request's start,
     * so the gap is always a little longer than the cache's idle time.
     */
    const val COLD_AFTER_MS: Long = 24L * 60 * 60 * 1000

    /** Per-format overrides of [COLD_AFTER_MS]; every format uses the default today. */
    val COLD_AFTER_BY_FORMAT: Map<ApiFormat, Long> = emptyMap()

    /** The newest tool results before a boundary that always stay. */
    const val KEEP_RECENT = 5

    /** Results this long or shorter stay (a cleared note would save next to nothing). */
    const val MIN_RESULT_CHARS = 2_000

    /** A boundary clears nothing unless what it clears adds up to at least this many (estimated) tokens. */
    const val MIN_CLEARED_TOKENS = 20_000

    /** Four characters a token, as compaction estimates. */
    private const val CHARS_PER_TOKEN = 4

    /** What one image in a tool result counts as, in characters (an image costs about [ModelRequirements.IMAGE_TOKENS]). */
    const val IMAGE_CHARS = ModelRequirements.IMAGE_TOKENS * CHARS_PER_TOKEN

    /** How every cleared result's text starts. */
    const val PREFIX = "[Earlier result cleared: "

    private val CLEARED = Regex("""^\[Earlier result cleared: ([\d,]+) characters?(?: and (\d+) images?)?\.""")

    data class Policy(
        val coldAfterMs: Long = COLD_AFTER_MS,
        val keepRecent: Int = KEEP_RECENT,
        val minResultChars: Int = MIN_RESULT_CHARS,
        val minClearedTokens: Int = MIN_CLEARED_TOKENS,
    )

    /** How many results a request's view cleared and about how many tokens that saved. */
    data class Cleared(val count: Int, val tokens: Int)

    /**
     * The policy for a request served in [format], or null for none: when the setting is off ([enabled]), and
     * always for Anthropic Messages. On-device models get it too: their KV cache lives in memory only, and a
     * gap of a day has long since dropped it.
     */
    fun policyFor(format: ApiFormat?, enabled: Boolean): Policy? = when {
        !enabled || format == null -> null
        format == ApiFormat.ANTHROPIC_MESSAGES -> null
        // Not yet: Locus replays encrypted reasoning items statelessly there, and whether OpenAI accepts an
        // edited earlier function_call_output beside them hasn't been checked with a live key
        // (docs/design/cold-cache-clearing.md, "Not done").
        format == ApiFormat.OPENAI_RESPONSES || format == ApiFormat.CHATGPT -> null
        else -> Policy(coldAfterMs = COLD_AFTER_BY_FORMAT[format] ?: COLD_AFTER_MS)
    }

    /**
     * [messages] as a request sends them: tool results before each cold boundary cleared under [policy];
     * [messages] itself when [policy] is null or nothing is cleared. Everything after the latest boundary is
     * as stored.
     */
    fun view(messages: List<Message>, policy: Policy?): List<Message> {
        if (policy == null || messages.size < 2) return messages
        val cleared = clearedPositions(messages, policy)
        if (cleared.isEmpty()) return messages
        return messages.mapIndexed { i, m ->
            val parts = cleared[i] ?: return@mapIndexed m
            m.copy(parts = m.parts.mapIndexed { p, part -> if (p in parts && part is Part.ToolResult) clear(part) else part })
        }
    }

    /**
     * The indices of cold boundaries in [messages], oldest first. A checkpoint's view is never a boundary itself.
     * The view of a summary of the later part ([Part.Checkpoint.from], docs/design/rewind.md) also counts like a
     * reply for measuring the gap: its summary request read the cache, kept messages included, when it was
     * written. (An ordinary checkpoint's view starts the context, so nothing before it could be cleared.)
     */
    fun boundaries(messages: List<Message>, coldAfterMs: Long = COLD_AFTER_MS): List<Int> {
        val out = mutableListOf<Int>()
        var lastReplyAt: Long? = null
        messages.forEachIndexed { i, m ->
            val checkpoint = m.parts.filterIsInstance<Part.Checkpoint>().lastOrNull()
            if (checkpoint != null) {
                if (checkpoint.from != null) lastReplyAt = m.createdAt
                return@forEachIndexed
            }
            val replied = lastReplyAt
            if (replied != null && m.createdAt - replied > coldAfterMs) {
                out += i
                // One boundary per gap: the messages after it, up to the next reply, are past the same gap.
                lastReplyAt = null
            }
            if (m.role == Role.ASSISTANT) lastReplyAt = m.createdAt
        }
        return out
    }

    /** Message index to the indices of its parts to clear. */
    private fun clearedPositions(messages: List<Message>, policy: Policy): Map<Int, Set<Int>> {
        val boundaries = boundaries(messages, policy.coldAfterMs)
        // Every tool result in order: (message index, part index).
        val results = messages.flatMapIndexed { i, m -> m.parts.mapIndexedNotNull { p, part -> if (part is Part.ToolResult) Triple(i, p, part) else null } }
        // Results a summary of the later part found cleared in what was last sent: cleared from the start, so the
        // messages it kept go out as they did ([Part.Checkpoint.clearedResults]).
        val precleared = messages.mapIndexedNotNull { i, m -> m.parts.filterIsInstance<Part.Checkpoint>().lastOrNull()?.let { i to it.clearedResults.toSet() } }
            .filter { it.second.isNotEmpty() }
        val cleared = results.filter { (i, _, r) -> precleared.any { (at, ids) -> i < at && r.callId in ids } }.map { it.first to it.second }.toMutableSet()
        if (boundaries.isEmpty()) return cleared.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }
        for (b in boundaries) {
            val before = results.filter { it.first < b }
            val recent = before.takeLast(policy.keepRecent).map { it.first to it.second }.toSet()
            // The task in progress at the boundary: from the message that started it (the boundary itself when it's the user's).
            val taskStart = (b downTo 0).firstOrNull { messages[it].startsTurn() } ?: 0
            val fresh = before.filter { (i, p, r) ->
                i < taskStart && (i to p) !in recent && (i to p) !in cleared && !r.isError && weight(r) > policy.minResultChars
            }
            val saved = fresh.sumOf { (_, _, r) -> (weight(r) - clear(r).content.length).coerceAtLeast(0).toLong() }
            if (saved / CHARS_PER_TOKEN >= policy.minClearedTokens) fresh.forEach { cleared += it.first to it.second }
        }
        return cleared.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }
    }

    /**
     * The call ids of the tool results before [end] in [messages] that [policy]'s view clears: what a summary of
     * the part from [end] on keeps cleared ([Part.Checkpoint.clearedResults]). Empty with no policy.
     */
    fun clearedBefore(messages: List<Message>, end: Int, policy: Policy?): List<String> {
        if (policy == null) return emptyList()
        val positions = clearedPositions(messages, policy)
        return positions.filterKeys { it < end }.flatMap { (i, parts) -> parts.mapNotNull { (messages[i].parts[it] as? Part.ToolResult)?.callId } }.distinct()
    }

    /** What a result weighs: its text, and each image as [IMAGE_CHARS]. */
    private fun weight(r: Part.ToolResult): Int = r.content.length + r.images.size * IMAGE_CHARS

    /** [r] cleared: the same call, tool and error flag, a short English note instead of its content and images. */
    fun clear(r: Part.ToolResult): Part.ToolResult = r.copy(content = note(r.content.length, r.images.size), images = emptyList())

    /**
     * The note a cleared result sends, e.g. "[Earlier result cleared: 48,210 characters. Run the tool again if
     * you need it.]". English and grouped with commas whatever the phone's language, so it's byte-stable.
     */
    fun note(chars: Int, images: Int = 0): String = buildString {
        append(PREFIX).append(grouped(chars)).append(if (chars == 1) " character" else " characters")
        if (images > 0) append(" and ").append(images).append(if (images == 1) " image" else " images")
        append(". Run the tool again if you need it.]")
    }

    /** [n] with a comma every three digits, never the locale's separators. */
    fun grouped(n: Int): String {
        val digits = kotlin.math.abs(n.toLong()).toString()
        val out = StringBuilder()
        digits.forEachIndexed { i, c ->
            if (i > 0 && (digits.length - i) % 3 == 0) out.append(',')
            out.append(c)
        }
        return if (n < 0) "-$out" else out.toString()
    }

    /**
     * How many of [messages]' tool results a view cleared, and about how many tokens that saved (the original
     * size, read back from each note, less the note); null when none was. For "What was sent".
     */
    fun clearedIn(messages: List<Message>): Cleared? {
        var count = 0
        var chars = 0L
        for (m in messages) for (part in m.parts) {
            if (part !is Part.ToolResult) continue
            val match = CLEARED.find(part.content) ?: continue
            val original = match.groupValues[1].replace(",", "").toLongOrNull() ?: continue
            val images = match.groupValues[2].toLongOrNull() ?: 0L
            count++
            chars += (original + images * IMAGE_CHARS - part.content.length).coerceAtLeast(0)
        }
        return if (count == 0) null else Cleared(count, (chars / CHARS_PER_TOKEN).toInt())
    }
}
