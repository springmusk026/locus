package dev.locus.core.agent.home

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A memory topic a chat read: its name, and whether it came from general memory in a project chat. */
data class MemoryRef(val topic: String, val general: Boolean = false)

/**
 * Which memory topics a turn read, worked out from the transcript as it is: the arguments of
 * `memory_read` calls that succeeded and the topic lines of `memory_search` results. Nothing is added
 * to what the model sees; the chat's steps card shows these with Forget beside each.
 */
object MemoryReads {
    const val READ = "memory_read"
    const val SEARCH = "memory_search"

    private const val GENERAL = " (general memory)"
    private const val SEPARATOR = " — "

    /**
     * A topic line starts at the margin with "- ", then the topic (a [AgentHome.memorySlug]: no spaces),
     * then optionally "(general memory)", then " — " and the summary. Snippets are indented, so they never match.
     */
    private val HIT = Regex("""^- ([^\s:]+)( \(general memory\))?(?: — .*)?$""")

    /** The format before ranked search: "- topic: line", general memory marked at the start of the line. */
    private val LEGACY_HIT = Regex("""^- ([^\s:]+): (\(general memory\) )?.*$""")

    /** `memory_search`'s result for the model: topics best first, each with its index line and where it matched. */
    fun format(hits: List<MemoryHit>): String =
        if (hits.isEmpty()) "No matches." else hits.joinToString("\n") { h ->
            buildString {
                append("- ").append(h.topic)
                if (h.general) append(GENERAL)
                if (h.summary.isNotBlank()) append(SEPARATOR).append(h.summary.trim())
                h.snippet?.takeIf { it.isNotBlank() && it.trim() != h.summary.trim() }?.let { append("\n  ").append(it.trim()) }
            }
        }

    /** The topics one tool call read; empty for other tools, failures and calls without a result yet. */
    fun of(toolName: String, argumentsJson: String, result: String?, isError: Boolean): List<MemoryRef> {
        if (result == null || isError) return emptyList()
        return when (toolName) {
            READ -> topicArgument(argumentsJson)?.let { listOf(MemoryRef(AgentHome.memorySlug(it))) }.orEmpty()
            SEARCH -> result.lineSequence().mapNotNull { HIT.matchEntire(it.trimEnd()) ?: LEGACY_HIT.matchEntire(it.trimEnd()) }
                .map { MemoryRef(it.groupValues[1], general = it.groupValues[2].isNotEmpty()) }
                .toList()
            else -> emptyList()
        }
    }

    /** Every topic read by [calls] (name, arguments, result, failed), once each, in the order first read. */
    fun inCalls(calls: List<Call>): List<MemoryRef> =
        calls.flatMap { of(it.name, it.argumentsJson, it.result, it.isError) }.distinct()

    data class Call(val name: String, val argumentsJson: String, val result: String?, val isError: Boolean)

    private val json = Json { ignoreUnknownKeys = true }

    private fun topicArgument(argumentsJson: String): String? = runCatching {
        ((json.parseToJsonElement(argumentsJson) as? JsonObject)?.get("topic") as? JsonPrimitive)?.takeIf { it.isString }?.content
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
