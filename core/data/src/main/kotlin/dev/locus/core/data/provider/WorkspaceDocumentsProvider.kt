package dev.locus.core.data.provider

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import dev.locus.core.agent.tools.TrashEntry
import dev.locus.core.agent.tools.Workspace
import java.io.File
import java.io.FileNotFoundException

/**
 * Shows the agent's workspace in the system Files app and in other apps' file pickers, so the user
 * can open, add and edit the same files the agent works on. Document IDs are workspace-relative
 * paths; deletes go to the workspace trash like the agent's own deletes.
 */
class WorkspaceDocumentsProvider : DocumentsProvider() {

    private lateinit var workspace: Workspace

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        workspace = Workspace(File(ctx.filesDir, "workspace"), File(ctx.filesDir, "workspace-trash"))
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: ROOT_COLUMNS).apply {
        newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT_ID)
            add(Root.COLUMN_DOCUMENT_ID, ROOT_DOC)
            add(Root.COLUMN_TITLE, "Locus workspace")
            add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_LOCAL_ONLY)
            add(Root.COLUMN_ICON, context!!.applicationInfo.icon)
            add(Root.COLUMN_AVAILABLE_BYTES, workspace.root.freeSpace)
        }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply { addFile(this, documentId, fileFor(documentId)) }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        fileFor(parentDocumentId).listFiles().orEmpty().forEach { addFile(cursor, idFor(it), it) }
        cursor.setNotificationUri(context!!.contentResolver, android.provider.DocumentsContract.buildChildDocumentsUri(authority(), parentDocumentId))
        return cursor
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileFor(documentId), ParcelFileDescriptor.parseMode(mode))

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = fileFor(parentDocumentId)
        var target = File(parent, displayName.replace('/', '_'))
        var n = 1
        while (target.exists()) target = File(parent, "$displayName (${n++})")
        if (mimeType == Document.MIME_TYPE_DIR) target.mkdirs() else target.createNewFile()
        notifyChildren(parentDocumentId)
        return idFor(target)
    }

    override fun deleteDocument(documentId: String) {
        val file = fileFor(documentId)
        if (documentId == ROOT_DOC) throw UnsupportedOperationException("The workspace root cannot be deleted.")
        workspace.trash(file, TrashEntry.Reason.DELETED)
        notifyChildren(idFor(file.parentFile ?: workspace.root))
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val file = fileFor(documentId)
        val target = File(file.parentFile, displayName.replace('/', '_'))
        if (target.exists() || !file.renameTo(target)) throw IllegalStateException("Cannot rename to $displayName")
        notifyChildren(idFor(target.parentFile ?: workspace.root))
        return idFor(target)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        documentId == parentDocumentId || parentDocumentId == ROOT_DOC || documentId.startsWith("$parentDocumentId/")

    override fun getDocumentType(documentId: String): String = mimeType(fileFor(documentId))

    private fun fileFor(documentId: String): File {
        val file = runCatching { workspace.resolve(if (documentId == ROOT_DOC) "." else documentId) }.getOrNull()
        if (file == null || !file.exists()) throw FileNotFoundException(documentId)
        return file
    }

    private fun idFor(file: File): String = workspace.relative(file).let { if (it == ".") ROOT_DOC else it }

    private fun addFile(cursor: MatrixCursor, id: String, file: File) {
        var flags = Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        flags = if (file.isDirectory) flags or Document.FLAG_DIR_SUPPORTS_CREATE else flags or Document.FLAG_SUPPORTS_WRITE
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, id)
            add(Document.COLUMN_DISPLAY_NAME, if (id == ROOT_DOC) "Locus workspace" else file.name)
            add(Document.COLUMN_SIZE, if (file.isFile) file.length() else null)
            add(Document.COLUMN_MIME_TYPE, mimeType(file))
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            add(Document.COLUMN_FLAGS, if (id == ROOT_DOC) Document.FLAG_DIR_SUPPORTS_CREATE else flags)
        }
    }

    private fun mimeType(file: File): String = if (file.isDirectory) {
        Document.MIME_TYPE_DIR
    } else {
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
    }

    private fun authority() = "${context!!.packageName}.workspace"

    private fun notifyChildren(parentId: String) {
        context?.contentResolver?.notifyChange(android.provider.DocumentsContract.buildChildDocumentsUri(authority(), parentId), null)
    }

    private companion object {
        const val ROOT_ID = "workspace"
        const val ROOT_DOC = "root"
        val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS, Root.COLUMN_ICON, Root.COLUMN_AVAILABLE_BYTES,
        )
        val DOCUMENT_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )
    }
}
