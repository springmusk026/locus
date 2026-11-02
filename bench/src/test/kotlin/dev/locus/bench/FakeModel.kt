package dev.locus.bench

import dev.locus.core.model.ApiFormat
import dev.locus.core.model.ProviderConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * A task's reference solution, replayed by the scripted fake model: [turns] in order, each either tool
 * [Turn.calls] or a final [Turn.text]. Arguments are fixed, which works because fixtures are. Like
 * Terminal-Bench's oracle solutions, it proves the task can be solved and that the checks accept a
 * correct end state; it says nothing about any real model.
 */
@Serializable
data class Oracle(val turns: List<Turn>, val helpers: List<Helper> = emptyList()) {
    /** A helper's script: used for helper requests whose task contains [match]. */
    @Serializable
    data class Helper(val match: String, val turns: List<Turn>)

    @Serializable
    data class Turn(val calls: List<Call> = emptyList(), val text: String? = null)

    @Serializable
    data class Call(val tool: String, val args: JsonObject = JsonObject(emptyMap()))

    companion object {
        fun load(file: File): Oracle? = file.takeIf { it.isFile }?.let { BenchJson.decodeFromString(serializer(), it.readText()) }
    }
}

/**
 * The scripted fake model, served as an OpenAI Chat Completions endpoint at `llm.bench.test` on the
 * fixture server. Locus talks to it through its real adapter (OpenAiChatModel, streaming SSE), so a
 * fake run exercises the same request and parsing path as a real OpenAI-compatible provider.
 *
 * Which turn to play is the number of assistant messages already in the request. Requests that don't
 * carry the task's prompt are helpers (or side questions): they play the oracle's matching
 * [Oracle.helpers] script, or get a short refusal. With no oracle the model
 * gives up at once, so the task must fail: the checks never pass on an untouched workspace.
 */
class FakeModel(private val prompt: String, private val oracle: Oracle?, private val placeholders: Placeholders) {
    /** Requests answered, for tests. */
    var requests = 0
        private set

    fun handle(request: FixtureWeb.Request): FixtureWeb.Response {
        if (!request.path.endsWith("/chat/completions")) return FixtureWeb.Response(404, "application/json", "{\"error\":{\"message\":\"not found\"}}")
        requests++
        val body = BenchJson.parseToJsonElement(request.body).jsonObject
        val messages = (body["messages"] as? JsonArray).orEmpty().map { it.jsonObject }
        val firstUser = messages.firstOrNull { it.role() == "user" }?.let(::textOf).orEmpty()
        val promptTokens = request.body.length / 4
        val turn = when {
            !firstUser.contains(prompt.take(80)) -> oracle?.helpers?.firstOrNull { firstUser.contains(it.match) }
                ?.let { h -> h.turns.getOrNull(messages.count { it.role() == "assistant" }) ?: Oracle.Turn(text = "Done.") }
                ?: Oracle.Turn(text = "(fake model: no script for this request)")
            oracle == null -> Oracle.Turn(text = NO_ORACLE)
            else -> oracle.turns.getOrNull(messages.count { it.role() == "assistant" }) ?: Oracle.Turn(text = "Done.")
        }
        return FixtureWeb.Response(200, "text/event-stream", sse(turn, promptTokens))
    }

    private fun sse(turn: Oracle.Turn, promptTokens: Int): String = buildString {
        if (turn.calls.isEmpty()) {
            append(chunk(buildJsonObject { put("content", turn.text ?: "Done.") }, "stop"))
        } else {
            turn.calls.forEachIndexed { i, call ->
                val args = placeholders.fill(call.args.toString())
                val tc = buildJsonObject {
                    put("index", i)
                    put("id", "call_${System.nanoTime()}_$i")
                    put("type", "function")
                    put("function", buildJsonObject { put("name", call.tool); put("arguments", args) })
                }
                append(chunk(buildJsonObject { put("tool_calls", JsonArray(listOf(tc))) }, if (i == turn.calls.lastIndex) "tool_calls" else null))
            }
        }
        append("data: {\"choices\":[],\"usage\":{\"prompt_tokens\":$promptTokens,\"completion_tokens\":${COMPLETION_TOKENS}}}\n\n")
        append("data: [DONE]\n\n")
    }

    private fun chunk(delta: JsonObject, finish: String?): String {
        val choice = buildJsonObject {
            put("index", 0)
            put("delta", delta)
            if (finish != null) put("finish_reason", finish)
        }
        return "data: ${buildJsonObject { put("choices", JsonArray(listOf(choice))) }}\n\n"
    }

    private fun JsonObject.role() = (this["role"] as? JsonPrimitive)?.contentOrNull

    private fun textOf(message: JsonObject): String = when (val c: JsonElement? = message["content"]) {
        is JsonPrimitive -> c.contentOrNull.orEmpty()
        is JsonArray -> c.joinToString("\n") { part -> ((part as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull.orEmpty() }
        else -> ""
    }

    companion object {
        const val PROVIDER_ID = "bench-fake"
        const val MODEL = "scripted-oracle"
        const val NO_ORACLE = "I can't do this task. (Scripted fake model: this task has no oracle.)"
        private const val COMPLETION_TOKENS = 40

        fun provider(base: String) = ProviderConfig(PROVIDER_ID, "Scripted fake", ApiFormat.OPENAI_CHAT, "$base/v1", MODEL, requiresKey = false, contextWindow = 200_000)
    }
}
