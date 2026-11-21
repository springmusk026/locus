package dev.locus.core.data.integrations

import dev.locus.core.agent.home.ConnectorKeys
import dev.locus.core.agent.integrations.ChannelHttpException
import dev.locus.core.agent.integrations.Gotify
import dev.locus.core.agent.integrations.Integration
import dev.locus.core.agent.integrations.Integrations
import dev.locus.core.agent.integrations.Webhook
import dev.locus.core.data.R
import dev.locus.core.data.connectors.ServiceSetupException
import dev.locus.core.data.connectors.networkMessage
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
    WEBHOOK(Integrations.webhook),
    ;

    companion object {
        fun of(integrationId: String): ReachMeChannel? = entries.firstOrNull { it.integration.id == integrationId }
    }
}

/**
 * Settings → Integrations → Gotify or Webhook: check the address and secret, send a test, save them
 * in the vault together (see [Gotify.parse], [Webhook.parse]), or remove them.
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
            ReachMeChannel.WEBHOOK -> Webhook.parse(value)?.let { it.url to it.secret }
        }
    }

    /** The saved address, to fill the sheet (the secret is never read back to the UI). */
    suspend fun savedUrl(channel: ReachMeChannel): String? = saved(channel)?.first

    /** Whether a secret is saved with it: a webhook may have none. */
    suspend fun hasSecret(channel: ReachMeChannel): Boolean = saved(channel)?.second != null

    /**
     * The vault value for [url] and [secret]. [secret] blank keeps the saved one when [url] has the same
     * origin; for a webhook, null means "no signing secret".
     */
    internal suspend fun value(channel: ReachMeChannel, url: String, secret: String?): String {
        val checked = when (channel) {
            ReachMeChannel.GOTIFY -> dev.locus.core.model.ServiceAddress.check(url)
            ReachMeChannel.WEBHOOK -> Webhook.check(url)
        }
        val address = checked.url ?: throw ServiceSetupException(EngineText.Plain(checked.error.orEmpty()), checked.error.orEmpty())
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

    /** Sends a short test message with [url] and [secret] (see [value]). */
    suspend fun sendTest(channel: ReachMeChannel, url: String, secret: String?) {
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
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ChannelHttpException) {
            throw ServiceSetupException(statusText(channel, e.status, host), "$host answered HTTP ${e.status}.")
        } catch (e: Exception) {
            throw ServiceSetupException(networkMessage(e, host) { text -> forms.fold(text) { acc, f -> acc.replace(f, "[secret]") } }, "Couldn't reach $host.")
        }
    }

    suspend fun save(channel: ReachMeChannel, url: String, secret: String?) = secrets.put(name(channel), value(channel, url, secret))

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
