package dev.locus.core.data.agent

import dev.locus.core.model.AppSettings
import dev.locus.core.model.DeviceState
import dev.locus.core.model.PauseReason
import dev.locus.core.model.PowerSettings
import dev.locus.core.model.ThermalLevel
import dev.locus.core.testing.ScriptedProvider
import dev.locus.core.testing.ScriptedProvider.script
import dev.locus.core.testing.TestGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
        val g = TestGraph(backgroundScope, settings)
        g.script(ScriptedProvider.text("It will rain."), ScriptedProvider.text("Rain check"))
        g.conditions.set(DeviceState(thermal = ThermalLevel.SEVERE))
        val chat = g.conversations.create(ScriptedProvider.config.id).id
        val runner = g.runner
        runCurrent()

        runner.send(chat, "will it rain?")
        // Parts of a turn run on real threads (the scripted HTTP engine, the stores): wait in real time.
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
}
