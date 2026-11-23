package dev.locus.core.data.agent

import dev.locus.core.model.AgentControlTools
import dev.locus.core.model.AppSettings
import dev.locus.core.model.ModelRef
import dev.locus.core.model.Role
import dev.locus.core.testing.ScriptedProvider
import dev.locus.core.testing.ScriptedProvider.script
import dev.locus.core.testing.TestGraph
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Plan mode's "Run with" (docs/design/plan-run.md): a plan made on the chat's model and carried out on another one.
 * The run model gets the chat's own frozen system prompt, tool list and path; the chat goes back to its own model
 * when that task ends; and the receipt shows both models.
 */
class PlanRunTest {
    /** A second provider on the same scripted host, with its own model. */
    private val other = ScriptedProvider.config.copy(id = "other", name = "Other", model = "other-model")
    private val settings = AppSettings(
        providers = listOf(ScriptedProvider.config, other), defaultProviderId = ScriptedProvider.config.id,
        autoTitle = false, suggestFollowUps = false, reflectAfterTasks = false,
    )
    private val otherRef = ModelRef(other.id, other.model)

    private fun TestScope.graph() = TestGraph(backgroundScope, settings)

    private suspend fun TestGraph.idle(chat: String) {
        runner.states.first { it[chat]?.running != true }
        while (runner.activeCount > 0) delay(5)
    }

    private fun TestGraph.bodies(): List<JsonObject> = synchronized(requests) {
        requests.filter { it.url.host == "llm.test" }.map { Json.parseToJsonElement((it.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject }
    }

    private fun JsonObject.model() = getValue("model").jsonPrimitive.content

    /** A chat in plan mode whose model proposed a plan that now waits for Approve. */
    private suspend fun TestGraph.planned(): String {
        val chat = conversations.create(ScriptedProvider.config.id).id
        conversations.setPlanMode(chat, true)
        runner.send(chat, "Tidy my files")
        conversations.observeMessages(chat).first { m -> m.any { it.role == Role.TOOL } }
        idle(chat)
        return chat
    }

    @Test
    fun `the plan runs on the picked model with the chat's own prompt and tools, then the chat is back on its model`() = runTest {
        val g = graph()
        g.script(
            ScriptedProvider.toolCall("p1", AgentControlTools.PLAN_PROPOSE, """{"title":"Tidy","steps":["List the files","Rename them"]}"""),
            ScriptedProvider.text("Renamed everything."),
            ScriptedProvider.text("Anything else?"),
        )
        val chat = g.planned()
        g.runner.approvePlan(chat, otherRef)
        g.conversations.observeMessages(chat).first { m -> m.lastOrNull()?.text == "Renamed everything." }
        g.idle(chat)

        val (planning, running) = g.bodies()
        assertEquals("test-model", planning.model())
        assertEquals("other-model", running.model())
        // The frozen system prompt and the fixed tool list, exactly as the chat's own request sent them.
        assertEquals(planning.getValue("messages").jsonArray.first(), running.getValue("messages").jsonArray.first())
        assertEquals(planning["tools"], running["tools"])
        // Append-only: the run model's request starts with everything the planning request sent.
        val before = planning.getValue("messages").jsonArray
        assertEquals(before.toList(), running.getValue("messages").jsonArray.take(before.size))

        // Plan mode went off, the reply is stored as the other model's, and the chat says it's back on its own.
        assertFalse(assertNotNull(g.conversations.get(chat)).planMode)
        val rows = g.database.messageRows.filter { it.conversationId == chat }
        assertEquals("other-model", rows.last { it.role == "ASSISTANT" }.model)
        val state = g.runner.states.value.getValue(chat)
        assertNull(state.planRun)
        assertEquals(RunNote.PlanRunDone("other-model (Other)", "test-model (Scripted)"), state.noticeNote)
        // The chat's own model is unchanged.
        assertNull(g.conversations.get(chat)?.modelOverride)
        assertEquals(ScriptedProvider.config.id, g.conversations.get(chat)?.providerId)

        // The receipt lists the run model, and the plan's model with what it cost.
        val receipt = assertNotNull(g.receipts.latest(chat))
        assertEquals(listOf("other-model"), receipt.models.map { it.name })
        assertEquals(listOf("test-model"), assertNotNull(receipt.plan).models)

        // The next message goes to the chat's own model again.
        g.runner.send(chat, "Thanks")
        g.conversations.observeMessages(chat).first { m -> m.lastOrNull()?.text == "Anything else?" }
        g.idle(chat)
        assertEquals("test-model", g.bodies().last().model())
    }

    @Test
    fun `a model whose provider is gone sends nothing, and the plan still waits`() = runTest {
        val g = graph()
        g.script(ScriptedProvider.toolCall("p1", AgentControlTools.PLAN_PROPOSE, """{"title":"Tidy","steps":["List the files"]}"""))
        val chat = g.planned()
        val sent = g.bodies().size
        g.runner.approvePlan(chat, ModelRef("gone", "some-model"))
        val note = g.runner.states.first { it[chat]?.noticeNote is RunNote.PlanRunUnavailable }.getValue(chat).noticeNote
        assertTrue("no longer set up" in assertNotNull(note).english, note.english)
        assertEquals(sent, g.bodies().size)
        assertTrue(assertNotNull(g.conversations.get(chat)).planMode, "the plan still waits for Approve")
        assertEquals(Role.TOOL, g.conversations.history(chat).last().role, "no approval message was added")
    }

    @Test
    fun `picking the chat's own model is a normal Approve`() = runTest {
        val g = graph()
        g.script(
            ScriptedProvider.toolCall("p1", AgentControlTools.PLAN_PROPOSE, """{"title":"Tidy","steps":["List the files"]}"""),
            ScriptedProvider.text("Done."),
        )
        val chat = g.planned()
        g.runner.approvePlan(chat, ModelRef(ScriptedProvider.config.id, "test-model"))
        g.conversations.observeMessages(chat).first { m -> m.lastOrNull()?.text == "Done." }
        g.idle(chat)
        assertEquals(listOf("test-model", "test-model"), g.bodies().map { it.model() })
        assertFalse(g.runner.states.value.getValue(chat).noticeNote is RunNote.PlanRunDone)
        assertEquals(AgentControlTools.PLAN_APPROVED, g.conversations.history(chat).first { it.role == Role.USER && it.text == AgentControlTools.PLAN_APPROVED }.text)
    }
}
