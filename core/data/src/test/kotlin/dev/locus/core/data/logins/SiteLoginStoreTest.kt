package dev.locus.core.data.logins

import dev.locus.core.browser.LoginOffer
import dev.locus.core.datastore.SecretStore
import dev.locus.core.model.SiteLogin
import dev.locus.core.testing.TestGraph
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Saved site logins in the vault, and the save prompt's flow (docs/design/browser.md, "Saved logins"). Test values only. */
class SiteLoginStoreTest {
    private val password = "test-pass-Z9q-4410"

    @Test
    fun `a login is kept in the vault by its exact origin, one per site`() = runTest {
        val g = TestGraph(backgroundScope)
        val store = g.siteLogins
        store.upsert(SiteLogin("a", "https://Accounts.Example.com:443/login?x=1", " alice ", password))
        val saved = store.forOrigin("https://accounts.example.com")!!
        assertEquals("https://accounts.example.com", saved.origin)
        assertEquals("alice", saved.username)
        assertNull(store.forOrigin("https://example.com"), "no parent-domain match")
        assertNull(store.forOrigin("https://eu.accounts.example.com"), "no subdomain match")
        assertNull(store.forOrigin("http://accounts.example.com"), "no scheme downgrade")
        // Another login for the same site replaces it.
        store.upsert(SiteLogin("b", "https://accounts.example.com", "bob", "other-pass-Q2"))
        assertEquals(listOf("bob"), store.all().map { it.username })
        // In the vault, under its own name; nothing in the settings or the database.
        assertTrue(g.secrets.entries(SecretStore.SITE_LOGIN_PREFIX).keys.single() == SecretStore.siteLogin("b"))
    }

    @Test
    fun `only secure sites, a password and a readable authenticator key`() = runTest {
        val store = TestGraph(backgroundScope).siteLogins
        assertFailsWith<IllegalArgumentException> { store.upsert(SiteLogin("a", "http://example.org", "u", password)) }
        assertFailsWith<IllegalArgumentException> { store.upsert(SiteLogin("a", "not a site", "u", password)) }
        assertFailsWith<IllegalArgumentException> { store.upsert(SiteLogin("a", "https://example.org", "u", "")) }
        assertFailsWith<IllegalArgumentException> { store.upsert(SiteLogin("a", "https://example.org", "u", password, totpSecret = "not-a-key!")) }
        store.upsert(SiteLogin("a", "http://localhost:3000", "u", password))
        store.upsert(SiteLogin("b", "https://example.org", "u", password, totpSecret = "gezd gnbv gy3t qojq gezd gnbv gy3t qojq"))
        assertEquals(2, store.all().size)
    }

    @Test
    fun `the vault's scrubbing list has the secrets, not the origin or username`() = runTest {
        val g = TestGraph(backgroundScope)
        g.siteLogins.upsert(SiteLogin("a", "https://accounts.example.com", "alice-long-name", password, totpSecret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"))
        g.siteLogins.setNeverOffer("https://never.example.com")
        val values = g.secrets.valuesForScrubbing()
        assertTrue(password in values && "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ" in values)
        assertFalse(values.any { "accounts.example.com" in it || "alice-long-name" in it || "never.example.com" in it })
    }

    @Test
    fun `deleting and marking used`() = runTest {
        val store = TestGraph(backgroundScope).siteLogins
        store.clock = { 42 }
        store.upsert(SiteLogin("a", "https://accounts.example.com", "alice", password))
        store.markUsed("a")
        assertEquals(42, store.all().single().lastUsed)
        store.delete("a")
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `the save prompt saves what the user typed, or drops it`() = runTest {
        val g = TestGraph(backgroundScope)
        val browser = g.browser
        // Save, with the username the user corrected in the sheet.
        browser.showOffer(LoginOffer("https://accounts.example.com", "alic", false, password, 1))
        browser.saveOffer("alice")
        assertNull(browser.loginOffer.value)
        assertEquals("alice", g.siteLogins.forOrigin("https://accounts.example.com")!!.username)
        assertEquals(password, g.siteLogins.forOrigin("https://accounts.example.com")!!.password)
        // Not now: nothing saved, nothing kept.
        browser.showOffer(LoginOffer("https://mail.example.org", "bob", false, "other-pass-Q2", 1))
        browser.dismissOffer()
        assertNull(browser.loginOffer.value)
        assertNull(g.siteLogins.forOrigin("https://mail.example.org"))
        // Never for this site: remembered, and nothing saved.
        browser.showOffer(LoginOffer("https://mail.example.org", "bob", false, "other-pass-Q2", 1))
        browser.neverOfferSite()
        assertTrue(g.siteLogins.neverOffer("https://mail.example.org"))
        assertNull(g.siteLogins.forOrigin("https://mail.example.org"))
        // Answering twice does nothing more.
        browser.saveOffer("x")
        assertNull(g.siteLogins.forOrigin("https://mail.example.org"))
    }
}
