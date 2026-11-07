package dev.locus.core.data.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.connectbot.sshlib.SftpClient
import org.connectbot.sshlib.SftpFileHandle
import org.connectbot.sshlib.SftpResult
import org.connectbot.sshlib.SftpStatusCode
import java.io.InputStream

/**
 * Moves a file's bytes over SFTP with many requests on the wire at once, as OpenSSH's `sftp` does
 * (64 requests of 32 KB). Waiting for each reply before sending the next request caps a transfer at
 * one chunk per round trip: 32 KB every 100 ms is about 300 KB/s, whatever the link's real speed.
 * sshlib matches replies to requests by id and its SFTP channel has an 8 MB window, so up to
 * [IN_FLIGHT] requests can be outstanding.
 */
internal object SftpTransfer {
    /** Bytes per request: the size every SFTP server must accept (draft-ietf-secsh-filexfer-02, §3). */
    const val CHUNK = 32 * 1024

    /** Requests outstanding at once: 2 MB in flight, OpenSSH's default. */
    const val IN_FLIGHT = 64

    /** Small requests (stat, mkdir) sent at once when there are many, e.g. making a folder tree. */
    const val PARALLEL_REQUESTS = 8

    /**
     * Runs [block] on every item, [parallel] at a time. The first failure cancels the rest and is
     * thrown; files over one SFTP session share its connection, each with its own requests in flight.
     */
    suspend fun <T> forEachConcurrent(items: List<T>, parallel: Int, block: suspend (T) -> Unit) = coroutineScope {
        val next = java.util.concurrent.atomic.AtomicInteger()
        repeat(minOf(parallel, items.size)) {
            launch {
                while (true) {
                    val i = next.getAndIncrement()
                    if (i >= items.size) break
                    block(items[i])
                }
            }
        }
    }

    /**
     * Writes everything [input] yields to [handle] from offset 0, keeping up to [IN_FLIGHT] writes
     * outstanding. Returns the bytes written. The first failed write is thrown via [fail]; writes
     * still on the wire are cancelled.
     */
    suspend fun write(sftp: SftpClient, handle: SftpFileHandle, input: InputStream, fail: (SftpResult<*>) -> Throwable): Long = coroutineScope {
        val inFlight = ArrayDeque<Deferred<SftpResult<Unit>>>()
        var offset = 0L
        try {
            while (true) {
                val buffer = ByteArray(CHUNK)
                val n = readFully(input, buffer)
                if (n <= 0) break
                val data = if (n == CHUNK) buffer else buffer.copyOf(n)
                if (inFlight.size >= IN_FLIGHT) inFlight.removeFirst().await().orThrow(fail)
                val at = offset
                inFlight += async { sftp.write(handle, at, data) }
                offset += n
                if (n < CHUNK) break
            }
            while (inFlight.isNotEmpty()) inFlight.removeFirst().await().orThrow(fail)
        } catch (e: Throwable) {
            inFlight.forEach { it.cancel() }
            throw e
        }
        offset
    }

    /**
     * Reads [handle] from [start] into [sink], in order, at most [maxBytes]; returns the bytes read.
     * [sizeHint] (the file's size when it was opened, 0 when unknown) bounds the read-ahead: past it,
     * one request at a time, so a file that is still growing is read to its end without asking for
     * 64 chunks of nothing, and a file that reports no size (`/proc`) is read one chunk at a time.
     * A short read (allowed by the protocol) is completed before the next chunk is delivered.
     */
    suspend fun read(
        sftp: SftpClient,
        handle: SftpFileHandle,
        start: Long,
        maxBytes: Long,
        sizeHint: Long,
        fail: (SftpResult<*>) -> Throwable,
        sink: (ByteArray) -> Unit,
    ): Long = coroutineScope {
        val end = start + maxBytes
        val sizeEnd = start + maxOf(0L, sizeHint - start)
        val queue = ArrayDeque<Pair<Long, Deferred<SftpResult<ByteArray?>>>>()
        var next = start
        var delivered = 0L
        try {
            while (true) {
                while (next < end && queue.size < IN_FLIGHT && (next < sizeEnd || queue.isEmpty())) {
                    queue += issue(sftp, handle, next, minOf(CHUNK.toLong(), end - next).toInt())
                    next += CHUNK
                }
                val (offset, reply) = queue.removeFirstOrNull() ?: break
                val wanted = minOf(CHUNK.toLong(), end - offset).toInt()
                var chunk = reply.await().dataOrThrow(fail) ?: break
                var eof = false
                // A short read: ask for the rest of this chunk before anything after it is delivered.
                while (chunk.size < wanted) {
                    val rest = sftp.read(handle, offset + chunk.size, wanted - chunk.size).dataOrThrow(fail)
                    if (rest == null || rest.isEmpty()) {
                        eof = true
                        break
                    }
                    chunk += rest
                }
                if (chunk.isNotEmpty()) {
                    sink(chunk)
                    delivered += chunk.size
                }
                if (eof || chunk.isEmpty()) break
            }
        } finally {
            queue.forEach { it.second.cancel() }
        }
        delivered
    }

    private fun CoroutineScope.issue(sftp: SftpClient, handle: SftpFileHandle, offset: Long, length: Int) =
        offset to async { sftp.read(handle, offset, length) }

    /** Fills [buffer] unless the stream ends first; returns the bytes read, -1 at the end. */
    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val n = input.read(buffer, total, buffer.size - total)
            if (n < 0) return if (total == 0) -1 else total
            total += n
        }
        return total
    }

    private fun <T> SftpResult<T>.orThrow(fail: (SftpResult<*>) -> Throwable): T = when (this) {
        is SftpResult.Success -> value
        else -> throw fail(this)
    }

    /** The data, or null at the end of the file. */
    private fun SftpResult<ByteArray?>.dataOrThrow(fail: (SftpResult<*>) -> Throwable): ByteArray? = when (this) {
        is SftpResult.Success -> value
        is SftpResult.ServerError -> if (statusCode == SftpStatusCode.EOF) null else throw fail(this)
        else -> throw fail(this)
    }
}
