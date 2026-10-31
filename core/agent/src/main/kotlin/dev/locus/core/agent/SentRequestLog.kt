package dev.locus.core.agent

import dev.locus.core.llm.ChatModel
import dev.locus.core.llm.ChatRequest
import dev.locus.core.model.ProviderConfig

/**
 * One model request as the agent loop made it: the exact [request] (immutable, the same objects the
 * adapter was given), the [model] that sent it and the [provider] settings it ran with. The body is
 * rebuilt from these on demand through the adapter's own code ([dev.locus.core.llm.wireBody]), so
 * capturing costs one list entry and no serialisation.
 */
class SentRequest(
    val model: ChatModel,
    val provider: ProviderConfig,
    val request: ChatRequest,
    /** The assistant message this request produced; null when it failed before a reply was saved. */
    val replyId: String?,
    val at: Long,
)

/**
 * The last few requests of the most recent chats, in memory only: never written to disk, gone when the
 * app process ends. Holding a request keeps its transcript alive, so both dimensions are capped.
 */
class SentRequestLog(private val perChat: Int = PER_CHAT, private val maxChats: Int = MAX_CHATS) {
    // Access order, so the least recently used chat is dropped first.
    private val chats = LinkedHashMap<String, ArrayDeque<SentRequest>>(16, 0.75f, true)

    @Synchronized
    fun record(conversationId: String, sent: SentRequest) {
        val list = chats.getOrPut(conversationId) { ArrayDeque() }
        list.addLast(sent)
        while (list.size > perChat) list.removeFirst()
        while (chats.size > maxChats) chats.remove(chats.keys.first())
    }

    /** The request that produced the reply [replyId], if it is still kept. */
    @Synchronized
    fun forReply(conversationId: String, replyId: String): SentRequest? = chats[conversationId]?.lastOrNull { it.replyId == replyId }

    /** The chat's most recent request, if any is kept. */
    @Synchronized
    fun latest(conversationId: String): SentRequest? = chats[conversationId]?.lastOrNull()

    @Synchronized
    fun size(conversationId: String): Int = chats[conversationId]?.size ?: 0

    @Synchronized
    fun forget(conversationId: String) {
        chats.remove(conversationId)
    }

    companion object {
        const val PER_CHAT = 6
        const val MAX_CHATS = 8
    }
}
