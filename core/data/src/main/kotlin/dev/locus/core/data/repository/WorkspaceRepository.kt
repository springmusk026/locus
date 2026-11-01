package dev.locus.core.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.locus.core.agent.tools.TrashEntry
import dev.locus.core.agent.tools.Workspace
import dev.locus.core.model.Part
import androidx.core.graphics.scale
import kotlinx.coroutines.Dispatchers
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
) {
    suspend fun list(path: String): List<WorkspaceEntry> = withContext(Dispatchers.IO) {
        workspace.resolve(path).listFiles().orEmpty()
            .map { it.toEntry() }
            .sortedWith(compareByDescending<WorkspaceEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    /** Text preview, or null for binary files. */
    /** The most recently changed files, for home. Skips hidden folders and dependency trees. */
    suspend fun recent(limit: Int): List<WorkspaceEntry> = withContext(Dispatchers.IO) {
        val skip = setOf("node_modules", "venv", ".venv", "__pycache__", "build", "dist", "target")
        workspace.resolve(".").walkTopDown()
            .maxDepth(4)
            .onEnter { dir -> dir == workspace.resolve(".") || (!dir.name.startsWith(".") && dir.name !in skip) }
            .filter { it.isFile && !it.name.startsWith(".") }
            .sortedByDescending { it.lastModified() }
            .take(limit)
            .map { it.toEntry() }
            .toList()
    }

    suspend fun preview(path: String, maxChars: Int = 200_000): String? = withContext(Dispatchers.IO) {
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

    suspend fun import(uri: Uri, intoFolder: String): String = withContext(Dispatchers.IO) {
        val name = displayName(uri) ?: "imported-${System.currentTimeMillis()}"
        val target = uniqueFile(workspace.resolve(intoFolder), name)
        context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
            ?: error("Could not read the selected file.")
        workspace.relative(target)
    }

    /**
     * Copies a file another app shared into [intoFolder], under the [SharedImport] rules: `content://`
     * from another app only, at most [SharedImport.MAX_BYTES]. Throws [SharedImportException] with
     * the reason otherwise; a partial copy is removed.
     */
    suspend fun importShared(uri: Uri, intoFolder: String): String = withContext(Dispatchers.IO) {
        SharedImport.refusal(uri.scheme, uri.authority, context.packageName)?.let { throw SharedImportException(it) }
        val name = displayName(uri) ?: "shared-${System.currentTimeMillis()}"
        val target = uniqueFile(workspace.resolve(intoFolder), name)
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw SharedImportException("it couldn't be read")
            input.use { source ->
                target.outputStream().use { out ->
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
     * Copies an attachment into `inbox/`. Photos are downscaled to at most 1568 px on the long edge
     * (larger images cost more and gain nothing for vision models) and saved as JPEG.
     */
    suspend fun importAttachment(uri: Uri): Part.Attachment = withContext(Dispatchers.IO) {
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val rel = if (mime.startsWith("image/") && mime != "image/gif") {
            val bitmap = context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) }
            if (bitmap != null) {
                val scale = minOf(1f, 1568f / maxOf(bitmap.width, bitmap.height))
                val scaled = if (scale < 1f) bitmap.scale((bitmap.width * scale).toInt(), (bitmap.height * scale).toInt()) else bitmap
                val name = (displayName(uri) ?: "photo").substringBeforeLast('.') + ".jpg"
                val target = uniqueFile(workspace.resolve("inbox"), name)
                target.outputStream().use { scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }
                workspace.relative(target)
            } else import(uri, "inbox")
        } else import(uri, "inbox")
        val file = workspace.resolve(rel)
        val finalMime = if (rel.endsWith(".jpg") && mime.startsWith("image/")) "image/jpeg" else mime
        Part.Attachment(file.name, finalMime, file.path, rel, file.length())
    }

    suspend fun export(path: String, uri: Uri) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri)?.use { out -> workspace.resolve(path).inputStream().use { it.copyTo(out) } }
            ?: error("Could not write to the chosen location.")
    }

    suspend fun delete(path: String) = withContext(Dispatchers.IO) {
        workspace.trash(workspace.resolve(path), TrashEntry.Reason.DELETED)
    }

    suspend fun trash(): List<TrashEntry> = withContext(Dispatchers.IO) { workspace.trashEntries() }

    suspend fun restore(id: String): String = withContext(Dispatchers.IO) { workspace.restore(id) }

    suspend fun purge(id: String) = withContext(Dispatchers.IO) { workspace.purgeTrash(id) }

    private fun File.toEntry() = WorkspaceEntry(workspace.relative(this), name, isDirectory, if (isFile) length() else 0, lastModified())

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }?.replace('/', '_')

    private fun uniqueFile(dir: File, name: String): File {
        dir.mkdirs()
        var candidate = File(dir, name)
        var n = 1
        while (candidate.exists()) {
            candidate = File(dir, "${name.substringBeforeLast('.', name)} ($n)${name.substringAfterLast('.', "").let { if (it.isEmpty() || !name.contains('.')) "" else ".$it" }}")
            n++
        }
        return candidate
    }
}
