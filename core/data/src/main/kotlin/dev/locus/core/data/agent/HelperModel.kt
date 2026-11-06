package dev.locus.core.data.agent

import dev.locus.core.data.auth.ProviderAccess
import dev.locus.core.data.repository.SettingsRepository
import dev.locus.core.data.repository.UsageLog
import dev.locus.core.llm.ChatEvent
import dev.locus.core.llm.ChatRequest
import dev.locus.core.llm.HelperModelChoice
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
 * Small side jobs around a chat (titles, follow-up suggestions) done by the helper model: one the user
 * chose in Settings, else "Automatic" (a cheaper model of the chat's own provider, see [providerFor]),
 * else the chat's own model. Each job is one short request without tools, separate from the agent's
 * conversation, so it never disturbs that conversation's prompt cache.
 *
 * Titles and suggestions use it, at `low` effort: each is a few hundred tokens of checkable output
 * ("High-volume work with outputs you can check: try Claude Haiku 5.5 first", Anthropic's cost guide),
 * sent without caching, so moving them off the chat's model costs no cache hit.
 */
@Singleton
class HelperModel @Inject constructor(
    private val access: ProviderAccess,
    private val settings: SettingsRepository,
    private val usage: UsageLog,
    /** Chats and projects, for a project's own helper model (docs/design/projects.md); null in tests. */
    private val chats: dev.locus.core.database.ConversationDao? = null,
    private val projects: dev.locus.core.database.ProjectDao? = null,
    /** The catalogue and model lists, for the automatic helper model; null in tests (the documented default only). */
    private val directory: dev.locus.core.data.repository.ModelDirectory? = null,
) {
    /**
     * The provider and model that do helper work for a chat on [chatProvider]: the helper model of the
     * chat's project ([conversationId]) when it sets one, else the global helper setting. With neither,
     * "Automatic" ([AppSettings.helperAutomatic], the default) picks a cheaper model of the chat's own
     * provider when one is known ([automaticModel]); otherwise it's the chat's own model.
     *
     * [automatic] false skips that pick: the safety reviewer passes it, so a cheaper model never decides
     * which actions run without asking unless the user chose one.
     */
    suspend fun providerFor(chatProvider: ProviderConfig?, conversationId: String? = null, automatic: Boolean = true): ProviderConfig? {
        val s = settings.current()
        val project = conversationId?.let { id -> chats?.get(id)?.projectId }?.let { projects?.get(it) }
        val helperId = project?.helperProviderId ?: s.helperProviderId
        val helperModel = if (project?.helperProviderId != null) project.helperModel.orEmpty() else s.helperModel
        val chosen = s.provider(helperId)?.takeIf { helperId != null && it.id == helperId }
        val base = chosen ?: chatProvider ?: return null
        val model = when {
            chosen != null -> helperModel.takeIf { it.isNotBlank() } ?: base.model
            automatic && s.helperAutomatic -> automaticModel(base) ?: base.model
            else -> base.model
        }
        // On-device models are too slow to spend on side jobs while the user waits.
        return base.copy(model = model, maxOutputTokens = 2_000).takeIf { it.format != ApiFormat.ON_DEVICE }
    }

    /**
     * The automatic helper model for a chat on [chat]: [HelperModelChoice]'s pick, when the provider's own
     * list (if fetched) has it and it calls tools with enough context; else null (the chat's model).
     */
    suspend fun automaticModel(chat: ProviderConfig): String? {
        val catalog = directory?.index()
        val picked = HelperModelChoice.automatic(chat.format, chat.baseUrl, chat.model, catalog) ?: return null
        val listed = (directory?.lists?.value?.get(chat.id) as? dev.locus.core.data.repository.ModelList.Loaded)?.models
        val caps = directory?.capabilities(chat, picked) ?: dev.locus.core.data.repository.resolveCapabilities(chat, picked, listed, catalog)
        return picked.takeIf { HelperModelChoice.fits(it, caps, listed?.map { m -> m.id }) }
    }

    /**
     * One prompt in, plain text out; null when there's no usable model or the request fails. The
     * request's tokens are recorded under [source] (and [conversationId], when it's for a chat).
     * [quick] side jobs (a title, follow-up suggestions) run at [QUICK_EFFORT] rather than the chat's
     * effort: Anthropic recommends `low` for "simpler tasks that need the best speed and lowest costs, such
     * as subagents" (effort docs), and a title doesn't need a chat's thinking.
     */
    suspend fun complete(
        chatProvider: ProviderConfig?,
        prompt: String,
        maxOutputTokens: Int = 400,
        timeoutMs: Long = 45_000,
        source: UsageSource = UsageSource.OTHER,
        conversationId: String? = null,
        quick: Boolean = false,
    ): String? {
        val provider = providerFor(chatProvider, conversationId)
            ?.let { if (quick) quick(it) else it }
            ?.copy(maxOutputTokens = maxOutputTokens) ?: return null
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
        /**
         * The effort for quick side jobs. `low` because every route Locus sends effort on takes it: Anthropic
         * (low / medium / high / xhigh / max), OpenAI (not every model takes `minimal` or `none`: GPT-6.1 Sol
         * takes neither, reasoning guide) and Gemini's compatible endpoint (where 2.5's `low` and `minimal` are
         * the same 1,024-token budget). Models without the setting don't get it (each adapter checks).
         */
        const val QUICK_EFFORT = "low"

        /** [provider] set up for a quick side job: [QUICK_EFFORT]. */
        fun quick(provider: ProviderConfig): ProviderConfig = provider.copy(effort = QUICK_EFFORT)

        /** The last few turns as plain text, each cut short, for helper prompts. */
        fun excerpt(history: List<Message>, turns: Int, perMessage: Int): String =
            history.filter { (it.role == Role.USER || it.role == Role.ASSISTANT) && it.userText.isNotBlank() }
                .takeLast(turns)
                .joinToString("\n\n") { m -> (if (m.role == Role.USER) "User: " else "Assistant: ") + m.userText.trim().take(perMessage) }

        /** What a chat is called from its first message until a real title comes (ConversationRepository). */
        fun fallbackTitle(firstMessage: String): String = firstMessage.lineSequence().first().take(TITLE_MAX)

        /**
         * Whether [text] is only small talk ("hi", "hey there!", "thanks", "good morning"): nothing a
         * chat's title could say what it's about from.
         */
        fun isSmallTalk(text: String): Boolean {
            val words = text.lowercase().split(Regex("[^\\p{L}\\p{N}']+")).filter { it.isNotEmpty() }
            return words.isNotEmpty() && words.size <= 6 && words.all { it in SMALL_TALK }
        }

        /**
         * Whether to title the chat after a turn, given the user's messages so far ([userTexts], oldest
         * first) and its [currentTitle]:
         * - after the first exchange, unless it was only small talk: "A Friendly Greeting" from "hi" says
         *   nothing about the task that follows;
         * - once more, when the first real request comes after small talk only, while the chat still has its
         *   automatic name from the first message. A chat the user renamed keeps its name.
         */
        fun shouldTitle(userTexts: List<String>, currentTitle: String?): Boolean {
            val texts = userTexts.filter { it.isNotBlank() }
            if (texts.isEmpty() || isSmallTalk(texts.last())) return false
            if (texts.size == 1) return true
            if (!texts.dropLast(1).all(::isSmallTalk)) return false
            return currentTitle == null || currentTitle == "New chat" || currentTitle == fallbackTitle(texts.first())
        }

        private val SMALL_TALK = setOf(
            "hi", "hii", "hello", "hey", "heya", "hiya", "yo", "howdy", "sup", "gm", "hola", "bonjour", "hallo", "ciao", "namaste", "salut",
            "there", "locus", "good", "morning", "afternoon", "evening", "night", "how", "are", "you", "u", "doing", "what's", "whats", "up",
            "thanks", "thank", "thx", "ty", "ok", "okay", "cool", "nice", "great", "test", "testing", "ping",
        )

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
