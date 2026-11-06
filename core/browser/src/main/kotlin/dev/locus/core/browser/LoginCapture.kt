package dev.locus.core.browser

import dev.locus.core.model.SiteLogin
import dev.locus.core.model.SiteOrigin
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * "Save this login for <origin>?", offered after the user signed in themselves. [password] is what they
 * typed, held in memory only until they answer (or [LoginCapture.TTL_MS] passes); it is never put in a
 * tool result, an event, the Activity log or a log line, and [toString] leaves it out.
 */
class LoginOffer(
    val origin: String,
    val username: String,
    /** A login for [origin] is already saved: this replaces it. */
    val isUpdate: Boolean,
    internal val password: String,
    val at: Long,
) {
    override fun toString(): String = "LoginOffer(origin=$origin, isUpdate=$isUpdate)"
}

/**
 * Reads the page helper's report of a sign-in the user typed (docs/design/browser.md, "Saved logins",
 * "Reading what the user typed"). The helper sends it through the page's own `prompt()`, which WebView
 * hands to Kotlin synchronously (`WebChromeClient.onJsPrompt`), so it arrives before the page navigates
 * away. Pure, so each check is unit-tested (`LoginCaptureTest`).
 */
object LoginCapture {
    /** The start of the prompt message the helper sends; the tab's secret follows it. */
    const val MARK = "__locus_login__"

    /** How long an unanswered offer, and the password in it, is kept. */
    const val TTL_MS = 5 * 60_000L

    class Typed(val origin: String, val username: String, val password: String) {
        override fun toString(): String = "Typed(origin=$origin)"
    }

    /** Whether a prompt [message] is the helper's report rather than the page's own dialog. */
    fun isReport(message: String?): Boolean = message?.startsWith(MARK) == true

    /**
     * The sign-in in a prompt [message] with [payload] (its default text) from the frame at [frameUrl], in
     * the tab showing [pageUrl] whose helper secret is [tabSecret]; null unless every check passes: the
     * message carries the tab's secret (only the helper copy that ran before the page's scripts has it);
     * the frame is the top page's own origin; that origin is HTTPS (or the phone's loopback); and the
     * payload is a username and a non-empty password of sane length.
     */
    fun read(message: String?, payload: String?, frameUrl: String?, pageUrl: String?, tabSecret: String): Typed? {
        if (message == null || !message.startsWith(MARK) || tabSecret.isEmpty()) return null
        val given = message.removePrefix(MARK)
        if (!java.security.MessageDigest.isEqual(given.toByteArray(), tabSecret.toByteArray())) return null
        val origin = SiteOrigin.of(pageUrl) ?: return null
        if (SiteOrigin.of(frameUrl) != origin || !SiteOrigin.secureEnough(origin)) return null
        val o = runCatching { Json.parseToJsonElement(payload ?: return null) as? JsonObject }.getOrNull() ?: return null
        val user = (o["u"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val pass = (o["p"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        if (pass.isEmpty() || pass.length > 512 || user.length > 256) return null
        return Typed(origin, user.trim(), pass)
    }

    /**
     * The offer to show for [typed], or null when there's nothing to ask: the user said never for this
     * site, or exactly this login is already saved. An existing login with another password is offered
     * as an update.
     */
    fun offer(typed: Typed, existing: SiteLogin?, never: Boolean, now: Long): LoginOffer? {
        if (never) return null
        if (existing != null && existing.password == typed.password && (typed.username.isEmpty() || typed.username == existing.username)) return null
        val username = typed.username.ifEmpty { existing?.username.orEmpty() }
        return LoginOffer(typed.origin, username, existing != null, typed.password, now)
    }
}
