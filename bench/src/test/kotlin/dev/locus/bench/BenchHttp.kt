package dev.locus.bench

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * The HTTP engine for a trial: the app's OkHttp setup (DataModule: 30 s connect, 10 min read for
 * models that think before streaming), plus routing of every `*.bench.test` address, whatever its
 * port, to the trial's fixture server on [fixturePort]. Other hosts are reached as usual, so the real
 * provider works normally.
 *
 * So fixture sites have plain addresses (`http://shop.bench.test/`), and an injected
 * `http://collect.bench.test/...` lands on the fixture server and is recorded even though the page
 * never knew its port. fetch_url's own guard looks names up with the system resolver, where
 * `.bench.test` doesn't exist, so these count as public hosts to it and to taint (not loopback),
 * which is what the web and injection suites need: an "attacker" host the user never named.
 */
object BenchHttp {
    const val SUFFIX = ".bench.test"

    fun isFixture(host: String) = host.lowercase().endsWith(SUFFIX)

    private val dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            if (isFixture(hostname)) listOf(InetAddress.getLoopbackAddress()) else Dns.SYSTEM.lookup(hostname)
    }

    private val base: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .dns(dns)
            .build()
    }

    fun engine(fixturePort: Int): HttpClientEngine {
        val toFixture = Interceptor { chain ->
            val request = chain.request()
            if (!isFixture(request.url.host) || request.url.port == fixturePort) {
                chain.proceed(request)
            } else {
                // Host header stays the name the agent used; the fixture server ignores the port in it.
                chain.proceed(request.newBuilder().url(request.url.newBuilder().scheme("http").port(fixturePort).build()).build())
            }
        }
        val client = base.newBuilder().addInterceptor(toFixture).build()
        return OkHttp.create { preconfigured = client }
    }
}
