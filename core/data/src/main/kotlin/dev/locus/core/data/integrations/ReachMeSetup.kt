package dev.locus.core.data.integrations

import dev.locus.core.agent.home.ConnectorKeys
import dev.locus.core.agent.integrations.ChannelHttpException
import dev.locus.core.agent.integrations.Gotify
import dev.locus.core.agent.integrations.Integration
import dev.locus.core.agent.integrations.Integrations
import dev.locus.core.agent.integrations.Matrix
import dev.locus.core.agent.integrations.Webhook
import dev.locus.core.data.R
import dev.locus.core.data.connectors.ServiceSetupException
import dev.locus.core.data.connectors.networkMessage
import dev.locus.core.data.connectors.refused
import dev.locus.core.datastore.SecretStore
import dev.locus.core.linux.EngineText
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import java.net.URI
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/** The reach-me channels set up with an address and a secret (docs/design/reach-me-channels.md). */
enum class ReachMeChannel(val integration: Integration) {
    GOTIFY(Integrations.gotify),
    MATRIX(Integrations.matrix),
    WEBHOOK(Integrations.webhook),
    ;

    companion object {
        fun of(integrationId: String): ReachMeChannel? = entries.firstOrNull { it.integration.id == integrationId }
    }
}

/**
 * Settings → Integrations → Gotify, Matrix or Webhook: check the address and secret, send a test, save
 * them in the vault together (see [Gotify.parse], [Matrix.parse], [Webhook.parse]), or remove them.
 *
 * Matrix also needs a room, and is checked against the homeserver before it's saved ([checkMatrix]):
 * the token, the room (an alias is resolved to its ID), that the bot has joined it, and that it isn't
 * end-to-end encrypted, which Locus can't send into.
 *
 * A blank secret means "the one already saved", but only for the same origin: a saved token is never
 * sent to an address the user didn't save it for. Redirects aren't followed. Every failure is a
 * [ServiceSetupException] whose text never contains the secret.
 */
@Singleton
class ReachMeSetup @Inject constructor(http: HttpClient, private val secrets: SecretStore) {
    private val http = http.config { followRedirects = false; expectSuccess = false }

    private fun name(channel: ReachMeChannel) = SecretStore.connectorSecret(channel.integration.secret)

    /** What is saved, as (url, secret or null); null when nothing usable is. */
    private suspend fun saved(channel: ReachMeChannel): Pair<String, String?>? = secrets.get(name(channel))?.let { value ->
        when (channel) {
            ReachMeChannel.GOTIFY -> Gotify.parse(value)?.let { it.url to it.token }
            ReachMeChannel.MATRIX -> Matrix.parse(value)?.let { it.url to it.token }
            ReachMeChannel.WEBHOOK -> Webhook.parse(value)?.let { it.url to it.secret }
        }
    }

    /** The saved address, to fill the sheet (the secret is never read back to the UI). */
    suspend fun savedUrl(channel: ReachMeChannel): String? = saved(channel)?.first

    /** The saved Matrix room's ID, to fill the sheet. */
    suspend fun savedRoom(): String? = secrets.get(name(ReachMeChannel.MATRIX))?.let(Matrix::parse)?.roomId

    /** Whether a secret is saved with it: a webhook may have none. */
    suspend fun hasSecret(channel: ReachMeChannel): Boolean = saved(channel)?.second != null

    /**
     * The vault value for [url] and [secret]. [secret] blank keeps the saved one when [url] has the same
     * origin; for a webhook, null means "no signing secret". For Matrix it is the address and the token;
     * [checkMatrix] adds the room.
     */
    internal suspend fun value(channel: ReachMeChannel, url: String, secret: String?): String {
        val checked = when (channel) {
            ReachMeChannel.GOTIFY, ReachMeChannel.MATRIX -> dev.locus.core.model.ServiceAddress.check(url)
            ReachMeChannel.WEBHOOK -> Webhook.check(url)
        }
        val address = checked.url ?: throw checked.refused()
        val entered = secret?.trim()
        val kept = if (entered != null && entered.isEmpty()) {
            saved(channel)?.takeIf { ConnectorKeys.origin(it.first) == ConnectorKeys.origin(address) }?.second
        } else null
        return when (channel) {
            ReachMeChannel.GOTIFY -> {
                val token = entered?.takeIf { it.isNotEmpty() } ?: kept
                    ?: if (saved(channel) != null) fail(R.string.data_reachme_token_again, "Paste the token again for the new address.")
                    else fail(R.string.data_reachme_token_missing, "Paste the application token.")
                if (!Gotify.isToken(token)) fail(R.string.data_reachme_token_invalid, "That isn't an application token.")
                Gotify.format(address, token)
            }
            ReachMeChannel.MATRIX -> {
                val token = entered?.takeIf { it.isNotEmpty() } ?: kept
                    ?: if (saved(channel) != null) fail(R.string.data_reachme_token_again, "Paste the token again for the new address.")
                    else fail(R.string.data_reachme_matrix_token_missing, "Paste the bot account's access token.")
                if (!Matrix.isToken(token)) fail(R.string.data_reachme_matrix_token_invalid, "That isn't an access token.")
                "$address $token"
            }
            ReachMeChannel.WEBHOOK -> {
                val signing = when {
                    entered == null -> null
                    entered.isNotEmpty() -> entered.also { if (!Webhook.isSecret(it)) fail(R.string.data_reachme_secret_invalid, "That signing secret is too short or has spaces.") }
                    else -> kept ?: if (hasSecret(channel)) fail(R.string.data_reachme_secret_again, "Enter the signing secret again for the new address.") else null
                }
                Webhook.format(address, signing)
            }
        }
    }

    /** What a Matrix check found: the bot's user ID, the room's ID (an alias resolved) and the vault value. */
    data class MatrixCheck(val userId: String, val roomId: String, internal val value: String)

    /**
     * Checks a Matrix setup against the homeserver, in the order a user can fix it: the token
     * (`whoami`), the room (an alias resolved through the room directory), the bot's membership
     * (`joined_rooms`) and the room's encryption (`m.room.encryption` state): an encrypted room is refused.
     */
    suspend fun checkMatrix(url: String, token: String?, room: String?): MatrixCheck {
        val (address, key) = value(ReachMeChannel.MATRIX, url, token).split(' ').let { it[0] to it[1] }
        val typed = room?.trim().orEmpty()
        if (!Matrix.isRoomId(typed) && !Matrix.isAlias(typed)) fail(R.string.data_reachme_matrix_room_invalid, "Enter a room ID (!…) or a room address (#name:server).")
        return matrixCall(hostOf(address), key, typed) {
            val user = Matrix.whoami(http, address, key)
            val roomId = if (Matrix.isAlias(typed)) Matrix.resolveAlias(http, address, key, typed) else typed
            if (roomId !in Matrix.joinedRooms(http, address, key)) {
                throw ServiceSetupException(EngineText.Res(R.string.data_reachme_matrix_not_joined, user), "$user hasn't joined that room.")
            }
            if (Matrix.isEncrypted(http, address, key, roomId)) throw Matrix.encrypted()
            MatrixCheck(user, roomId, Matrix.format(address, key, roomId))
        }
    }

    /** Runs Matrix calls, turning each failure into the user's words without the token. */
    private suspend fun <T> matrixCall(host: String, token: String, room: String, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: ServiceSetupException) {
        throw e
    } catch (e: Matrix.Failure) {
        val text = when (e.problem) {
            Matrix.Problem.TOKEN -> EngineText.Res(R.string.data_reachme_matrix_token_refused, host)
            Matrix.Problem.ALIAS_UNKNOWN -> EngineText.Res(R.string.data_reachme_matrix_alias_unknown, room)
            Matrix.Problem.NOT_JOINED -> EngineText.Res(R.string.data_reachme_matrix_not_member)
            Matrix.Problem.ENCRYPTED -> EngineText.Res(R.string.data_reachme_matrix_encrypted)
            Matrix.Problem.FORBIDDEN -> EngineText.Res(R.string.data_reachme_matrix_forbidden)
            Matrix.Problem.RATE_LIMITED -> EngineText.Res(R.string.data_reachme_matrix_rate_limited, host)
            Matrix.Problem.REDIRECT -> EngineText.Res(R.string.data_reachme_redirect, host)
            Matrix.Problem.NOT_MATRIX -> EngineText.Res(R.string.data_reachme_matrix_not_matrix, host)
            Matrix.Problem.OTHER -> if (e.status >= 500) EngineText.Res(R.string.data_service_server_error, host, e.status)
            else EngineText.Res(R.string.data_service_http, host, e.status)
        }
        throw ServiceSetupException(text, Matrix.scrub(e.message.orEmpty(), token))
    } catch (e: Exception) {
        throw ServiceSetupException(networkMessage(e, host) { Matrix.scrub(it, token) }, "Couldn't reach $host.")
    }

    /**
     * Sends a short test message with [url] and [secret] (see [value]). Matrix also needs [room] and is
     * checked first ([checkMatrix]); its bot's user ID is returned, null for the other channels.
     */
    suspend fun sendTest(channel: ReachMeChannel, url: String, secret: String?, room: String? = null): String? {
        if (channel == ReachMeChannel.MATRIX) {
            val check = checkMatrix(url, secret, room)
            val target = Matrix.parse(check.value)!!
            matrixCall(hostOf(target.url), target.token, room.orEmpty().trim()) {
                Matrix.send(http, target, "Locus", "Matrix is connected. Notifications and scheduled task results will be posted in this room.")
            }
            return check.userId
        }
        val value = value(channel, url, secret)
        val host = hostOf(value.substringBefore(' '))
        val forms = value.split(' ').filter { it.length >= 6 }
        try {
            when (channel) {
                ReachMeChannel.GOTIFY -> Gotify.publish(
                    http, Gotify.parse(value)!!, "Locus",
                    "Gotify is connected. Notifications and scheduled task results will arrive here.",
                )
                ReachMeChannel.WEBHOOK -> Webhook.send(
                    http, Webhook.parse(value)!!, "Locus",
                    "The webhook is connected. Notifications and scheduled task results will be posted here.",
                )
                ReachMeChannel.MATRIX -> Unit
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ChannelHttpException) {
            throw ServiceSetupException(statusText(channel, e.status, host), "$host answered HTTP ${e.status}.")
        } catch (e: Exception) {
            throw ServiceSetupException(networkMessage(e, host) { text -> forms.fold(text) { acc, f -> acc.replace(f, "[secret]") } }, "Couldn't reach $host.")
        }
        return null
    }

    /** Saves the channel. Matrix is checked against the homeserver first ([checkMatrix]) and saved with the room's ID. */
    suspend fun save(channel: ReachMeChannel, url: String, secret: String?, room: String? = null) = secrets.put(
        name(channel),
        if (channel == ReachMeChannel.MATRIX) checkMatrix(url, secret, room).value else value(channel, url, secret),
    )

    suspend fun remove(channel: ReachMeChannel) = secrets.put(name(channel), null)

    private fun statusText(channel: ReachMeChannel, code: Int, host: String): EngineText = when {
        code in 300..399 -> EngineText.Res(R.string.data_reachme_redirect, host)
        (code == 401 || code == 403) && channel == ReachMeChannel.GOTIFY -> EngineText.Res(R.string.data_reachme_gotify_refused, host, code)
        code == 401 || code == 403 -> EngineText.Res(R.string.data_reachme_webhook_refused, host, code)
        code == 404 -> EngineText.Res(R.string.data_reachme_not_found)
        code >= 500 -> EngineText.Res(R.string.data_service_server_error, host, code)
        else -> EngineText.Res(R.string.data_service_http, host, code)
    }

    private fun fail(id: Int, english: String): Nothing = throw ServiceSetupException(EngineText.Res(id), english)

    private fun hostOf(url: String) = runCatching { URI(url).let { it.host + if (it.port != -1) ":${it.port}" else "" } }.getOrDefault("the server")

    companion object {
        /** A random signing secret for a webhook: 32 URL-safe characters (192 bits). */
        fun newSecret(random: SecureRandom = SecureRandom()): String {
            val bytes = ByteArray(24).also(random::nextBytes)
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}
