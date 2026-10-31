package dev.locus.core.ondevice

import dev.locus.core.llm.ChatRequest
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import dev.locus.core.model.ToolSpec

/**
 * The text side of what LiteRT-LM gets for a request, shared by [OnDeviceChatModel] and the "What was
 * sent" viewer: the declared tools and each turn as the model sees it. Photos are named in the text;
 * the model also gets their pixels with the newest user message.
 */
object OnDeviceText {
    /**
     * Small on-device models handle a short tool list far better, and every declared tool costs
     * prefill time on each step. Local models don't use prompt caching, so trimming is free.
     */
    private val ON_DEVICE_TOOLS = setOf(
        "list_files", "read_file", "write_file", "edit_file", "search_files",
        "run_command", "fetch_url", "browser_open", "browser_read",
        "memory_save", "memory_read", "memory_search", "device_calendar_event", "device_alarm",
        "weather", "wikipedia", "web_search", "chat_history_search", "chat_history_read",
    )

    fun tools(request: ChatRequest): List<ToolSpec> = request.tools.filter { it.name in ON_DEVICE_TOOLS }

    /** Each message as (role, text): `user`, `model` (its text, then its tool calls) or `tool`. */
    fun turns(request: ChatRequest): List<Pair<String, String>> {
        val lastUser = request.messages.indexOfLast { it.role == Role.USER }
        return request.messages.mapIndexed { i, message ->
            when (message.role) {
                Role.USER -> {
                    val (images, others) = splitImages(message, withImages = i == lastUser)
                    "user" to userNote(message, images, others)
                }
                Role.ASSISTANT -> "model" to (listOf(message.text) + message.toolCalls.map { "[tool call] ${it.name} ${it.argumentsJson}" })
                    .filter { it.isNotEmpty() }.joinToString("\n")
                Role.TOOL -> "tool" to message.toolResults.joinToString("\n") { "${it.toolName}: ${if (it.isError) "Error: ${it.content}" else it.content}" }
            }
        }
    }

    /** The photos sent as pixels (only with the newest user message, and only if still on disk), and the rest. */
    internal fun splitImages(message: Message, withImages: Boolean): Pair<List<Part.Attachment>, List<Part.Attachment>> =
        message.attachments.partition { withImages && it.mimeType.startsWith("image/") && java.io.File(it.path).isFile }

    internal fun userNote(message: Message, images: List<Part.Attachment>, others: List<Part.Attachment>): String =
        (others.map { "[Attached file: ${it.workspacePath} (${it.mimeType}). It is in the workspace.]" } +
            images.map { "[Photo ${it.workspacePath} is shown above.]" } + message.text).joinToString("\n")
}
