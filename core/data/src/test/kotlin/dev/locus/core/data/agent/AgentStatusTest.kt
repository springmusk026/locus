package dev.locus.core.data.agent

import dev.locus.core.agent.ApprovalOrigin
import dev.locus.core.agent.ApprovalRequest
import dev.locus.core.model.ToolRisk
import dev.locus.core.agent.tools.UserQuestion
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AgentStatusTest {

    private fun approval(chat: String = "a", tool: String = "shell", summary: String = "Run ls", origin: ApprovalOrigin? = null) =
        ApprovalRequest(chat, "call-$tool", tool, ToolRisk.EXECUTE, summary, "{}", origin = origin)

    private fun question(chat: String = "a", options: List<String> = emptyList(), multi: Boolean = false, allowOther: Boolean = true) =
        UserQuestion(chat, "q-$chat", "Which branch?", options, multi, allowOther)

    @Test
    fun `a user run starting says Working`() {
        val status = agentStatus(mapOf("a" to RunState(running = true)), emptyList())
        assertEquals("Working…", status.text)
        assertEquals("Working", status.chip)
        assertTrue(status.promotable)
        assertNull(status.detail)
    }

    @Test
    fun `the chip counts steps from the second one`() {
        assertEquals("Working", agentStatus(mapOf("a" to RunState(running = true, step = 1)), emptyList()).chip)
        assertEquals("Step 2", agentStatus(mapOf("a" to RunState(running = true, step = 2)), emptyList()).chip)
        assertEquals("Step 99", agentStatus(mapOf("a" to RunState(running = true, step = 99)), emptyList()).chip)
        // "Step 100" would be cut off in the chip.
        assertEquals("Working", agentStatus(mapOf("a" to RunState(running = true, step = 100)), emptyList()).chip)
    }

    @Test
    fun `chips always fit`() {
        listOf(0, 1, 5, 42, 99, 100, 1000).forEach { step ->
            val chip = agentStatus(mapOf("a" to RunState(running = true, step = step)), emptyList()).chip
            assertTrue(chip.length <= AgentStatus.CHIP_MAX, chip)
        }
    }

    @Test
    fun `a tool in use and helpers are in the text`() {
        assertEquals("Using browser", agentStatus(mapOf("a" to RunState(running = true, activeTool = "browser")), emptyList()).text)
        assertEquals("Working · 2 helpers", agentStatus(mapOf("a" to RunState(running = true, helpers = 2)), emptyList()).text)
        assertEquals("Using shell · 1 helper", agentStatus(mapOf("a" to RunState(running = true, activeTool = "shell", helpers = 1)), emptyList()).text)
    }

    @Test
    fun `a waiting approval asks, with its title and summary`() {
        val request = approval()
        val status = agentStatus(mapOf("a" to RunState(running = true, step = 4, approvals = listOf(request))), emptyList())
        assertEquals("Waiting for your approval", status.text)
        assertEquals("Asks", status.chip)
        assertSame(request, status.approval)
        assertNull(status.question)
        assertEquals("Allow shell?\nRun ls", status.detail)
    }

    @Test
    fun `a helper's approval names the helper`() {
        val request = approval(origin = ApprovalOrigin("h1", "Researcher #2"))
        val status = agentStatus(mapOf("a" to RunState(helpers = 1, approvals = listOf(request))), emptyList())
        assertEquals("Waiting for your approval · 1 helper", status.text)
        assertEquals("Researcher #2 wants to use shell\nRun ls", status.detail)
        assertTrue(status.promotable)
    }

    @Test
    fun `a waiting question asks, and an approval comes first`() {
        val q = question(options = listOf("main", "dev", "release"))
        val asking = agentStatus(mapOf("a" to RunState(running = true)), listOf(q))
        assertEquals("Waiting for your answer", asking.text)
        assertEquals("Asks", asking.chip)
        assertSame(q, asking.question)
        assertEquals("Which branch?\n1. main\n2. dev\n3. release", asking.detail)

        val both = agentStatus(mapOf("a" to RunState(running = true, approvals = listOf(approval()))), listOf(q))
        assertEquals("Waiting for your approval", both.text)
        assertNull(both.question)
    }

    @Test
    fun `a scheduled task's run is never promoted and doesn't change the status`() {
        val scheduled = RunState(running = true, activeTool = "web_fetch", step = 3, helpers = 1, scheduled = true)
        val alone = agentStatus(mapOf("s" to scheduled), listOf(question(chat = "s")))
        assertFalse(alone.promotable)
        assertEquals("Working…", alone.text)
        assertEquals("Working", alone.chip)
        assertNull(alone.question)

        val withUserRun = agentStatus(mapOf("s" to scheduled, "a" to RunState(running = true, step = 2)), emptyList())
        assertTrue(withUserRun.promotable)
        assertEquals("Working…", withUserRun.text)
        assertEquals("Step 2", withUserRun.chip)
    }

    @Test
    fun `nothing running is not promoted`() {
        assertFalse(agentStatus(emptyMap(), emptyList()).promotable)
        assertFalse(agentStatus(mapOf("a" to RunState(running = false, step = 5)), emptyList()).promotable)
    }

    @Test
    fun `background helpers after the turn keep the Live Update`() {
        val status = agentStatus(mapOf("a" to RunState(running = false, helpers = 2)), emptyList())
        assertTrue(status.promotable)
        assertEquals("Working · 2 helpers", status.text)
        assertEquals("Working", status.chip)
    }

    @Test
    fun `question buttons follow the three-action limit`() {
        val three = question(options = listOf("a", "b", "c"), allowOther = false)
        assertTrue(allOptionsAsButtons(three))
        assertEquals(3, questionButtons(three))
        assertEquals("Which branch?", questionBody(three))

        val withOther = question(options = listOf("a", "b", "c"))
        assertFalse(allOptionsAsButtons(withOther))
        assertEquals(2, questionButtons(withOther))

        val multi = question(options = listOf("a", "b"), multi = true)
        assertEquals(0, questionButtons(multi))
        assertEquals("Which branch?\n1. a\n2. b\nReply with your choices.", questionBody(multi))

        assertEquals(0, questionButtons(question()))
    }
}
