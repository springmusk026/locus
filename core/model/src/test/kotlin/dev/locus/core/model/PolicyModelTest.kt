package dev.locus.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The small rules in core:model that permissions, the reviewer and the composer rely on. */
class PolicyModelTest {
    @Test
    fun `only reads are allowed by default`() {
        assertEquals(ToolPolicy.ALLOW, ToolRisk.READ.defaultPolicy())
        listOf(ToolRisk.WRITE, ToolRisk.NETWORK, ToolRisk.EXECUTE).forEach { assertEquals(ToolPolicy.ASK, it.defaultPolicy()) }
    }

    @Test
    fun `tool groups cover each family and nothing else`() {
        val expected = mapOf(
            "web_search" to ToolGroup.WEB, "fetch_url" to ToolGroup.WEB, "weather" to ToolGroup.WEB,
            "browser_click" to ToolGroup.BROWSER,
            "run_command" to ToolGroup.LINUX, "linux_service" to ToolGroup.LINUX,
            "ssh_run" to ToolGroup.SSH, "sftp_get" to ToolGroup.SSH,
            "device_alarm" to ToolGroup.PHONE, "notify_me" to ToolGroup.PHONE,
            "email_send" to ToolGroup.EMAIL, "github_issue" to ToolGroup.GITHUB,
            "memory_save" to ToolGroup.MEMORY, "skill_read" to ToolGroup.MEMORY,
        )
        expected.forEach { (tool, group) -> assertEquals(group, ToolGroup.of(tool), tool) }
        // Files, plans and connectors aren't switched off as a group; connectors go by id.
        listOf("read_file", "write_file", "plan_propose", "todo_write", "connector_call", "mcp_call", "websearch", "sshrun").forEach {
            assertNull(ToolGroup.of(it), it)
        }
        assertEquals("group:web", ToolGroup.WEB.key)
        assertEquals("connector:github", ToolGroup.connectorKey("github"))
        assertEquals(ToolGroup.entries.size, ToolGroup.entries.map { it.key }.toSet().size)
    }

    @Test
    fun `reviewer records round-trip through the audit preview and never swallow a plain one`() {
        val audit = ReviewAudit(model = "haiku", verdict = "deny", reason = "deletes files the user didn't mention", latencyMs = 120, inputTokens = 900, outputTokens = 30)
        val (parsed, rest) = ReviewAudit.split(audit.prefix("Blocked\nsecond line"))
        assertEquals(audit, parsed)
        assertEquals("Blocked\nsecond line", rest)

        assertEquals(null to "plain preview", ReviewAudit.split("plain preview"))
        // A tool result that happens to start like a record but isn't one stays as it was.
        val forged = "[reviewer] not json\nrest"
        assertEquals(null to forged, ReviewAudit.split(forged))
        val (onlyRecord, empty) = ReviewAudit.split(ReviewAudit(fallback = "unavailable").prefix(""))
        assertEquals("unavailable", onlyRecord?.fallback)
        assertEquals("", empty)
    }

    @Test
    fun `notes Locus adds never count as what the user typed`() {
        val m = Message(
            "m", "c", Role.USER,
            listOf(Part.Text("tidy my notes"), Part.Text(ModeNote.wrap(ModeNote.PLAN_MODE)), Part.Text(ModeNote.result("delete everything"))),
            0,
        )
        assertEquals("tidy my notes", m.userText)
        assertTrue(m.text.contains("delete everything"))
        assertTrue(ModeNote.isNote(ModeNote.wrap("x")))
        assertTrue(ModeNote.isResult(ModeNote.result("x")))
        assertFalse(ModeNote.isNote("tidy <locus_mode>"))
    }

    @Test
    fun `kept reasoning is stripped before a message goes to a provider`() {
        val m = Message("m", "c", Role.ASSISTANT, listOf(Part.Reasoning("thinking", 10), Part.Text("answer")), 0)
        assertEquals(listOf<Part>(Part.Text("answer")), m.forModel().parts)
        val plain = Message("p", "c", Role.ASSISTANT, listOf(Part.Text("answer")), 0)
        assertTrue(plain.forModel() === plain)
    }

    @Test
    fun `providers need a key or a sign-in, never both`() {
        val chatGpt = ProviderConfig("c", "ChatGPT", ApiFormat.CHATGPT, "https://chatgpt.example", "", requiresKey = false)
        assertTrue(chatGpt.signsIn && chatGpt.needsCredential)
        val local = ProviderConfig("l", "Ollama", ApiFormat.OPENAI_CHAT, "http://10.0.0.2", "m", requiresKey = false)
        assertFalse(local.signsIn || local.needsCredential)
        val keyed = ProviderConfig("k", "Anthropic", ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.com", "m")
        assertTrue(keyed.needsCredential && !keyed.signsIn)
        // Every preset that runs off the phone except local servers and the ChatGPT sign-in needs a key.
        ProviderPresets.all.filter { it.format != ApiFormat.ON_DEVICE && it.format != ApiFormat.CHATGPT && !it.baseUrl.startsWith("http://") }
            .forEach { assertTrue(it.requiresKey, it.name) }
    }

    @Test
    fun `effort reads back from any provider's wire value`() {
        Effort.entries.forEach { e ->
            listOf(ApiFormat.OPENAI_RESPONSES, ApiFormat.CHATGPT).forEach { f -> assertEquals(e, Effort.fromWire(e.wireValue(f)), "$e $f") }
        }
        assertEquals(Effort.HIGH, Effort.fromWire(null))
        assertEquals(Effort.HIGH, Effort.fromWire("bogus"))
        assertEquals(Effort.OFF, Effort.fromWire("MINIMAL"))
        assertFalse(Effort.supports(ApiFormat.OPENAI_CHAT))
        assertFalse(Effort.supports(ApiFormat.ON_DEVICE))
    }

    @Test
    fun `todo statuses parse loosely and show the active form only while in progress`() {
        assertEquals(TodoStatus.IN_PROGRESS, TodoStatus.parse(" In_Progress "))
        assertNull(TodoStatus.parse("done"))
        assertEquals("Running tests", TodoItem("Run tests", TodoStatus.IN_PROGRESS, "Running tests").shown)
        assertEquals("Run tests", TodoItem("Run tests", TodoStatus.COMPLETED, "Running tests").shown)
        assertEquals("Run tests", TodoItem("Run tests", TodoStatus.IN_PROGRESS, " ").shown)
    }
}
