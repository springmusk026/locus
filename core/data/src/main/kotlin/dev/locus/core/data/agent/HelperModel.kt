package dev.locus.core.data.agent

import dev.locus.core.data.auth.ProviderAccess
import dev.locus.core.data.repository.SettingsRepository
import dev.locus.core.data.repository.UsageLog
import dev.locus.core.llm.ChatEvent
import dev.locus.core.llm.ChatRequest
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.AppSettings
import dev.locus.core.model.HelperPrompts
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.Role
import dev.locus.core.model.UsageSource
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Small side jobs around a chat (titles, follow-up suggestions, compaction summaries) done by the
 * user's helper model: a cheap, fast model they choose in Settings, or the chat's own model when
 * none is set. Each job is one short request without tools, separate from the agent's
 * conversation, so it never disturbs that conversation's prompt cache.
 */
@Singleton
class HelperModel @Inject constructor(
    private val access: ProviderAccess,
    private val settings: SettingsRepository,
    private val usage: UsageLog,
) {
    /** The provider and model that do helper work for a chat on [chatProvider]. */
    suspend fun providerFor(chatProvider: ProviderConfig?): ProviderConfig? {
        val s = settings.current()
        val base = s.provider(s.helperProviderId)?.takeIf { s.helperProviderId != null } ?: chatProvider ?: return null
        val model = s.helperModel.takeIf { it.isNotBlank() && s.helperProviderId == base.id } ?: base.model
        // On-device models are too slow to spend on side jobs while the user waits.
        return base.copy(model = model, maxOutputTokens = 2_000).takeIf { it.format != ApiFormat.ON_DEVICE }
    }

    /**
     * One prompt in, plain text out; null when there's no usable model or the request fails. The
     * request's tokens are recorded under [source] (and [conversationId], when it's for a chat).
     */
    suspend fun complete(
        chatProvider: ProviderConfig?,
        prompt: String,
        maxOutputTokens: Int = 400,
        timeoutMs: Long = 45_000,
        source: UsageSource = UsageSource.OTHER,
        conversationId: String? = null,
    ): String? {
        val provider = providerFor(chatProvider)?.copy(maxOutputTokens = maxOutputTokens) ?: return null
        if (access.missing(provider) != null) return null
        return runCatching {
            withTimeout(timeoutMs) {
                val startedAt = System.currentTimeMillis()
                val message = Message(UUID.randomUUID().toString(), "helper", Role.USER, listOf(Part.Text(prompt)), startedAt)
                val turn = access.chatModel(provider)
                    // Never repeated, so a cache write would only add cost.
                    .stream(ChatRequest(system = "", messages = listOf(message), tools = emptyList(), cache = false))
                    .filterIsInstance<ChatEvent.Completed>()
                    .first().turn
                usage.record(source, conversationId, provider.id, provider.model, turn.usage, System.currentTimeMillis() - startedAt)
                turn.text.trim()
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    companion object {
        /** The last few turns as plain text, each cut short, for helper prompts. */
        fun excerpt(history: List<Message>, turns: Int, perMessage: Int): String =
            history.filter { (it.role == Role.USER || it.role == Role.ASSISTANT) && it.userText.isNotBlank() }
                .takeLast(turns)
                .joinToString("\n\n") { m -> (if (m.role == Role.USER) "User: " else "Assistant: ") + m.userText.trim().take(perMessage) }

        /** The title request: the user's prompt from [settings] (or the default) with the excerpt filled in. */
        fun titlePrompt(excerpt: String, settings: AppSettings = AppSettings()) = HelperPrompts.title(settings, excerpt)

        fun suggestionsPrompt(excerpt: String, settings: AppSettings = AppSettings()) = HelperPrompts.suggestions(settings, excerpt)

        private const val TITLE_MAX = 60
        private const val SUGGESTION_MAX = 80
        private val THINKING = Regex("<think(?:ing)?>[\\s\\S]*?</think(?:ing)?>", RegexOption.IGNORE_CASE)
        private val LABEL = Regex("^(?:chat\\s+)?(?:title|suggestions?|follow[- ]?ups?)(?:\\s*\\d+)?\\s*[:：\\-–—]\\s*", RegexOption.IGNORE_CASE)
        private val LIST_MARK = Regex("^(?:[-•–—+]+|\\(?\\d+[.):])\\s+")
        private val JSON_STRING = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")
        // Wrapping a model adds around a line: quotes and bold on both ends, heading marks in front.
        private const val WRAP_END = "\"“”‘’'*"
        private const val WRAP_START = "$WRAP_END#"

        /**
         * The reply's content lines: reasoning blocks, code fences and an intro line ("Here are some
         * ideas:") dropped, since an edited prompt can make a model add any of them.
         */
        private fun contentLines(raw: String): List<String> {
            val lines = raw.replace(THINKING, "").lines().map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("```") }
            return if (lines.size > 1 && lines.first().endsWith(':')) lines.drop(1) else lines
        }

        private fun String.unwrap() = trimStart { it in WRAP_START || it.isWhitespace() }.trimEnd { it in WRAP_END || it.isWhitespace() }

        /** One line without list marks, a "Title:"-style label, markdown emphasis or quotes. */
        private fun tidy(line: String): String =
            line.unwrap().replace(LIST_MARK, "").unwrap().replace(LABEL, "").unwrap().replace(Regex("\\s+"), " ")

        /** Cleans a title model reply down to the title, whatever shape the reply takes. */
        fun cleanTitle(raw: String): String? {
            val lines = contentLines(raw)
            val line = jsonItems(lines.joinToString(" ")).ifEmpty { lines }.map(::tidy).firstOrNull { it.any(Char::isLetterOrDigit) } ?: return null
            val title = line.trimEnd('.', '!', '?', ':', ';', ',', '。', ' ')
            // Too long: cut at a word boundary rather than mid-word.
            val cut = if (title.length <= TITLE_MAX) title else title.take(TITLE_MAX).substringBeforeLast(' ').ifBlank { title.take(TITLE_MAX) }
            return cut.trim().takeIf { it.isNotBlank() }
        }

        fun cleanSuggestions(raw: String): List<String> {
            val lines = contentLines(raw)
            // A JSON array (["…", "…"]) is read as its strings.
            val items = jsonItems(lines.joinToString(" ")).ifEmpty { lines }
            return items.map(::tidy)
                .filter { it.any(Char::isLetterOrDigit) && it.length <= SUGGESTION_MAX && !it.endsWith(':') }
                .distinct()
                .take(3)
        }

        /** The string values of a reply that is JSON (["…"] or {"title": "…"}); empty for anything else. */
        private fun jsonItems(text: String): List<String> {
            val t = text.trim()
            if (!(t.startsWith("[") && t.endsWith("]")) && !(t.startsWith("{") && t.endsWith("}"))) return emptyList()
            return JSON_STRING.findAll(t)
                // Object keys ("title": …) aren't values.
                .filterNot { m -> t.substring(m.range.last + 1).trimStart().startsWith(":") }
                .map { it.groupValues[1].replace("\\\"", "\"").replace("\\n", " ") }
                .filter { it.isNotBlank() }.toList()
        }
    }
}
