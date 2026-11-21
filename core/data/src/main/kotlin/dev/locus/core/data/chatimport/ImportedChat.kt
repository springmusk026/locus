package dev.locus.core.data.chatimport

import dev.locus.core.model.ChatImportSource
import dev.locus.core.model.Role
import java.security.MessageDigest

/**
 * One conversation read from an export, already reduced to what Locus shows: the user's and the
 * assistant's text, as a tree. Hidden plumbing (system messages, tool calls and results, reasoning,
 * custom-instruction context) is left out; see docs/design/import-chats.md.
 */
data class ImportedChat(
    val source: ChatImportSource,
    /** The export's own id for the conversation; the Locus id is made from it, so a re-import finds it. */
    val sourceId: String,
    /** Null when the export has none; the importer then names it from the first message. */
    val title: String?,
    val createdAt: Long,
    val updatedAt: Long,
    /** Parents before children; siblings oldest first. */
    val messages: List<ImportedMessage>,
    /** The message the export shows as the end of the conversation (ChatGPT's `current_node`). */
    val activeLeaf: String?,
    /** Parts of a known message whose type this parser doesn't know, so they were left out. */
    val unknownParts: Int = 0,
    /** Files and images that weren't imported (only text is), by name where the export gives one. */
    val notImported: List<String> = emptyList(),
    /** Images without a name that weren't imported. */
    val imagesNotImported: Int = 0,
    /** The model the export names for the conversation, e.g. "gpt-4o", when it gives one. */
    val model: String? = null,
) {
    val firstUserText: String? get() = messages.firstOrNull { it.role == Role.USER && it.text.isNotBlank() }?.text
}

data class ImportedMessage(
    /** The export's node or message id. */
    val nodeId: String,
    /** The nearest shown message above it, or null for a first message. */
    val parentNodeId: String?,
    val role: Role,
    val text: String,
    val createdAt: Long,
    /** Names of files attached to this message that weren't imported. */
    val notImported: List<String> = emptyList(),
    /** Unnamed images in this message that weren't imported. */
    val imagesNotImported: Int = 0,
)

/** What one entry of an export's conversation list turned out to be. */
sealed interface ParsedEntry {
    data class Chat(val chat: ImportedChat) : ParsedEntry

    /** A conversation with nothing to show (no user or assistant text). */
    data class Empty(val source: ChatImportSource) : ParsedEntry

    /** Not a conversation this parser can read (damaged, or a shape it doesn't know). */
    data class Unreadable(val reason: String) : ParsedEntry
}

internal object ExportIds {
    /** A stable id for a conversation the export gave none: a hash of what does identify it. */
    fun derived(vararg parts: String?): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(parts.joinToString("\u0000") { it.orEmpty() }.toByteArray())
        return "h" + digest.take(12).joinToString("") { "%02x".format(it) }
    }
}
