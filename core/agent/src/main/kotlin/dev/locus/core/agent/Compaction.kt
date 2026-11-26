package dev.locus.core.agent

import dev.locus.core.agent.tools.Todos
import dev.locus.core.llm.ChatRequest
import dev.locus.core.model.Message
import dev.locus.core.model.ModeNote
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import dev.locus.core.model.TodoItem
import dev.locus.core.model.TodoStatus
import dev.locus.core.model.TokenUsage
import dev.locus.core.model.ToolSpec
import dev.locus.core.model.startsTurn

/**
 * How a run compacts its transcript while it works (see [AgentConfig.compaction] and
 * docs/design/compaction.md). Null [contextWindow] and [ceiling] turn off compacting at a threshold (the
 * window is unknown, or the provider compacts on its own) but keep the recovery from a context-overflow error.
 */
class CompactionSetup(
    /** The model's context window in tokens, or null. */
    val contextWindow: Int?,
    /** The last message of the summary request ([dev.locus.core.model.HelperPrompts.compaction]). */
    val prompt: String,
    /** Compact once the last request used this share of [contextWindow]. */
    val threshold: Double = Compaction.THRESHOLD,
    /**
     * Compact before the prompt passes this many tokens even when the window has room: just below the
     * model's long-context price step ([dev.locus.core.model.PriceSteps.ceiling]), past which every token
     * of a request costs more. Null when the price doesn't step.
     */
    val ceiling: Int? = null,
    /**
     * Start the summary in the background at [earlyLimit] while the run goes on, so it's usually ready by the time
     * the run reaches [limit] (docs/design/compaction.md, "Starting the summary early"). Only where the serving
     * model's format allows keeping the turns written meanwhile after it ([Compaction.keepsTurnsAfterSummary]).
     */
    val background: Boolean = false,
) {
    /** Where compaction starts, in tokens: the lower of the window's [threshold] and the [ceiling]; null for never. */
    val limit: Int?
        get() = listOfNotNull(contextWindow?.takeIf { it > 0 }?.let { (it * threshold).toInt() }, ceiling?.takeIf { it > 0 }).minOrNull()

    /** Where a [background] summary starts, in tokens: [Compaction.EARLY_SHARE] of [limit] (70% of the window at the 80% threshold). */
    val earlyLimit: Int?
        get() = limit?.let { (it * Compaction.EARLY_SHARE).toInt() }
}

/**
 * Compaction: the model summarises the transcript so far into a [Part.Checkpoint], which the store
 * keeps (history stays append-only) and from then on stands in for everything before it.
 *
 * What the model sees of a checkpoint ([view]) is the summary, then the user's latest messages before
 * it word for word, then the checklist as it stood, then where to find the rest. Keeping the user's
 * own messages follows Codex (`codex-rs/core/src/compact.rs`: `COMPACT_USER_MESSAGE_MAX_TOKENS =
 * 20_000`, newest first, the last one that doesn't fit is cut to the space left). The view is a pure
 * function of the stored checkpoint, so a run that compacts in memory and a later run that rebuilds
 * the context from storage ([modelContext]) send byte-identical text, and the cached prefix holds.
 */
object Compaction {
    /** Compact once the last request used this share of the context window. */
    const val THRESHOLD = 0.8

    /** The user's own messages kept verbatim after a summary, in (estimated) tokens. */
    const val KEEP_USER_TOKENS = 20_000

    /**
     * Where a background summary starts, as a share of where compaction is due ([CompactionSetup.limit]): 70% of
     * the window at the 80% threshold, as Codex starts its mid-turn compaction early (`async_compaction`).
     */
    const val EARLY_SHARE = 0.875

    /**
     * A background summary is used only while what the run added after it started, plus the summary's own view,
     * stays under this share of [CompactionSetup.limit]; past that it's dropped for an ordinary summary, which
     * leaves a smaller context.
     */
    const val KEPT_AFTER_SHARE = 0.5

    /**
     * Whether a summary may keep the turns written after the point it covers, word for word after it, on a
     * model of [format]. Only Chat Completions: there Locus replays no reasoning, so a kept turn doesn't depend on
     * the exact transcript before it. Anthropic's preserved thinking is checked against everything before the
     * thinking block, and Responses (and the ChatGPT plan) replay encrypted reasoning items, which Locus never sends
     * after a changed prefix (docs/design/rewind.md, "Prefix byte-identity per provider"). On-device models don't
     * compact at a threshold.
     */
    fun keepsTurnsAfterSummary(format: dev.locus.core.model.ApiFormat?): Boolean = format == dev.locus.core.model.ApiFormat.OPENAI_CHAT

    /** A reply shorter than this isn't a summary. */
    const val MIN_SUMMARY_CHARS = 40

    const val HEADER = "[Summary of the earlier part of this chat, which you no longer see in full]"

    /**
     * Whether a request that used [usage] filled enough of the window to compact before the next one;
     * [pendingTokens] is what the next request adds on top (the tool results just appended).
     */
    fun due(usage: TokenUsage?, setup: CompactionSetup, pendingTokens: Int = 0, early: Boolean = false): Boolean {
        val limit = (if (early) setup.earlyLimit else setup.limit) ?: return false
        val used = usage?.context ?: return false
        return used + pendingTokens > limit
    }

    /**
     * The summary request: the run's own system prompt, transcript, tools, cache key and task budget,
     * exactly as its next turn would send them, so it reads the provider's prompt cache, then one user
     * message asking for the summary.
     */
    fun request(
        system: String,
        transcript: List<Message>,
        tools: List<ToolSpec>,
        cacheKey: String?,
        taskBudget: Long?,
        prompt: String,
        ask: Message,
        /** The next turn's [ColdClearing] policy, so the summary request sends the same view of the transcript. */
        clearing: ColdClearing.Policy? = null,
    ): ChatRequest {
        require(ask.role == Role.USER)
        val withPrompt = ask.copy(parts = listOf(Part.Text(prompt)))
        return ChatRequest(system, ColdClearing.view(transcript.map { it.forModel() }, clearing) + withPrompt, tools, cacheKey, taskBudget = taskBudget)
    }

    /** How the view of a summary of the later part starts (Summarise from here): the messages before it are kept. */
    const val PARTIAL_HEADER = "[Summary of the rest of this chat from here on, which you no longer see in full; everything before this point is kept as it was]"

    /**
     * What the model sees of a chat: the messages in order, each checkpoint replacing what it stands in for
     * with its [view]. A checkpoint replaces everything before it; one written by Summarise from here
     * ([Part.Checkpoint.from]) replaces only the context from that message on, and an edited summary
     * ([Part.Checkpoint.supersedes]) replaces the view it corrects. So the messages before a partial summary
     * are sent exactly as they were before it (docs/design/rewind.md). A summary written in the background
     * ([Part.Checkpoint.coversUpTo]) replaces only the messages up to the one it names; the ones the run added
     * meanwhile follow its view. Earlier messages stay in the chat for the user. Kept reasoning never goes back.
     */
    fun modelContext(all: List<Message>): List<Message> {
        val history = all.map { it.forModel() }
        if (history.none { m -> m.parts.any { it is Part.Checkpoint } }) return history
        val context = ArrayList<Message>(history.size)
        for (m in history) {
            if (m.parts.none { it is Part.Checkpoint }) context += m else place(context, m)
        }
        return context
    }

    /**
     * Puts the view of checkpoint [message] into [context] (the model's context so far) in place of what it stands
     * in for: from the start, or from [Part.Checkpoint.from], or the view it corrects ([Part.Checkpoint.supersedes]);
     * to the end, or to [Part.Checkpoint.coversUpTo]. A running task applies a background summary with this too, so
     * its transcript is exactly what [modelContext] rebuilds from storage.
     */
    fun place(context: MutableList<Message>, message: Message) {
        val checkpoint = message.parts.filterIsInstance<Part.Checkpoint>().last()
        // Where this checkpoint starts replacing; a start that isn't in the context (never on a consistent
        // path) replaces everything, which is always a valid request.
        val found = (checkpoint.supersedes ?: checkpoint.from)?.let { id -> context.indexOfFirst { it.id == id } }?.takeIf { it >= 0 }
        val start = found ?: 0
        val covers = checkpoint.coversUpTo
        val end = when {
            covers == null -> context.size
            // An edited background summary replaces only the view it corrects; the messages kept after it stay.
            checkpoint.supersedes != null -> found?.plus(1) ?: context.size
            else -> context.indexOfFirst { it.id == covers }.takeIf { it >= start }?.plus(1) ?: context.size
        }
        context.subList(start, end).clear()
        context.add(start, view(message))
    }

    /**
     * The checkpoint message as the model sees it: the rendered text, with the checkpoint kept beside
     * it (providers send only text parts) so a later compaction can carry its kept messages forward
     * and the safety reviewer knows it isn't something the user typed.
     */
    fun view(message: Message): Message {
        val checkpoint = message.parts.filterIsInstance<Part.Checkpoint>().last()
        return message.copy(parts = listOf(Part.Text(render(message.conversationId, checkpoint)), checkpoint))
    }

    fun render(chatId: String, checkpoint: Part.Checkpoint): String = buildString {
        append(if (checkpoint.from != null) PARTIAL_HEADER else HEADER).append("\n\n").append(checkpoint.summary.trim())
        if (checkpoint.recentUserMessages.isNotEmpty()) {
            append("\n\n<recent_user_messages>\nThe user's latest messages before this summary, word for word, oldest first:")
            checkpoint.recentUserMessages.forEach { append("\n\n<message>\n").append(it).append("\n</message>") }
            append("\n</recent_user_messages>")
        }
        checkpoint.todos?.takeIf { it.isNotBlank() }?.let { append("\n\n<todo_list>\nYour checklist at this point:\n").append(it).append("\n</todo_list>") }
        append("\n\nThe full earlier transcript is still on the phone: chat_history_read with id \"").append(chatId)
        append("\" reads it (offset to page through it, include_tool_results to see what tools returned). ")
        if (checkpoint.coversUpTo != null) {
            append("The messages after this summary came later and are kept word for word: carry on from the latest of them.")
        } else {
            append("If you were in the middle of a task, carry on from where this leaves off.")
        }
    }

    /** The checkpoint for [summary] of [transcript] (what the model saw, a previous checkpoint's view first). */
    fun checkpoint(summary: String, transcript: List<Message>, contextWindow: Int? = null, tainted: Set<String> = emptySet()): Part.Checkpoint =
        Part.Checkpoint(
            summary.trim(), transcript.size, keep(userMessages(transcript), keptTokens(contextWindow)), todos(transcript),
            // The summary may repeat outside text it read: a run that starts from it is tainted too.
            untrustedSources = (tainted + Taint.sourcesIn(transcript)).sorted(),
        )

    /**
     * How much of the user's own text a checkpoint keeps: [KEEP_USER_TOKENS], or an eighth of a smaller
     * window, so the view itself stays well under the threshold (a 32k Ollama model keeps 4k).
     */
    fun keptTokens(contextWindow: Int?): Int =
        contextWindow?.takeIf { it > 0 }?.let { minOf(KEEP_USER_TOKENS, it / 8) } ?: KEEP_USER_TOKENS

    /**
     * What the user wrote in [transcript], oldest first: a previous checkpoint's kept messages, then
     * each user message's own text (Locus's notes and results left out), attachments by name.
     */
    fun userMessages(transcript: List<Message>): List<String> = buildList {
        for (m in transcript) {
            val previous = m.parts.filterIsInstance<Part.Checkpoint>().lastOrNull()
            if (previous != null) {
                addAll(previous.recentUserMessages)
                continue
            }
            if (m.role != Role.USER || m.parts.any { it is Part.Text && ModeNote.isResult(it.text) }) continue
            val text = m.userText.trim()
            val names = m.attachments.map { it.workspacePath }
            val line = when {
                names.isEmpty() -> text
                else -> (text + "\n[attached: ${names.joinToString(", ")}]").trim()
            }
            // Text another app sent (docs/design/android-bridge.md) stays marked as such after the summary.
            if (line.isNotEmpty()) add(if (ModeNote.isFromAnotherApp(m)) "[sent by ${ModeNote.externalApp(m)}, not typed by the user] $line" else line)
        }
    }

    /** The newest of [messages] that fit in [maxTokens], oldest first; the oldest kept one may be cut to fit. */
    fun keep(messages: List<String>, maxTokens: Int = KEEP_USER_TOKENS): List<String> {
        val kept = ArrayDeque<String>()
        var left = maxTokens
        for (m in messages.asReversed()) {
            if (left <= 0) break
            val tokens = estimateTokens(m)
            if (tokens <= left) {
                kept.addFirst(m)
                left -= tokens
            } else {
                kept.addFirst(cutTokens(m, left))
                break
            }
        }
        return kept.toList()
    }

    /**
     * The checklist at the end of [transcript]: the latest successful `todo_write` after the latest
     * checkpoint view, or, when there's none since, the one that checkpoint kept; before a summary of the
     * later part (which keeps the messages before it), the list the kept messages last wrote. Null when
     * there's no list (or it was emptied).
     */
    fun todos(transcript: List<Message>): String? {
        val at = transcript.indexOfLast { m -> m.parts.any { it is Part.Checkpoint } }
        val latest = Todos.latest(if (at < 0) transcript else transcript.drop(at + 1))
        if (latest != null) return latest.takeIf { it.isNotEmpty() }?.joinToString("\n", transform = ::todoLine)
        if (at < 0) return null
        val checkpoint = transcript[at].parts.filterIsInstance<Part.Checkpoint>().last()
        checkpoint.todos?.let { return it }
        if (checkpoint.from == null) return null
        return todos(transcript.take(at))
    }

    private fun todoLine(item: TodoItem): String = when (item.status) {
        TodoStatus.COMPLETED -> "- [done] "
        TodoStatus.IN_PROGRESS -> "- [in progress] "
        TodoStatus.PENDING -> "- [pending] "
    } + item.content

    // ---- Summarise from here (docs/design/rewind.md) ------------------------------------------------

    /** Why Summarise from here isn't offered on a message. */
    enum class FromUnavailable {
        /** An earlier summary already stands in for this message: the model doesn't see it any more. */
        SUMMARISED,

        /** Nothing comes after this reply to summarise. */
        NOTHING_AFTER,

        /** Too little from here on to be worth a summary. */
        TOO_LITTLE,
    }

    /** Where a summary from a message starts in the model's context ([start]), or why it can't ([unavailable]). */
    data class From(val start: Int, val unavailable: FromUnavailable? = null)

    /**
     * Where Summarise from here on [messageId] starts in [context] (the model's context, [modelContext]): a
     * message the user sent starts the summarised part; a reply ends the part that's kept (with the rest of
     * its turn), so the summary starts at the next message that starts a turn.
     */
    fun from(context: List<Message>, messageId: String): From {
        val at = context.indexOfFirst { it.id == messageId }
        if (at < 0) return From(-1, FromUnavailable.SUMMARISED)
        val start = if (context[at].role == Role.USER) {
            at
        } else {
            (at + 1 until context.size).firstOrNull { context[it].startsTurn() } ?: return From(-1, FromUnavailable.NOTHING_AFTER)
        }
        if (context.size - start < MIN_FROM_MESSAGES) return From(start, FromUnavailable.TOO_LITTLE)
        return From(start)
    }

    /** A summary from here needs at least this much from the point on (a message and a reply). */
    const val MIN_FROM_MESSAGES = 2

    /**
     * The last message of a summary request from [start] of [context]: the chat's [prompt], then where the
     * summarised part starts (quoted, since the request sends the whole context so it reads the cache), then
     * the user's [instructions]. With [start] 0 it covers the whole context, as an ordinary summary.
     */
    fun fromPrompt(prompt: String, context: List<Message>, start: Int, instructions: String?): String = buildString {
        append(prompt)
        if (start > 0) {
            val first = context[start]
            append("\n\nScope: summarise only the part of this conversation from the message quoted below to the end. ")
            append("Everything before that message stays in your context word for word after this summary, so leave it out; ")
            append("where the instructions above say \"everything above\", they mean that part only. ")
            append("The part starts with ").append(describe(first)).append(":\n<part_start>\n").append(cut(quoted(first), QUOTE_CHARS)).append("\n</part_start>")
        }
        instructions?.trim()?.takeIf { it.isNotEmpty() }?.let {
            append("\n\nThe user's instructions for this summary (where they differ from the sections above, follow them):\n<user_instructions>\n")
            append(it).append("\n</user_instructions>")
        }
    }

    private const val QUOTE_CHARS = 400

    private fun describe(m: Message): String = when {
        m.parts.any { it is Part.Checkpoint } -> "the summary of an earlier part of this chat"
        m.role == Role.USER -> "the user's message"
        m.role == Role.ASSISTANT -> "your message"
        else -> "a tool result"
    }

    private fun quoted(m: Message): String = when {
        m.parts.any { it is Part.Checkpoint } || m.role != Role.USER -> m.text
        else -> m.userText.ifBlank { m.attachments.joinToString(", ") { "[attached: ${it.workspacePath}]" } }
    }.trim()

    /**
     * The checkpoint for [summary] of [context] from [start] on. With [start] 0 it is an ordinary checkpoint.
     * Otherwise it names where it starts ([Part.Checkpoint.from]), keeps the user's messages and checklist of
     * the summarised part only (the part before is still there), and the tool results before [start] that
     * cold-cache clearing cleared in what the chat last sent ([clearing], [ColdClearing.clearedBefore]), so the
     * kept prefix goes out byte for byte as it did.
     */
    fun checkpointFrom(
        summary: String,
        context: List<Message>,
        start: Int,
        contextWindow: Int? = null,
        tainted: Set<String> = emptySet(),
        clearing: ColdClearing.Policy? = null,
    ): Part.Checkpoint {
        if (start <= 0) return checkpoint(summary, context, contextWindow, tainted)
        val tail = context.drop(start)
        return checkpoint(summary, tail, contextWindow, tainted).copy(
            from = context[start].id,
            clearedResults = ColdClearing.clearedBefore(context, start, clearing),
        )
    }

    /**
     * An edited summary of [previous] (the checkpoint message it corrects): the same fields with [summary] as
     * the user wrote it, standing in for [previous]'s view ([Part.Checkpoint.supersedes]).
     */
    fun edited(previous: Message, summary: String): Part.Checkpoint {
        val old = previous.parts.filterIsInstance<Part.Checkpoint>().last()
        return old.copy(summary = summary.trim(), supersedes = previous.id)
    }

    /**
     * Whether Anthropic compacted on its server in [messages] (a `compaction` block in a replayed turn). A
     * summary of a part holding one would drop it, and the API would then see the earlier turns it had
     * folded away again, so Summarise from here isn't offered before it (docs/design/rewind.md).
     */
    fun serverCompactedIn(messages: List<Message>): Boolean = messages.any { m ->
        m.role == Role.ASSISTANT && m.format == dev.locus.core.model.ApiFormat.ANTHROPIC_MESSAGES &&
            m.rawPayload?.let { "\"compaction\"" in it && SERVER_COMPACTION.containsMatchIn(it) } == true
    }

    private val SERVER_COMPACTION = Regex(""""type"\s*:\s*"compaction"""")

    /**
     * [transcript] without its oldest part, for a summary request that overflowed itself (Codex drops
     * the oldest item and tries again). Never splits a tool call from its result, and starts with a
     * user message as providers require. Null when nothing more can go.
     */
    fun dropOldest(transcript: List<Message>, noteId: String): List<Message>? {
        if (transcript.size <= 1) return null
        var rest = transcript.drop(maxOf(2, transcript.size / 4))
        // A tool result whose call was dropped would be rejected.
        while (rest.firstOrNull()?.role == Role.TOOL) rest = rest.drop(1)
        if (rest.isEmpty()) return null
        if (rest.first().role != Role.USER) {
            val first = rest.first()
            rest = listOf(Message(noteId, first.conversationId, Role.USER, listOf(Part.Text(DROPPED)), first.createdAt)) + rest
        }
        return rest
    }

    private const val DROPPED = "[Earlier messages were left out here to fit the context window.]"

    /**
     * An estimate of [text]'s tokens: about four characters a token, as Codex estimates
     * (`approx_token_count`), but one token for each Chinese, Japanese or Korean character, and for each emoji (real
     * tokenizers spend 0.7 to 1.5 on one; four characters a token would be 3 to 6 times too low).
     */
    fun estimateTokens(text: String): Int = (quarterTokens(text) + 3) / 4

    /** [estimateTokens] in quarter tokens: 1 for most characters, 4 for a CJK one or one outside the BMP (an emoji is several bytes). */
    private fun quarterTokens(text: String): Int {
        var total = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            total += quarterTokens(cp)
            i += Character.charCount(cp)
        }
        return total
    }

    private fun quarterTokens(cp: Int): Int = if (cp >= 0x10000 || isCjk(cp)) 4 else 1

    /** Han, kana, Hangul, Bopomofo and their punctuation and full-width forms: scripts written without spaces between words. */
    private fun isCjk(cp: Int): Boolean {
        // Everything below Hangul Jamo (Latin, Greek, Cyrillic, Arabic, Thai, ...) is in neither.
        if (cp < 0x1100) return false
        return when (Character.UnicodeScript.of(cp)) {
            Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
            Character.UnicodeScript.HANGUL, Character.UnicodeScript.BOPOMOFO,
            -> true
            else -> when (Character.UnicodeBlock.of(cp)) {
                Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION, Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS -> true
                else -> false
            }
        }
    }

    /** [text] cut to about [maxChars], keeping its start and end. */
    private fun cut(text: String, maxChars: Int): String = cut(text, maxChars) { Character.charCount(it) }

    /** [text] cut to about [maxTokens] by [estimateTokens]'s weights, keeping its start and end. */
    private fun cutTokens(text: String, maxTokens: Int): String = cut(text, maxTokens * 4, ::quarterTokens)

    /**
     * [text] cut to a [budget] of [weight]s, keeping its start and end. Both pieces end on whole code
     * points, so no emoji or other character outside the BMP is split into a lone surrogate.
     */
    private fun cut(text: String, budget: Int, weight: (Int) -> Int): String {
        var total = 0
        var i = 0
        while (i < text.length && total <= budget) {
            val cp = text.codePointAt(i)
            total += weight(cp)
            i += Character.charCount(cp)
        }
        if (total <= budget) return text
        val half = (budget / 2).coerceAtLeast(1)
        var head = 0
        var used = 0
        while (head < text.length) {
            val cp = text.codePointAt(head)
            if (used + weight(cp) > half) break
            used += weight(cp)
            head += Character.charCount(cp)
        }
        var tail = text.length
        used = 0
        while (tail > head) {
            val cp = text.codePointBefore(tail)
            if (used + weight(cp) > half) break
            used += weight(cp)
            tail -= Character.charCount(cp)
        }
        return text.substring(0, head) + "\n…[${tail - head} characters cut]…\n" + text.substring(tail)
    }
}
