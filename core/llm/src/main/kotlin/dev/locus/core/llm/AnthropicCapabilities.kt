package dev.locus.core.llm

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * What an Anthropic model supports, read from the Models API and kept for the life of the process.
 *
 * `GET /v1/models` and `GET /v1/models/{model_id}` return `capabilities`, including
 * `context_management.compact_20260112.supported` for server-side compaction
 * (https://platform.claude.com/docs/en/api/models/retrieve). `capabilities` may be null, and the
 * lookup can fail (offline, a key without access), so every answer falls back to the static list
 * taken from the compaction docs' supported models.
 */
object AnthropicCapabilities {
    private val compaction = ConcurrentHashMap<String, Boolean>()
    private val failedAt = ConcurrentHashMap<String, Long>()

    /** Whether [model] compacts long chats on the server: the Models API's answer, else the static list. */
    fun compaction(model: String): Boolean = compaction[model.trim()] ?: (model.trim() in STATIC_COMPACTION_MODELS)

    /** Whether the Models API has answered for [model] in this process. */
    fun known(model: String): Boolean = compaction.containsKey(model.trim())

    /** Records what a Models API model object says; ignores objects without `capabilities`. */
    internal fun record(model: JsonObject) {
        val id = model.string("id") ?: return
        val caps = model["capabilities"] as? JsonObject ?: return
        val supported = ((caps["context_management"] as? JsonObject)?.get("compact_20260112") as? JsonObject)
            ?.get("supported")?.let { (it as? JsonPrimitive)?.booleanOrNull }
            // The strategy key is nullable in the schema: absent or null means not supported.
            ?: false
        compaction[id] = supported
    }

    /**
     * Looks [model] up once (`GET /v1/models/{model}`) unless it's already known or failed within
     * the last [RETRY_AFTER_MS]. Never throws and gives up after [TIMEOUT_MS] so a chat isn't held up.
     */
    suspend fun ensure(http: HttpClient, baseUrl: String, apiKey: String, model: String, now: Long = System.currentTimeMillis()) {
        val id = model.trim()
        if (id.isEmpty() || compaction.containsKey(id)) return
        failedAt[id]?.let { if (now - it < RETRY_AFTER_MS) return }
        val ok = runCatching {
            withTimeoutOrNull(TIMEOUT_MS) {
                val response = http.get("${baseUrl.trimEnd('/')}/v1/models/$id") {
                    header("x-api-key", apiKey)
                    header("anthropic-version", AnthropicChatModel.API_VERSION)
                }
                if (response.status.value !in 200..299) return@withTimeoutOrNull false
                val body = LlmJson.parseToJsonElement(response.bodyAsText()) as? JsonObject ?: return@withTimeoutOrNull false
                // The path accepts aliases; record under the ID the user configured as well.
                record(body)
                if (body.string("id") != id) record(JsonObject(body + ("id" to JsonPrimitive(id))))
                compaction.containsKey(id)
            } ?: false
        }.getOrDefault(false)
        if (ok) failedAt.remove(id) else failedAt[id] = now
    }

    internal fun clear() {
        compaction.clear()
        failedAt.clear()
    }

    private const val TIMEOUT_MS = 5_000L
    private const val RETRY_AFTER_MS = 60 * 60 * 1000L

    /**
     * Fallback when the Models API hasn't answered: the `supportedModels` of
     * https://platform.claude.com/docs/en/build-with-claude/compaction-threshold (read 2026-10-31).
     */
    internal val STATIC_COMPACTION_MODELS = setOf(
        "claude-fable-5-1", "claude-mythos-5-1", "claude-fable-5", "claude-mythos-5", "claude-mythos-preview",
        "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5", "claude-sonnet-5", "claude-haiku-5-5",
        "claude-opus-4-8", "claude-opus-4-7", "claude-opus-4-6", "claude-sonnet-4-6",
    )
}
