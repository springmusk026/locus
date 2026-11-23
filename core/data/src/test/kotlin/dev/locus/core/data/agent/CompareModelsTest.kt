package dev.locus.core.data.agent

import dev.locus.core.model.AppSettings
import dev.locus.core.model.CompareTurn
import dev.locus.core.model.ModelRef
import dev.locus.core.model.Role
import dev.locus.core.testing.ScriptedProvider
import dev.locus.core.testing.ScriptedProvider.script
import dev.locus.core.testing.TestGraph
import io.ktor.client.engine.mock.respond
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Compare models (docs/design/compare-models.md): one message, two sibling answers in one task. The second
 * model only reads; the chat's own model answers exactly as a normal send would, so its cache is untouched.
 */
class CompareModelsTest {
    /** A second provider on the same scripted host, with its own model. */
    private val other = ScriptedProvider.config.copy(id = "other", name = "Other", model = "other-model")
    private val settings = AppSettings(
        providers = listOf(ScriptedProvider.config, other), defaultProviderId = ScriptedProvider.config.id,
        autoTitle = false, suggestFollowUps = false, reflectAfterTasks = false,
    )
    private val otherRef = ModelRef(other.id, other.model)

    private fun TestScope.graph() = TestGraph(backgroundScope, settings)

    /** Until the chat's task is over and nothing holds its slot. */
    private suspend fun TestGraph.idle(chat: String) {
        runner.states.first { it[chat]?.running != true }
        while (runner.activeCount > 0) delay(5)
    }

    private fun TestGraph.bodies(): List<String> = synchronized(requests) {
        requests.filter { it.url.host == "llm.test" }.map { (it.body as OutgoingContent.ByteArrayContent).bytes().decodeToString() }
    }

    private fun model(body: String) = Json.parseToJsonElement(body).jsonObject.getValue("model").jsonPrimitive.content

    @Test
    fun `both answers branch from the same message, each on its own model, and the second one can't write`() = runTest {
        val g = graph()
        g.script(
            // The second model tries to change something first.
            ScriptedProvider.toolCall("w1", "write_file", """{"path":"notes.txt","content":"from the comparison"}"""),
            ScriptedProvider.text("B: I would write notes.txt."),
            // Then the chat's own model answers.
            ScriptedProvider.text("A: the answer."),
        )
        val chat = g.conversations.create(ScriptedProvider.config.id).id
        var userId: String? = null
        assertTrue(g.runner.sendCompare(chat, "Write my notes", other = otherRef) { userId = it })
        g.idle(chat)

        val rows = g.database.messageRows.filter { it.conversationId == chat }
        val user = rows.single { it.role == "USER" }
        assertEquals(user.id, userId)
        // Two answers, both children of the one user message.
        val firsts = rows.filter { it.parentId == user.id }
        assertEquals(2, firsts.size, rows.toString())
        val replies = rows.filter { it.role == "ASSISTANT" }
        val bFirst = firsts.first()
        val aFirst = firsts.last()
        assertEquals("other-model", bFirst.model)
        assertEquals("other", bFirst.providerId)
        assertEquals("test-model", aFirst.model)
        assertEquals(ScriptedProvider.config.id, aFirst.providerId)
        assertTrue(replies.filter { it.model == "other-model" }.size == 2)

        // The requests went to each model.
        val bodies = g.bodies()
        assertEquals(listOf("other-model", "other-model", "test-model"), bodies.map(::model))

        // The write was refused, and nothing reached the workspace.
        val history = g.conversations.history(chat)
        assertEquals("A: the answer.", history.last().text, "the chat's own answer is shown")
        val refusal = rows.single { it.role == "TOOL" }.partsJson
        assertTrue("this answer is a comparison" in refusal, refusal)
        assertFalse(File(g.context.filesDir, "workspace/notes.txt").exists())

        // Switching to the second answer shows its branch, refusal included.
        g.runner.switchBranch(chat, bFirst.id)
        g.conversations.observeMessages(chat).first { m -> m.lastOrNull()?.text == "B: I would write notes.txt." }
    }

    @Test
    fun `the chat's own request is byte for byte the one a normal turn sends`() = runTest {
        val g = graph()
        g.script(ScriptedProvider.text("Bravo answer."), ScriptedProvider.text("Alpha answer."), ScriptedProvider.text("Again."))
        val chat = g.conversations.create(ScriptedProvider.config.id).id
        var userId: String? = null
        assertTrue(g.runner.sendCompare(chat, "Hello", other = otherRef) { userId = it })
        g.idle(chat)
        val compared = g.bodies().last()

        // A normal turn from the same message (Regenerate runs exactly what a send runs after storing it).
        g.runner.regenerate(chat, userId!!)
        g.conversations.observeMessages(chat).first { m -> m.lastOrNull()?.text == "Again." }
        g.idle(chat)
        val normal = g.bodies().last()
        assertEquals(normal, compared)
        assertEquals("test-model", model(normal))
        // The second model's answer is on another branch: not in the chat's request.
        assertFalse("Bravo answer." in compared, compared)
    }

    @Test
    fun `the second model failing is a notice, and the chat's model still answers`() = runTest {
        val g = graph()
        // The comparison's request gets a 404 (unscripted), then the chat's model is scripted.
        var n = 0
        g.http = { request ->
            n++
            if (n == 1) {
                respond("{\"error\":{\"message\":\"no such model\"}}", io.ktor.http.HttpStatusCode.BadRequest)
            } else {
                respond(ScriptedProvider.text("Alpha answer."), io.ktor.http.HttpStatusCode.OK, io.ktor.http.headersOf(io.ktor.http.HttpHeaders.ContentType, "text/event-stream"))
            }
        }
        val chat = g.conversations.create(ScriptedProvider.config.id).id
        assertTrue(g.runner.sendCompare(chat, "Hello", other = otherRef))
        g.idle(chat)
        val state = g.runner.states.value.getValue(chat)
        assertEquals(null, state.error)
        assertTrue(state.noticeNote is RunNote.CompareFailed, state.toString())
        assertEquals("Alpha answer.", g.conversations.history(chat).last().text)
    }

    @Test
    fun `the task's receipt counts both models, and Keep moves the tally that stays on the phone`() = runTest {
        val g = graph()
        g.script(ScriptedProvider.text("Bravo answer."), ScriptedProvider.text("Alpha answer."))
        val chat = g.conversations.create(ScriptedProvider.config.id).id
        var userId: String? = null
        assertTrue(
            g.runner.sendCompare(chat, "Hello", other = otherRef) { id ->
                userId = id
                g.settingsRepository.recordCompare(CompareTurn(chat, id, ModelRef(ScriptedProvider.config.id, "test-model"), otherRef))
            },
        )
        g.idle(chat)

        val receipt = assertNotNull(g.receipts.latest(chat))
        assertEquals(setOf("test-model", "other-model"), receipt.models.map { it.name }.toSet())
        assertEquals(2, receipt.tokens.requests)

        // Switch to the second answer and keep it: it's the chat's continuation, and the pick is counted.
        val rows = g.database.messageRows.filter { it.conversationId == chat }
        val bFirst = rows.first { it.parentId == userId }
        g.runner.switchBranch(chat, bFirst.id)
        g.conversations.observeMessages(chat).first { m -> m.lastOrNull()?.text == "Bravo answer." }
        assertTrue(g.settingsRepository.keepCompare(chat, userId!!, otherRef))
        assertEquals(bFirst.id, g.conversations.activeLeaf(chat))
        val picks = g.settingsRepository.current().modelPicks.single()
        assertEquals(1, picks.total)
        assertEquals(1, if (picks.first == otherRef) picks.firstKept else picks.secondKept)

        // The next message continues from the kept answer, and nothing about picks is ever sent.
        g.script(ScriptedProvider.text("Next."))
        g.runner.send(chat, "Go on")
        g.conversations.observeMessages(chat).first { m -> m.lastOrNull()?.text == "Next." }
        g.idle(chat)
        val last = g.bodies().last()
        assertTrue("Bravo answer." in last, last)
        assertFalse("Alpha answer." in last, last)
        g.bodies().forEach { body -> assertFalse("firstKept" in body || "modelPicks" in body, body) }
        assertTrue(synchronized(g.requests) { g.requests.all { it.url.host == "llm.test" } })
    }
}
