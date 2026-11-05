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
import dev.locus.core.model.ToolGroup
import dev.locus.core.model.ToolPolicy
import dev.locus.core.model.ToolRisk
import dev.locus.core.model.ToolSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The loop breaker: a model that keeps calling tools that can't run is stopped with a message, instead
 * of spinning until the user notices (the 0.8B model that made 26 refused calls in a row).
 */
class ToolLoopGuardTest {
    private val conversation = Conversation("c", "t", "p", "system", 0, 0)
    private val user = Message("u", "c", Role.USER, listOf(Part.Text("Name three planets with rings")), 0)

    /** Plays [turns] in order and counts the requests; past the end it keeps repeating the last one. */
    private class Scripted(private val turns: List<AssistantTurn>) : ChatModel {
        var requests = 0
        override val format = ApiFormat.OPENAI_CHAT
        override fun stream(request: ChatRequest): Flow<ChatEvent> = flow {
            emit(ChatEvent.Completed(turns[minOf(requests++, turns.lastIndex)]))
        }
    }

    private class Store : TranscriptStore {
        val messages = mutableListOf<Message>()
        override suspend fun append(message: Message) { messages += message }
        override suspend fun record(entry: AuditEntry) = Unit
    }

    private class Probe(name: String, private val result: () -> ToolResult = { ToolResult("ran") }) : Tool {
        var runs = 0
        override val risk = ToolRisk.READ
        override val spec = ToolSpec(name, "probe", ObjectSchema().string("q", "q", required = false).build())
        override fun describe(args: JsonObject) = "probe"
        override suspend fun isObservation(args: JsonObject) = true
        override suspend fun execute(args: JsonObject): ToolResult = result().also { runs++ }
    }

    private var callIds = 0
    private fun call(name: String, args: String = "{}") =
        AssistantTurn("", listOf(Part.ToolCall("k${callIds++}", name, args)), StopReason.TOOL_USE, ApiFormat.OPENAI_CHAT, null)
    private fun calls(vararg names: String) =
        AssistantTurn("", names.map { Part.ToolCall("k${callIds++}", it, "{}") }, StopReason.TOOL_USE, ApiFormat.OPENAI_CHAT, null)
    private val answer = AssistantTurn("Saturn, Jupiter and Uranus.", emptyList(), StopReason.END_TURN, ApiFormat.OPENAI_CHAT, null)

    /** The web group switched off for the chat, as the composer's tools menu does. */
    private val webOff: suspend (Tool, JsonObject) -> String? = { tool, args -> ChatPolicy.denial(tool, args, planMode = false, disabled = setOf(ToolGroup.WEB.key)) }

    private suspend fun run(
        model: Scripted,
        vararg tools: Tool,
        restrict: (suspend (Tool, JsonObject) -> String?)? = null,
        unattended: Boolean = false,
    ): Pair<Outcome, Store> {
        val store = Store()
        var n = 0
        val config = AgentConfig(restrict = restrict, unattended = unattended, readOnly = unattended)
        val events = AgentLoop(model, ToolRegistry(tools.toList()), { ToolPolicy.ALLOW }, { true }, store, config, ids = { "id${n++}" })
            .run(conversation, listOf(user)).toList()
        return (events.last() as AgentEvent.Finished).outcome to store
    }

    private fun Store.results() = messages.filter { it.role == Role.TOOL }.flatMap { it.toolResults }

    @Test
    fun `a model that keeps calling a switched-off tool is stopped after three refused steps`() = runTest {
        val web = Probe("web_search")
        val model = Scripted(listOf(call("web_search")))
        val (outcome, store) = run(model, web, restrict = webOff)
        val stopped = assertIs<Outcome.ToolLoopStopped>(outcome)
        assertEquals(ToolLoop.Kind.REFUSED, stopped.loop.kind)
        assertEquals(ToolLoopGuard.REFUSED_STEPS, model.requests)
        assertEquals(0, web.runs)
        // Every call has its result, so the chat can carry on from here.
        assertEquals(ToolLoopGuard.REFUSED_STEPS, store.results().size)
        assertTrue(store.results().all { it.decision == ApprovalDecision.POLICY_DENIED })
        assertTrue("switch to a larger model" in stopped.loop.message.lowercase(), stopped.loop.message)
        assertTrue("turn tools on" in stopped.loop.message, stopped.loop.message)
        assertTrue(!stopped.loop.unattended)
    }

    @Test
    fun `a scheduled task stopped for refused calls points at the task and its model, not the tools menu`() = runTest {
        val model = Scripted(listOf(call("web_search")))
        val (outcome, _) = run(model, Probe("web_search"), restrict = webOff, unattended = true)
        val loop = assertIs<Outcome.ToolLoopStopped>(outcome).loop
        assertEquals(ToolLoop.Kind.REFUSED, loop.kind)
        assertTrue(loop.unattended)
        val message = loop.message
        assertTrue("refused ${ToolLoopGuard.REFUSED_STEPS} times in a row" in message, message)
        assertTrue("Edit the task" in message && "more capable model" in message, message)
        assertTrue("tools menu" !in message && "this chat" !in message, message)
    }

    @Test
    fun `a scheduled task stopped for a repeated failure points at the task and its model`() = runTest {
        val offline = Probe("web_search") { ToolResult.error("No connection.") }
        val (outcome, _) = run(Scripted(listOf(call("web_search", """{"q":"rings"}"""))), offline, unattended = true)
        val loop = assertIs<Outcome.ToolLoopStopped>(outcome).loop
        assertEquals(ToolLoop.Kind.REPEATED, loop.kind)
        val message = loop.message
        assertTrue("kept failing the same call to web_search" in message, message)
        assertTrue("Edit the task" in message && "more capable model" in message, message)
        assertTrue("Rephrase the request" !in message && "tools menu" !in message, message)
    }

    @Test
    fun `a chat keeps the interactive wording for both kinds`() {
        val refused = ToolLoop(ToolLoop.Kind.REFUSED, "web_search", 3).message
        assertTrue("turn tools on in the tools menu" in refused && "in this chat" in refused, refused)
        assertTrue("Edit the task" !in refused, refused)
        val repeated = ToolLoop(ToolLoop.Kind.REPEATED, "web_search", 5).message
        assertTrue("Rephrase the request" in repeated && "switch to a larger model" in repeated, repeated)
        assertTrue("Edit the task" !in repeated, repeated)
    }

    @Test
    fun `the refusal tells the model plainly to answer without the tool`() = runTest {
        val (_, store) = run(Scripted(listOf(call("web_search"), answer)), Probe("web_search"), restrict = webOff)
        val refusal = store.results().single().content
        assertTrue("Don't call it again" in refusal && "answer the user without it" in refusal, refusal)
    }

    @Test
    fun `a model that answers after a refusal or two finishes normally`() = runTest {
        val (outcome, _) = run(Scripted(listOf(call("web_search"), call("web_search"), answer)), Probe("web_search"), restrict = webOff)
        assertEquals(Outcome.Completed, outcome)
    }

    @Test
    fun `a call that runs in between resets the count`() = runTest {
        val turns = listOf(call("web_search"), call("web_search"), call("read_notes"), call("web_search"), call("web_search"), answer)
        val (outcome, _) = run(Scripted(turns), Probe("web_search"), Probe("read_notes"), restrict = webOff)
        assertEquals(Outcome.Completed, outcome)
    }

    @Test
    fun `several refused calls side by side count as one step`() = runTest {
        val (outcome, _) = run(Scripted(listOf(calls("web_search", "wikipedia", "weather"), answer)), Probe("web_search"), Probe("wikipedia"), Probe("weather"), restrict = webOff)
        assertEquals(Outcome.Completed, outcome)
    }

    @Test
    fun `calls to tools the chat doesn't have count as refused`() = runTest {
        // A chat-only chat declares no tools; a small model may still write a call.
        val model = Scripted(listOf(call("web_search")))
        val (outcome, store) = run(model)
        assertEquals(ToolLoop.Kind.REFUSED, assertIs<Outcome.ToolLoopStopped>(outcome).loop.kind)
        assertEquals(ToolLoopGuard.REFUSED_STEPS, model.requests)
        assertTrue(store.results().all { it.content.startsWith(ToolLoopGuard.UNKNOWN_TOOL) })
    }

    @Test
    fun `the same failing call repeated five times is stopped`() = runTest {
        val offline = Probe("web_search") { ToolResult.error("No connection.") }
        val model = Scripted(listOf(call("web_search", """{"q":"rings"}""")))
        val (outcome, _) = run(model, offline)
        val stopped = assertIs<Outcome.ToolLoopStopped>(outcome)
        assertEquals(ToolLoop.Kind.REPEATED, stopped.loop.kind)
        assertEquals(ToolLoopGuard.REPEATED_FAILURES, offline.runs)
        assertTrue("web_search" in stopped.loop.message, stopped.loop.message)
    }

    @Test
    fun `polling with the same call that succeeds is never stopped`() = runTest {
        val status = Probe("job_status") { ToolResult("still running") }
        val turns = List(8) { call("job_status", """{"q":"job 1"}""") } + answer
        val (outcome, _) = run(Scripted(turns), status)
        assertEquals(Outcome.Completed, outcome)
        assertEquals(8, status.runs)
    }

    @Test
    fun `failing calls with different arguments aren't a repeat`() = runTest {
        val search = Probe("web_search") { ToolResult.error("No results.") }
        val turns = List(6) { i -> call("web_search", """{"q":"try $i"}""") } + answer
        val (outcome, _) = run(Scripted(turns), search)
        assertEquals(Outcome.Completed, outcome)
    }

    @Test
    fun `calls the user declined or the reviewer blocked aren't refusals`() {
        val guard = ToolLoopGuard()
        repeat(4) { i ->
            val c = Part.ToolCall("k$i", "send_email", """{"to":"$i"}""")
            val r = Part.ToolResult("k$i", "send_email", "The user declined this action.", isError = true, decision = ApprovalDecision.USER_DENIED)
            assertNull(guard.afterStep(listOf(c), listOf(r)))
        }
        assertTrue(!ToolLoopGuard.refused(Part.ToolResult("k", "x", "no", isError = true, decision = ApprovalDecision.USER_DENIED)))
        assertTrue(!ToolLoopGuard.refused(Part.ToolResult("k", "x", "blocked", isError = true, decision = ApprovalDecision.REVIEWER_DENIED)))
        assertTrue(ToolLoopGuard.refused(Part.ToolResult("k", "x", "Not allowed", isError = true, decision = ApprovalDecision.POLICY_DENIED)))
    }
}
