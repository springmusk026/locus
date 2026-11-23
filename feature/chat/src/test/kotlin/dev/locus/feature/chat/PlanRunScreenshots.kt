package dev.locus.feature.chat

import androidx.compose.runtime.Composable
import dev.locus.core.data.agent.RunState
import dev.locus.core.model.ModelRef
import dev.locus.core.model.PlanRunEstimate
import dev.locus.core.model.ProposedPlan
import dev.locus.core.screenshot.ScreenshotTest
import org.junit.Test

/** Plan mode's "Run with" (docs/design/plan-run.md): the card, the picker, the confirmation and the run. */
class PlanRunScreenshots : ScreenshotTest() {
    private val opus = ModelRef("a", "claude-opus-5-5")
    private val gpt = ModelRef("r", "openai/gpt-5")
    private val choice = CompareChoice(gpt, "OpenAI: GPT-5")

    @Composable
    private fun Chat(state: ChatUiState) = ChatScreen(
        state = state,
        onBack = {},
        onOpenBrowser = {},
        onSend = {},
        onStop = {},
        onAnswer = { _, _ -> },
        onDismissMessages = {},
        onResume = {},
    )

    private val plan = ChatItem.Plan(
        "p1",
        ProposedPlan(
            "Move garden-app's API to Hono",
            listOf(
                "Add hono and @hono/node-server; keep Express until the switch",
                "Port the 14 routes in src/api, starting with /health and /plants",
                "Run the API tests and the e2e suite against both servers",
            ),
        ),
        PlanStatus.Waiting,
        plannedBy = opus,
    )

    private val user = ChatItem.User("u1", "Move the garden-app API from Express to Hono", planMode = true)

    /** A waiting plan whose card runs it on another model. */
    @Test
    fun card() = snap("chat-plan-run-with") {
        Chat(Fx.state(listOf(user, plan), title = "Express to Hono").let { it.copy(controls = it.controls.copy(planMode = true, empty = false, planRun = choice)) })
    }

    /** The model sheet picking the model that runs the plan. */
    @Test
    fun picker() = snap("sheet-plan-run-picker", heightDp = 1500) {
        val settings = Fx.settings.copy(
            controls = Fx.controls.copy(empty = false, planRun = choice),
            picker = Fx.picker.copy(needs = dev.locus.core.llm.ModelRequirements(tools = true, contextTokens = 12_000)),
        )
        ModelSheet(settings, onPick = { _, _ -> }, onOpen = {}, onToggleFavorite = {}, onEnableCatalog = {}, onDismiss = {}, planRun = true)
    }

    /** Approve on a provider the chat doesn't use: where it goes, both estimates (one unknown), and the way back. */
    @Test
    fun confirm() = snap("sheet-plan-run-confirm", heightDp = 1100) {
        PlanRunConfirmSheet(
            PlanRunConfirm(
                CompareChoice(opus, "Claude Opus 5.5"), choice,
                PlanRunEstimate(48_300, chatWarm = 0.0283, run = null), first = false, otherProvider = "OpenRouter",
            ),
            onRun = {}, onCancel = {},
        )
    }

    /** The plan running on the other model, and later the card that says which one ran it. */
    @Test
    fun running() = snap("chat-plan-running") {
        val approved = plan.copy(status = PlanStatus.Approved, ranOn = gpt)
        val items = listOf(user, approved, ChatItem.User("u2", dev.locus.core.model.AgentControlTools.PLAN_APPROVED))
        Chat(Fx.state(items, title = "Express to Hono", run = RunState(running = true, planRun = "gpt-5 (OpenRouter)", progress = "Reading src/api")))
    }
}
