package dev.locus.core.agent.tools

import dev.locus.core.agent.ApprovalOrigin
import dev.locus.core.agent.Tool
import dev.locus.core.agent.ToolCallContext
import dev.locus.core.agent.ToolException
import dev.locus.core.agent.ToolResult
import dev.locus.core.agent.bool
import dev.locus.core.agent.str
import dev.locus.core.model.AgentControlTools
import dev.locus.core.model.ToolPolicy
import dev.locus.core.model.ToolRisk
import dev.locus.core.model.ToolSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.currentCoroutineContext

/**
 * A question the agent put to the user mid-run, waiting for an answer: options to pick from (one, or
 * several when [multi]), or free text. Shown as a card above the composer and as a notification.
 */
data class UserQuestion(
    /** The chat that shows it: the asking chat, or the parent chat when a helper asks. */
    val chatId: String,
    val callId: String,
    val question: String,
    val options: List<String> = emptyList(),
    val multi: Boolean = false,
    /** Offer "Other…" with a text field next to the options. Without options, the answer is always text. */
    val allowOther: Boolean = true,
    /** Set when a helper asks: the card says "Researcher #2 asks". */
    val origin: ApprovalOrigin? = null,
)

/** What the user answered: options they picked and/or their own words. */
data class QuestionAnswer(val chosen: List<String> = emptyList(), val text: String? = null) {
    val isEmpty: Boolean get() = chosen.isEmpty() && text.isNullOrBlank()
}

/** Puts a question to the user and waits. Null means no answer (timed out). */
fun interface UserAsker {
    suspend fun ask(question: UserQuestion): QuestionAnswer?
}

/**
 * The open questions of the whole app. [ask] suspends until [answer] is called for that call id, the
 * waiting run is cancelled, or [timeoutMs] passes (then the answer is null: no answer, never a guess).
 * [route] sends a helper's question to its parent chat, labelled with the helper's name; it returns
 * the chat to show it in and the origin, or null to keep the asking chat.
 */
class QuestionDesk(
    private val timeoutMs: Long = AskUser.TIMEOUT_MS,
    private val route: (conversationId: String) -> Pair<String, ApprovalOrigin>? = { null },
) : UserAsker {
    private val waiting = ConcurrentHashMap<String, Pair<UserQuestion, CompletableDeferred<QuestionAnswer>>>()
    private val _pending = MutableStateFlow<List<UserQuestion>>(emptyList())

    /** Open questions, oldest first. */
    val pending: StateFlow<List<UserQuestion>> = _pending.asStateFlow()

    override suspend fun ask(question: UserQuestion): QuestionAnswer? {
        val routed = route(question.chatId)?.let { (chat, origin) -> question.copy(chatId = chat, origin = origin) } ?: question
        val answer = CompletableDeferred<QuestionAnswer>()
        waiting[routed.callId] = routed to answer
        _pending.update { list -> list.filterNot { it.callId == routed.callId } + routed }
        return try {
            withTimeoutOrNull(timeoutMs) { answer.await() }
        } finally {
            waiting.remove(routed.callId)
            _pending.update { list -> list.filterNot { it.callId == routed.callId } }
        }
    }

    /**
     * Answers the open question [callId]. Picks that aren't among its options are dropped and the text
     * is trimmed and capped; an empty answer is ignored. False when nothing was waiting or it was empty.
     */
    fun answer(callId: String, answer: QuestionAnswer): Boolean {
        val (question, deferred) = waiting[callId] ?: return false
        val chosen = answer.chosen.filter { it in question.options }.distinct()
            .let { if (question.multi) question.options.filter { o -> o in it } else it.take(1) }
        val text = answer.text?.trim()?.take(AskUser.MAX_ANSWER_CHARS)?.takeIf { it.isNotEmpty() }
        val clean = QuestionAnswer(chosen, text)
        if (clean.isEmpty) return false
        waiting.remove(callId)
        return deferred.complete(clean)
    }

    /** The option at [index] of the open question [callId] (a notification button), as an answer. */
    fun answerOption(callId: String, index: Int): Boolean {
        val option = waiting[callId]?.first?.options?.getOrNull(index) ?: return false
        return answer(callId, QuestionAnswer(chosen = listOf(option)))
    }

    fun question(callId: String): UserQuestion? = waiting[callId]?.first
}

/**
 * `ask_user`: the agent asks the user a question and waits for the answer, which comes back as the
 * tool result. Shaped like the ask tools of comparable agents (OpenMinis, nanoMuse) and Claude Code's
 * AskUserQuestion: a question, optional options, optional multi-select, optional free text.
 *
 * It only asks the user, so it is always allowed and counts as observation (it works in plan mode).
 * Read-only (background, scheduled) runs have nobody to ask: they get an immediate "not available".
 */
class AskUser(private val asker: UserAsker) : Tool {
    override val risk = ToolRisk.READ
    override val defaultPolicy = ToolPolicy.ALLOW

    // It waits for the user; the turn's other calls run before or after it, in order.
    override val runsAlone = true

    override val spec = ToolSpec(
        AgentControlTools.ASK_USER,
        "Ask the user a question and wait for the answer. Use it when the request is ambiguous or a choice has real consequences " +
            "(deleting, spending, sending, very different approaches), instead of guessing. Give 2–$MAX_OPTIONS short options when the " +
            "answer is a choice (\"Yes\"/\"No\" for a confirmation); set multi to let them pick several. Don't ask what you can find out yourself.",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("question") { put("type", "string"); put("description", "One clear question, at most $MAX_QUESTION_CHARS characters.") }
                putJsonObject("options") {
                    put("type", "array")
                    put("description", "Optional: 2–$MAX_OPTIONS short answers to pick from (each at most $MAX_OPTION_CHARS characters). Omit for a free-text answer.")
                    putJsonObject("items") { put("type", "string") }
                }
                putJsonObject("multi") { put("type", "boolean"); put("description", "Let the user pick several options. Default false.") }
                putJsonObject("allow_other") { put("type", "boolean"); put("description", "Also let the user type their own answer. Default true.") }
            }
            putJsonArray("required") { add("question") }
            put("additionalProperties", false)
        },
    )

    override fun describe(args: JsonObject) = "Ask you: ${args.str("question").orEmpty().take(MAX_QUESTION_CHARS)}"

    override suspend fun isObservation(args: JsonObject) = true

    override suspend fun execute(args: JsonObject): ToolResult {
        val parsed = parse(args)
        val ctx = currentCoroutineContext()[ToolCallContext]
        // Background and scheduled runs: nobody is there to answer, so say so at once rather than hang.
        if (ctx == null || ctx.readOnly) return ToolResult.error(UNAVAILABLE)
        val answer = asker.ask(parsed.copy(chatId = ctx.conversationId, callId = ctx.callId))
            ?: return ToolResult.error(NO_ANSWER)
        return ToolResult(formatAnswer(answer))
    }

    companion object {
        const val MAX_QUESTION_CHARS = 500
        const val MAX_OPTIONS = 6
        const val MAX_OPTION_CHARS = 80
        const val MAX_ANSWER_CHARS = 4000

        /** Matches approvals: an unanswered question ends after 30 minutes. */
        const val TIMEOUT_MS = 30L * 60 * 1000

        const val UNAVAILABLE = "The user isn't available to answer: this is a background run. Don't wait for an answer; " +
            "continue with what's safe without it, or say in your reply what you need from them."
        const val NO_ANSWER = "No answer: the user didn't reply within 30 minutes. Don't assume an answer for anything with " +
            "consequences; continue only with what's safe, or end your turn and say what you need."
        const val RESTARTED = "The user didn't answer: Locus was restarted while the question was open. Ask again if you still need it."

        private const val CHOSE = "The user chose: "
        private const val WROTE = "The user wrote: "

        /** The checked question (chat and call ids are filled in by the caller). */
        fun parse(args: JsonObject): UserQuestion {
            val question = args.str("question")?.trim().orEmpty()
            if (question.isEmpty()) throw ToolException("question must not be empty.")
            if (question.length > MAX_QUESTION_CHARS) throw ToolException("question is too long: keep it under $MAX_QUESTION_CHARS characters.")
            val raw = args["options"]
            val options = when (raw) {
                null -> emptyList()
                is JsonArray -> raw.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull?.trim() ?: throw ToolException("options must be strings.") }
                else -> throw ToolException("options must be an array of strings.")
            }.filter { it.isNotEmpty() }
            if (options.size > MAX_OPTIONS) throw ToolException("Too many options: give at most $MAX_OPTIONS.")
            if (options.size == 1) throw ToolException("Give at least 2 options, or none for a free-text answer.")
            options.firstOrNull { it.length > MAX_OPTION_CHARS }?.let { throw ToolException("Option “${it.take(20)}…” is too long: keep each under $MAX_OPTION_CHARS characters.") }
            if (options.distinct().size != options.size) throw ToolException("options must be different from each other.")
            return UserQuestion(
                chatId = "",
                callId = "",
                question = question,
                options = options,
                multi = options.isNotEmpty() && args.bool("multi") == true,
                allowOther = options.isEmpty() || args.bool("allow_other") != false,
            )
        }

        /** The tool result for [answer]; one line per part so [shownAnswer] can read it back. */
        fun formatAnswer(answer: QuestionAnswer): String = buildList {
            if (answer.chosen.isNotEmpty()) add(CHOSE + answer.chosen.joinToString { "“$it”" })
            answer.text?.takeIf { it.isNotBlank() }?.let { add(WROTE + it) }
        }.joinToString("\n")

        /** What the steps card shows after "Answered:", from a successful result; null when it wasn't answered. */
        fun shownAnswer(resultContent: String): String? {
            // The picks are only ever the first line; the user's own words follow and may span lines.
            val first = resultContent.substringBefore('\n')
            val chose = first.takeIf { it.startsWith(CHOSE) }?.removePrefix(CHOSE)
            val rest = if (chose != null) resultContent.substringAfter('\n', "") else resultContent
            val wrote = rest.takeIf { it.startsWith(WROTE) }?.removePrefix(WROTE)?.takeIf { it.isNotBlank() }
            return listOfNotNull(chose, wrote).joinToString(" · ").takeIf { it.isNotEmpty() }
        }
    }
}
