package dev.locus.core.data.integrations

import dev.locus.core.agent.integrations.Integrations
import dev.locus.core.agent.integrations.Telegram
import dev.locus.core.datastore.SecretStore
import io.ktor.client.HttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Settings → Integrations → Telegram. The bot token and chat id are stored together in the vault
 * (see [Telegram.parse]). A blank token in any call means "the one already saved", so the user can
 * change the chat or send a test without pasting the token again. Errors never contain the token.
 */
@Singleton
class TelegramSetup @Inject constructor(
    private val http: HttpClient,
    private val secrets: SecretStore,
) {
    private val name = SecretStore.connectorSecret(Integrations.telegram.secret)

    private suspend fun saved(): Telegram.Target? = secrets.get(name)?.let(Telegram::parse)

    /** The saved chat id, for showing in the sheet (the token is never read back to the UI). */
    suspend fun savedChatId(): Long? = saved()?.chatId

    private suspend fun token(entered: String?): String {
        val token = entered?.trim()?.takeIf { it.isNotEmpty() } ?: saved()?.token
            ?: throw IllegalArgumentException("Paste the bot token from @BotFather first.")
        require(Telegram.isToken(token)) { "That doesn't look like a bot token. It has the form 123456789:AAH… (copy it whole from @BotFather)." }
        return token
    }

    /** Checks the token and lists the chats that have messaged the bot, newest first. */
    suspend fun findChats(token: String?): Telegram.Discovery = Telegram.findChats(http, token(token))

    suspend fun sendTest(token: String?, chatId: Long) =
        Telegram.send(http, Telegram.Target(token(token), chatId), "Locus", "Telegram is connected. Notifications and scheduled task results will arrive here.")

    suspend fun save(token: String?, chatId: Long) = secrets.put(name, Telegram.format(token(token), chatId))

    suspend fun remove() = secrets.put(name, null)
}
