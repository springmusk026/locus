package dev.locus.core.agent

import dev.locus.core.agent.tools.webTools
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which hosts count as ones the user named: URLs every parser reads the same way, and registrable
 * domains rather than shared hosting names (docs/design/taint.md, "Hosts you named").
 */
class HostTrustTest {
    private val fetch = webTools(HttpClient(MockEngine { respond("") })).single()
    private val named = setOf("github.com")

    private suspend fun disclosure(url: String) =
        Taint.disclosureReason(fetch, buildJsonObject { put("url", url) }, setOf("read_file"), named, emptySet())

    private suspend fun tainted(url: String) =
        Taint.reason(fetch, buildJsonObject { put("url", url) }, setOf("fetch_url"), named)

    @Test
    fun `a backslash or user info URL never passes as the named host`() = runTest {
        val tricks = listOf(
            "https://evil.com\\@github.com/?d=secret",
            "https://evil.com\\\\@github.com/x",
            "https://github.com\\@evil.com/x",
            "https://github.com@evil.com/x",
            "https:/\\evil.com/x",
            "https:evil.com/x",
        )
        tricks.forEach { url ->
            // After a private read and after outside content, the call asks.
            assertNotNull(disclosure(url), url)
            assertNotNull(tainted(url), url)
            // In a reply, the link asks too.
            assertNotNull(LinkGuard.check(url, outsideContent = true, namedHosts = named), url)
        }
        // An ordinary URL on the named host is unchanged.
        assertNull(disclosure("https://github.com/a/b?c=d"))
        assertNull(tainted("https://docs.github.com/x"))
        assertNull(LinkGuard.check("https://github.com/x", outsideContent = true, namedHosts = named))
    }

    @Test
    fun `an IPv4-mapped loopback is read as the loopback it is`() = runTest {
        assertNull(tainted("http://[::ffff:7f00:1]:3000/")) // the phone's own server, as 127.0.0.1 is
        assertNotNull(tainted("http://[::ffff:8.8.8.8]/")) // another host asks
    }

    @Test
    fun `naming a shared hosting domain trusts no one else's subdomain`() {
        val hosts = Taint.hostsIn(listOf("deploy it on github.io please", "or vercel.app", "see https://pages.dev/ and bbc.co.uk and co.uk"))
        assertEquals(setOf("bbc.co.uk"), hosts)
        listOf("evil.github.io", "attacker.vercel.app", "x.pages.dev", "evil.co.uk", "evil.bbc.co.uk.example.com").forEach {
            assertFalse(Taint.trusted(it, hosts), it)
        }
        // A suffix that was set up directly covers itself and not the sites below it.
        val set = setOf("github.io", "co.uk")
        assertTrue(Taint.trusted("github.io", set))
        assertFalse(Taint.trusted("evil.github.io", set))
        assertFalse(Taint.trusted("evil.co.uk", set))
    }

    @Test
    fun `a registrable domain still covers its subdomains`() {
        val hosts = Taint.hostsIn(listOf("open github.com and https://myname.github.io/blog and bbc.co.uk"))
        assertTrue(Taint.trusted("docs.github.com", hosts))
        assertTrue(Taint.trusted("myname.github.io", hosts))
        assertTrue(Taint.trusted("x.myname.github.io", hosts))
        assertFalse(Taint.trusted("other.github.io", hosts))
        assertTrue(Taint.trusted("news.bbc.co.uk", hosts))
    }

    @Test
    fun `public suffixes`() {
        listOf(
            "github.io", "vercel.app", "pages.dev", "netlify.app", "ngrok.io", "herokuapp.com", "web.app", "firebaseapp.com", "workers.dev",
            "onrender.com", "azurewebsites.net", "s3.amazonaws.com", "co.uk", "com.au", "co.jp", "com",
        ).forEach { assertTrue(PublicSuffixes.isPublicSuffix(it), it) }
        listOf("github.com", "bbc.co.uk", "evil.github.io", "example.org", "locus.dev").forEach { assertFalse(PublicSuffixes.isPublicSuffix(it), it) }
    }
}
