package dev.locus.core.data.chatimport

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Hand-written fixtures (src/test/resources/chatimport) and builders for the import tests. */
internal object ExportFixtures {
    fun bytes(name: String): ByteArray = requireNotNull(ExportFixtures::class.java.getResourceAsStream("/chatimport/$name")) { name }.use { it.readBytes() }

    fun file(name: String, bytes: ByteArray) = ExportFile(name, bytes.size.toLong()) { bytes.inputStream() }

    fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    suspend fun readAll(vararg files: ExportFile): Pair<List<ParsedEntry>, ReadSummary> {
        val entries = ArrayList<ParsedEntry>()
        val summary = ExportReader().read(files.toList()) { entries += it }
        return entries to summary
    }

    /** A ChatGPT conversation with [turns] user/assistant pairs, as JSON text. */
    fun chatGpt(id: String, createdSeconds: Long, turns: Int = 1, text: String = "hello", title: String = "Chat $id", updatedSeconds: Long = createdSeconds + 60): String {
        val nodes = StringBuilder()
        var parent = "null"
        for (i in 0 until turns * 2) {
            val node = "n$i"
            val role = if (i % 2 == 0) "user" else "assistant"
            if (nodes.isNotEmpty()) nodes.append(',')
            nodes.append(
                """"$node":{"id":"$node","parent":$parent,"children":[],"message":{"id":"$node","author":{"role":"$role"},""" +
                    """"create_time":${createdSeconds + i},"content":{"content_type":"text","parts":[${quote("$text $i")}]},"recipient":"all"}}""",
            )
            parent = "\"$node\""
        }
        return """{"title":${quote(title)},"create_time":$createdSeconds,"update_time":$updatedSeconds,"id":"$id","conversation_id":"$id","current_node":"n${turns * 2 - 1}","mapping":{$nodes}}"""
    }

    fun quote(s: String) = buildString {
        append('"')
        s.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                else -> append(c)
            }
        }
        append('"')
    }

    /**
     * A ChatGPT conversations file of [count] conversations, produced as it's read and never held
     * whole: the streaming tests read hundreds of megabytes' worth through it with a small heap.
     */
    class GeneratedExport(private val count: Int, private val textPerChat: Int) : InputStream() {
        private var index = -1
        private var chunk: ByteArray = "[".toByteArray()
        private var pos = 0
        var produced = 0L
            private set

        private fun next(): Boolean {
            index++
            chunk = when {
                index < count -> ((if (index > 0) "," else "") + chatGpt("gen-$index", 1_600_000_000L + index * 3600L, text = "x".repeat(textPerChat))).toByteArray()
                index == count -> "]".toByteArray()
                else -> return false
            }
            pos = 0
            return true
        }

        override fun read(): Int {
            while (pos >= chunk.size) if (!next()) return -1
            produced++
            return chunk[pos++].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pos >= chunk.size) if (!next()) return -1
            val n = minOf(len, chunk.size - pos)
            System.arraycopy(chunk, pos, b, off, n)
            pos += n
            produced += n
            return n
        }
    }
}
