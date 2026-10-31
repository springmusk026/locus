package dev.locus.core.data.search

import dev.locus.core.database.MessageHitRow
import dev.locus.core.model.Conversation
import dev.locus.core.model.Role

/** A chat found by a search: its newest matching message, or only its title. */
data class ChatSearchHit(
    val conversation: Conversation,
    /** Null when only the title matched. */
    val message: MessageMatch? = null,
) {
    /** When the match happened: the message's time, or the chat's last activity for a title match. */
    val at: Long get() = message?.createdAt ?: conversation.updatedAt
}

data class MessageMatch(
    val id: String,
    val role: Role,
    val createdAt: Long,
    val snippet: Highlighted,
    /** False when the message is on a branch the chat isn't showing (an earlier version of a reply or edit). */
    val onShownBranch: Boolean,
)

/** Where [ChatSearch] reads from; the database in the app, lists in tests. */
internal interface SearchSource {
    /** The user's chats (helper chats left out). */
    suspend fun chats(): List<Conversation>

    /** See `MessageDao.search`. */
    suspend fun messages(match: String, tokens: Int, limit: Int): List<MessageHitRow>

    /** Ids of the messages on the branch the chat shows. */
    suspend fun shownBranch(conversationId: String): Set<String>
}

/**
 * Full-text search over the user's chats: one result per chat, newest match first. Message text comes
 * from the FTS5 index (every branch; see docs/design/search.md), titles are matched here.
 */
internal class ChatSearch(private val source: SearchSource) {
    suspend fun search(raw: String, limit: Int = DEFAULT_LIMIT, snippetTokens: Int = SNIPPET_TOKENS): List<ChatSearchHit> {
        val query = SearchQuery.parse(raw)
        if (query.isEmpty) return emptyList()
        val chats = source.chats().associateBy { it.id }
        val byMessage = query.match?.let { source.messages(it, snippetTokens.coerceIn(1, 64), limit) }.orEmpty().mapNotNull { row ->
            val chat = chats[row.conversationId] ?: return@mapNotNull null
            ChatSearchHit(
                chat,
                MessageMatch(
                    id = row.messageId,
                    role = runCatching { Role.valueOf(row.role) }.getOrDefault(Role.USER),
                    createdAt = row.createdAt,
                    snippet = SearchSnippet.parse(row.snippet),
                    onShownBranch = row.messageId in source.shownBranch(row.conversationId),
                ),
            )
        }
        val found = byMessage.mapTo(HashSet()) { it.conversation.id }
        val byTitle = chats.values.filter { it.id !in found && query.matchesTitle(it.title) }.map { ChatSearchHit(it) }
        return (byMessage + byTitle).sortedByDescending { it.at }.take(limit)
    }

    companion object {
        const val DEFAULT_LIMIT = 50

        /** About two lines on a phone. */
        const val SNIPPET_TOKENS = 18
    }
}
