package dev.locus.core.data.mcp

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.locus.core.agent.mcp.McpAuthException
import dev.locus.core.agent.mcp.McpOAuth
import dev.locus.core.agent.mcp.OAuthSession
import dev.locus.core.datastore.SecretStore
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.BindException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Signs in to an MCP server in the user's browser. The MCP spec requires a localhost or HTTPS
 * redirect, so for the length of one sign-in Locus listens on 127.0.0.1 for the browser's redirect
 * (RFC 8252 loopback), checks it, and keeps the tokens in the encrypted vault under a name the
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
            val server = try {
                ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"))
            } catch (_: BindException) {
                throw McpAuthException("Another sign-in is using port $PORT. Close it and try again.")
            }
            server.use {
                it.soTimeout = 1_000
                context.startActivity(android.content.Intent(Intent.ACTION_VIEW, pending.url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                try {
                    awaitRedirect(it, pending.state) { params ->
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
            bringLocusBack()
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

    /**
     * Waits up to five minutes for the browser's redirect. Anything else that connects (preconnects,
     * favicon requests, or a web page or app poking the port) is answered and ignored: only a
     * /callback carrying this sign-in's state is taken, and "Signed in" is shown only once
     * [onCallback] has exchanged the code.
     */
    private suspend fun awaitRedirect(server: ServerSocket, state: String, onCallback: suspend (Map<String, String>) -> Unit) {
        val deadline = System.currentTimeMillis() + 5 * 60_000
        while (System.currentTimeMillis() < deadline) {
            coroutineContext.ensureActive()
            val socket = try {
                server.accept()
            } catch (_: SocketTimeoutException) {
                continue
            }
            socket.use { s ->
                val target = try {
                    s.soTimeout = 3_000
                    readRequestTarget(s)
                } catch (_: IOException) {
                    null
                } ?: return@use
                val params = if (target.startsWith("/callback")) {
                    val uri = "http://127.0.0.1$target".toUri()
                    uri.queryParameterNames.associateWith { uri.getQueryParameter(it).orEmpty() }
                } else {
                    emptyMap()
                }
                if (params["state"] != state) {
                    respond(s, 404, "Not found.")
                    return@use
                }
                val outcome = runCatching { onCallback(params) }
                respond(s, 200, if (outcome.isSuccess) "Signed in. You can go back to Locus." else "Sign-in failed. Go back to Locus for details.")
                outcome.getOrThrow()
                return
            }
        }
        throw McpAuthException("Sign-in timed out. Try again.")
    }

    /** The request target from "GET /path?query HTTP/1.1", reading at most 8 KB. */
    private fun readRequestTarget(socket: Socket): String? {
        val input = socket.getInputStream()
        val line = StringBuilder()
        while (line.length < 8_192) {
            val c = input.read()
            if (c < 0 || c == '\n'.code) break
            if (c != '\r'.code) line.append(c.toChar())
        }
        return line.split(' ').getOrNull(1)
    }

    private fun respond(socket: Socket, status: Int, message: String) {
        val body = "<!doctype html><meta name=viewport content='width=device-width'><title>Locus</title><p style='font:18px sans-serif;margin:2em'>$message</p>"
        val reason = if (status == 200) "OK" else "Not Found"
        runCatching {
            socket.getOutputStream().write(
                ("HTTP/1.1 $status $reason\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.toByteArray().size}\r\n" +
                    "Connection: close\r\n\r\n$body").toByteArray(),
            )
        }
    }

    private fun bringLocusBack() {
        context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            ?.let { runCatching { context.startActivity(it) } }
    }

    private companion object {
        /** Fixed so the registered redirect URI matches exactly on later sign-ins. */
        const val PORT = 33418
        const val REDIRECT_URI = "http://127.0.0.1:$PORT/callback"
    }
}
