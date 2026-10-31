package dev.locus.feature.workspace.preview

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File

/** An opened workspace file: what it is, and its text for the kinds shown as text. */
data class OpenFile(
    val path: String,
    val file: File,
    val size: Long,
    val modified: Long,
    val kind: PreviewKind,
    val text: String? = null,
    /** The file is larger than what was read, so [text] is only its beginning. */
    val truncated: Boolean = false,
    /** JSON that was re-indented for reading (the file itself is untouched). */
    val prettyPrinted: Boolean = false,
) {
    val name: String get() = file.name
}

internal object FileLoader {
    /** How much of a text file the viewer reads. Bigger files show their beginning with a notice. */
    const val MAX_TEXT_BYTES = 1_000_000

    private val textKinds = setOf(PreviewKind.Markdown, PreviewKind.Web, PreviewKind.Table, PreviewKind.Json, PreviewKind.Code, PreviewKind.Text)

    private val prettyJson = Json { prettyPrint = true; prettyPrintIndent = "  " }

    suspend fun load(path: String, file: File): OpenFile = withContext(Dispatchers.IO) {
        val kind = previewKindFor(file.name)
        val base = OpenFile(path, file, file.length(), file.lastModified(), kind)
        if (kind !in textKinds) return@withContext base
        val bytes = readHead(file, MAX_TEXT_BYTES)
        // A NUL byte in the first few KB means binary (the same test git and grep use).
        if (bytes.take(8_192).any { it == 0.toByte() }) return@withContext base.copy(kind = PreviewKind.Binary)
        var text = bytes.decodeToString().removePrefix("\uFEFF")
        val truncated = file.length() > bytes.size
        var pretty = false
        if (kind == PreviewKind.Json && !truncated && file.extension.lowercase() != "jsonc" && file.extension.lowercase() != "json5") {
            runCatching { prettyJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(text)) }
                .getOrNull()
                ?.takeIf { it != text }
                ?.let { text = it; pretty = true }
        }
        base.copy(text = text, truncated = truncated, prettyPrinted = pretty)
    }

    private fun readHead(file: File, max: Int): ByteArray = file.inputStream().use { input ->
        val buffer = ByteArray(minOf(file.length(), max.toLong()).toInt())
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n < 0) break
            read += n
        }
        buffer.copyOf(read)
    }
}
