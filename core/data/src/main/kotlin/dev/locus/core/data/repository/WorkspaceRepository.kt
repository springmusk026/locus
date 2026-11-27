package dev.locus.core.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.locus.core.agent.ChatScope
import dev.locus.core.agent.tools.TrashEntry
import dev.locus.core.agent.tools.Workspace
import dev.locus.core.data.R
import dev.locus.core.linux.EngineStateException
import dev.locus.core.linux.EngineText
import dev.locus.core.model.ChatScopeSpec
import dev.locus.core.model.FileTrees
import dev.locus.core.model.Part
import androidx.core.graphics.scale
import dev.locus.core.data.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class WorkspaceEntry(val path: String, val name: String, val isDirectory: Boolean, val size: Long, val modified: Long)

/** File operations the user performs from the Workspace screen (the agent uses its own tools). */
@Singleton
class WorkspaceRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val workspace: Workspace,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    suspend fun list(path: String): List<WorkspaceEntry> = withContext(io) {
        // A connected folder (docs/design/connected-folders.md): listed through its provider.
        val c = workspace.connected
        c?.target(path)?.let { t ->
            if (!c.existing(t).isDirectory) throw EngineStateException(EngineText.Res(R.string.data_workspace_is_file, t.path), "${t.path} is a file.")
            return@withContext c.list(t, fresh = true)
                .map { WorkspaceEntry(t.child(it.name).path, it.name, it.isDirectory, if (it.isDirectory) 0 else it.size, it.modified) }
                .sortedWith(compareByDescending<WorkspaceEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
        }
        workspace.resolve(path).listFiles().orEmpty()
            .map { it.toEntry() }
            .sortedWith(compareByDescending<WorkspaceEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    /** Text preview, or null for binary files. */
    /** The most recently changed files, for home. Skips hidden folders and dependency trees. */
    suspend fun recent(limit: Int): List<WorkspaceEntry> = withContext(io) {
        val skip = setOf("node_modules", "venv", ".venv", "__pycache__", "build", "dist", "target")
        workspace.resolve(".").walkTopDown()
            .maxDepth(4)
            // Never into a linked folder: Linux can link to anywhere on the phone, Locus's own data included.
            .onEnter { dir -> dir == workspace.resolve(".") || (!dir.name.startsWith(".") && dir.name !in skip && !FileTrees.isLink(dir)) }
            .filter { !FileTrees.isLink(it) }
            .filter { it.isFile && !it.name.startsWith(".") }
            .sortedByDescending { it.lastModified() }
            .take(limit)
            .map { it.toEntry() }
            .toList()
    }

    suspend fun preview(path: String, maxChars: Int = 200_000): String? = withContext(io) {
        val file = workspace.resolve(path)
        val bytes = file.inputStream().use { input ->
            val buffer = ByteArray(minOf(file.length(), maxChars.toLong()).toInt())
            var read = 0
            while (read < buffer.size) {
                val n = input.read(buffer, read, buffer.size - read)
                if (n < 0) break
                read += n
            }
            buffer.copyOf(read)
        }
        if (bytes.any { it == 0.toByte() }) null else bytes.decodeToString()
    }

    suspend fun import(uri: Uri, intoFolder: String): String = withContext(io) {
        val name = displayName(uri) ?: "imported-${System.currentTimeMillis()}"
        val target = uniqueFile(workspace.resolve(intoFolder), name)
        // Never through a link a Linux program left at the name or on the way (Workspace.write).
        context.contentResolver.openInputStream(uri)?.use { input -> workspace.write(target) { input.copyTo(it) } }
            ?: throw EngineStateException(EngineText.Res(R.string.data_workspace_read_failed), "Could not read the selected file.")
        workspace.relative(target)
    }

    /**
     * Copies a file another app shared into [intoFolder], under the [SharedImport] rules: `content://`
     * from another app only, at most [SharedImport.MAX_BYTES]. Throws [SharedImportException] with
     * the reason otherwise; a partial copy is removed.
     */
    suspend fun importShared(uri: Uri, intoFolder: String): String = withContext(io) {
        SharedImport.refusal(uri.scheme, uri.authority, context.packageName)?.let { throw SharedImportException(it) }
        val name = displayName(uri) ?: "shared-${System.currentTimeMillis()}"
        val target = uniqueFile(workspace.resolve(intoFolder), name)
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw SharedImportException("it couldn't be read")
            input.use { source ->
                workspace.write(target) { out ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        val n = source.read(buffer)
                        if (n < 0) break
                        copied += n
                        SharedImport.checkSize(copied)
                        out.write(buffer, 0, n)
                    }
                }
            }
        } catch (e: Exception) {
            target.delete()
            if (e is SharedImportException || e is kotlinx.coroutines.CancellationException) throw e
            throw SharedImportException(e.message ?: "it couldn't be read")
        }
        workspace.relative(target)
    }

    /**
     * Copies an attachment into `inbox/` of the folder the chat's tools are rooted at: the project's
     * folder in a project chat ([scope]; docs/design/projects.md), otherwise the whole workspace. Its
     * [Part.Attachment.workspacePath] is relative to that same folder, so the path the model is told
     * is the one read_file, ls and Linux's `/workspace` resolve. Photos are downscaled to at most
     * 1568 px on the long edge (larger images cost more and gain nothing for vision models) and saved
     * as JPEG.
     */
    suspend fun importAttachment(uri: Uri, scope: ChatScopeSpec? = null): Part.Attachment = withContext(io) {
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        if (mime.startsWith("image/") && mime != "image/gif") {
            val bitmap = context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) }
            if (bitmap != null) {
                val scale = minOf(1f, 1568f / maxOf(bitmap.width, bitmap.height))
                val scaled = if (scale < 1f) bitmap.scale((bitmap.width * scale).toInt(), (bitmap.height * scale).toInt()) else bitmap
                val name = (displayName(uri) ?: "photo").substringBeforeLast('.') + ".jpg"
                return@withContext saveAttachment(name, "image/jpeg", scope) { scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            }
        }
        val name = displayName(uri) ?: "imported-${System.currentTimeMillis()}"
        val input = context.contentResolver.openInputStream(uri) ?: throw EngineStateException(EngineText.Res(R.string.data_workspace_read_failed), "Could not read the selected file.")
        val finalMime = if (name.endsWith(".jpg") && mime.startsWith("image/")) "image/jpeg" else mime
        input.use { source -> saveAttachment(name, finalMime, scope) { source.copyTo(it) } }
    }

    /**
     * Writes a new attachment named [name] into `inbox/` of [scope]'s root (see [importAttachment]),
     * under a free name, never through a link (Workspace.write).
     */
    internal suspend fun saveAttachment(name: String, mime: String, scope: ChatScopeSpec?, write: (java.io.OutputStream) -> Unit): Part.Attachment =
        withContext(io + ChatScope.element(scope)) {
            val target = uniqueFile(workspace.resolve(INBOX), name)
            workspace.write(target, write)
            attachment(target, mime)
        }

    /**
     * [a], not yet sent, made to fit a chat whose scope is now [scope] (it was just moved into or out of
     * a project): a file already inside the new root only gets its path renamed relative to it; one
     * outside (in the workspace's `inbox/` when the chat moved into a project) is copied into the
     * project's `inbox/`, since the project's tools can't reach it. A file that's gone is left as it was.
     */
    suspend fun attachmentFor(a: Part.Attachment, scope: ChatScopeSpec?): Part.Attachment = withContext(io + ChatScope.element(scope)) {
        val file = File(a.path)
        if (!file.isFile || FileTrees.isLink(file)) return@withContext a
        val real = file.canonicalFile
        val root = workspace.root
        val inside = real.path.startsWith(root.path + File.separator) &&
            runCatching { workspace.resolve(workspace.relative(real)) == real }.getOrDefault(false)
        if (inside) a.copy(path = real.path, workspacePath = workspace.relative(real))
        else saveAttachment(real.name, a.mimeType, scope) { out -> real.inputStream().use { it.copyTo(out) } }
    }

    private fun attachment(file: File, mime: String) = Part.Attachment(file.name, mime, file.path, workspace.relative(file), file.length())

    suspend fun export(path: String, uri: Uri) = withContext(io) {
        context.contentResolver.openOutputStream(uri)?.use { out -> workspace.resolve(path).inputStream().use { it.copyTo(out) } }
            ?: throw EngineStateException(EngineText.Res(R.string.data_workspace_write_failed), "Could not write to the chosen location.")
    }

    suspend fun delete(path: String) = withContext(io) {
        val c = workspace.connected
        c?.target(path)?.let { return@withContext c.trash(it, TrashEntry.Reason.DELETED) }
        workspace.trash(workspace.resolve(path), TrashEntry.Reason.DELETED)
    }

    /** Whether [path] is in a connected folder rather than the workspace. */
    fun isConnected(path: String): Boolean = workspace.connected?.claims(path) == true

    /** A step the user can't take here: [text] for the screen, [english] for logs. */
    private fun fail(text: EngineText, english: String): Nothing = throw EngineStateException(text, english)

    /**
     * A copy of the connected folder's file [path] for the viewers, in the cache (they read files), and
     * the provider's own address for Open with… and Share. Files over [MAX_VIEW_BYTES] aren't copied.
     */
    suspend fun viewConnected(path: String): Pair<File, String?> = withContext(io) {
        val c = workspace.connected ?: error("No connected folders.")
        val t = c.target(path) ?: error("$path isn't in a connected folder.")
        val e = c.existing(t)
        if (e.isDirectory) fail(EngineText.Res(R.string.data_workspace_is_folder, t.path), "${t.path} is a folder.")
        if (e.size > MAX_VIEW_BYTES) {
            val mb = (MAX_VIEW_BYTES / 1024 / 1024).toInt()
            fail(EngineText.Res(R.string.data_workspace_too_big, t.name, mb), "${t.name} is over $mb MB; open it in another app from your Files app.")
        }
        val dir = File(context.cacheDir, "connected-view").apply { deleteRecursively(); mkdirs() }
        val copy = File(dir, t.name)
        c.open(t).use { input -> copy.outputStream().use { input.copyTo(it) } }
        if (e.modified > 0) copy.setLastModified(e.modified)
        copy to c.uriOf(t)
    }

    private companion object {
        const val MAX_VIEW_BYTES = 50L * 1024 * 1024

        /** Where attachments go, inside the chat's root. */
        const val INBOX = "inbox"

        /** Attached photos are saved as JPEG at this quality. */
        const val JPEG_QUALITY = 85
    }

    suspend fun trash(): List<TrashEntry> = withContext(io) { workspace.trashEntries() }

    suspend fun restore(id: String): String = withContext(io) { workspace.restore(id) }

    suspend fun purge(id: String) = withContext(io) { workspace.purgeTrash(id) }

    private fun File.toEntry() = WorkspaceEntry(workspace.relative(this), name, isDirectory, if (isFile) length() else 0, lastModified())

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }?.replace('/', '_')

    private fun uniqueFile(dir: File, name: String): File {
        dir.mkdirs()
        var candidate = File(dir, name)
        var n = 1
        // FileTrees.exists: a dangling link Linux left at a name counts as taken, never written through.
        while (FileTrees.exists(candidate)) {
            candidate = File(dir, "${name.substringBeforeLast('.', name)} ($n)${name.substringAfterLast('.', "").let { if (it.isEmpty() || !name.contains('.')) "" else ".$it" }}")
            n++
        }
        return candidate
    }
}
