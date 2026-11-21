package dev.locus.core.data.repository

import dev.locus.core.agent.tools.ShellSnapshots
import dev.locus.core.model.FileTrees
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * A command in a project with "Snapshot before shell commands" on whose file changes weren't tracked, and
 * why (docs/design/shell-snapshots.md, "When it skips"). Shown on the turn's changes card.
 */
@Serializable
data class ShellNote(
    val conversationId: String,
    val turnId: String,
    val callId: String,
    val reason: ShellSnapshots.SkipReason,
    val at: Long,
)

/**
 * The [ShellNote]s, newest [MAX_NOTES] kept, in one small JSON file in the app's no-backup storage (the
 * snapshots they're about aren't backed up either). A store without a folder keeps them in memory only.
 */
class ShellNotes(private val file: File?) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(ShellNote.serializer())
    private val all = MutableStateFlow(load())

    private fun load(): List<ShellNote> {
        val f = file ?: return emptyList()
        if (!f.isFile || FileTrees.isLink(f)) return emptyList()
        return runCatching { json.decodeFromString(serializer, f.readText()) }.getOrDefault(emptyList())
    }

    /** [conversationId]'s notes by turn id. */
    fun observe(conversationId: String): Flow<Map<String, List<ShellNote>>> =
        all.map { notes -> notes.filter { it.conversationId == conversationId }.groupBy { it.turnId } }.distinctUntilChanged()

    fun add(note: ShellNote) {
        all.update { (it + note).takeLast(MAX_NOTES) }
        val f = file ?: return
        val parent = f.parentFile ?: return
        runCatching {
            if (!FileTrees.exists(parent)) parent.mkdirs()
            FileTrees.writeReplacing(parent, f, json.encodeToString(serializer, all.value))
        }
    }

    companion object {
        const val MAX_NOTES = 300
    }
}
