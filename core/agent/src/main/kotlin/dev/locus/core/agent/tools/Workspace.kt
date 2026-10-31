package dev.locus.core.agent.tools

import dev.locus.core.agent.ToolException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class TrashEntry(
    val id: String,
    /** Path relative to the workspace root. */
    val originalPath: String,
    val reason: Reason,
    val trashedAt: Long,
    val isDirectory: Boolean,
) {
    enum class Reason { DELETED, OVERWRITTEN, MOVED_OVER }
}

/**
 * The agent's folder. Every path the model supplies is resolved inside [root]; anything that
 * escapes it (via `..` or symlinks) is rejected. Destructive changes are made recoverable: deleted
 * files and the previous contents of overwritten files go to [trashDir] and can be restored.
 */
class Workspace(val root: File, private val trashDir: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val canonicalRoot: File = root.apply { mkdirs() }.canonicalFile

    fun resolve(path: String): File {
        val cleaned = path.trim().removePrefix("/").ifEmpty { "." }
        val file = File(canonicalRoot, cleaned).canonicalFile
        if (file != canonicalRoot && !file.path.startsWith(canonicalRoot.path + File.separator)) {
            throw ToolException("Path '$path' is outside the workspace.")
        }
        return file
    }

    fun relative(file: File): String = file.canonicalFile.relativeTo(canonicalRoot).path.ifEmpty { "." }

    fun existing(path: String): File = resolve(path).also { if (!it.exists()) throw ToolException("'$path' does not exist.") }

    /** Moves [file] into the trash and returns its entry. */
    fun trash(file: File, reason: TrashEntry.Reason): TrashEntry {
        val id = "${clock()}-${(0..0xffff).random().toString(16)}"
        val entryDir = File(trashDir, id).apply { mkdirs() }
        val entry = TrashEntry(id, relative(file), reason, clock(), file.isDirectory)
        if (!file.renameTo(File(entryDir, PAYLOAD))) {
            file.copyRecursively(File(entryDir, PAYLOAD), overwrite = true)
            file.deleteRecursively()
        }
        File(entryDir, META).writeText(json.encodeToString(TrashEntry.serializer(), entry))
        return entry
    }

    /** Keeps a copy of [file]'s current contents before it is overwritten. */
    fun backup(file: File, reason: TrashEntry.Reason): TrashEntry {
        val id = "${clock()}-${(0..0xffff).random().toString(16)}"
        val entryDir = File(trashDir, id).apply { mkdirs() }
        val entry = TrashEntry(id, relative(file), reason, clock(), file.isDirectory)
        file.copyRecursively(File(entryDir, PAYLOAD), overwrite = true)
        File(entryDir, META).writeText(json.encodeToString(TrashEntry.serializer(), entry))
        return entry
    }

    fun trashEntries(): List<TrashEntry> =
        trashDir.listFiles().orEmpty()
            .mapNotNull { dir -> runCatching { json.decodeFromString(TrashEntry.serializer(), File(dir, META).readText()) }.getOrNull() }
            .sortedByDescending { it.trashedAt }

    /** Puts a trashed item back. If something now occupies its path, that is trashed first. */
    fun restore(id: String): String {
        val entryDir = File(trashDir, id)
        val entry = json.decodeFromString(TrashEntry.serializer(), File(entryDir, META).readText())
        val target = resolve(entry.originalPath)
        if (target.exists()) trash(target, TrashEntry.Reason.MOVED_OVER)
        target.parentFile?.mkdirs()
        val payload = File(entryDir, PAYLOAD)
        if (!payload.renameTo(target)) payload.copyRecursively(target, overwrite = true)
        entryDir.deleteRecursively()
        return entry.originalPath
    }

    fun purgeTrash(id: String) {
        File(trashDir, id).deleteRecursively()
    }

    private companion object {
        const val PAYLOAD = "payload"
        const val META = "entry.json"
        val json = Json { ignoreUnknownKeys = true }
    }
}
