package dev.locus.core.browser

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the page looked like at one browser call, kept for the user ("See the page" on a past browser step;
 * docs/design/browser.md, "The page as it was"). Never sent to a model: the transcript keeps the call's
 * result exactly as the model got it, and this sits beside it in its own files.
 *
 * [text] is the whole snapshot as the model would have seen it without a diff (the result itself is often
 * only the changes). [picture] says whether a small picture of the viewport was kept: only when the browser
 * was on screen at that moment, since drawing an off-screen WebView needs a hidden window and a wait on
 * every step.
 */
data class PageRecord(
    val url: String,
    val title: String,
    val at: Long,
    val text: String,
    val picture: Boolean = false,
    val pictureWidth: Int = 0,
    val pictureHeight: Int = 0,
) {
    /** As stored: a small JSON object (this module has no serialization plugin; the shape is pinned by BrowserPagesTest). */
    fun toJson(): String = buildJsonObject {
        put("url", url)
        put("title", title)
        put("at", at)
        put("text", text)
        put("picture", picture)
        put("pictureWidth", pictureWidth)
        put("pictureHeight", pictureHeight)
    }.toString()

    companion object {
        /** A stored record; null when it isn't one (a damaged or foreign file). */
        fun fromJson(text: String): PageRecord? = runCatching {
            val o = Json.parseToJsonElement(text) as? JsonObject ?: return null
            fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
            PageRecord(
                url = str("url") ?: return null,
                title = str("title").orEmpty(),
                at = (o["at"] as? JsonPrimitive)?.longOrNull ?: 0L,
                text = str("text") ?: return null,
                picture = (o["picture"] as? JsonPrimitive)?.booleanOrNull == true,
                pictureWidth = (o["pictureWidth"] as? JsonPrimitive)?.intOrNull ?: 0,
                pictureHeight = (o["pictureHeight"] as? JsonPrimitive)?.intOrNull ?: 0,
            )
        }.getOrNull()
    }
}

/**
 * The [PageRecord]s of every chat, one JSON file per call (`<chat>/<call>.json`) and its picture beside it
 * (`<call>.jpg`), in the app's private files under [DIR]. Not in backups, like `tool-images`: a restored chat
 * shows "not kept" instead. A chat's records go when the chat is deleted ([forget]).
 *
 * Bounded: [MAX_TEXT] characters of text and a [PICTURE_WIDTH]-pixel JPEG per call, the newest [PER_CHAT]
 * calls per chat, and [MAX_BYTES] in all, the oldest going first ([prune]).
 */
@Singleton
class BrowserPages internal constructor(
    private val root: File,
    private val maxBytes: Long = MAX_BYTES,
    private val perChat: Int = PER_CHAT,
) {
    @Inject constructor(@ApplicationContext context: Context) : this(File(context.filesDir, DIR))

    private val lock = Any()

    /** Keeps [record] (and [jpeg], the picture's bytes) for call [callId] of chat [conversationId], replacing any earlier one. */
    fun save(conversationId: String, callId: String, record: PageRecord, jpeg: ByteArray? = null) {
        val chat = dirOf(conversationId) ?: return
        val name = safe(callId) ?: return
        synchronized(lock) {
            chat.mkdirs()
            val kept = record.copy(text = record.text.take(MAX_TEXT), picture = jpeg != null)
            val pic = File(chat, "$name$PICTURE")
            if (jpeg != null) write(pic, jpeg) else pic.delete()
            write(File(chat, "$name$RECORD"), kept.toJson().toByteArray())
            prune()
        }
    }

    /** The record of call [callId] in chat [conversationId]; null when none was kept (or it can't be read). */
    fun load(conversationId: String, callId: String): PageRecord? {
        val file = recordFile(conversationId, callId) ?: return null
        if (!file.isFile) return null
        return runCatching { PageRecord.fromJson(file.readText()) }.getOrNull()
    }

    /** Whether a record of the call exists, without reading it. */
    fun has(conversationId: String, callId: String): Boolean = recordFile(conversationId, callId)?.isFile == true

    /** The picture of call [callId], when one was kept. */
    fun pictureOf(conversationId: String, callId: String): File? {
        val chat = dirOf(conversationId) ?: return null
        val name = safe(callId) ?: return null
        return File(chat, "$name$PICTURE").takeIf { it.isFile }
    }

    /** Deletes everything kept for [conversationIds] (a deleted chat and its helpers). */
    fun forget(conversationIds: Collection<String>) = synchronized(lock) {
        conversationIds.forEach { id -> dirOf(id)?.deleteRecursively() }
    }

    private val background = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /** [forget] on a background thread, for callers that shouldn't wait on the disk (deleting a chat from the UI). */
    fun forgetSoon(conversationIds: Collection<String>) {
        val ids = conversationIds.toList()
        background.launch { runCatching { forget(ids) } }
    }

    /** The newest [perChat] calls per chat, then the newest across chats until they fit [maxBytes]. */
    internal fun prune() = synchronized(lock) {
        val all = root.listFiles { f -> f.isDirectory }.orEmpty().flatMap { chat ->
            val calls = calls(chat).sortedByDescending { it.modified }
            calls.drop(perChat).forEach(Call::delete)
            calls.take(perChat).also { if (it.isEmpty()) chat.delete() }
        }
        var total = all.sumOf { it.bytes }
        for (call in all.sortedBy { it.modified }) {
            if (total <= maxBytes) break
            total -= call.bytes
            call.delete()
        }
        root.listFiles { f -> f.isDirectory && f.list().isNullOrEmpty() }?.forEach { it.delete() }
    }

    private class Call(val record: File, val picture: File) {
        val modified = record.lastModified()
        val bytes = record.length() + picture.length()
        fun delete() {
            record.delete()
            picture.delete()
        }
    }

    private fun calls(chat: File): List<Call> = chat.listFiles { f -> f.name.endsWith(RECORD) }.orEmpty().map { f ->
        Call(f, File(chat, f.name.removeSuffix(RECORD) + PICTURE))
    }

    private fun recordFile(conversationId: String, callId: String): File? {
        val chat = dirOf(conversationId) ?: return null
        val name = safe(callId) ?: return null
        return File(chat, "$name$RECORD")
    }

    private fun dirOf(conversationId: String): File? = safe(conversationId)?.let { File(root, it) }

    /** Written next to its place and moved in, so a reader never sees half a file. */
    private fun write(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    companion object {
        const val DIR = "browser-pages"
        const val MAX_TEXT = 60_000
        const val PICTURE_WIDTH = 720
        const val PICTURE_QUALITY = 70
        const val PER_CHAT = 60
        const val MAX_BYTES = 48L * 1024 * 1024
        private const val RECORD = ".json"
        private const val PICTURE = ".jpg"

        /** The browser tools whose results describe the page after them, so a step of theirs offers "See the page". */
        val SHOWS_PAGE = setOf(
            "browser_open", "browser_snapshot", "browser_click", "browser_type", "browser_select", "browser_fill_form",
            "browser_press_key", "browser_scroll", "browser_back", "browser_wait", "browser_tabs", "browser_dialog",
            "browser_upload", "browser_login", "browser_ask_user",
        )

        /**
         * The page's title and address as a step's result gave them (the snapshot header's last `Page:` and `URL:`
         * lines), for a step whose page wasn't kept. Null when the result names no page.
         */
        fun headerOf(result: String): Pair<String, String>? {
            val lines = result.lines()
            val at = lines.indexOfLast { it.startsWith("URL: ") }.takeIf { it >= 0 } ?: return null
            val url = lines[at].removePrefix("URL: ").trim().takeIf { it.isNotEmpty() } ?: return null
            val title = lines.getOrNull(at - 1)?.takeIf { it.startsWith("Page: ") }?.removePrefix("Page: ")?.trim().orEmpty()
            return title to url
        }

        /**
         * A chat or call id as a file name: ids are UUIDs and provider call ids (`toolu_…`, `call_…`), so
         * anything else (a path separator, `..`, an empty or very long id) isn't stored at all.
         */
        internal fun safe(id: String): String? = id.takeIf { SAFE.matches(it) && it.trim('.').isNotEmpty() }

        private val SAFE = Regex("[A-Za-z0-9_.:-]{1,128}")
    }
}
