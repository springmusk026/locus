package dev.locus.feature.chat

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import dev.locus.core.data.agent.QueuedMessage
import dev.locus.core.data.agent.RunState
import dev.locus.core.model.ProposedPlan
import dev.locus.core.screenshot.Step
import dev.locus.core.screenshot.screenAfter
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The composer never loses what the user typed and hasn't sent (#319): an edit sets the draft aside and gives it
 * back, Back leaves the edit first, and a plan's Edit only fills an empty composer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposerEditTest {
    private val draft = "and check the refund path"
    private val queued = "Don't touch the admin app"
    private val drafts = mutableListOf<String>()
    private var activity: ComponentActivity? = null

    private fun chat(state: ChatUiState) = @Composable {
        activity = LocalActivity.current as? ComponentActivity
        ChatScreen(
            state = state,
            onBack = {},
            onOpenBrowser = {},
            onSend = {},
            onStop = {},
            onAnswer = { _, _ -> },
            onDismissMessages = {},
            onResume = {},
            onDraftChange = { drafts += it },
        )
    }

    private val running = chat(
        Fx.state(
            listOf(ChatItem.User("u1", "Fix the checkout tests")),
            run = RunState(running = true, queued = listOf(QueuedMessage("q1", queued, createdAt = Fx.T0))),
        ),
    )

    @Test
    fun `stopping the edit of a queued message gives the draft back`() {
        val editing = screenAfter(listOf(Step.Type(draft), Step.Tap("Edit"))) { running() }
        assertTrue(editing.shows("Editing a queued message"), editing.texts.toString())
        // While the edit is open, the draft kept for the chat is still the one set aside.
        assertEquals(draft, drafts.last())

        val stopped = screenAfter(listOf(Step.Type(draft), Step.Tap("Edit"), Step.Tap("Stop editing"))) { running() }
        assertFalse(stopped.shows("Editing a queued message"))
        assertTrue(stopped.texts.contains(draft), stopped.texts.toString())
    }

    @Test
    fun `back leaves the edit and gives the draft back`() {
        val back = screenAfter(
            listOf(Step.Type(draft), Step.Tap("Edit"), Step.Act { activity!!.onBackPressedDispatcher.onBackPressed() }),
        ) { running() }
        assertFalse(back.shows("Editing a queued message"))
        assertTrue(back.texts.contains(draft), back.texts.toString())
    }

    private val plan = chat(
        Fx.state(
            listOf(
                ChatItem.User("u1", "Move the API to Hono", planMode = true),
                ChatItem.Plan("p1", ProposedPlan("Move the API to Hono", listOf("Port the routes", "Remove Express")), PlanStatus.Waiting),
            ),
        ),
    )

    @Test
    fun `a plan's Edit never replaces a draft`() {
        val kept = screenAfter(listOf(Step.Type(draft), Step.Tap("Edit"))) { plan() }
        assertTrue(kept.texts.contains(draft), kept.texts.toString())
        assertFalse(kept.shows("Change the plan:"))

        // Into an empty composer it starts the change, in the user's language (a string resource).
        val started = screenAfter(listOf(Step.Tap("Edit"))) { plan() }
        assertTrue(started.texts.any { it.startsWith("Change the plan:") }, started.texts.toString())
    }
}
