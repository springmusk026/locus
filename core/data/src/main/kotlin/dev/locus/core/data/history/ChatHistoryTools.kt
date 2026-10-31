package dev.locus.core.data.history

import dev.locus.core.agent.ObjectSchema
import dev.locus.core.agent.Tool
import dev.locus.core.agent.ToolException
import dev.locus.core.agent.ToolResult
import dev.locus.core.agent.int
import dev.locus.core.agent.str
import dev.locus.core.data.repository.ConversationRepository
import dev.locus.core.data.search.Highlighted
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
        if (query.isEmpty()) {
            val chats = conversations.allOnce().filter { it.title != "New chat" }.take(limit)
            if (chats.isEmpty()) return ToolResult("No earlier chats.")
            return ToolResult(chats.joinToString("\n") { line(it) })
        }
        // The same full-text index as the app's search: newest match first, the words around it so the
        // model can tell which chat is meant.
        val hits = conversations.search(query, limit = limit, snippetTokens = SNIPPET_TOKENS)
        if (hits.isEmpty()) return ToolResult("No chats mention “$query”.")
        return ToolResult(hits.joinToString("\n") { hit -> line(hit.conversation) + hit.message?.let { "\n    …${snippet(it.snippet)}…" }.orEmpty() })
    }

    private fun line(c: Conversation) = "- id ${c.id} · “${c.title}” · last active ${stamp(c.updatedAt)}"

    /** Matches stay unmarked, as before: the model reads the words, not the highlighting. */
    private fun snippet(s: Highlighted) = s.text.removePrefix("…").removeSuffix("…")

    private companion object {
        /** About the 200 characters the tool showed before. */
        const val SNIPPET_TOKENS = 40
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
