package dev.locus.core.data.network

import androidx.datastore.core.DataStore
import dev.locus.core.datastore.SecretStore
import dev.locus.core.llm.ChatEvent
import dev.locus.core.llm.ChatModel
import dev.locus.core.llm.ChatRequest
import dev.locus.core.llm.LlmException
import dev.locus.core.model.AppSettings
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.ProxyType
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.OkHttpClient
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Builds the HTTP engine for a provider's own client: proxy, trust and redirects. */
internal fun interface ProviderEngines {
    fun create(plan: NetworkPlan, proxyCredentials: ProxyCredentials?): HttpClientEngine
}

/**
 * The OkHttp engine for one provider, derived from the app's shared OkHttpClient (same timeouts),
 * with its own dispatcher and connection pool so closing it can't affect other clients. Ktor sets a
 * proxy on the engine, not per request (ktor.io/docs/client-proxy.html), hence one engine per plan.
 */
internal class OkHttpProviderEngines(private val shared: OkHttpClient) : ProviderEngines {
    override fun create(plan: NetworkPlan, proxyCredentials: ProxyCredentials?): HttpClientEngine = OkHttp.create {
        preconfigured = configure(shared.newBuilder(), plan, proxyCredentials).build()
    }

    companion object {
        fun configure(builder: OkHttpClient.Builder, plan: NetworkPlan, proxyCredentials: ProxyCredentials?): OkHttpClient.Builder = builder.apply {
            dispatcher(okhttp3.Dispatcher())
            connectionPool(okhttp3.ConnectionPool())
            if (!plan.followRedirects) {
                followRedirects(false)
                followSslRedirects(false)
            }
            plan.proxy?.let { proxy ->
                proxySelector(LoopbackBypassProxySelector(proxy.toJavaProxy()))
                if (proxyCredentials != null) {
                    if (proxy.type == ProxyType.HTTP) {
                        proxyAuthenticator(httpProxyAuthenticator(proxyCredentials))
                    } else {
                        SocksCredentials.register(proxy, proxyCredentials)
                    }
                }
            }
            if (plan.certificates.isNotEmpty()) {
                val extra = plan.certificates.mapNotNull(Certificates::decode)
                val trust = ExtraCaTrustManager(ExtraCaTrustManager.systemTrustManager(), extra)
                sslSocketFactory(ExtraCaTrustManager.sslContext(trust).socketFactory, trust)
            }
        }

        /** Answers an HTTP proxy's 407 (also for the CONNECT tunnel to an https provider) with Basic auth, once. */
        fun httpProxyAuthenticator(credentials: ProxyCredentials) = Authenticator { _, response ->
            if (response.request.header("Proxy-Authorization") != null) {
                null // Already tried: the proxy refused these credentials.
            } else {
                response.request.newBuilder()
                    .header("Proxy-Authorization", Credentials.basic(credentials.username, credentials.password))
                    .build()
            }
        }
    }
}

/** A provider's client, and the secret values it sends, for scrubbing them from errors. */
internal class ProviderClient(val http: HttpClient, val secretValues: List<String>)

/**
 * HTTP clients for model providers. A provider with no network settings uses the app's shared
 * client; one with extra headers, a proxy or extra trusted CAs gets its own, cached until its
 * settings or vault values change.
 */
@Singleton
class ProviderHttpClients internal constructor(
    private val shared: HttpClient,
    private val engines: ProviderEngines,
    private val secrets: SecretStore,
    private val settings: DataStore<AppSettings>,
) {
    @Inject
    constructor(shared: HttpClient, okHttp: OkHttpClient, secrets: SecretStore, settings: DataStore<AppSettings>) :
        this(shared, OkHttpProviderEngines(okHttp), secrets, settings)

    private class Cached(val key: String, val client: HttpClient)

    private val cache = HashMap<String, Cached>()
    private val lock = Mutex()

    internal suspend fun clientFor(provider: ProviderConfig): ProviderClient {
        val plan = NetworkPlan.resolve(provider, settings.data.first().network)
        if (plan.isDirect || provider.format == ApiFormat.ON_DEVICE) {
            lock.withLock { cache.remove(provider.id)?.client?.close() }
            return ProviderClient(shared, emptyList())
        }
        val headers = HeaderValues(
            plan.headerNames.mapNotNull { name -> secrets.get(SecretStore.providerHeader(provider.id, name))?.let { name to it } }.toMap(),
        )
        val proxyCredentials = plan.proxyScope?.let { readProxyCredentials(secrets.get(SecretStore.proxyCredentials(it))) }
        val secretValues = headers.values.values.toList() + listOfNotNull(proxyCredentials?.password)
        // The key carries a digest of the secret values, never the values themselves.
        val key = plan.toString() + digest(headers.values.entries.joinToString("\u0000") { "${it.key}=${it.value}" } + "\u0000" + proxyCredentials?.let { "${it.username}:${it.password}" })
        val client = lock.withLock {
            val cached = cache[provider.id]
            if (cached != null && cached.key == key) {
                cached.client
            } else {
                cached?.client?.close()
                build(plan, headers, proxyCredentials).also { cache[provider.id] = Cached(key, it) }
            }
        }
        return ProviderClient(client, secretValues)
    }

    private fun build(plan: NetworkPlan, headers: HeaderValues, proxyCredentials: ProxyCredentials?): HttpClient =
        HttpClient(engines.create(plan, proxyCredentials)) {
            install(HttpTimeout) { requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS }
            expectSuccess = false
            // With extra headers, a redirect could carry them to another host (OkHttp only drops Authorization).
            followRedirects = plan.followRedirects
            if (headers.values.isNotEmpty()) {
                install(ProviderHeaders) {
                    this.headers = headers
                    allowAuthOverride = plan.allowAuthOverride
                }
            }
        }

    private fun digest(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        /**
         * For tests outside this module: every provider client gets an engine from [engine] (a mock),
         * with the real header, redirect and caching rules on top. Proxy and trust need a real engine.
         */
        fun withEngine(shared: HttpClient, secrets: SecretStore, settings: DataStore<AppSettings>, engine: () -> HttpClientEngine) =
            ProviderHttpClients(shared, { _, _ -> engine() }, secrets, settings)

        internal fun readProxyCredentials(stored: String?): ProxyCredentials? {
            if (stored.isNullOrBlank()) return null
            val obj = runCatching { Json.parseToJsonElement(stored) as? JsonObject }.getOrNull() ?: return null
            val user = runCatching { obj["username"]?.jsonPrimitive?.content }.getOrNull().orEmpty()
            val pass = runCatching { obj["password"]?.jsonPrimitive?.content }.getOrNull().orEmpty()
            return if (user.isEmpty() && pass.isEmpty()) null else ProxyCredentials(user, pass)
        }

        fun writeProxyCredentials(username: String, password: String): String =
            JsonObject(mapOf("username" to kotlinx.serialization.json.JsonPrimitive(username), "password" to kotlinx.serialization.json.JsonPrimitive(password))).toString()
    }
}

/** [text] with every one of [secrets] replaced by `[secret]`. */
internal fun scrubSecrets(text: String, secrets: Collection<String>): String =
    secrets.filter { it.length >= 4 }.sortedByDescending { it.length }.fold(text) { acc, s -> acc.replace(s, "[secret]") }

/**
 * A model whose errors can't show a provider's extra header or proxy password: a gateway that
 * echoes request headers in its error body would otherwise put them in the chat.
 */
internal class ScrubbingChatModel(private val inner: ChatModel, private val secrets: List<String>) : ChatModel {
    override val format: ApiFormat get() = inner.format

    override fun stream(request: ChatRequest): Flow<ChatEvent> = inner.stream(request).catch { e ->
        val message = e.message
        if (message != null && secrets.any { it.length >= 4 && message.contains(it) }) {
            val status = (e as? LlmException)?.httpStatus
            val retryable = (e as? LlmException)?.retryable ?: false
            // The cause is dropped: its message may hold the same text.
            throw LlmException(scrubSecrets(message, secrets), status, retryable)
        }
        throw e
    }
}
