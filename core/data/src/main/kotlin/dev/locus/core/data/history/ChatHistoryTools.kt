package dev.locus.core.data.history

import dev.locus.core.agent.ObjectSchema
import dev.locus.core.agent.Tool
import dev.locus.core.agent.ToolException
import dev.locus.core.agent.ToolResult
import dev.locus.core.agent.int
import dev.locus.core.agent.str
import dev.locus.core.data.repository.ConversationRepository
import dev.locus.core.model.Conversation
import dev.locus.core.model.Role
import dev.locus.core.model.ToolPolicy
import dev.locus.core.model.ToolRisk
import dev.locus.core.model.ToolSpec
import dev.locus.core.model.toolLabel
import kotlinx.serialization.json.JsonObject
import java.text.DateFormat
import java.util.Date

/**
 * Lets the agent look back at the user's earlier chats, the way it would remember them: search by
 * words, list the latest, and read one. Read-only, and it never leaves the phone.
 */
fun chatHistoryTools(conversations: ConversationRepository): List<Tool> = listOf(ChatHistorySearch(conversations), ChatHistoryRead(conversations))

private fun stamp(millis: Long) = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

private class ChatHistorySearch(private val conversations: ConversationRepository) : Tool {
    override val risk = ToolRisk.READ
    override val defaultPolicy = ToolPolicy.ALLOW
    override val spec = ToolSpec(
        "chat_history_search",
        "Find the user's earlier chats with you. With a query, matches chat titles and message text; without one, lists the most recent chats. " +
            "Use when the user refers to something you discussed before (\"last time\", \"that script we wrote\"). Then read one with chat_history_read.",
        ObjectSchema()
            .string("query", "Words to look for. Leave out to list recent chats.", required = false)
            .integer("limit", "How many chats, 1–20. Default 8.")
            .build(),
    )

    override fun describe(args: JsonObject) = args.str("query")?.let { "Search past chats for “$it”" } ?: "List recent chats"
    override suspend fun isObservation(args: JsonObject) = true

    override suspend fun execute(args: JsonObject): ToolResult {
        val limit = (args.int("limit") ?: 8).coerceIn(1, 20)
        val query = args.str("query")?.trim().orEmpty()
        val all = conversations.allOnce()
        val found = if (query.isEmpty()) all else conversations.search(query).let { ids -> all.filter { it.id in ids } }
        val chats = found.filter { it.title != "New chat" || query.isNotEmpty() }.take(limit)
        if (chats.isEmpty()) return ToolResult(if (query.isEmpty()) "No earlier chats." else "No chats mention “$query”.")
        val lines = chats.map { c -> line(c) + (if (query.isNotEmpty()) snippet(c, query)?.let { "\n    …$it…" }.orEmpty() else "") }
        return ToolResult(lines.joinToString("\n"))
    }

    private fun line(c: Conversation) = "- id ${c.id} · “${c.title}” · last active ${stamp(c.updatedAt)}"

    /** The words around the first match in the chat's text, so the model can tell which chat is meant. */
    private suspend fun snippet(c: Conversation, query: String): String? {
        val text = conversations.history(c.id).filter { it.role != Role.TOOL }.joinToString(" ") { it.text }.replace(Regex("\\s+"), " ")
        val at = text.indexOf(query, ignoreCase = true).takeIf { it >= 0 } ?: return null
        return text.substring((at - 80).coerceAtLeast(0), (at + query.length + 120).coerceAtMost(text.length))
    }
}

private class ChatHistoryRead(private val conversations: ConversationRepository) : Tool {
    override val risk = ToolRisk.READ
    override val defaultPolicy = ToolPolicy.ALLOW
    override val spec = ToolSpec(
        "chat_history_read",
        "Read an earlier chat (from chat_history_search or <recent_chats>): what the user asked, what you answered, and the actions you took.",
        ObjectSchema()
            .string("id", "Chat id.")
            .integer("max_chars", "Truncate to this many characters, keeping the end. Default 12000.")
            .build(),
    )

    override fun describe(args: JsonObject) = "Read an earlier chat"
    override suspend fun isObservation(args: JsonObject) = true

    override suspend fun execute(args: JsonObject): ToolResult {
        val id = args.str("id").orEmpty()
        val chat = conversations.get(id) ?: throw ToolException("No chat with id “$id”. Use chat_history_search to find it.")
        val max = (args.int("max_chars") ?: 12_000).coerceIn(1_000, 60_000)
        val transcript = buildString {
            for (m in conversations.history(id)) {
                when (m.role) {
                    Role.USER -> {
                        m.attachments.forEach { appendLine("User attached ${it.workspacePath}") }
                        if (m.text.isNotBlank()) appendLine("User: ${m.text.trim()}")
                    }
                    Role.ASSISTANT -> {
                        if (m.text.isNotBlank()) appendLine("You: ${m.text.trim()}")
                        m.toolCalls.forEach { appendLine("  [${toolLabel(it.name, it.argumentsJson)}]") }
                    }
                    // Results can be long and are reproducible; the step above says what was done.
                    Role.TOOL -> m.toolResults.filter { it.isError }.forEach { appendLine("  [failed: ${it.content.take(200)}]") }
                }
            }
        }.trim()
        val body = if (transcript.length > max) "…(earlier part cut)\n" + transcript.takeLast(max) else transcript
        return ToolResult("Chat “${chat.title}”, started ${stamp(chat.createdAt)}, last active ${stamp(chat.updatedAt)}:\n\n${body.ifEmpty { "(empty)" }}")
    }
}
