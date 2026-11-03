package dev.locus.core.data.diagnostics

import dev.locus.core.datastore.DiagnosticsLog
import dev.locus.core.llm.ChatEvent
import dev.locus.core.llm.ChatModel
import dev.locus.core.llm.ChatRequest
import dev.locus.core.llm.LlmException
import dev.locus.core.model.ApiFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the body of a failed model response that the chat doesn't show ([LlmException.rawBody]): a
 * gateway's HTML page, a long dump, a JSON error the message only quotes. The chat gets plain words
 * ([dev.locus.core.llm.ProviderErrorText]); the body goes to Settings → About & privacy → Diagnostics and to
 * logcat, scrubbed of credential patterns here and of the vault's values again when Diagnostics shows it.
 *
 * The same body from the same provider is kept once per [quietMs], so a request retried four times
 * against a gateway that keeps timing out leaves one entry, not four of the log's 30.
 */
@Singleton
class ProviderErrorLog internal constructor(
    private val log: DiagnosticsLog,
    private val clock: () -> Long,
    private val logcat: (String) -> Unit,
) {
    @Inject constructor(log: DiagnosticsLog) : this(log, System::currentTimeMillis, { text -> runCatching { android.util.Log.w("Locus", text) } })

    private val lastSeen = ConcurrentHashMap<String, Long>()

    /** Records [error] for [provider] when it carries a body the message doesn't already show whole. */
    fun record(provider: String, model: String, error: LlmException) {
        val body = error.rawBody?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val message = error.message.orEmpty()
        if (body in message) return
        val key = "$provider|${error.httpStatus}|${body.hashCode()}"
        val now = clock()
        val previous = lastSeen[key]
        if (previous != null && now - previous < QUIET_MS) return
        lastSeen[key] = now
        if (lastSeen.size > MAX_KEYS) lastSeen.entries.removeIf { now - it.value >= QUIET_MS }
        val status = error.httpStatus?.let { "HTTP $it" } ?: "error"
        val ids = listOfNotNull(
            error.errorCode?.let { "Error code: $it" },
            error.requestId?.let { "Request id: $it" },
            error.responseId?.let { "Response id: $it" },
        ).joinToString("") { "$it\n" }
        val text = CrashRecorder.scrub(
            "Provider: $provider\nModel: $model\nStatus: $status\n${ids}Shown in the chat: $message\n\nResponse body as received:\n$body",
        )
        log.record(DiagnosticsLog.Kind.ERROR, "Model provider error: $provider $status", text)
        logcat("$provider $status: " + CrashRecorder.scrub(body).take(LOGCAT_CHARS))
    }

    /** [model] with every failure that carries a body recorded on its way out. */
    fun watch(model: ChatModel, provider: String, modelId: String): ChatModel = object : ChatModel {
        override val format: ApiFormat get() = model.format
        override fun stream(request: ChatRequest): Flow<ChatEvent> = model.stream(request).catch { e ->
            if (e is LlmException) record(provider, modelId, e)
            throw e
        }
    }

    internal companion object {
        const val QUIET_MS = 10 * 60_000L
        const val MAX_KEYS = 64
        const val LOGCAT_CHARS = 2_000
    }
}
