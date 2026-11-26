package dev.locus.core.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the user typed in each chat's composer and hasn't sent, kept when they leave the chat and across
 * restarts, and shown as "Draft:" in the chat list (#319). One small text file per chat in no-backup storage,
 * as [dev.locus.core.data.agent.MessageQueue] keeps queued messages: no database change, and a restored
 * backup never brings back a stale draft. A temporary chat's draft stays in memory only.
 *
 * [dir] null keeps every draft in memory (tests).
 */
@Singleton
class ChatDrafts(
    private val dir: File?,
    private val writer: Executor = Executor { it.run() },
) {
    @Inject constructor(@ApplicationContext context: Context) : this(
        File(context.noBackupFilesDir, DIR),
        Executors.newSingleThreadExecutor { r -> Thread(r, "locus-drafts").apply { isDaemon = true } }::execute,
    )

    private val _drafts = MutableStateFlow<Map<String, String>>(emptyMap())
    private val loaded = lazy { load() }

    /** Every chat's unsent text, by chat id. */
    val drafts: StateFlow<Map<String, String>>
        get() {
            loaded.value
            return _drafts.asStateFlow()
        }

    /** The unsent text of [chatId], or null. */
    fun get(chatId: String): String? {
        loaded.value
        return _drafts.value[chatId]
    }

    /** Keeps [text] as [chatId]'s draft (on disk unless [inMemory]); blank text drops it. */
    fun set(chatId: String, text: String, inMemory: Boolean = false) {
        loaded.value
        val kept = text.takeIf { it.isNotBlank() }
        if (inMemory && _drafts.value[chatId] == kept) return
        _drafts.update { if (kept == null) it - chatId else it + (chatId to kept) }
        // In memory only: nothing on disk (a temporary chat), or the write comes once typing pauses.
        if (!inMemory) write(chatId, kept)
    }

    /** Drops [chatId]'s draft (the chat was deleted). */
    fun clear(chatId: String) {
        loaded.value
        _drafts.update { it - chatId }
        write(chatId, null)
    }

    private fun load() {
        val files = dir?.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) }.orEmpty()
        val saved = files.mapNotNull { f -> runCatching { f.name.removeSuffix(SUFFIX) to f.readText() }.getOrNull()?.takeIf { it.second.isNotBlank() } }.toMap()
        // Anything set before the files were read wins.
        _drafts.update { saved + it }
    }

    private fun write(chatId: String, text: String?) {
        val dir = dir ?: return
        // Chat ids are UUIDs; anything else never becomes a path.
        if (!SAFE_ID.matches(chatId)) return
        writer.execute {
            runCatching {
                val file = File(dir, chatId + SUFFIX)
                if (text == null) {
                    file.delete()
                    return@runCatching
                }
                dir.mkdirs()
                val tmp = File(dir, "$chatId$SUFFIX.tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(file)) {
                    file.delete()
                    tmp.renameTo(file)
                }
            }
        }
    }

    companion object {
        const val DIR = "drafts"
        private const val SUFFIX = ".txt"
        private val SAFE_ID = Regex("[A-Za-z0-9_-]{1,100}")
    }
}
