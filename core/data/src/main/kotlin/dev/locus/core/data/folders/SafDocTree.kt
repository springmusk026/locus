package dev.locus.core.data.folders

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.webkit.MimeTypeMap
import dev.locus.core.agent.tools.DocEntry
import dev.locus.core.agent.tools.DocTree
import dev.locus.core.agent.tools.DocTreeException
import dev.locus.core.agent.tools.DocTreeException.Problem
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * A connected folder's tree through the Storage Access Framework (docs/design/connected-folders.md).
 * Every document is found by listing its parent's children with one `DocumentsContract` query per
 * folder (not `DocumentFile`, which asks the provider again for each child's name and type), starting
 * from the granted tree's own document, so nothing outside the grant can be reached. Document ids
 * found on the way are kept for [ID_TTL_MS] and dropped on every change.
 */
class SafDocTree(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
    private val clock: () -> Long = System::currentTimeMillis,
) : DocTree {
    private class Child(val id: String, val entry: DocEntry, val mime: String?)

    private val rootId: String by lazy {
        try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: IllegalArgumentException) {
            throw DocTreeException(Problem.GONE, "the saved folder address isn't a folder tree")
        }
    }

    private val ids = HashMap<String, Pair<Long, String>>()

    private fun docUri(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)

    override fun root(): DocEntry = top { guard { query(docUri(rootId))?.entry ?: throw DocTreeException(Problem.GONE, "the folder is gone") } }

    override fun list(path: List<String>): List<DocEntry> =
        if (path.isEmpty()) top { guard { children(rootId).map { it.entry } } } else guard { children(idOf(path, directory = true)).map { it.entry } }

    /** At the tree's top, "not found" means the granted folder itself is gone. */
    private inline fun <T> top(block: () -> T): T = try {
        block()
    } catch (e: DocTreeException) {
        throw if (e.problem == Problem.NOT_FOUND) DocTreeException(Problem.GONE, e.message.orEmpty()) else e
    }

    override fun open(path: List<String>): InputStream = guard {
        resolver.openInputStream(docUri(idOf(path))) ?: throw DocTreeException(Problem.FAILED, "the storage app returned nothing")
    }

    override fun write(path: List<String>, bytes: ByteArray) = guard {
        val uri = docUri(idOf(path))
        // "wt" truncates; some providers only know "w" (which, for a few, doesn't truncate: the file is
        // rewritten from the start and the old tail is cut by writing the whole length).
        val truncating = try {
            resolver.openOutputStream(uri, "wt")
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: FileNotFoundException) {
            if (e.message?.contains("mode", ignoreCase = true) == true) null else throw e
        }
        val out = truncating ?: resolver.openOutputStream(uri, "w")
            ?: throw DocTreeException(Problem.FAILED, "the storage app wouldn't open the file for writing")
        out.use { it.write(bytes) }
    }

    override fun createFile(parent: List<String>, name: String): String = guard {
        val ext = name.substringAfterLast('.', "").lowercase()
        // A MIME type that matches the extension, or octet-stream: providers add an extension for a type that doesn't match.
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        create(parent, name, mime)
    }

    override fun createDirectory(parent: List<String>, name: String): String = guard { create(parent, name, Document.MIME_TYPE_DIR) }

    private fun create(parent: List<String>, name: String, mime: String): String {
        val parentId = idOf(parent, directory = true)
        val uri = DocumentsContract.createDocument(resolver, docUri(parentId), mime, name)
            ?: throw DocTreeException(Problem.REJECTED, "it couldn't create '$name' (the storage may be full or read-only)")
        ids.clear()
        return query(uri)?.entry?.name ?: name
    }

    override fun delete(path: List<String>) = guard {
        if (path.isEmpty()) throw DocTreeException(Problem.REJECTED, "the connected folder itself can't be deleted")
        val ok = DocumentsContract.deleteDocument(resolver, docUri(idOf(path)))
        ids.clear()
        if (!ok) throw DocTreeException(Problem.REJECTED, "it didn't delete it")
    }

    override fun rename(path: List<String>, newName: String): String = guard {
        if (path.isEmpty()) throw DocTreeException(Problem.REJECTED, "the connected folder itself can't be renamed")
        val old = docUri(idOf(path))
        // The provider may give the document a new id (ExternalStorageProvider's ids are paths); null means it kept it.
        val renamed = DocumentsContract.renameDocument(resolver, old, newName) ?: old
        ids.clear()
        query(renamed)?.entry?.name ?: newName
    }

    override fun uriOf(path: List<String>): String = guard { docUri(idOf(path)).toString() }

    // ---- Lookups

    /** The document id at [path], walking from the tree's top by name. */
    private fun idOf(path: List<String>, directory: Boolean = false): String {
        var id = rootId
        val now = clock()
        for (i in path.indices) {
            val key = path.subList(0, i + 1).joinToString("/")
            val cached = ids[key]?.takeIf { now - it.first < ID_TTL_MS }?.second
            id = cached ?: run {
                val child = children(id).firstOrNull { it.entry.name == path[i] }
                    ?: throw DocTreeException(Problem.NOT_FOUND, "'${path.joinToString("/")}' isn't there")
                if (i < path.lastIndex && !child.entry.isDirectory) throw DocTreeException(Problem.NOT_FOUND, "'$key' is a file, not a folder")
                ids[key] = now to child.id
                child.id
            }
        }
        if (ids.size > MAX_IDS) ids.clear()
        if (directory && path.isNotEmpty() && query(docUri(id))?.entry?.isDirectory == false) {
            throw DocTreeException(Problem.NOT_FOUND, "'${path.joinToString("/")}' is a file, not a folder")
        }
        return id
    }

    private fun children(parentId: String): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val cursor = resolver.query(uri, PROJECTION, null, null, null)
            ?: throw DocTreeException(Problem.GONE, "the storage app returned no listing")
        return cursor.use { c -> buildList { while (c.moveToNext()) read(c)?.let(::add) } }
    }

    private fun query(uri: Uri): Child? =
        resolver.query(uri, PROJECTION, null, null, null)?.use { c -> if (c.moveToFirst()) read(c) else null }

    private fun read(c: Cursor): Child? {
        val id = c.getString(0) ?: return null
        val name = c.getString(1) ?: return null
        val mime = c.getString(2)
        val flags = if (c.isNull(5)) 0 else c.getInt(5)
        val dir = mime == Document.MIME_TYPE_DIR
        return Child(
            id,
            DocEntry(
                name = name,
                isDirectory = dir,
                size = if (c.isNull(3)) 0 else c.getLong(3),
                modified = if (c.isNull(4)) 0 else c.getLong(4),
                canWrite = flags and Document.FLAG_SUPPORTS_WRITE != 0,
                canDelete = flags and Document.FLAG_SUPPORTS_DELETE != 0,
                canRename = flags and Document.FLAG_SUPPORTS_RENAME != 0,
                canCreate = flags and Document.FLAG_DIR_SUPPORTS_CREATE != 0,
            ),
            mime,
        )
    }

    /** Turns what providers throw into [DocTreeException]s the file layer can explain. */
    private inline fun <T> guard(block: () -> T): T = try {
        synchronized(this) { block() }
    } catch (e: DocTreeException) {
        throw e
    } catch (e: SecurityException) {
        throw DocTreeException(Problem.PERMISSION_LOST, e.message.orEmpty())
    } catch (e: FileNotFoundException) {
        throw DocTreeException(Problem.NOT_FOUND, e.message.orEmpty())
    } catch (e: UnsupportedOperationException) {
        throw DocTreeException(Problem.UNSUPPORTED, e.message.orEmpty())
    } catch (e: IllegalStateException) {
        throw DocTreeException(Problem.REJECTED, e.message.orEmpty())
    } catch (e: IllegalArgumentException) {
        throw DocTreeException(Problem.GONE, e.message.orEmpty())
    } catch (e: java.io.IOException) {
        throw DocTreeException(Problem.FAILED, e.message.orEmpty())
    }

    private companion object {
        const val ID_TTL_MS = 3_000L
        const val MAX_IDS = 2_048
        val PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
        )
    }
}
