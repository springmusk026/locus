package dev.locus.core.testing

import dev.locus.core.model.ApiFormat
import dev.locus.core.model.ProviderConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A Chat Completions provider at llm.test that needs no key, and the stream bodies it answers with.
 * [script] makes [TestGraph] answer each chat request with the next reply in order.
 */
object ScriptedProvider {
    val config = ProviderConfig("scripted", "Scripted", ApiFormat.OPENAI_CHAT, "https://llm.test/v1", "test-model", requiresKey = false, contextWindow = 100_000)

    /** A finished reply with [text]. */
    fun text(text: String, promptTokens: Int = 10, completionTokens: Int = 5): String =
        chunk(buildJsonObject { put("content", text) }.toString(), "stop") +
            "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":$promptTokens,\"completion_tokens\":$completionTokens}}\n\ndata: [DONE]\n\n"

    /** A turn that calls [tool] with [argumentsJson]. */
    fun toolCall(id: String, tool: String, argumentsJson: String): String {
        val call = buildJsonObject {
            put("index", 0)
            put("id", id)
            put("function", buildJsonObject { put("name", tool); put("arguments", argumentsJson) })
        }
        return "data: {\"choices\":[{\"delta\":{\"tool_calls\":[$call]},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n"
    }

    private fun chunk(delta: String, finish: String) = "data: {\"choices\":[{\"delta\":$delta,\"finish_reason\":\"$finish\"}]}\n\n"

    /** Answers chat requests to [config] with [replies] in order, then fails the test loudly. */
    fun TestGraph.script(vararg replies: String) {
        val queue = ArrayDeque(replies.toList())
        http = { request ->
            if (request.url.host == "llm.test" && request.url.encodedPath.endsWith("/chat/completions")) {
                val next = synchronized(queue) { queue.removeFirstOrNull() } ?: error("No scripted reply left for ${request.url}")
                respond(next, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
            } else {
                respond("", HttpStatusCode.NotFound)
            }
        }
    }
}
