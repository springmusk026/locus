package dev.locus.core.data.agent

import dev.locus.core.model.Part
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** A message the user sent while the agent was working, waiting for the next step boundary. */
@Serializable
data class QueuedMessage(
    /** Also the id of the chat message it becomes, so a restart can tell whether it was already added. */
    val id: String,
    val text: String,
    val attachments: List<Part.Attachment> = emptyList(),
    val createdAt: Long,
)

/**
 * Messages queued per chat (steer and queue), kept in one small JSON file per chat so they survive the
 * app being killed, with no database change. The files live in no-backup storage: a restored backup
 * brings back chats, never a stale queue.
 *
 * [take] hands messages over for sending; they stay on disk ("in flight") until [delivered] confirms
 * each one was added to the chat, and [restore] puts back any that weren't (the run was cancelled
 * first). After a crash between the two, [load] brings them back and the runner drops the ones the chat
 * already has ([dropDelivered]).
 */
class MessageQueue(
    private val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Runs file writes. The app passes a single background thread, so the UI never waits on the disk and
     * writes land in order; each write is a snapshot taken under the lock. Tests write directly.
     */
    private val writer: (Runnable) -> Unit = Runnable::run,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(QueuedMessage.serializer())
    private val lock = Any()

    private val _queued = MutableStateFlow<Map<String, List<QueuedMessage>>>(emptyMap())

    /** Waiting messages per chat, oldest first (in-flight ones are no longer shown). */
    val queued: StateFlow<Map<String, List<QueuedMessage>>> = _queued.asStateFlow()

    private val inFlight = mutableMapOf<String, List<QueuedMessage>>()

    /**
     * Reads every chat's saved queue; call once at start. Anything queued in this process before it ran
     * is kept after the saved messages. Returns the chats that have one.
     */
    fun load(): Set<String> = synchronized(lock) {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) }.orEmpty()
        val loaded = files.mapNotNull { file ->
            val chat = file.name.removeSuffix(SUFFIX)
            val list = runCatching { json.decodeFromString(serializer, file.readText()) }.getOrNull()
            if (list.isNullOrEmpty()) {
                file.delete()
                null
            } else {
                chat to list
            }
        }.toMap()
        val merged = loaded.toMutableMap()
        _queued.value.forEach { (chat, list) -> merged[chat] = merged[chat].orEmpty().let { saved -> saved + list.filterNot { m -> saved.any { it.id == m.id } } } }
        _queued.value = merged
        loaded.keys.forEach(::persist)
        merged.keys
    }

    fun get(chatId: String): List<QueuedMessage> = _queued.value[chatId].orEmpty()

    fun has(chatId: String): Boolean = get(chatId).isNotEmpty()

    fun add(chatId: String, text: String, attachments: List<Part.Attachment> = emptyList()): QueuedMessage = synchronized(lock) {
        val message = QueuedMessage(UUID.randomUUID().toString(), text, attachments, clock())
        set(chatId, get(chatId) + message)
        message
    }

    /** Changes a waiting message's text; false when it was already sent (or removed). */
    fun edit(chatId: String, id: String, text: String): Boolean = synchronized(lock) {
        val list = get(chatId)
        if (text.isBlank() || list.none { it.id == id }) return false
        set(chatId, list.map { if (it.id == id) it.copy(text = text.trim()) else it })
        true
    }

    /** Cancels a waiting message; false when it was already sent. */
    fun remove(chatId: String, id: String): Boolean = synchronized(lock) {
        val list = get(chatId)
        if (list.none { it.id == id }) return false
        set(chatId, list.filterNot { it.id == id })
        true
    }

    fun clear(chatId: String) = synchronized(lock) {
        inFlight.remove(chatId)
        set(chatId, emptyList())
    }

    /** Hands over every waiting message, oldest first. They stay saved until [delivered]. */
    fun take(chatId: String): List<QueuedMessage> = synchronized(lock) {
        val list = get(chatId)
        if (list.isEmpty()) return emptyList()
        inFlight[chatId] = inFlight[chatId].orEmpty() + list
        set(chatId, emptyList())
        list
    }

    /** Message [id] is now in the chat: forget it. */
    fun delivered(chatId: String, id: String) = synchronized(lock) {
        val flying = inFlight[chatId] ?: return
        if (flying.none { it.id == id }) return
        inFlight[chatId] = flying.filterNot { it.id == id }
        persist(chatId)
    }

    /** Puts taken messages that never reached the chat back at the front of the queue. */
    fun restore(chatId: String) = synchronized(lock) {
        val flying = inFlight.remove(chatId).orEmpty()
        if (flying.isNotEmpty()) set(chatId, flying + get(chatId))
    }

    /** After a restart: forgets messages the chat already has (added just before the app died). */
    fun dropDelivered(chatId: String, inChat: Set<String>) = synchronized(lock) {
        val list = get(chatId)
        if (list.any { it.id in inChat }) set(chatId, list.filterNot { it.id in inChat })
    }

    private fun set(chatId: String, list: List<QueuedMessage>) {
        _queued.value = if (list.isEmpty()) _queued.value - chatId else _queued.value + (chatId to list)
        persist(chatId)
    }

    /** Writes waiting and in-flight messages; atomically (a temp file renamed over the old one). */
    private fun persist(chatId: String) {
        val all = inFlight[chatId].orEmpty() + get(chatId)
        writer(Runnable { write(chatId, all) })
    }

    private fun write(chatId: String, all: List<QueuedMessage>) {
        // Chat ids are UUIDs; anything else never becomes a path.
        if (!SAFE_ID.matches(chatId)) return
        val file = File(dir, chatId + SUFFIX)
        runCatching {
            if (all.isEmpty()) {
                file.delete()
                return
            }
            dir.mkdirs()
            val tmp = File(dir, "$chatId$SUFFIX.tmp")
            tmp.writeText(json.encodeToString(serializer, all))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    companion object {
        private const val SUFFIX = ".json"
        private val SAFE_ID = Regex("[A-Za-z0-9_-]{1,100}")
    }
}

/**
 * The chat message a queued one becomes, built as a typed message is (see
 * [dev.locus.core.data.repository.ConversationRepository.appendUserMessage]): attachments, the text, and
 * the plan-mode note while plan mode is on. It keeps the queued id.
 */
internal fun QueuedMessage.toChatMessage(chatId: String, planMode: Boolean, now: Long = System.currentTimeMillis()): dev.locus.core.model.Message {
    val parts = attachments + listOfNotNull(
        Part.Text(text).takeIf { text.isNotBlank() },
        dev.locus.core.model.ModeNote.PLAN_MODE.takeIf { planMode }?.let { Part.Text(dev.locus.core.model.ModeNote.wrap(it)) },
    )
    return dev.locus.core.model.Message(id, chatId, dev.locus.core.model.Role.USER, parts, now)
}
