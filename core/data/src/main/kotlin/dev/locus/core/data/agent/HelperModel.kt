package dev.locus.core.data.agent

import dev.locus.core.data.auth.ProviderAccess
import dev.locus.core.data.repository.SettingsRepository
import dev.locus.core.data.repository.UsageLog
import dev.locus.core.llm.ChatEvent
import dev.locus.core.llm.ChatRequest
import dev.locus.core.model.ApiFormat
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
                    .stream(ChatRequest(system = "", messages = listOf(message), tools = emptyList()))
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

        fun titlePrompt(excerpt: String) = """
            Write a title for this chat between a user and an AI assistant: 2 to 6 words that name its subject, in the language the user writes in.
            Reply with the title only: no quotes, no trailing punctuation, no prefix like "Title:".

            <chat>
            $excerpt
            </chat>
        """.trimIndent()

        fun suggestionsPrompt(excerpt: String) = """
            Here is the end of a chat between a user and an AI assistant that can use tools (web, files, a Linux shell, the phone's apps).
            Suggest 3 short things the user might say next: natural follow-ups, written as the user, in the user's language and style, each under 8 words.
            Reply with one suggestion per line and nothing else: no numbering, bullets or quotes.

            <chat>
            $excerpt
            </chat>
        """.trimIndent()

        /** Cleans a title model reply down to the title. */
        fun cleanTitle(raw: String): String? =
            raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
                ?.removePrefix("Title:")?.trim()?.trim('"', '“', '”', '\'', '*', '#', ' ')?.trimEnd('.', '!', '?')
                ?.take(60)?.takeIf { it.isNotBlank() }

        fun cleanSuggestions(raw: String): List<String> =
            raw.lines().map { it.trim().trimStart('-', '*', '•', ' ').replace(Regex("^\\d+[.)]\\s*"), "").trim('"', '“', '”') }
                .filter { it.isNotBlank() && it.length <= 80 }
                .distinct()
                .take(3)
    }
}
