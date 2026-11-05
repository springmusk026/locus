package dev.locus.core.data.network

import io.ktor.http.Url
import kotlinx.coroutines.test.runTest
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A pasted model URL can't reach the phone's LAN or Locus's own listeners: the URL check before
 * connecting, and the DNS check at connect time (a name that rebinds to a private address).
 */
class PublicModelNetworkTest {
    private val answers = mapOf(
        "huggingface.co" to "18.239.50.16",
        "cdn.example" to "93.184.216.34",
        "router.example" to "192.168.1.1",
        "rebind.example" to "10.0.0.5",
        "metadata.example" to "169.254.169.254",
        "v6local.example" to "fd00::1",
    )
    /** Names from [answers]; IP literals as the system resolver returns them (no lookup); nothing else. */
    private val resolve: suspend (String) -> List<InetAddress> = { host ->
        val literal = host.removePrefix("[").removeSuffix("]").takeIf { ':' in it || it.all { c -> c.isDigit() || c == '.' } }
        listOfNotNull((answers[host] ?: literal)?.let(InetAddress::getByName))
    }
    private val dns = Dns { host -> listOfNotNull(answers[host]?.let(InetAddress::getByName)).ifEmpty { throw UnknownHostException(host) } }
    private val network = PublicModelNetwork(OkHttpClient(), resolve, dns)

    @Test
    fun publicHostsAreAllowed() = runTest {
        assertNull(network.refusal(Url("https://huggingface.co/a/b/resolve/main/x.gguf")))
        assertNull(network.refusal(Url("https://cdn.example/x.gguf")))
    }

    @Test
    fun localAndPrivateAddressesAreRefused() = runTest {
        listOf(
            "https://192.168.1.1/x.gguf",
            "https://127.0.0.1:8080/x.gguf",
            "https://localhost/x.gguf",
            "https://router.example/x.gguf",
            "https://rebind.example/x.gguf",
            "https://metadata.example/latest",
            "https://[::1]/x.gguf",
            "https://v6local.example/x.gguf",
        ).forEach { assertNotNull(network.refusal(Url(it)), it) }
    }

    @Test
    fun theDnsRefusesAPrivateAnswerAtConnectTime() {
        // The client's DNS is FetchDns with no allowed hosts: what a rebinding name answers when connecting.
        val fetchDns = FetchDns({ emptySet() }, dns)
        assertFailsWith<UnknownHostException> { fetchDns.lookup("rebind.example") }
        assertNotNull(fetchDns.lookup("cdn.example").single())
    }
}
