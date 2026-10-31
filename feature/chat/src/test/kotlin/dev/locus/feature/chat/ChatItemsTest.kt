package dev.locus.feature.chat

import dev.locus.core.model.AgentControlTools
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.ProposedPlan
import dev.locus.core.model.Role
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatItemsTest {
    private fun msg(id: String, role: Role, vararg parts: Part) = Message(id, "c", role, parts.toList(), 0)

    @Test
    fun `a plan waits until the user answers, and approval is recognised`() {
        val plan = ChatItem.Plan("p", ProposedPlan("T", listOf("a")), PlanStatus.Waiting)
        assertEquals(PlanStatus.Waiting, (resolvePlans(listOf(plan)).single() as ChatItem.Plan).status)
        val approved = resolvePlans(listOf(plan, ChatItem.User("u", AgentControlTools.PLAN_APPROVED)))
        assertEquals(PlanStatus.Approved, (approved.first() as ChatItem.Plan).status)
        val changed = resolvePlans(listOf(plan, ChatItem.User("u", "Change the plan: skip step 2")))
        assertEquals(PlanStatus.Answered, (changed.first() as ChatItem.Plan).status)
    }

    @Test
    fun `only the latest turn's checklist is current`() {
        val todos = """{"todos":[{"content":"A","status":"pending"}]}"""
        val earlier = listOf(
            msg("u1", Role.USER, Part.Text("first")),
            msg("a1", Role.ASSISTANT, Part.ToolCall("t1", AgentControlTools.TODO_WRITE, todos)),
            msg("r1", Role.TOOL, Part.ToolResult("t1", AgentControlTools.TODO_WRITE, "ok")),
        )
        assertEquals(1, currentTodos(earlier)?.size)
        assertNull(currentTodos(earlier + msg("u2", Role.USER, Part.Text("next"))))
    }

    @Test
    fun `reasoning sits in the steps when the step called tools, above the reply otherwise, and checklist calls are hidden`() {
        val step = msg(
            "a1", Role.ASSISTANT,
            Part.Reasoning("look first", 2_000),
            Part.ToolCall("t1", "read_file", "{}"),
            Part.ToolCall("t2", AgentControlTools.TODO_WRITE, """{"todos":[]}"""),
        )
        val reply = msg("a2", Role.ASSISTANT, Part.Reasoning("now answer", 1_000), Part.Text("Done."))
        val items = turnItems(listOf(step, reply), emptyMap())
        val thoughts = items.filterIsInstance<ChatItem.Thought>()
        assertEquals(listOf(true, false), thoughts.map { it.inSteps })
        assertTrue(items.none { it is ChatItem.ToolCall && it.name == AgentControlTools.TODO_WRITE })
        assertEquals("Done.", (items.last() as ChatItem.Assistant).text)
        assertEquals("Thought for 12s", thoughtLabel(12_400))
    }

    @Test
    fun `sent files become cards after the reply, failed sends stay steps`() {
        val call = msg(
            "a1", Role.ASSISTANT,
            Part.Text("Here is your report."),
            Part.ToolCall("t1", "send_file", """{"path":"/workspace/out/report.pdf","note":"Q3"}"""),
            Part.ToolCall("t2", "send_file", """{"path":"missing.pdf"}"""),
        )
        val results = mapOf(
            "t1" to Part.ToolResult("t1", "send_file", "Sent out/report.pdf"),
            "t2" to Part.ToolResult("t2", "send_file", "'missing.pdf' does not exist.", isError = true),
        )
        val items = turnItems(listOf(call), results)
        val card = items.last() as ChatItem.SentFile
        assertEquals("out/report.pdf", card.path)
        assertEquals("Q3", card.note)
        assertTrue(items.any { it is ChatItem.ToolCall && it.name == "send_file" })
    }
}
