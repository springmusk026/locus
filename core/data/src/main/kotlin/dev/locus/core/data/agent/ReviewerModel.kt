package dev.locus.core.data.agent

import dev.locus.core.agent.ReviewVerdict
import dev.locus.core.agent.ReviewerPrompt
import dev.locus.core.agent.ReviewerUnavailable
import dev.locus.core.agent.ToolReviewer
import dev.locus.core.data.auth.ProviderAccess
import dev.locus.core.llm.ChatEvent
import dev.locus.core.llm.ChatRequest
import dev.locus.core.llm.StopReason
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.Role
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The AI safety reviewer on the user's helper model (Settings → Extras), or the chat's own model when
 * no helper is set. Each review is one short request with a fixed system prompt and no tools,
 * separate from the agent's conversation. On-device models are never used: they are too slow to sit
 * in front of every action.
 */
@Singleton
class ReviewerModel @Inject constructor(
    private val access: ProviderAccess,
    private val helper: HelperModel,
) {
    /** A reviewer for a chat on [chatProvider], or null when no usable model is set up. */
    suspend fun reviewerFor(chatProvider: ProviderConfig?): ToolReviewer? {
        val provider = helper.providerFor(chatProvider)
            // Thinking models spend part of the budget before answering; low effort keeps it quick.
            ?.copy(maxOutputTokens = MAX_OUTPUT_TOKENS, effort = "low")
            ?: return null
        if (access.missing(provider) != null) return null
        val model = access.chatModel(provider)
        return ToolReviewer { ctx ->
            val startedAt = System.currentTimeMillis()
            val message = Message(UUID.randomUUID().toString(), "reviewer", Role.USER, listOf(Part.Text(ctx.render())), startedAt)
            val turn = model.stream(ChatRequest(ReviewerPrompt.SYSTEM, listOf(message), tools = emptyList()))
                .filterIsInstance<ChatEvent.Completed>().first().turn
            if (turn.stopReason == StopReason.REFUSAL) throw ReviewerUnavailable("the reviewer model declined")
            val (verdict, reason) = ReviewerPrompt.parse(turn.text) ?: throw ReviewerUnavailable("the reviewer's reply wasn't a valid verdict")
            ReviewVerdict(verdict, reason, model = provider.model, latencyMs = System.currentTimeMillis() - startedAt, usage = turn.usage)
        }
    }

    private companion object {
        const val MAX_OUTPUT_TOKENS = 1_024
    }
}
