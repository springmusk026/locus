package dev.locus.core.data.remote

import dev.locus.core.agent.ToolException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.connectbot.sshlib.SftpAttributes
import org.connectbot.sshlib.SftpClient
import org.connectbot.sshlib.SftpDirectoryEntry
import org.connectbot.sshlib.SftpFileHandle
import org.connectbot.sshlib.SftpOpenFlag
import org.connectbot.sshlib.SftpResult
import org.connectbot.sshlib.SftpStatusCode
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [SftpTransfer] keeps many requests on the wire: over a link with a 100 ms round trip, a 4 MB file
 * takes a handful of round trips rather than one per 32 KB chunk (128 of them, 12.8 s). Virtual time:
 * every request to the fake server costs [RTT].
 */
class SftpTransferTest {
    /** An SFTP server in memory whose every request takes [RTT]; [shortReads] returns half of what's asked. */
    private class SlowSftp(var content: ByteArray = ByteArray(0), val shortReads: Boolean = false, val failWriteAt: Long = -1) : SftpClient {
        val written = ByteArrayOutputStream()
        private val writes = sortedMapOf<Long, ByteArray>()
        val inFlight = AtomicInteger()
        var maxInFlight = 0

        private suspend fun <T> trip(block: () -> SftpResult<T>): SftpResult<T> {
            maxInFlight = maxOf(maxInFlight, inFlight.incrementAndGet())
            try {
                delay(RTT)
                return block()
            } finally {
                inFlight.decrementAndGet()
            }
        }

        fun result(): ByteArray {
            val out = ByteArray(writes.entries.maxOfOrNull { it.key + it.value.size }?.toInt() ?: 0)
            writes.forEach { (at, data) -> data.copyInto(out, at.toInt()) }
            return out
        }

        private val handle = SftpFileHandle::class.java.getConstructor(ByteArray::class.java).newInstance("h".toByteArray())

        override val protocolVersion = 3
        override val extensions: Set<String> = emptySet()
        override val isOpen = true
        override suspend fun open(path: String, flags: Set<SftpOpenFlag>, attrs: SftpAttributes) = trip { SftpResult.Success(handle) }
        override suspend fun close(handle: SftpFileHandle) = trip { SftpResult.Success(Unit) }
        override suspend fun read(handle: SftpFileHandle, offset: Long, length: Int): SftpResult<ByteArray?> = trip {
            if (offset >= content.size) {
                SftpResult.ServerError(SftpStatusCode.EOF, "eof")
            } else {
                val n = minOf(if (shortReads) maxOf(1, length / 2) else length, content.size - offset.toInt())
                SftpResult.Success(content.copyOfRange(offset.toInt(), offset.toInt() + n))
            }
        }
        override suspend fun write(handle: SftpFileHandle, offset: Long, data: ByteArray) = trip {
            if (failWriteAt in offset until offset + data.size) {
                SftpResult.ServerError(SftpStatusCode.FAILURE, "disk full")
            } else {
                synchronized(writes) { writes[offset] = data }
                SftpResult.Success(Unit)
            }
        }
        override suspend fun stat(path: String) = lstat(path)
        override suspend fun lstat(path: String): SftpResult<SftpAttributes> = trip { SftpResult.Success(SftpAttributes(content.size.toLong(), null, null, 0x81a4, null, 1)) }
        override suspend fun fstat(handle: SftpFileHandle): SftpResult<SftpAttributes> = lstat("")
        override suspend fun setstat(path: String, attrs: SftpAttributes) = trip { SftpResult.Success(Unit) }
        override suspend fun fsetstat(handle: SftpFileHandle, attrs: SftpAttributes) = trip { SftpResult.Success(Unit) }
        override suspend fun opendir(path: String): SftpResult<SftpFileHandle> = SftpResult.ServerError(SftpStatusCode.FAILURE, "no")
        override suspend fun readdir(handle: SftpFileHandle): SftpResult<List<SftpDirectoryEntry>> = SftpResult.ServerError(SftpStatusCode.FAILURE, "no")
        override suspend fun mkdir(path: String, attrs: SftpAttributes) = trip { SftpResult.Success(Unit) }
        override suspend fun rmdir(path: String) = trip { SftpResult.Success(Unit) }
        override suspend fun remove(path: String) = trip { SftpResult.Success(Unit) }
        override suspend fun rename(oldPath: String, newPath: String) = trip { SftpResult.Success(Unit) }
        override suspend fun copyData(readHandle: SftpFileHandle, readOffset: Long, length: Long, writeHandle: SftpFileHandle, writeOffset: Long, extra: Long): SftpResult<Unit> =
            SftpResult.ServerError(SftpStatusCode.FAILURE, "no")
        override suspend fun realpath(path: String): SftpResult<String> = SftpResult.Success(path)
        override suspend fun readlink(path: String): SftpResult<String> = SftpResult.ServerError(SftpStatusCode.FAILURE, "no")
        override suspend fun symlink(linkPath: String, targetPath: String): SftpResult<Unit> = SftpResult.ServerError(SftpStatusCode.FAILURE, "no")
        override fun close() = Unit
    }

    private val fourMb = Random(1).nextBytes(4 * 1024 * 1024 + 777)

    @Test
    fun `a download keeps many reads in flight and arrives intact`() = runTest {
        val server = SlowSftp(fourMb)
        val out = ByteArrayOutputStream()
        val started = currentTime
        val n = SftpFs(server).download("/f", out, Long.MAX_VALUE)
        val took = currentTime - started
        assertEquals(fourMb.size.toLong(), n)
        assertContentEquals(fourMb, out.toByteArray())
        assertEquals(SftpTransfer.IN_FLIGHT, server.maxInFlight)
        // open + fstat + 129 chunks 64 at a time + close: about 6 round trips, not 131.
        assertTrue(took <= 10 * RTT, "took $took ms")
    }

    @Test
    fun `an upload keeps many writes in flight and arrives intact`() = runTest {
        val server = SlowSftp()
        val started = currentTime
        SftpFs(server).upload("/dir/f", { ByteArrayInputStream(fourMb) }, executable = false)
        val took = currentTime - started
        assertContentEquals(fourMb, server.result())
        assertEquals(SftpTransfer.IN_FLIGHT, server.maxInFlight)
        assertTrue(took <= 12 * RTT, "took $took ms")
    }

    @Test
    fun `short reads are completed in order`() = runTest {
        val server = SlowSftp(fourMb, shortReads = true)
        val out = ByteArrayOutputStream()
        SftpFs(server).download("/f", out, Long.MAX_VALUE)
        assertContentEquals(fourMb, out.toByteArray())
    }

    @Test
    fun `a file without a size (proc) is read one chunk at a time to its end`() = runTest {
        val data = Random(2).nextBytes(100_000)
        val server = object : SftpClient by SlowSftp(data) {
            override suspend fun fstat(handle: SftpFileHandle): SftpResult<SftpAttributes> = SftpResult.Success(SftpAttributes(0, null, null, 0x81a4, null, 1))
        }
        val out = ByteArrayOutputStream()
        assertEquals(data.size.toLong(), SftpFs(server).download("/proc/x", out, Long.MAX_VALUE))
        assertContentEquals(data, out.toByteArray())
    }

    @Test
    fun `a download stops at the limit`() = runTest {
        val out = ByteArrayOutputStream()
        assertEquals(100_001L, SftpFs(SlowSftp(fourMb)).download("/f", out, 100_001))
        assertContentEquals(fourMb.copyOf(100_001), out.toByteArray())
    }

    @Test
    fun `a log is read from an offset`() = runTest {
        val bytes = SftpFs(SlowSftp(fourMb)).readFrom("/log", 1_000_000, 70_000)
        assertContentEquals(fourMb.copyOfRange(1_000_000, 1_070_000), bytes)
    }

    @Test
    fun `a failed write fails the upload`() = runTest {
        val server = SlowSftp(failWriteAt = 2_000_000)
        assertFailsWith<ToolException> { SftpFs(server).upload("/dir/f", { ByteArrayInputStream(fourMb) }, executable = false) }
    }

    @Test
    fun `files run a few at a time`() = runTest {
        val running = AtomicInteger()
        var most = 0
        SftpTransfer.forEachConcurrent((1..20).toList(), 4) {
            most = maxOf(most, running.incrementAndGet())
            delay(RTT)
            running.decrementAndGet()
        }
        assertEquals(4, most)
        assertEquals(5 * RTT, currentTime)
    }

    private companion object {
        const val RTT = 100L
    }
}
