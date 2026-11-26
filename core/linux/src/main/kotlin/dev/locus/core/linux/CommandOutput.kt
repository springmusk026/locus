package dev.locus.core.linux

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * Reading a command's output and a service's log as text. A UTF-8 character can be cut between two
 * reads (8 KiB each), and a log is read from an arbitrary byte near its end: bytes are decoded as a
 * stream, so a character split that way comes out whole, and nothing is ever cut between the two
 * halves of a surrogate pair (an emoji).
 */
internal object CommandOutput {

    /** How [awaitEnd] left a command: whether it exited by itself, and what [awaitEnd]'s reader returned (null: cut off). */
    class Ended(val exited: Boolean, val read: Any?)

    /**
     * Reads [process]'s output with [read] while waiting up to [timeoutMs] for it to exit, else
     * [stop]s it; then waits at most [graceMs] more for the rest of the output. A program left in the
     * background (`server &`), or one proot couldn't end, keeps the pipe open after the command ended
     * or was stopped; a read blocked on it must not hold the result back, so the reader isn't a child
     * of the caller and is given up after the grace ([stopReading]).
     */
    suspend fun awaitEnd(
        process: java.lang.Process,
        timeoutMs: Long,
        graceMs: Long,
        stop: suspend (java.lang.Process) -> Unit,
        read: (InputStream) -> Any,
    ): Ended {
        val reader = CoroutineScope(Dispatchers.IO).async { read(process.inputStream) }
        val exited = try {
            withTimeoutOrNull(timeoutMs) {
                while (process.isAliveCompat()) delay(100)
                true
            } ?: false
        } catch (e: CancellationException) {
            // Cancelled (the user cancelled a package install, say): stop the process rather than
            // leave it running, and stop reading its output.
            withContext(NonCancellable) {
                stop(process)
                stopReading(process, reader)
            }
            throw e
        }
        if (!exited) stop(process)
        // The rest of the output, for a moment: whatever still holds the pipe after that is cut off.
        val result = withTimeoutOrNull(graceMs) { reader.await() }
        if (result == null) stopReading(process, reader)
        return Ended(exited, result)
    }

    /**
     * Gives up on [reader]: closing the pipe wakes a read blocked on it on Android (on a JVM the read
     * may stay blocked until the program holding the pipe exits; the reader then ends on its own).
     * Closed on another thread, so a close that waits for the read can't hold up the caller.
     */
    private fun stopReading(process: java.lang.Process, reader: Job) {
        reader.cancel()
        Thread({ runCatching { process.inputStream.close() } }, "locus-output-close").apply { isDaemon = true }.start()
    }

    /** Reads [input] to its end into [into]. */
    fun pump(input: InputStream, into: BoundedBuffer) {
        val buf = ByteArray(8 * 1024)
        val text = Utf8Stream()
        input.use {
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                into.append(text.decode(buf, n))
            }
        }
        into.append(text.finish())
    }

    /** [pump], also keeping the raw bytes in [keep] up to [maxSaved]. True when that cap cut it short. */
    fun pumpAndKeep(input: InputStream, into: BoundedBuffer, keep: File, maxSaved: Long): Boolean {
        val buf = ByteArray(8 * 1024)
        val text = Utf8Stream()
        var kept = 0L
        var capped = false
        keep.outputStream().buffered().use { out ->
            input.use {
                while (true) {
                    val n = it.read(buf)
                    if (n < 0) break
                    into.append(text.decode(buf, n))
                    if (kept + n <= maxSaved) {
                        out.write(buf, 0, n)
                        kept += n
                    } else {
                        capped = true
                    }
                }
            }
        }
        into.append(text.finish())
        return capped
    }

    /**
     * The last [maxChars] characters of [file] (fewer only when the file is shorter), read from its
     * end so a large log isn't loaded whole. A UTF-8 character takes at most 4 bytes per 2 UTF-16
     * chars, 3 bytes per char otherwise, so 4 bytes per char always reach far enough back; the read
     * starts after any continuation bytes, never inside a character.
     */
    fun tail(file: File, maxChars: Int): String = java.io.RandomAccessFile(file, "r").use { raf ->
        val length = raf.length()
        val start = (length - maxChars.toLong() * 4).coerceAtLeast(0)
        val bytes = ByteArray((length - start).toInt())
        raf.seek(start)
        raf.readFully(bytes)
        var from = 0
        if (start > 0) while (from < bytes.size && (bytes[from].toInt() and 0xC0) == 0x80) from++
        val text = String(bytes, from, bytes.size - from, Charsets.UTF_8)
        if (maxChars <= 0) return@use ""
        if (text.length <= maxChars) return@use text
        var cut = text.length - maxChars
        if (text[cut].isLowSurrogate()) cut++ // never start with the second half of a pair
        text.substring(cut)
    }
}

/**
 * UTF-8 decoded across reads: bytes of a character that isn't complete yet are kept for the next
 * call. Malformed input becomes U+FFFD, as `String(bytes, UTF_8)` does.
 */
internal class Utf8Stream {
    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var leftover = ByteArray(0)

    fun decode(bytes: ByteArray, count: Int): String {
        val input = ByteBuffer.allocate(leftover.size + count).put(leftover).put(bytes, 0, count)
        input.flip()
        val out = CharBuffer.allocate(input.remaining() + 1)
        decoder.decode(input, out, false)
        leftover = ByteArray(input.remaining()).also { input.get(it) }
        out.flip()
        return out.toString()
    }

    /** What's left at the end of the stream: an unfinished character becomes U+FFFD. */
    fun finish(): String {
        val input = ByteBuffer.wrap(leftover)
        leftover = ByteArray(0)
        val out = CharBuffer.allocate(input.remaining() + 2)
        decoder.decode(input, out, true)
        decoder.flush(out)
        decoder.reset()
        out.flip()
        return out.toString()
    }
}

/** Keeps the first and last parts of long output, which is where errors and results usually are. */
internal class BoundedBuffer(private val max: Int) {
    private val head = StringBuilder()
    private val tail = StringBuilder()
    private var headFull = false
    var truncated = false
        private set

    @Synchronized
    fun append(s: String) {
        if (s.isEmpty()) return
        if (headFull) {
            appendTail(s)
            return
        }
        var take = minOf(s.length, max / 2 - head.length)
        if (take in 1 until s.length && s[take - 1].isHighSurrogate()) take-- // keep a pair together, in the tail
        head.append(s, 0, take)
        if (take < s.length) {
            headFull = true
            appendTail(s.substring(take))
        }
    }

    private fun appendTail(s: String) {
        tail.append(s)
        if (tail.length > max / 2) {
            truncated = true
            var cut = tail.length - max / 2
            if (cut < tail.length && tail[cut].isLowSurrogate()) cut++
            tail.delete(0, cut)
        }
    }

    @Synchronized
    fun text(): String = if (truncated) "$head\n…[output truncated]…\n$tail" else "$head$tail"
}

/** Whether the process still runs: exitValue() throws until it has exited. */
internal fun java.lang.Process.isAliveCompat(): Boolean = runCatching { exitValue(); false }.getOrDefault(true)
