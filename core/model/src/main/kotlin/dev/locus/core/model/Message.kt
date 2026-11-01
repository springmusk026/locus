package dev.locus.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class Role { USER, ASSISTANT, TOOL }

/** Provider-neutral content. Adapters translate these to and from each wire format. */
@Serializable
sealed interface Part {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : Part

    @Serializable
    @SerialName("tool_call")
    data class ToolCall(val id: String, val name: String, val argumentsJson: String) : Part

    /** A file the user attached. [path] is absolute on the device; [workspacePath] is how the agent refers to it. */
    @Serializable
    @SerialName("attachment")
    data class Attachment(val name: String, val mimeType: String, val path: String, val workspacePath: String, val sizeBytes: Long) : Part

    @Serializable
    @SerialName("tool_result")
    data class ToolResult(
        val callId: String,
        val toolName: String,
        val content: String,
        val isError: Boolean = false,
        /** How the call was authorised; for display only, never sent to providers. Null when not run. */
        val decision: ApprovalDecision? = null,
    ) : Part

    /**
     * A compaction checkpoint: a summary of everything before it. The messages before stay in the
     * chat for the user to read, but from here on only this summary is sent to the model.
     *
     * Besides the model's [summary] it keeps what a summary tends to blur, so the model sees it
     * verbatim (see docs/design/compaction.md): the user's latest messages before it,
     * [recentUserMessages] (oldest first, about 20k tokens at most), and the agent's checklist at that
     * point, [todos] (one line per item). Both are empty on checkpoints written before they existed.
     */
    @Serializable
    @SerialName("checkpoint")
    data class Checkpoint(
        val summary: String,
        val replacedMessages: Int,
        val recentUserMessages: List<String> = emptyList(),
        val todos: String? = null,
    ) : Part

    /**
     * What the model showed of its reasoning while producing this assistant turn (thinking summaries,
     * Anthropic progress updates, `reasoning_content`), kept so the user can reopen it. Display only:
     * it is stripped before any request ([Message.forModel]); providers that need their reasoning back
     * get it from [Message.rawPayload].
     */
    @Serializable
    @SerialName("reasoning")
    data class Reasoning(val content: String, val durationMs: Long = 0) : Part
}

/**
 * One entry in a conversation. History is append-only: messages are never edited or removed once
 * sent, because providers such as Anthropic bind reasoning blocks to the exact prior transcript.
 *
 * [rawPayload] holds the provider's own encoding of an assistant turn (for Anthropic, the content
 * blocks including thinking blocks and signatures). It is replayed verbatim to the same [format]
 * and ignored by other formats, which rebuild the turn from [parts].
 */
@Serializable
data class Message(
    val id: String,
    val conversationId: String,
    val role: Role,
    val parts: List<Part>,
    val createdAt: Long,
    val format: ApiFormat? = null,
    val rawPayload: String? = null,
    /** Tokens the provider reported for the request that produced this assistant turn. */
    val usage: TokenUsage? = null,
    /**
     * Which model produced this assistant turn and how long it took. Bookkeeping for Insights only:
     * adapters never read it, so it never changes a request. Null for other roles and older turns.
     */
    val reply: ReplyInfo? = null,
) {
    val text: String get() = parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
    val toolCalls: List<Part.ToolCall> get() = parts.filterIsInstance<Part.ToolCall>()
    val toolResults: List<Part.ToolResult> get() = parts.filterIsInstance<Part.ToolResult>()
    val attachments: List<Part.Attachment> get() = parts.filterIsInstance<Part.Attachment>()
    val reasoning: Part.Reasoning? get() = parts.filterIsInstance<Part.Reasoning>().firstOrNull()

    /** The text the user typed, without the notes Locus adds for the model ([ModeNote]). */
    val userText: String get() = parts.filterIsInstance<Part.Text>().filterNot { ModeNote.isNote(it.text) || ModeNote.isResult(it.text) }.joinToString("") { it.text }

    /** This message as providers see it: display-only parts removed. */
    fun forModel(): Message = if (parts.none { it is Part.Reasoning }) this else copy(parts = parts.filterNot { it is Part.Reasoning })
}

/**
 * Notes Locus attaches to a user message for the model, such as "plan mode is on". They ride on the
 * new user message (not the frozen system prompt), so the cached prefix is untouched, and the chat
 * hides them from the user's bubble.
 */
object ModeNote {
    private const val OPEN = "<locus_mode>"
    private const val CLOSE = "</locus_mode>"

    fun wrap(text: String) = "\n\n$OPEN$text$CLOSE"

    fun isNote(text: String) = text.trimStart().startsWith(OPEN)

    private const val RESULT_OPEN = "<locus_result>"
    private const val RESULT_CLOSE = "</locus_result>"

    /**
     * Output Locus attaches to a user message for the model, such as the result of a call the user ran
     * themselves. Hidden from the bubble and left out of [Message.userText], so it never counts as
     * something the user typed (the safety reviewer trusts only that).
     */
    fun result(text: String) = "\n\n$RESULT_OPEN$text$RESULT_CLOSE"

    fun isResult(text: String) = text.trimStart().startsWith(RESULT_OPEN)

    const val PLAN_MODE = "Plan mode is on. Only reading and looking tools work (no writing, running, sending or changing anything). " +
        "Investigate as needed, then call plan_propose with your plan and stop. Don't carry out the task until the user approves."

    /**
     * Rides on a message the user sent while the agent worked, when it joins at a step boundary (steer
     * and queue): how to treat it. In the message, never the system prompt, so the cached prefix holds.
     */
    const val MID_TASK = "The user sent the message above while you were working on the current task. It is not a new task: " +
        "read it now and take it into account from your next step. If it corrects you, adds information or changes priorities, " +
        "say in one short line how you'll adjust, then carry on. If it asks a question, answer it briefly, then carry on. " +
        "If it asks you to stop or wrap up, start no new work, leave things in a clean state, and end with a short summary " +
        "of what's done and what's left. Otherwise keep working on the task."

    /** Whether the user's message [message] joined mid-task, at a step boundary ([MID_TASK]). */
    fun isMidTask(message: Message): Boolean = message.parts.any { it is Part.Text && isNote(it.text) && MID_TASK in it.text }

    /** Whether [message] was sent with plan mode on ([PLAN_MODE]). */
    fun isPlanMode(message: Message): Boolean = message.parts.any { it is Part.Text && isNote(it.text) && PLAN_MODE in it.text }

    private const val UNDO_PREFIX = "The user undid your changes to "
    private const val UNDO_REST = ", restoring the version from before your edit."
    private const val MAX_UNDO_PATHS = 20

    /**
     * Tells the agent the user undid its changes to [paths] from the changes card. It rides on the
     * user's next message, or joins at a step boundary as a message of its own ([isAgentNote]); either
     * way it's appended, never inserted, so the cached prefix holds.
     */
    fun userUndid(paths: List<String>): String {
        val shown = paths.take(MAX_UNDO_PATHS).joinToString(", ")
        val more = if (paths.size > MAX_UNDO_PATHS) " and ${paths.size - MAX_UNDO_PATHS} more" else ""
        val one = paths.size == 1
        return UNDO_PREFIX + shown + more + UNDO_REST +
            " Your earlier view of ${if (one) "that file" else "those files"} is out of date: read ${if (one) "it" else "them"} again before changing ${if (one) "it" else "them"}," +
            " and don't redo the change unless the user asks."
    }

    /** What a [userUndid] note in [message] names ("a.kt, b.kt"), for showing it in the chat; null when there's none. */
    fun undone(message: Message): String? = message.parts.filterIsInstance<Part.Text>()
        .filter { isNote(it.text) && UNDO_PREFIX in it.text }
        .joinToString(", ") { it.text.substringAfter(UNDO_PREFIX).substringBefore(UNDO_REST) }
        .ifEmpty { null }

    /**
     * A user-role message that holds only notes from Locus (an undo joining at a step boundary), not
     * something the user typed. It doesn't start a turn: the chat shows it as a line inside the turn.
     */
    fun isAgentNote(message: Message): Boolean =
        message.role == Role.USER && message.parts.isNotEmpty() && message.parts.all { it is Part.Text && isNote(it.text) }
}

/** Whether this message starts a turn: anything the user sent, or a compaction checkpoint; not a note from Locus ([ModeNote.isAgentNote]). */
fun Message.startsTurn(): Boolean = role == Role.USER && !ModeNote.isAgentNote(this)

data class Conversation(
    val id: String,
    val title: String,
    val providerId: String?,
    /** Frozen when the conversation starts so the prompt prefix never changes mid-conversation. */
    val systemPrompt: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean = false,
    /** A model to use instead of the provider's default, for this chat only. */
    val modelOverride: String? = null,
    /** Reasoning effort for this chat; null means the provider's setting. */
    val effort: Effort? = null,
    /** Plan mode: the agent may only look, then must propose a plan for the user to approve. */
    val planMode: Boolean = false,
    /** [ToolGroup.key]s and [ToolGroup.connectorKey]s switched off for this chat. */
    val disabledTools: Set<String> = emptySet(),
    /** For a sub-agent: the chat that started it; null for the user's own chats. */
    val parentConversationId: String? = null,
    /** For a sub-agent: the parent's `agent_spawn` call that started it. */
    val parentCallId: String? = null,
    /** For a sub-agent: its profile ("researcher", or a user-defined name). */
    val agentType: String? = null,
    /** For a sub-agent: where it got to. */
    val agentStatus: SubagentStatus? = null,
    /** Hidden from the chat list, search and history (sub-agent transcripts). */
    val hidden: Boolean = false,
    /**
     * Built-in integrations (ids) this chat reaches through the vendor's MCP server instead, chosen
     * once when it was created; their built-in tools aren't declared in it. Never changes afterwards.
     */
    val mcpFirst: Set<String> = emptySet(),
)

/**
 * Token counts for one model request, normalised across providers: [input] is the whole prompt,
 * cached parts included, so [input] + [output] is how full the context window was after the turn.
 * [output] always includes reasoning (thinking) tokens; [reasoning] is the part of it the provider
 * reported as reasoning, 0 when it doesn't say.
 */
@Serializable
data class TokenUsage(
    val input: Int,
    val output: Int,
    val cacheRead: Int = 0,
    val cacheWrite: Int = 0,
    val reasoning: Int = 0,
) {
    val context: Int get() = input + output
}

/**
 * Who answered a model request and how fast, recorded when the turn is stored (database v9).
 * [firstTokenMs] runs from sending the request to the first streamed output of any kind (text,
 * reasoning or a tool call starting); [totalMs] to the finished turn. Both are timed on the attempt
 * that succeeded, so retries' back-off isn't counted. Null where unknown.
 */
@Serializable
data class ReplyInfo(
    val providerId: String? = null,
    val model: String? = null,
    val firstTokenMs: Long? = null,
    val totalMs: Long? = null,
    /**
     * Set when a backup model answered because the chat's own couldn't (database v11): how the chat's
     * own model is named, e.g. "claude-opus-5-5 (Anthropic)". Shown as a note under the reply.
     */
    val fallbackFrom: String? = null,
)

/**
 * What a model request was for, for "Where tokens went" in Insights. Chat replies and helpers
 * (sub-agents) are stored as messages; the others are side requests recorded as usage events.
 * [wire] is what the database stores; never rename it.
 */
enum class UsageSource(val wire: String) {
    CHAT("chat"),
    HELPER("helper"),
    TITLE("title"),
    SUGGESTIONS("suggestions"),
    COMPACTION("compaction"),
    REVIEWER("reviewer"),
    REFLECTION("reflection"),
    SIDE_QUESTION("side_question"),
    OTHER("other"),
    ;

    companion object {
        fun parse(wire: String?): UsageSource = entries.firstOrNull { it.wire == wire } ?: OTHER
    }
}
