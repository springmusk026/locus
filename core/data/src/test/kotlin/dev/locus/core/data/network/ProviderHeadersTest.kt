package dev.locus.core.data.network

import dev.locus.core.datastore.SecretStore
import dev.locus.core.llm.ChatRequest
import dev.locus.core.llm.LlmException
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.AppSettings
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.ProviderNetwork
import dev.locus.core.model.Role
import dev.locus.core.testing.InMemoryDataStore
import dev.locus.core.testing.TestGraph
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Extra provider headers: sent, kept in the vault, never shown, and never following a redirect. */
class ProviderHeadersTest {
    private val openRouter = ProviderConfig(
        "or", "OpenRouter", ApiFormat.OPENAI_CHAT, "https://openrouter.example/api/v1", "m",
        network = ProviderNetwork(headerNames = listOf("HTTP-Referer", "X-Title")),
    )
    private val referer = "https://locus.example/app-referer"
    private val title = "Locus-Title-Value"

    private class Fixture(val clients: ProviderHttpClients, val sent: MutableList<HttpRequestData>, val shared: HttpClient, val secrets: SecretStore)

    private fun fixture(
        settings: AppSettings = AppSettings(),
        respond: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData = { respond("{}", HttpStatusCode.OK) },
    ): Fixture {
        val sent = mutableListOf<HttpRequestData>()
        val shared = HttpClient(MockEngine { respond("{}") })
        val secrets = SecretStore(InMemoryDataStore(emptyMap()))
        val clients = ProviderHttpClients(shared, { _, _ -> MockEngine { req -> sent += req; respond(req) } }, secrets, InMemoryDataStore(settings))
        return Fixture(clients, sent, shared, secrets)
    }

    @Test
    fun `extra headers are added to every request of that provider`() = runTest {
        val f = fixture()
        f.secrets.put(SecretStore.providerHeader("or", "HTTP-Referer"), referer)
        f.secrets.put(SecretStore.providerHeader("or", "X-Title"), title)

        f.clients.clientFor(openRouter).http.get("https://openrouter.example/api/v1/models")

        val req = f.sent.single()
        assertEquals(referer, req.headers["HTTP-Referer"])
        assertEquals(title, req.headers["X-Title"])
    }

    @Test
    fun `a provider without network settings uses the shared client`() = runTest {
        val f = fixture()
        val plain = openRouter.copy(network = ProviderNetwork())
        assertSame(f.shared, f.clients.clientFor(plain).http)
        assertTrue(f.clients.clientFor(plain).secretValues.isEmpty())
    }

    @Test
    fun `the adapter's credential header wins unless replacing it is allowed`() = runTest {
        val f = fixture()
        val gateway = openRouter.copy(network = ProviderNetwork(headerNames = listOf("Authorization", "X-Title")))
        f.secrets.put(SecretStore.providerHeader("or", "Authorization"), "Bearer gateway-token-1")
        f.secrets.put(SecretStore.providerHeader("or", "X-Title"), title)

        // Not allowed: the header isn't even part of the plan, and the adapter's own value is sent.
        f.clients.clientFor(gateway).http.get("https://openrouter.example/x") { header("Authorization", "Bearer provider-key") }
        assertEquals(listOf("Bearer provider-key"), f.sent.last().headers.getAll("Authorization"))
        assertEquals(title, f.sent.last().headers["X-Title"])

        // Allowed: the gateway's credential replaces it (one value, not two).
        val allowed = gateway.copy(network = gateway.network.copy(allowAuthOverride = true))
        f.clients.clientFor(allowed).http.get("https://openrouter.example/x") { header("Authorization", "Bearer provider-key") }
        assertEquals(listOf("Bearer gateway-token-1"), f.sent.last().headers.getAll("Authorization"))

        // A non-credential header the adapter set is never replaced.
        f.clients.clientFor(gateway).http.get("https://openrouter.example/x") { header("X-Title", "adapter") }
        assertEquals(listOf("adapter"), f.sent.last().headers.getAll("X-Title"))
    }

    @Test
    fun `a ChatGPT sign-in can't be replaced by a header`() = runTest {
        val plan = NetworkPlan.resolve(
            ProviderConfig("c", "ChatGPT", ApiFormat.CHATGPT, "https://api.openai.com/v1", "m", network = ProviderNetwork(headerNames = listOf("Authorization", "X-Title"), allowAuthOverride = true)),
            dev.locus.core.model.NetworkDefaults(),
        )
        assertEquals(listOf("X-Title"), plan.headerNames)
        assertFalse(plan.allowAuthOverride)
    }

    @Test
    fun `forbidden names in a hand-edited settings file are never sent`() = runTest {
        val f = fixture()
        val tampered = openRouter.copy(network = ProviderNetwork(headerNames = listOf("Host", "Content-Length", "Transfer-Encoding", "Proxy-Authorization", "X-Title")))
        listOf("Host", "Content-Length", "Transfer-Encoding", "Proxy-Authorization", "X-Title").forEach { f.secrets.put(SecretStore.providerHeader("or", it), "evil.example") }

        f.clients.clientFor(tampered).http.get("https://openrouter.example/x")

        val req = f.sent.single()
        assertEquals("openrouter.example", req.url.host)
        assertNull(req.headers["Host"])
        assertNull(req.headers["Proxy-Authorization"])
        assertNull(req.headers["Transfer-Encoding"])
        assertEquals("evil.example", req.headers["X-Title"])
        // And the repository never stores them in the first place.
        assertEquals(listOf("X-Title"), NetworkSecretEdits.sanitize(tampered.network).headerNames)
    }

    @Test
    fun `redirects are not followed when a provider sends extra headers`() = runTest {
        val f = fixture { req ->
            if (req.url.host == "openrouter.example") respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://elsewhere.example/steal"))
            else respond("{}")
        }
        f.secrets.put(SecretStore.providerHeader("or", "X-Title"), title)

        val response = f.clients.clientFor(openRouter).http.get("https://openrouter.example/api/v1/models")

        assertEquals(HttpStatusCode.Found, response.status)
        assertEquals(listOf("openrouter.example"), f.sent.map { it.url.host }, "the redirect target was contacted")
        assertTrue(NetworkPlan.resolve(openRouter, dev.locus.core.model.NetworkDefaults()).followRedirects.not())
        assertTrue(NetworkPlan.resolve(openRouter.copy(network = ProviderNetwork()), dev.locus.core.model.NetworkDefaults()).followRedirects)
    }

    @Test
    fun `OkHttp's own redirects are off too`() {
        val plan = NetworkPlan(headerNames = listOf("X-Title"))
        val built = OkHttpProviderEngines.configure(okhttp3.OkHttpClient.Builder(), plan, null).build()
        assertFalse(built.followRedirects)
        assertFalse(built.followSslRedirects)
        val proxyOnly = OkHttpProviderEngines.configure(okhttp3.OkHttpClient.Builder(), NetworkPlan(proxy = dev.locus.core.model.ProxyConfig(host = "p", port = 1)), null).build()
        assertTrue(proxyOnly.followRedirects)
    }

    @Test
    fun `a changed header value builds a new client and closes the old one`() = runTest {
        val f = fixture()
        f.secrets.put(SecretStore.providerHeader("or", "X-Title"), "one")
        val first = f.clients.clientFor(openRouter).http
        assertSame(first, f.clients.clientFor(openRouter).http)
        f.secrets.put(SecretStore.providerHeader("or", "X-Title"), "two")
        val second = f.clients.clientFor(openRouter).http
        assertFalse(first === second)
        second.get("https://openrouter.example/x")
        assertEquals("two", f.sent.last().headers["X-Title"])
    }

    @Test
    fun `values are never in settings, plans, cache keys or debug strings`() = runTest {
        val graph = TestGraph(backgroundScope)
        graph.settingsRepository.saveProvider(openRouter, "sk-or-key-123456", NetworkSecretEdits(mapOf("HTTP-Referer" to referer, "X-Title" to title)))

        val stored = graph.settingsStore.value.toString()
        assertFalse(referer in stored || title in stored, "a header value reached the settings file")
        assertEquals(referer, graph.secrets.get(SecretStore.providerHeader("or", "http-referer")))
        assertEquals(title, graph.secrets.get(SecretStore.providerHeader("or", "X-TITLE")))

        val plan = NetworkPlan.resolve(graph.settingsStore.value.providers.single(), graph.settingsStore.value.network)
        assertFalse(referer in plan.toString() || title in plan.toString())
        assertFalse(title in HeaderValues(mapOf("X-Title" to title)).toString())
        assertFalse(title in NetworkSecretEdits(mapOf("X-Title" to title)).toString())
        assertFalse("pw-secret" in ProxyCredentials("u", "pw-secret").toString())
        // Header values don't count as the provider's API key.
        assertEquals(setOf("or"), graph.settingsRepository.providersWithKeys.first())
    }

    @Test
    fun `removed headers and deleted providers leave nothing in the vault`() = runTest {
        val graph = TestGraph(backgroundScope)
        val repo = graph.settingsRepository
        repo.saveProvider(openRouter, null, NetworkSecretEdits(mapOf("HTTP-Referer" to referer, "X-Title" to title)))
        // Saving again without values keeps them.
        repo.saveProvider(openRouter.copy(model = "m2"), null)
        assertEquals(title, graph.secrets.get(SecretStore.providerHeader("or", "X-Title")))

        repo.saveProvider(openRouter.copy(network = ProviderNetwork(headerNames = listOf("X-Title"))), null)
        assertNull(graph.secrets.get(SecretStore.providerHeader("or", "HTTP-Referer")))
        assertEquals(title, graph.secrets.get(SecretStore.providerHeader("or", "X-Title")))

        repo.deleteProvider("or")
        assertTrue(graph.secrets.names.first().none { it.startsWith(SecretStore.PROVIDER_HEADER_PREFIX) })
    }

    @Test
    fun `a gateway that echoes a header in its error can't put it in the chat`() = runTest {
        val graph = TestGraph(backgroundScope)
        graph.http = { req ->
            respond("""{"error":{"message":"bad header X-Title: ${req.headers["X-Title"]} for key ${req.headers["Authorization"]}"}}""", HttpStatusCode.BadRequest, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        graph.settingsRepository.saveProvider(openRouter, "sk-or-key-123456", NetworkSecretEdits(mapOf("HTTP-Referer" to referer, "X-Title" to title)))
        val provider = graph.settingsRepository.current().providers.single()

        val model = graph.access.chatModel(provider)
        val history = listOf(Message("m", "chat", Role.USER, listOf(Part.Text("hi")), 0))
        val error = assertFailsWith<LlmException> { model.stream(ChatRequest("sys", history, emptyList())).toList() }

        assertEquals(title, graph.requests.last().headers["X-Title"], "the header was sent")
        assertFalse(title in error.message.orEmpty(), "the header value reached the error: ${error.message}")
        assertTrue("[secret]" in error.message.orEmpty())
        assertEquals(400, error.httpStatus)
    }
}
