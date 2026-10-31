package dev.locus.core.data.network

import com.sun.net.httpserver.HttpServer
import dev.locus.core.datastore.SecretStore
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.AppSettings
import dev.locus.core.model.NetworkDefaults
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.ProviderNetwork
import dev.locus.core.model.ProxyConfig
import dev.locus.core.model.ProxyMode
import dev.locus.core.model.ProxyType
import dev.locus.core.testing.InMemoryDataStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.Base64
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which proxy a provider uses, its credentials, and that nothing on this device goes through one. */
class ProxySelectionTest {
    private val corp = ProxyConfig(ProxyType.HTTP, "proxy.corp.example", 3128)
    private val socks = ProxyConfig(ProxyType.SOCKS5, "socks.example", 1080, hasCredentials = true)
    private fun provider(network: ProviderNetwork = ProviderNetwork()) =
        ProviderConfig("p1", "P", ApiFormat.OPENAI_CHAT, "https://api.example/v1", "m", network = network)

    @Test
    fun `a provider follows the global default, its own proxy, or none`() {
        val defaults = NetworkDefaults(proxy = corp)
        assertEquals(corp, NetworkPlan.resolve(provider(), defaults).proxy)
        assertNull(NetworkPlan.resolve(provider(), NetworkDefaults()).proxy)
        assertNull(NetworkPlan.resolve(provider(ProviderNetwork(proxyMode = ProxyMode.DIRECT)), defaults).proxy)

        val own = NetworkPlan.resolve(provider(ProviderNetwork(proxyMode = ProxyMode.CUSTOM, proxy = socks)), defaults)
        assertEquals(socks, own.proxy)
        assertEquals("p1", own.proxyScope, "a provider's own proxy uses its own credentials")

        val viaDefault = NetworkPlan.resolve(provider(), NetworkDefaults(proxy = corp.copy(hasCredentials = true)))
        assertEquals(SecretStore.DEFAULT_PROXY_SCOPE, viaDefault.proxyScope)
        // An invalid custom proxy means no proxy, never a half-set one.
        assertNull(NetworkPlan.resolve(provider(ProviderNetwork(proxyMode = ProxyMode.CUSTOM, proxy = corp.copy(port = 0))), defaults).proxy)
        assertNull(NetworkPlan.resolve(provider(ProviderNetwork(proxyMode = ProxyMode.CUSTOM, proxy = corp)), defaults).proxyScope)
    }

    @Test
    fun `loopback never goes through the proxy`() {
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.corp.example", 3128))
        val selector = LoopbackBypassProxySelector(proxy)
        listOf(
            "http://localhost:1455/auth/callback", "http://127.0.0.1:8080/", "http://127.5.6.7/", "http://[::1]:3000/",
            "http://[0:0:0:0:0:0:0:1]/", "http://app.localhost/", "http://LOCALHOST./", "http://[::ffff:127.0.0.1]/",
        ).forEach { assertEquals(listOf(Proxy.NO_PROXY), selector.select(URI(it)), it) }
        listOf(
            "https://api.openai.com/v1", "http://192.168.1.10:11434/v1", "http://128.0.0.1/", "http://localhost.evil.example/",
            "http://127.0.0.1.nip.io/", "http://[::2]/", "http://[2001:db8::1]/",
        ).forEach { assertEquals(listOf(proxy), selector.select(URI(it)), it) }
    }

    @Test
    fun `SOCKS credentials answer only their own proxy and only for SOCKS5`() {
        SocksCredentials.register(socks, ProxyCredentials("alice", "s3cret-pw"))
        try {
            val answer = assertNotNull(SocksCredentials.answer("SOCKS5", "socks.example", 1080))
            assertEquals("alice", answer.userName)
            assertEquals("s3cret-pw", String(answer.password))
            assertNull(SocksCredentials.answer("SOCKS5", "socks.example", 1081))
            assertNull(SocksCredentials.answer("SOCKS5", "other.example", 1080))
            assertNull(SocksCredentials.answer("http", "socks.example", 1080))
            assertNull(SocksCredentials.answer(null, "socks.example", 1080))
        } finally {
            SocksCredentials.unregister(socks)
        }
        assertNull(SocksCredentials.answer("SOCKS5", "socks.example", 1080))
    }

    @Test
    fun `stored proxy credentials round-trip and anything malformed reads as none`() {
        val stored = ProviderHttpClients.writeProxyCredentials("bob", "p:w\"d")
        assertEquals(ProxyCredentials("bob", "p:w\"d"), ProviderHttpClients.readProxyCredentials(stored))
        assertNull(ProviderHttpClients.readProxyCredentials(null))
        assertNull(ProviderHttpClients.readProxyCredentials("not json"))
        assertNull(ProviderHttpClients.readProxyCredentials("""{"username":"","password":""}"""))
    }

    /**
     * End to end with OkHttp: a local HTTP proxy that demands Basic auth. A provider's request goes to
     * it with the vault's credentials; a request to 127.0.0.1 goes straight to the server.
     */
    @Test
    fun `requests go through an HTTP proxy with its credentials, loopback goes direct`() = runTest {
        val seen = Collections.synchronizedList(mutableListOf<Pair<String, String?>>())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val auth = ex.requestHeaders.getFirst("Proxy-Authorization")
            seen += ex.requestURI.toString() to auth
            val absolute = ex.requestURI.isAbsolute
            val (status, body) = when {
                absolute && auth == null -> 407 to ""
                absolute -> 200 to "via-proxy"
                else -> 200 to "direct"
            }
            if (status == 407) ex.responseHeaders.add("Proxy-Authenticate", "Basic realm=\"corp\"")
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
        }
        server.start()
        try {
            val port = server.address.port
            val proxy = ProxyConfig(ProxyType.HTTP, "127.0.0.1", port, hasCredentials = true)
            val secrets = SecretStore(InMemoryDataStore(emptyMap()))
            secrets.put(SecretStore.proxyCredentials(SecretStore.DEFAULT_PROXY_SCOPE), ProviderHttpClients.writeProxyCredentials("alice", "s3cret-pw"))
            val clients = ProviderHttpClients(
                HttpClient(MockEngine { respond("shared") }),
                OkHttpProviderEngines(OkHttpClient()),
                secrets,
                InMemoryDataStore(AppSettings(network = NetworkDefaults(proxy = proxy))),
            )

            val remote = ProviderConfig("p1", "P", ApiFormat.OPENAI_CHAT, "http://api.example.test/v1", "m")
            val viaProxy = clients.clientFor(remote).http.get("http://api.example.test/v1/models")
            assertEquals("via-proxy", viaProxy.bodyAsText())
            assertEquals("http://api.example.test/v1/models", seen.last().first)
            val expected = "Basic " + Base64.getEncoder().encodeToString("alice:s3cret-pw".toByteArray())
            assertEquals(expected, seen.last().second)

            val local = remote.copy(id = "p2", baseUrl = "http://127.0.0.1:$port/v1")
            val direct = clients.clientFor(local).http.get("http://127.0.0.1:$port/v1/models")
            assertEquals("direct", direct.bodyAsText())
            assertEquals("/v1/models", seen.last().first)
            assertTrue(seen.none { it.first.contains("127.0.0.1") && it.first.startsWith("http") }, "a loopback request reached the proxy")
        } finally {
            server.stop(0)
        }
    }
}
