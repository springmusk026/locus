package dev.locus.core.data.repository

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The app's HTTP client is OkHttp, which follows redirects itself and drops only `Authorization` when the host
 * changes, so a key in `X-Api-Key` went to wherever a service redirected. connector_call uses [ToolCatalog.connectorClient],
 * whose engine hands every redirect back so connector_call can check it (ConnectorTransportTest).
 */
class ConnectorClientTest {
    private val loopback = InetAddress.getLoopbackAddress()

    private fun server(handler: (com.sun.net.httpserver.HttpExchange) -> Unit): HttpServer =
        HttpServer.create(InetSocketAddress(loopback, 0), 0).apply {
            createContext("/") { ex -> handler(ex); ex.close() }
            start()
        }

    @Test
    fun `the connector client doesn't follow a redirect and the app's own client does`() = runBlocking {
        val keysSeen = CopyOnWriteArrayList<String?>()
        val target = server { ex -> keysSeen += ex.requestHeaders.getFirst("X-Api-Key"); ex.sendResponseHeaders(200, -1) }
        val origin = server { ex ->
            ex.responseHeaders.add("Location", "http://${loopback.hostAddress}:${target.address.port}/steal")
            ex.sendResponseHeaders(302, -1)
        }
        try {
            val app = HttpClient(OkHttp) { engine { preconfigured = OkHttpClient() } }
            val connectors = assertNotNull(ToolCatalog.connectorClient(app))
            val url = "http://${loopback.hostAddress}:${origin.address.port}/"

            val reply = connectors.get(url) { header("X-Api-Key", "SECRET123") }
            assertEquals(302, reply.status.value)
            assertTrue(reply.headers[HttpHeaders.Location].orEmpty().endsWith("/steal"))
            assertEquals(emptyList(), keysSeen, "the redirect was followed")

            // The premise: the app's client would have delivered the key.
            app.get(url) { header("X-Api-Key", "SECRET123") }
            assertEquals(listOf<String?>("SECRET123"), keysSeen)
            connectors.close()
            app.close()
        } finally {
            origin.stop(0)
            target.stop(0)
        }
    }
}
