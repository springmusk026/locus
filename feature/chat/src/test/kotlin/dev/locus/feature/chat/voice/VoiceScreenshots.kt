package dev.locus.feature.chat.voice

import dev.locus.core.agent.ApprovalFlag
import dev.locus.core.agent.ApprovalRequest
import dev.locus.core.model.ToolRisk
import dev.locus.core.model.VoiceSettings
import dev.locus.core.screenshot.ScreenshotTest
import org.junit.Test

/** Voice mode's screen in every phase, and with an approval waiting. */
class VoiceScreenshots : ScreenshotTest() {

    private val engine = VoiceEngine("On-device recognition", true, "Speech is turned into text on this phone.")

    private val plainApproval = ApprovalRequest(
        conversationId = "c", callId = "k1", toolName = "device_calendar_event", risk = ToolRisk.WRITE,
        summary = "Add “Dentist” to your calendar on Thursday 6 November, 9:30–10:15", argumentsJson = "{}", flags = emptySet(),
    )

    private val tapApproval = ApprovalRequest(
        conversationId = "c", callId = "k2", toolName = "browser_click", risk = ToolRisk.NETWORK,
        summary = "Click “Pay €42.00” on opentable.com", argumentsJson = "{}", flags = setOf(ApprovalFlag.MUST_CONFIRM),
    )

    private fun voice(name: String, phase: VoicePhase, level: Float = 0f, approval: ApprovalRequest? = null) = snap(name) {
        VoiceScreen(phase, level, engine, approval, onTap = {}, onAnswer = { _, _ -> }, onExit = {})
    }

    @Test fun idle() = voice("voice-idle", VoicePhase.Idle("Tap to talk"))

    @Test fun listening() = voice("voice-listening", VoicePhase.Listening("Remind me to water the basil tomorrow at", ListenFor.MESSAGE), level = 0.6f)

    @Test fun working() = voice("voice-working", VoicePhase.Working("Checking your calendar"))

    @Test
    fun speaking() = voice(
        "voice-speaking",
        VoicePhase.Speaking(
            "You're free on Thursday after 2. I've put the dentist at 9:30, and the plumber can come between 3 and 5, so you'll be home for both.",
            SpeechKind.REPLY,
        ),
    )

    @Test fun approvalByVoice() = voice("voice-approval", VoicePhase.Listening("", ListenFor.APPROVAL), approval = plainApproval)

    @Test
    fun tapApproval() = voice(
        "voice-tap-approval",
        VoicePhase.TapApproval(tapApproval, "This looks like a payment or another action that must be confirmed, so it needs a tap."),
        approval = tapApproval,
    )

    @Test fun paused() = voice("voice-paused", VoicePhase.Paused("Another app is playing audio. Tap to go on."))

    // Failures: the phase a real session reaches for each recognizer error, so the words are the app's own.

    private class Ears : VoiceEars {
        override fun listen() = Unit
        override fun stop() = Unit
        override fun cancel() = Unit
    }

    private class Mouth : VoiceMouth {
        var lastId = 0
        override fun speak(text: String, id: Int) { lastId = id }
        override fun stop() = Unit
    }

    private class Agent : VoiceAgent {
        override fun send(text: String) = Unit
        override fun answer(callId: String, approved: Boolean) = Unit
    }

    /** A session listening for a message, after [errors] (android.speech.SpeechRecognizer.ERROR_* codes) came back. */
    private fun afterErrors(vararg errors: Int, silentFor: Long = 0L): VoicePhase {
        var clock = 0L
        val s = VoiceSession(Ears(), Mouth(), Agent(), { clock }, VoiceSettings(spokenStatus = false))
        s.start()
        clock += silentFor
        errors.forEach { s.hearFailed(hearError(it)) }
        return s.phase.value
    }

    /** A code Android adds later, or one Locus doesn't map: plain words and Try again. */
    @Test fun failed() = voice("voice-failed", afterErrors(99))

    /** ERROR_CLIENT, what the recognizer reports when it heard nothing it could use: not a failure. */
    @Test fun nothingHeard() = voice("voice-nothing-heard", afterErrors(5))

    @Test fun failedServer() = voice("voice-failed-server", afterErrors(4)) // ERROR_SERVER

    @Test fun failedNoRecognizer() = voice(
        "voice-failed-no-recognizer",
        VoicePhase.Failed("This phone has no speech recognizer. Install one (such as Speech Services by Google), then tap to try again.", VoiceFix.SPEECH_SETTINGS),
    )

    @Test fun failedMic() = voice("voice-failed-mic", afterErrors(9)) // ERROR_INSUFFICIENT_PERMISSIONS

    @Test fun failedNetwork() = voice("voice-failed-network", afterErrors(2)) // ERROR_NETWORK

    @Test fun failedBusy() = voice("voice-failed-busy", afterErrors(8, 8)) // ERROR_RECOGNIZER_BUSY, after the one retry

    @Test fun failedLanguage() = voice("voice-failed-language", afterErrors(12)) // ERROR_LANGUAGE_NOT_SUPPORTED

    @Test fun failedAudio() = voice("voice-failed-audio", afterErrors(3)) // ERROR_AUDIO

    /** No speech (ERROR_NO_MATCH, ERROR_SPEECH_TIMEOUT) isn't a failure: after a long silence the microphone goes off and it waits for a tap. */
    @Test fun noSpeech() = voice("voice-no-speech", afterErrors(7, silentFor = 2 * 60_000L))

    /** Something only the chat screen can settle: voice mode stops and says so. */
    @Test
    fun needsScreen() {
        val s = VoiceSession(Ears(), Mouth(), Agent(), { 0L }, VoiceSettings(spokenStatus = false))
        s.start()
        s.needsScreen("deepseek-v4 can't take this message.")
        voice("voice-needs-screen", s.phase.value)
    }

    /** The recognizer the phone has when it has no on-device one: the screen says it may send audio away. */
    @Test
    fun systemRecognizer() = snap("voice-listening-system-recognizer") {
        VoiceScreen(
            VoicePhase.Listening("What's on my calendar", ListenFor.MESSAGE), 0.4f,
            VoiceEngine("Speech Services by Google", false, "This recognizer may send your voice to its own service."),
            null, onTap = {}, onAnswer = { _, _ -> }, onExit = {},
        )
    }
}
