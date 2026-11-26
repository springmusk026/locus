package dev.locus.core.data.provider

import dev.locus.core.agent.tools.TrashEntry
import dev.locus.core.agent.tools.Workspace
import java.io.File
import java.io.FileNotFoundException

/**
 * The file rules behind [WorkspaceDocumentsProvider], apart from Android's types so they are tested on
 * the JVM. The provider is exported with `grantUriPermissions`, so every id and name here comes from
 * another app (docs/design/android-bridge.md, "The workspace in other apps"):
 *
 * - an id is a workspace-relative path, resolved by [Workspace.resolve], which canonicalises it and
 *   refuses anything outside the workspace;
 * - a subtree grant covers a document only when its canonical file is inside the granted folder's
 *   ([isChild]), so `projects/a/../b/x` is not a child of `projects/a`;
 * - a new or renamed document's name is one path segment, never `.` or `..`, and its file must land in
 *   the folder it was asked for ([create], [rename]);
 * - the workspace root can't be renamed or deleted;
 * - a write keeps a copy of what it replaces in the workspace trash first ([beforeOpen]).
 */
internal class WorkspaceDocuments(private val workspace: Workspace) {

    fun fileFor(documentId: String): File {
        val file = runCatching { workspace.resolve(if (documentId == ROOT_DOC) "." else documentId) }.getOrNull()
        if (file == null || !file.exists()) throw FileNotFoundException(documentId)
        return file
    }

    fun idFor(file: File): String = workspace.relative(file).let { if (it == ".") ROOT_DOC else it }

    /** Whether [documentId] is [parentDocumentId] or inside it, comparing the real (canonical) files. */
    fun isChild(parentDocumentId: String, documentId: String): Boolean {
        val parent = runCatching { fileFor(parentDocumentId) }.getOrNull() ?: return false
        val child = runCatching { fileFor(documentId) }.getOrNull() ?: return false
        return child == parent || child.path.startsWith(parent.path + File.separator)
    }

    /** Creates a file or folder named [displayName] in [parentDocumentId]; a taken name gets " (1)" before its extension. */
    fun create(parentDocumentId: String, directory: Boolean, displayName: String): File {
        val parent = fileFor(parentDocumentId)
        if (!parent.isDirectory) throw IllegalArgumentException("Not a folder: $parentDocumentId")
        val name = cleanName(displayName)
        val target = candidates(name, directory).map { File(parent, it) }.take(MAX_COPIES).firstOrNull { !it.exists() }
            ?: throw IllegalStateException("Cannot create $displayName")
        inside(parent, target)
        val made = if (directory) target.mkdir() else target.createNewFile()
        if (!made) throw IllegalStateException("Cannot create $displayName")
        return target
    }

    /** Renames [documentId] to [displayName] in the same folder. The workspace root is never renamed. */
    fun rename(documentId: String, displayName: String): File {
        if (documentId == ROOT_DOC) throw UnsupportedOperationException("The workspace root cannot be renamed.")
        val file = fileFor(documentId)
        val parent = file.parentFile ?: throw UnsupportedOperationException("The workspace root cannot be renamed.")
        if (file == workspace.root) throw UnsupportedOperationException("The workspace root cannot be renamed.")
        val target = File(parent, cleanName(displayName))
        inside(parent, target)
        if (target.exists() || !file.renameTo(target)) throw IllegalStateException("Cannot rename to $displayName")
        return target
    }

    /** Moves [documentId] to the workspace trash; returns the folder it was in. The root is never deleted. */
    fun delete(documentId: String): File {
        if (documentId == ROOT_DOC) throw UnsupportedOperationException("The workspace root cannot be deleted.")
        val file = fileFor(documentId)
        if (file == workspace.root) throw UnsupportedOperationException("The workspace root cannot be deleted.")
        workspace.trash(file, TrashEntry.Reason.DELETED)
        return file.parentFile ?: workspace.root
    }

    /**
     * Before [documentId] is opened in [mode] ("r", "w", "wt", "wa", "rw" or "rwt"): a mode that can
     * change a file with something in it first keeps a copy in the workspace trash, as the agent's own
     * writes do, so another app's write can be taken back from Workspace → Trash.
     */
    fun beforeOpen(documentId: String, mode: String): File {
        val file = fileFor(documentId)
        if ('w' in mode && file.isFile && file.length() > 0) workspace.backup(file, TrashEntry.Reason.OVERWRITTEN)
        return file
    }

    private fun inside(parent: File, target: File) {
        if (target.canonicalFile.parentFile != parent.canonicalFile) throw IllegalArgumentException("Not a name in this folder.")
    }

    companion object {
        const val ROOT_DOC = "root"

        /** Copies tried before giving up: "a (1).txt" to "a (999).txt". */
        private const val MAX_COPIES = 1_000

        /** [displayName] as one path segment: separators and NULs become '_'; empty, `.` and `..` are refused. */
        fun cleanName(displayName: String): String {
            val name = displayName.replace('/', '_').replace('\\', '_').replace('\u0000', '_').trim()
            if (name.isEmpty() || name == "." || name == "..") throw IllegalArgumentException("Not a usable name: '$displayName'")
            return name
        }

        /** [name], then "name (1).ext", "name (2).ext"…; a folder, or a name without an extension, gets the number at the end. */
        fun candidates(name: String, directory: Boolean): Sequence<String> {
            val dot = name.lastIndexOf('.')
            val (stem, ext) = if (!directory && dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
            return sequenceOf(name) + generateSequence(1) { it + 1 }.map { "$stem ($it)$ext" }
        }
    }
}
