package dev.locus.core.data.network

import dev.locus.core.agent.tools.FetchGuard
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.Proxy

/**
 * fetch_url's DNS (docs/design/network.md, "fetch_url and DNS rebinding"). Every answer OkHttp gets for a
 * host goes through [FetchGuard.checkAnswer] before it connects, so the address checked is the address
 * used: a name that answered with a public address when fetch_url checked the URL, and with 192.168.x.x
 * or 127.0.0.1 when the connection is made, fails instead of reaching the LAN or Locus's own listeners.
 * Each redirect hop is a new lookup through here too.
 */
class FetchDns(
    private val allowedHosts: suspend () -> Set<String>,
    private val system: Dns = Dns.SYSTEM,
) : Dns {
    // OkHttp calls this on its own threads, where blocking is expected; the hosts come from settings.
    override fun lookup(hostname: String): List<InetAddress> =
        FetchGuard.checkAnswer(hostname, system.lookup(hostname), runBlocking { allowedHosts() }.map { it.lowercase() }.toSet())

    companion object {
        /**
         * fetch_url's client, from the app's OkHttpClient (same timeouts and pool settings): this DNS, no
         * proxy (a proxy would resolve the name itself, out of reach of the check), and no automatic
         * redirects (fetch_url follows them one checked hop at a time).
         */
        fun client(base: OkHttpClient, allowedHosts: suspend () -> Set<String>, system: Dns = base.dns): HttpClient = HttpClient(OkHttp) {
            engine {
                preconfigured = base.newBuilder()
                    .dns(FetchDns(allowedHosts, system))
                    .proxy(Proxy.NO_PROXY)
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .build()
            }
            install(HttpTimeout) { requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS }
            followRedirects = false
            expectSuccess = false
        }
    }
}
