package dev.locus.core.browser

import dev.locus.core.agent.AgentConfig
import dev.locus.core.agent.AgentLoop
import dev.locus.core.agent.ApprovalAnswer
import dev.locus.core.agent.ApprovalGate
import dev.locus.core.agent.ApprovalRequest
import dev.locus.core.agent.ObjectSchema
import dev.locus.core.agent.Tool
import dev.locus.core.agent.ToolCallContext
import dev.locus.core.agent.ToolRegistry
import dev.locus.core.agent.ToolResult
import dev.locus.core.agent.TranscriptStore
import dev.locus.core.llm.AssistantTurn
import dev.locus.core.llm.ChatEvent
import dev.locus.core.llm.ChatModel
import dev.locus.core.llm.ChatRequest
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
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Take over and hand back through the real [AgentLoop] (docs/design/browser.md, "Take over and hand
 * back"). The browser is a fake page (a WebView can't run on the JVM); its snapshot tool is wrapped in
 * [takeoverGate] exactly as BrowserTools wraps every browser call, and formats the page with the real
 * [PageSnapshot] and [SnapshotFormat].
 */
class BrowserTakeoverTest {
    private var now = 0L
    private val control = BrowserControl(clock = { now })

    /** The page as the helper script reports it: the user typed their address into e2 while they had control. */
    private val pageJson = """
        {"title":"Checkout","url":"https://shop.example/checkout","items":[
          {"ref":"e1","role":"textbox","name":"Promo code","value":"SPRING26"},
          {"ref":"e2","role":"textbox","name":"Street address","entered":true,"hasValue":true,"value":"12 Hidden Lane"},
          {"ref":"e3","role":"textbox","name":"Notes","entered":true}
        ]}
    """

    private var snapshots = 0
    private var snapshotsWhileUserHadControl = 0

    private inner class SnapshotTool : Tool {
        override val risk = ToolRisk.READ
        override val defaultPolicy = ToolPolicy.ALLOW
        override val runsAlone get() = true
        override val spec = ToolSpec("browser_snapshot", "snapshot", ObjectSchema().build())
        override fun describe(args: JsonObject) = "Look at the page"
        override suspend fun execute(args: JsonObject): ToolResult {
            val key = currentCoroutineContext()[ToolCallContext]!!.conversationId
            return takeoverGate(control, key) {
                snapshots++
                if (control.inControl(key)) snapshotsWhileUserHadControl++
                val snap = PageSnapshot.parse(Json.parseToJsonElement(pageJson).jsonObject)
                ToolResult(Untrusted.wrap(SnapshotFormat.lines(snap).joinToString("\n")))
            }
        }
    }

    private class Allow : ApprovalGate {
        override suspend fun request(request: ApprovalRequest) = true
        override suspend fun answer(request: ApprovalRequest) = ApprovalAnswer.ALLOW
    }

    private class Store : TranscriptStore {
        override suspend fun append(message: Message) = Unit
        override suspend fun record(entry: AuditEntry) = Unit
    }

    /** Calls browser_snapshot once, then stops; keeps every request it was sent. */
    private class Model : ChatModel {
        val requests = mutableListOf<ChatRequest>()
        override val format = ApiFormat.OPENAI_CHAT
        override fun stream(request: ChatRequest): Flow<ChatEvent> = flow {
            requests += request
            val turn = if (requests.size == 1) {
                AssistantTurn("", listOf(Part.ToolCall("k1", "browser_snapshot", "{}")), StopReason.TOOL_USE, ApiFormat.OPENAI_CHAT, null)
            } else {
                AssistantTurn("done", emptyList(), StopReason.END_TURN, ApiFormat.OPENAI_CHAT, null)
            }
            emit(ChatEvent.Completed(turn))
        }
    }

    private fun loop(model: Model) = AgentLoop(model, ToolRegistry(listOf(SnapshotTool())), { it.defaultPolicy }, Allow(), Store(), AgentConfig(), ids = { "id" + System.nanoTime() })

    private val chat = Conversation("c", "t", "p", "system", 0, 0)
    private val history = listOf(Message("u", "c", Role.USER, listOf(Part.Text("Buy the lamp on shop.example")), 0))

    private fun ChatRequest.lastToolResult(): String =
        messages.flatMap { it.parts }.filterIsInstance<Part.ToolResult>().last().content

    @Test
    fun `while the user has control the call waits and the model is sent nothing about the page`() = runTest {
        val model = Model()
        control.takeOver("c")
        val run = async { loop(model).run(chat, history).toList() }
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        // The model asked for a snapshot and is still waiting for it: no snapshot taken, no second request.
        assertEquals(1, model.requests.size)
        assertEquals(0, snapshots)
        assertTrue(control.waiting("c"))

        now = 60_000
        control.handBack("c", noted = true)
        run.await()
        assertEquals(2, model.requests.size)
        assertEquals(1, snapshots)
        assertEquals(0, snapshotsWhileUserHadControl)
        val result = model.requests[1].lastToolResult()
        assertTrue("The user took control of the browser for 1 min and handed it back" in result, result)
        assertTrue("They left a note for you; it follows as their next message." in result)
    }

    @Test
    fun `values the user typed are hidden in the snapshot after hand-back, the page's own values aren't`() = runTest {
        val model = Model()
        control.takeOver("c")
        val run = async { loop(model).run(chat, history).toList() }
        runCurrent()
        control.handBack("c", noted = false)
        run.await()
        val result = model.requests[1].lastToolResult()
        assertFalse("12 Hidden Lane" in result, result)
        assertTrue("[e2] textbox \"Street address\" ${SnapshotFormat.TYPED_BY_USER}" in result, result)
        // An emptied field the user typed into says nothing about a value.
        assertTrue(result.lines().any { it == "[e3] textbox \"Notes\"" }, result)
        assertTrue("[e1] textbox \"Promo code\" value=\"SPRING26\"" in result, result)
    }

    @Test
    fun `a call that waits too long gets a clear error and the run goes on`() = runTest {
        val model = Model()
        control.takeOver("c")
        val run = async { loop(model).run(chat, history).toList() }
        runCurrent()
        advanceTimeBy(BrowserControl.WAIT_MS + 1_000)
        run.await()
        assertEquals(2, model.requests.size)
        assertEquals(0, snapshots)
        val result = model.requests[1].lastToolResult()
        assertTrue("hasn't handed it back after 5 min" in result, result)
        assertTrue(control.inControl("c"), "the user keeps control after the call gives up")
    }

    @Test
    fun `without a takeover the call runs at once and carries no notice`() = runTest {
        val model = Model()
        loop(model).run(chat, history).toList()
        assertEquals(1, snapshots)
        assertFalse("took control" in model.requests[1].lastToolResult())
    }
}
