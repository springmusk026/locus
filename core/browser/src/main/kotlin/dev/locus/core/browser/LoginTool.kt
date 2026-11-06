package dev.locus.core.browser

import dev.locus.core.agent.ObjectSchema
import dev.locus.core.agent.PageAction
import dev.locus.core.agent.Tool
import dev.locus.core.agent.ToolCallContext
import dev.locus.core.agent.ToolException
import dev.locus.core.agent.ToolResult
import dev.locus.core.agent.str
import dev.locus.core.model.SiteOrigin
import dev.locus.core.model.ToolPolicy
import dev.locus.core.model.ToolRisk
import dev.locus.core.model.ToolSpec
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.JsonObject

/**
 * What browser_login needs from the browser: the calling chat's page, by the browser's own record. The
 * app's is [AgentBrowser] (through BrowserTools); tests use a fake page.
 */
interface LoginPage {
    /** The active tab's top-frame URL (WebView's record, not the page's word); null without a page. */
    suspend fun url(): String?

    /** Whether this chat opened [origin] before its run read outside content ([AgentBrowser.vouch]). */
    suspend fun vouched(origin: String): Boolean

    /** The page's sign-in fields, never their values. */
    suspend fun loginForm(): LoginForm?

    /** Fills [plan] on document [doc] of [origin]; an error, or null when done. */
    suspend fun fill(origin: String, plan: LoginPlan, doc: String?, username: String?, password: String?, code: String?): String?

    /** The page after the fill, as every browser action returns it, under [note]. */
    suspend fun view(note: String): ToolResult

    /** Whether the user has taken over this chat's browser ([BrowserControl]): then nothing is filled. */
    suspend fun takenOver(): Boolean = false

    /** Runs the fill under the takeover rule ([takeoverGate]), so a hand-back notice comes with its result. */
    suspend fun underControl(run: suspend () -> ToolResult): ToolResult = run()
}

/**
 * Runs a browser tool's [block] and takes every saved login's secrets out of what it returns or throws
 * ([LoginScrubber]): the backstop that keeps a page that echoes a filled password from handing it to the
 * model. The scrubber is built after the call, so a one-time code that came due during it is covered.
 */
suspend fun scrubbingLogins(logins: SiteLogins, clock: () -> Long = System::currentTimeMillis, block: suspend () -> ToolResult): ToolResult {
    suspend fun scrubber() = runCatching { LoginScrubber.of(logins.all(), clock() / 1000) }.getOrElse { LoginScrubber(emptyList()) }
    val result = try {
        block()
    } catch (e: ToolException) {
        throw ToolException(scrubber().scrub(e.message.orEmpty()))
    }
    val s = scrubber()
    return if (s.isEmpty) result else result.copy(content = s.scrub(result.content))
}

/**
 * browser_login: fill the user's saved login for the page that is open. The model only asks; Locus picks
 * the login (by the page's exact origin), the fields, and the values, and checks everything in Kotlin
 * before the page helper checks it again and fills (docs/design/browser.md, "Saved logins"):
 * - the page's origin, by the browser's record of the top frame, equals the saved login's and the call's
 *   `origin` exactly; HTTPS only, the phone's loopback excepted ([LoginRules.pageRefusal]);
 * - once the run has read outside content, the site was named by the user or opened before that
 *   ([LoginRules.reachRefusal]);
 * - the password goes only into the one visible `type=password` field of a sign-in form in the top
 *   document, whose form submits to the same site; the TOTP code only into a one-time-code field
 *   ([LoginRules.plan]).
 * It never fills while the user has taken over the browser ([BrowserControl]): their typing isn't raced.
 * It never submits: the sign-in click goes through [SensitiveActions] like any other. Its policy is Ask,
 * so the first fill per site asks; "Always" is remembered per origin (ApprovalScopes).
 *
 * Its own tool, not a mode of another: the user can set it to Deny in Permissions without turning off
 * typing, its approvals and remembered rules are its own, and the Activity log names it.
 */
class BrowserLogin(
    private val page: LoginPage,
    private val logins: SiteLogins,
    private val clock: () -> Long = System::currentTimeMillis,
) : Tool {
    override val risk = ToolRisk.WRITE
    override val defaultPolicy = ToolPolicy.ASK
    override val runsAlone: Boolean get() = true
    override val pagesOwnOutput: Boolean get() = true

    override val spec = ToolSpec(
        NAME,
        "Fill the user's saved login into the page that is open, when the snapshot says \"Saved login available for <origin>\". " +
            "Locus picks the username, password and (if the login has an authenticator key) one-time-code fields and fills them; you never see the values. " +
            "It only fills: click the sign-in button afterwards (the user is asked first). " +
            "Without a saved login, hand the sign-in to the user with browser_ask_user. CAPTCHAs, passkeys, SMS or app approvals, " +
            "\"Sign in with Google\" consent screens and payments are always the user's: hand those over too.",
        ObjectSchema().string("origin", "The page's origin exactly as the snapshot's saved-login note gives it, e.g. https://github.com.").build(),
    )

    override fun describe(args: JsonObject): String =
        "Fill your saved login for " + (SiteOrigin.of(args.str("origin"))?.let(SiteOrigin::host) ?: args.str("origin") ?: "this page")

    // A sign-in, not an observation: refused in read-only background runs. It types values the model never
    // chose (the vault's, to the vault's site), so it isn't text that taint's typing rule is about.
    override suspend fun pageAction(args: JsonObject): PageAction? {
        if (currentCoroutineContext()[ToolCallContext] == null) return null
        val host = BrowserArgs.host(page.url().orEmpty())
        return PageAction(host, carriesText = false)
    }

    // Everything that can be known before anyone is asked, so nobody approves a fill that can only fail.
    override suspend fun precheck(args: JsonObject): String? = refusal(args).first

    // The scrubber wraps the takeover gate, so a hand-back notice and any error are scrubbed with the rest.
    override suspend fun execute(args: JsonObject): ToolResult = scrubbingLogins(logins, clock) {
        if (page.takenOver()) throw ToolException(TAKEN_OVER)
        page.underControl { fillNow(args) }
    }

    private suspend fun fillNow(args: JsonObject): ToolResult {
        val (why, origin) = refusal(args)
        if (why != null || origin == null) throw ToolException(why ?: "Locus couldn't check this page.")
        val site: String = origin
        // Read again: the user may have edited or deleted it while the card was up.
        val login = logins.forOrigin(site) ?: throw ToolException(noLogin(site))
        val form = page.loginForm()
        val plan = when (val r = LoginRules.plan(form, site, login)) {
            is LoginRules.Result.Refused -> throw ToolException(r.reason)
            is LoginRules.Result.Fill -> r.plan
        }
        val code = plan.codeRef?.let { login.totpSecret?.let { key -> Totp.codeFor(key, clock() / 1000) } ?: throw ToolException("The saved authenticator key for ${SiteOrigin.host(site)} can't be read. Ask the user to fix it in Settings → Saved logins.") }
        page.fill(site, plan, form?.doc, plan.usernameRef?.let { login.username }, plan.passwordRef?.let { login.password }, code)?.let { throw ToolException(it) }
        runCatching { logins.markUsed(login.id) }
        val parts = listOfNotNull(
            plan.usernameRef?.let { "username in $it" },
            plan.passwordRef?.let { "password in $it" },
            plan.codeRef?.let { "one-time code in $it" },
        )
        return page.view(
            "Filled the saved login for ${SiteOrigin.host(site)}: ${parts.joinToString(", ")}. Nothing was submitted: click the sign-in button next " +
                "(the user is asked before it signs in).",
        )
    }

    /** Why this call can't fill, with the page's origin when it can. */
    private suspend fun refusal(args: JsonObject): Pair<String?, String?> {
        // First: reading the page's URL would wait for the user to hand back.
        if (page.takenOver()) return TAKEN_OVER to null
        val url = page.url()
        LoginRules.pageRefusal(url, args.str("origin"))?.let { return it to null }
        val origin = SiteOrigin.of(url)!!
        if (logins.forOrigin(origin) == null) return noLogin(origin) to null
        // Without a call context nothing says what the run read: treat it as having read outside content.
        val taint = currentCoroutineContext()[ToolCallContext]?.taint
        val sources = taint?.sources ?: setOf("unknown")
        LoginRules.reachRefusal(origin, sources, taint?.trustedHosts.orEmpty(), page.vouched(origin))?.let { return it to null }
        return null to origin
    }

    private fun noLogin(origin: String) =
        "There's no saved login for $origin. Hand the sign-in to the user with browser_ask_user; they can save it for next time."

    companion object {
        const val NAME = "browser_login"

        const val TAKEN_OVER = "The user has taken over the browser, so Locus didn't fill the saved login. Don't retry in a loop: " +
            "the next browser result after they hand it back says so; then take a new snapshot and call browser_login again if the sign-in page is still open."
    }
}
