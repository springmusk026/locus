package dev.locus.feature.chat.voice

import dev.locus.core.agent.ApprovalFlag
import dev.locus.core.agent.ApprovalRequest
import dev.locus.core.model.ToolRisk
import dev.locus.core.model.VoiceSettings
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VoiceSessionTest {

    private class Ears : VoiceEars {
        var listens = 0
        var stops = 0
        var cancels = 0
        override fun listen() { listens++ }
        override fun stop() { stops++ }
        override fun cancel() { cancels++ }
    }

    private class Mouth : VoiceMouth {
        val said = mutableListOf<Pair<String, Int>>()
        var stops = 0
        val lastId get() = said.last().second
        val lastText get() = said.last().first
        override fun speak(text: String, id: Int) { said += text to id }
        override fun stop() { stops++ }
    }

    private class Agent : VoiceAgent {
        val sent = mutableListOf<String>()
        val answers = mutableListOf<Pair<String, Boolean>>()
        override fun send(text: String) { sent += text }
        override fun answer(callId: String, approved: Boolean) { answers += callId to approved }
    }

    private var clock = 0L
    private val ears = Ears()
    private val mouth = Mouth()
    private val agent = Agent()

    private fun session(settings: VoiceSettings = VoiceSettings(spokenStatus = false)) =
        VoiceSession(ears, mouth, agent, { clock }, settings)

    private fun approval(id: String = "c1", flags: Set<ApprovalFlag>? = emptySet(), note: String? = null) =
        ApprovalRequest("chat", id, "run_command", ToolRisk.EXECUTE, "Run `ls -la`", "{}", reviewerNote = note, flags = flags)

    /** Finishes whatever is being said. */
    private fun VoiceSession.done() = spoken(mouth.lastId)

    @Test
    fun `the loop listens, sends, speaks the reply and listens again`() {
        val s = session()
        s.onAgent(VoiceAgentView(replyKey = "r0", replyText = "Old"))
        s.start()
        assertEquals(VoicePhase.Listening("", ListenFor.MESSAGE), s.phase.value)
        s.heardPartial("what's the")
        assertEquals(VoicePhase.Listening("what's the", ListenFor.MESSAGE), s.phase.value)
        s.heard("what's the weather")
        assertEquals(listOf("what's the weather"), agent.sent)
        assertIs<VoicePhase.Working>(s.phase.value)
        s.onAgent(VoiceAgentView(running = true, replyKey = "r0", replyText = "Old"))
        s.onAgent(VoiceAgentView(running = false, replyKey = "r1", replyText = "**Sunny**, 20 degrees.\n\n```\ncode\n```"))
        val speaking = assertIs<VoicePhase.Speaking>(s.phase.value)
        assertEquals(SpeechKind.REPLY, speaking.kind)
        assertEquals("Sunny, 20 degrees.", mouth.lastText)
        s.done()
        assertEquals(VoicePhase.Listening("", ListenFor.MESSAGE), s.phase.value)
    }

    @Test
    fun `with auto-listen off it waits for a tap after the reply`() {
        val s = session(VoiceSettings(autoListen = false, spokenStatus = false))
        s.start()
        assertEquals(VoicePhase.Idle(VoiceSession.TAP_TO_TALK), s.phase.value)
        s.tap()
        assertIs<VoicePhase.Listening>(s.phase.value)
    }

    @Test
    fun `an earlier run's error isn't read as this message's`() {
        val s = session()
        s.onAgent(VoiceAgentView(error = "old failure", replyKey = "r0"))
        s.start()
        s.heard("hello")
        // The runner hasn't started yet; the stale error must not be spoken.
        s.onAgent(VoiceAgentView(error = "old failure", replyKey = "r0"))
        assertEquals(VoicePhase.Working("Sending"), s.phase.value)
        assertTrue(mouth.said.isEmpty())
    }

    @Test
    fun `a run that never starts points at the chat screen`() {
        val s = session()
        s.start()
        s.heard("hello")
        clock += VoiceSession.START_WAIT_MS
        s.tick()
        assertIs<VoicePhase.Failed>(s.phase.value)
    }

    @Test
    fun `an error ending the run is spoken`() {
        val s = session()
        s.start()
        s.heard("hello")
        s.onAgent(VoiceAgentView(running = true))
        s.onAgent(VoiceAgentView(running = false, error = "Rate limited"))
        assertTrue("Rate limited" in mouth.lastText)
    }

    @Test
    fun `a run without a reply ends after a wait`() {
        val s = session()
        s.start()
        s.heard("hello")
        s.onAgent(VoiceAgentView(running = true))
        s.onAgent(VoiceAgentView(running = false))
        assertEquals(VoicePhase.Working("Finishing"), s.phase.value)
        clock += VoiceSession.REPLY_WAIT_MS
        s.tick()
        assertEquals(VoiceSession.NO_REPLY, mouth.lastText)
    }

    @Test
    fun `tapping while Locus talks stops it and listens (barge-in by tap)`() {
        val s = session()
        s.start()
        s.heard("hi")
        s.onAgent(VoiceAgentView(running = true))
        s.onAgent(VoiceAgentView(replyKey = "r1", replyText = "A long answer."))
        assertIs<VoicePhase.Speaking>(s.phase.value)
        val stops = mouth.stops
        s.tap()
        assertEquals(stops + 1, mouth.stops)
        assertEquals(VoicePhase.Listening("", ListenFor.MESSAGE), s.phase.value)
    }

    @Test
    fun `speaking over Locus interrupts it, but its own echo doesn't`() {
        val s = session()
        s.start()
        s.heard("hi")
        s.onAgent(VoiceAgentView(running = true))
        s.onAgent(VoiceAgentView(replyKey = "r1", replyText = "The weather in Paris is sunny today."))
        val listensBefore = ears.listens
        assertTrue(listensBefore > 0)
        // Its own words through the speaker: ignored.
        s.heardPartial("weather in Paris")
        assertIs<VoicePhase.Speaking>(s.phase.value)
        // The user: two words not in the reply.
        val stops = mouth.stops
        s.heardPartial("stop, what about London")
        assertEquals(stops + 1, mouth.stops)
        assertEquals(VoicePhase.Listening("stop, what about London", ListenFor.MESSAGE), s.phase.value)
        s.heard("what about London")
        assertEquals("what about London", agent.sent.last())
    }

    @Test
    fun `barge-in by voice can be switched off`() {
        val s = session(VoiceSettings(interruptByVoice = false, spokenStatus = false))
        s.start()
        s.heard("hi")
        s.onAgent(VoiceAgentView(running = true))
        val listens = ears.listens
        s.onAgent(VoiceAgentView(replyKey = "r1", replyText = "Answer."))
        assertEquals(listens, ears.listens)
    }

    @Test
    fun `speaking while the agent works sends a mid-task note`() {
        val s = session()
        s.start()
        s.heard("build the app")
        s.onAgent(VoiceAgentView(running = true, activeTool = "run_command"))
        assertEquals(VoicePhase.Working("Running a command"), s.phase.value)
        s.tap()
        s.heard("also run the tests")
        assertEquals(listOf("build the app", "also run the tests"), agent.sent)
        assertEquals(VoiceSession.NOTE_ACK, mouth.lastText)
        s.done()
        assertIs<VoicePhase.Working>(s.phase.value)
    }

    @Test
    fun `spoken status says what tools run, not too often`() {
        val s = session(VoiceSettings(spokenStatus = true))
        s.start()
        s.heard("build it")
        s.onAgent(VoiceAgentView(running = true, activeTool = "run_command"))
        assertEquals("Working on it. Running a command.", mouth.lastText)
        s.done()
        s.onAgent(VoiceAgentView(running = true, activeTool = "web_search"))
        assertEquals(1, mouth.said.size)
        clock += VoiceSession.STATUS_GAP_MS
        s.onAgent(VoiceAgentView(running = true, activeTool = "web_read"))
        assertEquals("Reading a page.", mouth.lastText)
    }

    @Test
    fun `a plain Ask approval is answered by allow then confirm`() {
        val s = session()
        s.start()
        s.heard("list files")
        s.onAgent(VoiceAgentView(running = true, approval = approval()))
        assertEquals("Locus wants to: Run ls -la. Say allow, or deny.", mouth.lastText)
        s.done()
        assertEquals(VoicePhase.Listening("", ListenFor.APPROVAL), s.phase.value)
        s.heard("yes allow it")
        assertTrue(agent.answers.isEmpty(), "allow alone never approves")
        assertEquals(VoiceApprovals.CONFIRM_PROMPT, mouth.lastText)
        s.done()
        assertEquals(VoicePhase.Listening("", ListenFor.CONFIRM), s.phase.value)
        s.heard("confirm")
        assertEquals(listOf("c1" to true), agent.answers)
    }

    @Test
    fun `deny needs no confirmation`() {
        val s = session()
        s.start()
        s.onAgent(VoiceAgentView(running = true, approval = approval()))
        s.done()
        s.heard("no, don't")
        assertEquals(listOf("c1" to false), agent.answers)
    }

    @Test
    fun `yes at the confirm step is not enough, and unclear answers go to the screen`() {
        val s = session()
        s.start()
        s.onAgent(VoiceAgentView(running = true, approval = approval()))
        s.done()
        s.heard("allow")
        s.done()
        s.heard("yes")
        assertTrue(agent.answers.isEmpty())
        s.done()
        s.heard("banana")
        assertTrue(agent.answers.isEmpty())
        assertEquals(VoiceApprovals.UNCLEAR_TAP, mouth.lastText)
        s.done()
        assertIs<VoicePhase.TapApproval>(s.phase.value)
    }

    @Test
    fun `a tainted, reviewer-flagged, payment-like or unknown approval refuses voice`() {
        for (request in listOf(
            approval(flags = setOf(ApprovalFlag.TAINTED)),
            approval(note = "Sends the file to an unknown host"),
            approval(flags = setOf(ApprovalFlag.MUST_CONFIRM)),
            approval(flags = null),
        )) {
            val e = Ears()
            val m = Mouth()
            val a = Agent()
            val s = VoiceSession(e, m, a, { clock }, VoiceSettings(spokenStatus = false))
            s.start()
            s.onAgent(VoiceAgentView(running = true, approval = request))
            assertTrue("Tap Allow or Deny" in m.lastText, m.lastText)
            s.spoken(m.lastId)
            assertIs<VoicePhase.TapApproval>(s.phase.value)
            // Whatever is said now changes nothing.
            s.heard("allow")
            s.heard("confirm")
            assertTrue(a.answers.isEmpty())
            assertIs<VoicePhase.TapApproval>(s.phase.value)
        }
    }

    @Test
    fun `the approval prompt's own allow coming back through the mic can't approve`() {
        val s = session()
        s.start()
        s.onAgent(VoiceAgentView(running = true, approval = approval()))
        // Barge-in ear open while the prompt plays; it hears the prompt.
        s.heard("allow")
        s.heardPartial("say allow or deny")
        assertIs<VoicePhase.Speaking>(s.phase.value)
        assertTrue(agent.answers.isEmpty())
    }

    @Test
    fun `an approval answered on screen drops the voice prompt`() {
        val s = session()
        s.start()
        s.onAgent(VoiceAgentView(running = true, approval = approval()))
        s.done()
        assertEquals(VoicePhase.Listening("", ListenFor.APPROVAL), s.phase.value)
        s.onAgent(VoiceAgentView(running = true))
        assertIs<VoicePhase.Working>(s.phase.value)
    }

    @Test
    fun `long silence turns the microphone off and waits for a tap`() {
        val s = session()
        s.start()
        clock += 5_000
        s.hearFailed(HearError.NoSpeech)
        assertEquals(VoicePhase.Listening("", ListenFor.MESSAGE), s.phase.value)
        clock += VoiceSession.SILENCE_END_MS
        s.hearFailed(HearError.NoSpeech)
        assertEquals(VoicePhase.Idle(VoiceSession.NOTHING_HEARD), s.phase.value)
        s.tap()
        assertIs<VoicePhase.Listening>(s.phase.value)
    }

    @Test
    fun `recognizer errors stop the loop until a tap, busy retries once`() {
        val s = session()
        s.start()
        val listens = ears.listens
        s.hearFailed(HearError.Busy)
        assertEquals(listens + 1, ears.listens)
        s.hearFailed(HearError.Busy)
        assertIs<VoicePhase.Failed>(s.phase.value)
        s.tap()
        assertIs<VoicePhase.Listening>(s.phase.value)
        s.hearFailed(HearError.Permission)
        assertEquals(VoicePhase.Failed(VoiceSession.MIC_NEEDED, VoiceFix.MIC_PERMISSION), s.phase.value)
    }

    @Test
    fun `speech failure stops the loop`() {
        val s = session()
        s.start()
        s.heard("hi")
        s.onAgent(VoiceAgentView(running = true))
        s.onAgent(VoiceAgentView(replyKey = "r1", replyText = "Answer."))
        s.speakFailed(mouth.lastId, "No engine")
        assertEquals(VoicePhase.Failed("No engine"), s.phase.value)
    }

    @Test
    fun `losing audio focus to a call pauses and resumes the reply when it ends`() {
        val s = session()
        s.start()
        s.heard("hi")
        s.onAgent(VoiceAgentView(running = true))
        s.onAgent(VoiceAgentView(replyKey = "r1", replyText = "Answer."))
        clock += 10_000
        s.focusLost(transient = true)
        assertIs<VoicePhase.Paused>(s.phase.value)
        s.focusGained()
        assertEquals("Answer.", mouth.lastText)
        assertIs<VoicePhase.Speaking>(s.phase.value)
    }

    @Test
    fun `a permanent focus loss waits for a tap`() {
        val s = session()
        s.start()
        clock += 10_000
        s.focusLost(transient = false)
        s.focusGained()
        assertIs<VoicePhase.Paused>(s.phase.value)
        s.tap()
        assertIs<VoicePhase.Listening>(s.phase.value)
    }

    @Test
    fun `the recognizer taking focus as it starts isn't a pause`() {
        val s = session()
        s.start()
        s.focusLost(transient = true)
        assertIs<VoicePhase.Listening>(s.phase.value)
    }

    @Test
    fun `going to the background pauses and ending stops everything`() {
        val s = session()
        s.start()
        s.backgrounded()
        assertIs<VoicePhase.Paused>(s.phase.value)
        s.end()
        assertEquals(VoicePhase.Ended, s.phase.value)
        s.onAgent(VoiceAgentView(running = true, approval = approval()))
        assertEquals(VoicePhase.Ended, s.phase.value)
    }

    @Test
    fun `an open question is read and the answer sent`() {
        val s = session()
        s.start()
        s.heard("plan a trip")
        s.onAgent(VoiceAgentView(running = true, question = "Which city?", questionId = "q1"))
        assertEquals("Which city?", mouth.lastText)
        s.done()
        s.heard("Lisbon")
        assertEquals("Lisbon", agent.sent.last())
        assertIs<VoicePhase.Working>(s.phase.value)
    }

    @Test
    fun `recognizer error codes map to voice errors`() {
        assertEquals(HearError.NoSpeech, hearError(7))
        assertEquals(HearError.NoSpeech, hearError(6))
        assertEquals(HearError.Busy, hearError(8))
        assertEquals(HearError.Permission, hearError(9))
        assertIs<HearError.Failed>(hearError(2))
        assertEquals(HearError.NothingHeard, hearError(5))
        assertEquals(VoiceFix.SPEECH_SETTINGS, (hearError(12) as HearError.Failed).fix)
        // A code Android adds later still gets words a person can act on.
        assertIs<HearError.Failed>(hearError(99))
    }

    @Test
    fun `nothing heard waits for a tap without calling it a failure`() {
        val s = session()
        s.start()
        s.hearFailed(HearError.NothingHeard)
        assertEquals(VoicePhase.Idle(VoiceSession.NOTHING_HEARD), s.phase.value)
        s.tap()
        assertIs<VoicePhase.Listening>(s.phase.value)
    }

    @Test
    fun `every recognizer error says what to do`() {
        (0..20).map(::hearError).filterIsInstance<HearError.Failed>().forEach { e ->
            assertTrue(e.message.endsWith(".") && e.message.split(". ").size >= 2, "no next step in: ${e.message}")
        }
    }
}
