package dev.locus.core.agent

import dev.locus.core.llm.AssistantTurn
import dev.locus.core.llm.ChatEvent
import dev.locus.core.llm.ChatModel
import dev.locus.core.llm.ChatRequest
import dev.locus.core.llm.LlmException
import dev.locus.core.llm.LlmFailure
import dev.locus.core.llm.OpenAiChatModel
import dev.locus.core.llm.OpenAiResponsesModel
import dev.locus.core.llm.StopReason
import dev.locus.core.llm.wireBody
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.AuditEntry
import dev.locus.core.model.Conversation
import dev.locus.core.model.Message
import dev.locus.core.model.ModeNote
import dev.locus.core.model.Part
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.Role
import dev.locus.core.model.ToolPolicy
import dev.locus.core.model.ToolRisk
import dev.locus.core.model.ToolSpec
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Clearing old tool results from what's sent, only past a cold-cache gap (docs/design/cold-cache-clearing.md). */
class ColdClearingTest {
    private val hour = 60L * 60 * 1000
    private val policy = ColdClearing.policyFor(ApiFormat.OPENAI_CHAT, enabled = true)

    /** Builds a chat: each [step] is an assistant call and its result, a second apart. */
    private class Chat(start: Long = 0) {
        val messages = mutableListOf<Message>()
        var now = start
        private var n = 0

        fun user(text: String, at: Long = now + 1_000) = add(Message("m${n++}", "c", Role.USER, listOf(Part.Text(text)), at))

        fun step(content: String, isError: Boolean = false, images: Int = 0, name: String = "read_file") {
            val call = "call${n}"
            add(Message("m${n++}", "c", Role.ASSISTANT, listOf(Part.ToolCall(call, name, "{}")), now + 1_000))
            val pics = List(images) { Part.Attachment("shot$it.png", "image/png", "/tool-images/$it.png", "shots/shot$it.png", 10) }
            add(Message("m${n++}", "c", Role.TOOL, listOf(Part.ToolResult(call, name, content, isError, images = pics)), now + 1_000))
        }

        fun reply(text: String) = add(Message("m${n++}", "c", Role.ASSISTANT, listOf(Part.Text(text)), now + 1_000))

        private fun add(m: Message) {
            messages += m
            now = m.createdAt
        }
    }

    private fun big(i: Int, chars: Int = 15_000) = "result $i ".padEnd(chars, 'x')

    private fun results(messages: List<Message>) = messages.flatMap { it.toolResults }

    @Test
    fun `no cold boundary sends the transcript as it is`() {
        val chat = Chat()
        chat.user("Read the logs")
        repeat(12) { chat.step(big(it)) }
        chat.reply("Done.")
        chat.user("And again?", at = chat.now + 23 * hour)
        assertSame(chat.messages, ColdClearing.view(chat.messages, policy))
        assertTrue(ColdClearing.boundaries(chat.messages).isEmpty())
        assertNull(ColdClearing.clearedIn(chat.messages))
    }

    @Test
    fun `after a gap of more than a day old long results are cleared, the last five, errors and short ones stay`() {
        val chat = Chat()
        chat.user("Read the logs")
        chat.step(big(0, 48_210))
        chat.step("ERROR: ".padEnd(5_000, 'e'), isError = true)
        chat.step("short")
        repeat(4) { chat.step(big(it + 1)) }
        chat.step(big(5), images = 1)
        repeat(5) { chat.step(big(it + 10)) }
        chat.reply("Done.")
        chat.user("Back after the weekend: anything new?", at = chat.now + 25 * hour)
        val sent = ColdClearing.view(chat.messages, policy)

        assertEquals(chat.messages.size, sent.size)
        assertEquals(chat.messages.map { it.id }, sent.map { it.id })
        val before = results(chat.messages)
        val after = results(sent)
        assertEquals(before.map { it.callId }, after.map { it.callId })
        assertEquals(before.map { it.toolName }, after.map { it.toolName })
        assertEquals(before.map { it.isError }, after.map { it.isError })
        // The first, the four after the short one and the one with an image are cleared.
        assertEquals("[Earlier result cleared: 48,210 characters. Run the tool again if you need it.]", after[0].content)
        assertEquals(before[1], after[1], "errors stay")
        assertEquals(before[2], after[2], "short results stay")
        (3..6).forEach { assertTrue(after[it].content.startsWith(ColdClearing.PREFIX), "result $it") }
        assertEquals("[Earlier result cleared: 15,000 characters and 1 image. Run the tool again if you need it.]", after[7].content)
        assertTrue(after[7].images.isEmpty(), "a cleared result sends no images")
        assertEquals(before.takeLast(5), after.takeLast(5), "the last five stay")
        // The newest message, after the boundary, is untouched.
        assertSame(chat.messages.last(), sent.last())

        val cleared = assertNotNull(ColdClearing.clearedIn(sent))
        assertEquals(6, cleared.count)
        val saved = before.take(8).filterIndexed { i, _ -> i !in 1..2 }.sumOf { it.content.length + it.images.size * ColdClearing.IMAGE_CHARS } -
            after.take(8).filterIndexed { i, _ -> i !in 1..2 }.sumOf { it.content.length }
        assertEquals(saved / 4, cleared.tokens)
    }

    @Test
    fun `a boundary that would clear less than 20k tokens clears nothing`() {
        val chat = Chat()
        chat.user("Read the logs")
        repeat(3) { chat.step(big(it, 20_000)) }
        repeat(5) { chat.step(big(it + 10)) }
        chat.reply("Done.")
        chat.user("Back", at = chat.now + 30 * hour)
        assertEquals(listOf(chat.messages.lastIndex), ColdClearing.boundaries(chat.messages))
        // 3 × 20,000 characters is about 15k tokens: not worth a rewrite.
        assertSame(chat.messages, ColdClearing.view(chat.messages, policy))
    }

    @Test
    fun `the task in progress at a boundary keeps its results`() {
        val chat = Chat()
        chat.user("Read the logs")
        repeat(12) { chat.step(big(it)) }
        // The last call waited a day for approval: its result comes after the gap, mid-task.
        chat.messages += Message("late", "c", Role.TOOL, listOf(Part.ToolResult("x", "read_file", big(99))), chat.now + 25 * hour)
        assertEquals(listOf(chat.messages.lastIndex), ColdClearing.boundaries(chat.messages))
        assertSame(chat.messages, ColdClearing.view(chat.messages, policy), "every result belongs to the task still running")

        // A mid-task note doesn't start a task either.
        val noted = chat.messages.dropLast(1) + Message("note", "c", Role.USER, listOf(Part.Text(ModeNote.wrap(ModeNote.MID_TASK))), chat.now + 25 * hour)
        assertSame(noted, ColdClearing.view(noted, policy))
    }

    @Test
    fun `the view is byte stable as the transcript grows after a boundary, and after a restart`() {
        val chat = Chat()
        chat.user("Read the logs")
        repeat(12) { chat.step(big(it)) }
        chat.reply("Done.")
        chat.user("Back", at = chat.now + 25 * hour)
        val http = HttpClient(MockEngine { respond("") })
        val adapters = listOf(
            OpenAiChatModel(http, ProviderConfig("p", "O", ApiFormat.OPENAI_CHAT, "https://api.openai.com/v1", "gpt-5.5"), "k") to "messages",
            OpenAiResponsesModel(http, ProviderConfig("p", "R", ApiFormat.OPENAI_RESPONSES, "https://api.openai.com/v1", "gpt-5.5"), "k") to "input",
        )
        val first = chat.messages.toList()
        repeat(6) { chat.step(big(100 + it)) }
        chat.reply("Nothing new.")
        val grown = chat.messages.toList()
        val firstView = ColdClearing.view(first, policy)
        val grownView = ColdClearing.view(grown, policy)
        assertEquals(firstView, grownView.take(firstView.size))
        // Rebuilt from storage after a restart: equal copies give the same view.
        assertEquals(grownView, ColdClearing.view(grown.map { it.copy() }, policy))
        for ((model, key) in adapters) {
            fun items(view: List<Message>) = (model.wireBody(ChatRequest("system", view, emptyList(), "chat-c"))!![key] as JsonArray).map { it.toString() }
            val a = items(firstView)
            val b = items(grownView)
            assertEquals(a, b.take(a.size), "${model.format}: the earlier request is a prefix of the later one")
            assertTrue(a.any { ColdClearing.PREFIX.trimEnd() in it }, "${model.format} sends the note")
        }
    }

    @Test
    fun `never on Anthropic, and never with the setting off`() {
        assertNull(ColdClearing.policyFor(ApiFormat.ANTHROPIC_MESSAGES, enabled = true))
        assertNull(ColdClearing.policyFor(ApiFormat.OPENAI_RESPONSES, enabled = false))
        assertNull(ColdClearing.policyFor(null, enabled = true))
        listOf(ApiFormat.OPENAI_CHAT, ApiFormat.OPENAI_RESPONSES, ApiFormat.CHATGPT, ApiFormat.ON_DEVICE).forEach {
            assertEquals(ColdClearing.COLD_AFTER_MS, assertNotNull(ColdClearing.policyFor(it, enabled = true)).coldAfterMs)
        }
        val chat = Chat()
        chat.user("Read the logs")
        repeat(12) { chat.step(big(it)) }
        chat.reply("Done.")
        chat.user("Back", at = chat.now + 48 * hour)
        assertSame(chat.messages, ColdClearing.view(chat.messages, ColdClearing.policyFor(ApiFormat.ANTHROPIC_MESSAGES, enabled = true)))
    }

    @Test
    fun `boundaries clear cumulatively, oldest first`() {
        val chat = Chat()
        chat.user("Read the logs")
        repeat(8) { chat.step(big(it)) }
        chat.reply("Done.")
        chat.user("Back", at = chat.now + 25 * hour)
        repeat(8) { chat.step(big(20 + it)) }
        chat.reply("Done again.")
        val firstEnd = chat.messages.size
        chat.user("Back again", at = chat.now + 26 * hour)
        assertEquals(2, ColdClearing.boundaries(chat.messages).size)

        val sent = results(ColdClearing.view(chat.messages, policy))
        val cleared = sent.map { it.content.startsWith(ColdClearing.PREFIX) }
        // First boundary: 3 of the first 8 could go (45k chars, about 11k tokens): under the minimum, so none.
        // Second boundary: everything but its last five: the first 8 and 3 of the next 8 (165k chars).
        assertEquals(List(11) { true } + List(5) { false }, cleared)

        // Only up to the first boundary's chat: its own view, which a request back then sent.
        val early = chat.messages.take(firstEnd)
        assertSame(early, ColdClearing.view(early, policy))
    }

    @Test
    fun `earlier clearing carries over to a later boundary`() {
        val chat = Chat()
        chat.user("Read the logs")
        repeat(15) { chat.step(big(it)) }
        chat.reply("Done.")
        chat.user("Back", at = chat.now + 25 * hour)
        val firstView = ColdClearing.view(chat.messages.toList(), policy)
        assertEquals(List(10) { true } + List(5) { false }, results(firstView).map { it.content.startsWith(ColdClearing.PREFIX) })
        repeat(6) { chat.step(big(30 + it)) }
        chat.reply("Done again.")
        chat.user("Back again", at = chat.now + 25 * hour)
        val later = results(ColdClearing.view(chat.messages, policy)).map { it.content.startsWith(ColdClearing.PREFIX) }
        // The second boundary newly clears the first view's last five and one more (90k chars): over the minimum.
        assertEquals(List(16) { true } + List(5) { false }, later)
        // The first view's notes are unchanged by the second boundary.
        assertEquals(results(firstView).take(10), results(ColdClearing.view(chat.messages, policy)).take(10))
    }

    @Test
    fun `the note's numbers ignore the phone's locale`() {
        val saved = Locale.getDefault()
        try {
            for (locale in listOf(Locale.GERMANY, Locale.FRANCE, Locale.forLanguageTag("ar-EG"), Locale.forLanguageTag("hi-IN-u-nu-deva"))) {
                Locale.setDefault(locale)
                assertEquals("[Earlier result cleared: 1,234,567 characters and 2 images. Run the tool again if you need it.]", ColdClearing.note(1_234_567, 2))
                assertEquals("2,000", ColdClearing.grouped(2_000))
                assertEquals("999", ColdClearing.grouped(999))
            }
        } finally {
            Locale.setDefault(saved)
        }
    }

    // The agent loop: what it sends, after a fallback too, and that the store keeps every result.

    private class Model(override val format: ApiFormat, vararg failures: LlmException) : ChatModel {
        val script = ArrayDeque(failures.toList())
        val requests = mutableListOf<ChatRequest>()
        override fun stream(request: ChatRequest): Flow<ChatEvent> = flow {
            requests += request
            script.removeFirstOrNull()?.let { throw it }
            emit(ChatEvent.Completed(AssistantTurn("Nothing new.", emptyList(), StopReason.END_TURN, format, null)))
        }
    }

    private class Store : TranscriptStore {
        val messages = mutableListOf<Message>()
        override suspend fun append(message: Message) { messages += message }
        override suspend fun record(entry: AuditEntry) = Unit
    }

    private class Noop : Tool {
        override val risk = ToolRisk.READ
        override val spec = ToolSpec("read_file", "test", ObjectSchema().string("path", "p").build())
        override fun describe(args: JsonObject) = "read"
        override suspend fun execute(args: JsonObject) = ToolResult("ok")
    }

    private fun coldChat(): List<Message> {
        val chat = Chat()
        chat.user("Read the logs")
        repeat(12) { chat.step(big(it)) }
        chat.reply("Done.")
        chat.user("Back", at = chat.now + 25 * hour)
        return chat.messages.toList()
    }

    private fun run(model: ChatModel, history: List<Message>, enabled: Boolean = true, backup: ChatModel? = null, store: Store = Store()) = runTest {
        var n = 0
        AgentLoop(
            model, ToolRegistry(listOf(Noop())), { ToolPolicy.ALLOW }, ApprovalGate { true }, store,
            AgentConfig(
                coldClearing = enabled,
                fallbacks = listOfNotNull(backup?.let { b -> FallbackModel("b", "backup", "Backup") { b } }),
                compaction = CompactionSetup(null, prompt = "Summarise."),
            ),
            ids = { "id${n++}" },
        ).run(Conversation("c", "t", "p", "system", 0, 0), history).toList()
    }

    @Test
    fun `the loop sends the cleared view and stores every result in full`() {
        val history = coldChat()
        val model = Model(ApiFormat.OPENAI_RESPONSES)
        val store = Store()
        run(model, history, store = store)
        val sent = model.requests.single().messages
        assertEquals(7, ColdClearing.clearedIn(sent)?.count)
        assertTrue(store.messages.flatMap { it.toolResults }.none { it.content.startsWith(ColdClearing.PREFIX) })
        assertTrue(history.flatMap { it.toolResults }.none { it.content.startsWith(ColdClearing.PREFIX) })

        val off = Model(ApiFormat.OPENAI_RESPONSES)
        run(off, history, enabled = false)
        assertNull(ColdClearing.clearedIn(off.requests.single().messages))

        val claude = Model(ApiFormat.ANTHROPIC_MESSAGES)
        run(claude, history)
        assertEquals(history, claude.requests.single().messages)
    }

    @Test
    fun `after a fallback the backup's format decides`() {
        val history = coldChat()
        val quota = LlmException("out of credit", 402, kind = LlmFailure.QUOTA)
        val primary = Model(ApiFormat.OPENAI_CHAT, quota)
        val backup = Model(ApiFormat.ANTHROPIC_MESSAGES)
        run(primary, history, backup = backup)
        assertNotNull(ColdClearing.clearedIn(primary.requests.single().messages))
        assertEquals(history, backup.requests.single().messages, "Anthropic gets every result as stored")

        val toOpenAi = Model(ApiFormat.OPENAI_CHAT)
        run(Model(ApiFormat.ANTHROPIC_MESSAGES, quota), history, backup = toOpenAi)
        assertEquals(7, ColdClearing.clearedIn(toOpenAi.requests.single().messages)?.count)
    }

    @Test
    fun `the summary request sends the same view as the next turn`() {
        val history = coldChat()
        val clearing = ColdClearing.policyFor(ApiFormat.OPENAI_CHAT, enabled = true)
        val ask = Message("ask", "c", Role.USER, emptyList(), history.last().createdAt + 1)
        val summary = Compaction.request("system", history, emptyList(), "k", null, "Summarise.", ask, clearing)
        val turn = ColdClearing.view(history, clearing)
        assertEquals(turn, summary.messages.dropLast(1))
        assertEquals("Summarise.", summary.messages.last().text)
        assertFalse(Compaction.request("system", history, emptyList(), "k", null, "Summarise.", ask).messages.dropLast(1).any { m ->
            m.toolResults.any { it.content.startsWith(ColdClearing.PREFIX) }
        })
    }

    @Test
    fun `after a checkpoint only what follows it is considered`() {
        val chat = Chat()
        chat.user("Read the logs")
        repeat(12) { chat.step(big(it)) }
        chat.reply("Done.")
        val checkpoint = Message("cp", "c", Role.USER, listOf(Part.Checkpoint("Read twelve logs.", 26)), chat.now + 1_000)
        val all = chat.messages + checkpoint + Message("u2", "c", Role.USER, listOf(Part.Text("Back")), checkpoint.createdAt + 25 * hour)
        val context = Compaction.modelContext(all)
        // The checkpoint's view is a user message: no reply before the gap, so no boundary and nothing to clear.
        assertTrue(ColdClearing.boundaries(context).isEmpty())
        assertSame(context, ColdClearing.view(context, policy))
    }
}
