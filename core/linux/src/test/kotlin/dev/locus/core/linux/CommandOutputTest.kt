package dev.locus.core.linux

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Command output and service logs read as UTF-8 however the bytes arrive (#311). */
class CommandOutputTest {
    @get:Rule val tmp = TemporaryFolder()

    // Spanish, Portuguese and Chinese (app languages), an emoji (a surrogate pair) and a 4-byte CJK character.
    private val text = "Configuración: ação 设置完成 😀 𠜎 ok\n"
    private val bytes = text.toByteArray(Charsets.UTF_8)

    /** Hands out the bytes in reads of the given sizes, then the rest. */
    private class Chunked(private val data: ByteArray, private val sizes: List<Int>) : InputStream() {
        private var pos = 0
        private var call = 0
        override fun read(): Int = if (pos < data.size) data[pos++].toInt() and 0xFF else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= data.size) return -1
            val want = sizes.getOrNull(call++) ?: (data.size - pos)
            val n = minOf(len, want.coerceAtLeast(1), data.size - pos)
            System.arraycopy(data, pos, b, off, n)
            pos += n
            return n
        }
    }

    @Test
    fun `text split at every byte offset comes out whole`() {
        for (i in 0..bytes.size) {
            for (j in i..bytes.size) {
                val out = BoundedBuffer(10_000)
                CommandOutput.pump(Chunked(bytes, listOf(i, j - i)), out)
                assertEquals(text, out.text(), "reads of $i, ${j - i} and the rest")
            }
        }
    }

    @Test
    fun `one byte per read comes out whole, and the saved copy is the raw bytes`() {
        val out = BoundedBuffer(10_000)
        val keep = tmp.newFile("out.log")
        assertFalse(CommandOutput.pumpAndKeep(Chunked(bytes, List(bytes.size) { 1 }), out, keep, Long.MAX_VALUE))
        assertEquals(text, out.text())
        assertTrue(bytes.contentEquals(keep.readBytes()))
    }

    @Test
    fun `an unfinished character at the end is one replacement character`() {
        val out = BoundedBuffer(100)
        CommandOutput.pump(ByteArrayInputStream("ok".toByteArray() + byteArrayOf(0xE8.toByte(), 0xAE.toByte())), out)
        assertEquals("ok�", out.text())
    }

    @Test
    fun `the head and tail are never cut inside an emoji`() {
        val emoji = "😀".repeat(50) // 100 UTF-16 chars
        for (max in 2..30) {
            val out = BoundedBuffer(max)
            out.append("a")
            out.append(emoji)
            out.append("😀b")
            val shown = out.text()
            assertTrue(out.truncated)
            shown.forEachIndexed { i, c ->
                if (c.isHighSurrogate()) assertTrue(shown.getOrNull(i + 1)?.isLowSurrogate() == true, "max $max: '$shown'")
                if (c.isLowSurrogate()) assertTrue(shown.getOrNull(i - 1)?.isHighSurrogate() == true, "max $max: '$shown'")
            }
            assertTrue(shown.startsWith("a"), "the head comes first, max $max")
            assertTrue(shown.endsWith("b"), "max $max")
        }
    }

    @Test
    fun `a log's tail is whole characters and as long as asked`() {
        val log = tmp.newFile("svc.log")
        val line = "服务器已启动 😀\n" // 10 UTF-16 chars, 24 bytes
        log.writeText(line.repeat(500))
        for (max in listOf(1, 7, 10, 99, 400)) {
            val tail = CommandOutput.tail(log, max)
            assertFalse('�' in tail, "max $max: '$tail'")
            assertFalse(tail.first().isLowSurrogate(), "max $max")
            assertEquals(max, tail.length, "max $max")
            assertTrue(line.repeat(500).endsWith(tail))
        }
        assertEquals(line.repeat(500), CommandOutput.tail(log, 100_000))
    }

    /**
     * proot after its SIGKILL, with a program it traced still holding the output pipe: the process has
     * exited but a read blocks until the pipe is closed (closing it wakes the read, as on Android).
     */
    private class HeldPipeProcess : Process() {
        @Volatile private var exited = false
        val closed = CountDownLatch(1)
        private val pipe = object : InputStream() {
            override fun read(): Int = read(ByteArray(1), 0, 1)
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                closed.await()
                throw IOException("Stream closed")
            }
            override fun close() = closed.countDown()
        }
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = pipe
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int {
            while (!exited) Thread.sleep(10)
            return 137
        }
        override fun exitValue(): Int = if (exited) 137 else throw IllegalThreadStateException()
        override fun destroy() {
            exited = true
        }
    }

    @Test
    fun `a stopped command whose pipe stays open returns within its timeout and grace (#312)`() = runBlocking {
        val process = HeldPipeProcess()
        val started = System.nanoTime()
        val ended = withTimeout(10_000) {
            CommandOutput.awaitEnd(process, timeoutMs = 300, graceMs = 500, stop = { it.destroy() }) { input -> input.read(ByteArray(16)) }
        }
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertFalse(ended.exited)
        assertNull(ended.read, "the reader was cut off")
        assertTrue(tookMs < 300 + 500 + 1_500, "took $tookMs ms")
        assertTrue(process.closed.await(5, TimeUnit.SECONDS), "the pipe is closed, so the reader's thread ends too")
    }
}
