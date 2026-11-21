package dev.locus.core.data.chatimport

import dev.locus.core.model.ChatImportSource
import dev.locus.core.model.Role
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One conversation of a ChatGPT export (`conversations.json`, or its shards `conversations-000.json` …):
 * `{id, title, create_time, update_time, current_node, mapping: {node id: {id, parent, children, message}}}`.
 * The format is undocumented; what this reads, and why, is in docs/design/import-chats.md.
 *
 * It keeps every branch of the tree and marks `current_node` as the one shown. `children` is ignored
 * (2026 exports dropped it): the tree is rebuilt from `parent`.
 */
internal object ChatGptExport {
    /** Content types that are never shown: the model's context, reasoning and its summaries. */
    private val HIDDEN_TYPES = setOf(
        "user_editable_context", "model_editable_context", "thoughts", "reasoning_recap",
        "system_error", "tether_browsing_display", "tether_browsing_code", "sonic_webpage",
    )

    /** ChatGPT's citation markers: "【12†source】" and the private-use ones (U+E200 cite U+E202 turn0search0 U+E201), with the space before them. */
    private val CITATIONS = Regex("[ \\t]*(?:【[^】]{0,80}†[^】]{0,80}】|\uE200[^\uE201]{0,200}\uE201)")

    fun isConversation(obj: JsonObject) = obj.containsKey("mapping")

    fun parse(obj: JsonObject): ParsedEntry {
        val mapping = obj.obj("mapping") ?: return ParsedEntry.Unreadable("no mapping")
        val created = obj["create_time"].epochMillis()
        val updated = obj["update_time"].epochMillis()
        var unknown = 0
        val nodes = ArrayList<RawNode>(mapping.size)
        for ((key, value) in mapping) {
            val node = value as? JsonObject ?: continue
            val id = node.text("id") ?: key
            val parent = node.text("parent")
            val message = node.obj("message")
            val read = message?.let(::readMessage)
            if (read != null) unknown += read.unknown
            nodes += read?.node?.copy(id = id, parentId = parent) ?: RawNode(id, parent, shown = false, createdAt = message?.get("create_time").epochMillis())
        }
        val times = nodes.mapNotNull { it.createdAt }
        val start = created ?: times.minOrNull() ?: 0L
        val tree = ExportTree.build(nodes, obj.text("current_node"), start)
        if (tree.messages.isEmpty()) return ParsedEntry.Empty(ChatImportSource.CHATGPT)
        val end = maxOf(updated ?: 0L, tree.messages.maxOf { it.createdAt }, start)
        val sourceId = obj.text("conversation_id") ?: obj.text("id") ?: ExportIds.derived(obj.text("title"), created?.toString(), tree.messages.first().text.take(200))
        return ParsedEntry.Chat(
            ImportedChat(
                source = ChatImportSource.CHATGPT,
                sourceId = sourceId,
                title = obj.text("title")?.trim()?.takeIf { it.isNotEmpty() },
                createdAt = start,
                updatedAt = end,
                messages = tree.messages,
                activeLeaf = tree.activeLeaf,
                unknownParts = unknown,
                notImported = tree.messages.flatMap { it.notImported },
                imagesNotImported = tree.messages.sumOf { it.imagesNotImported },
                model = obj.text("default_model_slug"),
            ),
        )
    }

    private class Read(val node: RawNode, val unknown: Int)

    private fun readMessage(message: JsonObject): Read {
        val time = message["create_time"].epochMillis()
        val hidden = RawNode("", null, shown = false, createdAt = time)
        val role = when (message.obj("author")?.text("role")) {
            "user" -> Role.USER
            "assistant" -> Role.ASSISTANT
            else -> return Read(hidden, 0) // system and tool messages: plumbing
        }
        val recipient = message.text("recipient")
        if (recipient != null && recipient != "all") return Read(hidden, 0) // a call to a tool
        val metadata = message.obj("metadata")
        if (metadata != null && (metadata.flag("is_visually_hidden_from_conversation") || metadata.flag("is_thinking_preamble_message"))) return Read(hidden, 0)
        val content = message.obj("content") ?: return Read(hidden, 0)
        val type = content.text("content_type")
        if (type in HIDDEN_TYPES) return Read(hidden, 0)

        var unknown = 0
        var images = 0
        val text = StringBuilder()
        fun add(s: String?) {
            val t = s?.let { CITATIONS.replace(it, "") }?.trim().orEmpty()
            if (t.isEmpty()) return
            if (text.isNotEmpty()) text.append("\n\n")
            text.append(t)
        }
        when (type) {
            "code" -> content.text("text")?.takeIf { it.isNotBlank() }?.let { code ->
                val language = content.text("language")?.takeIf { it.isNotBlank() && it != "unknown" }.orEmpty()
                add("```$language\n${code.trimEnd()}\n```")
            }
            "tether_quote" -> {
                val quote = content.text("text")?.trim().orEmpty()
                val from = listOfNotNull(content.text("title")?.takeIf { it.isNotBlank() }, content.text("url")?.takeIf { it.isNotBlank() }).joinToString(" ")
                if (quote.isNotEmpty()) add(quote.lines().joinToString("\n") { "> $it" } + if (from.isNotEmpty()) "\n\n— $from" else "")
            }
            "execution_output" -> add(content.text("text")?.let { "```\n${it.trimEnd()}\n```" })
            else -> {
                // text, multimodal_text, and types this parser doesn't know that still carry parts or text.
                val parts = content.arr("parts")
                val before = unknown
                if (parts != null) {
                    for (part in parts) {
                        when (part) {
                            is JsonPrimitive -> if (part.isString) add(part.content)
                            is JsonObject -> when (part.text("content_type")) {
                                "image_asset_pointer" -> images++
                                "audio_transcription", "text" -> add(part.text("text"))
                                "audio_asset_pointer", "real_time_user_audio_video_asset_pointer", "video_container_asset_pointer" -> Unit
                                else -> if (part.text("text") != null) add(part.text("text")) else unknown++
                            }
                            else -> unknown++
                        }
                    }
                } else if (content.text("text") != null) {
                    add(content.text("text"))
                } else {
                    unknown++
                }
                // An unknown type that yielded nothing readable counts once.
                if (type != "text" && type != "multimodal_text" && text.isEmpty() && images == 0 && unknown == before) unknown++
            }
        }
        val files = metadata?.arr("attachments").orEmpty().mapNotNull { (it as? JsonObject)?.text("name")?.trim()?.takeIf(String::isNotEmpty) }
        // Named image attachments are listed by name; only the rest are counted as images.
        val namedImages = metadata?.arr("attachments").orEmpty().count { (it as? JsonObject)?.text("mime_type")?.startsWith("image/") == true }
        val unnamed = (images - namedImages).coerceAtLeast(0)
        val shown = text.isNotEmpty() || files.isNotEmpty() || unnamed > 0
        return Read(
            RawNode("", null, shown = shown, role = role, text = text.toString(), createdAt = time, notImported = files, imagesNotImported = unnamed),
            unknown,
        )
    }
}
