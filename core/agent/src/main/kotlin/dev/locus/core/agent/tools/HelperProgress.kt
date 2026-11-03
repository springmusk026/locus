package dev.locus.core.agent.tools

import dev.locus.core.agent.ToolResult
import dev.locus.core.model.DiscoveryTools
import dev.locus.core.model.Part
import dev.locus.core.model.toolLabel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * What a helper has done so far, kept while it works so a helper that times out or is stopped hands
 * back what it had: the reply it was streaming, each tool result in one line, and the workspace paths
 * its file tools changed. Thread-safe; the engine feeds it from the helper's events.
 */
class HelperProgress {
    private val lock = Any()
    private val reply = StringBuilder()
    private val calls = HashMap<String, Part.ToolCall>()
    private val results = ArrayList<String>()
    private val changed = LinkedHashSet<String>()

    /** Text of the reply being streamed now. */
    fun streamed(text: String) {
        synchronized(lock) { if (reply.length < MAX_REPLY_CHARS) reply.append(text.take(MAX_REPLY_CHARS - reply.length)) }
    }

    /** The reply was saved, or started over (a retry, a fallback, a summary): what streamed is no longer cut off. */
    fun discardReply() {
        synchronized(lock) { reply.setLength(0) }
    }

    /** A tool call the helper made; its result arrives in [finished]. */
    fun called(call: Part.ToolCall) {
        synchronized(lock) { calls[call.id] = call }
    }

    fun finished(callId: String, result: ToolResult) {
        synchronized(lock) {
            val call = calls[callId]
            val name = call?.name ?: "tool"
            val args = call?.argumentsJson ?: "{}"
            results += resultLine(name, args, result)
            if (!result.isError) changed += changedPaths(name, args)
        }
    }

    fun snapshot(texts: List<String>, steps: List<String>): Snapshot = synchronized(lock) {
        Snapshot(
            texts = texts,
            steps = steps,
            results = results.toList(),
            changedFiles = changed.toList(),
            cutOff = reply.toString().trim().takeIf { it.isNotEmpty() },
        )
    }

    /** What the helper had when it stopped. */
    data class Snapshot(
        /** Its saved replies, oldest first. */
        val texts: List<String> = emptyList(),
        /** Its tool calls as labels, oldest first. */
        val steps: List<String> = emptyList(),
        /** One line per tool result, oldest first: "Read notes.md: ok, …". */
        val results: List<String> = emptyList(),
        /** Workspace paths its file tools changed, in the order first changed. */
        val changedFiles: List<String> = emptyList(),
        /** The reply it was streaming when it stopped, never saved. */
        val cutOff: String? = null,
    ) {
        val isEmpty: Boolean get() = texts.isEmpty() && steps.isEmpty() && results.isEmpty() && cutOff == null
    }

    companion object {
        private const val MAX_REPLY_CHARS = 4_000
        private const val EXCERPT_CHARS = 160

        /** "Read notes.md: ok, 12 lines" or "Ran gradle test: failed, BUILD FAILED": the label and the result's first line. */
        fun resultLine(name: String, argumentsJson: String, result: ToolResult): String {
            val first = result.content.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
            val excerpt = if (first.length > EXCERPT_CHARS) first.take(EXCERPT_CHARS).trimEnd() + "…" else first
            val outcome = if (result.isError) "failed" else "ok"
            return toolLabel(name, argumentsJson) + ": " + outcome + (if (excerpt.isNotEmpty()) ", $excerpt" else "")
        }

        /**
         * The workspace paths a successful call of a file tool changed, with what happened: "notes/a.md
         * (written)". A tool run through call_tool counts as itself. Commands can change files too, but
         * what they changed isn't known; they show in the tool results.
         */
        fun changedPaths(name: String, argumentsJson: String): List<String> {
            val (tool, args) = DiscoveryTools.unwrap(name, argumentsJson)
            val obj = runCatching { Json.parseToJsonElement(args) as JsonObject }.getOrNull() ?: return emptyList()
            fun arg(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            return when (tool) {
                "write_file" -> listOfNotNull(arg("path")?.let { "$it (written)" })
                "edit_file", "multi_edit" -> listOfNotNull(arg("path")?.let { "$it (edited)" })
                "delete_path" -> listOfNotNull(arg("path")?.let { "$it (deleted)" })
                "make_directory" -> listOfNotNull(arg("path")?.let { "$it (folder created)" })
                "copy_path" -> listOfNotNull(arg("to")?.let { "$it (copied from ${arg("from") ?: "…"})" })
                "move_path" -> listOfNotNull(arg("to")?.let { "$it (moved from ${arg("from") ?: "…"})" })
                else -> emptyList()
            }
        }
    }
}
