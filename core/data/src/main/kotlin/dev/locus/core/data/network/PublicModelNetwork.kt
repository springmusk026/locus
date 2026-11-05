package dev.locus.core.data.network

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.locus.core.agent.tools.FetchGuard
import dev.locus.core.agent.tools.FetchNetwork
import dev.locus.core.ondevice.gguf.ModelNetwork
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.Url
import io.ktor.utils.io.jvm.javaio.toInputStream
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress
import javax.inject.Singleton

/**
 * The network GGUF downloads use: fetch_url's rules with no exceptions. The client is the app's
 * OkHttp client with [FetchDns] (every DNS answer is checked at connect time, so a name that rebinds
 * to 192.168.x.x or 127.0.0.1 fails), no proxy and no automatic redirects; [refusal] is
 * [FetchGuard.refusal] with no allowed local hosts, so a pasted URL can never reach the phone's LAN,
 * a router, or a server running in Locus's Linux (docs/design/llamacpp.md, "Getting models").
 */
class PublicModelNetwork(
    base: OkHttpClient,
    private val resolve: suspend (String) -> List<InetAddress> = FetchNetwork().resolve,
    system: Dns = base.dns,
) : ModelNetwork {
    private val client: HttpClient = FetchDns.client(base, allowedHosts = { emptySet() }, system = system)

    override suspend fun <T> get(url: Url, headers: Map<String, String>, block: suspend (ModelNetwork.Response) -> T): T =
        client.prepareGet(url) { headers.forEach { (k, v) -> header(k, v) } }.execute { response ->
            val body = response.bodyAsChannel().toInputStream()
            block(object : ModelNetwork.Response {
                override val status = response.status.value
                override fun header(name: String): String? = response.headers[name]
                override val body = body
            })
        }

    override suspend fun refusal(url: Url): String? =
        FetchGuard.refusal(url, emptySet(), resolve)?.let {
            "Blocked: ${url.host} is a local or private network address. Models download only from the public internet."
        }
}

@Module
@InstallIn(SingletonComponent::class)
internal object ModelNetworkModule {
    @Provides
    @Singleton
    fun modelNetwork(okHttp: OkHttpClient): ModelNetwork = PublicModelNetwork(okHttp)
}
