package dev.locus.core.browser

import dev.locus.core.agent.Taint
import dev.locus.core.model.SiteLogin
import dev.locus.core.model.SiteOrigin
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * The user's saved site logins, as the browser sees them (docs/design/browser.md, "Saved logins"). The
 * app's implementation keeps them in the encrypted vault (`SiteLoginStore` in core/data).
 */
interface SiteLogins {
    /** The login saved for exactly [origin], or null. No subdomain or wildcard matching. */
    suspend fun forOrigin(origin: String): SiteLogin?

    /** Every saved login. */
    suspend fun all(): List<SiteLogin>

    /** Records that Locus filled [id] now. */
    suspend fun markUsed(id: String)

    /** Saves (or replaces the password of) the login for [origin] and [username]. */
    suspend fun save(origin: String, username: String, password: String)

    /** Whether the user said never to offer saving a login for [origin]. */
    suspend fun neverOffer(origin: String): Boolean

    /** The user said never to offer saving a login for [origin]. */
    suspend fun setNeverOffer(origin: String)

    /** No logins: for tests and builds without a vault. */
    object None : SiteLogins {
        override suspend fun forOrigin(origin: String): SiteLogin? = null
        override suspend fun all(): List<SiteLogin> = emptyList()
        override suspend fun markUsed(id: String) = Unit
        override suspend fun save(origin: String, username: String, password: String) = Unit
        override suspend fun neverOffer(origin: String) = false
        override suspend fun setNeverOffer(origin: String) = Unit
    }
}

/**
 * Takes saved logins' secrets out of text the model or the user would see: every browser tool result,
 * its errors and its approval reasons ([BrowserTools]). A backstop: the fill never writes a secret into
 * a result, but the page itself could copy a filled password into visible text, its title, its console
 * or a URL. Exact values only: each password, each authenticator key, and the codes of the previous,
 * current and next time step. Usernames aren't secret and stay.
 */
class LoginScrubber(values: Collection<String>) {
    /** Longest first, so a value inside a longer one doesn't leave the rest of the longer one behind. */
    private val secrets = values.filter { it.length >= MIN_LENGTH }.distinct().sortedByDescending { it.length }

    fun scrub(text: String): String {
        if (secrets.isEmpty()) return text
        var out = text
        for (s in secrets) if (out.contains(s)) out = out.replace(s, HIDDEN)
        return out
    }

    val isEmpty: Boolean get() = secrets.isEmpty()

    companion object {
        const val HIDDEN = "[saved login secret hidden]"

        /** Shorter values (a one-character test password) would garble ordinary text; nothing real is that short. */
        private const val MIN_LENGTH = 4

        /** The scrubber for [logins] at [epochSeconds]. */
        fun of(logins: Collection<SiteLogin>, epochSeconds: Long): LoginScrubber = LoginScrubber(
            logins.flatMap { l ->
                listOf(l.password) + l.totpSecret?.let { key -> listOf(key) + Totp.codesAround(key, epochSeconds) }.orEmpty()
            },
        )
    }
}

/**
 * What the page helper (`__locus.loginForm`) reports about the page's sign-in fields. Values never come
 * back, only what the fields are. Parsed strictly: anything missing or mistyped makes the whole report
 * unusable, and the fill refuses ([LoginRules.plan]).
 */
data class LoginForm(
    /** The helper answered in the top-level frame. */
    val top: Boolean,
    /** `location.origin` as the page reports it; checked against the browser's own record too. */
    val origin: String,
    val fields: List<Field>,
    /** Whether the answer came from the helper that ran before the page's scripts ([PageReply.verified]). */
    val verified: Boolean,
    val doc: String?,
) {
    data class Field(
        val ref: String,
        /** "username", "password", "new-password" or "code", as the helper classified it. */
        val kind: String,
        /** The element's `type` now. */
        val type: String,
        /** Visible on the page (not a hidden honeypot). */
        val visible: Boolean,
        /** In the top-level document, not a frame. */
        val topDocument: Boolean,
        /** Index of its form on the page, or -1 outside any form. */
        val form: Int,
        /** Where its form submits: the form's action and every submit button's formaction, resolved. Empty outside a form. */
        val actions: List<String>,
    )

    companion object {
        fun parse(o: JsonObject, reply: PageReply? = null): LoginForm? {
            val top = o.bool("top") ?: return null
            val origin = o.str("origin") ?: return null
            val fields = (o["fields"] as? JsonArray ?: return null).map { e ->
                val f = e as? JsonObject ?: return null
                Field(
                    ref = f.str("ref") ?: return null,
                    kind = f.str("kind") ?: return null,
                    type = f.str("type") ?: return null,
                    visible = f.bool("visible") ?: return null,
                    topDocument = f.bool("topDocument") ?: return null,
                    form = (f["form"] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.toIntOrNull() ?: return null,
                    actions = (f["actions"] as? JsonArray ?: return null).map { a -> (a as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null },
                )
            }
            return LoginForm(top, origin, fields, reply?.verified == true, reply?.doc)
        }

        private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
    }
}

/** Which fields get which value; refs are the page helper's. Any may be null when the page has no such field. */
data class LoginPlan(val usernameRef: String?, val passwordRef: String?, val codeRef: String?) {
    val isEmpty: Boolean get() = usernameRef == null && passwordRef == null && codeRef == null
}

/**
 * Every rule a saved-login fill must pass, in Kotlin, failing closed (docs/design/browser.md, "Saved logins").
 * Pure, so each rule has a unit test (`LoginRulesTest`).
 */
object LoginRules {
    /**
     * Why no saved login may be filled on the page at [pageUrl] (the browser's own record of the active
     * tab's top frame) for the call that names [named] (the `origin` argument), or null when it may.
     */
    fun pageRefusal(pageUrl: String?, named: String?): String? {
        val page = SiteOrigin.of(pageUrl) ?: return "No web page is open in this tab. Open the site's sign-in page first."
        val asked = SiteOrigin.of(named) ?: return "Give the saved login's origin exactly as the snapshot names it, e.g. https://github.com."
        if (asked != page) return "This page is on $page, not $asked. Locus fills a saved login only on the exact site it was saved for."
        if (!SiteOrigin.secureEnough(page)) return "This page isn't HTTPS ($page), so Locus won't fill a saved login on it."
        return null
    }

    /**
     * Why the page may not get a saved login because of how the run got there, or null. Once a run has
     * read outside content ([sources]), a page could have steered it to a sign-in page the user never
     * meant: so the page's host must be one the user named in this chat ([namedHosts]), or one this
     * chat opened before it read anything from outside ([vouched]; see [AgentBrowser.vouch]).
     */
    fun reachRefusal(origin: String, sources: Set<String>, namedHosts: Set<String>, vouched: Boolean): String? {
        if (sources.isEmpty() || vouched) return null
        val host = SiteOrigin.host(origin).let { if (it.startsWith("[")) it.substringBefore("]").removePrefix("[") else it.substringBefore(':') }
        if (Taint.trusted(host, namedHosts)) return null
        return "Locus reached $host while working from content it read, and you didn't name that site in this chat, so it won't fill a " +
            "saved login there. Ask the user to name the site (e.g. \"sign in to $host\") or to sign in themselves with browser_ask_user."
    }

    /**
     * Which fields to fill for [login] on the page [form] describes, whose origin is [origin]; or why not.
     * The password goes only into the one visible `type=password` field of a sign-in form (not a sign-up or
     * password change, which has a new-password field), whose form submits to the same site. The code goes
     * only into a visible one-time-code field. Everything must be in the top-level document.
     */
    fun plan(form: LoginForm?, origin: String, login: SiteLogin): Result {
        if (form == null) return Result.Refused("Locus couldn't read this page's sign-in form (the page didn't answer). Take a new snapshot and try again.")
        if (!form.verified) return Result.Refused("This phone's WebView can't protect Locus's page check, so it won't fill a saved login here. Hand over with browser_ask_user.")
        if (!form.top) return Result.Refused("The sign-in form isn't in the page's top frame, so Locus won't fill it.")
        if (SiteOrigin.of(form.origin) != origin) return Result.Refused("The page reports another site (${form.origin}) than the browser shows ($origin), so Locus won't fill it.")
        val usable = form.fields.filter { it.visible && it.topDocument }
        if (usable.any { it.kind == "new-password" }) {
            return Result.Refused("This form sets a new password (a sign-up or password change). Locus only fills saved logins into sign-in forms; hand over with browser_ask_user.")
        }
        val passwords = usable.filter { it.kind == "password" }
        if (passwords.size > 1) return Result.Refused("This page has more than one password field, so Locus can't tell which is the sign-in. Hand over with browser_ask_user.")
        val password = passwords.singleOrNull()
        if (password != null) {
            if (password.type != "password") return Result.Refused("The password field isn't a password field (type=${password.type}), so Locus won't fill it.")
            actionRefusal(password, origin)?.let { return Result.Refused(it) }
        }
        val username = usable.filter { it.kind == "username" }.let { users ->
            // The one in the password's form; without a password field, the page's only one.
            if (password != null) users.firstOrNull { it.form == password.form && password.form >= 0 } ?: users.singleOrNull().takeIf { password.form < 0 }
            else users.singleOrNull()
        }
        username?.let { u -> actionRefusal(u, origin)?.let { return Result.Refused(it) } }
        val codes = usable.filter { it.kind == "code" }
        val code = if (codes.size == 1) codes.single() else null
        if (codes.size > 1) return Result.Refused("This page has several one-time-code fields, so Locus can't tell which to fill. Hand over with browser_ask_user.")
        code?.let { c -> actionRefusal(c, origin)?.let { return Result.Refused(it) } }
        val codeRef = code?.ref?.takeIf { login.totpSecret != null }
        if (code != null && login.totpSecret == null && password == null) {
            return Result.Refused(
                "This page asks for a one-time code, and the saved login for ${SiteOrigin.host(origin)} has no authenticator key. " +
                    "If the code comes by SMS, email or an app prompt, hand over with browser_ask_user.",
            )
        }
        val plan = LoginPlan(username?.ref, password?.ref, codeRef)
        if (plan.isEmpty) {
            return Result.Refused(
                "There's no sign-in field on this page for Locus to fill. Open the sign-in page first; if the site signs in with a passkey, " +
                    "an SMS code or an app prompt, hand over with browser_ask_user.",
            )
        }
        return Result.Fill(plan)
    }

    /** Why [field]'s form could send it to another site, or null. A field outside any form has no action to check. */
    private fun actionRefusal(field: LoginForm.Field, origin: String): String? {
        val elsewhere = field.actions.firstOrNull { !sameSite(it, origin) } ?: return null
        val to = SiteOrigin.of(elsewhere) ?: elsewhere.take(80)
        return "This form sends what's typed to $to, not ${SiteOrigin.host(origin)}, so Locus won't fill the saved login into it."
    }

    /**
     * Whether [actionUrl] goes to the same site as [origin]: the same scheme, and the same host or one
     * a dot-suffix of the other (`https://example.com` posting to `https://login.example.com`). No public
     * suffix list, so a parent or child domain counts; a sibling (`a.example.com` to `b.example.com`)
     * doesn't. A `javascript:` or empty action stays on the page.
     */
    fun sameSite(actionUrl: String, origin: String): Boolean {
        val a = actionUrl.trim()
        if (a.isEmpty() || a.startsWith("javascript:", ignoreCase = true)) return true
        val to = SiteOrigin.of(a) ?: return false
        if (to.substringBefore("://") != origin.substringBefore("://")) return false
        val h1 = SiteOrigin.host(to).substringBefore(':')
        val h2 = SiteOrigin.host(origin).substringBefore(':')
        return h1 == h2 || h1.endsWith(".$h2") || h2.endsWith(".$h1")
    }

    sealed interface Result {
        data class Fill(val plan: LoginPlan) : Result
        data class Refused(val reason: String) : Result
    }
}
