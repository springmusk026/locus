package dev.locus.feature.chat

import dev.locus.core.data.repository.ModelList
import dev.locus.core.llm.ModelRequirements
import dev.locus.core.llm.RemoteModel
import dev.locus.core.model.AgentControlTools
import dev.locus.core.model.Message
import dev.locus.core.model.ModelRef
import dev.locus.core.model.Part
import dev.locus.core.model.ReplyInfo
import dev.locus.core.model.Role
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Plan mode's "Run with" in the chat (docs/design/plan-run.md): which models can run the plan, and what the card says after. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlanRunUiTest {
    private val opus = ModelRef("a", "claude-opus-5-5")
    private val haiku = ModelRef("a", "claude-haiku-5")
    private val noTools = ModelRef("r", "acme/no-tools-7b")

    private val lists: Map<String, ModelList> = Fx.lists + (
        "r" to ModelList.Loaded(listOf(RemoteModel("acme/no-tools-7b", "Acme: No Tools 7B", contextWindow = 32_000, tools = false)))
        )

    private fun rows(runOn: ModelRef?, chatOnly: Boolean = false): Map<ModelRef, PickerRow> =
        buildModelPicker(
            providers = Fx.providers, lists = lists, currentProviderId = "a", controls = Fx.controls,
            favorites = emptyList(), recents = emptyList(), needs = compareNeeds(ModelRequirements(tools = false, contextTokens = 2_000), chatOnly),
        ).forPlanRun(runOn).flatMap { it.rows }.associateBy { it.ref }

    @Test
    fun `the chat's own model is the default and stays pickable, and models that can't serve the chat aren't`() {
        val r = rows(runOn = null)
        assertTrue(r.getValue(opus).pickable)
        assertTrue(r.getValue(opus).chatModel)
        assertTrue(r.getValue(opus).selected, "nothing picked: the chat's own model runs it")
        assertTrue(r.getValue(haiku).pickable)
        // The run model gets the chat's tool list, so it must be able to call tools.
        assertFalse(r.getValue(noTools).pickable)
        assertTrue(rows(runOn = null, chatOnly = true).getValue(noTools).pickable)
    }

    @Test
    fun `the picked model is the one marked`() {
        val r = rows(runOn = haiku)
        assertTrue(r.getValue(haiku).selected)
        assertFalse(r.getValue(opus).selected)
        assertTrue(r.getValue(opus).chatModel)
        assertEquals(1, r.values.count { it.selected })
    }

    private var t = 0L
    private fun msg(id: String, role: Role, vararg parts: Part, ref: ModelRef? = null) =
        Message(id, "c", role, parts.toList(), t++, reply = ref?.let { ReplyInfo(it.providerId, it.model) })

    private fun planned(approval: String, runBy: ModelRef) = listOf(
        msg("u1", Role.USER, Part.Text("Tidy my files")),
        msg("a1", Role.ASSISTANT, Part.ToolCall("p1", AgentControlTools.PLAN_PROPOSE, """{"title":"Tidy","steps":["List","Rename"]}"""), ref = opus),
        msg("t1", Role.TOOL, Part.ToolResult("p1", AgentControlTools.PLAN_PROPOSE, "Shown.")),
        msg("u2", Role.USER, Part.Text(approval)),
        msg("a2", Role.ASSISTANT, Part.Text("Done."), ref = runBy),
    )

    @Test
    fun `an approved plan run on another model says which one, and one run on its own model doesn't`() {
        val other = transcriptItems(planned(AgentControlTools.PLAN_APPROVED, haiku)).filterIsInstance<ChatItem.Plan>().single()
        assertEquals(PlanStatus.Approved, other.status)
        assertEquals(opus, other.plannedBy)
        assertEquals(haiku, other.ranOn)

        val same = transcriptItems(planned(AgentControlTools.PLAN_APPROVED, opus)).filterIsInstance<ChatItem.Plan>().single()
        assertNull(same.ranOn)

        // Answered with changes instead: not run, so no model ran it.
        val changed = transcriptItems(planned("Change the plan: photos only", haiku)).filterIsInstance<ChatItem.Plan>().single()
        assertEquals(PlanStatus.Answered, changed.status)
        assertNull(changed.ranOn)
        assertNull(planRanOn(planned("Change the plan: photos only", haiku), "a1"))
    }

    @Test
    fun `the estimate prices the run model's first request as a cache write, unknown without prices`() {
        val e = planRunEstimate(Fx.providers, catalog = null, contextTokens = 40_000, chat = opus, run = haiku)
        assertTrue(e.promptTokens > 40_000)
        assertNull(e.run)
        assertNull(e.chatWarm)
    }
}
