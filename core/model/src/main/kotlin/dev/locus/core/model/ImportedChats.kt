package dev.locus.core.model

/**
 * Where an imported chat came from (docs/design/import-chats.md). [wire] is part of the chat's id and of
 * each message's source reference; never rename it.
 */
enum class ChatImportSource(val wire: String, val displayName: String) {
    CHATGPT("chatgpt", "ChatGPT"),
    CLAUDE("claude", "Claude"),
    ;

    companion object {
        fun parse(wire: String?): ChatImportSource? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * Chats imported from a ChatGPT or Claude export are read-only archives. What marks one is its id:
 * `imported-<source>-<the export's own conversation id>`. No column says it, so the database schema is
 * unchanged, and importing the same export again finds the same ids instead of adding copies.
 *
 * Locus's own chats have random UUIDs, which never start with [PREFIX].
 */
object ImportedChats {
    const val PREFIX = "imported-"
    private const val MAX_SOURCE_ID = 120

    /** The Locus id of the chat [sourceId] from [source]. */
    fun chatId(source: ChatImportSource, sourceId: String): String = "$PREFIX${source.wire}-${clean(sourceId)}"

    /** The Locus id of the message [nodeId] in the imported chat [chatId]; unique across chats, as the messages table needs. */
    fun messageId(chatId: String, nodeId: String): String = "$chatId:${clean(nodeId)}"

    /**
     * What an imported message stores as its source (`messages.copiedFrom`): "chatgpt:<node id>". Being
     * non-null keeps it out of Insights like a fork's copy; it never equals a Locus message id, so search
     * still finds it.
     */
    fun sourceRef(source: ChatImportSource, nodeId: String): String = "${source.wire}:$nodeId"

    /** Where the chat [conversationId] was imported from, or null for Locus's own chats. */
    fun sourceOf(conversationId: String): ChatImportSource? {
        if (!conversationId.startsWith(PREFIX)) return null
        val wire = conversationId.removePrefix(PREFIX).substringBefore('-')
        return ChatImportSource.parse(wire)
    }

    fun isImported(conversationId: String): Boolean = sourceOf(conversationId) != null

    private fun clean(id: String): String =
        id.trim().map { c -> if (c.isLetterOrDigit() && c.code < 128 || c == '-' || c == '_' || c == '.') c else '_' }
            .joinToString("").take(MAX_SOURCE_ID).ifEmpty { "_" }
}

/** Where this chat was imported from (a read-only archive), or null for a chat made in Locus. */
val Conversation.importedFrom: ChatImportSource? get() = ImportedChats.sourceOf(id)
