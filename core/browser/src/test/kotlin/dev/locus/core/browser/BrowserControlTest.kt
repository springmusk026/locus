package dev.locus.core.browser

import dev.locus.core.agent.ToolException
import dev.locus.core.agent.ToolResult
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Take over and hand back: the pause, the resume, the timeout and the one-time notice ([BrowserControl]). */
class BrowserControlTest {
    private var now = 0L
    private val changes = mutableListOf<String>()
    private val control = BrowserControl(clock = { now }, onChange = { changes += it })

    @Test
    fun `the agent's call goes ahead at once when nobody has taken over`() = runTest {
        assertFalse(control.await("c"))
        assertNull(control.takeNotice("c"))
    }

    @Test
    fun `a call waits while the user has the browser, and goes on when they hand back`() = runTest {
        assertTrue(control.takeOver("c"))
        assertFalse(control.takeOver("c"), "already theirs")
        val call = async { control.await("c") }
        runCurrent()
        assertFalse(call.isCompleted)
        assertTrue(control.waiting("c"))
        // Another chat's browser isn't paused.
        assertFalse(control.await("other"))
        now = 95_000
        assertEquals(95_000L, control.handBack("c", noted = false))
        assertTrue(call.await())
        assertFalse(control.inControl("c"))
        assertFalse(control.waiting("c"))
        val notice = control.takeNotice("c")!!
        assertTrue("1 min" in notice && "refs from earlier snapshots no longer apply" in notice, notice)
        assertTrue(SnapshotFormat.TYPED_BY_USER in notice)
        assertFalse("note" in notice)
        assertNull(control.takeNotice("c"), "once")
        assertTrue(changes.isNotEmpty())
    }

    @Test
    fun `a hand-back with a note says a message follows, without its text`() {
        control.takeOver("c")
        control.handBack("c", noted = true)
        assertTrue("They left a note for you; it follows as their next message." in control.takeNotice("c")!!)
    }

    @Test
    fun `a call times out with what to do instead, and the user keeps control`() = runTest {
        control.takeOver("c")
        val call = async { runCatching { control.await("c") } }
        advanceTimeBy(BrowserControl.WAIT_MS + 1)
        val error = call.await().exceptionOrNull()
        assertTrue(error is ToolException)
        assertTrue("hasn't handed it back after 5 min" in error.message!! && "nothing on the page was read" in error.message!!)
        assertTrue(control.inControl("c"))
        assertFalse(control.waiting("c"))
    }

    @Test
    fun `handing back when nobody took over does nothing`() {
        assertNull(control.handBack("c", noted = true))
        assertNull(control.takeNotice("c"))
    }

    @Test
    fun `the gate runs nothing while the user has control and puts the notice first`() = runTest {
        var ran = 0
        control.takeOver("c")
        val result = async { takeoverGate(control, "c") { ran++; ToolResult("page") } }
        runCurrent()
        assertEquals(0, ran)
        control.handBack("c", noted = false)
        val content = result.await().content
        assertEquals(1, ran)
        assertTrue(content.startsWith("The user took control of the browser") && content.endsWith("\n\npage"), content)
    }

    @Test
    fun `after a wait a call can answer differently, and an error carries the notice too`() = runTest {
        control.takeOver("c")
        val asked = async { takeoverGate(control, "c", afterWait = { ToolResult("already handed back") }) { ToolResult("hand over now") } }
        runCurrent()
        control.handBack("c", noted = false)
        assertTrue(asked.await().content.endsWith("already handed back"))

        control.takeOver("c")
        val click = async { runCatching { takeoverGate(control, "c") { throw ToolException(BrowserSessions.AFTER_TAKEOVER) } } }
        runCurrent()
        control.handBack("c", noted = false)
        val message = click.await().exceptionOrNull()!!.message!!
        assertTrue(message.startsWith("The user took control") && message.endsWith(BrowserSessions.AFTER_TAKEOVER))
    }

    @Test
    fun `durations read as a person would say them`() {
        assertEquals("1 s", BrowserControl.duration(200))
        assertEquals("42 s", BrowserControl.duration(42_000))
        assertEquals("5 min", BrowserControl.duration(300_000))
        assertEquals("1 h 5 min", BrowserControl.duration(3_900_000))
    }

    @Test
    fun `refs are void after a takeover until the next snapshot`() {
        val sessions = BrowserSessions()
        sessions.snapshotTaken("c", 1, "d1")
        assertTrue(sessions.checkRefs("c", 1) is BrowserSessions.RefCheck.Ok)
        sessions.userTookOver("c")
        assertEquals(BrowserSessions.RefCheck.Stale(BrowserSessions.AFTER_TAKEOVER), sessions.checkRefs("c", 1))
        sessions.snapshotTaken("c", 1, "d2")
        assertEquals(BrowserSessions.RefCheck.Ok("d2"), sessions.checkRefs("c", 1))
    }

    @Test
    fun `the timeout is long enough for a login with a code`() {
        assertTrue(BrowserControl.WAIT_MS >= 3 * 60_000)
    }
}
