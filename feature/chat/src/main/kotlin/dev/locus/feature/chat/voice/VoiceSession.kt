package dev.locus.feature.chat.voice

import dev.locus.core.agent.ApprovalRequest
import dev.locus.core.model.VoiceSettings
import dev.locus.core.model.toolActivity
import dev.locus.feature.chat.SpeechText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The speech recognizer, as voice mode uses it. Results come back through [VoiceSession]'s `heard…` calls. */
internal interface VoiceEars {
    /** Starts one recognition session (it ends by itself after the speaker pauses). */
    fun listen()

    /** Stops listening and delivers what was heard so far. */
    fun stop()

    /** Stops listening and drops what was heard. */
    fun cancel()
}

/** Text-to-speech, as voice mode uses it. Completion comes back through [VoiceSession.spoken]. */
internal interface VoiceMouth {
    fun speak(text: String, id: Int)
    fun stop()
}

/** The chat's agent: the same [send] the composer uses (it queues a mid-task note while a task runs, and answers an open question). */
internal interface VoiceAgent {
    fun send(text: String)
    fun answer(callId: String, approved: Boolean)
}

/** Why a recognition session ended without words. Mapped from SpeechRecognizer's codes by [hearError]. */
internal sealed interface HearError {
    /** Nothing was said (no match, or speech timeout). */
    data object NoSpeech : HearError
    data object Busy : HearError
    data object Permission : HearError

    /**
     * The recognizer gave up without a reason it names (ERROR_CLIENT, what it reports when it heard
     * nothing it could use): not a fault, so voice mode waits for a tap instead of saying it failed.
     */
    data object NothingHeard : HearError
    data class Failed(val message: String, val fix: VoiceFix = VoiceFix.RETRY) : HearError
}

/** What the person can do about a [VoicePhase.Failed]; the screen shows it as a button. */
internal enum class VoiceFix {
    /** Tap to try again: nothing to change first. */
    RETRY,

    /** Allow the microphone in Locus's app settings. */
    MIC_PERMISSION,

    /** Install or set up speech recognition (a recognizer, a language) in Android's settings. */
    SPEECH_SETTINGS,

    /** Something on the chat screen needs an answer first: go back to it. */
    CHAT,
}

/** What the chat is doing, as voice mode needs it; built from the chat's state by [voiceView]. */
internal data class VoiceAgentView(
    val running: Boolean = false,
    /** The approval to answer now (the oldest). */
    val approval: ApprovalRequest? = null,
    /** The agent's open question (`ask_user`), as speech, and its call id. */
    val question: String? = null,
    val questionId: String? = null,
    /** The latest reply in the chat (key and Markdown). */
    val replyKey: String? = null,
    val replyText: String? = null,
    val activeTool: String? = null,
    val error: String? = null,
)

/** What voice mode is listening for. */
internal enum class ListenFor { MESSAGE, APPROVAL, CONFIRM }

/** What voice mode is saying. */
internal enum class SpeechKind { REPLY, STATUS, PROMPT, NOTICE }

/** What voice mode is doing; the screen draws this. */
internal sealed interface VoicePhase {
    /** Waiting for a tap to talk ([hint] says so). */
    data class Idle(val hint: String) : VoicePhase

    /** The microphone is on. [partial] is what has been recognised so far. */
    data class Listening(val partial: String, val purpose: ListenFor) : VoicePhase

    /** The agent is working; [status] is what it's doing. Tapping talks to it (a mid-task note). */
    data class Working(val status: String) : VoicePhase

    data class Speaking(val text: String, val kind: SpeechKind) : VoicePhase

    /** An approval only a tap can answer, and why. */
    data class TapApproval(val request: ApprovalRequest, val reason: String) : VoicePhase

    data class Paused(val reason: String) : VoicePhase

    /** Something stopped the loop; tapping tries again, and [fix] says what else helps. */
    data class Failed(val message: String, val fix: VoiceFix = VoiceFix.RETRY) : VoicePhase

    data object Ended : VoicePhase
}

/**
 * Voice mode's loop (docs/design/voice-mode.md): listen, send to the chat's agent, speak the reply,
 * listen again; with barge-in, approvals by voice for plain Ask-level requests only, mid-task notes,
 * spoken status, and pauses for audio focus and the background. Pure and single-threaded: the Android
 * adapters call it on the main thread, tests call it directly with fakes and a fake clock.
 */
internal class VoiceSession(
    private val ears: VoiceEars,
    private val mouth: VoiceMouth,
    private val agent: VoiceAgent,
    private val now: () -> Long,
    var settings: VoiceSettings = VoiceSettings(),
) {
    private val _phase = MutableStateFlow<VoicePhase>(VoicePhase.Idle(TAP_TO_TALK))
    val phase: StateFlow<VoicePhase> = _phase

    private val _level = MutableStateFlow(0f)

    /** The microphone's level, 0–1, while listening; 0 otherwise. */
    val level: StateFlow<Float> = _level

    private var view = VoiceAgentView()
    private var started = false

    // The turn voice mode is waiting on: speak the first reply after [baseline].
    private var awaitingReply = false
    private var baseline: String? = null
    private var runEndedAt: Long? = null

    // Whether the run for what was sent has been seen (so an earlier run's error isn't read as this one's).
    private var sawRun = false
    private var sentAt = 0L

    // Approvals: the one being handled, whether "allow" was heard, and how many unclear answers.
    private var approvalId: String? = null
    private var confirming = false
    private var unclear = 0

    private var questionId: String? = null

    // Spoken status, at most every [STATUS_GAP_MS], and only when it changes.
    private var statusLabel: String? = null
    private var statusAt = 0L

    // Speech: what's being said, and what comes after it.
    private var speechId = 0
    private var speaking: VoicePhase.Speaking? = null
    private var afterSpeech: () -> Unit = {}

    /** Whether the recognizer runs during speech, for barge-in. */
    private var bargeListening = false

    private var busyRetried = false
    private var listenStartedAt = 0L
    private var lastHeardAt = 0L

    /** Paused for audio focus loss that ends by itself (a call): resume when focus comes back. */
    private var resumeOnFocus = false

    /** A reply interrupted by a pause, said again on resume. */
    private var interrupted: VoicePhase.Speaking? = null

    private val phaseNow: VoicePhase get() = _phase.value

    // ---------------------------------------------------------------- lifecycle

    fun start() {
        if (started) return
        started = true
        lastHeardAt = now()
        // A task already running when voice mode opens: say its reply when it ends.
        if (view.running) {
            awaitingReply = true
            sawRun = true
            baseline = view.replyKey
        }
        next()
    }

    /** The Stop button, or leaving the screen. The agent's task goes on in the chat. */
    fun end() {
        if (phaseNow == VoicePhase.Ended) return
        silence()
        _phase.value = VoicePhase.Ended
    }

    /** Audio focus lost: a call, or another app's audio. [transient]: it comes back by itself. */
    fun focusLost(transient: Boolean) {
        // A speech recognizer may take audio focus itself as it starts listening: that is our own
        // microphone, not a call. A loss later than that is someone else's.
        if (transient && (phaseNow is VoicePhase.Listening || bargeListening) && now() - listenStartedAt < RECOGNIZER_FOCUS_MS) return
        pauseForFocus(transient)
    }

    private fun pauseForFocus(transient: Boolean) = pause(if (transient) "Paused while another app uses audio. Locus goes on when it's done." else "Paused because another app is playing audio. Tap to go on.", resume = transient)

    fun focusGained() {
        if (phaseNow is VoicePhase.Paused && resumeOnFocus) resume()
    }

    /** Locus left the screen: no listening in the background. */
    fun backgrounded() = pause("Paused while Locus was in the background. Tap to go on.", resume = false)

    private fun pause(reason: String, resume: Boolean) {
        if (phaseNow == VoicePhase.Ended || phaseNow is VoicePhase.Paused) return
        val wasSaying = speaking?.takeIf { it.kind == SpeechKind.REPLY }
        if (wasSaying != null) interrupted = wasSaying
        silence()
        // An approval prompt cut off is asked again from the start on resume.
        approvalId = null
        confirming = false
        resumeOnFocus = resume
        _phase.value = VoicePhase.Paused(reason)
    }

    private fun resume() {
        resumeOnFocus = false
        _phase.value = VoicePhase.Idle(TAP_TO_TALK)
        lastHeardAt = now()
        val again = interrupted
        interrupted = null
        if (again != null && approvalId == null) speak(again.text, SpeechKind.REPLY) { next() } else next()
    }

    /** Stops the microphone and the voice, and forgets what was coming next. */
    private fun silence() {
        ears.cancel()
        bargeListening = false
        if (speaking != null) mouth.stop()
        speaking = null
        afterSpeech = {}
        _level.value = 0f
    }

    // ---------------------------------------------------------------- the user

    /** A tap on the big circle: talk now, interrupt, finish talking, or go on after a pause or error. */
    fun tap() {
        when (val p = phaseNow) {
            is VoicePhase.Speaking -> {
                // Barge-in by tap: stop talking and listen. An approval prompt keeps its question.
                val purpose = purposeAfter(p)
                silence()
                listen(purpose)
            }
            is VoicePhase.Listening -> ears.stop()
            is VoicePhase.Working, is VoicePhase.Idle -> {
                lastHeardAt = now()
                listen(ListenFor.MESSAGE)
            }
            is VoicePhase.Failed -> {
                busyRetried = false
                lastHeardAt = now()
                next()
            }
            is VoicePhase.Paused -> resume()
            is VoicePhase.TapApproval, VoicePhase.Ended -> Unit
        }
    }

    // ---------------------------------------------------------------- the agent

    fun onAgent(v: VoiceAgentView) {
        val previous = view
        view = v
        if (!started || phaseNow == VoicePhase.Ended) return
        // A task that started some other way (a tap in the chat): say its reply too.
        if (v.running && !previous.running && !awaitingReply) {
            awaitingReply = true
            baseline = previous.replyKey
        }
        if (v.running) sawRun = true
        if (!v.running && previous.running) runEndedAt = now()
        if (phaseNow is VoicePhase.Paused) return

        // The approval being handled was answered on screen (or went away): drop its prompt.
        if (approvalId != null && v.approval?.callId != approvalId) {
            approvalId = null
            confirming = false
            if (inApproval()) {
                silence()
                next()
                return
            }
        }
        // A new approval interrupts status and an idle microphone, not someone mid-sentence.
        if (v.approval != null && approvalId == null && interruptible()) {
            silence()
            next()
            return
        }
        if (v.questionId != null && v.questionId != questionId && interruptible()) {
            silence()
            next()
            return
        }
        when (val p = phaseNow) {
            is VoicePhase.Working -> {
                if (!v.running) next() else {
                    val status = statusText(v)
                    if (!maybeSayStatus(v)) _phase.value = p.copy(status = status)
                }
            }
            is VoicePhase.Idle -> if (v.running || awaitingReply) next()
            else -> Unit
        }
    }

    /** About once a second: lets a finished run whose reply never showed move on. */
    fun tick() {
        if (!awaitingReply || view.running || phaseNow !is VoicePhase.Working) return
        if (!sawRun) {
            // Sent, but no run started: something on the chat screen is waiting for the user.
            if (now() - sentAt >= START_WAIT_MS) needsScreen("Locus didn't start on that.")
            return
        }
        val ended = runEndedAt ?: return
        if (now() - ended >= REPLY_WAIT_MS) {
            awaitingReply = false
            runEndedAt = null
            speak(NO_REPLY, SpeechKind.NOTICE) { afterReply() }
        }
    }

    /**
     * Something only the chat screen can settle (the model can't take the message, the phone is hot):
     * voice mode stops listening and says so. [reason] is the chat's own wording.
     */
    fun needsScreen(reason: String) {
        if (phaseNow == VoicePhase.Ended) return
        silence()
        awaitingReply = false
        _phase.value = VoicePhase.Failed("$reason Check the chat, then tap to talk again.", VoiceFix.CHAT)
    }

    // ---------------------------------------------------------------- the recognizer

    fun heardLevel(level: Float) {
        if (phaseNow is VoicePhase.Listening) _level.value = level.coerceIn(0f, 1f)
    }

    fun heardPartial(text: String) {
        val p = phaseNow
        if (p is VoicePhase.Speaking && bargeListening) {
            if (!BargeIn.interrupts(text, p.text)) return
            // Barge-in by voice: stop talking; the recognition session that heard it goes on as the turn.
            val purpose = purposeAfter(p)
            bargeListening = false
            mouth.stop()
            speaking = null
            afterSpeech = {}
            _phase.value = VoicePhase.Listening(text, purpose)
            return
        }
        if (p is VoicePhase.Listening) _phase.value = p.copy(partial = text)
    }

    fun heard(text: String) {
        val p = phaseNow
        if (p is VoicePhase.Speaking && bargeListening) {
            bargeListening = false
            if (!BargeIn.interrupts(text, p.text)) {
                // Its own voice, or a word or two: keep talking, and keep an ear open.
                if (settings.interruptByVoice) listenDuringSpeech()
                return
            }
            val purpose = purposeAfter(p)
            mouth.stop()
            speaking = null
            afterSpeech = {}
            _phase.value = VoicePhase.Listening(text, purpose)
            handle(text, purpose)
            return
        }
        if (p !is VoicePhase.Listening) return
        _level.value = 0f
        if (text.isBlank()) return noSpeech(p.purpose)
        handle(text.trim(), p.purpose)
    }

    fun hearFailed(error: HearError) {
        val p = phaseNow
        if (p is VoicePhase.Speaking && bargeListening) {
            bargeListening = false
            // The barge-in ear timed out or failed: open it again while still talking, unless it can't work.
            if (settings.interruptByVoice && (error == HearError.NoSpeech || error == HearError.Busy)) listenDuringSpeech()
            return
        }
        if (p !is VoicePhase.Listening) return
        _level.value = 0f
        when (error) {
            HearError.NoSpeech -> noSpeech(p.purpose)
            HearError.Busy -> if (!busyRetried) {
                busyRetried = true
                ears.cancel()
                ears.listen()
            } else {
                _phase.value = VoicePhase.Failed("Another app is using speech recognition. Tap to try again.")
            }
            HearError.Permission -> _phase.value = VoicePhase.Failed(MIC_NEEDED, VoiceFix.MIC_PERMISSION)
            HearError.NothingHeard -> _phase.value = VoicePhase.Idle(NOTHING_HEARD)
            is HearError.Failed -> _phase.value = VoicePhase.Failed(error.message, error.fix)
        }
    }

    private fun noSpeech(purpose: ListenFor) {
        when {
            purpose != ListenFor.MESSAGE -> unclearAnswer()
            // Listening was the user's own tap while the agent works: back to waiting quietly.
            view.running -> next()
            // Nothing said for a while: the microphone goes off and voice mode waits for a tap, saying
            // why, instead of closing under the person.
            now() - lastHeardAt >= SILENCE_END_MS -> {
                silence()
                _phase.value = VoicePhase.Idle(NOTHING_HEARD)
            }
            else -> listen(ListenFor.MESSAGE)
        }
    }

    private fun handle(text: String, purpose: ListenFor) {
        lastHeardAt = now()
        busyRetried = false
        when (purpose) {
            ListenFor.MESSAGE -> message(text)
            ListenFor.APPROVAL, ListenFor.CONFIRM -> approvalAnswer(text, purpose == ListenFor.CONFIRM)
        }
    }

    private fun message(text: String) {
        val v = view
        when {
            // Words spoken while an open question waits answer it (the composer's rule).
            v.questionId != null -> {
                agent.send(text)
                awaitingReply = true
                sawRun = v.running
                sentAt = now()
                _phase.value = VoicePhase.Working("Answering")
            }
            v.running -> {
                // A mid-task note: read at the agent's next step, without stopping it.
                agent.send(text)
                speak(NOTE_ACK, SpeechKind.NOTICE) { next() }
            }
            else -> {
                baseline = v.replyKey
                awaitingReply = true
                sawRun = false
                sentAt = now()
                runEndedAt = null
                statusLabel = null
                statusAt = 0L
                agent.send(text)
                _phase.value = VoicePhase.Working("Sending")
            }
        }
    }

    // ---------------------------------------------------------------- approvals

    private fun approvalAnswer(text: String, confirmStep: Boolean) {
        val request = view.approval?.takeIf { it.callId == approvalId }
        // Answered on screen meanwhile, or no longer one voice may answer.
        if (request == null || !VoiceApprovals.voiceAllowed(request)) return next()
        when (VoiceApprovals.parse(text, confirmStep)) {
            VoiceReply.DENY -> {
                agent.answer(request.callId, false)
                approvalId = null
                confirming = false
                speak("Denied.", SpeechKind.NOTICE) { next() }
            }
            VoiceReply.ALLOW -> if (!confirmStep) {
                confirming = true
                unclear = 0
                speak(VoiceApprovals.CONFIRM_PROMPT, SpeechKind.PROMPT) { listen(ListenFor.CONFIRM) }
            } else unclearAnswer()
            VoiceReply.CONFIRM -> if (confirmStep) {
                agent.answer(request.callId, true)
                approvalId = null
                confirming = false
                speak("Allowed.", SpeechKind.NOTICE) { next() }
            } else unclearAnswer()
            VoiceReply.UNCLEAR -> unclearAnswer()
        }
    }

    private fun unclearAnswer() {
        val request = view.approval?.takeIf { it.callId == approvalId } ?: return next()
        unclear++
        if (unclear >= MAX_UNCLEAR) {
            confirming = false
            speak(VoiceApprovals.UNCLEAR_TAP, SpeechKind.PROMPT) { _phase.value = VoicePhase.TapApproval(request, "Voice didn't get a clear answer.") }
            return
        }
        val step = if (confirming) ListenFor.CONFIRM else ListenFor.APPROVAL
        speak(if (confirming) VoiceApprovals.UNCLEAR_CONFIRM else VoiceApprovals.UNCLEAR_AGAIN, SpeechKind.PROMPT) { listen(step) }
    }

    private fun startApproval(request: ApprovalRequest) {
        approvalId = request.callId
        confirming = false
        unclear = 0
        val reason = VoiceApprovals.tapReason(request)
        if (reason != null) {
            speak(VoiceApprovals.tapPrompt(request, reason), SpeechKind.PROMPT) { _phase.value = VoicePhase.TapApproval(request, reason) }
        } else {
            speak(VoiceApprovals.askPrompt(request), SpeechKind.PROMPT) { listen(ListenFor.APPROVAL) }
        }
    }

    // ---------------------------------------------------------------- what next

    /** Decides what to do when nothing is in progress. */
    private fun next() {
        if (phaseNow == VoicePhase.Ended || phaseNow is VoicePhase.Paused) return
        val v = view
        val approval = v.approval
        if (approval != null) {
            if (approvalId != approval.callId) return startApproval(approval)
            // Already handled: a tap-only request waits for the tap.
            VoiceApprovals.tapReason(approval)?.let { _phase.value = VoicePhase.TapApproval(approval, it) } ?: run {
                if (phaseNow !is VoicePhase.TapApproval) listen(if (confirming) ListenFor.CONFIRM else ListenFor.APPROVAL)
            }
            return
        }
        if (v.questionId != null && v.questionId != questionId) {
            questionId = v.questionId
            return speak(v.question.orEmpty(), SpeechKind.PROMPT) { listen(ListenFor.MESSAGE) }
        }
        if (awaitingReply && !v.running && !sawRun) {
            // Sent; the run hasn't shown up yet.
            _phase.value = VoicePhase.Working("Sending")
            return
        }
        if (awaitingReply && !v.running) {
            val reply = v.replyText
            if (v.replyKey != null && v.replyKey != baseline && reply != null) {
                awaitingReply = false
                runEndedAt = null
                return speak(SpeechText.forVoice(reply), SpeechKind.REPLY) { afterReply() }
            }
            if (v.error != null) {
                awaitingReply = false
                runEndedAt = null
                return speak("Something went wrong. ${SpeechText.prepare(v.error)}", SpeechKind.NOTICE) { afterReply() }
            }
            // The run ended and its reply isn't in the chat yet: wait a moment ([tick]).
            if (runEndedAt == null) runEndedAt = now()
            _phase.value = VoicePhase.Working("Finishing")
            return
        }
        if (v.running) {
            _phase.value = VoicePhase.Working(statusText(v))
            maybeSayStatus(v)
            return
        }
        if (settings.autoListen) listen(ListenFor.MESSAGE) else _phase.value = VoicePhase.Idle(TAP_TO_TALK)
    }

    private fun afterReply() {
        lastHeardAt = now()
        next()
    }

    private fun listen(purpose: ListenFor) {
        if (phaseNow == VoicePhase.Ended || phaseNow is VoicePhase.Paused) return
        _phase.value = VoicePhase.Listening("", purpose)
        _level.value = 0f
        listenStartedAt = now()
        ears.listen()
    }

    private fun speak(text: String, kind: SpeechKind, then: () -> Unit) {
        val id = ++speechId
        val phase = VoicePhase.Speaking(text, kind)
        speaking = phase
        afterSpeech = then
        _phase.value = phase
        mouth.speak(text, id)
        // Short notices aren't worth an open microphone.
        if (settings.interruptByVoice && kind != SpeechKind.NOTICE) listenDuringSpeech()
    }

    private fun listenDuringSpeech() {
        bargeListening = true
        listenStartedAt = now()
        ears.listen()
    }

    /** The voice finished saying utterance [id]. */
    fun spoken(id: Int) {
        if (id != speechId || speaking == null) return
        speaking = null
        if (bargeListening) {
            bargeListening = false
            ears.cancel()
        }
        val then = afterSpeech
        afterSpeech = {}
        then()
    }

    /** The voice couldn't say utterance [id]. */
    fun speakFailed(id: Int, message: String) {
        if (id != speechId || speaking == null) return
        silence()
        _phase.value = VoicePhase.Failed(message)
    }

    // ---------------------------------------------------------------- helpers

    private fun purposeAfter(p: VoicePhase.Speaking): ListenFor =
        if (p.kind == SpeechKind.PROMPT && approvalId != null) (if (confirming) ListenFor.CONFIRM else ListenFor.APPROVAL) else ListenFor.MESSAGE

    private fun inApproval(): Boolean = when (val p = phaseNow) {
        is VoicePhase.TapApproval -> true
        is VoicePhase.Listening -> p.purpose != ListenFor.MESSAGE
        is VoicePhase.Speaking -> p.kind == SpeechKind.PROMPT
        else -> false
    }

    /** Status, waiting, or a microphone nobody has spoken into yet. */
    private fun interruptible(): Boolean = when (val p = phaseNow) {
        is VoicePhase.Working, is VoicePhase.Idle -> true
        is VoicePhase.Speaking -> p.kind == SpeechKind.STATUS || p.kind == SpeechKind.NOTICE
        is VoicePhase.Listening -> p.partial.isBlank() && p.purpose == ListenFor.MESSAGE
        else -> false
    }

    private fun statusText(v: VoiceAgentView): String = v.activeTool?.let(::toolActivity) ?: "Thinking"

    /** Says what the agent is doing, when spoken status is on and it changed, no more often than every [STATUS_GAP_MS]. */
    private fun maybeSayStatus(v: VoiceAgentView): Boolean {
        if (!settings.spokenStatus || phaseNow !is VoicePhase.Working) return false
        val tool = v.activeTool ?: return false
        val label = toolActivity(tool)
        val t = now()
        if (label == statusLabel || (statusLabel != null && t - statusAt < STATUS_GAP_MS)) return false
        val first = statusLabel == null
        statusLabel = label
        statusAt = t
        speak(if (first) "Working on it. $label." else "$label.", SpeechKind.STATUS) { next() }
        return true
    }

    companion object {
        const val TAP_TO_TALK = "Tap to talk"
        const val NOTHING_HEARD = "Didn't hear anything. Tap to talk"
        const val NOTE_ACK = "Got it. Locus will read that after this step."
        const val NO_REPLY = "The task ended without a reply to read."
        const val MIC_NEEDED ="Voice mode needs the microphone. Allow it, then tap to try again."

        /** Listening with nothing heard for this long (after the last words or reply) turns the microphone off until a tap. */
        const val SILENCE_END_MS = 30_000L

        /** A spoken status at most this often. */
        const val STATUS_GAP_MS = 15_000L

        /** How long to wait for a finished run's reply to reach the chat. */
        const val REPLY_WAIT_MS = 4_000L

        /** How long to wait for a sent message's run to start before pointing at the chat screen. */
        const val START_WAIT_MS = 10_000L

        /** Unclear answers to an approval before handing it to the screen. */
        const val MAX_UNCLEAR = 2

        /** A transient focus loss this soon after starting to listen is the recognizer's own. */
        const val RECOGNIZER_FOCUS_MS = 2_000L
    }
}

/**
 * Barge-in by voice: whether what the recognizer heard while Locus talks is the user, not Locus's own
 * voice coming back through the microphone. It must hold at least [MIN_WORDS] words that aren't in
 * what's being said (Pipecat's MinWords strategy, plus an echo check). So "allow" can never be picked
 * up from the approval prompt that says "allow" itself.
 */
internal object BargeIn {
    const val MIN_WORDS = 2

    fun interrupts(heard: String, saying: String): Boolean {
        val said = words(saying).toSet()
        return words(heard).count { it !in said } >= MIN_WORDS
    }

    private fun words(text: String): List<String> = text.lowercase().split(Regex("[^\\p{L}\\p{N}']+")).filter { it.isNotEmpty() }
}

/** SpeechRecognizer's error codes (android.speech.SpeechRecognizer.ERROR_*), as [HearError]s. */
internal fun hearError(code: Int): HearError = when (code) {
    6, 7 -> HearError.NoSpeech // ERROR_SPEECH_TIMEOUT, ERROR_NO_MATCH
    8 -> HearError.Busy // ERROR_RECOGNIZER_BUSY
    9 -> HearError.Permission // ERROR_INSUFFICIENT_PERMISSIONS
    5 -> HearError.NothingHeard // ERROR_CLIENT
    1, 2 -> HearError.Failed("Speech recognition needs a connection right now. Check it, then tap to try again.") // NETWORK_TIMEOUT, NETWORK
    4, 10 -> HearError.Failed("The speech service is busy. Wait a moment, then tap to try again.") // SERVER, TOO_MANY_REQUESTS
    12, 13 -> HearError.Failed( // LANGUAGE_NOT_SUPPORTED, LANGUAGE_UNAVAILABLE
        "Speech recognition on this phone doesn't have your language yet. Add it in the phone's speech settings.",
        VoiceFix.SPEECH_SETTINGS,
    )
    3 -> HearError.Failed("Couldn't record from the microphone. Another app may be using it. Tap to try again.") // ERROR_AUDIO
    11 -> HearError.Failed("The speech recognizer closed. Tap to start it again.") // ERROR_SERVER_DISCONNECTED
    else -> HearError.Failed("Listening stopped. Tap to try again.")
}
