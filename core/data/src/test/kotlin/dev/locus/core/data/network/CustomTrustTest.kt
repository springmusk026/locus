package dev.locus.core.data.network

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import dev.locus.core.datastore.SecretStore
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.AppSettings
import dev.locus.core.model.NetworkDefaults
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.ProviderNetwork
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
import java.security.KeyStore
import java.security.cert.CertificateException
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Imported CA certificates are trusted only for the providers that chose them. Fixtures were made
 * with keytool (see src/test/resources/network/README.txt): a test CA, a localhost server signed by
 * it, and an unrelated CA.
 */
class CustomTrustTest {
    private fun resource(name: String): ByteArray = javaClass.getResourceAsStream("/network/$name")!!.readBytes()

    private val testCa = Certificates.parse(resource("test-ca.pem")).single()
    private val otherCa = Certificates.parse(resource("other-ca.pem")).single()
    private val leaf = Certificates.parse(resource("server-leaf.pem")).single()
    private val chain = arrayOf(leaf, testCa)

    @Test
    fun `the trust manager accepts the test CA's server only when that CA is added`() {
        val system = ExtraCaTrustManager.systemTrustManager()
        assertFailsWith<CertificateException> { system.checkServerTrusted(chain, "RSA") }

        ExtraCaTrustManager(system, listOf(testCa)).checkServerTrusted(chain, "RSA")
        // Also when the server leaves the CA out of the chain it sends.
        ExtraCaTrustManager(system, listOf(testCa)).checkServerTrusted(arrayOf(leaf), "RSA")

        assertFailsWith<CertificateException> { ExtraCaTrustManager(system, listOf(otherCa)).checkServerTrusted(chain, "RSA") }
        assertFailsWith<CertificateException> { ExtraCaTrustManager(system, emptyList()).checkServerTrusted(chain, "RSA") }
    }

    @Test
    fun `a CA with the right name but another key is refused`() {
        // Same subject as the test CA, different key: the leaf's signature doesn't verify against it.
        val impostor = Certificates.parse(resource("impostor-ca.pem")).single()
        assertEquals(testCa.subjectX500Principal, impostor.subjectX500Principal)
        val tm = ExtraCaTrustManager(ExtraCaTrustManager.systemTrustManager(), listOf(impostor))
        assertFailsWith<CertificateException> { tm.checkServerTrusted(arrayOf(leaf, impostor), "RSA") }
        assertFalse(tm.chainsToExtra(arrayOf(leaf)))
    }

    @Test
    fun `system roots are still trusted and still listed`() {
        val system = ExtraCaTrustManager.systemTrustManager()
        val tm = ExtraCaTrustManager(system, listOf(testCa))
        assertTrue(tm.acceptedIssuers.size == system.acceptedIssuers.size + 1)
        assertTrue(tm.acceptedIssuers.any { it == testCa })
    }

    @Test
    fun `importing reads PEM and DER, names and fingerprints the certificate`() {
        val pem = Certificates.parse(resource("test-ca.pem")).single()
        val der = Certificates.parse(pem.encoded).single()
        assertEquals(pem, der)
        val trusted = Certificates.toTrusted(pem)
        assertEquals("Locus Test CA", trusted.subject)
        assertEquals(95, trusted.sha256.length)
        assertEquals(pem, Certificates.decode(trusted))
        assertFailsWith<IllegalArgumentException> { Certificates.parse("hello".toByteArray()) }
        assertFailsWith<IllegalArgumentException> { Certificates.parse(ByteArray(Certificates.MAX_FILE_BYTES + 1)) }
    }

    /** End to end over TLS with OkHttp: only the provider that trusts the test CA can talk to the server. */
    @Test
    fun `only the chosen provider trusts the custom CA`() = runTest {
        val server = httpsServer()
        try {
            val url = "https://127.0.0.1:${server.address.port}/v1/models"
            val ca = Certificates.toTrusted(testCa)
            val other = Certificates.toTrusted(otherCa)
            fun provider(id: String, certs: List<String>) =
                ProviderConfig(id, id, ApiFormat.OPENAI_CHAT, "https://127.0.0.1:${server.address.port}/v1", "m", network = ProviderNetwork(certificateIds = certs))
            val settings = InMemoryDataStore(AppSettings(network = NetworkDefaults(certificates = listOf(ca, other))))
            val clients = ProviderHttpClients(
                HttpClient(MockEngine { respond("shared") }),
                OkHttpProviderEngines(OkHttpClient()),
                SecretStore(InMemoryDataStore(emptyMap())),
                settings,
            )

            assertEquals("ok", clients.clientFor(provider("chosen", listOf(ca.id))).http.get(url).bodyAsText())
            assertFailsWith<SSLException> { clients.clientFor(provider("other-ca", listOf(other.id))).http.get(url) }

            // A provider with no certificates uses the shared client, which never sees the custom CA.
            val plain = provider("plain", emptyList())
            assertTrue(clients.clientFor(plain).http.engine is MockEngine)
            // A real OkHttp client without it (what the shared client is in the app) refuses the server.
            assertFailsWith<SSLException> {
                OkHttpClient().newCall(okhttp3.Request.Builder().url(url).build()).execute().close()
            }

            // "All providers" extends it to every provider, including one that picked nothing.
            settings.updateData { s -> s.copy(network = s.network.copy(certificates = s.network.certificates.map { if (it.id == ca.id) it.copy(allProviders = true) else it })) }
            assertEquals("ok", clients.clientFor(plain).http.get(url).bodyAsText())
        } finally {
            server.stop(0)
        }
    }

    private fun httpsServer(): HttpsServer {
        val password = "changeit".toCharArray()
        val keyStore = KeyStore.getInstance("PKCS12").apply { load(CustomTrustTest::class.java.getResourceAsStream("/network/server.p12"), password) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, password) }
        val context = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        return HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            httpsConfigurator = HttpsConfigurator(context)
            createContext("/") { ex ->
                val bytes = "ok".toByteArray()
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
    }
}
