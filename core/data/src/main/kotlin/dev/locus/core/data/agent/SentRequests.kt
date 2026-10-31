package dev.locus.core.data.agent

import dev.locus.core.agent.SecretScrubber
import dev.locus.core.agent.SentRequest
import dev.locus.core.agent.SentRequestLog
import dev.locus.core.agent.SentView
import dev.locus.core.data.repository.ConversationRepository
import dev.locus.core.datastore.SecretStore
import dev.locus.core.llm.ChatModel
import dev.locus.core.llm.ChatRequest
import dev.locus.core.llm.wireBody
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.ProviderConfig
import dev.locus.core.ondevice.OnDeviceText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "What was sent": the chat's recent model requests, kept in memory only ([SentRequestLog]) as the
 * exact inputs the adapter got, and turned into a scrubbed [SentView] only when the user opens one.
 * The body is rebuilt with the adapter's own `buildBody` ([wireBody]), so the run pays nothing for it.
 */
@Singleton
class SentRequests @Inject constructor(
    private val secrets: SecretStore,
    private val conversations: ConversationRepository,
) {
    private val log = SentRequestLog()

    /** Called by the agent loop for each request of a chat's own run. */
    fun record(conversationId: String, model: ChatModel, provider: ProviderConfig, request: ChatRequest, replyId: String?) =
        log.record(conversationId, SentRequest(model, provider, request, replyId, System.currentTimeMillis()))

    /** Whether the request behind reply [replyId] is still kept. */
    fun hasReply(conversationId: String, replyId: String): Boolean = log.forReply(conversationId, replyId) != null

    /** The request that produced [replyId], or the chat's latest when [replyId] is null; null when none is kept. */
    suspend fun view(conversationId: String, replyId: String?): SentView? {
        val sent = (if (replyId == null) log.latest(conversationId) else log.forReply(conversationId, replyId)) ?: return null
        return build(conversationId, sent)
    }

    private suspend fun build(conversationId: String, sent: SentRequest): SentView = withContext(Dispatchers.IO) {
        val scrubber = SecretScrubber(secrets.valuesForScrubbing())
        val provider = sent.provider
        if (provider.format == ApiFormat.ON_DEVICE) {
            SentView.fromOnDevice(provider.name, provider.model, sent.request.system, OnDeviceText.tools(sent.request), OnDeviceText.turns(sent.request), scrubber)
        } else {
            val reported = sent.replyId?.let { id -> conversations.history(conversationId).firstOrNull { it.id == id }?.usage?.input }
            val body = sent.model.wireBody(sent.request)
            if (body == null) {
                SentView.fromOnDevice(provider.name, provider.model, sent.request.system, sent.request.tools, emptyList(), scrubber)
            } else {
                SentView.fromBody(provider.format, provider.name, provider.model, provider.baseUrl, body, scrubber, reported)
            }
        }
    }
}
