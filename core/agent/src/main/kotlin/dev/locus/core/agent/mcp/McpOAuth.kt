package dev.locus.core.agent.mcp

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.parameters
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

class McpAuthException(message: String) : Exception(message)

/** What a signed-in MCP connector keeps in the vault. Tokens never reach the model. */
@Serializable
data class OAuthSession(
    val issuer: String,
    val clientId: String,
    val tokenEndpoint: String,
    val resource: String,
    val scope: String? = null,
    val accessToken: String,
    val refreshToken: String? = null,
    /** Epoch millis, or null when the server didn't say. */
    val expiresAt: Long? = null,
)

/**
 * OAuth for remote MCP servers, following the MCP authorization spec (2026-07-28): protected
 * resource metadata (RFC 9728) → authorization server metadata (RFC 8414 / OIDC discovery, issuer
 * checked) → dynamic client registration as a native public client → authorization code with PKCE
 * S256, `resource` (RFC 8707), state and `iss` (RFC 9207) checks → token refresh.
 *
 * The browser part is left to the caller: [authorizationRequest] gives the URL to open, and
 * [complete] takes the redirect's query parameters.
 */
class McpOAuth(private val http: HttpClient, private val now: () -> Long = System::currentTimeMillis) {

    data class AuthServer(
        val issuer: String,
        val authorizationEndpoint: String,
        val tokenEndpoint: String,
        val registrationEndpoint: String?,
        val issParameterSupported: Boolean,
        val scopesSupported: List<String>,
    )

    data class Discovery(val resource: String, val scope: String?, val server: AuthServer)

    data class PendingAuthorization(val url: String, val state: String, val verifier: String, val redirectUri: String)

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Finds how to sign in to [serverUrl], or returns null when it doesn't need OAuth (it answered
     * an unauthenticated request without a 401).
     */
    suspend fun discover(serverUrl: String): Discovery? {
        val resource = canonicalResource(serverUrl)
        // An unauthenticated initialize: a server that needs auth answers 401 with a challenge.
        val probe = http.post(serverUrl) {
            contentType(ContentType.Application.Json)
            header("Accept", "application/json, text/event-stream")
            setBody("""{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"locus","version":"0.1.0"}}}""")
        }
        if (probe.status.value != 401) return null
        val challenge = parseChallenge(probe.headers["WWW-Authenticate"].orEmpty())
        val prm = (listOfNotNull(challenge["resource_metadata"]?.takeIf(::isSecure)) + protectedResourceMetadataUrls(serverUrl))
            .firstNotNullOfOrNull { url -> fetchJson(url) }
            ?: throw McpAuthException("This server asks for sign-in but doesn't publish how (no protected resource metadata).")
        // RFC 9728 §3.3: the metadata must describe this very server.
        val described = (prm["resource"] as? JsonPrimitive)?.contentOrNull
        if (described != null && runCatching { canonicalResource(described) }.getOrNull() != resource) {
            throw McpAuthException("The server's sign-in metadata is for a different address ($described).")
        }
        val issuer = (prm["authorization_servers"] as? JsonArray)?.firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: throw McpAuthException("The server's metadata names no authorization server.")
        if (!isSecure(issuer)) throw McpAuthException("The authorization server isn't on HTTPS, so signing in isn't safe.")
        val server = authServer(issuer)
        val scope = challenge["scope"] ?: (prm["scopes_supported"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.joinToString(" ")?.ifBlank { null }
        return Discovery(resource, scope, server)
    }

    private suspend fun authServer(issuer: String): AuthServer {
        val meta = authServerMetadataUrls(issuer).firstNotNullOfOrNull { url ->
            // The document must be for exactly this issuer, or it can't be trusted.
            fetchJson(url)?.takeIf { (it["issuer"] as? JsonPrimitive)?.contentOrNull == issuer }
        } ?: throw McpAuthException("Couldn't read the authorization server's metadata at $issuer.")
        fun s(k: String) = (meta[k] as? JsonPrimitive)?.contentOrNull
        val methods = (meta["code_challenge_methods_supported"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        if (methods == null || "S256" !in methods) throw McpAuthException("The authorization server doesn't support PKCE S256, so signing in isn't safe.")
        // Codes, verifiers and tokens travel to these, and the first one is opened in the browser.
        fun secure(k: String) = s(k)?.also { if (!isSecure(it)) throw McpAuthException("The authorization server's $k isn't HTTPS, so signing in isn't safe.") }
        return AuthServer(
            issuer = issuer,
            authorizationEndpoint = secure("authorization_endpoint") ?: throw McpAuthException("No authorization endpoint."),
            tokenEndpoint = secure("token_endpoint") ?: throw McpAuthException("No token endpoint."),
            registrationEndpoint = secure("registration_endpoint"),
            issParameterSupported = s("authorization_response_iss_parameter_supported") == "true",
            scopesSupported = (meta["scopes_supported"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
        )
    }

    /** Registers Locus as a native public client (RFC 7591) and returns its client id. */
    suspend fun register(server: AuthServer, redirectUri: String): String {
        val endpoint = server.registrationEndpoint
            ?: throw McpAuthException("This authorization server needs a pre-registered client, which Locus doesn't have yet.")
        val body = buildJsonObject {
            put("client_name", "Locus")
            putJsonArray("redirect_uris") { add(redirectUri) }
            putJsonArray("grant_types") { add("authorization_code"); add("refresh_token") }
            putJsonArray("response_types") { add("code") }
            put("token_endpoint_auth_method", "none")
            put("application_type", "native")
        }
        val response = http.post(endpoint) {
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        val obj = parse(response, "Client registration")
        return (obj["client_id"] as? JsonPrimitive)?.contentOrNull ?: throw McpAuthException("Registration returned no client id.")
    }

    fun authorizationRequest(discovery: Discovery, clientId: String, redirectUri: String): PendingAuthorization {
        val verifier = randomToken(48)
        val state = randomToken(24)
        val scope = withOfflineAccess(discovery.scope, discovery.server.scopesSupported)
        val url = URLBuilder(discovery.server.authorizationEndpoint).apply {
            parameters.append("response_type", "code")
            parameters.append("client_id", clientId)
            parameters.append("redirect_uri", redirectUri)
            parameters.append("code_challenge", challengeFor(verifier))
            parameters.append("code_challenge_method", "S256")
            parameters.append("state", state)
            parameters.append("resource", discovery.resource)
            scope?.let { parameters.append("scope", it) }
        }.buildString()
        return PendingAuthorization(url, state, verifier, redirectUri)
    }

    /** Checks the redirect ([params] is its query) and exchanges the code for tokens. */
    suspend fun complete(discovery: Discovery, clientId: String, pending: PendingAuthorization, params: Map<String, String>): OAuthSession {
        if (params["state"] != pending.state) throw McpAuthException("Sign-in response didn't match this request; ignored it.")
        // RFC 9207: the response must come from the server we sent the user to.
        val iss = params["iss"]
        if (iss != null && iss != discovery.server.issuer) throw McpAuthException("Sign-in response came from an unexpected server; ignored it.")
        if (iss == null && discovery.server.issParameterSupported) throw McpAuthException("Sign-in response is missing its issuer; ignored it.")
        params["error"]?.let { throw McpAuthException("Sign-in was not completed: ${params["error_description"] ?: it}.") }
        val code = params["code"] ?: throw McpAuthException("Sign-in returned no authorization code.")
        val response = http.submitForm(
            discovery.server.tokenEndpoint,
            parameters {
                append("grant_type", "authorization_code")
                append("code", code)
                append("redirect_uri", pending.redirectUri)
                append("client_id", clientId)
                append("code_verifier", pending.verifier)
                append("resource", discovery.resource)
            },
        )
        return session(parse(response, "Token request"), discovery.server.issuer, clientId, discovery.server.tokenEndpoint, discovery.resource, discovery.scope, previousRefresh = null)
    }

    fun needsRefresh(session: OAuthSession) = session.expiresAt != null && session.expiresAt - now() < 60_000

    suspend fun refresh(session: OAuthSession): OAuthSession {
        val refreshToken = session.refreshToken ?: throw McpAuthException("Sign-in expired. Sign in again in Library → Connectors.")
        val response = http.submitForm(
            session.tokenEndpoint,
            parameters {
                append("grant_type", "refresh_token")
                append("refresh_token", refreshToken)
                append("client_id", session.clientId)
                append("resource", session.resource)
            },
        )
        if (response.status.value in 400..499) throw McpAuthException("Sign-in expired. Sign in again in Library → Connectors.")
        return session(parse(response, "Token refresh"), session.issuer, session.clientId, session.tokenEndpoint, session.resource, session.scope, previousRefresh = refreshToken)
    }

    private fun session(obj: JsonObject, issuer: String, clientId: String, tokenEndpoint: String, resource: String, scope: String?, previousRefresh: String?): OAuthSession {
        fun s(k: String) = (obj[k] as? JsonPrimitive)?.contentOrNull
        val access = s("access_token") ?: throw McpAuthException("The server returned no access token.")
        val expiresIn = (obj["expires_in"] as? JsonPrimitive)?.longOrNull
        return OAuthSession(
            issuer = issuer,
            clientId = clientId,
            tokenEndpoint = tokenEndpoint,
            resource = resource,
            scope = s("scope") ?: scope,
            accessToken = access,
            // Public clients get rotated refresh tokens; keep the old one only if no new one came.
            refreshToken = s("refresh_token") ?: previousRefresh,
            expiresAt = expiresIn?.let { now() + it * 1000 },
        )
    }

    private suspend fun fetchJson(url: String): JsonObject? = runCatching {
        val response = http.get(url) { header("Accept", "application/json") }
        if (response.status.value != 200) null else json.parseToJsonElement(response.bodyAsText()) as? JsonObject
    }.getOrNull()

    private suspend fun parse(response: HttpResponse, what: String): JsonObject {
        val text = response.bodyAsText()
        val obj = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        if (response.status.value in 200..299 && obj != null) return obj
        val reason = (obj?.get("error_description") as? JsonPrimitive)?.contentOrNull ?: (obj?.get("error") as? JsonPrimitive)?.contentOrNull ?: text.take(200)
        throw McpAuthException("$what failed (${response.status.value}): $reason")
    }

    companion object {
        private val random = SecureRandom()

        fun randomToken(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

        /** PKCE S256: BASE64URL(SHA256(verifier)). */
        fun challengeFor(verifier: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

        /** The server's canonical URI for `resource`: lowercase scheme and host, no fragment or query, no trailing slash. */
        fun canonicalResource(serverUrl: String): String {
            val u = Url(serverUrl)
            val port = if (u.port == u.protocol.defaultPort) "" else ":${u.port}"
            return "${u.protocol.name.lowercase()}://${u.host.lowercase()}$port${u.encodedPath.trimEnd('/')}"
        }

        /** RFC 9728 well-known locations: path-specific first, then the root. */
        fun protectedResourceMetadataUrls(serverUrl: String): List<String> {
            val u = Url(serverUrl)
            val origin = "${u.protocol.name}://${u.hostWithPortIfSpecified()}"
            val path = u.encodedPath.trimEnd('/')
            return listOfNotNull(
                path.takeIf { it.isNotEmpty() }?.let { "$origin/.well-known/oauth-protected-resource$it" },
                "$origin/.well-known/oauth-protected-resource",
            )
        }

        /** RFC 8414 and OpenID discovery locations, in the order the MCP spec gives. */
        fun authServerMetadataUrls(issuer: String): List<String> {
            val u = Url(issuer)
            val origin = "${u.protocol.name}://${u.hostWithPortIfSpecified()}"
            val path = u.encodedPath.trimEnd('/')
            return if (path.isEmpty()) {
                listOf("$origin/.well-known/oauth-authorization-server", "$origin/.well-known/openid-configuration")
            } else {
                listOf(
                    "$origin/.well-known/oauth-authorization-server$path",
                    "$origin/.well-known/openid-configuration$path",
                    "$origin$path/.well-known/openid-configuration",
                )
            }
        }

        /** Parses `Bearer resource_metadata="…", scope="…"` into its parameters. */
        fun parseChallenge(header: String): Map<String, String> =
            Regex("""([A-Za-z_]+)\s*=\s*(?:"((?:[^"\\]|\\.)*)"|([^\s,]+))""").findAll(header)
                .associate { m -> m.groupValues[1] to (m.groupValues[2].ifEmpty { m.groupValues[3] }).replace("\\\"", "\"") }

        /**
         * Asks for offline_access too when the server offers it, so the sign-in can be refreshed.
         * Not when no scope is known: a lone offline_access would replace the server's defaults.
         */
        fun withOfflineAccess(scope: String?, supported: List<String>): String? {
            if (scope.isNullOrBlank() || "offline_access" !in supported) return scope
            val parts = scope?.split(' ')?.filter { it.isNotBlank() }.orEmpty()
            return (parts + "offline_access").distinct().joinToString(" ")
        }

        /** HTTPS, or plain http only to this device (local development servers). */
        fun isSecure(url: String): Boolean {
            val u = runCatching { Url(url) }.getOrNull() ?: return false
            return u.protocol.name == "https" || (u.protocol.name == "http" && u.host in setOf("127.0.0.1", "localhost", "[::1]", "::1"))
        }

        private fun Url.hostWithPortIfSpecified() = if (port == protocol.defaultPort) host else "$host:$port"
    }
}
