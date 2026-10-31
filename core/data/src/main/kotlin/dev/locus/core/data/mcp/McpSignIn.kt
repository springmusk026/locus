package dev.locus.core.data.mcp

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.locus.core.agent.mcp.McpAuthException
import dev.locus.core.agent.mcp.McpOAuth
import dev.locus.core.agent.mcp.OAuthSession
import dev.locus.core.data.auth.LoopbackRedirect
import dev.locus.core.datastore.SecretStore
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Signs in to an MCP server in the user's browser. The MCP spec requires a localhost or HTTPS
 * redirect, so for the length of one sign-in Locus listens on 127.0.0.1 for the browser's redirect
 * ([LoopbackRedirect]), checks it, and keeps the tokens in the encrypted vault under a name the
 * agent's connectors can't reach ([SecretStore.mcpOAuthSession]).
 */
@Singleton
class McpSignIn @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val secrets: SecretStore,
    http: HttpClient,
) {
    private val oauth = McpOAuth(http)
    private val json = Json { ignoreUnknownKeys = true }
    private val signInLock = Mutex()
    /** Serialises token refreshes and saves, so a refresh can't overwrite a newer session. */
    private val tokenLock = Mutex()

    /** Whether [serverUrl] wants OAuth (it answers an unauthenticated request with 401). */
    suspend fun requiresSignIn(serverUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            oauth.discover(serverUrl) != null
        } catch (e: McpAuthException) {
            throw e
        } catch (e: Exception) {
            throw McpAuthException("Couldn't reach $serverUrl: ${e.message ?: e::class.simpleName}")
        }
    }

    /** Runs the whole browser sign-in and stores the session under [secretName]. */
    suspend fun signIn(serverUrl: String, secretName: String) = signInLock.withLock {
        withContext(Dispatchers.IO) {
            val discovery = oauth.discover(serverUrl) ?: throw McpAuthException("This server doesn't ask for sign-in.")
            // One registration per authorization server, reused for later sign-ins.
            val clientKey = SecretStore.mcpOAuthClient(discovery.server.issuer)
            val clientId = secrets.get(clientKey) ?: oauth.register(discovery.server, REDIRECT_URI).also { secrets.put(clientKey, it) }
            val pending = oauth.authorizationRequest(discovery, clientId, REDIRECT_URI)
            val listener = LoopbackRedirect.open(PORT, CALLBACK_PATH, anyPortIfBusy = false)
                ?: throw McpAuthException("Another sign-in is using port $PORT. Close it and try again.")
            listener.use {
                LoopbackRedirect.openBrowser(context, pending.url)
                try {
                    it.await(pending.state, ::McpAuthException) { params ->
                        val session = oauth.complete(discovery, clientId, pending, params)
                        tokenLock.withLock { save(secretName, session) }
                    }
                } catch (e: McpAuthException) {
                    // A registration the server forgot leaves the browser on an error page and us waiting;
                    // drop it so the next attempt registers again.
                    if (e.message.orEmpty().let { m -> "timed out" in m || "invalid_client" in m || "unauthorized_client" in m }) secrets.put(clientKey, null)
                    throw e
                }
            }
            LoopbackRedirect.bringAppBack(context)
        }
    }

    /** A valid access token for [secretName], refreshed (and saved) when it's about to expire. */
    suspend fun accessToken(secretName: String): String? = tokenLock.withLock {
        val session = load(secretName) ?: return@withLock null
        if (!oauth.needsRefresh(session)) return@withLock session.accessToken
        val fresh = withContext(Dispatchers.IO) { oauth.refresh(session) }
        save(secretName, fresh)
        fresh.accessToken
    }

    /** After the server rejected the token: refresh if possible; returns the new token or null. */
    suspend fun forceRefresh(secretName: String): String? = tokenLock.withLock {
        val session = load(secretName) ?: return@withLock null
        val fresh = runCatching { withContext(Dispatchers.IO) { oauth.refresh(session) } }.getOrNull() ?: return@withLock null
        save(secretName, fresh)
        fresh.accessToken
    }

    private suspend fun load(secretName: String): OAuthSession? =
        secrets.get(SecretStore.mcpOAuthSession(secretName))?.let { runCatching { json.decodeFromString(OAuthSession.serializer(), it) }.getOrNull() }

    private suspend fun save(secretName: String, session: OAuthSession) =
        secrets.put(SecretStore.mcpOAuthSession(secretName), json.encodeToString(OAuthSession.serializer(), session))

    private companion object {
        /** Fixed so the registered redirect URI matches exactly on later sign-ins. */
        const val PORT = 33418
        const val CALLBACK_PATH = "/callback"
        const val REDIRECT_URI = "http://127.0.0.1:$PORT$CALLBACK_PATH"
    }
}
