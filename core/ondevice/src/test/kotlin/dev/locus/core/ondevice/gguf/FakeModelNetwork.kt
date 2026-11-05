package dev.locus.core.ondevice.gguf

import io.ktor.http.Url
import java.io.ByteArrayInputStream
import java.io.InputStream

/** A request the fake network saw. */
data class SeenRequest(val url: Url, val headers: Map<String, String>)

/** What the scripted server answers. */
class FakeResponse(override val status: Int, val bytes: ByteArray = ByteArray(0), private val headers: Map<String, String> = emptyMap()) : ModelNetwork.Response {
    override fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    override val body: InputStream by lazy { ByteArrayInputStream(bytes) }
}

/**
 * A scripted server instead of the network (no live calls in tests), with the private-address rule
 * applied to a few host names as `PublicModelNetwork` applies it to resolved addresses.
 */
class FakeModelNetwork(private val handler: (SeenRequest) -> FakeResponse) : ModelNetwork {
    val requests = mutableListOf<SeenRequest>()

    override suspend fun <T> get(url: Url, headers: Map<String, String>, block: suspend (ModelNetwork.Response) -> T): T {
        val req = SeenRequest(url, headers)
        requests += req
        return block(handler(req))
    }

    override suspend fun refusal(url: Url): String? =
        if (url.host in PRIVATE) "Blocked: ${url.host} is a local or private network address." else null

    companion object {
        val PRIVATE = setOf("192.168.1.1", "localhost", "10.0.0.2", "router.lan", "127.0.0.1")
    }
}
