package dev.locus.core.data.chatimport

import dev.locus.core.model.ChatImportSource
import dev.locus.core.model.Role
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * One conversation of a Claude export (`conversations.json`, also inside `conversations-000.zip` in the
 * per-category exports): `{uuid, name, created_at, updated_at, chat_messages: [{uuid, sender, text,
 * content: [blocks], created_at, attachments, files, parent_message_uuid}]}`. Undocumented; see
 * docs/design/import-chats.md for what this reads and the sources.
 *
 * Exports since spring 2026 link messages with `parent_message_uuid` (edits and retries are siblings);
 * older ones are a flat list, read in time order.
 */
internal object ClaudeExport {
    /** The parent Claude writes for a conversation's first message. */
    private const val ROOT = "00000000-0000-4000-8000-000000000000"

    /** Blocks that are deliberately not shown: reasoning, tool plumbing and Claude's own bookkeeping. */
    private val HIDDEN_BLOCKS = setOf("thinking", "redacted_thinking", "tool_use", "tool_result", "server_tool_use", "web_search_tool_result", "injected_prompt_block", "token_budget")

    /** Most of an attachment's extracted text that's imported; the rest is cut with a note. */
    private const val MAX_ATTACHMENT_CHARS = 200_000

    fun isConversation(obj: JsonObject) = obj.containsKey("chat_messages") || (obj.containsKey("uuid") && obj["messages"] is JsonArray)

    fun parse(obj: JsonObject, attachedLabel: (String) -> String = { "Attached: $it" }): ParsedEntry {
        val list = obj.arr("chat_messages") ?: obj.arr("messages") ?: return ParsedEntry.Unreadable("no chat_messages")
        val created = obj["created_at"].epochMillis()
        val updated = obj["updated_at"].epochMillis()
        var unknown = 0
        val raw = ArrayList<RawNode>(list.size)
        list.forEachIndexed { i, element ->
            val m = element as? JsonObject ?: run { unknown++; return@forEachIndexed }
            val id = m.text("uuid") ?: "m$i"
            val role = when (m.text("sender")) {
                "human", "user" -> Role.USER
                "assistant" -> Role.ASSISTANT
                else -> null
            }
            val time = m["created_at"].epochMillis()
            val parent = m.text("parent_message_uuid")?.takeIf { it != ROOT }
            if (role == null) {
                raw += RawNode(id, parent, shown = false, createdAt = time)
                return@forEachIndexed
            }
            val text = StringBuilder()
            fun add(s: String?) {
                val t = s?.trim().orEmpty()
                if (t.isEmpty()) return
                if (text.isNotEmpty()) text.append("\n\n")
                text.append(t)
            }
            val blocks = m.arr("content")
            if (blocks != null && blocks.isNotEmpty()) {
                for (b in blocks) {
                    val block = b as? JsonObject ?: run { unknown++; null } ?: continue
                    when (val type = block.text("type")) {
                        "text" -> add(block.text("text"))
                        "voice_note" -> add(listOfNotNull(block.text("title"), block.text("text")).joinToString("\n"))
                        in HIDDEN_BLOCKS -> Unit
                        else -> if (type != null && block.text("text") != null) add(block.text("text")) else unknown++
                    }
                }
                // Some exports carry the text only in `text`.
                if (text.isEmpty()) add(m.text("text"))
            } else {
                add(m.text("text"))
            }
            // Pasted text and documents come with their extracted text: that is imported, the file isn't.
            m.arr("attachments").orEmpty().forEach { a ->
                val att = a as? JsonObject ?: return@forEach
                val content = att.text("extracted_content")?.takeIf { it.isNotBlank() } ?: return@forEach
                val name = att.text("file_name")?.trim().orEmpty()
                val body = if (content.length > MAX_ATTACHMENT_CHARS) content.take(MAX_ATTACHMENT_CHARS) + "\n…" else content
                add((if (name.isNotEmpty()) attachedLabel(name) + "\n\n" else "") + body)
            }
            val files = m.arr("files").orEmpty().mapNotNull { (it as? JsonObject)?.text("file_name")?.trim()?.takeIf(String::isNotEmpty) } +
                m.arr("attachments").orEmpty().mapNotNull { a ->
                    (a as? JsonObject)?.takeIf { it.text("extracted_content").isNullOrBlank() }?.text("file_name")?.trim()?.takeIf(String::isNotEmpty)
                }
            raw += RawNode(id, parent, shown = text.isNotEmpty() || files.isNotEmpty(), role = role, text = text.toString(), createdAt = time, notImported = files)
        }
        val linked = raw.any { it.parentId != null }
        val nodes = if (linked) raw else {
            // A flat list: chain it in time order (the array isn't always in order).
            val sorted = raw.withIndex().sortedWith(compareBy({ it.value.createdAt ?: Long.MIN_VALUE }, { it.index })).map { it.value }
            sorted.mapIndexed { i, n -> n.copy(parentId = sorted.getOrNull(i - 1)?.id) }
        }
        val start = created ?: nodes.mapNotNull { it.createdAt }.minOrNull() ?: 0L
        val tree = ExportTree.build(nodes, obj.text("current_leaf_message_uuid"), start)
        if (tree.messages.isEmpty()) return ParsedEntry.Empty(ChatImportSource.CLAUDE)
        val end = maxOf(updated ?: 0L, tree.messages.maxOf { it.createdAt }, start)
        val sourceId = obj.text("uuid") ?: ExportIds.derived(obj.text("name"), created?.toString(), tree.messages.first().text.take(200))
        return ParsedEntry.Chat(
            ImportedChat(
                source = ChatImportSource.CLAUDE,
                sourceId = sourceId,
                title = obj.text("name")?.trim()?.takeIf { it.isNotEmpty() },
                createdAt = start,
                updatedAt = end,
                messages = tree.messages,
                activeLeaf = tree.activeLeaf,
                unknownParts = unknown,
                notImported = tree.messages.flatMap { it.notImported },
                model = obj.text("model"),
            ),
        )
    }
}
