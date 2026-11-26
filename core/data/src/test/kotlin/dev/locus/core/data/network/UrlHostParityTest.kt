package dev.locus.core.data.network

import dev.locus.core.model.LocalNetwork
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The host every egress decision uses ([LocalNetwork.hostOf]) is the host OkHttp, the app's HTTP client,
 * connects to (issue #377). OkHttp parses URLs the WHATWG way, as the WebView does, except that it refuses
 * tabs and line breaks the WebView drops (LocalNetworkTest covers those).
 */
class UrlHostParityTest {
    @Test
    fun `hostOf agrees with OkHttp on tricky addresses`() {
        listOf(
            "https://evil.com\\@github.com/?d=secret",
            "https://evil.com\\\\@github.com/x",
            "https://github.com\\@evil.com/x",
            "https:/\\evil.com/x",
            "https:evil.com/x",
            "https:///evil.com/x",
            "https://[::ffff:7f00:1]/x",
            "https://[0:0:0:0:0:0:0:1]:8443/x",
            "https://user:pw@github.com:443/x",
            "https://github.com@evil.com/",
            "https://github.com:443@evil.com/",
            "https://evil.com#@github.com/",
            "https://evil.com?@github.com/",
            "https://Evil.COM/x",
            "http://192.168.1.5:11434/v1",
        ).forEach { url ->
            val okHttp = assertNotNull(url.toHttpUrlOrNull(), url).host
            assertEquals(okHttp, LocalNetwork.hostOf(url), url)
        }
    }
}
