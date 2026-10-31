package dev.locus.core.agent

import dev.locus.core.llm.OpenAiChatModel
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.AuditEntry
import dev.locus.core.model.Conversation
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.Role
import dev.locus.core.model.ToolPolicy
import dev.locus.core.model.ToolRisk
import dev.locus.core.model.ToolSpec
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeStringUtf8
import io.ktor.utils.io.writer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** SSE streams that break mid-response, over a real adapter and Ktor's MockEngine. */
class StreamDropRetryTest {
    private val conversation = Conversation("c", "t", "p", "system", 0, 0)
    private val user = Message("u", "c", Role.USER, listOf(Part.Text("go")), 0)
    private val config = ProviderConfig("p", "Groq", ApiFormat.OPENAI_CHAT, "https://api.groq.com/openai/v1", "m")

    private fun chunk(json: String) = "data: $json\n\n"
    private val text = { t: String -> chunk("""{"choices":[{"delta":{"content":"$t"}}]}""") }
    private val stop = chunk("""{"choices":[{"delta":{},"finish_reason":"stop"}]}""") + "data: [DONE]\n\n"
    private val toolStart = chunk("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call1","type":"function","function":{"name":"touch","arguments":"{\"pa"}}]}}]}""")

    /** A body that sends [partial] and then fails like a reset connection. */
    private fun dropping(partial: String): () -> ByteReadChannel = {
        CoroutineScope(Dispatchers.IO).writer {
            channel.writeStringUtf8(partial)
            channel.flush()
            // Let the reader take the partial events first: a failed channel drops what's still buffered.
            kotlinx.coroutines.delay(300)
            throw IOException("Connection reset")
        }.channel
    }

    private fun full(body: String): () -> ByteReadChannel = { ByteReadChannel(body) }

    /** Each request takes the next body; the last one repeats. */
    private fun client(vararg bodies: () -> ByteReadChannel): Pair<HttpClient, () -> Int> {
        var n = 0
        val client = HttpClient(MockEngine {
            val body = bodies[minOf(n++, bodies.lastIndex)]()
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
        })
        return client to { n }
    }

    private class Touch : Tool {
        var runs = 0
        override val risk = ToolRisk.READ
        override val spec = ToolSpec("touch", "test", ObjectSchema().string("path", "p").build())
        override fun describe(args: JsonObject) = "touch"
        override suspend fun execute(args: JsonObject): ToolResult = ToolResult("ok").also { runs++ }
    }

    private class Store : TranscriptStore {
        val messages = mutableListOf<Message>()
        override suspend fun append(message: Message) { messages += message }
        override suspend fun record(entry: AuditEntry) = Unit
    }

    private var id = 0
    private fun loop(client: HttpClient, tool: Touch, store: Store) =
        AgentLoop(OpenAiChatModel(client, config, "gsk"), ToolRegistry(listOf(tool)), { ToolPolicy.ALLOW }, { true }, store, AgentConfig(), ids = { "id${id++}" })

    @Test
    fun `a stream that drops before any tool call is retried and the partial text discarded`() = runTest {
        val (client, requests) = client(dropping(text("Hel")), full(text("Hello") + text(" there") + stop))
        val store = Store()
        val events = loop(client, Touch(), store).run(conversation, listOf(user)).toList()

        assertEquals(2, requests())
        val retry = events.filterIsInstance<AgentEvent.Retrying>().single()
        assertTrue("connection dropped" in retry.reason.lowercase())
        // The partial "Hel" streamed, then Retrying told the UI to drop it; the saved reply is the retry's alone.
        assertTrue(events.indexOfFirst { it is AgentEvent.Retrying } > events.indexOfFirst { it is AgentEvent.TextDelta })
        assertEquals(listOf("Hello there"), store.messages.filter { it.role == Role.ASSISTANT }.map { it.text })
        assertEquals(Outcome.Completed, (events.last() as AgentEvent.Finished).outcome)
    }

    @Test
    fun `a stream that drops after a tool call started is not retried and runs nothing`() = runTest {
        val tool = Touch()
        val store = Store()
        val (client, requests) = client(dropping(text("Let me") + toolStart), full(text("never") + stop))
        val events = loop(client, tool, store).run(conversation, listOf(user)).toList()

        assertEquals(1, requests())
        assertTrue(events.none { it is AgentEvent.Retrying })
        assertEquals(0, tool.runs)
        assertTrue(store.messages.isEmpty())
        val failed = assertIs<Outcome.Failed>((events.last() as AgentEvent.Finished).outcome)
        assertTrue("connection dropped" in failed.message.lowercase())
    }

    @Test
    fun `drops are retried with the same bound as server errors`() = runTest {
        val (client, requests) = client(dropping(text("a")))
        val events = loop(client, Touch(), Store()).run(conversation, listOf(user)).toList()

        assertEquals(AgentConfig().maxRetries + 1, requests())
        assertEquals(AgentConfig().maxRetries, events.count { it is AgentEvent.Retrying })
        assertIs<Outcome.Failed>((events.last() as AgentEvent.Finished).outcome)
    }
}
