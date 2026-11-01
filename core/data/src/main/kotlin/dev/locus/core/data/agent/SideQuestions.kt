package dev.locus.core.data.agent

import dev.locus.core.agent.recoverTranscript
import dev.locus.core.data.auth.ProviderAccess
import dev.locus.core.data.di.ApplicationScope
import dev.locus.core.data.repository.ConversationRepository
import dev.locus.core.data.repository.SettingsRepository
import dev.locus.core.data.repository.ToolCatalog
import dev.locus.core.data.repository.UsageLog
import dev.locus.core.llm.ChatEvent
import dev.locus.core.llm.ChatRequest
import dev.locus.core.llm.PromptCacheKey
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.Conversation
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import dev.locus.core.model.ToolSpec
import dev.locus.core.model.UsageSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** One side question (`/btw`) and its answer. */
data class SideExchange(
    val id: String,
    val question: String,
    /** The answer so far: it streams in while [done] is false. */
    val answer: String = "",
    val done: Boolean = false,
    val error: String? = null,
    /** The model wrote tool calls anyway; none of them ran. */
    val triedTools: Boolean = false,
)

/** A chat's side questions, newest last, and whether the card is showing. Kept in memory only. */
data class SideThread(val exchanges: List<SideExchange> = emptyList(), val open: Boolean = false) {
    val current: SideExchange? get() = exchanges.lastOrNull()
    val earlier: List<SideExchange> get() = exchanges.dropLast(1)
}

/**
 * Side questions, as Claude Code's `/btw`: a quick question about the chat, answered from what is
 * already in it, without tools, and kept out of the chat. The running task is never stopped or told:
 * the question goes as a separate request on a read-only copy of the chat, so the agent doesn't see it.
 *
 * The request reuses the chat's prompt cache: the chat's frozen system prompt, its fixed tool list and
 * its history exactly as a normal step sends them, then one user message with the question (see
 * [sideQuestionRequest]). Nothing is stored in the chat, so its history stays append-only and unchanged.
 * On-device chats ask the helper model instead (the phone's model is busy with the task).
 */
@Singleton
class SideQuestions @Inject constructor(
    @param:ApplicationScope private val scope: CoroutineScope,
    private val access: ProviderAccess,
    private val conversations: ConversationRepository,
    private val settings: SettingsRepository,
    private val tools: ToolCatalog,
    private val helper: HelperModel,
    private val usage: UsageLog,
    private val runner: AgentRunner,
) {
    private val _threads = MutableStateFlow<Map<String, SideThread>>(emptyMap())
    val threads: StateFlow<Map<String, SideThread>> = _threads.asStateFlow()
    private val jobs = ConcurrentHashMap<String, Job>()

    /** How long an answer may take; null waits as long as it takes (tests on virtual time). */
    var timeoutMs: Long? = TIMEOUT_MS

    /** Asks [question] about chat [conversationId]; the answer streams into [threads]. */
    fun ask(conversationId: String, question: String) {
        val q = question.trim()
        if (q.isEmpty()) return
        val earlier = _threads.value[conversationId]?.exchanges.orEmpty().filter { it.done && it.error == null }
        val exchange = SideExchange(UUID.randomUUID().toString(), q)
        update(conversationId) { t -> SideThread((t.exchanges + exchange).takeLast(KEPT), open = true) }
        jobs[exchange.id] = scope.launch {
            try {
                answer(conversationId, exchange.id, q, earlier.takeLast(REPLAYED))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                finish(conversationId, exchange.id) { it.copy(done = true, error = e.message ?: "The side question failed.") }
            } finally {
                jobs.remove(exchange.id)
            }
        }
    }

    /** Shows the card again on the latest exchange (`/btw` with no question). */
    fun reopen(conversationId: String) {
        if (_threads.value[conversationId]?.exchanges.isNullOrEmpty()) return
        update(conversationId) { it.copy(open = true) }
    }

    /** Hides the card; an answer still arriving keeps coming. */
    fun dismiss(conversationId: String) = update(conversationId) { it.copy(open = false) }

    /** Forgets the chat's side questions, stopping any still being answered. */
    fun clear(conversationId: String) {
        _threads.value[conversationId]?.exchanges?.forEach { jobs.remove(it.id)?.cancel() }
        _threads.update { it - conversationId }
    }

    private suspend fun answer(conversationId: String, id: String, question: String, earlier: List<SideExchange>) {
        val conversation = conversations.get(conversationId) ?: error("This chat is gone.")
        val base = settings.current().provider(conversation.providerId) ?: error("Add a model provider in Settings first.")
        val provider = chatProvider(base, conversation)
        if (provider.format == ApiFormat.ON_DEVICE) {
            val history = modelContext(conversations.history(conversationId))
            val text = helper.complete(
                provider, sideQuestionPrompt(history, question, earlier), maxOutputTokens = 1_000, timeoutMs = timeoutMs ?: Long.MAX_VALUE,
                source = UsageSource.SIDE_QUESTION, conversationId = conversationId,
            ) ?: error("Side questions in an on-device chat need a helper model: choose one in Settings.")
            finish(conversationId, id) { it.copy(answer = text, done = true) }
            return
        }
        access.missing(provider)?.let { error(it) }
        // A snapshot of the chat as it is now; the task appending meanwhile doesn't change it.
        val history = modelContext(conversations.history(conversationId))
        val chatTools = tools.forChat(conversation)
        val request = sideQuestionRequest(conversation, history, chatTools.specs, question, earlier, runner.taskBudgetTokens(conversationId))
            .copy(deferredTools = chatTools.requestDeferred())
        val startedAt = System.currentTimeMillis()
        val model = access.chatModel(provider)
        val text = StringBuilder()
        val stream = suspend {
            model.stream(request).collect { event ->
                when (event) {
                    is ChatEvent.TextDelta -> {
                        text.append(event.text)
                        val now = text.toString()
                        finish(conversationId, id) { it.copy(answer = now) }
                    }
                    is ChatEvent.Completed -> {
                        val turn = event.turn
                        usage.record(UsageSource.SIDE_QUESTION, conversationId, provider.id, provider.model, turn.usage, System.currentTimeMillis() - startedAt)
                        val final = turn.text.ifBlank { text.toString() }.trim()
                        finish(conversationId, id) {
                            it.copy(answer = final, done = true, triedTools = turn.toolCalls.isNotEmpty(), error = if (final.isEmpty() && turn.toolCalls.isEmpty()) "No answer came back." else null)
                        }
                    }
                    else -> Unit
                }
            }
        }
        val limit = timeoutMs
        val finished = if (limit == null) stream().let { true } else withTimeoutOrNull(limit) { stream() } != null
        if (!finished) error("No answer within ${(limit ?: 0) / 1_000} seconds.")
        // A stream that ended without its final event: keep what arrived.
        finish(conversationId, id) { if (it.done) it else it.copy(answer = it.answer.trim(), done = true, error = if (it.answer.isBlank()) "No answer came back." else null) }
    }

    private fun finish(conversationId: String, id: String, transform: (SideExchange) -> SideExchange) =
        update(conversationId) { t -> t.copy(exchanges = t.exchanges.map { if (it.id == id) transform(it) else it }) }

    private fun update(conversationId: String, transform: (SideThread) -> SideThread) {
        _threads.update { all -> all + (conversationId to transform(all[conversationId] ?: SideThread())) }
    }

    companion object {
        /** Exchanges kept per chat (shown dimmed above the current one). */
        const val KEPT = 20

        /** Earlier exchanges replayed with a new question, so follow-ups make sense. */
        const val REPLAYED = 5
        private const val TIMEOUT_MS = 90_000L
    }
}

/** What a call still running gets in the side question's copy of the chat; never stored. */
internal const val STILL_RUNNING = "Still running; no result yet."

/**
 * The side question's request: the chat's own system prompt, tools and history exactly as a step
 * sends them (so the prompt cache is reused), then the question as one clearly marked user message.
 *
 * The history is a read-only copy. A step in progress can leave calls without results (providers
 * reject that), so those get a placeholder result in the copy. When the copy ends on the user's own
 * message, the question joins that message rather than adding a second user turn in a row.
 */
internal fun sideQuestionRequest(
    conversation: Conversation,
    history: List<Message>,
    tools: List<ToolSpec>,
    question: String,
    earlier: List<SideExchange> = emptyList(),
    taskBudget: Long? = null,
    id: String = UUID.randomUUID().toString(),
    now: Long = System.currentTimeMillis(),
): ChatRequest {
    val copy = closeOpenCalls(history, id, now)
    val ask = Part.Text(sideQuestionText(question, earlier))
    val last = copy.lastOrNull()
    val messages = if (last != null && last.role == Role.USER && last.parts.none { it is Part.ToolResult }) {
        copy.dropLast(1) + last.copy(parts = last.parts + ask)
    } else {
        copy + Message(id, conversation.id, Role.USER, listOf(ask), now)
    }
    return ChatRequest(conversation.systemPrompt, messages, tools, PromptCacheKey.forChat(conversation.id), taskBudget = taskBudget)
}

/** [history] with every call that has no result yet answered by [STILL_RUNNING], in one tool message at the end. */
internal fun closeOpenCalls(history: List<Message>, id: String, now: Long): List<Message> {
    val missing = recoverTranscript(history) { STILL_RUNNING }.missingResults.map { it.copy(isError = false) }
    if (missing.isEmpty()) return history
    val last = history.last()
    return if (last.role == Role.TOOL) history.dropLast(1) + last.copy(parts = last.parts + missing)
    else history + Message("$id-results", last.conversationId, Role.TOOL, missing, now)
}

/** The question as the model reads it: marked as a side question, with the rules and any earlier exchanges. */
internal fun sideQuestionText(question: String, earlier: List<SideExchange> = emptyList()): String = buildString {
    append(SIDE_QUESTION_MARK)
    append(" The task above keeps going on its own; this question and your answer stay out of it. ")
    append("Answer briefly from what is already in this conversation. Don't call tools: none will run. ")
    append("If the conversation doesn't tell you, say so.]")
    if (earlier.isNotEmpty()) {
        append("\n\nEarlier side questions:")
        earlier.forEach { e -> append("\nQ: ").append(e.question).append("\nA: ").append(e.answer.take(ANSWER_REPLAY_MAX)) }
    }
    append("\n\n").append(question.trim())
}

/** The same for a helper model with no access to the chat itself (on-device chats): an excerpt instead. */
internal fun sideQuestionPrompt(history: List<Message>, question: String, earlier: List<SideExchange>): String =
    "Here is the end of a conversation between a user and an AI agent that is still working on a task:\n\n" +
        HelperModel.excerpt(history, turns = 12, perMessage = 1_500) +
        "\n\n" + sideQuestionText(question, earlier).replace(" above ", " in that conversation ")

internal const val SIDE_QUESTION_MARK = "[Side question from the user, answered separately."
private const val ANSWER_REPLAY_MAX = 1_500
