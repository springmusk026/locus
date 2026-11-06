package dev.locus.core.agent

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import com.icegreen.greenmail.util.ServerSetupTest
import dev.locus.core.agent.home.SecretResolver
import dev.locus.core.agent.integrations.emailTools
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The agent can learn its own mailbox's address (so "sign up with the integrated email" isn't impossible),
 * from a tool result, never the frozen system prompt; and the mailbox's password never reaches the model.
 */
class MailboxAddressTest {
    private val green = GreenMail(arrayOf(ServerSetupTest.SMTP, ServerSetupTest.IMAP).map { it.dynamicPort() }.toTypedArray<ServerSetup>()).apply { start() }

    init {
        green.setUser("agent@locus.test", "agent@locus.test", "s3cret pw")
    }

    @AfterTest
    fun stop() = green.stop()

    private val own = "This mailbox's own address (the agent's, not the user's): agent@locus.test"

    private fun config(imap: Boolean = true) =
        "smtp+insecure://agent@locus.test:s3cret%20pw@127.0.0.1:${green.smtp.port}" +
            (if (imap) " imap+insecure://127.0.0.1:${green.imap.port}" else "") + " Locus Agent"

    private fun tools(value: String = config()) = emailTools(SecretResolver { if (it == "integration-email") value else null })

    private fun leaks(text: String) = "s3cret" in text || "s3cret%20pw" in text

    private class Recording(turns: List<AssistantTurn>) : ChatModel {
        private val queue = ArrayDeque(turns)
        val requests = mutableListOf<ChatRequest>()
        override val format = ApiFormat.OPENAI_CHAT
        override fun stream(request: ChatRequest): Flow<ChatEvent> = flow {
            requests += request
            emit(ChatEvent.Completed(queue.removeFirst()))
        }
    }

    private class Store : TranscriptStore {
        val messages = mutableListOf<Message>()
        val audit = mutableListOf<AuditEntry>()
        override suspend fun append(message: Message) { messages += message }
        override suspend fun record(entry: AuditEntry) { audit += entry }
    }

    private fun turn(name: String, args: String = "{}") =
        AssistantTurn("", listOf(Part.ToolCall("k-$name", name, args)), StopReason.TOOL_USE, ApiFormat.OPENAI_CHAT, null)

    private val done = AssistantTurn("done", emptyList(), StopReason.END_TURN, ApiFormat.OPENAI_CHAT, null)

    @Test
    fun `the agent loop hands the model the address, never the password`() = runBlocking {
        val model = Recording(listOf(turn("email_list"), done))
        val store = Store()
        var n = 0
        val system = "You are Locus."
        AgentLoop(model, ToolRegistry(tools()), { it.defaultPolicy }, { true }, store, AgentConfig(), ids = { "id${n++}" })
            .run(Conversation("c", "t", "p", system, 0, 0), listOf(Message("u", "c", Role.USER, listOf(Part.Text("Sign up for the newsletter with the integrated email")), 0)))
            .toList()

        val result = store.messages.flatMap { it.toolResults }.single { it.toolName == "email_list" }
        assertFalse(result.isError, result.content)
        assertTrue(result.content.startsWith(own), result.content)
        // The second request carries the result to the model.
        val sent = model.requests.last().messages.flatMap { it.toolResults }.single()
        assertTrue(own in sent.content)
        // Nothing the model was sent holds the password: messages, tool specs, system prompt.
        model.requests.forEach { r -> assertFalse(leaks(r.toString()), "password reached the model") }
        assertFalse(store.audit.any { leaks(it.toString()) })
        // Prompt cache: the system prompt is the chat's own and the tool list is the same on every request.
        assertTrue(model.requests.all { it.system == system })
        assertEquals(1, model.requests.map { it.tools }.distinct().size)
        assertTrue(model.requests.first().tools.single { it.name == "email_list" }.description.contains("own address"))
    }

    @Test
    fun `the address sits outside the untrusted mail text, in every listing`() = runBlocking {
        val list = tools().single { it.spec.name == "email_list" }
        val empty = list.execute(JsonObject(emptyMap()))
        assertTrue(empty.content.startsWith(own), empty.content)
        val folders = list.execute(JsonObject(mapOf("folder" to kotlinx.serialization.json.JsonPrimitive("?"))))
        assertTrue(folders.content.startsWith(own))
        // Ahead of the wrapper, so mail can't forge or change it.
        assertTrue(folders.content.indexOf(own) < folders.content.indexOf("<untrusted"), folders.content)
        listOf(empty, folders).forEach { assertFalse(leaks(it.content)) }
    }

    @Test
    fun `a send-only mailbox still says its address, without the password`() = runBlocking {
        val list = tools(config(imap = false)).single { it.spec.name == "email_list" }
        val e = assertFailsWith<ToolException> { list.execute(JsonObject(emptyMap())) }
        assertTrue(own in e.message.orEmpty(), e.message)
        assertFalse(leaks(e.message.orEmpty()))
    }
}
