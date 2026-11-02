package dev.locus.core.data.integrations

import dev.locus.core.agent.integrations.TelegramRemote
import dev.locus.core.model.IntegrityKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

/**
 * The Telegram remote inbox on disk (docs/design/telegram-remote.md, "Inbox"): a small JSON file in the
 * app's no-backup folder, written atomically (temp file, fsync, rename), so a message taken in survives
 * the process.
 *
 * It is sealed with the same Keystore HMAC key as the permissions ([IntegrityKey]). The file is reachable
 * from Locus's Linux, which runs as the same user, and a forged "accepted" entry would otherwise be run as
 * a message from the user's Telegram. A file whose seal fails is ignored (treated as empty), so nothing in
 * it runs; an unreadable key fails the same way.
 */
class TelegramInboxFile(private val file: File, private val key: IntegrityKey) : TelegramRemote.InboxStore {
    @Serializable
    private data class Sealed(val entries: List<TelegramRemote.InboxEntry>, val mac: String)

    private val json = Json { ignoreUnknownKeys = true }
    private val list = ListSerializer(TelegramRemote.InboxEntry.serializer())

    override suspend fun load(): List<TelegramRemote.InboxEntry> = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext emptyList()
        try {
            val sealed = json.decodeFromString(Sealed.serializer(), file.readText())
            if (java.security.MessageDigest.isEqual(sealed.mac.toByteArray(), mac(sealed.entries).toByteArray())) sealed.entries else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun save(entries: List<TelegramRemote.InboxEntry>) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(json.encodeToString(Sealed.serializer(), Sealed(entries, mac(entries))).encodeToByteArray())
            out.fd.sync()
        }
        check(tmp.renameTo(file)) { "Could not save the Telegram inbox" }
    }

    private fun mac(entries: List<TelegramRemote.InboxEntry>): String =
        key.mac((CONTEXT + json.encodeToString(list, entries)).encodeToByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val CONTEXT = "locus-telegram-inbox-v1:"
    }
}
