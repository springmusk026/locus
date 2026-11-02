package dev.locus.core.data.automation

import dev.locus.core.data.automation.AutomationContract as C
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The automation intents' parsing and who may ask (docs/design/android-bridge.md). */
class AutomationTest {
    private fun parse(action: String?, vararg extras: Pair<String, Any?>): Parsed {
        val map = mapOf(*extras)
        return AutomationRequest.parse(action, map.keys, map::get)
    }

    private fun ok(action: String?, vararg extras: Pair<String, Any?>) = assertIs<Parsed.Ok>(parse(action, *extras)).request
    private fun invalid(action: String?, vararg extras: Pair<String, Any?>) = assertIs<Parsed.Invalid>(parse(action, *extras)).reason

    // ---- Parsing -------------------------------------------------------------------------------

    @Test
    fun `start chat takes a prompt, a project and whether to show it`() {
        val r = ok(C.ACTION_START_CHAT, C.EXTRA_PROMPT to "  Summarise my day ", C.EXTRA_PROJECT to "Garden", C.EXTRA_SHOW_CHAT to true, C.EXTRA_REQUEST_ID to "42")
        assertEquals(AutomationRequest.StartChat("Summarise my day", "Garden", openChat = true, requestId = "42"), r)
        // Tasker sends booleans as text.
        assertEquals(true, (ok(C.ACTION_START_CHAT, C.EXTRA_PROMPT to "x", C.EXTRA_SHOW_CHAT to "TRUE") as AutomationRequest.StartChat).openChat)
        assertEquals(false, (ok(C.ACTION_START_CHAT, C.EXTRA_PROMPT to "x") as AutomationRequest.StartChat).openChat)
    }

    @Test
    fun `start chat refuses a missing, blank, huge or oddly typed prompt`() {
        invalid(C.ACTION_START_CHAT)
        invalid(C.ACTION_START_CHAT, C.EXTRA_PROMPT to "   ")
        invalid(C.ACTION_START_CHAT, C.EXTRA_PROMPT to 42)
        invalid(C.ACTION_START_CHAT, C.EXTRA_PROMPT to "x".repeat(AutomationRequest.MAX_PROMPT_CHARS + 1))
        invalid(C.ACTION_START_CHAT, C.EXTRA_PROMPT to "x", C.EXTRA_SHOW_CHAT to 3)
        invalid(C.ACTION_START_CHAT, C.EXTRA_PROMPT to "x", C.EXTRA_PROJECT to "p".repeat(500))
    }

    @Test
    fun `workflow inputs come from JSON and from param extras, extras winning`() {
        val r = ok(
            C.ACTION_RUN_WORKFLOW,
            C.EXTRA_WORKFLOW to "weekly-report",
            C.EXTRA_PARAMS to """{"city": "Lisbon", "days": 3, "draft": true}""",
            C.PARAM_PREFIX + "city" to "Porto",
            C.PARAM_PREFIX + "lang" to "pt",
        ) as AutomationRequest.RunWorkflow
        assertEquals("weekly-report", r.workflow)
        assertEquals(mapOf("city" to "Porto", "days" to "3", "draft" to "true", "lang" to "pt"), r.params)
    }

    @Test
    fun `bad workflow inputs are refused before anyone is asked`() {
        invalid(C.ACTION_RUN_WORKFLOW)
        invalid(C.ACTION_RUN_WORKFLOW, C.EXTRA_WORKFLOW to "w", C.EXTRA_PARAMS to "not json")
        invalid(C.ACTION_RUN_WORKFLOW, C.EXTRA_WORKFLOW to "w", C.EXTRA_PARAMS to "[1, 2]")
        invalid(C.ACTION_RUN_WORKFLOW, C.EXTRA_WORKFLOW to "w", C.EXTRA_PARAMS to """{"a": {"nested": 1}}""")
        invalid(C.ACTION_RUN_WORKFLOW, C.EXTRA_WORKFLOW to "w", C.EXTRA_PARAMS to """{"bad name!": "x"}""")
        invalid(C.ACTION_RUN_WORKFLOW, C.EXTRA_WORKFLOW to "w", C.PARAM_PREFIX + "big" to "x".repeat(AutomationRequest.MAX_PARAM_CHARS + 1))
        invalid(C.ACTION_RUN_WORKFLOW, C.EXTRA_WORKFLOW to "w", C.PARAM_PREFIX + "list" to listOf("a"))
        val many = (0..AutomationRequest.MAX_PARAMS).map { C.PARAM_PREFIX + "p$it" to "v" }.toTypedArray()
        invalid(C.ACTION_RUN_WORKFLOW, C.EXTRA_WORKFLOW to "w", *many)
    }

    @Test
    fun `run task needs a task, unknown actions are refused`() {
        assertEquals(AutomationRequest.RunTask("Morning digest", null), ok(C.ACTION_RUN_TASK, C.EXTRA_TASK to "Morning digest"))
        invalid(C.ACTION_RUN_TASK)
        assertTrue("Unknown action" in invalid("dev.locus.action.DELETE_EVERYTHING", C.EXTRA_PROMPT to "x"))
        invalid(null)
    }

    @Test
    fun `summaries say what is asked`() {
        assertEquals("start a chat in project “Garden”", AutomationRequest.StartChat("x", "Garden", false, null).summary)
        assertEquals("run the workflow “w”", AutomationRequest.RunWorkflow("w", emptyMap(), null).summary)
        assertEquals("run the scheduled task “t” now", AutomationRequest.RunTask("t", null).summary)
    }

    // ---- Who may ask ---------------------------------------------------------------------------

    private val tasker = Caller("net.dinglisch.android.taskerm", "Tasker")
    private val unknown = Caller(null, null)

    private fun decide(
        caller: Caller,
        enabled: Boolean = true,
        ours: Boolean = true,
        holds: Boolean = false,
        remembered: Boolean? = null,
    ) = AutomationAccess.decide(enabled, caller, ours, holds, remembered, ownPackage = "dev.locus")

    @Test
    fun `nothing runs while automation is off, whoever asks`() {
        assertEquals(AccessDecision.Off, decide(tasker, enabled = false, holds = true))
        assertEquals(AccessDecision.Off, decide(tasker, enabled = false, remembered = true))
        assertEquals(AccessDecision.Off, decide(unknown, enabled = false))
    }

    @Test
    fun `an app Android doesn't name is confirmed every time`() {
        assertEquals(AccessDecision.Confirm, decide(unknown))
        // Holding the permission can't count without knowing who holds it.
        assertEquals(AccessDecision.Confirm, decide(unknown, holds = true))
    }

    @Test
    fun `a named app runs only once the user allowed it`() {
        assertEquals(AccessDecision.Confirm, decide(tasker))
        assertEquals(AccessDecision.Run, decide(tasker, remembered = true))
        assertEquals(AccessDecision.Run, decide(tasker, holds = true))
        assertIs<AccessDecision.Refuse>(decide(tasker, remembered = false))
        // "Never" wins over a granted permission.
        assertIs<AccessDecision.Refuse>(decide(tasker, remembered = false, holds = true))
    }

    @Test
    fun `a permission another app defined first stops everything`() {
        assertIs<AccessDecision.Refuse>(decide(tasker, ours = false, remembered = true))
        assertIs<AccessDecision.Refuse>(decide(unknown, ours = false))
    }

    @Test
    fun `Locus never takes requests from itself`() {
        assertIs<AccessDecision.Refuse>(decide(Caller("dev.locus", "Locus"), remembered = true))
    }

    @Test
    fun `the caller is who Android vouches for, and results go only to its own PendingIntent`() {
        assertEquals("a", AutomationAccess.identify("a", "b", "c"))
        assertEquals("b", AutomationAccess.identify(null, "b", "c"))
        assertEquals("c", AutomationAccess.identify(null, null, "c"))
        assertNull(AutomationAccess.identify(null, null, null))
        assertTrue(AutomationAccess.resultTarget("a", "a"))
        // A PendingIntent made by another app than the caller would send the result elsewhere.
        assertFalse(AutomationAccess.resultTarget("a", "c"))
        assertFalse(AutomationAccess.resultTarget(null, null))
        assertEquals("Tasker (net.dinglisch.android.taskerm)", tasker.shown)
        assertEquals("an app Android didn't name", unknown.shown)
    }

    @Test
    fun `lookups match an id, else one name ignoring case`() {
        data class Item(val id: String, val name: String)
        val items = listOf(Item("1", "Morning"), Item("2", "Evening"), Item("3", "Twin"), Item("4", "twin"))
        assertEquals("1", AutomationLookup.find(items, "1", { it.id }, { it.name })?.id)
        assertEquals("2", AutomationLookup.find(items, " evening ", { it.id }, { it.name })?.id)
        // Two items share the name: nothing is guessed.
        assertNull(AutomationLookup.find(items, "TWIN", { it.id }, { it.name }))
        assertNull(AutomationLookup.find(items, "Noon", { it.id }, { it.name }))
    }

    @Test
    fun `the permission follows the application id`() {
        assertEquals(C.PERMISSION, C.permission("dev.locus"))
        assertEquals("dev.locus.debug.permission.RUN_TASK", C.permission("dev.locus.debug"))
    }
}
