package dev.locus.core.data.repository

import dev.locus.core.database.MessageViewRow
import dev.locus.core.database.TranscriptVersion
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.ChatThread
import dev.locus.core.model.Message
import dev.locus.core.model.MessageTree
import dev.locus.core.model.ReplyInfo
import dev.locus.core.model.Role
import dev.locus.core.model.TokenUsage
import dev.locus.core.model.TreeNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow

/**
 * A chat's shown branch for display, read incrementally (docs/design/transcript-reads.md).
 *
 * Messages are never changed once written, and only deleted with their chat, so a transcript only
 * grows at the end. [observe] watches the chat's [TranscriptVersion] (a one-row query) and, when it
 * changes, reads only the rows written since the last read and decodes only the messages on the shown
 * path that weren't decoded yet. Writes to other chats, and `touch`es of this one, change nothing here
 * and emit nothing. A count that doesn't add up (a deleted chat, rows written out of order) reloads
 * everything, so the result is always what a full read would give.
 *
 * Rows come without `rawPayload`: [Message.rawPayload] is null in what this emits. Model requests are
 * built from [ConversationRepository.history], which reads every column.
 */
internal class TranscriptReader(
    private val version: (conversationId: String) -> Flow<TranscriptVersion>,
    private val rows: suspend (conversationId: String, after: Long, upTo: Long) -> List<MessageViewRow>,
    private val decode: (MessageViewRow) -> Message,
) {
    fun observe(conversationId: String): Flow<ChatThread> = flow {
        val cache = Cache()
        version(conversationId).distinctUntilChanged().collect { v -> emit(cache.update(conversationId, v)) }
    }.distinctUntilChanged() // e.g. a message added off the shown branch; cheap, as unchanged messages are the same instances

    private inner class Cache {
        /** Every row of the chat read so far (all branches), in [MessageViewRow.seq] order. */
        private val read = ArrayList<MessageViewRow>()
        private val byId = HashMap<String, MessageViewRow>()
        private val decoded = HashMap<String, Message>()
        private var upTo = 0L

        suspend fun update(conversationId: String, v: TranscriptVersion): ChatThread {
            if (v.maxSeq < upTo || v.count < read.size) reset()
            val added = if (v.maxSeq > upTo) rows(conversationId, upTo, v.maxSeq) else emptyList()
            if (read.size + added.size != v.count || added.any { it.seq <= upTo }) {
                // Rows appeared below what was read, or some went: read the chat again from the start.
                reset()
                append(rows(conversationId, 0, v.maxSeq))
            } else {
                append(added)
            }
            upTo = v.maxSeq
            return thread(v.activeLeafId)
        }

        private fun append(new: List<MessageViewRow>) {
            new.forEach { row ->
                read += row
                byId[row.id] = row
            }
        }

        private fun reset() {
            read.clear()
            byId.clear()
            decoded.clear()
            upTo = 0
        }

        private fun thread(activeLeaf: String?): ChatThread {
            if (read.isEmpty()) return ChatThread(emptyList())
            val tree = MessageTree(read.map { TreeNode(it.id, it.parentId) })
            val path = tree.path(tree.leaf(activeLeaf))
            return ChatThread(path.map { id -> decoded.getOrPut(id) { decode(byId.getValue(id)) } }, tree.branches(path))
        }
    }

    companion object {
        /** [row] as a [Message], decoded like [ConversationRepository.toModel] but without a provider payload. */
        fun decode(row: MessageViewRow): Message = with(row) {
            Message(
                id = id,
                conversationId = conversationId,
                role = Role.valueOf(role),
                parts = ConversationRepository.json.decodeFromString(ConversationRepository.partsSerializer, partsJson),
                createdAt = createdAt,
                format = format?.let(ApiFormat::valueOf),
                rawPayload = null,
                usage = usageJson?.let { runCatching { ConversationRepository.json.decodeFromString(TokenUsage.serializer(), it) }.getOrNull() },
                reply = if (providerId == null && model == null && firstTokenMs == null && totalMs == null && fallbackFrom == null) null
                else ReplyInfo(providerId, model, firstTokenMs, totalMs, fallbackFrom),
            )
        }
    }
}
