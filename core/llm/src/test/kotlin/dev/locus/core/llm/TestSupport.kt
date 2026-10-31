package dev.locus.core.llm

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel

class RecordingEngine(private val status: HttpStatusCode = HttpStatusCode.OK, private val body: String) {
    val requests = mutableListOf<HttpRequestData>()
    val client = HttpClient(MockEngine { request ->
        requests += request
        respond(ByteReadChannel(body), status, headersOf(HttpHeaders.ContentType, "text/event-stream"))
    })

    fun lastBody(): String = (requests.last().body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
}

fun sse(vararg events: Pair<String?, String>): String = events.joinToString("") { (event, data) ->
    (if (event != null) "event: $event\n" else "") + "data: $data\n\n"
}
