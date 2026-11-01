package dev.locus.core.agent

import dev.locus.core.agent.tools.Workspace
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Long results: start and end inline, all of it in a file, and marked outside text still marked. */
class LongOutputTest {
    private val open = UntrustedContent.OPEN
    private val close = UntrustedContent.CLOSE

    /** Markers alternate open, close, open, … and every open is closed. */
    private fun assertBalanced(text: String) {
        val markers = Regex("${Regex.escape(open)}|${Regex.escape(close)}").findAll(text).map { it.value }.toList()
        markers.forEachIndexed { i, m -> assertEquals(if (i % 2 == 0) open else close, m, "marker $i in:\n$text") }
        assertEquals(0, markers.size % 2, "unbalanced:\n$text")
        // No marker cut in two.
        assertEquals(markers.size, Regex("<\\s*/?\\s*untrusted_web").findAll(text).count(), text)
    }

    /** The note is Locus's own text: never inside a marked block. */
    private fun assertNoteOutside(text: String) {
        val at = text.indexOf("characters cut")
        assertTrue(at >= 0, text)
        val before = text.substring(0, at)
        assertTrue(before.lastIndexOf(open) <= before.lastIndexOf(close), "note inside a block:\n$text")
    }

    private fun lines(prefix: String, n: Int) = (1..n).joinToString("\n") { "$prefix line $it" }

    @Test
    fun `when it fits it's unchanged, or names the file the tool saved itself`() {
        assertEquals("short", LongOutput.shorten("short", 100, null))
        val noted = LongOutput.shorten("short", 100, ".locus/out/x.log")
        assertTrue(noted.startsWith("short\n") && "Full output: .locus/out/x.log" in noted)
    }

    @Test
    fun `head and tail are kept around a note naming the file`() {
        val text = lines("plain", 5_000)
        val out = LongOutput.shorten(text, 2_000, ".locus/out/f.log")
        assertTrue(out.length < 2_000 + 300, "${out.length}")
        assertTrue(out.startsWith("plain line 1\n") && out.endsWith("plain line 5000"))
        assertTrue("Full output: .locus/out/f.log" in out && "read_file" in out && "search_files" in out)
        // Cut on line breaks.
        assertTrue(out.lines().all { it.startsWith("plain line ") || it.startsWith("[…") }, out)
    }

    @Test
    fun `a cut through one marked block closes it before the note and reopens it after`() {
        val text = "Exit code 0 (1.0 s).\n" + UntrustedContent.wrap(lines("out", 5_000), UntrustedContent.note("command output"))
        val out = LongOutput.shorten(text, 3_000, ".locus/out/c.log")
        assertBalanced(out)
        assertNoteOutside(out)
        assertTrue(out.startsWith("Exit code 0"))
        assertTrue(out.endsWith(UntrustedContent.note("command output")))
        assertEquals(2, Regex(Regex.escape(open)).findAll(out).count())
    }

    @Test
    fun `any cut through many marked blocks stays balanced and never splits a marker`() {
        val text = (1..40).joinToString("\n") { i -> "Result $i:\n" + UntrustedContent.wrap(lines("r$i", 30 + i), UntrustedContent.note("result $i")) }
        // Every limit from tiny to nearly all of it, so cuts land everywhere, including inside markers.
        for (limit in (50..text.length - 1 step 37)) {
            val out = LongOutput.shorten(text, limit, "p")
            assertBalanced(out)
            assertNoteOutside(out)
        }
    }

    @Test
    fun `cuts never split a marker even without line breaks`() {
        val text = "x".repeat(997) + UntrustedContent.wrap("y".repeat(3_000), "(n)") + "z".repeat(1_000)
        // The head ends inside the opening marker for limits near 1,500, the tail starts inside the closing one near 3,100.
        for (limit in 1_400..3_200) {
            val out = LongOutput.shorten(text, limit, "p")
            assertBalanced(out)
        }
    }

    // ---- In the agent loop ---------------------------------------------------------------------

    private val conversation = Conversation("c", "t", "p", "system", 0, 0)
    private val user = Message("u", "c", Role.USER, listOf(Part.Text("go")), 0)

    private class Scripted(vararg turns: AssistantTurn) : ChatModel {
        private val queue = ArrayDeque(turns.toList())
        override val format = ApiFormat.OPENAI_CHAT
        override fun stream(request: ChatRequest): Flow<ChatEvent> = flow { emit(ChatEvent.Completed(queue.removeFirst())) }
    }

    private class Store : TranscriptStore {
        val messages = mutableListOf<Message>()
        override suspend fun append(message: Message) { messages += message }
        override suspend fun record(entry: AuditEntry) {}
    }

    private class Long(name: String, private val result: ToolResult, override val pagesOwnOutput: Boolean = false) : Tool {
        override val risk = ToolRisk.READ
        override val spec = ToolSpec(name, "t", ObjectSchema().build())
        override fun describe(args: JsonObject) = "t"
        override suspend fun execute(args: JsonObject) = result
    }

    private class Spill : OutputSpill {
        val saved = mutableListOf<Pair<String, String>>()
        override suspend fun save(toolName: String, text: String): String = ".locus/out/$toolName.log".also { saved += toolName to text }
    }

    private suspend fun runWith(tool: Tool, spill: OutputSpill?, inline: Int = 1_000): Part.ToolResult {
        val store = Store()
        val turn = AssistantTurn("", listOf(Part.ToolCall("k", tool.spec.name, "{}")), StopReason.TOOL_USE, ApiFormat.OPENAI_CHAT, null)
        val done = AssistantTurn("ok", emptyList(), StopReason.END_TURN, ApiFormat.OPENAI_CHAT, null)
        var n = 0
        AgentLoop(
            Scripted(turn, done), ToolRegistry(listOf(tool)), { ToolPolicy.ALLOW }, { true }, store,
            AgentConfig(spill = spill, inlineResultChars = inline, maxResultChars = 20_000), ids = { "id${n++}" },
        ).run(conversation, listOf(user)).toList()
        return store.messages.single { it.role == Role.TOOL }.toolResults.single()
    }

    @Test
    fun `every tool's long result is saved whole and shortened with its marking intact`() = runTest {
        val full = "Page:\n" + UntrustedContent.wrap(lines("page", 2_000), UntrustedContent.note("a web page"))
        val spill = Spill()
        val result = runWith(Long("web_read", ToolResult(full)), spill)
        assertEquals(listOf("web_read" to full), spill.saved)
        assertTrue(result.content.length < 1_300, "${result.content.length}")
        assertTrue("Full output: .locus/out/web_read.log" in result.content)
        assertBalanced(result.content)
        assertNoteOutside(result.content)
    }

    @Test
    fun `short results, tools that page themselves, and files a tool saved itself aren't saved again`() = runTest {
        val spill = Spill()
        assertEquals("short", runWith(Long("web_read", ToolResult("short")), spill).content)

        // Past the inline size but under the cap: kept whole.
        val paged = runWith(Long("read_file", ToolResult(lines("file", 1_000)), pagesOwnOutput = true), spill)
        assertTrue(paged.content.length > 1_000 && paged.content.startsWith("file line 1\n") && "file line 1000" in paged.content)
        assertFalse("characters cut" in paged.content)

        val command = runWith(Long("run_command", ToolResult(lines("cmd", 2_000), savedTo = ".locus/out/own.log")), spill)
        assertTrue("Full output: .locus/out/own.log" in command.content)
        assertTrue(spill.saved.isEmpty())
    }

    @Test
    fun `without a spill a long result is only cut, at the larger cap`() = runTest {
        val result = runWith(Long("web_read", ToolResult(lines("page", 5_000))), spill = null)
        assertTrue(result.content.length in 19_000..20_400, "${result.content.length}")
        assertTrue("wasn't saved" in result.content)
    }

    @Test
    fun `the workspace spill scrubs secrets, keeps the folder out of git and prunes old files`() = runTest {
        val base = Files.createTempDirectory("spill").toFile()
        try {
            val ws = Workspace(File(base, "workspace"), File(base, "trash"))
            val spill = WorkspaceOutputSpill(ws) { SecretScrubber(listOf("hunter2-very-secret")) }
            val path = spill.save("mcp_call", "token hunter2-very-secret and Authorization: Bearer abcdefghijklmnop")!!
            assertTrue(path.startsWith(".locus/out/") && path.endsWith("-mcp_call.log"), path)
            val saved = ws.resolve(path).readText()
            assertFalse("hunter2" in saved || "abcdefghijklmnop" in saved, saved)
            assertEquals("*\n", File(ws.root, ".locus/.gitignore").readText())

            val dir = ws.resolve(SavedOutput.DIR)
            (1..40).forEach { File(dir, "20200101-0000${"%02d".format(it)}-abc-old.log").writeText("$it") }
            spill.save("web_read", "x")
            assertEquals(SavedOutput.KEEP, dir.listFiles()!!.size)
            assertNull(dir.listFiles()!!.firstOrNull { it.name == "20200101-000001-abc-old.log" })
        } finally {
            base.deleteRecursively()
        }
    }
}
