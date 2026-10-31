package dev.locus.core.agent.auth

import dev.locus.core.agent.mcp.McpOAuth
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.URLBuilder
import io.ktor.http.parameters
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.math.BigInteger
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/** [signInAgain]: the saved tokens are unusable and were (or should be) cleared. */
class ChatGptAuthException(
    message: String,
    val signInAgain: Boolean = false,
    /** The issued client itself was refused (`invalid_client`); the next sign-in registers a new one. */
    val clientInvalid: Boolean = false,
) : Exception(message)

/**
 * What stays on the phone after signing out, so the next sign-in reuses the same registration:
 * the issued client id and the verified identity it belongs to.
 */
@Serializable
data class ChatGptAccount(
    val clientId: String,
    val subject: String,
    val email: String? = null,
    /** The user declined plan use last time; ask for consent explicitly on the next sign-in. */
    val planDeclined: Boolean = false,
)

/** A signed-in ChatGPT plan session. Kept in the vault only; tokens never reach the model. */
@Serializable
data class ChatGptSession(
    val clientId: String,
    val subject: String,
    val email: String? = null,
    /** Kept for `id_token_hint` on the next sign-in; cleared at sign-out. */
    val idToken: String? = null,
    val accessToken: String,
    val refreshToken: String? = null,
    val scopes: List<String> = emptyList(),
    /** Epoch millis. */
    val expiresAt: Long,
    val savedAt: Long,
) {
    val canUsePlan: Boolean get() = ChatGptOAuth.SCOPE_PLAN in scopes
}

/**
 * Sign in with ChatGPT for open-source apps: the user's ChatGPT plan pays for Responses API
 * requests, with no API key or client secret. Follows developers.openai.com/siwc/token-sharing-open-source
 * (sign-in, profiles-and-sessions, token-reference, errors-and-recovery):
 *
 * - First sign-in registers a client with `client_id=dynamic_agent_client`, `agent_name_hint` and
 *   this install's `ext_agent_host_id`; the redirect brings back the issued `oaiapp_…` id, which every
 *   later request (token exchange, refresh, revocation, re-sign-in) uses.
 * - Authorization code with PKCE S256, `state` and OIDC `nonce`, `resource=https://api.openai.com/v1`,
 *   and a `http://127.0.0.1:<port>/auth/callback` redirect.
 * - The ID token is verified (RS256 against OpenAI's JWKS, issuer, audience, expiry, nonce), and plan
 *   use needs `chatgpt.tokens.use.direct` among the granted scopes.
 * - Access tokens last an hour; refresh tokens rotate, so refreshes go through [ChatGptTokens].
 *
 * The browser and loopback listener are the caller's: [authorizationRequest] gives the URL, and
 * [complete] takes the redirect's query.
 */
class ChatGptOAuth(private val http: HttpClient, private val now: () -> Long = System::currentTimeMillis) {

    data class PendingAuthorization(
        val url: String,
        val state: String,
        val nonce: String,
        val verifier: String,
        val redirectUri: String,
        /** The saved issued client id on a re-sign-in; null while registering for the first time. */
        val clientId: String?,
        /** The account this re-sign-in must come back as. */
        val expectedSubject: String?,
    )

    /** What OpenAI's OIDC discovery document says; loaded per sign-in and sign-out. */
    data class Discovery(val jwksUri: String, val revocationEndpoint: String?)

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun discover(): Discovery {
        val obj = runCatching {
            val response = http.get(DISCOVERY_URL) { header("Accept", "application/json") }
            if (response.status.value != 200) null else json.parseToJsonElement(response.bodyAsText()) as? JsonObject
        }.getOrNull() ?: throw ChatGptAuthException("Couldn't reach OpenAI's sign-in service. Check the connection and try again.")
        if (obj.s("issuer") != ISSUER) throw ChatGptAuthException("OpenAI's sign-in metadata names an unexpected issuer; stopped.")
        val jwks = obj.s("jwks_uri")?.takeIf { it.startsWith("https://") } ?: throw ChatGptAuthException("OpenAI's sign-in metadata has no key set.")
        return Discovery(jwks, obj.s("revocation_endpoint")?.takeIf { it.startsWith("https://") })
    }

    /**
     * The authorization URL for one attempt. [account] null registers a new client; otherwise its
     * issued client id is reused with login hints, and [idTokenHint] (when retained) skips the
     * account chooser.
     */
    fun authorizationRequest(hostId: String, redirectUri: String, account: ChatGptAccount?, idTokenHint: String? = null): PendingAuthorization {
        require(redirectUri.startsWith("http://127.0.0.1:") && redirectUri.endsWith(CALLBACK_PATH)) { "The redirect must be a 127.0.0.1 loopback ending in $CALLBACK_PATH" }
        val verifier = McpOAuth.randomToken(48)
        val state = McpOAuth.randomToken(24)
        val nonce = McpOAuth.randomToken(24)
        val url = URLBuilder(AUTHORIZE_URL).apply {
            if (account == null) {
                parameters.append("client_id", DYNAMIC_CLIENT)
                parameters.append("agent_name_hint", AGENT_NAME)
            } else {
                parameters.append("client_id", account.clientId)
                idTokenHint?.let { parameters.append("id_token_hint", it) }
                account.email?.let { parameters.append("login_hint", it) }
                // After a declined plan permission, ask again rather than reuse the earlier answer.
                if (account.planDeclined) parameters.append("prompt", "consent")
            }
            parameters.append("ext_agent_host_id", hostId)
            parameters.append("response_type", "code")
            parameters.append("redirect_uri", redirectUri)
            parameters.append("scope", SCOPE)
            parameters.append("resource", RESOURCE)
            parameters.append("state", state)
            parameters.append("nonce", nonce)
            parameters.append("code_challenge", McpOAuth.challengeFor(verifier))
            parameters.append("code_challenge_method", "S256")
        }.buildString()
        return PendingAuthorization(url, state, nonce, verifier, redirectUri, account?.clientId, account?.subject)
    }

    /**
     * Checks the redirect's query against [pending] and returns the client id to exchange the code
     * with: the issued one from a new registration, or the saved one (a different one is refused).
     */
    fun callbackClientId(pending: PendingAuthorization, params: Map<String, String>): String {
        if (params["state"] != pending.state) throw ChatGptAuthException("The sign-in response didn't match this request; ignored it.")
        params["error"]?.let { error ->
            throw ChatGptAuthException(
                if (error == "access_denied") "Sign-in was cancelled in the browser." else "Sign-in was not completed: ${params["error_description"] ?: error}.",
            )
        }
        val returned = params["client_id"]?.takeIf { it.isNotBlank() }
        return if (pending.clientId == null) {
            if (returned == null || returned == DYNAMIC_CLIENT) throw ChatGptAuthException("OpenAI didn't finish registering Locus. Try again.")
            returned
        } else {
            if (returned != null && returned != pending.clientId) throw ChatGptAuthException("The sign-in came back for a different registration; ignored it.")
            pending.clientId
        }
    }

    /** Checks the redirect, exchanges its code, and verifies the ID token. */
    suspend fun complete(pending: PendingAuthorization, params: Map<String, String>, discovery: Discovery): ChatGptSession {
        val clientId = callbackClientId(pending, params)
        val code = params["code"]?.takeIf { it.isNotBlank() } ?: throw ChatGptAuthException("Sign-in returned no authorization code.")
        val response = http.submitForm(
            TOKEN_URL,
            parameters {
                append("grant_type", "authorization_code")
                append("client_id", clientId)
                append("code", code)
                append("code_verifier", pending.verifier)
                append("redirect_uri", pending.redirectUri)
                append("resource", RESOURCE)
            },
        )
        val tokens = parse(response, "Signing in")
        val idToken = tokens.s("id_token") ?: throw ChatGptAuthException("OpenAI returned no ID token.")
        val claims = verifyIdToken(idToken, clientId, pending.nonce, jwks(discovery.jwksUri))
        val subject = claims.s("sub")?.takeIf { it.isNotBlank() } ?: throw ChatGptAuthException("The ID token names no account.")
        if (pending.expectedSubject != null && subject != pending.expectedSubject) {
            throw ChatGptAuthException("That's a different ChatGPT account from the one this provider uses. Add another ChatGPT provider for it.")
        }
        return session(tokens, clientId, subject, claims.s("email"), idToken, previous = null)
    }

    /** Refresh shortly before the hour-long access token runs out. */
    fun needsRefresh(session: ChatGptSession) = session.expiresAt - now() < REFRESH_MARGIN_MS

    /** One refresh; the caller saves the result before anything else uses the old refresh token. */
    suspend fun refresh(session: ChatGptSession): ChatGptSession {
        val refreshToken = session.refreshToken ?: throw ChatGptAuthException("The ChatGPT sign-in expired. Continue with ChatGPT again in Settings → Models.", signInAgain = true)
        val response = http.submitForm(
            TOKEN_URL,
            parameters {
                append("grant_type", "refresh_token")
                append("client_id", session.clientId)
                append("refresh_token", refreshToken)
                append("resource", RESOURCE)
            },
        )
        if (response.status.value in 400..499) {
            val error = runCatching { (json.parseToJsonElement(response.bodyAsText()) as? JsonObject)?.let { it.s("error") ?: (it["error"] as? JsonObject)?.s("code") } }.getOrNull()
            if (error == "invalid_client") throw ChatGptAuthException("OpenAI no longer knows this Locus registration. Continue with ChatGPT again in Settings → Models.", signInAgain = true, clientInvalid = true)
            if (error in TERMINAL_REFRESH_ERRORS) {
                throw ChatGptAuthException("The ChatGPT sign-in expired or was disconnected. Continue with ChatGPT again in Settings → Models.", signInAgain = true)
            }
        }
        return session(parse(response, "Refreshing the ChatGPT sign-in"), session.clientId, session.subject, session.email, session.idToken, previous = session)
    }

    /**
     * Ends the renewable session at the revocation endpoint. True when OpenAI confirmed (an empty
     * 200, also for an already-invalid token); false after a network failure or 5xx retried once.
     */
    suspend fun revoke(session: ChatGptSession, discovery: Discovery?): Boolean {
        val endpoint = discovery?.revocationEndpoint ?: return false
        val token = session.refreshToken ?: return true
        repeat(2) { attempt ->
            val status = runCatching {
                http.submitForm(
                    endpoint,
                    parameters {
                        append("token", token)
                        append("token_type_hint", "refresh_token")
                        append("client_id", session.clientId)
                    },
                ).status.value
            }.getOrNull()
            if (status == 200) return true
            if (status != null && status < 500) return false
            if (attempt == 0) kotlinx.coroutines.delay(1_000)
        }
        return false
    }

    private fun session(tokens: JsonObject, clientId: String, subject: String, email: String?, idToken: String?, previous: ChatGptSession?): ChatGptSession {
        val access = tokens.s("access_token") ?: throw ChatGptAuthException("OpenAI returned no access token.")
        val expiresIn = (tokens["expires_in"] as? JsonPrimitive)?.longOrNull ?: 3_600
        val savedAt = now()
        return ChatGptSession(
            clientId = clientId,
            subject = subject,
            email = email,
            idToken = tokens.s("id_token") ?: idToken,
            accessToken = access,
            // Refresh tokens rotate: a new one replaces the old; keep the old only if none came.
            refreshToken = tokens.s("refresh_token") ?: previous?.refreshToken,
            // Omitting `scope` on refresh keeps the grant, so a response without it keeps the old scopes.
            scopes = tokens.s("scope")?.split(' ')?.filter { it.isNotBlank() }?.sorted() ?: previous?.scopes.orEmpty(),
            expiresAt = savedAt + expiresIn * 1_000,
            savedAt = savedAt,
        )
    }

    private suspend fun jwks(uri: String): JsonArray = runCatching {
        val response = http.get(uri) { header("Accept", "application/json") }
        (json.parseToJsonElement(response.bodyAsText()) as? JsonObject)?.get("keys") as? JsonArray
    }.getOrNull() ?: throw ChatGptAuthException("Couldn't load OpenAI's signing keys to check the sign-in.")

    /** Verifies [idToken] and returns its claims. Allows two minutes of clock skew. */
    fun verifyIdToken(idToken: String, clientId: String, nonce: String, keys: JsonArray): JsonObject {
        val parts = idToken.split('.')
        if (parts.size != 3) throw ChatGptAuthException("The ID token is malformed.")
        val header = decodeJson(parts[0])
        val claims = decodeJson(parts[1])
        if (header.s("alg") != "RS256") throw ChatGptAuthException("The ID token uses an unexpected signature algorithm.")
        val kid = header.s("kid")
        val jwk = keys.mapNotNull { it as? JsonObject }.firstOrNull { it.s("kty") == "RSA" && (kid == null || it.s("kid") == kid) }
            ?: throw ChatGptAuthException("The ID token was signed with an unknown key.")
        val key = KeyFactory.getInstance("RSA").generatePublic(
            RSAPublicKeySpec(BigInteger(1, b64(jwk.s("n").orEmpty())), BigInteger(1, b64(jwk.s("e").orEmpty()))),
        )
        val valid = runCatching {
            Signature.getInstance("SHA256withRSA").run {
                initVerify(key)
                update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
                verify(b64(parts[2]))
            }
        }.getOrDefault(false)
        if (!valid) throw ChatGptAuthException("The ID token's signature didn't verify; stopped.")
        if (claims.s("iss") != ISSUER) throw ChatGptAuthException("The ID token came from an unexpected issuer; stopped.")
        val audiences = when (val aud = claims["aud"]) {
            is JsonArray -> aud.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> listOfNotNull(aud.contentOrNull)
            else -> emptyList()
        }
        if (clientId !in audiences) throw ChatGptAuthException("The ID token was issued for a different app; stopped.")
        val exp = (claims["exp"] as? JsonPrimitive)?.longOrNull ?: throw ChatGptAuthException("The ID token has no expiry.")
        if (exp * 1_000 + CLOCK_SKEW_MS < now()) throw ChatGptAuthException("The ID token has expired. Check the phone's clock and try again.")
        if (claims.s("nonce") != nonce) throw ChatGptAuthException("The ID token didn't match this sign-in; stopped.")
        return claims
    }

    private fun decodeJson(part: String): JsonObject =
        runCatching { json.parseToJsonElement(String(b64(part), Charsets.UTF_8)) as? JsonObject }.getOrNull()
            ?: throw ChatGptAuthException("The ID token is malformed.")

    private suspend fun parse(response: HttpResponse, what: String): JsonObject {
        val text = response.bodyAsText()
        val obj = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        if (response.status.value in 200..299 && obj != null) return obj
        val error = obj?.s("error") ?: (obj?.get("error") as? JsonObject)?.s("code")
        if (error == "invalid_grant") throw ChatGptAuthException("$what failed: the code was already used or expired. Try again.")
        val reason = obj?.s("error_description") ?: (obj?.get("error") as? JsonObject)?.s("message") ?: error ?: text.take(200)
        throw ChatGptAuthException("$what failed (${response.status.value}): $reason")
    }

    private fun JsonObject.s(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    companion object {
        const val ISSUER = "https://auth.openai.com"
        const val DISCOVERY_URL = "$ISSUER/.well-known/openid-configuration"
        const val AUTHORIZE_URL = "$ISSUER/api/accounts/authorize"
        const val TOKEN_URL = "$ISSUER/api/accounts/oauth/token"
        const val RESOURCE = "https://api.openai.com/v1"
        const val DYNAMIC_CLIENT = "dynamic_agent_client"
        const val AGENT_NAME = "Locus"
        const val CALLBACK_PATH = "/auth/callback"
        const val SCOPE_PLAN = "chatgpt.tokens.use.direct"
        const val SCOPE = "openid profile email offline_access resource.invoke $SCOPE_PLAN"
        const val REFRESH_MARGIN_MS = 5 * 60_000L
        private const val CLOCK_SKEW_MS = 2 * 60_000L

        /** Refresh errors after which the refresh token can't be used again (errors-and-recovery). */
        val TERMINAL_REFRESH_ERRORS = setOf(
            "invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired", "refresh_token_invalidated", "refresh_token_reused",
        )

        /** A new host id for this install: a UUIDv4 URN, one of the documented formats. */
        fun newHostId(): String = "urn:uuid:${java.util.UUID.randomUUID()}"

        private fun b64(s: String): ByteArray = Base64.getUrlDecoder().decode(s.trimEnd('='))
    }
}

/**
 * Hands out access tokens for ChatGPT sessions, refreshing near expiry. Refresh tokens rotate, so
 * refreshes are serialised: a second caller waits and then finds the already-refreshed session
 * instead of spending the old refresh token again (which would revoke the session as reused).
 */
class ChatGptTokens(
    private val oauth: ChatGptOAuth,
    private val load: suspend (String) -> ChatGptSession?,
    private val save: suspend (String, ChatGptSession?) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()

    /** Runs [block] while no refresh can start, for sign-in and sign-out to replace or clear sessions. */
    suspend fun <T> exclusive(block: suspend () -> T): T = lock.withLock { block() }

    suspend fun accessToken(id: String): String = lock.withLock {
        val session = load(id) ?: throw ChatGptAuthException("Not signed in to ChatGPT. Continue with ChatGPT in Settings → Models.", signInAgain = true)
        if (!session.canUsePlan) throw ChatGptAuthException("This sign-in doesn't allow Locus to use your ChatGPT plan. Continue with ChatGPT again and allow it.", signInAgain = true)
        if (!oauth.needsRefresh(session)) return@withLock session.accessToken
        val fresh = try {
            oauth.refresh(session)
        } catch (e: ChatGptAuthException) {
            if (e.signInAgain) save(id, null)
            throw e
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // A network hiccup: keep the session, and use the current token while it still works.
            if (session.expiresAt > now()) return@withLock session.accessToken
            throw ChatGptAuthException("Couldn't refresh the ChatGPT sign-in: ${e.message ?: e::class.simpleName}")
        }
        save(id, fresh)
        fresh.accessToken
    }
}
