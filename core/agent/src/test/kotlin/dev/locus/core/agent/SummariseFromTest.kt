package dev.locus.core.agent

import dev.locus.core.llm.AnthropicChatModel
import dev.locus.core.llm.ChatModel
import dev.locus.core.llm.ChatRequest
import dev.locus.core.llm.OpenAiChatModel
import dev.locus.core.llm.OpenAiResponsesModel
import dev.locus.core.llm.wireBody
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.Role
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Summarise from here (docs/design/rewind.md): only the tail is replaced by the summary, the messages before the
 * point go out byte for byte as before with every adapter, and an edited summary is stored as a new checkpoint.
 */
class SummariseFromTest {
    private val hour = 60L * 60 * 1000

    /** An Anthropic turn as stored: its blocks, thinking and signature included, replayed verbatim. */
    private fun claude(id: String, text: String, signature: String, at: Long, vararg calls: Part.ToolCall): Message {
        val blocks = buildList {
            add("""{"type":"thinking","thinking":"Thinking about $id","signature":"$signature"}""")
            if (text.isNotEmpty()) add("""{"type":"text","text":"$text"}""")
            calls.forEach { add("""{"type":"tool_use","id":"${it.id}","name":"${it.name}","input":${it.argumentsJson}}""") }
        }
        val parts = listOfNotNull(Part.Text(text).takeIf { text.isNotEmpty() }) + calls
        return Message(id, "c", Role.ASSISTANT, parts, at, format = ApiFormat.ANTHROPIC_MESSAGES, rawPayload = blocks.joinToString(",", "[", "]"))
    }

    private fun user(id: String, text: String, at: Long) = Message(id, "c", Role.USER, listOf(Part.Text(text)), at)

    /** A chat: an early exchange worth keeping, then a long debugging stretch to summarise. */
    private val path = listOf(
        user("u1", "Design the payments API", 1_000),
        claude("a1", "Here is the design: POST /charges, idempotency keys.", "sig-head", 2_000),
        user("u2", "The test fails, debug it", 3_000),
        claude("a2", "", "sig-tail-1", 4_000, Part.ToolCall("call1", "run_command", """{"command":"npm test"}""")),
        Message("t2", "c", Role.TOOL, listOf(Part.ToolResult("call1", "run_command", "Exit code 1\n".padEnd(3_000, 'x'))), 5_000),
        claude("a3", "Fixed the off-by-one in retry.ts.", "sig-tail-2", 6_000),
    )

    private val http = HttpClient(MockEngine { respond("") })

    private val adapters: List<Pair<ChatModel, String>> = listOf(
        AnthropicChatModel(http, ProviderConfig("a", "Anthropic", ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.com", "claude-opus-5-5"), "k") to "messages",
        OpenAiResponsesModel(http, ProviderConfig("r", "R", ApiFormat.OPENAI_RESPONSES, "https://api.openai.com/v1", "gpt-5.5"), "k") to "input",
        OpenAiChatModel(http, ProviderConfig("o", "O", ApiFormat.OPENAI_CHAT, "https://api.openai.com/v1", "gpt-5.5"), "k") to "messages",
    )

    /** The request's items as JSON, without Anthropic's moving cache breakpoint (it marks each request's last block). */
    private fun items(model: ChatModel, key: String, messages: List<Message>): List<String> =
        (model.wireBody(ChatRequest("You are Locus.", messages, emptyList(), "chat-c"))!![key] as JsonArray).map { strip(it).toString() }

    private fun strip(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.filterKeys { it != "cache_control" }.mapValues { strip(it.value) })
        is JsonArray -> JsonArray(e.map(::strip))
        else -> e
    }

    private fun summarised(start: Int, summary: String = "## Done so far\nFound and fixed the retry off-by-one."): Message {
        val context = Compaction.modelContext(path)
        return Message("cp", "c", Role.USER, listOf(Compaction.checkpointFrom(summary, context, start)), 7_000)
    }

    @Test
    fun `only the tail is replaced, by the summary's view`() {
        val checkpoint = summarised(start = 2)
        val next = user("u3", "Now add refunds", 8_000)
        val context = Compaction.modelContext(path + checkpoint + next)
        assertEquals(listOf("u1", "a1", "cp", "u3"), context.map { it.id })
        val view = context[2].text
        assertTrue(view.startsWith(Compaction.PARTIAL_HEADER), view)
        assertTrue("Found and fixed the retry off-by-one." in view, view)
        // The user's own words from the summarised part stay verbatim; the kept part's aren't repeated.
        assertTrue("The test fails, debug it" in view && "Design the payments API" !in view, view)
        val stored = checkpoint.parts.filterIsInstance<Part.Checkpoint>().single()
        assertEquals("u2", stored.from)
        assertEquals(4, stored.replacedMessages)
    }

    @Test
    fun `the prefix before the point is byte-identical with every adapter, and no tail thinking is replayed`() {
        val before = Compaction.modelContext(path) // what the last request before the summary sent
        val after = Compaction.modelContext(path + summarised(start = 2) + user("u3", "Now add refunds", 8_000))
        for ((model, key) in adapters) {
            val a = items(model, key, before)
            val b = items(model, key, after)
            val kept = items(model, key, before.take(2))
            assertEquals(kept, a.take(kept.size), "${model.format}: the kept part as sent before")
            assertEquals(kept, b.take(kept.size), "${model.format}: the kept part as sent after the summary")
            assertTrue(b.drop(kept.size).joinToString().contains("Found and fixed the retry off-by-one."), "${model.format}: the view follows")
        }
        val anthropic = adapters.first().first.wireBody(ChatRequest("You are Locus.", after, emptyList(), "chat-c")).toString()
        // The kept turn's thinking is replayed with the exact prefix it was made with; the summarised turns' never are.
        assertTrue("sig-head" in anthropic)
        assertFalse("sig-tail-1" in anthropic || "sig-tail-2" in anthropic)
    }

    @Test
    fun `from a reply, the reply's turn stays and the summary starts after it`() {
        val context = Compaction.modelContext(path)
        assertEquals(Compaction.From(2), Compaction.from(context, "a1"))
        assertEquals(Compaction.From(2), Compaction.from(context, "u2"))
        assertEquals(Compaction.FromUnavailable.NOTHING_AFTER, Compaction.from(context, "a3").unavailable)
        assertEquals(Compaction.FromUnavailable.TOO_LITTLE, Compaction.from(context + user("u4", "one more", 9_000), "u4").unavailable)
        // Once summarised, a message isn't in the model's context any more.
        val summarisedContext = Compaction.modelContext(path + summarised(start = 2))
        assertEquals(Compaction.FromUnavailable.SUMMARISED, Compaction.from(summarisedContext, "u2").unavailable)
    }

    @Test
    fun `the summary request reads the cache and carries the scope and the user's instructions`() {
        val context = Compaction.modelContext(path)
        val prompt = Compaction.fromPrompt("Write a summary.", context, 2, "keep the API decisions, drop the debugging")
        val ask = Message("ask", "c", Role.USER, emptyList(), 7_000)
        val request = Compaction.request("You are Locus.", context, emptyList(), "chat-c", null, prompt, ask)
        assertEquals(context, request.messages.dropLast(1))
        val question = request.messages.last().text
        assertTrue(question.startsWith("Write a summary."), question)
        assertTrue("<part_start>\nThe test fails, debug it\n</part_start>" in question, question)
        assertTrue("<user_instructions>\nkeep the API decisions, drop the debugging\n</user_instructions>" in question, question)
        // From the start it's an ordinary summary: no scope, still the instructions.
        val whole = Compaction.fromPrompt("Write a summary.", context, 0, "short")
        assertFalse("<part_start>" in whole)
        assertTrue("<user_instructions>\nshort\n</user_instructions>" in whole)
        assertEquals(null, Compaction.checkpointFrom("x".repeat(50), context, 0).from)
    }

    @Test
    fun `an edited summary is a new checkpoint that replaces the old view, the old one stays stored`() {
        val first = summarised(start = 2)
        val edited = Message("cp2", "c", Role.USER, listOf(Compaction.edited(first, "## Done so far\nRetry fixed; keep idempotency keys.")), 7_500)
        val stored = path + first + edited
        val context = Compaction.modelContext(stored + user("u3", "Now add refunds", 8_000))
        assertEquals(listOf("u1", "a1", "cp2", "u3"), context.map { it.id })
        assertTrue("keep idempotency keys" in context[2].text && "Found and fixed" !in context[2].text)
        assertTrue(context[2].text.startsWith(Compaction.PARTIAL_HEADER))
        // Nothing stored was changed: the old checkpoint is still there, as written.
        assertEquals(first, stored[path.size])
        // A full checkpoint edited the same way stays a full one.
        val full = Message("f", "c", Role.USER, listOf(Compaction.checkpoint("Everything so far, summarised.", path)), 7_000)
        val fixed = Message("f2", "c", Role.USER, listOf(Compaction.edited(full, "Everything so far, corrected.")), 7_100)
        val fullContext = Compaction.modelContext(path + full + fixed)
        assertEquals(listOf("f2"), fullContext.map { it.id })
        assertTrue(fullContext.single().text.startsWith(Compaction.HEADER))
    }

    @Test
    fun `a server-side compaction in the tail is found`() {
        val compacted = Message(
            "s", "c", Role.ASSISTANT, listOf(Part.Text("ok")), 0, format = ApiFormat.ANTHROPIC_MESSAGES,
            rawPayload = """[{"type":"compaction","content":"earlier turns"},{"type":"text","text":"ok"}]""",
        )
        assertTrue(Compaction.serverCompactedIn(listOf(compacted)))
        assertFalse(Compaction.serverCompactedIn(path))
    }

    @Test
    fun `results cleared by a cold boundary in the summarised part stay cleared, so the kept part is sent as before`() {
        val policy = assertNotNull(ColdClearing.policyFor(ApiFormat.OPENAI_CHAT, enabled = true))
        val messages = mutableListOf(user("m0", "Read the logs", 0))
        var at = 0L
        repeat(12) { i ->
            at += 1_000
            messages += Message("c$i", "c", Role.ASSISTANT, listOf(Part.ToolCall("k$i", "read_file", "{}")), at)
            messages += Message("r$i", "c", Role.TOOL, listOf(Part.ToolResult("k$i", "read_file", "log $i ".padEnd(15_000, 'x'))), at)
        }
        messages += Message("d0", "c", Role.ASSISTANT, listOf(Part.Text("Done.")), at + 1_000)
        val keep = messages.size
        // A day later the tail starts; its first message is a cold boundary that clears the kept part's results.
        val later = at + 26 * hour
        messages += user("m1", "Back again", later)
        messages += Message("e0", "c", Role.ASSISTANT, listOf(Part.Text("Welcome back.")), later + 1_000)
        messages += user("m2", "Debug this", later + 2_000)
        messages += Message("e1", "c", Role.ASSISTANT, listOf(Part.Text("Found it.")), later + 3_000)
        val before = ColdClearing.view(Compaction.modelContext(messages), policy)
        assertTrue(before.take(keep).any { m -> m.toolResults.any { it.content.startsWith(ColdClearing.PREFIX) } }, "the boundary cleared the kept part")

        // Summarise from m2 (after the boundary) and from m1 (the boundary itself).
        for (from in listOf("m2", "m1")) {
            val context = Compaction.modelContext(messages)
            val start = context.indexOfFirst { it.id == from }
            val checkpoint = Message("cp", "c", Role.USER, listOf(Compaction.checkpointFrom("A summary of the later part.", context, start, clearing = policy)), later + 4_000)
            val after = ColdClearing.view(Compaction.modelContext(messages + checkpoint + user("m3", "Next", later + 5_000)), policy)
            assertEquals(before.take(start), after.take(start), "from $from: the kept part as last sent")
            val cc = OpenAiChatModel(http, ProviderConfig("o", "O", ApiFormat.OPENAI_CHAT, "https://api.openai.com/v1", "gpt-5.5"), "k")
            val a = items(cc, "messages", before)
            val b = items(cc, "messages", after)
            val kept = items(cc, "messages", before.take(start))
            assertEquals(a.take(kept.size), b.take(kept.size), "from $from: byte for byte")
        }
    }

    @Test
    fun `a checkpoint view resets the gap and is never a cold boundary itself`() {
        val replyAt = 1_000L
        val messages = listOf(
            user("u", "hi", 0),
            Message("a", "c", Role.ASSISTANT, listOf(Part.Text("hello")), replyAt),
            Message("cp", "c", Role.USER, listOf(Part.Text("summary"), Part.Checkpoint("summary", 1, from = "x")), replyAt + 30 * hour),
            user("n", "next", replyAt + 30 * hour + 1_000),
        )
        assertEquals(emptyList(), ColdClearing.boundaries(messages))
    }
}
