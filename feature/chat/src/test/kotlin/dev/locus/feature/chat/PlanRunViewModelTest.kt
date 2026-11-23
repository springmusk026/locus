package dev.locus.feature.chat

import dev.locus.core.model.AgentControlTools
import dev.locus.core.model.AppSettings
import dev.locus.core.model.ModelRef
import dev.locus.core.testing.MainDispatcherRule
import dev.locus.core.testing.ScriptedProvider
import dev.locus.core.testing.ScriptedProvider.script
import dev.locus.core.testing.TestGraph
import dev.locus.core.testing.ViewModelHost
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Plan mode's "Run with" in ChatViewModel (docs/design/plan-run.md), over the real runner: picking the model on the
 * plan card, asking before a provider the chat doesn't use, and what the card shows once the plan ran.
 */
class PlanRunViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val host = ViewModelHost()

    private fun runTest(body: suspend TestScope.() -> Unit) = kotlinx.coroutines.test.runTest {
        try {
            body()
        } finally {
            host.clear()
        }
    }

    /** A second provider on the scripted host, so its requests are scripted too. */
    private val other = ScriptedProvider.config.copy(id = "other", name = "Other", model = "other-model")
    private val otherRef = ModelRef(other.id, other.model)
    private val settings = AppSettings(
        providers = listOf(ScriptedProvider.config, other), defaultProviderId = ScriptedProvider.config.id,
        autoTitle = false, suggestFollowUps = false, reflectAfterTasks = false,
    )

    private class Fixture(val graph: TestGraph, val chat: String, val vm: ChatViewModel)

    private suspend fun TestScope.fixture(s: AppSettings = settings): Fixture {
        val g = TestGraph(backgroundScope, s, dispatcher = StandardTestDispatcher(testScheduler))
        val chat = g.conversations.create(s.defaultProviderId).id
        val vm = host.add(ChatViewModel(chat, g.conversations, g.settingsRepository, g.directory, g.runner, g.browser, g.workspaceRepository, g.library, g.workspace, g.onDevice, g.sentRequests, g.sideQuestions, g.changes, g.compute, g.receipts))
        backgroundScope.launch { vm.uiState.collect {} }
        backgroundScope.launch { vm.planRunConfirm.collect {} }
        runCurrent()
        return Fixture(g, chat, vm)
    }

    private suspend fun ChatViewModel.state(until: (ChatUiState) -> Boolean) = uiState.first { !it.loading && until(it) }

    private fun TestGraph.models(): List<String> = synchronized(requests) {
        requests.filter { it.url.host == "llm.test" }.map {
            Json.parseToJsonElement((it.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject.getValue("model").jsonPrimitive.content
        }
    }

    /** Plan mode on, and a plan proposed and waiting. */
    private suspend fun Fixture.planned() {
        vm.setPlanMode(true)
        vm.state { it.controls.planMode }
        vm.send("tidy my files")
        vm.state { st -> !st.run.running && st.items.any { it is ChatItem.Plan } }
    }

    @Test
    fun `the plan card's pick shows on the controls, and the chat's own model means no pick`() = runTest {
        val f = fixture()
        f.vm.state { it.provider != null }
        assertNull(f.vm.state { true }.controls.planRun)
        f.vm.setPlanRun(otherRef)
        assertEquals(otherRef, f.vm.state { it.controls.planRun != null }.controls.planRun?.ref)
        // The chat's own model, or a provider's default named without a model, both resolve.
        f.vm.setPlanRun(ModelRef(ScriptedProvider.config.id, "test-model"))
        assertNull(f.vm.state { it.controls.planRun == null }.controls.planRun)
        f.vm.setPlanRun(ModelRef(other.id, ""))
        assertEquals(otherRef, f.vm.state { it.controls.planRun != null }.controls.planRun?.ref)
        f.vm.setPlanRun(null)
        assertNull(f.vm.state { it.controls.planRun == null }.controls.planRun)
    }

    @Test
    fun `another provider asks first, not now leaves the plan waiting, and yes runs it there`() = runTest {
        val f = fixture()
        f.graph.script(
            ScriptedProvider.toolCall("p1", AgentControlTools.PLAN_PROPOSE, """{"title":"Tidy","steps":["List files","Rename them"]}"""),
            ScriptedProvider.text("Renamed everything."),
        )
        f.planned()
        f.vm.setPlanRun(otherRef)
        f.vm.state { it.controls.planRun != null }

        f.vm.approvePlan()
        val confirm = assertNotNull(f.vm.planRunConfirm.first { it != null })
        assertEquals("Other", confirm.otherProvider)
        assertEquals(otherRef, confirm.run.ref)
        assertEquals(ModelRef(ScriptedProvider.config.id, "test-model"), confirm.chat.ref)

        // Not now: nothing ran, the plan still waits, the pick stays.
        f.vm.dismissPlanRunConfirm()
        runCurrent()
        assertEquals(listOf("test-model"), f.graph.models())
        val waiting = f.vm.state { true }
        assertEquals(PlanStatus.Waiting, waiting.items.filterIsInstance<ChatItem.Plan>().single().status)
        assertEquals(otherRef, waiting.controls.planRun?.ref)

        f.vm.approvePlan()
        f.vm.planRunConfirm.first { it != null }
        f.vm.confirmPlanRun()
        val done = f.vm.state { st ->
            !st.run.running && st.items.filterIsInstance<ChatItem.Assistant>().any { it.text == "Renamed everything." } &&
                st.items.filterIsInstance<ChatItem.Plan>().single().status == PlanStatus.Approved
        }
        assertEquals(listOf("test-model", "other-model"), f.graph.models())
        val plan = done.items.filterIsInstance<ChatItem.Plan>().single()
        assertEquals(otherRef, plan.ranOn)
        // One approval uses the pick up, the chat's own model is unchanged, and the confirmation is remembered.
        assertNull(done.controls.planRun)
        assertEquals("test-model", done.controls.model)
        assertFalse(done.controls.modelOverridden)
        assertTrue(f.graph.settingsRepository.current().planRunConfirmed)
    }

    @Test
    fun `the chat's own model approves at once, without asking`() = runTest {
        val f = fixture()
        f.graph.script(
            ScriptedProvider.toolCall("p1", AgentControlTools.PLAN_PROPOSE, """{"title":"Tidy","steps":["List files"]}"""),
            ScriptedProvider.text("Done."),
        )
        f.planned()
        f.vm.approvePlan()
        f.vm.state { st -> !st.run.running && st.items.filterIsInstance<ChatItem.Assistant>().any { it.text == "Done." } }
        assertNull(f.vm.planRunConfirm.value)
        assertEquals(listOf("test-model", "test-model"), f.graph.models())
    }

    @Test
    fun `the same provider asks only the first time when the price is unknown`() = runTest {
        val sameProvider = settings.copy(planRunConfirmed = true)
        val f = fixture(sameProvider)
        f.graph.script(
            ScriptedProvider.toolCall("p1", AgentControlTools.PLAN_PROPOSE, """{"title":"Tidy","steps":["List files"]}"""),
            ScriptedProvider.text("Done on the bigger one."),
        )
        f.planned()
        f.vm.setPlanRun(ModelRef(ScriptedProvider.config.id, "bigger-model"))
        f.vm.state { it.controls.planRun != null }
        f.vm.approvePlan()
        f.vm.state { st -> !st.run.running && st.items.filterIsInstance<ChatItem.Assistant>().any { it.text == "Done on the bigger one." } }
        assertNull(f.vm.planRunConfirm.value)
        assertEquals(listOf("test-model", "bigger-model"), f.graph.models())
    }
}
