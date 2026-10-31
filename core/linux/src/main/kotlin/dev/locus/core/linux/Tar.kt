package dev.locus.core.linux

import android.system.Os
import java.io.File
import java.io.InputStream

/**
 * Minimal ustar/GNU tar extractor: enough for distro rootfs archives. Keeps permissions and
 * symlinks; turns hard links into copies (Android forbids hard links in app storage).
 */
internal object Tar {
    fun extract(input: InputStream, dest: File) {
        val header = ByteArray(512)
        val root = dest.canonicalFile
        var longName: String? = null
        var longLink: String? = null
        val hardLinks = mutableListOf<Pair<File, String>>()
        while (true) {
            if (!readFully(input, header)) break
            if (header.all { it == 0.toByte() }) break
            val size = octal(header, 124, 12)
            val type = header[156].toInt().toChar()
            var name = longName ?: (string(header, 345, 155).let { if (it.isNotEmpty()) "$it/" else "" } + string(header, 0, 100))
            val link = longLink ?: string(header, 157, 100)
            longName = null
            longLink = null
            val mode = octal(header, 100, 8).toInt()

            if (type == 'L' || type == 'K') {
                val data = String(readData(input, size), Charsets.UTF_8).trimEnd('\u0000')
                if (type == 'L') longName = data else longLink = data
                continue
            }
            name = name.removePrefix("./")
            // Entries that would land outside the destination are skipped, not written.
            val target = File(root, name).canonicalFile.takeIf { it == root || it.path.startsWith(root.path + File.separator) }
            when (type) {
                '0', '\u0000', '7' -> {
                    if (target == null) {
                        skip(input, size)
                    } else {
                        target.parentFile?.mkdirs()
                        target.delete()
                        target.outputStream().use { out -> copy(input, out, size) }
                        skipPadding(input, size)
                        runCatching { Os.chmod(target.path, mode and 0x1ff) }
                    }
                }
                '5' -> {
                    target?.mkdirs()
                    target?.let { runCatching { Os.chmod(it.path, mode and 0x1ff) } }
                    skip(input, size)
                }
                '2' -> {
                    target?.let {
                        it.parentFile?.mkdirs()
                        it.delete()
                        runCatching { Os.symlink(link, it.path) }
                    }
                    skip(input, size)
                }
                '1' -> {
                    target?.let { hardLinks += it to link.removePrefix("./") }
                    skip(input, size)
                }
                else -> skip(input, size)
            }
        }
        hardLinks.forEach { (file, linkName) ->
            val source = File(root, linkName)
            if (source.isFile) {
                file.parentFile?.mkdirs()
                source.copyTo(file, overwrite = true)
                runCatching { Os.chmod(file.path, Os.stat(source.path).st_mode and 0x1ff) }
            }
        }
    }

    private fun string(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end] != 0.toByte()) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    private fun octal(b: ByteArray, off: Int, len: Int): Long {
        val s = string(b, off, len).trim()
        return if (s.isEmpty()) 0 else s.toLong(8)
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var read = 0
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n < 0) return false
            read += n
        }
        return true
    }

    private fun readData(input: InputStream, size: Long): ByteArray {
        val data = ByteArray(size.toInt())
        readFully(input, data)
        skipPadding(input, size)
        return data
    }

    private fun copy(input: InputStream, out: java.io.OutputStream, size: Long) {
        val buf = ByteArray(64 * 1024)
        var left = size
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) error("Truncated archive")
            out.write(buf, 0, n)
            left -= n
        }
    }

    private fun skip(input: InputStream, size: Long) {
        if (size <= 0) return
        copy(input, Discard, size)
        skipPadding(input, size)
    }

    /** OutputStream.nullOutputStream() needs API 33; minSdk is 26. */
    private object Discard : java.io.OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    }

    private fun skipPadding(input: InputStream, size: Long) {
        val pad = ((512 - size % 512) % 512).toInt()
        if (pad > 0) readFully(input, ByteArray(pad))
    }
}
