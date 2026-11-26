package dev.locus.core.data.agent

import dev.locus.core.model.AppSettings
import dev.locus.core.model.ApprovalDecision
import dev.locus.core.model.DeviceState
import dev.locus.core.model.DiscoveryTools
import dev.locus.core.model.PauseReason
import dev.locus.core.model.PowerPolicy
import dev.locus.core.model.PowerSettings
import dev.locus.core.model.ThermalLevel
import dev.locus.core.testing.ScriptedProvider
import dev.locus.core.testing.ScriptedProvider.script
import dev.locus.core.testing.TestGraph
import dev.locus.core.llm.DeviceConditions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Non-urgent side jobs (titles, follow-ups, lessons, the catalogue refresh) wait while the phone is hot
 * or in battery saver, run by themselves once it's back to normal, and at once on Run anyway. The
 * user's own turn is never held (docs/design/battery-thermal.md).
 */
class DeferredWorkTest {
    private val settings = AppSettings(
        providers = listOf(ScriptedProvider.config), defaultProviderId = ScriptedProvider.config.id,
        autoTitle = true, suggestFollowUps = false, reflectAfterTasks = false,
    )

    private fun TestGraph.chatRequests() = requests.count { it.url.host == "llm.test" }

    @Test
    fun `work runs at once when the phone is fine`() = runTest {
        val g = TestGraph(backgroundScope, settings)
        runCurrent()
        var ran = 0
        assertFalse(g.deferred.runOrHold("k", "chat") { ran++ })
        assertEquals(1, ran)
        assertTrue(g.deferred.paused.value.isEmpty())
    }

    @Test
    fun `held while hot, released when it cools, newest per key`() = runTest {
        val g = TestGraph(backgroundScope, settings)
        g.conditions.set(DeviceState(thermal = ThermalLevel.SEVERE))
        runCurrent()
        val ran = mutableListOf<String>()
        assertTrue(g.deferred.runOrHold("chat/after", "chat") { ran += "old" })
        assertTrue(g.deferred.runOrHold("chat/after", "chat") { ran += "new" })
        assertTrue(g.deferred.runOrHold("catalog", null) { ran += "catalog" })
        runCurrent()
        assertTrue(ran.isEmpty())
        assertEquals(mapOf("chat" to PauseReason.HOT), g.deferred.paused.value)

        // Battery saver alone still holds it, now for that reason.
        g.conditions.set(DeviceState(powerSave = true))
        runCurrent()
        assertEquals(mapOf("chat" to PauseReason.POWER_SAVE), g.deferred.paused.value)
        assertTrue(ran.isEmpty())

        g.conditions.set(DeviceState())
        runCurrent()
        assertEquals(setOf("new", "catalog"), ran.toSet())
        assertTrue(g.deferred.paused.value.isEmpty())
    }

    @Test
    fun `run anyway runs one chat's work only`() = runTest {
        val g = TestGraph(backgroundScope, settings)
        g.conditions.set(DeviceState(powerSave = true))
        runCurrent()
        val ran = mutableListOf<String>()
        g.deferred.runOrHold("a/after", "a") { ran += "a" }
        g.deferred.runOrHold("b/after", "b") { ran += "b" }
        g.deferred.runAnyway("a")
        runCurrent()
        assertEquals(listOf("a"), ran)
        assertEquals(setOf("b"), g.deferred.paused.value.keys)
    }

    @Test
    fun `the setting turns deferral off`() = runTest {
        val g = TestGraph(backgroundScope, settings.copy(power = PowerSettings(deferWhenHot = false)))
        g.conditions.set(DeviceState(thermal = ThermalLevel.CRITICAL))
        runCurrent()
        var ran = false
        assertFalse(g.deferred.runOrHold("k", "chat") { ran = true })
        assertTrue(ran)
    }

    @Test
    fun `a hot phone answers the user but holds the chat's title, shown as paused, until Run anyway`() = runTest {
        val g = TestGraph(backgroundScope, settings, dispatcher = StandardTestDispatcher(testScheduler))
        g.script(ScriptedProvider.text("It will rain."), ScriptedProvider.text("Rain check"))
        g.conditions.set(DeviceState(thermal = ThermalLevel.SEVERE))
        val chat = g.conversations.create(ScriptedProvider.config.id).id
        val runner = g.runner
        runCurrent()

        runner.send(chat, "will it rain?")
        // The scripted HTTP answers on the test dispatcher (as in RunAnywayTest), so the title request's
        // timeout can't fire in virtual time before a reply from a real thread. The stores still use
        // their own threads: wait in real time.
        suspend fun until(what: String, check: () -> Boolean) {
            withContext(Dispatchers.Default) {
                withTimeout(10_000) { while (!check()) delay(10) }
            }
            assertTrue(check(), what)
        }
        until("the turn finished") { runner.states.value[chat]?.running == false && g.chatRequests() == 1 }
        until("its side jobs are held") { runner.states.value[chat]?.paused == PauseReason.HOT }
        // The user's turn ran; the title request didn't.
        assertEquals(1, g.chatRequests())

        runner.runDeferredNow(chat)
        until("the title was asked for") { g.chatRequests() == 2 }
        until("nothing is held any more") { runner.states.value[chat]?.paused == null }
    }

    @Test
    fun `a hot phone still runs a deferred tool through call_tool in the user's turn`() = runTest {
        val g = TestGraph(backgroundScope, settings, dispatcher = StandardTestDispatcher(testScheduler))
        g.script(
            ScriptedProvider.toolCall("c1", DiscoveryTools.CALL_TOOL, """{"name":"linux_service_list","arguments":{}}"""),
            ScriptedProvider.text("Nothing is running."),
            ScriptedProvider.text("Services"),
        )
        g.conditions.set(DeviceState(thermal = ThermalLevel.SEVERE))
        val chat = g.conversations.create(ScriptedProvider.config.id)
        // A new chat finds tools on demand: linux_service_list isn't declared, only reachable through call_tool.
        assertEquals(DiscoveryTools.CURRENT, chat.toolDiscovery)
        val runner = g.runner
        runCurrent()

        runner.send(chat.id, "what services are running?")
        suspend fun until(what: String, check: suspend () -> Boolean) {
            withContext(Dispatchers.Default) {
                withTimeout(10_000) { while (!check()) delay(10) }
            }
            assertTrue(check(), what)
        }
        until("the turn finished") { runner.states.value[chat.id]?.running == false && g.chatRequests() == 2 }
        val result = g.conversations.history(chat.id).flatMap { it.toolResults }.single()
        assertEquals(DiscoveryTools.CALL_TOOL, result.toolName)
        assertEquals(ApprovalDecision.AUTO_ALLOWED, result.decision, result.content)
        // Only the side job (the title) waits for the phone to cool.
        until("its side jobs are held") { runner.states.value[chat.id]?.paused == PauseReason.HOT }
        assertEquals(2, g.chatRequests())
    }

    @Test
    fun `work checked just before the phone cooled runs, rather than being held after the release`() = runTest {
        val g = TestGraph(backgroundScope, settings)
        val conditions = StaleRead(DeviceState(thermal = ThermalLevel.SEVERE))
        val deferred = DeferredWork(backgroundScope, conditions, g.settingsRepository)
        runCurrent()
        // The phone cools and the (empty) hold is released...
        conditions.flow.value = DeviceState()
        runCurrent()
        // ...but runOrHold read the conditions a moment before. It used to hold the block after the
        // release had run, where nothing would ever release it.
        conditions.stale = DeviceState(thermal = ThermalLevel.SEVERE)
        var ran = false
        assertFalse(deferred.runOrHold("k", "chat") { ran = true })
        assertTrue(ran)
        assertTrue(deferred.paused.value.isEmpty())
    }

    @Test
    fun `work held for heat waits a minute below severe, and flapping back to severe restarts the wait`() = runTest {
        val g = TestGraph(backgroundScope, settings)
        g.conditions.set(DeviceState(thermal = ThermalLevel.SEVERE))
        runCurrent()
        val ran = mutableListOf<String>()
        assertTrue(g.deferred.runOrHold("a/after", "a") { ran += "a" })

        g.conditions.set(DeviceState(thermal = ThermalLevel.MODERATE))
        runCurrent()
        advanceTimeBy(40_000)
        runCurrent()
        assertTrue(ran.isEmpty(), "a dip to MODERATE doesn't release at once")
        assertEquals(mapOf("a" to PauseReason.HOT), g.deferred.paused.value)
        // New work in that minute waits too, rather than running beside the held work.
        assertTrue(g.deferred.runOrHold("b/after", "b") { ran += "b" })

        g.conditions.set(DeviceState(thermal = ThermalLevel.SEVERE))
        runCurrent()
        g.conditions.set(DeviceState(thermal = ThermalLevel.MODERATE))
        runCurrent()
        advanceTimeBy(PowerPolicy.HOT_RELEASE_AFTER_MS - 1_000)
        runCurrent()
        assertTrue(ran.isEmpty(), "back to SEVERE restarted the minute")
        // Battery readings changing in the meantime don't restart it.
        g.conditions.set(DeviceState(thermal = ThermalLevel.MODERATE, batteryPercent = 40))
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(setOf("a", "b"), ran.toSet())
        assertTrue(g.deferred.paused.value.isEmpty())
    }

    @Test
    fun `work held for heat is released at once below moderate`() = runTest {
        val g = TestGraph(backgroundScope, settings)
        g.conditions.set(DeviceState(thermal = ThermalLevel.CRITICAL))
        runCurrent()
        var ran = false
        assertTrue(g.deferred.runOrHold("k", "chat") { ran = true })
        g.conditions.set(DeviceState(thermal = ThermalLevel.LIGHT))
        runCurrent()
        assertTrue(ran)
        assertTrue(g.deferred.paused.value.isEmpty())
    }
}

/** Conditions whose next `state.value` read returns [stale] once: a read made just before a change. */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class StaleRead(initial: DeviceState) : DeviceConditions {
    val flow = MutableStateFlow(initial)
    var stale: DeviceState? = null
    override val state: StateFlow<DeviceState> = object : StateFlow<DeviceState> by flow {
        override val value: DeviceState get() = stale?.also { stale = null } ?: flow.value
    }
}
