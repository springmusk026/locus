package dev.locus.core.data.chatimport

import dev.locus.core.data.repository.ConversationRepository
import dev.locus.core.data.repository.ConversationRepository.Companion.toEntity
import dev.locus.core.database.ConversationEntity
import dev.locus.core.model.ChatImportSource
import dev.locus.core.model.ImportedChats
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** The words an import writes into chats, in the user's language (the screen passes them from its resources). */
class ImportTexts(
    /** A chat with no title and no text to name it by. */
    val untitled: String = "Imported chat",
    /** Heads an attachment's extracted text: "Attached: notes.txt". */
    val attached: (name: String) -> String = { "Attached: $it" },
    /** The line under a message whose files weren't imported: "Not imported: a.pdf, 2 images". */
    val notImported: (names: List<String>, images: Int) -> String = { names, images ->
        "Not imported: " + (names + listOfNotNull(images.takeIf { it > 0 }?.let { "$it image(s)" })).joinToString(", ")
    },
)

/** A readable chat found by [ChatImporter.scan]: enough to count and filter, not its messages. */
data class ScanChat(
    /** Its Locus id ([ImportedChats.chatId]). */
    val id: String,
    val source: ChatImportSource,
    val createdAt: Long,
    val updatedAt: Long,
    /** Files and images in it that won't be imported. */
    val notImported: Int,
    /** Parts of unknown types that were skipped. */
    val unknownParts: Int,
)

/** What [ChatImporter.scan] found, before anything is written. */
data class ImportScan(
    val chats: List<ScanChat>,
    /** Entries that weren't conversations Locus can read. */
    val unreadable: Int,
    /** Conversations with nothing to show. */
    val empty: Int,
    /** A file ended early; [chats] is what came before. */
    val truncated: Boolean,
    /** Chats already imported: Locus id to the update time stored. */
    val existing: Map<String, Long>,
) {
    /** Chats that started in [from] ..< [until]; null bounds are open. */
    fun plan(from: Long? = null, until: Long? = null): ImportPlan =
        plan { (from == null || it.createdAt >= from) && (until == null || it.createdAt < until) }

    /** What importing the chats [chosen] picks would do: new, updated (the export's copy is newer) and unchanged. */
    fun plan(pick: (ScanChat) -> Boolean): ImportPlan {
        val chosen = chats.filter(pick).distinctBy { it.id }
        val known = chosen.filter { it.id in existing }
        val newer = known.count { it.updatedAt > existing.getValue(it.id) }
        return ImportPlan(
            new = chosen.size - known.size,
            updated = newer,
            unchanged = known.size - newer,
            notImported = chosen.sumOf { it.notImported },
            ids = chosen.filter { it.id !in existing || it.updatedAt > existing.getValue(it.id) }.mapTo(HashSet()) { it.id },
        )
    }

    /** The sources found, most chats first. */
    val sources: List<ChatImportSource> get() = chats.groupingBy { it.source }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
    val oldest: Long? get() = chats.minOfOrNull { it.createdAt }
    val newest: Long? get() = chats.maxOfOrNull { it.createdAt }
}

/** What an import of a selection will do. */
data class ImportPlan(val new: Int, val updated: Int, val unchanged: Int, val notImported: Int, val ids: Set<String>) {
    val toWrite: Int get() = new + updated
}

/** How far a scan or an import has got. */
data class ImportProgress(val bytes: Long, val totalBytes: Long?, val chats: Int)

data class ImportResult(val imported: Int, val updated: Int, val failed: Int)

/**
 * Imports ChatGPT and Claude exports as read-only chats (docs/design/import-chats.md). Two passes over
 * the files, each a stream ([ExportReader]): [scan] counts what's there for the preview and writes
 * nothing; [import] reads again and writes the chosen chats, each in one transaction, so cancelling
 * leaves whole chats only. A chat already imported is skipped, or replaced when the export's copy is newer.
 */
@Singleton
class ChatImporter @Inject constructor(private val conversations: ConversationRepository) {

    suspend fun scan(files: List<ExportFile>, onProgress: (ImportProgress) -> Unit = {}): ImportScan = withContext(Dispatchers.IO) {
        val total = files.totalSize()
        val chats = ArrayList<ScanChat>()
        var unreadable = 0
        var empty = 0
        var bytes = 0L
        val summary = ExportReader().read(files, onBytes = { bytes = it; onProgress(ImportProgress(it, total, chats.size)) }) { entry ->
            when (entry) {
                is ParsedEntry.Chat -> {
                    val c = entry.chat
                    chats += ScanChat(
                        ImportedChats.chatId(c.source, c.sourceId), c.source, c.createdAt, c.updatedAt,
                        notImported = c.notImported.size + c.imagesNotImported,
                        unknownParts = c.unknownParts,
                    )
                    if (chats.size % PROGRESS_EVERY == 0) onProgress(ImportProgress(bytes, total, chats.size))
                }
                is ParsedEntry.Empty -> empty++
                is ParsedEntry.Unreadable -> unreadable++
            }
        }
        onProgress(ImportProgress(bytes, total, chats.size))
        ImportScan(chats, unreadable, empty, summary.truncated, conversations.importedStamps().mapValues { it.value.updatedAt })
    }

    /** Writes the chats in [ids] (from [ImportPlan.ids]) found in [files]. */
    suspend fun import(files: List<ExportFile>, ids: Set<String>, texts: ImportTexts = ImportTexts(), onProgress: (ImportProgress) -> Unit = {}): ImportResult = withContext(Dispatchers.IO) {
        val total = files.totalSize()
        var imported = 0
        var updated = 0
        var failed = 0
        var bytes = 0L
        val written = HashSet<String>()
        // Read once: a query per chat would be quadratic for an export of thousands.
        val stamps = conversations.importedStamps().toMutableMap()
        ExportReader(texts.attached).read(files, onBytes = { bytes = it; onProgress(ImportProgress(it, total, imported + updated)) }) { entry ->
            val chat = (entry as? ParsedEntry.Chat)?.chat ?: return@read
            val id = ImportedChats.chatId(chat.source, chat.sourceId)
            if (id !in ids || !written.add(id)) return@read
            val existing = stamps[id]
            if (existing != null && chat.updatedAt <= existing.updatedAt) return@read
            try {
                val (conversation, messages) = entities(chat, id, texts, pinned = existing?.pinned == true)
                conversations.saveImported(conversation, messages, replacing = existing != null)
                if (existing != null) updated++ else imported++
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                failed++
            }
            onProgress(ImportProgress(bytes, total, imported + updated))
        }
        ImportResult(imported, updated, failed)
    }

    private fun List<ExportFile>.totalSize(): Long? = if (any { it.size == null || it.size <= 0 }) null else sumOf { it.size ?: 0 }

    internal companion object {
        const val PROGRESS_EVERY = 25
        private const val TITLE_MAX = 60

        /** The rows for [chat]: a conversation with no model and no prompt (it is never sent), and its messages. */
        fun entities(chat: ImportedChat, id: String, texts: ImportTexts, pinned: Boolean = false): Pair<ConversationEntity, List<dev.locus.core.database.MessageEntity>> {
            val ids = HashMap<String, String>()
            val used = HashSet<String>()
            chat.messages.forEachIndexed { i, m ->
                var mid = ImportedChats.messageId(id, m.nodeId)
                // Two node ids that clean to the same text: keep both.
                if (!used.add(mid)) mid = ImportedChats.messageId(id, "${m.nodeId}_$i").also { used.add(it) }
                ids[m.nodeId] = mid
            }
            val messages = chat.messages.map { m ->
                val note = if (m.notImported.isNotEmpty() || m.imagesNotImported > 0) texts.notImported(m.notImported, m.imagesNotImported) else null
                val text = listOfNotNull(m.text.takeIf { it.isNotBlank() }, note).joinToString("\n\n")
                Message(ids.getValue(m.nodeId), id, m.role, listOf(Part.Text(text)), m.createdAt)
                    .toEntity(parentId = m.parentNodeId?.let(ids::get), copiedFrom = ImportedChats.sourceRef(chat.source, m.nodeId))
            }
            val title = chat.title ?: chat.firstUserText?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.take(TITLE_MAX) ?: texts.untitled
            val conversation = ConversationEntity(
                id = id,
                title = title,
                providerId = null,
                systemPrompt = "",
                createdAt = chat.createdAt,
                updatedAt = chat.updatedAt,
                pinned = pinned,
                activeLeafId = chat.activeLeaf?.let(ids::get),
            )
            return conversation to messages
        }
    }
}
