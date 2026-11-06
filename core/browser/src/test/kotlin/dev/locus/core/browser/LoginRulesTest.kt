package dev.locus.core.browser

import dev.locus.core.model.SiteLogin
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every rule a saved-login fill must pass (docs/design/browser.md, "Saved logins"). Test values only. */
class LoginRulesTest {
    private val site = "https://accounts.example.com"
    private val login = SiteLogin("l1", site, "alice", "test-pass-Z9q", null)
    private val withCode = login.copy(totpSecret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")

    private fun field(ref: String, kind: String, type: String = if ("password" in kind) "password" else "text", form: Int = 0,
        actions: List<String> = listOf("$site/session"), visible: Boolean = true, top: Boolean = true) =
        LoginForm.Field(ref, kind, type, visible, top, form, actions)

    private fun form(vararg fields: LoginForm.Field, origin: String = site, top: Boolean = true, verified: Boolean = true) =
        LoginForm(top, origin, fields.toList(), verified, "doc1")

    private fun refused(r: LoginRules.Result): String = assertIs<LoginRules.Result.Refused>(r).reason
    private fun planned(r: LoginRules.Result): LoginPlan = assertIs<LoginRules.Result.Fill>(r).plan

    // ---- The page's origin -----------------------------------------------------------------------

    @Test
    fun `only the exact origin it was saved for`() {
        assertNull(LoginRules.pageRefusal("$site/login?next=/", site))
        assertNull(LoginRules.pageRefusal("https://ACCOUNTS.example.com:443/login", site), "case and the default port don't matter")
        // Another site, a subdomain, a parent domain, a look-alike, another port: all refused.
        listOf(
            "https://evil.example/login", "https://eu.accounts.example.com/login", "https://example.com/login",
            "https://accounts.example.com.evil.example/login", "https://accounts.example.com:8443/login",
        ).forEach { url -> assertNotNull(LoginRules.pageRefusal(url, site), url) }
    }

    @Test
    fun `the call's origin must be the page's, so a model can't aim a login elsewhere`() {
        val why = LoginRules.pageRefusal("https://evil.example/login", site)!!
        assertTrue("evil.example" in why && "exact site" in why, why)
        assertNotNull(LoginRules.pageRefusal("$site/login", "https://evil.example"))
        assertNotNull(LoginRules.pageRefusal("$site/login", "not an origin"))
        assertNotNull(LoginRules.pageRefusal("$site/login", null))
    }

    @Test
    fun `http is refused, except on the phone's own loopback`() {
        assertNotNull(LoginRules.pageRefusal("http://accounts.example.com/login", "http://accounts.example.com"))
        assertNotNull(LoginRules.pageRefusal("http://accounts.example.com/login", site), "http vs https is another origin")
        assertNull(LoginRules.pageRefusal("http://localhost:3000/login", "http://localhost:3000"))
        assertNull(LoginRules.pageRefusal("http://127.0.0.1:8080/", "http://127.0.0.1:8080"))
        assertNotNull(LoginRules.pageRefusal("https://user:pw@accounts.example.com/", site), "credentials in the URL")
        assertNotNull(LoginRules.pageRefusal(null, site), "no page")
    }

    // ---- How the run got there --------------------------------------------------------------------

    @Test
    fun `a page reached from outside content needs a site the user named or opened before`() {
        // Nothing read from outside yet: fine.
        assertNull(LoginRules.reachRefusal(site, emptySet(), emptySet(), vouched = false))
        // Steered there after reading a page, and the user never named it: refused.
        val why = LoginRules.reachRefusal(site, setOf("browser_open"), setOf("news.example.org"), vouched = false)
        assertNotNull(why)
        assertTrue("didn't name" in why, why)
        // The user named the site, or its parent domain; or the chat opened it before reading anything.
        assertNull(LoginRules.reachRefusal(site, setOf("browser_open"), setOf("accounts.example.com"), vouched = false))
        assertNull(LoginRules.reachRefusal(site, setOf("browser_open"), setOf("example.com"), vouched = false))
        assertNull(LoginRules.reachRefusal(site, setOf("browser_open"), emptySet(), vouched = true))
        // A named sibling doesn't count.
        assertNotNull(LoginRules.reachRefusal(site, setOf("web_read"), setOf("www2.example.org"), vouched = false))
    }

    // ---- Which fields ---------------------------------------------------------------------------

    @Test
    fun `a sign-in form gets the username and the password`() {
        val plan = planned(LoginRules.plan(form(field("e1", "username"), field("e2", "password")), site, login))
        assertEquals(LoginPlan("e1", "e2", null), plan)
    }

    @Test
    fun `a field that isn't a password field never gets the password`() {
        // The helper says "password" but the element is a text box now: refused.
        val why = refused(LoginRules.plan(form(field("e1", "username"), field("e2", "password", type = "text")), site, login))
        assertTrue("isn't a password field" in why, why)
        // A visible text field the model might want it in is never a password target: only the username goes there.
        val plan = planned(LoginRules.plan(form(field("e3", "username")), site, login))
        assertEquals(LoginPlan("e3", null, null), plan)
    }

    @Test
    fun `a form that submits to another site is refused`() {
        val cross = field("e2", "password", actions = listOf("https://collect.evil.example/steal"))
        assertTrue("collect.evil.example" in refused(LoginRules.plan(form(field("e1", "username"), cross), site, login)))
        // A submit button's formaction counts too.
        val viaButton = field("e2", "password", actions = listOf("$site/session", "https://evil.example/x"))
        refused(LoginRules.plan(form(viaButton), site, login))
        // A sibling subdomain is another site; http is another scheme.
        refused(LoginRules.plan(form(field("e2", "password", actions = listOf("https://billing.example.com/x"))), site, login))
        refused(LoginRules.plan(form(field("e2", "password", actions = listOf("http://accounts.example.com/session"))), site, login))
        // Its parent domain, a page-relative action and javascript: stay on the site.
        planned(LoginRules.plan(form(field("e2", "password", actions = listOf("https://example.com/login"))), site, login))
        planned(LoginRules.plan(form(field("e2", "password", actions = listOf("javascript:void(0)"))), site, login))
        planned(LoginRules.plan(form(field("e2", "password", form = -1, actions = emptyList())), site, login))
    }

    @Test
    fun `fields in frames, hidden fields and other documents are never filled`() {
        // Only a password field in a frame: nothing to fill on the page.
        refused(LoginRules.plan(form(field("e2", "password", top = false)), site, login))
        // A hidden honeypot next to the real one is ignored, not counted as a second password.
        val plan = planned(LoginRules.plan(form(field("e1", "username"), field("e2", "password"), field("e9", "password", visible = false)), site, login))
        assertEquals("e2", plan.passwordRef)
        // The helper answered from a frame, or for another origin than the browser shows.
        refused(LoginRules.plan(form(field("e2", "password"), top = false), site, login))
        refused(LoginRules.plan(form(field("e2", "password"), origin = "https://evil.example"), site, login))
    }

    @Test
    fun `sign-ups, password changes and ambiguous forms go to the user`() {
        assertTrue("new password" in refused(LoginRules.plan(form(field("e1", "username"), field("e2", "new-password")), site, login)))
        assertTrue("more than one" in refused(LoginRules.plan(form(field("e2", "password"), field("e3", "password")), site, login)))
        assertTrue("several" in refused(LoginRules.plan(form(field("e2", "code"), field("e3", "code")), site, withCode)))
    }

    @Test
    fun `unverified or missing answers fail closed`() {
        assertTrue("WebView" in refused(LoginRules.plan(form(field("e2", "password"), verified = false), site, login)))
        refused(LoginRules.plan(null, site, login))
        refused(LoginRules.plan(form(), site, login))
        // A report missing a field is no report at all.
        assertNull(LoginForm.parse(Json.parseToJsonElement("""{"top":true,"origin":"$site","fields":[{"ref":"e2","kind":"password"}]}""") as JsonObject))
        assertNotNull(LoginForm.parse(Json.parseToJsonElement("""{"top":true,"origin":"$site","fields":[]}""") as JsonObject))
    }

    @Test
    fun `the one-time code goes only into a code field, and only with an authenticator key`() {
        assertEquals(LoginPlan(null, null, "e5"), planned(LoginRules.plan(form(field("e5", "code")), site, withCode)))
        val why = refused(LoginRules.plan(form(field("e5", "code")), site, login))
        assertTrue("no authenticator key" in why && "browser_ask_user" in why, why)
        // With a password on the same page and no key, the password is still filled; the code is left to the user.
        assertEquals(LoginPlan(null, "e2", null), planned(LoginRules.plan(form(field("e2", "password"), field("e5", "code")), site, login)))
    }

    @Test
    fun `same-site matches the page helper's rule`() {
        assertTrue(LoginRules.sameSite("https://accounts.example.com/x", site))
        assertTrue(LoginRules.sameSite("https://login.accounts.example.com/x", site))
        assertTrue(LoginRules.sameSite("", site))
        assertTrue(!LoginRules.sameSite("https://example.com.evil.example/", site))
        assertTrue(!LoginRules.sameSite("not a url", site))
    }
}
