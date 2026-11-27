package dev.locus.feature.chat.voice

import android.content.res.Resources
import dev.locus.core.agent.ApprovalRequest
import dev.locus.core.designsystem.localizedApprovalSummary
import dev.locus.core.designsystem.localizedToolActivity
import dev.locus.core.model.toolActivity
import dev.locus.feature.chat.R
import dev.locus.feature.chat.SpeechWords
import dev.locus.feature.chat.speechWords
import java.text.Normalizer
import java.util.Locale

/**
 * What voice mode itself says and shows, and the answers it understands, in the app's language
 * (docs/design/localization.md, docs/design/voice-mode.md). The defaults are the English, which the tests
 * compare; [voiceWords] builds the same from the module's strings (VoiceWordsTest checks the English
 * strings say exactly these). Templates take `%1$s`, `%2$s` in order ([fill]). The model's own words
 * (replies, questions, errors from the provider) are said as they came.
 */
internal data class VoiceWords(
    val tapToTalk: String = "Tap to talk",
    val nothingHeard: String = "Didn't hear anything. Tap to talk",
    val noteAck: String = "Got it. Locus will read that after this step.",
    val noReply: String = "The task ended without a reply to read.",
    val micNeeded: String = "Voice mode needs the microphone. Allow it, then tap to try again.",
    val pausedTransient: String = "Paused while another app uses audio. Locus goes on when it's done.",
    val pausedFocus: String = "Paused because another app is playing audio. Tap to go on.",
    val pausedBackground: String = "Paused while Locus was in the background. Tap to go on.",
    val didNotStart: String = "Locus didn't start on that.",
    /** [VoiceSession.needsScreen]: the chat's reason, then what to do. */
    val needsScreen: String = "%1\$s Check the chat, then tap to talk again.",
    val recognizerBusy: String = "Another app is using speech recognition. Tap to try again.",
    val answering: String = "Answering",
    val sending: String = "Sending",
    val finishing: String = "Finishing",
    val thinking: String = "Thinking",
    val denied: String = "Denied.",
    val allowed: String = "Allowed.",
    val notClear: String = "Voice didn't get a clear answer.",
    val wentWrong: String = "Something went wrong. %1\$s",
    val firstStatus: String = "Working on it. %1\$s.",
    val status: String = "%1\$s.",
    // Why an approval needs a tap (VoiceApprovals.tapReason).
    val tapMemory: String = "This changes what Locus remembers, and the note is on the screen, so it needs a look and a tap.",
    val tapReviewer: String = "The safety reviewer flagged this, so it needs a tap.",
    val tapUnknown: String = "This needs a tap.",
    val tapMustConfirm: String = "This looks like a payment or another action that must be confirmed, so it needs a tap.",
    val tapTainted: String = "Locus read outside content during this task, so this needs a tap.",
    val tapPrivateData: String = "This task read your private data and this opens a new address, so it needs a tap.",
    val tapScope: String = "This goes beyond what the task was set up to do, so it needs a tap.",
    // The approval prompts (VoiceApprovals).
    val locusWants: String = "Locus wants to: %1\$s",
    val helperWants: String = "%1\$s wants to: %2\$s",
    val askPrompt: String = "%1\$s. Say allow, or deny.",
    val confirmPrompt: String = "To allow it, say confirm. Or say no.",
    val tapPrompt: String = "%1\$s. %2\$s Tap Allow or Deny on the screen.",
    val unclearTap: String = "I didn't catch that. Tap Allow or Deny on the screen.",
    val unclearAgain: String = "Sorry, say allow, or deny.",
    val unclearConfirm: String = "Sorry, say confirm to allow it, or no.",
    // The agent's question (voiceView).
    val asks: String = "%1\$s asks: %2\$s",
    val youCanSay: String = "%1\$s You can say %2\$s.",
    val listJoin: String = "%1\$s, %2\$s",
    val listLast: String = "%1\$s, or %2\$s",
    // The recognizer and the voice (AndroidVoice, hearError).
    val noRecognizer: String = "This phone has no speech recognizer. Install one (such as Speech Services by Google), then tap to try again.",
    val couldNotStart: String = "Couldn't start speech recognition. Tap to try again.",
    val needsConnection: String = "Speech recognition needs a connection right now. Check it, then tap to try again.",
    val serviceBusy: String = "The speech service is busy. Wait a moment, then tap to try again.",
    val noLanguage: String = "Speech recognition on this phone doesn't have your language yet. Add it in the phone's speech settings.",
    val noMicrophone: String = "Couldn't record from the microphone. Another app may be using it. Tap to try again.",
    val recognizerClosed: String = "The speech recognizer closed. Tap to start it again.",
    val listeningStopped: String = "Listening stopped. Tap to try again.",
    val voiceDownloading: String = "The voice is still downloading. Tap to try again.",
    val couldNotSpeak: String = "Couldn't speak the reply. Tap to try again.",
    val noEngine: String = "No text-to-speech engine is installed. Add one (such as Speech Services by Google), then try again.",
    val onDevice: String = "On-device recognition",
    val onDeviceNote: String = "Speech is turned into text on this phone.",
    val systemRecognizer: String = "System speech recognizer",
    val preferOfflineNote: String = "Asked to stay on the phone; whether it does is up to that app.",
    val onlineNote: String = "This recognizer may send your voice to its own service.",
    /** What the agent is doing, from the tool it runs ("Searching the web"). */
    val activity: (String) -> String = ::toolActivity,
    /** The answers voice approvals understand ([VoiceApprovals.parse]); always the English ones too. */
    val answers: VoiceAnswers = VoiceAnswers(),
    /** What replies' code, tables and links are read as. */
    val speech: SpeechWords = SpeechWords(),
    /** An approval's line as said: the tool's line in the app's language when it has one ([localizedApprovalSummary]). */
    val approvalLine: (ApprovalRequest) -> String = { it.summary },
) {
    /** [template] with [values] in place of `%1$s`, `%2$s`…, the way a string resource is formatted. */
    fun fill(template: String, vararg values: Any): String = String.format(Locale.ROOT, template, *values)
}

/**
 * The words that answer an approval by voice, lower case and without accents. [negators] are the ones
 * that, right before an allow or confirm word, turn it into a no ("don't allow", "no permitir", "不允许").
 */
internal data class VoiceAnswers(
    val yes: Set<String> = setOf("yes", "yeah", "yep", "yup", "allow", "allowed", "approve", "approved", "ok", "okay", "sure", "accept"),
    val yesPhrases: Set<String> = setOf("go ahead", "do it"),
    val confirms: Set<String> = setOf("confirm", "confirmed"),
    val negations: Set<String> = setOf("no", "nope", "nah", "deny", "denied", "don't", "dont", "not", "cancel", "stop", "reject", "never", "decline"),
    val negators: Set<String> = setOf("don't", "dont", "not", "never"),
) {
    /** These answers and [other]'s: the English ones are always understood, whatever the app's language. */
    operator fun plus(other: VoiceAnswers) = VoiceAnswers(
        yes + other.yes,
        yesPhrases + other.yesPhrases,
        confirms + other.confirms,
        negations + other.negations,
        negators + other.negators,
    )

    companion object {
        /** Lower case, without accents ("Sí" → "si", "não" → "nao"), so either spelling is understood. */
        fun normalize(text: String): String =
            Normalizer.normalize(text.lowercase(Locale.ROOT).replace('’', '\''), Normalizer.Form.NFD).replace(MARKS, "")

        private val MARKS = Regex("\\p{Mn}+")
    }
}

/** [VoiceWords] in the language of [resources]. */
internal fun voiceWords(resources: Resources): VoiceWords =
    sessionWords(resources, VoiceWords()).let { approvalWords(resources, it) }.let { deviceWords(resources, it) }.copy(
        activity = { tool -> localizedToolActivity(resources, tool) },
        speech = speechWords(resources),
        approvalLine = { localizedApprovalSummary(resources, it.summary, it.summaryText) },
        answers = VoiceAnswers() + VoiceAnswers(
            yes = answerWords(resources, R.string.chat_voice_answer_yes),
            yesPhrases = emptySet(),
            confirms = answerWords(resources, R.string.chat_voice_answer_confirm),
            negations = answerWords(resources, R.string.chat_voice_answer_no),
            negators = answerWords(resources, R.string.chat_voice_answer_negators),
        ),
    )

/** A `chat_voice_answer_*` list: its words, as [VoiceAnswers.normalize] matches them. */
private fun answerWords(resources: Resources, id: Int): Set<String> =
    resources.getString(id).split(' ').map { VoiceAnswers.normalize(it.trim()) }.filter { it.isNotEmpty() }.toSet()

/** What the loop itself says: listening, pauses, status. */
private fun sessionWords(resources: Resources, words: VoiceWords): VoiceWords {
    fun s(id: Int) = resources.getString(id)
    return words.copy(
        tapToTalk = s(R.string.chat_voice_say_tap_to_talk),
        nothingHeard = s(R.string.chat_voice_say_nothing_heard),
        noteAck = s(R.string.chat_voice_say_note_ack),
        noReply = s(R.string.chat_voice_say_no_reply),
        micNeeded = s(R.string.chat_voice_say_mic_needed),
        pausedTransient = s(R.string.chat_voice_say_paused_transient),
        pausedFocus = s(R.string.chat_voice_say_paused_focus),
        pausedBackground = s(R.string.chat_voice_say_paused_background),
        didNotStart = s(R.string.chat_voice_say_did_not_start),
        needsScreen = s(R.string.chat_voice_say_needs_screen),
        recognizerBusy = s(R.string.chat_voice_say_recognizer_busy),
        answering = s(R.string.chat_voice_say_answering),
        sending = s(R.string.chat_voice_say_sending),
        finishing = s(R.string.chat_voice_say_finishing),
        thinking = s(R.string.chat_voice_say_thinking),
        denied = s(R.string.chat_voice_say_denied),
        allowed = s(R.string.chat_voice_say_allowed),
        notClear = s(R.string.chat_voice_say_not_clear),
        wentWrong = s(R.string.chat_voice_say_went_wrong),
        firstStatus = s(R.string.chat_voice_say_first_status),
        status = s(R.string.chat_voice_say_status),
    )
}

/** Approvals and the agent's questions. */
private fun approvalWords(resources: Resources, words: VoiceWords): VoiceWords {
    fun s(id: Int) = resources.getString(id)
    return words.copy(
        tapMemory = s(R.string.chat_voice_say_tap_memory),
        tapReviewer = s(R.string.chat_voice_say_tap_reviewer),
        tapUnknown = s(R.string.chat_voice_say_tap_unknown),
        tapMustConfirm = s(R.string.chat_voice_say_tap_must_confirm),
        tapTainted = s(R.string.chat_voice_say_tap_tainted),
        tapPrivateData = s(R.string.chat_voice_say_tap_private_data),
        tapScope = s(R.string.chat_voice_say_tap_scope),
        locusWants = s(R.string.chat_voice_say_locus_wants),
        helperWants = s(R.string.chat_voice_say_helper_wants),
        askPrompt = s(R.string.chat_voice_say_ask_prompt),
        confirmPrompt = s(R.string.chat_voice_say_confirm_prompt),
        tapPrompt = s(R.string.chat_voice_say_tap_prompt),
        unclearTap = s(R.string.chat_voice_say_unclear_tap),
        unclearAgain = s(R.string.chat_voice_say_unclear_again),
        unclearConfirm = s(R.string.chat_voice_say_unclear_confirm),
        asks = s(R.string.chat_voice_say_asks),
        youCanSay = s(R.string.chat_voice_say_you_can_say),
        listJoin = s(R.string.chat_voice_say_list_join),
        listLast = s(R.string.chat_voice_say_list_last),
    )
}

/** The recognizer and the voice. */
private fun deviceWords(resources: Resources, words: VoiceWords): VoiceWords {
    fun s(id: Int) = resources.getString(id)
    return words.copy(
        noRecognizer = s(R.string.chat_voice_say_no_recognizer),
        couldNotStart = s(R.string.chat_voice_say_could_not_start),
        needsConnection = s(R.string.chat_voice_say_needs_connection),
        serviceBusy = s(R.string.chat_voice_say_service_busy),
        noLanguage = s(R.string.chat_voice_say_no_language),
        noMicrophone = s(R.string.chat_voice_say_no_microphone),
        recognizerClosed = s(R.string.chat_voice_say_recognizer_closed),
        listeningStopped = s(R.string.chat_voice_say_listening_stopped),
        voiceDownloading = s(R.string.chat_voice_say_voice_downloading),
        couldNotSpeak = s(R.string.chat_voice_say_could_not_speak),
        noEngine = s(R.string.chat_voice_say_no_engine),
        onDevice = s(R.string.chat_voice_say_on_device),
        onDeviceNote = s(R.string.chat_voice_say_on_device_note),
        systemRecognizer = s(R.string.chat_voice_say_system_recognizer),
        preferOfflineNote = s(R.string.chat_voice_say_prefer_offline_note),
        onlineNote = s(R.string.chat_voice_say_online_note),
    )
}
