package dev.locus.core.llm

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.utils.io.readUTF8Line

internal data class SseEvent(val event: String?, val data: String)

/**
 * Reads a `text/event-stream` body line by line. Done by hand rather than with Ktor's SSE plugin
 * because LLM APIs stream from POST requests and return JSON error bodies on non-2xx statuses,
 * which we want to surface verbatim.
 */
internal suspend fun HttpResponse.forEachSseEvent(onEvent: suspend (SseEvent) -> Unit) {
    val channel = bodyAsChannel()
    var event: String? = null
    val data = StringBuilder()
    while (true) {
        val line = channel.readUTF8Line() ?: break
        when {
            line.isEmpty() -> {
                if (data.isNotEmpty()) onEvent(SseEvent(event, data.toString()))
                event = null
                data.clear()
            }
            line.startsWith(":") -> Unit
            line.startsWith("event:") -> event = line.substringAfter(":").trim()
            line.startsWith("data:") -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.substringAfter(":").removePrefix(" "))
            }
        }
    }
    if (data.isNotEmpty()) onEvent(SseEvent(event, data.toString()))
}

internal suspend fun HttpResponse.throwIfFailed(provider: String) {
    val code = status.value
    if (code in 200..299) return
    val body = runCatching { bodyAsText() }.getOrDefault("").take(2000)
    throw LlmException("$provider returned HTTP $code: $body", code, isRetryableStatus(code))
}
