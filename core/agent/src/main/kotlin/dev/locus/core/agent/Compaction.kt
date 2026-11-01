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

/**
 * How a run compacts its transcript while it works (see [AgentConfig.compaction] and
 * docs/design/compaction.md). Null [contextWindow] turns off compacting at the threshold (the window
 * is unknown, or the provider compacts on its own) but keeps the recovery from a context-overflow error.
 */
class CompactionSetup(
    /** The model's context window in tokens, or null. */
    val contextWindow: Int?,
    /** The last message of the summary request ([dev.locus.core.model.HelperPrompts.compaction]). */
    val prompt: String,
    /** Compact once the last request used this share of [contextWindow]. */
    val threshold: Double = Compaction.THRESHOLD,
)

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

    /** A reply shorter than this isn't a summary. */
    const val MIN_SUMMARY_CHARS = 40

    const val HEADER = "[Summary of the earlier part of this chat, which you no longer see in full]"

    /**
     * Whether a request that used [usage] filled enough of the window to compact before the next one;
     * [pendingTokens] is what the next request adds on top (the tool results just appended).
     */
    fun due(usage: TokenUsage?, setup: CompactionSetup, pendingTokens: Int = 0): Boolean {
        val window = setup.contextWindow?.takeIf { it > 0 } ?: return false
        val used = usage?.context ?: return false
        return used + pendingTokens > window * setup.threshold
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
    ): ChatRequest {
        require(ask.role == Role.USER)
        val withPrompt = ask.copy(parts = listOf(Part.Text(prompt)))
        return ChatRequest(system, transcript.map { it.forModel() } + withPrompt, tools, cacheKey, taskBudget = taskBudget)
    }

    /**
     * What the model sees of a chat: everything after the latest checkpoint, the checkpoint itself
     * shown as [view]. Earlier messages stay in the chat for the user. Kept reasoning never goes back.
     */
    fun modelContext(all: List<Message>): List<Message> {
        val history = all.map { it.forModel() }
        val at = history.indexOfLast { m -> m.parts.any { it is Part.Checkpoint } }
        if (at < 0) return history
        return listOf(view(history[at])) + history.drop(at + 1)
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
        append(HEADER).append("\n\n").append(checkpoint.summary.trim())
        if (checkpoint.recentUserMessages.isNotEmpty()) {
            append("\n\n<recent_user_messages>\nThe user's latest messages before this summary, word for word, oldest first:")
            checkpoint.recentUserMessages.forEach { append("\n\n<message>\n").append(it).append("\n</message>") }
            append("\n</recent_user_messages>")
        }
        checkpoint.todos?.takeIf { it.isNotBlank() }?.let { append("\n\n<todo_list>\nYour checklist at this point:\n").append(it).append("\n</todo_list>") }
        append("\n\nThe full earlier transcript is still on the phone: chat_history_read with id \"").append(chatId)
        append("\" reads it (offset to page through it, include_tool_results to see what tools returned). ")
        append("If you were in the middle of a task, carry on from where this leaves off.")
    }

    /** The checkpoint for [summary] of [transcript] (what the model saw, a previous checkpoint's view first). */
    fun checkpoint(summary: String, transcript: List<Message>): Part.Checkpoint =
        Part.Checkpoint(summary.trim(), transcript.size, keep(userMessages(transcript)), todos(transcript))

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
            if (line.isNotEmpty()) add(line)
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
                kept.addFirst(cut(m, left * CHARS_PER_TOKEN))
                break
            }
        }
        return kept.toList()
    }

    /**
     * The checklist at the end of [transcript]: the latest successful `todo_write`, or, when there's
     * none since, the one a previous checkpoint kept. Null when there's no list (or it was emptied).
     */
    fun todos(transcript: List<Message>): String? {
        val latest = Todos.latest(transcript)
        if (latest != null) return latest.takeIf { it.isNotEmpty() }?.joinToString("\n", transform = ::todoLine)
        return transcript.firstNotNullOfOrNull { m -> m.parts.filterIsInstance<Part.Checkpoint>().lastOrNull() }?.todos
    }

    private fun todoLine(item: TodoItem): String = when (item.status) {
        TodoStatus.COMPLETED -> "- [done] "
        TodoStatus.IN_PROGRESS -> "- [in progress] "
        TodoStatus.PENDING -> "- [pending] "
    } + item.content

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

    private const val CHARS_PER_TOKEN = 4

    /** About four characters a token, as Codex estimates (`approx_token_count`). */
    fun estimateTokens(text: String): Int = (text.length + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN

    /** [text] cut to about [maxChars], keeping its start and end. */
    private fun cut(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val half = (maxChars / 2).coerceAtLeast(1)
        return text.take(half) + "\n…[${text.length - 2 * half} characters cut]…\n" + text.takeLast(half)
    }
}
