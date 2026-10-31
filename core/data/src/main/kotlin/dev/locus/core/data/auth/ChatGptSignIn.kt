package dev.locus.core.data.auth

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.locus.core.agent.auth.ChatGptAccount
import dev.locus.core.agent.auth.ChatGptAuthException
import dev.locus.core.agent.auth.ChatGptOAuth
import dev.locus.core.agent.auth.ChatGptSession
import dev.locus.core.agent.auth.ChatGptTokens
import dev.locus.core.datastore.SecretStore
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Continue with ChatGPT" for ChatGPT providers: the browser sign-in on a 127.0.0.1 loopback
 * ([LoopbackRedirect]), the sessions in the encrypted vault under names the agent's connectors can't
 * reach ([SecretStore.chatGptSession]), and access tokens for requests through [ChatGptTokens], which
 * serialises refreshes because refresh tokens rotate.
 *
 * Each provider is one ChatGPT account. Signing out revokes the session and clears its tokens but
 * keeps the account's issued client id, so signing in again reuses the same registration.
 */
@Singleton
class ChatGptSignIn @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val secrets: SecretStore,
    http: HttpClient,
) {
    private val oauth = ChatGptOAuth(http)
    private val json = Json { ignoreUnknownKeys = true }
    private val signInLock = Mutex()
    private val tokens = ChatGptTokens(oauth, ::load, ::save)

    /** Provider ids with a ChatGPT session saved (signed in). */
    val signedIn: Flow<Set<String>> = secrets.names
        .map { names -> names.filter { it.startsWith(SecretStore.CHATGPT_SESSION_PREFIX) }.map { it.removePrefix(SecretStore.CHATGPT_SESSION_PREFIX) }.toSet() }
        .distinctUntilChanged()

    /** The signed-in account's email, for showing which account a provider uses. */
    suspend fun email(providerId: String): String? = load(providerId)?.email ?: account(providerId)?.email

    /**
     * Runs the browser sign-in for [providerId] and saves the session. True when this was the first
     * sign-in for the provider (a new registration). Throws [ChatGptAuthException] with a message for
     * the user when it doesn't complete.
     */
    suspend fun signIn(providerId: String): Boolean = signInLock.withLock {
        withContext(Dispatchers.IO) {
            val discovery = oauth.discover()
            val account = account(providerId)
            val hostId = hostId()
            val listener = LoopbackRedirect.open(PORT, ChatGptOAuth.CALLBACK_PATH, anyPortIfBusy = true)
                ?: throw ChatGptAuthException("Couldn't open a port for the sign-in. Try again.")
            listener.use {
                val pending = oauth.authorizationRequest(hostId, it.redirectUri, account, idTokenHint = load(providerId)?.idToken)
                LoopbackRedirect.openBrowser(context, pending.url)
                it.await(pending.state, ::ChatGptAuthException) { params ->
                    val session = try {
                        oauth.complete(pending, params, discovery)
                    } catch (e: ChatGptAuthException) {
                        // Declining the plan permission still registers a client; keep it for the retry.
                        if (account != null && params["error"] == "access_denied") putAccount(providerId, account.copy(planDeclined = true))
                        throw e
                    }
                    putAccount(providerId, ChatGptAccount(session.clientId, session.subject, session.email, planDeclined = !session.canUsePlan))
                    if (!session.canUsePlan) {
                        throw ChatGptAuthException("ChatGPT didn't allow Locus to use your plan. Continue with ChatGPT again and allow plan use, or use an API key provider.")
                    }
                    tokens.exclusive { save(providerId, session) }
                }
            }
            LoopbackRedirect.bringAppBack(context)
            account == null
        }
    }

    /** A current access token for [providerId], refreshed first when it's about to expire. */
    suspend fun accessToken(providerId: String): String = withContext(Dispatchers.IO) {
        try {
            tokens.accessToken(providerId)
        } catch (e: ChatGptAuthException) {
            if (e.clientInvalid) putAccount(providerId, null)
            throw e
        }
    }

    /**
     * Signs out: revokes the renewable session, then clears the tokens either way. Returns false when
     * OpenAI couldn't confirm the revocation (the user can disconnect Locus in ChatGPT settings).
     */
    suspend fun signOut(providerId: String): Boolean = withContext(Dispatchers.IO) {
        tokens.exclusive {
            val session = load(providerId) ?: return@exclusive true
            val revoked = runCatching { oauth.revoke(session, runCatching { oauth.discover() }.getOrNull()) }.getOrDefault(false)
            save(providerId, null)
            revoked
        }
    }

    /** For a removed provider: signs out and forgets its registration too. */
    suspend fun forget(providerId: String): Boolean {
        val revoked = signOut(providerId)
        putAccount(providerId, null)
        return revoked
    }

    private suspend fun hostId(): String =
        secrets.get(SecretStore.CHATGPT_HOST_ID) ?: ChatGptOAuth.newHostId().also { secrets.put(SecretStore.CHATGPT_HOST_ID, it) }

    private suspend fun account(providerId: String): ChatGptAccount? =
        secrets.get(SecretStore.chatGptAccount(providerId))?.let { runCatching { json.decodeFromString(ChatGptAccount.serializer(), it) }.getOrNull() }

    private suspend fun putAccount(providerId: String, account: ChatGptAccount?) =
        secrets.put(SecretStore.chatGptAccount(providerId), account?.let { json.encodeToString(ChatGptAccount.serializer(), it) })

    private suspend fun load(providerId: String): ChatGptSession? =
        secrets.get(SecretStore.chatGptSession(providerId))?.let { runCatching { json.decodeFromString(ChatGptSession.serializer(), it) }.getOrNull() }

    private suspend fun save(providerId: String, session: ChatGptSession?) =
        secrets.put(SecretStore.chatGptSession(providerId), session?.let { json.encodeToString(ChatGptSession.serializer(), it) })

    private companion object {
        /** Preferred; SIWC lets later sign-ins use another port, so a busy one falls back to any free port. */
        const val PORT = 1455
    }
}
