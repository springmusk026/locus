package dev.locus.core.model

import kotlinx.serialization.Serializable

/**
 * Settings → Voice (docs/design/voice-mode.md). The voice itself and the speech engine are Android's
 * Text-to-speech settings, which Locus opens rather than copies; only the speed is Locus's own.
 */
@Serializable
data class VoiceSettings(
    /** Text-to-speech speed for read-aloud and voice mode; 1.0 is the engine's normal speed. */
    val speechRate: Float = 1.0f,
    /** In voice mode, listen again as soon as a reply has been read. Off: tap to talk each time. */
    val autoListen: Boolean = true,
    /** In voice mode, say a short status while tools run ("Working on it. Running a command."). */
    val spokenStatus: Boolean = true,
    /** In voice mode, speaking while Locus talks stops it and starts listening (tapping always does). */
    val interruptByVoice: Boolean = true,
) {
    /** [speechRate] within what the speed control offers. */
    val rate: Float get() = speechRate.coerceIn(MIN_RATE, MAX_RATE)

    companion object {
        const val MIN_RATE = 0.5f
        const val MAX_RATE = 2.0f
    }
}
