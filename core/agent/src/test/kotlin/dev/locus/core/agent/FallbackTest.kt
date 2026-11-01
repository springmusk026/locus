package dev.locus.core.agent

import dev.locus.core.llm.AssistantTurn
import dev.locus.core.llm.ChatEvent
import dev.locus.core.llm.ChatModel
import dev.locus.core.llm.ChatRequest
import dev.locus.core.llm.LlmException
import dev.locus.core.llm.LlmFailure
import dev.locus.core.llm.ModelCapabilities
import dev.locus.core.llm.Support
import dev.locus.core.model.TokenUsage
import dev.locus.core.llm.StopReason
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.AuditEntry
import dev.locus.core.model.Conversation
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import dev.locus.core.model.ToolPolicy
import dev.locus.core.model.ToolRisk
import dev.locus.core.model.ToolSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The fallback chain in the agent loop: when it switches, what it sends, and what it records. */
class FallbackTest {
    private val conversation = Conversation("c", "t", "primary", "system", 0, 0)
    private val user = Message("u", "c", Role.USER, listOf(Part.Text("go")), 0)

    /**
     * Fails with [failures] in order (a [Pair] with text streams it first), then answers [answer].
     * Records every request.
     */
    private class Model(val name: String, vararg failures: Any, val answer: String = "Answer from $name") : ChatModel {
        val script = ArrayDeque(failures.toList())
        val requests = mutableListOf<ChatRequest>()
        override val format = ApiFormat.OPENAI_CHAT
        override fun stream(request: ChatRequest): Flow<ChatEvent> = flow {
            requests += request
            when (val next = script.removeFirstOrNull()) {
                is LlmException -> throw next
                is Pair<*, *> -> {
                    emit(ChatEvent.TextDelta(next.first as String))
                    throw next.second as LlmException
                }
            }
            emit(ChatEvent.TextDelta(answer))
            emit(ChatEvent.Completed(AssistantTurn(answer, emptyList(), StopReason.END_TURN, format, null)))
        }
    }

    private class Store : TranscriptStore {
        val messages = mutableListOf<Message>()
        override suspend fun append(message: Message) { messages += message }
        override suspend fun record(entry: AuditEntry) = Unit
    }

    private class Touch : Tool {
        override val risk = ToolRisk.READ
        override val spec = ToolSpec("touch", "test", ObjectSchema().string("path", "p").build())
        override fun describe(args: JsonObject) = "touch"
        override suspend fun execute(args: JsonObject) = ToolResult("ok")
    }

    private val quota = LlmException("Anthropic: out of credit", 402, kind = LlmFailure.QUOTA)
    private fun overloaded(retryAfterMs: Long? = null) = LlmException("Overloaded", 529, retryable = true, kind = LlmFailure.OVERLOADED, retryAfterMs = retryAfterMs)

    private var id = 0
    private fun run(primary: ChatModel, vararg backups: Pair<String, ChatModel?>, store: Store = Store()): List<AgentEvent> {
        val fallbacks = backups.map { (name, model) ->
            FallbackModel("prov-$name", name, "$name (Prov)") { model ?: throw LlmException("$name has no API key.") }
        }
        val loop = AgentLoop(
            primary, ToolRegistry(listOf(Touch())), { ToolPolicy.ALLOW }, { true }, store,
            AgentConfig(providerId = "primary", model = "main", fallbacks = fallbacks, modelLabel = "main (Primary)"),
            ids = { "id${id++}" },
        )
        var events: List<AgentEvent> = emptyList()
        runTest { events = loop.run(conversation, listOf(user)).toList() }
        return events
    }

    @Test
    fun `a quota error switches at once, and the reply records who answered`() {
        val primary = Model("main", quota)
        val backup = Model("backup")
        val store = Store()
        val events = run(primary, "backup" to backup, store = store)

        assertEquals(1, primary.requests.size)
        assertTrue(events.none { it is AgentEvent.Retrying })
        val fell = events.filterIsInstance<AgentEvent.FellBack>().single()
        assertEquals("main (Primary)", fell.from)
        assertEquals("backup (Prov)", fell.to)
        val reply = store.messages.single { it.role == Role.ASSISTANT }
        assertEquals("Answer from backup", reply.text)
        assertEquals("prov-backup", reply.reply?.providerId)
        assertEquals("backup", reply.reply?.model)
        assertEquals("main (Primary)", reply.reply?.fallbackFrom)
        // The same request, byte for byte: system prompt, tools and messages.
        assertEquals(primary.requests.single(), backup.requests.single())
        assertEquals(Outcome.Completed, (events.last() as AgentEvent.Finished).outcome)
    }

    @Test
    fun `overload is retried first and then the next model takes over`() {
        val primary = Model("main", overloaded(), overloaded(), overloaded(), overloaded())
        val backup = Model("backup")
        val events = run(primary, "backup" to backup)
        // The first try and three retries, then the switch.
        assertEquals(4, primary.requests.size)
        assertEquals(3, events.filterIsInstance<AgentEvent.Retrying>().size)
        assertTrue(events.indexOfFirst { it is AgentEvent.FellBack } > events.indexOfLast { it is AgentEvent.Retrying })
        assertEquals(1, backup.requests.size)
    }

    @Test
    fun `without a chain nothing changes and the error is the run outcome`() {
        val primary = Model("main", quota)
        val events = run(primary)
        assertEquals(1, primary.requests.size)
        val failed = assertIs<Outcome.Failed>((events.last() as AgentEvent.Finished).outcome)
        assertTrue("out of credit" in failed.message)
    }

    @Test
    fun `a bad request does not switch since another model would not fix it`() {
        val primary = Model("main", LlmException("invalid tool schema", 400))
        val backup = Model("backup")
        val events = run(primary, "backup" to backup)
        assertTrue(backup.requests.isEmpty())
        assertIs<Outcome.Failed>((events.last() as AgentEvent.Finished).outcome)
    }

    @Test
    fun `partial output is discarded and never passed on, so the saved reply is the backup alone`() {
        val primary = Model("main", "Half an ans" to quota)
        val backup = Model("backup")
        val store = Store()
        val events = run(primary, "backup" to backup, store = store)

        val firstText = events.indexOfFirst { it is AgentEvent.TextDelta }
        val fell = events.indexOfFirst { it is AgentEvent.FellBack }
        assertTrue(fell > firstText, "FellBack tells the UI to drop what streamed")
        // The backup's request has no trace of the partial text: no assistant prefill, no continuation.
        val sent = backup.requests.single()
        assertEquals(listOf(user), sent.messages)
        assertTrue(sent.messages.none { m -> m.text.contains("Half an ans") })
        assertEquals(listOf("Answer from backup"), store.messages.filter { it.role == Role.ASSISTANT }.map { it.text })
    }

    @Test
    fun `a backup that can't open is skipped`() {
        val primary = Model("main", quota)
        val third = Model("third")
        val events = run(primary, "nokey" to null, "third" to third)
        assertEquals("third (Prov)", events.filterIsInstance<AgentEvent.FellBack>().single().to)
        assertEquals(1, third.requests.size)
    }

    @Test
    fun `when every model fails the last error is the run's`() {
        val primary = Model("main", quota)
        val backup = Model("backup", LlmException("backup refused the key", 401, kind = LlmFailure.AUTH))
        val events = run(primary, "backup" to backup)
        val failed = assertIs<Outcome.Failed>((events.last() as AgentEvent.Finished).outcome)
        assertTrue("backup refused the key" in failed.message)
    }

    @Test
    fun `retry-after is waited out when it's short`() = runTest {
        val primary = Model("main", overloaded(retryAfterMs = 20_000))
        val loop = AgentLoop(primary, ToolRegistry(listOf(Touch())), { ToolPolicy.ALLOW }, { true }, Store(), AgentConfig(), ids = { "id${id++}" })
        val events = loop.run(conversation, listOf(user)).toList()
        assertEquals(20_000, events.filterIsInstance<AgentEvent.Retrying>().single().waitMs)
        assertTrue(currentTime >= 20_000, "waited the provider's 20 s, not the 2 s backoff")
        assertEquals(Outcome.Completed, (events.last() as AgentEvent.Finished).outcome)
    }

    @Test
    fun `a retry-after too long to wait switches models, or fails saying how long`() {
        val long = overloaded(retryAfterMs = 10 * 60_000)
        val primary = Model("main", long)
        val backup = Model("backup")
        val events = run(primary, "backup" to backup)
        assertEquals(1, primary.requests.size)
        assertTrue(events.none { it is AgentEvent.Retrying })
        assertEquals(1, backup.requests.size)

        val alone = run(Model("main", long))
        val failed = assertIs<Outcome.Failed>((alone.last() as AgentEvent.Finished).outcome)
        assertTrue("10 min" in failed.message, failed.message)
        assertTrue(alone.none { it is AgentEvent.Retrying })
    }

    @Test
    fun `after a switch the rest of the run stays on the backup`() {
        val call = AssistantTurn("", listOf(Part.ToolCall("call1", "touch", """{"path":"a"}""")), StopReason.TOOL_USE, ApiFormat.OPENAI_CHAT, null)
        val primary = Model("main", quota)
        val backup = object : ChatModel {
            var n = 0
            override val format = ApiFormat.OPENAI_CHAT
            override fun stream(request: ChatRequest): Flow<ChatEvent> = flow {
                n++
                emit(ChatEvent.Completed(if (n == 1) call else AssistantTurn("done", emptyList(), StopReason.END_TURN, format, null)))
            }
        }
        val store = Store()
        run(primary, "backup" to backup, store = store)
        assertEquals(1, primary.requests.size)
        assertEquals(2, backup.n)
        store.messages.filter { it.role == Role.ASSISTANT }.forEach {
            assertEquals("backup", it.reply?.model)
            assertEquals("main (Primary)", it.reply?.fallbackFrom)
        }
    }

    @Test
    fun `replies from the chat's own model carry no fallback note`() {
        val store = Store()
        run(Model("main"), "backup" to Model("backup"), store = store)
        assertNull(store.messages.single { it.role == Role.ASSISTANT }.reply?.fallbackFrom)
    }

    /** Runs [history] on [primary] with [backups] as (name, model, what's known of it). */
    private fun runWith(primary: ChatModel, history: List<Message>, vararg backups: Triple<String, ChatModel, ModelCapabilities>): List<AgentEvent> {
        val fallbacks = backups.map { (name, model, caps) -> FallbackModel("prov-$name", name, "$name (Prov)", caps) { model } }
        val loop = AgentLoop(
            primary, ToolRegistry(listOf(Touch())), { ToolPolicy.ALLOW }, { true }, Store(),
            AgentConfig(providerId = "primary", model = "main", fallbacks = fallbacks, modelLabel = "main (Primary)"),
            ids = { "id${id++}" },
        )
        var events: List<AgentEvent> = emptyList()
        runTest { events = loop.run(conversation, history).toList() }
        return events
    }

    private val photo = Message("p", "c", Role.USER, listOf(Part.Text("what's this?"), Part.Attachment("cat.png", "image/png", "/x/cat.png", "cat.png", 10)), 0)

    @Test
    fun `a backup known to lack what the chat needs is skipped, an unknown one is tried`() {
        val blind = Model("blind")
        val maybe = Model("maybe")
        val events = runWith(
            Model("main", quota), listOf(photo),
            Triple("blind", blind, ModelCapabilities(tools = Support.YES, vision = Support.NO)),
            Triple("maybe", maybe, ModelCapabilities.UNKNOWN),
        )
        assertTrue(blind.requests.isEmpty())
        assertEquals(1, maybe.requests.size)
        val fell = events.filterIsInstance<AgentEvent.FellBack>().single()
        assertEquals("maybe (Prov)", fell.to)
        assertEquals(listOf("blind (Prov): no vision"), fell.skipped)
        // Tools and vision couldn't be confirmed; it's tried all the same.
        assertEquals("tool calling, vision", fell.unconfirmed)
        assertEquals(Outcome.Completed, (events.last() as AgentEvent.Finished).outcome)
    }

    @Test
    fun `a backup whose window is smaller than the context so far is skipped`() {
        val history = listOf(
            Message("u0", "c", Role.USER, listOf(Part.Text("earlier")), 0),
            Message("a0", "c", Role.ASSISTANT, listOf(Part.Text("long answer")), 0, usage = TokenUsage(input = 150_000, output = 2_000)),
            user,
        )
        val small = Model("small")
        val big = Model("big")
        val events = runWith(
            Model("main", quota), history,
            Triple("small", small, ModelCapabilities(tools = Support.YES, contextWindow = 128_000)),
            Triple("big", big, ModelCapabilities(tools = Support.YES, contextWindow = 1_000_000)),
        )
        assertTrue(small.requests.isEmpty())
        assertEquals(1, big.requests.size)
        val fell = events.filterIsInstance<AgentEvent.FellBack>().single()
        assertTrue(fell.skipped.single().startsWith("small (Prov): context too small (128K"))
        assertNull(fell.unconfirmed)
    }

    @Test
    fun `when every backup is skipped the error says which and why`() {
        val events = runWith(
            Model("main", quota), listOf(photo),
            Triple("blind", Model("blind"), ModelCapabilities(vision = Support.NO)),
        )
        assertTrue(events.none { it is AgentEvent.FellBack })
        val failed = assertIs<Outcome.Failed>((events.last() as AgentEvent.Finished).outcome)
        assertTrue("Backup models skipped: blind (Prov): no vision." in failed.message, failed.message)
    }
}
