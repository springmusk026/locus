package dev.locus.feature.chat.voice

import dev.locus.core.agent.ApprovalFlag
import dev.locus.core.agent.ApprovalRequest
import dev.locus.core.model.ToolRisk
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

    @Test fun failed() = voice("voice-failed", VoicePhase.Failed("Speech recognition failed. Tap to try again."))

    @Test fun failedMic() = voice("voice-failed-mic", VoicePhase.Failed("Locus can't use the microphone. Allow it in Settings → Apps → Locus → Permissions."))

    @Test fun failedNetwork() = voice("voice-failed-network", VoicePhase.Failed("No network connection. This phone's speech recognition needs the internet for now; tap to try again."))
}
