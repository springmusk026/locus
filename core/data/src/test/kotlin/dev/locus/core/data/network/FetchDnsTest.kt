package dev.locus.core.data.network

import dev.locus.core.agent.ToolException
import dev.locus.core.agent.tools.FetchNetwork
import dev.locus.core.agent.tools.webTools
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** fetch_url's DNS check is pinned to the connection, so DNS rebinding can't reach the LAN. */
class FetchDnsTest {
    private val public = InetAddress.getByAddress("rebind.test", byteArrayOf(93, 184.toByte(), 216.toByte(), 34))
    private val loopback = InetAddress.getByAddress("rebind.test", byteArrayOf(127, 0, 0, 1))
    private val lan = InetAddress.getByAddress("rebind.test", byteArrayOf(192.toByte(), 168.toByte(), 1, 1))

    /** Answers in turn: the first lookup gets the first answer, and so on (the last one repeats). */
    private class Rebinding(private vararg val answers: List<InetAddress>) : Dns {
        var lookups = 0
        override fun lookup(hostname: String): List<InetAddress> = answers[minOf(lookups++, answers.size - 1)]
    }

    @Test
    fun `a public answer then a private one fails at the second lookup`() {
        val dns = Rebinding(listOf(public), listOf(loopback))
        val pinned = FetchDns({ emptySet() }, dns)
        assertEquals(listOf(public), pinned.lookup("rebind.test"))
        val e = assertFailsWith<UnknownHostException> { pinned.lookup("rebind.test") }
        assertTrue(e.message!!.startsWith("Blocked:"))
        // One private address among public ones is enough to refuse.
        assertFailsWith<UnknownHostException> { FetchDns({ emptySet() }, Rebinding(listOf(public, lan))).lookup("rebind.test") }
        // An empty answer isn't an open door either.
        assertFailsWith<UnknownHostException> { FetchDns({ emptySet() }, Rebinding(emptyList())).lookup("rebind.test") }
    }

    @Test
    fun `hosts the user set up may be local`() {
        val pinned = FetchDns({ setOf("ollama.home") }, Rebinding(listOf(lan)))
        assertEquals(listOf(lan), pinned.lookup("ollama.home"))
    }

    @Test
    fun `fetch_url refuses a rebinding host at connect time`() = runTest {
        // fetch_url's own check sees the public address; the connection's lookup gets the private one.
        val dns = Rebinding(listOf(loopback))
        val client = FetchDns.client(OkHttpClient(), { emptySet() }, dns)
        val fetch = webTools(HttpClient(OkHttp), FetchNetwork(resolve = { listOf(public) }, allowedHosts = { emptySet() }, client = client)).single()
        val e = assertFailsWith<ToolException> { fetch.execute(buildJsonObject { put("url", "http://rebind.test/admin") }) }
        assertTrue("Blocked" in e.message!!, e.message)
        // The connection's own lookup is what refused it.
        assertTrue(dns.lookups >= 1)
    }
}
