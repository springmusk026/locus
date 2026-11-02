package dev.locus.core.agent.tools

import dev.locus.core.agent.ChatScope
import dev.locus.core.agent.ToolException
import dev.locus.core.model.ProjectPolicy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class TrashEntry(
    val id: String,
    /** Path relative to the whole workspace (not a project's folder). */
    val originalPath: String,
    val reason: Reason,
    val trashedAt: Long,
    val isDirectory: Boolean,
) {
    enum class Reason { DELETED, OVERWRITTEN, MOVED_OVER, MOVED_AWAY }
}

/**
 * The agent's folder. Every path the model supplies is resolved inside [root]; anything that
 * escapes it (via `..` or symlinks) is rejected. Destructive changes are made recoverable: deleted
 * files and the previous contents of overwritten files go to [trashDir] and can be restored.
 *
 * In a project chat ([ChatScope]), [root] is the project's folder, so paths resolve, and are shown,
 * relative to it, and nothing outside it can be named (docs/design/projects.md). Trash entries and
 * change records always use paths relative to the whole workspace ([stored]), so the Workspace
 * screen and Undo, which run outside any chat, find them.
 */
class Workspace(base: File, private val trashDir: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val canonicalBase: File = base.apply { mkdirs() }.canonicalFile

    /** The whole workspace, whatever chat is running. */
    val base: File get() = canonicalBase

    /** The folder paths resolve in: the project's in a project chat, otherwise the whole workspace. */
    val root: File get() = scopedRoot()

    private fun scopedRoot(): File {
        val folder = ChatScope.current()?.folder ?: return canonicalBase
        // A folder that fails the check can't come from Locus's own project editor; refuse rather than widen.
        val clean = ProjectPolicy.cleanFolder(folder) ?: throw ToolException("This project's folder '$folder' isn't a valid workspace folder.")
        val dir = File(canonicalBase, clean).apply { mkdirs() }.canonicalFile
        if (!dir.path.startsWith(canonicalBase.path + File.separator)) throw ToolException("This project's folder is outside the workspace.")
        return dir
    }

    fun resolve(path: String): File = resolveIn(scopedRoot(), path, if (ChatScope.current() != null) "the project's folder" else "the workspace")

    /** Resolves [path] against the whole workspace, ignoring any project (trash and undo records). */
    fun resolveStored(path: String): File = resolveIn(canonicalBase, path, "the workspace")

    private fun resolveIn(root: File, path: String, what: String): File {
        val cleaned = path.trim().removePrefix("/").ifEmpty { "." }
        val file = File(root, cleaned).canonicalFile
        if (file != root && !file.path.startsWith(root.path + File.separator)) {
            throw ToolException("Path '$path' is outside $what.")
        }
        return file
    }

    /** [file]'s path as the agent sees it: relative to the project's folder in a project chat. */
    fun relative(file: File): String = file.canonicalFile.relativeTo(scopedRoot()).path.ifEmpty { "." }

    /** [file]'s path relative to the whole workspace, for records read outside the chat (trash, undo). */
    fun stored(file: File): String = file.canonicalFile.relativeTo(canonicalBase).path.ifEmpty { "." }

    fun existing(path: String): File = resolve(path).also { if (!it.exists()) throw ToolException("'$path' does not exist.") }

    /** Moves [file] into the trash and returns its entry. */
    fun trash(file: File, reason: TrashEntry.Reason): TrashEntry {
        val id = "${clock()}-${(0..0xffff).random().toString(16)}"
        val entryDir = File(trashDir, id).apply { mkdirs() }
        val entry = TrashEntry(id, stored(file), reason, clock(), file.isDirectory)
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
        val entry = TrashEntry(id, stored(file), reason, clock(), file.isDirectory)
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
        val target = resolveStored(entry.originalPath)
        if (target.exists()) trash(target, TrashEntry.Reason.MOVED_OVER)
        target.parentFile?.mkdirs()
        val payload = File(entryDir, PAYLOAD)
        if (!payload.renameTo(target)) payload.copyRecursively(target, overwrite = true)
        entryDir.deleteRecursively()
        return entry.originalPath
    }

    /**
     * The kept copy of trash entry [id] (a file, or a folder for a trashed folder), or null once it has
     * been restored or purged. Read it, never move it: [restore] does that.
     */
    fun trashPayload(id: String): File? {
        if (!SAFE_ID.matches(id)) return null
        return File(File(trashDir, id), PAYLOAD).takeIf { it.exists() }
    }

    fun purgeTrash(id: String) {
        File(trashDir, id).deleteRecursively()
    }

    private companion object {
        const val PAYLOAD = "payload"
        const val META = "entry.json"
        val SAFE_ID = Regex("[0-9a-f-]{1,40}")
        val json = Json { ignoreUnknownKeys = true }
    }
}
