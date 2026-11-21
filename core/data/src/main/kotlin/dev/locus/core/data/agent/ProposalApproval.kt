package dev.locus.core.data.agent

import dev.locus.core.llm.ChatEvent
import dev.locus.core.llm.ChatModel
import dev.locus.core.llm.ChatRequest
import dev.locus.core.model.ApiFormat
import kotlinx.coroutines.flow.Flow

/** What Approve in Needs you did with a proposal ([AgentRunner.approveProposal], docs/design/needs-you.md). */
sealed interface ProposalApproval {
    /** It ran, and the note is in its chat. */
    data object Ran : ProposalApproval

    /** It ran and failed; [message] is the tool's error, also in its chat. */
    data class Failed(val message: String) : ProposalApproval

    /** Locus's checks stopped it as it was about to run ([reason], in English as the model reads it); it didn't run. */
    data class Blocked(val reason: String) : ProposalApproval

    /** Past its expiry: it never runs. */
    data object Expired : ProposalApproval

    /** No longer waiting: already approved (a second tap), declined, or gone with its chat. */
    data object Gone : ProposalApproval

    /** Its chat has a run going; it can be approved once that ends. */
    data object Busy : ProposalApproval
}

/** The model of a loop that only runs one approved call ([dev.locus.core.agent.AgentLoop.runApproved]): never asked. */
internal object NoModel : ChatModel {
    override val format: ApiFormat = ApiFormat.OPENAI_CHAT
    override fun stream(request: ChatRequest): Flow<ChatEvent> = error("An approved proposal runs one call and asks no model.")
}
