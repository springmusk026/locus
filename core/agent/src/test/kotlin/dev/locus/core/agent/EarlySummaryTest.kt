package dev.locus.core.agent

import dev.locus.core.agent.tools.agentControlTools
import dev.locus.core.llm.AssistantTurn
import dev.locus.core.llm.ChatEvent
import dev.locus.core.llm.ChatModel
import dev.locus.core.llm.ChatRequest
import dev.locus.core.llm.LlmException
import dev.locus.core.llm.LlmFailure
import dev.locus.core.llm.OpenAiChatModel
import dev.locus.core.llm.StopReason
import dev.locus.core.llm.wireBody
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.AuditEntry
import dev.locus.core.model.Conversation
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.Role
import dev.locus.core.model.TaskBudget
import dev.locus.core.model.TokenUsage
import dev.locus.core.model.ToolPolicy
import dev.locus.core.model.ToolRisk
import dev.locus.core.model.ToolSpec
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Starting the summary early (docs/design/compaction.md): at 70% of the window the summary request goes out beside
 * the run; at the 80% threshold the run uses it (waiting if it's still out), keeping what it did meanwhile after the
 * summary. The view is rebuilt byte for byte from storage, the run's own requests don't change until then, the
 * summary is billed whether it's used or not, and a failed or unusable one falls back to the ordinary summary.
 */
class EarlySummaryTest {
    private val conversation = Conversation("chat-1", "t", "p", "system", 0, 0)
    private val task = Message("u", "chat-1", Role.USER, listOf(Part.Text("Rename every photo in the trip folder by date")), 0)

    /**
     * The run's turns come from [main] in order; summary requests (the last message is the prompt) from [summaries],
     * each after [summaryDelay] of virtual time. A step takes [STEP_MS].
     */
    private class Split(
        private val main: List<Any>,
        private val summaries: List<Any>,
        private val summaryDelay: Long,
        override val format: ApiFormat = ApiFormat.OPENAI_CHAT,
    ) : ChatModel {
        private val mainQueue = ArrayDeque(main)
        private val summaryQueue = ArrayDeque(summaries)
        val requests = mutableListOf<ChatRequest>()
        val mainRequests = mutableListOf<ChatRequest>()
        val summaryRequests = mutableListOf<ChatRequest>()
        var summaryCancelled = false

        override fun stream(request: ChatRequest): Flow<ChatEvent> = flow {
            val copy = request.copy(messages = request.messages.toList())
            requests += copy
            val summary = request.messages.lastOrNull()?.text == PROMPT
            if (summary) {
                summaryRequests += copy
                try {
                    delay(summaryDelay)
                } catch (e: CancellationException) {
                    summaryCancelled = true
                    throw e
                }
            } else {
                mainRequests += copy
                delay(STEP_MS)
            }
            when (val next = (if (summary) summaryQueue else mainQueue).removeFirst()) {
                is Throwable -> throw next
                is AssistantTurn -> emit(ChatEvent.Completed(next))
                else -> error("bad script")
            }
        }
    }

    private class Touch : Tool {
        override val risk = ToolRisk.WRITE
        override val spec = ToolSpec("touch", "test", ObjectSchema().string("path", "p").build())
        override fun describe(args: JsonObject) = "touch"
        override suspend fun execute(args: JsonObject): ToolResult = ToolResult("renamed")
    }

    /** Outside content: a run that reads it is tainted ([Taint]). */
    private class Search : Tool {
        override val risk = ToolRisk.READ
        override val spec = ToolSpec("web_search", "test", ObjectSchema().string("q", "q").build())
        override fun describe(args: JsonObject) = "search"
        override suspend fun execute(args: JsonObject): ToolResult = ToolResult("Photos are usually named by EXIF date.")
    }

    private class Store : TranscriptStore {
        val messages = mutableListOf<Message>()
        override suspend fun append(message: Message) { messages += message }
        override suspend fun record(entry: AuditEntry) = Unit
    }

    private fun call(id: String, tool: String, usage: Int, format: ApiFormat = ApiFormat.OPENAI_CHAT) = AssistantTurn(
        "", listOf(Part.ToolCall(id, tool, if (tool == "touch") """{"path":"a.jpg"}""" else """{"q":"photo names"}""")), StopReason.TOOL_USE, format, null,
        usage = TokenUsage(usage, 50),
    )

    private val summary = AssistantTurn(
        "Searched how to name photos; renaming by EXIF date as YYYY-MM-DD_n.jpg, 2023 folder first.", emptyList(), StopReason.END_TURN, ApiFormat.OPENAI_CHAT, null,
        usage = TokenUsage(700, 40),
    )
    private val done = AssistantTurn("All renamed.", emptyList(), StopReason.END_TURN, ApiFormat.OPENAI_CHAT, null, usage = TokenUsage(200, 10))

    private var n = 0
    private fun loop(model: ChatModel, store: Store, background: Boolean = true, budget: RunBudget? = null) = AgentLoop(
        model, ToolRegistry(listOf<Tool>(Touch(), Search()) + agentControlTools()), { ToolPolicy.ALLOW }, ApprovalGate { true }, store,
        // A window of 1,000: the summary starts past 700 tokens and is due past 800.
        AgentConfig(compaction = CompactionSetup(1_000, prompt = PROMPT, background = background), budget = budget),
        ids = { "id${n++}" },
    )

    private fun checkpointIn(store: Store) = store.messages.single { m -> m.parts.any { it is Part.Checkpoint } }
    private fun Message.checkpoint() = parts.filterIsInstance<Part.Checkpoint>().single()

    @Test
    fun `a summary that's ready by the threshold is used at once, the turns written meanwhile kept after it`() = runTest {
        // Step 1 ends at 770 tokens (start the summary), step 2 at 810 (due).
        val model = Split(listOf(call("c1", "web_search", 720), call("c2", "touch", 760), done), listOf(summary), summaryDelay = 1)
        val store = Store().apply { messages += task }
        val events = loop(model, store).run(conversation, listOf(task)).toList()

        assertIs<Outcome.Completed>(events.filterIsInstance<AgentEvent.Finished>().single().outcome)
        assertEquals(3, model.mainRequests.size)
        assertEquals(1, model.summaryRequests.size)
        // It was ready: nobody waited for it.
        assertTrue(events.none { it is AgentEvent.Compacting })

        // The summary request is the step-2 request plus the question: the same prefix, so it reads the same cache.
        val step2 = model.mainRequests[1]
        val ask = model.summaryRequests.single()
        assertEquals(step2.system, ask.system)
        assertEquals(step2.tools, ask.tools)
        assertEquals(step2.cacheKey, ask.cacheKey)
        assertEquals(step2.messages, ask.messages.dropLast(1))

        // Stored after the turns it didn't cover, naming the last message it did.
        val checkpoint = checkpointIn(store)
        val part = checkpoint.checkpoint()
        val coveredEnd = store.messages.first { m -> m.toolResults.any { it.callId == "c1" } }
        assertEquals(coveredEnd.id, part.coversUpTo)
        assertEquals(ask.messages.size - 1, part.replacedMessages)
        // It carries what the part it covers read from outside.
        assertEquals(listOf("web_search"), part.untrustedSources)

        // The next request: the summary, then step 2's call and result word for word.
        val after = model.mainRequests[2].messages
        assertEquals(3, after.size)
        assertEquals(checkpoint.id, after[0].id)
        assertTrue(after[0].text.startsWith(Compaction.HEADER) && summary.text in after[0].text)
        assertTrue("kept word for word" in after[0].text, after[0].text)
        assertEquals(listOf("c2"), after[1].toolCalls.map { it.id })
        assertEquals(listOf("c2"), after[2].toolResults.map { it.callId })

        // Rebuilt from storage (as it stood when that request went out, before the final reply), it's the same
        // context, so the same bytes on the wire, and every rebuild agrees.
        val rebuilt = Compaction.modelContext(store.messages.dropLast(1))
        assertEquals(after, rebuilt)
        assertEquals(Compaction.modelContext(store.messages.dropLast(1).toList()), rebuilt)
        val wire = OpenAiChatModel(HttpClient(MockEngine { respond("") }), ProviderConfig("o", "O", ApiFormat.OPENAI_CHAT, "https://api.openai.com/v1", "gpt-5.5"), "k")
        assertEquals(wire.wireBody(model.mainRequests[2]).toString(), wire.wireBody(model.mainRequests[2].copy(messages = rebuilt)).toString())
        // A later run that starts from storage is tainted by what the summary covers.
        assertTrue("web_search" in Taint.sourcesIn(rebuilt))

        val compacted = events.filterIsInstance<AgentEvent.Compacted>().single()
        assertEquals(700, compacted.usage?.input)
        assertEquals(part.replacedMessages, compacted.replaced)
    }

    @Test
    fun `the run's own requests are the same as without it until the summary is used`() = runTest {
        val script = { listOf(call("c1", "web_search", 720), call("c2", "touch", 760), done) }
        val early = Split(script(), listOf(summary), summaryDelay = 1)
        loop(early, Store()).run(conversation, listOf(task)).toList()
        val blocking = Split(script(), listOf(summary), summaryDelay = 1)
        loop(blocking, Store(), background = false).run(conversation, listOf(task)).toList()

        // Message ids and times differ between the two runs; what goes on the wire doesn't.
        val wire = OpenAiChatModel(HttpClient(MockEngine { respond("") }), ProviderConfig("o", "O", ApiFormat.OPENAI_CHAT, "https://api.openai.com/v1", "gpt-5.5"), "k")
        assertEquals(blocking.mainRequests.take(2).map { wire.wireBody(it).toString() }, early.mainRequests.take(2).map { wire.wireBody(it).toString() })
        // The summary went out beside step 2 rather than after it, and covers what step 2 was sent.
        assertEquals(early.mainRequests[1].messages, early.summaryRequests.single().messages.dropLast(1))
        assertEquals(blocking.mainRequests[1].messages.size + 2, blocking.summaryRequests.single().messages.size - 1)
        // Without it the summary replaces everything; with it, step 2 stays.
        assertEquals(1, blocking.mainRequests[2].messages.size)
        assertEquals(3, early.mainRequests[2].messages.size)
    }

    @Test
    fun `at the threshold the run waits for a summary that's still out`() = runTest {
        val model = Split(listOf(call("c1", "touch", 720), call("c2", "touch", 760), done), listOf(summary), summaryDelay = 10 * STEP_MS)
        val store = Store().apply { messages += task }
        val events = loop(model, store).run(conversation, listOf(task)).toList()

        assertIs<Outcome.Completed>(events.filterIsInstance<AgentEvent.Finished>().single().outcome)
        assertEquals(1, model.summaryRequests.size, "it waited rather than sending another")
        val compacting = events.indexOfFirst { it is AgentEvent.Compacting }
        assertTrue(compacting >= 0 && compacting < events.indexOfFirst { it is AgentEvent.Compacted })
        assertEquals(model.mainRequests[1].messages, model.summaryRequests.single().messages.dropLast(1))
        assertEquals(3, model.mainRequests[2].messages.size)
        assertEquals(model.mainRequests[2].messages, Compaction.modelContext(store.messages.dropLast(1)))
    }

    @Test
    fun `a run that ends first cancels a summary still out and stores nothing`() = runTest {
        val budget = RunBudget(TaskBudget(tokens = 1_000_000))
        val model = Split(listOf(call("c1", "touch", 720), done), listOf(summary), summaryDelay = 10 * STEP_MS)
        val store = Store().apply { messages += task }
        val events = loop(model, store, budget = budget).run(conversation, listOf(task)).toList()
        runCurrent()

        assertIs<Outcome.Completed>(events.filterIsInstance<AgentEvent.Finished>().single().outcome)
        assertTrue(model.summaryCancelled)
        assertTrue(events.none { it is AgentEvent.Compacted })
        assertTrue(store.messages.none { m -> m.parts.any { it is Part.Checkpoint } })
        // Its prompt was billed although nothing came back: counted from an estimate (the context it started at, 770
        // tokens plus the step's result, and the question), before the run ends, in the budget too.
        val unused = events.filterIsInstance<AgentEvent.SummaryUnused>().single()
        val estimate = unused.usage!!
        assertEquals(770 + Compaction.estimateTokens("renamed") + Compaction.estimateTokens(PROMPT), estimate.input)
        assertEquals(0, estimate.output)
        assertTrue(events.indexOf(unused) < events.indexOfFirst { it is AgentEvent.Finished })
        val expected = listOf(TokenUsage(720, 50), done.usage!!, estimate).sumOf { TaskBudget.budgetTokens(it) }
        assertEquals(expected, budget.meter().tokens)
    }

    @Test
    fun `a summary that came back unused is still billed, before the run ends`() = runTest {
        val budget = RunBudget(TaskBudget(tokens = 1_000_000))
        // Ends at step 2, before the threshold, with the summary back since step 1.
        val model = Split(listOf(call("c1", "touch", 720), done), listOf(summary), summaryDelay = 1)
        val store = Store().apply { messages += task }
        val events = loop(model, store, budget = budget).run(conversation, listOf(task)).toList()

        val unused = events.filterIsInstance<AgentEvent.SummaryUnused>().single()
        assertEquals(700, unused.usage?.input)
        assertTrue(events.indexOf(unused) < events.indexOfFirst { it is AgentEvent.Finished })
        assertTrue(store.messages.none { m -> m.parts.any { it is Part.Checkpoint } })
        // The task budget counts it with the run's own requests.
        val expected = listOf(TokenUsage(720, 50), done.usage!!, summary.usage!!).sumOf { TaskBudget.budgetTokens(it) }
        assertEquals(expected, budget.meter().tokens)
    }

    @Test
    fun `a summary that failed falls back to the ordinary one at the threshold`() = runTest {
        val failed = LlmException("bad request", 400)
        val model = Split(listOf(call("c1", "touch", 720), call("c2", "touch", 760), done), listOf(failed, summary), summaryDelay = 1)
        val store = Store().apply { messages += task }
        val events = loop(model, store).run(conversation, listOf(task)).toList()

        assertIs<Outcome.Completed>(events.filterIsInstance<AgentEvent.Finished>().single().outcome)
        assertEquals(2, model.summaryRequests.size, "once in the background, then the ordinary summary at the threshold")
        assertTrue(events.any { it is AgentEvent.Compacting })
        assertNull(checkpointIn(store).checkpoint().coversUpTo)
        assertEquals(1, model.mainRequests[2].messages.size, "the ordinary summary replaces everything")
        assertTrue(events.none { it is AgentEvent.SummaryUnused }, "a failed request came back with nothing to bill")
    }

    @Test
    fun `too much after it falls back to the ordinary summary, and the unused one is billed`() = runTest {
        // Step 2 jumps from 770 to 1,350 tokens: keeping that after the summary would leave too big a context.
        val model = Split(listOf(call("c1", "touch", 720), call("c2", "touch", 1_300), done), listOf(summary, summary), summaryDelay = 1)
        val store = Store().apply { messages += task }
        val events = loop(model, store).run(conversation, listOf(task)).toList()

        assertIs<Outcome.Completed>(events.filterIsInstance<AgentEvent.Finished>().single().outcome)
        assertEquals(1, events.count { it is AgentEvent.SummaryUnused })
        assertEquals(2, model.summaryRequests.size)
        assertNull(checkpointIn(store).checkpoint().coversUpTo)
        assertEquals(1, model.mainRequests[2].messages.size)
    }

    @Test
    fun `an overflow drops the summary from the background for an ordinary one`() = runTest {
        val overflow = LlmException("p: prompt is too long: 1200 tokens > 1000 maximum", 400, kind = LlmFailure.CONTEXT_OVERFLOW)
        val model = Split(listOf(call("c1", "touch", 720), overflow, done), listOf(summary, summary), summaryDelay = 1)
        val store = Store().apply { messages += task }
        val events = loop(model, store).run(conversation, listOf(task)).toList()

        assertIs<Outcome.Completed>(events.filterIsInstance<AgentEvent.Finished>().single().outcome)
        assertEquals(1, events.count { it is AgentEvent.SummaryUnused })
        assertTrue(events.any { it is AgentEvent.Compacting && it.overflow })
        assertNull(checkpointIn(store).checkpoint().coversUpTo)
    }

    @Test
    fun `stopping the run mid-summary cancels it and leaves storage as it was`() = runTest {
        val model = Split(listOf(call("c1", "touch", 720), call("c2", "touch", 720), done), listOf(summary), summaryDelay = 100 * STEP_MS)
        val store = Store().apply { messages += task }
        val job = launch { loop(model, store).run(conversation, listOf(task)).toList() }
        advanceTimeBy(STEP_MS + 1)
        runCurrent()
        assertEquals(1, model.summaryRequests.size)
        job.cancel()
        job.join()
        assertTrue(model.summaryCancelled)
        assertTrue(store.messages.none { m -> m.parts.any { it is Part.Checkpoint } })
        // What a restart rebuilds is the stored transcript, untouched.
        assertEquals(store.messages, Compaction.modelContext(store.messages))
    }

    @Test
    fun `never on Anthropic, whose preserved thinking can't have the turns before it rewritten`() = runTest {
        val script = { listOf(call("c1", "touch", 720, ApiFormat.ANTHROPIC_MESSAGES), call("c2", "touch", 760, ApiFormat.ANTHROPIC_MESSAGES), done) }
        val model = Split(script(), listOf(summary), summaryDelay = 1, format = ApiFormat.ANTHROPIC_MESSAGES)
        loop(model, Store()).run(conversation, listOf(task)).toList()
        assertEquals(listOf(false, false, true, false), model.requests.map { it in model.summaryRequests })
        assertEquals(1, model.mainRequests[2].messages.size)
        // Nor on the Responses API, which replays encrypted reasoning.
        assertFalse(Compaction.keepsTurnsAfterSummary(ApiFormat.OPENAI_RESPONSES))
        assertFalse(Compaction.keepsTurnsAfterSummary(ApiFormat.CHATGPT))
        assertTrue(Compaction.keepsTurnsAfterSummary(ApiFormat.OPENAI_CHAT))
    }

    @Test
    fun `an edited background summary keeps the turns after it, and an unknown end replaces everything`() {
        val a = Message("a", "chat-1", Role.ASSISTANT, listOf(Part.ToolCall("c1", "touch", "{}")), 1)
        val r = Message("r", "chat-1", Role.TOOL, listOf(Part.ToolResult("c1", "touch", "renamed")), 2)
        val a2 = Message("a2", "chat-1", Role.ASSISTANT, listOf(Part.ToolCall("c2", "touch", "{}")), 3)
        val r2 = Message("r2", "chat-1", Role.TOOL, listOf(Part.ToolResult("c2", "touch", "renamed")), 4)
        val cp = Message("cp", "chat-1", Role.USER, listOf(Part.Checkpoint("what happened so far", 3, coversUpTo = "r")), 5)
        val chat = listOf(task, a, r, a2, r2, cp)
        assertEquals(listOf("cp", "a2", "r2"), Compaction.modelContext(chat).map { it.id })

        val edited = Message("ed", "chat-1", Role.USER, listOf(Compaction.edited(cp, "what really happened so far")), 6)
        val context = Compaction.modelContext(chat + edited)
        assertEquals(listOf("ed", "a2", "r2"), context.map { it.id })
        assertTrue("what really happened" in context.first().text)

        val lost = Message("cp", "chat-1", Role.USER, listOf(Part.Checkpoint("what happened so far", 3, coversUpTo = "gone")), 5)
        assertEquals(listOf("cp"), Compaction.modelContext(listOf(task, a, r, a2, r2, lost)).map { it.id })
    }

    private companion object {
        const val PROMPT = "Summarise the chat."
        const val STEP_MS = 10L
    }
}
