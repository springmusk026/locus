package dev.locus.core.llm

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Keeps a Responses `input` list one the API accepts, whatever the stored history went through (a
 * stop mid-step, a crash, a tool error, a fork). On a normal history it changes nothing, so the
 * request's prefix, and with it the prompt cache, is exactly what it was.
 *
 * Mirrors Codex's history normalisation before each request (openai/codex
 * `codex-rs/core/src/context_manager/normalize.rs`, `ensure_call_outputs_present` and
 * `remove_orphan_outputs`), checked 2026-11-03:
 *
 * - A `function_call` or `custom_tool_call` with no output gets one right after it, saying it didn't
 *   run. The API rejects a call without its output ("No tool output found for function call …").
 * - An output whose call isn't in the input is dropped.
 * - A `reasoning` item without `encrypted_content` is dropped: with `store: false` nothing is kept on
 *   the server, so the API can't resolve it by id (reasoning guide, "stateless use"). Reasoning items
 *   come with `encrypted_content` by default (developers.openai.com/api/docs/guides/reasoning), so
 *   this only happens to a history from elsewhere.
 * - A second item with the same `id` is dropped (the API rejects duplicate item ids).
 */
internal object ResponsesInput {
    /** The output Locus gives a call that has none. */
    const val NO_RESULT = "Not run: no result was recorded for this call."

    private val CALLS = mapOf("function_call" to "function_call_output", "custom_tool_call" to "custom_tool_call_output")
    private val OUTPUTS = CALLS.values.toSet()

    fun normalize(items: List<JsonObject>): List<JsonObject> {
        if (problems(items).isEmpty()) return items
        val answered = items.filter { it.type in OUTPUTS }.mapNotNull { it.callId }.toSet()
        val seenIds = HashSet<String>()
        val calls = HashSet<String>()
        return buildList {
            for (item in items) {
                val id = item.string("id")
                if (id != null && !seenIds.add(id)) continue
                when (item.type) {
                    "reasoning" -> if (item.string("encrypted_content").isNullOrEmpty()) continue
                    in OUTPUTS -> if (item.callId !in calls) continue
                }
                add(item)
                val output = CALLS[item.type]
                val callId = item.callId
                if (output != null && callId != null) {
                    calls += callId
                    if (callId !in answered) add(noResult(output, callId))
                }
            }
        }
    }

    /** What would make the API refuse [items], in words; empty when nothing would. For tests and the guard above. */
    fun problems(items: List<JsonObject>): List<String> = buildList {
        val ids = HashSet<String>()
        val calls = HashSet<String>()
        val outputs = items.filter { it.type in OUTPUTS }.mapNotNull { it.callId }.toSet()
        for ((i, item) in items.withIndex()) {
            val type = item.type
            if (type == null) add("item $i has no type")
            item.string("id")?.let { if (!ids.add(it)) add("item $i repeats id $it") }
            if (type == "message" && item.string("role") == "system") add("item $i is a system message (the ChatGPT route rejects them)")
            if (type == "reasoning" && item.string("encrypted_content").isNullOrEmpty()) add("item $i is reasoning without encrypted_content")
            CALLS[type]?.let {
                val callId = item.callId
                if (callId == null) add("item $i is a call without call_id") else {
                    calls += callId
                    if (callId !in outputs) add("call $callId has no output")
                }
            }
            if (type in OUTPUTS && item.callId !in calls) add("output for ${item.callId} comes before or without its call")
        }
    }

    private fun noResult(type: String, callId: String) = buildJsonObject {
        put("type", type)
        put("call_id", callId)
        put("output", NO_RESULT)
    }

    private val JsonObject.type: String? get() = (this["type"] as? JsonPrimitive)?.contentOrNull
    private val JsonObject.callId: String? get() = (this["call_id"] as? JsonPrimitive)?.contentOrNull
}
