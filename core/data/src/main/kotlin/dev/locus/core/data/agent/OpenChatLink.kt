package dev.locus.core.data.agent

import android.content.Context
import android.content.Intent
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * "Open this chat" on the intents Locus's own notifications launch it with.
 *
 * MainActivity is exported (it's the launcher and the share target), so any app can start it with
 * any extras. A chat id alone would let another app open a chat of its choosing. So the intent also
 * carries a token only Locus knows: random, made once per install, kept in no-backup storage, and
 * the chat opens only when it matches. It outlives the process, so a notification posted before
 * Locus was killed still opens its chat.
 */
object OpenChatLink {
    /** The chat to show. */
    const val EXTRA_CHAT = "dev.locus.extra.OPEN_CHAT"
    private const val EXTRA_TOKEN = "dev.locus.extra.OPEN_CHAT_TOKEN"
    private const val FILE = "open_chat_token"

    @Volatile private var cached: String? = null

    /** Adds [conversationId] and the token to [intent], one Locus is about to wrap in a PendingIntent. */
    fun attach(context: Context, intent: Intent, conversationId: String): Intent =
        intent.putExtra(EXTRA_CHAT, conversationId).putExtra(EXTRA_TOKEN, token(context))

    /** The chat [intent] asks to open, only when it came from Locus; null otherwise. */
    fun chatId(context: Context, intent: Intent): String? =
        verified(intent.getStringExtra(EXTRA_CHAT), intent.getStringExtra(EXTRA_TOKEN), token(context))

    /** Removes both extras, so the intent isn't handled twice. */
    fun clear(intent: Intent) {
        intent.removeExtra(EXTRA_CHAT)
        intent.removeExtra(EXTRA_TOKEN)
    }

    /** [chatId] when [presented] matches [expected], compared in constant time. */
    internal fun verified(chatId: String?, presented: String?, expected: String): String? {
        if (chatId.isNullOrBlank() || presented == null || expected.isEmpty()) return null
        return chatId.takeIf { MessageDigest.isEqual(presented.toByteArray(), expected.toByteArray()) }
    }

    internal fun newToken(random: SecureRandom = SecureRandom()): String =
        ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    @Synchronized
    private fun token(context: Context): String = cached ?: run {
        val file = File(context.noBackupFilesDir, FILE)
        val existing = runCatching { file.readText().trim() }.getOrNull()?.takeIf { it.length == 64 }
        (existing ?: newToken().also { file.writeText(it) }).also { cached = it }
    }
}
