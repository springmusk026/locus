package dev.locus.core.data.agent

import dev.locus.core.agent.ApprovalOrigin
import dev.locus.core.agent.tools.QuestionDesk
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * The app's open `ask_user` questions, shared by the tool (in [dev.locus.core.data.repository.ToolCatalog]),
 * [AgentRunner] (the chat's card) and [AgentService] (the notification). A helper's question goes to its
 * parent chat, labelled with the helper's name, as its approval requests do.
 */
@Singleton
class UserQuestions @Inject constructor(
    // A Provider: the helper runner needs the tool registry, which needs this.
    subagents: Provider<SubagentRunner>,
) {
    val desk = QuestionDesk(
        route = { conversationId ->
            subagents.get().engine.states.value[conversationId]?.let { it.parentConversationId to ApprovalOrigin(it.agentId, it.label) }
        },
    )
}
