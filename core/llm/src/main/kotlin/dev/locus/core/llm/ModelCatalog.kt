package dev.locus.core.llm

import dev.locus.core.model.ApiFormat
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.net.URI

/** What the catalogue says about one model. Absent facts are null / false: unknown, not "no". */
@Serializable
data class CatalogModel(
    val name: String? = null,
    val context: Int? = null,
    val vision: Boolean = false,
    val tools: Boolean = false,
    val reasoning: Boolean = false,
)

/** One catalogue provider: its API base URL when it has a fixed one, and its models by id. */
@Serializable
data class CatalogProvider(val api: String? = null, val models: Map<String, CatalogModel> = emptyMap())

/**
 * The models.dev catalogue (https://models.dev, MIT), reduced to what the model sheet shows: per
 * provider, each model's name, context window, and whether it takes images, calls tools and reasons.
 *
 * The source is `https://models.dev/api.json`: an object of providers keyed by id, each with `api`
 * (base URL, optional) and `models` keyed by model id, each with `name`, `tool_call`, `reasoning`,
 * `modalities.input` (e.g. `["text","image","pdf"]`) and `limit.context`.
 */
class ModelCatalogIndex(val providers: Map<String, CatalogProvider>) {
    private val byProviderLower: Map<String, Map<String, CatalogModel>> by lazy {
        providers.mapValues { (_, p) -> p.models.mapKeys { it.key.lowercase() } }
    }

    /** Every model id (lower-cased) to its entry, preferring the model maker's own listing. */
    private val global: Map<String, CatalogModel> by lazy {
        val order = FIRST_PARTY + providers.keys.filterNot { it in FIRST_PARTY }.sorted()
        buildMap {
            order.forEach { pid -> byProviderLower[pid]?.forEach { (id, m) -> putIfAbsent(id, m) } }
        }
    }

    /**
     * The entry for [modelId] as served by the provider at [baseUrl]: that provider's own listing
     * when the endpoint is a known one, else the same id anywhere in the catalogue (proxies and
     * aggregators usually serve models under their makers' ids), else the id without a `vendor/`
     * prefix. Null when the catalogue doesn't know it.
     */
    fun lookup(baseUrl: String, format: ApiFormat, modelId: String): CatalogModel? {
        if (format == ApiFormat.ON_DEVICE) return null
        val id = modelId.trim().removePrefix("models/").lowercase()
        if (id.isEmpty()) return null
        providersFor(baseUrl, format).forEach { pid -> byProviderLower[pid]?.get(id)?.let { return it } }
        return global[id] ?: id.substringAfterLast('/').takeIf { it != id }?.let { global[it] }
    }

    /** Catalogue provider ids that describe the endpoint at [baseUrl]. */
    internal fun providersFor(baseUrl: String, format: ApiFormat): List<String> {
        if (format == ApiFormat.CHATGPT) return listOf("openai")
        val host = hostOf(baseUrl) ?: return emptyList()
        if (isLocal(host)) return emptyList()
        val known = KNOWN_HOSTS[host]?.let(::listOf).orEmpty()
        val byApi = providers.filter { (_, p) -> p.api?.let(::hostOf) == host }.keys.sorted()
        return (known + byApi).distinct()
    }

    companion object {
        /** Model makers, whose listing wins when several providers serve the same id. */
        val FIRST_PARTY = listOf("anthropic", "openai", "google", "xai", "mistral", "deepseek", "moonshotai", "zai", "alibaba", "minimax")

        /** First-party endpoints the catalogue lists without an `api` URL. */
        private val KNOWN_HOSTS = mapOf(
            "api.anthropic.com" to "anthropic",
            "api.openai.com" to "openai",
            "chatgpt.com" to "openai",
            "generativelanguage.googleapis.com" to "google",
            "api.groq.com" to "groq",
            "api.mistral.ai" to "mistral",
            "api.x.ai" to "xai",
            "api.deepseek.com" to "deepseek",
            "openrouter.ai" to "openrouter",
        )

        private fun hostOf(url: String): String? = runCatching { URI(url.trim()).host?.lowercase()?.removePrefix("www.") }.getOrNull()

        /** Local servers (LM Studio, Ollama, vLLM on the LAN) serve whatever the user loaded; no guessing. */
        private fun isLocal(host: String) = host == "localhost" || host.endsWith(".local") || host.startsWith("127.") || host.startsWith("10.") ||
            host.startsWith("192.168.") || Regex("""^172\.(1[6-9]|2\d|3[01])\.""").containsMatchIn(host)

        private val sourceJson = Json { ignoreUnknownKeys = true }

        /** Parses models.dev's `api.json`. Fields of an unexpected type count as unknown, not as errors. */
        @OptIn(ExperimentalSerializationApi::class)
        fun parseModelsDev(input: java.io.InputStream): ModelCatalogIndex {
            val raw = sourceJson.decodeFromStream(MapSerializer(String.serializer(), RawProvider.serializer()), input)
            return ModelCatalogIndex(raw.mapValues { (_, p) -> CatalogProvider(p.api?.takeIf { it.isNotBlank() }, p.models.mapValues { (_, m) -> m.toCatalog() }) })
        }

        fun parseModelsDev(text: String): ModelCatalogIndex = parseModelsDev(text.byteInputStream())
    }
}

/** models.dev's provider entry, only the fields used. Typed loosely so one odd field can't sink the rest. */
@Serializable
private class RawProvider(val api: String? = null, val models: Map<String, RawModel> = emptyMap())

@Serializable
private class RawModel(
    val name: JsonElement? = null,
    @SerialName("tool_call") val toolCall: JsonElement? = null,
    val reasoning: JsonElement? = null,
    val modalities: JsonElement? = null,
    val limit: JsonElement? = null,
) {
    fun toCatalog() = CatalogModel(
        name = (name as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
        context = ((limit as? JsonObject)?.get("context") as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 },
        vision = (((modalities as? JsonObject)?.get("input") as? JsonArray)?.any { (it as? JsonPrimitive)?.contentOrNull == "image" }) == true,
        tools = (toolCall as? JsonPrimitive)?.booleanOrNull == true,
        reasoning = (reasoning as? JsonPrimitive)?.booleanOrNull == true,
    )
}

/** What Locus keeps on disk: the reduced catalogue and when it was last checked. */
@Serializable
private class CatalogCache(val checkedAt: Long, val etag: String? = null, val providers: Map<String, CatalogProvider>)

/**
 * The catalogue, cached in [cacheFile] and refreshed from [url] at most once per [maxAge]. The request
 * is a plain GET with no credentials, cookies or identifiers (an `If-None-Match` when there's a
 * cached copy). Offline, or when the fetch fails, the cached copy (or nothing) is used; a failed
 * fetch isn't retried for [retryAfter]. Never throws.
 */
class ModelCatalog(
    private val http: HttpClient,
    private val cacheFile: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val url: String = URL,
    private val maxAge: Long = DAY_MS,
    private val retryAfter: Long = HOUR_MS,
) {
    private val mutex = Mutex()
    private var memory: CatalogCache? = null
    private var loadedFromDisk = false
    private var lastFailure: Long? = null

    /** The copy on disk, without touching the network. */
    suspend fun cached(): ModelCatalogIndex? = mutex.withLock { readCache()?.let { ModelCatalogIndex(it.providers) } }

    /** The catalogue, refreshed first when the cached copy is older than a day. */
    suspend fun get(): ModelCatalogIndex? = mutex.withLock {
        val cache = readCache()
        val t = now()
        val fresh = cache != null && t - cache.checkedAt in 0 until maxAge
        val backingOff = lastFailure?.let { t - it in 0 until retryAfter } == true
        val result = if (fresh || backingOff) cache else refresh(cache, t)
        result?.let { ModelCatalogIndex(it.providers) }
    }

    private suspend fun refresh(cache: CatalogCache?, t: Long): CatalogCache? {
        val fetched = try {
            // Off the caller's dispatcher, so the timeout runs on real time.
            withContext(Dispatchers.IO) { withTimeoutOrNull(FETCH_TIMEOUT_MS) { fetch(cache, t) } }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (fetched == null) {
            lastFailure = t
            return cache
        }
        write(fetched)
        lastFailure = null
        return fetched
    }

    /** Null when the fetch gave nothing usable. */
    private suspend fun fetch(cache: CatalogCache?, t: Long): CatalogCache? {
        val response = http.get(url) { cache?.etag?.let { header("If-None-Match", it) } }
        return when (response.status.value) {
            304 -> cache?.let { CatalogCache(t, it.etag, it.providers) }
            in 200..299 -> {
                val bytes = response.bodyAsBytes()
                val index = withContext(Dispatchers.Default) { ModelCatalogIndex.parseModelsDev(bytes.inputStream()) }
                index.providers.takeIf { it.isNotEmpty() }?.let { CatalogCache(t, response.headers["ETag"], it) }
            }
            else -> null
        }
    }

    private suspend fun readCache(): CatalogCache? {
        if (!loadedFromDisk) {
            loadedFromDisk = true
            memory = withContext(Dispatchers.IO) {
                runCatching { if (cacheFile.isFile) cacheJson.decodeFromString(CatalogCache.serializer(), cacheFile.readText()) else null }.getOrNull()
            }
        }
        return memory
    }

    private suspend fun write(cache: CatalogCache) {
        memory = cache
        withContext(Dispatchers.IO) {
            runCatching {
                cacheFile.parentFile?.mkdirs()
                val tmp = File(cacheFile.path + ".tmp")
                tmp.writeText(cacheJson.encodeToString(CatalogCache.serializer(), cache))
                if (!tmp.renameTo(cacheFile)) {
                    cacheFile.delete()
                    tmp.renameTo(cacheFile)
                }
            }
        }
    }

    companion object {
        const val URL = "https://models.dev/api.json"
        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val HOUR_MS = 60 * 60 * 1000L
        private const val FETCH_TIMEOUT_MS = 60_000L
        private val cacheJson = Json { ignoreUnknownKeys = true }
    }
}
