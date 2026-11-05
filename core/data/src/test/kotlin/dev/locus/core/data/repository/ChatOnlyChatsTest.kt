package dev.locus.core.data.repository

import dev.locus.core.model.AppSettings
import dev.locus.core.model.Role
import dev.locus.core.testing.ScriptedProvider
import dev.locus.core.testing.ScriptedProvider.script
import dev.locus.core.testing.TestGraph
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A chat that starts on a model known not to call tools declares none (docs/design/model-capabilities.md,
 * "Small models and tools"). While the chat is empty its tools follow the model picked; the first message
 * fixes them, so the tool list stays the same for the chat's life and its cached prefix holds.
 */
class ChatOnlyChatsTest {
    private val quiet = AppSettings(
        providers = listOf(ScriptedProvider.config), defaultProviderId = ScriptedProvider.config.id,
        autoTitle = false, suggestFollowUps = false, reflectAfterTasks = false,
    )
    private val provider = ScriptedProvider.config.id

    /** "tiny" is the model that can't use tools; the provider's default ("test-model") can. */
    private fun TestScope.graph() = TestGraph(backgroundScope, quiet).apply {
        chatOnlyRule = ChatOnlyRule { _, model -> model == TINY }
    }

    /** Sends [text] and returns the request body the provider got. */
    private suspend fun TestGraph.sent(chat: String, text: String): kotlinx.serialization.json.JsonObject {
        val before = conversations.history(chat).count { it.role == Role.ASSISTANT }
        runner.send(chat, text)
        conversations.observeMessages(chat).first { m -> m.count { it.role == Role.ASSISTANT } > before }
        val body = requests.last { it.url.host == "llm.test" }.body as OutgoingContent.ByteArrayContent
        return Json.parseToJsonElement(body.bytes().decodeToString()).jsonObject
    }

    @Test
    fun `a chat created on a model that can't use tools starts chat only, and others don't`() = runTest {
        val g = graph()
        assertFalse(g.conversations.create(provider).chatOnly)
        // A project or caller that picks the model at creation: the rule sees it.
        g.chatOnlyRule = ChatOnlyRule { _, model -> model == null }
        assertTrue(g.conversations.create(provider).chatOnly)
    }

    @Test
    fun `an empty chat's tools follow the model picked, and the first message fixes them`() = runTest {
        val g = graph()
        g.script(ScriptedProvider.text("Saturn, Jupiter and Uranus."), ScriptedProvider.text("Still just chatting."))
        val chat = g.conversations.create(provider)
        assertFalse(chat.chatOnly)

        // Picked before anything was sent: the chat follows it, both ways.
        g.conversations.setModel(chat.id, provider, TINY)
        assertTrue(g.conversations.get(chat.id)!!.chatOnly)
        g.conversations.setModel(chat.id, provider, null)
        assertFalse(g.conversations.get(chat.id)!!.chatOnly)
        g.conversations.setModel(chat.id, provider, TINY)

        // The request declares no tools at all, and the registry the chat runs with is empty.
        val first = g.sent(chat.id, "Name three planets with rings")
        assertFalse("tools" in first, first.toString())
        assertTrue(g.tools.forChat(g.conversations.get(chat.id)).specs.isEmpty())

        // Switching to a model that can use tools after the first message keeps the list as it was.
        g.conversations.setModel(chat.id, provider, null)
        assertTrue(g.conversations.get(chat.id)!!.chatOnly)
        val second = g.sent(chat.id, "And their moons?")
        assertFalse("tools" in second, second.toString())
    }

    @Test
    fun `a chat on a model that can use tools keeps declaring them after a switch to a small one`() = runTest {
        val g = graph()
        g.script(ScriptedProvider.text("ok"), ScriptedProvider.text("ok"))
        val chat = g.conversations.create(provider)
        assertTrue("tools" in g.sent(chat.id, "hi"))
        g.conversations.setModel(chat.id, provider, TINY)
        assertFalse(g.conversations.get(chat.id)!!.chatOnly)
        assertTrue("tools" in g.sent(chat.id, "again"))
    }

    @Test
    fun `the first message settles a chat whose model was learned to be chat only after it was opened`() = runTest {
        val g = graph()
        g.script(ScriptedProvider.text("ok"))
        g.chatOnlyRule = ChatOnlyRule { _, _ -> false }
        val chat = g.conversations.create(provider)
        // The model's list arrived since: the default turns out not to call tools.
        g.chatOnlyRule = ChatOnlyRule { _, _ -> true }
        assertFalse("tools" in g.sent(chat.id, "hi"))
        assertTrue(g.conversations.get(chat.id)!!.chatOnly)
    }

    @Test
    fun `a copy of a chat-only chat stays chat only`() = runTest {
        val g = graph()
        g.script(ScriptedProvider.text("ok"))
        val chat = g.conversations.create(provider)
        g.conversations.setModel(chat.id, provider, TINY)
        g.sent(chat.id, "hi")
        val last = g.conversations.history(chat.id).last()
        val copy = g.conversations.fork(chat.id, last.id, inclusive = true)!!
        assertTrue(copy.chatOnly)
    }

    private companion object {
        const val TINY = "tiny"
    }
}
