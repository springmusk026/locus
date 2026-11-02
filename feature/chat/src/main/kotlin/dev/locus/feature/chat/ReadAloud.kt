package dev.locus.feature.chat

import android.content.Context
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextLanguage
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Reads replies aloud with the system's text-to-speech engine (no extra library; the engine decides
 * whether it needs the network). One reply at a time: starting another, [stop], or leaving the chat
 * ([shutdown]) stops it. [reading] is the key of the reply being read, for the "Reading…" state.
 */
@Stable
internal class ReadAloud(private val context: Context, private val scope: CoroutineScope, private val report: (String) -> Unit) {

    var reading by mutableStateOf<String?>(null)
        private set

    private var tts: TextToSpeech? = null
    private var job: Job? = null
    @Volatile
    private var session = 0

    /** The utterance whose end means the reply was read to the end. */
    @Volatile
    private var lastUtterance: String? = null

    fun toggle(key: String, markdown: String) = if (reading == key) stop() else start(key, markdown)

    fun start(key: String, markdown: String) {
        stop()
        val id = ++session
        reading = key
        job = scope.launch {
            val engine = engine()
            if (engine == null) return@launch fail(id, "No text-to-speech engine is installed. Add one (such as Speech Services by Google) from the Play Store, then try again.")
            val text = withContext(Dispatchers.Default) { SpeechText.prepare(markdown) }
            if (text.isBlank()) return@launch finish(id)
            val guessed = withContext(Dispatchers.Default) { guessLanguage(text) }
            val device = context.resources.configuration.locales[0] ?: Locale.getDefault()
            val locale = listOfNotNull(guessed, device).firstOrNull { engine.isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE }
            if (locale == null || engine.setLanguage(locale) < TextToSpeech.LANG_AVAILABLE) {
                val name = (guessed ?: device).displayLanguage.ifBlank { "this language" }
                return@launch fail(id, "No voice for $name is installed. Add it in system settings under Text-to-speech output.")
            }
            val chunks = SpeechText.chunks(text, TextToSpeech.getMaxSpeechInputLength() - 1)
            lastUtterance = "locus-$id-${chunks.lastIndex}"
            chunks.forEachIndexed { i, chunk ->
                engine.speak(chunk, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "locus-$id-$i")
            }
        }
    }

    fun stop() {
        session++
        job?.cancel()
        job = null
        lastUtterance = null
        tts?.stop()
        reading = null
    }

    /** Leaving the chat: stop and release the engine. */
    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
    }

    private fun finish(id: Int) {
        if (id == session) reading = null
    }

    private fun fail(id: Int, message: String) {
        if (id != session) return
        reading = null
        report(message)
    }

    /** The engine, started on first use; null when the device has none or it fails to start. */
    private suspend fun engine(): TextToSpeech? {
        tts?.let { return it }
        val engine = suspendCancellableCoroutine<TextToSpeech?> { cont ->
            var created: TextToSpeech? = null
            created = TextToSpeech(context) { status ->
                val e = created
                // Stopped while the engine was starting: don't leak it.
                if (!cont.isActive) e?.shutdown()
                else cont.resume(if (status == TextToSpeech.SUCCESS) e else null.also { e?.shutdown() })
            }
        } ?: return null
        if (engine.engines.isEmpty()) {
            engine.shutdown()
            return null
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                if (utteranceId != null && utteranceId == lastUtterance) scope.launch { if (utteranceId == lastUtterance) stop() }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = onError(utteranceId, TextToSpeech.ERROR)

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId == null || !utteranceId.startsWith("locus-${session}-")) return
                scope.launch {
                    stop()
                    report(
                        if (errorCode == TextToSpeech.ERROR_NOT_INSTALLED_YET) "The voice is still downloading. Try again in a moment."
                        else "Couldn't read this reply aloud.",
                    )
                }
            }
        })
        tts = engine
        return engine
    }

    /** The reply's language when the system can tell with some confidence (Android 10+); else null. */
    private fun guessLanguage(text: String): Locale? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            val classifier = context.getSystemService(TextClassificationManager::class.java)?.textClassifier ?: return null
            val result = classifier.detectLanguage(TextLanguage.Request.Builder(text.take(1_000)).build())
            if (result.localeHypothesisCount == 0) return null
            val best = result.getLocale(0)
            if (result.getConfidenceScore(best) < 0.7f) null else Locale.forLanguageTag(best.toLanguageTag())
        }.getOrNull()
    }
}

/** A [ReadAloud] for this screen; released when the screen leaves composition. Errors show as a toast. */
@Composable
internal fun rememberReadAloud(): ReadAloud {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val readAloud = remember { ReadAloud(context, scope) { Toast.makeText(context, it, Toast.LENGTH_LONG).show() } }
    DisposableEffect(readAloud) { onDispose { readAloud.shutdown() } }
    return readAloud
}

/** Under a reply being read: "Reading…" and a Stop button. */
@Composable
internal fun ReadingChip(onStop: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Reading…",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        TextButton(onClick = onStop, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Stop") }
    }
}
