package dev.locus.core.agent

import dev.locus.core.llm.AnthropicChatModel
import dev.locus.core.llm.AssistantTurn
import dev.locus.core.llm.ChatEvent
import dev.locus.core.llm.ChatModel
import dev.locus.core.llm.ChatRequest
import dev.locus.core.llm.StopReason
import dev.locus.core.llm.wireBody
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.AuditEntry
import dev.locus.core.model.Conversation
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.Role
import dev.locus.core.model.TokenUsage
import dev.locus.core.model.ToolPolicy
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The run-side parts of cost control (docs/design/cost.md): the compaction ceiling, cache lifetime per run, and the cache line. */
class CostControlsTest {
    // ---- Compaction below the price step -------------------------------------------------------

    @Test
    fun `a ceiling below the price step comes before 80 percent of a large window`() {
        val setup = CompactionSetup(1_000_000, "Summarise.", ceiling = 90_000)
        assertEquals(90_000, setup.limit)
        assertFalse(Compaction.due(TokenUsage(85_000, 4_000), setup))
        assertTrue(Compaction.due(TokenUsage(85_000, 4_000), setup, pendingTokens = 2_000))
        assertTrue(Compaction.due(TokenUsage(95_000, 1_000), setup))
    }

    @Test
    fun `the window still wins when it's the smaller, and a ceiling alone is enough`() {
        assertEquals(80_000, CompactionSetup(100_000, "s", ceiling = 90_000).limit)
        assertEquals(90_000, CompactionSetup(null, "s", ceiling = 90_000).limit)
        assertTrue(Compaction.due(TokenUsage(91_000, 0), CompactionSetup(null, "s", ceiling = 90_000)))
        // Neither known: only the overflow recovery is left.
        assertNull(CompactionSetup(null, "s").limit)
        assertFalse(Compaction.due(TokenUsage(900_000, 0), CompactionSetup(null, "s")))
        // Unchanged without a ceiling: 80 percent of the window.
        assertEquals(800_000, CompactionSetup(1_000_000, "s").limit)
    }

    // ---- Cache lifetime per run ----------------------------------------------------------------

    private class Once : ChatModel {
        val requests = mutableListOf<ChatRequest>()
        override val format = ApiFormat.ANTHROPIC_MESSAGES
        override fun stream(request: ChatRequest): Flow<ChatEvent> = flow {
            requests += request
            emit(ChatEvent.Completed(AssistantTurn("done", emptyList(), StopReason.END_TURN, ApiFormat.ANTHROPIC_MESSAGES, null, usage = TokenUsage(100, 5))))
        }
    }

    private class Store : TranscriptStore {
        override suspend fun append(message: Message) = Unit
        override suspend fun record(entry: AuditEntry) = Unit
    }

    private suspend fun requestOf(config: AgentConfig): ChatRequest {
        val model = Once()
        val task = Message("u", "c", Role.USER, listOf(Part.Text("hi")), 0)
        AgentLoop(model, ToolRegistry(emptyList()), { ToolPolicy.ALLOW }, ApprovalGate { true }, Store(), config, ids = { "id" })
            .run(Conversation("c", "t", "p", "system", 0, 0), listOf(task)).toList()
        return model.requests.single()
    }

    @Test
    fun `a run asks for the long cache only when its config says so`() = runTest {
        assertTrue(requestOf(AgentConfig(longCache = true)).longCache)
        assertFalse(requestOf(AgentConfig()).longCache)
    }

    @Test
    fun `a compaction summary request keeps the short lifetime`() {
        val ask = Message("a", "c", Role.USER, listOf(Part.Text("x")), 0)
        assertFalse(Compaction.request("s", emptyList(), emptyList(), "k", null, "Summarise.", ask).longCache)
    }

    // ---- The cache line in "What was sent" -----------------------------------------------------

    @Test
    fun `the cache line says warm or cold, the hit share and what was written`() {
        assertEquals("warm · 93% read · 2.1K written (1h)", SentView.cacheLine(TokenUsage(100_000, 50, cacheRead = 93_000, cacheWrite = 2_100), "1h"))
        assertEquals("cold · 0% read · 41.9K written (1h + 5m)", SentView.cacheLine(TokenUsage(42_000, 50, cacheWrite = 41_900), "1h + 5m"))
        assertEquals("warm · 50% read · 0 written", SentView.cacheLine(TokenUsage(2_000, 5, cacheRead = 1_000)))
        assertEquals("none reported", SentView.cacheLine(TokenUsage(2_000, 5)))
        assertEquals(
            "warm · 90% read · 1K written · summarised on the server (153.5K tokens)",
            SentView.cacheLine(TokenUsage(10_000, 5, cacheRead = 9_000, cacheWrite = 1_000, serverCompaction = TokenUsage(150_000, 3_500))),
        )
    }

    @Test
    fun `the view reads the request's cache lifetimes from the body Anthropic gets`() {
        val config = ProviderConfig("p", "Anthropic", ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.com", "claude-opus-5-5")
        val model = AnthropicChatModel(HttpClient(MockEngine { respondOk() }), config, "key")
        val messages = listOf(Message("u", "c", Role.USER, listOf(Part.Text("hi")), 0))
        fun view(request: ChatRequest, usage: TokenUsage?) =
            SentView.fromBody(config.format, config.name, config.model, config.baseUrl, model.wireBody(request)!!, SecretScrubber(emptyList()), reportedUsage = usage)
        val main = view(ChatRequest("You are Locus.", messages, emptyList(), longCache = true), TokenUsage(20_000, 10, cacheRead = 18_000, cacheWrite = 2_000, cacheWrite1h = 2_000))
        assertEquals("1h", main.cacheLifetime)
        assertEquals(20_000, main.reportedInputTokens)
        assertEquals("warm · 90% read · 2K written (1h)", main.cache)
        assertEquals("1h + 5m", view(ChatRequest("You are Locus.", messages, emptyList()), null).cacheLifetime)
        assertNull(view(ChatRequest("You are Locus.", messages, emptyList()), null).cache)
        assertNull(view(ChatRequest("You are Locus.", messages, emptyList(), cache = false), null).cacheLifetime)
    }
}
