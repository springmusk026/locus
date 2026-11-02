package dev.locus.feature.chat.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dev.locus.feature.chat.SpeechText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Which recognizer voice mode uses, for the screen to say (docs/design/voice-mode.md, Privacy). */
internal data class VoiceEngine(val label: String, val onDevice: Boolean, val note: String)

/**
 * Android's [SpeechRecognizer] as [VoiceEars]. The on-device recognizer (API 31+) when the phone has
 * one; otherwise the system recognizer, asked to stay offline ([RecognizerIntent.EXTRA_PREFER_OFFLINE])
 * and, if it can't, allowed to use its service. Must be used on the main thread, as SpeechRecognizer
 * requires. No audio is kept: the recognizer gets the microphone, Locus gets only text.
 */
internal class SystemEars(private val context: Context, private val session: () -> VoiceSession?) : VoiceEars {

    private var recognizer: SpeechRecognizer? = null
    private var onDevice = false
    private var preferOffline = true
    private var active = false

    /** The on-device recognizer said it can't do this language: use the system one from now on. */
    private var fellBack = false

    private val _engine = MutableStateFlow(describe())
    val engine: StateFlow<VoiceEngine> = _engine

    override fun listen() {
        val r = recognizer ?: create() ?: run {
            session()?.hearFailed(HearError.Failed("No speech recognizer is installed."))
            return
        }
        // One session at a time: a new one replaces any still open.
        if (active) runCatching { r.cancel() }
        active = true
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        if (!onDevice && preferOffline) intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        runCatching { r.startListening(intent) }.onFailure {
            active = false
            session()?.hearFailed(HearError.Failed("Couldn't start the speech recognizer."))
        }
    }

    override fun stop() {
        if (active) runCatching { recognizer?.stopListening() }
    }

    override fun cancel() {
        active = false
        runCatching { recognizer?.cancel() }
    }

    fun destroy() {
        active = false
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun create(): SpeechRecognizer? {
        val useOnDevice = onDeviceAvailable() && !fellBack
        val r = runCatching {
            if (useOnDevice && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else if (SpeechRecognizer.isRecognitionAvailable(context)) SpeechRecognizer.createSpeechRecognizer(context)
            else null
        }.getOrNull() ?: return null
        onDevice = useOnDevice
        r.setRecognitionListener(listener)
        recognizer = r
        _engine.value = describe()
        return r
    }

    private fun onDeviceAvailable(): Boolean =
        Build.VERSION.SDK_INT >= 31 && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)

    private fun describe(): VoiceEngine = when {
        onDeviceAvailable() && !fellBack -> VoiceEngine("On-device recognition", true, "Speech is turned into text on this phone.")
        preferOffline -> VoiceEngine(
            systemRecognizerName() ?: "System speech recognizer",
            false,
            "Asked to stay on the phone; whether it does is up to that app.",
        )
        else -> VoiceEngine(
            systemRecognizerName() ?: "System speech recognizer",
            false,
            "This recognizer may send your voice to its own service.",
        )
    }

    /** The default recognition service's app name, when Android says which it is. */
    private fun systemRecognizerName(): String? = runCatching {
        val flat = Settings.Secure.getString(context.contentResolver, "voice_recognition_service")
        val component = flat?.let(ComponentName::unflattenFromString)
        val pm = context.packageManager
        val pkg = component?.packageName ?: pm.queryIntentServices(Intent("android.speech.RecognitionService"), PackageManager.MATCH_DEFAULT_ONLY)
            .firstOrNull()?.serviceInfo?.packageName
        pkg?.let { pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString() }
    }.getOrNull()

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onRmsChanged(rmsdB: Float) {
            // Roughly -2 dB (quiet) to 10 dB (speech) on common recognizers; there's no guarantee it's called at all.
            if (active) session()?.heardLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!active) return
            first(partialResults)?.let { session()?.heardPartial(it) }
        }

        override fun onResults(results: Bundle?) {
            if (!active) return
            active = false
            session()?.heard(first(results).orEmpty())
        }

        override fun onError(error: Int) {
            if (!active) return
            active = false
            // Not available offline, or the on-device model lacks the language: fall back once, and say so.
            val language = error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
            if (onDevice && language) {
                fellBack = true
                destroy()
                _engine.value = describe()
                listen()
                return
            }
            if (!onDevice && preferOffline && (language || error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT)) {
                preferOffline = false
                _engine.value = describe()
                listen()
                return
            }
            session()?.hearFailed(hearError(error))
        }
    }

    private fun first(bundle: Bundle?): String? = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
}

/**
 * The system [TextToSpeech] engine as [VoiceMouth], with assistant audio attributes ([AudioAttributes.USAGE_ASSISTANT]:
 * on Android 17 its own volume stream) and the speed from Settings → Voice. Callbacks arrive on
 * binder threads ([UtteranceProgressListener]), so they're posted to the main thread.
 */
internal class SystemMouth(private val context: Context, private val session: () -> VoiceSession?) : VoiceMouth {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: Pair<String, Int>? = null
    var rate: Float = 1f

    /** The last chunk of each utterance, by the id voice mode gave it. */
    private val lastChunk = HashMap<String, Int>()

    override fun speak(text: String, id: Int) {
        val engine = tts ?: create()
        if (!ready) {
            pending = text to id
            return
        }
        say(engine, text, id)
    }

    override fun stop() {
        pending = null
        lastChunk.clear()
        runCatching { tts?.stop() }
    }

    fun shutdown() {
        stop()
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
    }

    private fun create(): TextToSpeech {
        var created: TextToSpeech? = null
        created = TextToSpeech(context.applicationContext) { status ->
            main.post {
                val engine = created ?: return@post
                if (status != TextToSpeech.SUCCESS || engine.engines.isEmpty()) {
                    val waiting = pending
                    pending = null
                    if (waiting != null) session()?.speakFailed(waiting.second, NO_ENGINE)
                    return@post
                }
                engine.setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                )
                engine.setOnUtteranceProgressListener(progress)
                ready = true
                pending?.let { (text, id) ->
                    pending = null
                    say(engine, text, id)
                }
            }
        }
        tts = created
        return created
    }

    private fun say(engine: TextToSpeech, text: String, id: Int) {
        engine.setSpeechRate(rate)
        val chunks = SpeechText.chunks(text, TextToSpeech.getMaxSpeechInputLength() - 1).ifEmpty { listOf(" ") }
        lastChunk.clear()
        lastChunk["voice-$id-${chunks.lastIndex}"] = id
        chunks.forEachIndexed { i, chunk ->
            engine.speak(chunk, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "voice-$id-$i")
        }
    }

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            main.post { lastChunk.remove(utteranceId)?.let { session()?.spoken(it) } }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = onError(utteranceId, TextToSpeech.ERROR)

        override fun onError(utteranceId: String?, errorCode: Int) {
            val id = utteranceId?.split('-')?.getOrNull(1)?.toIntOrNull() ?: return
            main.post {
                session()?.speakFailed(
                    id,
                    if (errorCode == TextToSpeech.ERROR_NOT_INSTALLED_YET) "The voice is still downloading. Tap to try again." else "Couldn't speak the reply. Tap to try again.",
                )
            }
        }
    }

    private companion object {
        const val NO_ENGINE = "No text-to-speech engine is installed. Add one (such as Speech Services by Google), then try again."
    }
}

/**
 * Transient audio focus for the whole voice session ([AudioFocusRequest]): other apps' media pauses
 * while Locus listens and talks. A call takes focus away; voice mode pauses and goes on when it ends.
 * Android 15+ grants focus only to the top app or one with a foreground service, which voice mode is.
 */
internal class VoiceFocus(context: Context, private val session: () -> VoiceSession?) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setWillPauseWhenDucked(true)
        // During a call focus is locked: the request is granted when the call ends (AUDIOFOCUS_GAIN).
        .setAcceptsDelayedFocusGain(true)
        .setOnAudioFocusChangeListener({ change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> session()?.focusLost(transient = false)
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> session()?.focusLost(transient = true)
                AudioManager.AUDIOFOCUS_GAIN -> session()?.focusGained()
                // A notification sound: speech isn't ducked by the system, and a ding isn't worth a pause.
                else -> Unit
            }
        }, main)
        .build()

    /** Asks for focus; a call in progress pauses voice mode until it ends. */
    fun request() {
        when (audio?.requestAudioFocus(request)) {
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> session()?.focusLost(transient = true)
            else -> Unit
        }
    }

    fun abandon() {
        audio?.abandonAudioFocusRequest(request)
    }
}
