package dev.locus.core.data.chatimport

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeToSequence
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/** A file the user picked: its name, its size when known, and a way to open it (again: preview and import each read it once). */
class ExportFile(val name: String, val size: Long?, val open: () -> InputStream)

/** Why a picked file couldn't be read as an export at all. */
enum class ExportProblem {
    /** Neither a zip nor a JSON list: not an export. */
    NotAnExport,

    /** A zip, but with no conversations file in it (e.g. Claude's projects zip, or the second part of a split download). */
    NoConversations,

    /** The file couldn't be read: damaged, or the phone couldn't open it. */
    Unreadable,
}

class ExportException(val problem: ExportProblem, cause: Throwable? = null) : IOException(problem.name, cause)

/** How a read went beyond the conversations themselves. */
data class ReadSummary(
    /** Conversation files read (a zip may hold several shards). */
    val files: Int,
    /** A file ended early or was damaged part-way: what came before it was still read. */
    val truncated: Boolean,
)

/**
 * Reads ChatGPT and Claude exports as a stream, one conversation at a time, so an export of hundreds of
 * megabytes never sits in memory: the zip is read entry by entry ([ZipInputStream]), and each
 * conversations file is decoded element by element ([decodeToSequence]); only the conversation being
 * parsed is held. A picked file may be:
 *
 * - a ChatGPT zip with `conversations.json`, or its shards `conversations-000.json` …;
 * - a zip that holds the conversations as another zip (OpenAI's privacy portal `Conversations__….zip`,
 *   Claude's `conversations-000.zip`), one level deep;
 * - a Claude zip with `conversations.json` (other files, such as `projects.json`, are skipped);
 * - a conversations JSON file on its own.
 *
 * Each element is recognised by its shape (ChatGPT's `mapping`, Claude's `chat_messages`), the way
 * LibreChat's importer does, so a file's name doesn't decide. See docs/design/import-chats.md.
 */
class ExportReader(private val attachedLabel: (String) -> String = { "Attached: $it" }) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Reads every conversation in [files], in order, handing each to [onEntry]. [onBytes] gets the bytes
     * read so far across all files (compressed bytes for a zip), for progress. Cancellation is checked
     * between conversations.
     */
    suspend fun read(files: List<ExportFile>, onBytes: (Long) -> Unit = {}, onEntry: suspend (ParsedEntry) -> Unit): ReadSummary {
        var base = 0L
        var conversationFiles = 0
        var truncated = false
        for (file in files) {
            val counted = try {
                CountingStream(file.open()) { n -> onBytes(base + n) }
            } catch (e: Exception) {
                throw ExportException(ExportProblem.Unreadable, e)
            }
            counted.use { raw ->
                val input = BufferedInputStream(raw, BUFFER)
                val result = when (sniff(input)) {
                    Kind.ZIP -> readZip(ZipInputStream(input), depth = 0, onEntry)
                    Kind.JSON -> readJson(input, onEntry).let { FileResult(1, it) }
                    Kind.OTHER -> throw ExportException(ExportProblem.NotAnExport)
                }
                conversationFiles += result.files
                truncated = truncated || result.truncated
                base += raw.count
            }
        }
        if (conversationFiles == 0) throw ExportException(ExportProblem.NoConversations)
        return ReadSummary(conversationFiles, truncated)
    }

    private class FileResult(val files: Int, val truncated: Boolean)

    private suspend fun readZip(zip: ZipInputStream, depth: Int, onEntry: suspend (ParsedEntry) -> Unit): FileResult {
        var files = 0
        var truncated = false
        while (true) {
            val entry = try {
                zip.nextEntry
            } catch (e: ZipException) {
                // A damaged zip: keep what came before, or say it can't be read when nothing did.
                if (files == 0) throw ExportException(ExportProblem.Unreadable, e)
                return FileResult(files, true)
            } catch (e: IOException) {
                if (files == 0) throw ExportException(ExportProblem.Unreadable, e)
                return FileResult(files, true)
            } ?: break
            if (entry.isDirectory) continue
            val name = entry.name.substringAfterLast('/').lowercase()
            when {
                CONVERSATIONS.matches(name) -> {
                    files++
                    if (readJson(BufferedInputStream(KeepOpen(zip), BUFFER), onEntry)) truncated = true
                }
                depth == 0 && name.endsWith(".zip") && "conversation" in name -> {
                    val inner = readZip(ZipInputStream(BufferedInputStream(KeepOpen(zip), BUFFER)), depth + 1, onEntry)
                    files += inner.files
                    truncated = truncated || inner.truncated
                }
            }
        }
        if (files == 0 && depth == 0) throw ExportException(ExportProblem.NoConversations)
        return FileResult(files, truncated)
    }

    /** Reads one conversations file; true when it ended early (damaged or cut off) after at least one conversation. */
    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun readJson(input: InputStream, onEntry: suspend (ParsedEntry) -> Unit): Boolean {
        if (!startsArray(input)) throw ExportException(ExportProblem.NotAnExport)
        var read = 0
        try {
            val elements = json.decodeToSequence(input, JsonElement.serializer(), DecodeSequenceMode.ARRAY_WRAPPED).iterator()
            while (true) {
                currentCoroutineContext().ensureActive()
                if (!elements.hasNext()) break
                val element = elements.next()
                read++
                onEntry(parse(element))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: ExportException) {
            throw e
        } catch (e: Exception) {
            // SerializationException, IllegalArgumentException or IOException from a damaged or cut-off file.
            if (read == 0) throw ExportException(ExportProblem.Unreadable, e)
            return true
        }
        return false
    }

    /** One element of a conversations list, recognised by its shape. Never throws. */
    fun parse(element: JsonElement): ParsedEntry {
        val obj = element as? JsonObject ?: return ParsedEntry.Unreadable("not an object")
        return try {
            when {
                ChatGptExport.isConversation(obj) -> ChatGptExport.parse(obj)
                ClaudeExport.isConversation(obj) -> ClaudeExport.parse(obj, attachedLabel)
                else -> ParsedEntry.Unreadable("unknown shape")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ParsedEntry.Unreadable(e.javaClass.simpleName)
        }
    }

    private enum class Kind { ZIP, JSON, OTHER }

    private fun sniff(input: BufferedInputStream): Kind {
        input.mark(8)
        val head = ByteArray(4)
        val n = input.read(head)
        input.reset()
        if (n == 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() && head[2] == 3.toByte() && head[3] == 4.toByte()) return Kind.ZIP
        if (n <= 0) return Kind.OTHER
        return if (startsArray(input)) Kind.JSON else Kind.OTHER
    }

    /** Whether [input] starts (after a byte-order mark and whitespace) with `[`; the stream is left where it was. */
    private fun startsArray(input: InputStream): Boolean {
        val buffered = input as? BufferedInputStream ?: return true
        buffered.mark(PEEK)
        try {
            var i = 0
            while (i < PEEK) {
                val b = buffered.read()
                if (b < 0) return false
                i++
                if (b == 0xEF || b == 0xBB || b == 0xBF) continue
                if (b == ' '.code || b == '\n'.code || b == '\r'.code || b == '\t'.code) continue
                return b == '['.code
            }
            return false
        } finally {
            buffered.reset()
        }
    }

    /** A zip entry's stream that leaves the zip open when the JSON reader is done with it. */
    private class KeepOpen(input: InputStream) : FilterInputStream(input) {
        override fun close() = Unit
    }

    /** Counts bytes as they're read, for progress, and for tests that the file is read as a stream. */
    private class CountingStream(input: InputStream, private val onCount: (Long) -> Unit) : FilterInputStream(input) {
        var count = 0L
            private set
        private var reported = 0L

        override fun read(): Int = super.read().also { if (it >= 0) add(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) add(it.toLong()) }

        override fun skip(n: Long): Long = super.skip(n).also { add(it) }

        private fun add(n: Long) {
            count += n
            if (count - reported >= REPORT_EVERY) {
                reported = count
                onCount(count)
            }
        }

        override fun close() {
            onCount(count)
            super.close()
        }
    }

    private companion object {
        val CONVERSATIONS = Regex("conversations(-\\d+)?\\.json")
        const val BUFFER = 64 * 1024
        const val PEEK = 4096
        const val REPORT_EVERY = 256 * 1024L
    }
}
