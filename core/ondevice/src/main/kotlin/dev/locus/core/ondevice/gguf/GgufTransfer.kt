package dev.locus.core.ondevice.gguf

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * One file download into a `.part` file: resumed with an HTTP Range request when part of it is
 * already there, checked against the size and SHA-256 the Hub publishes when they're known.
 *
 * The `.part` file is opened with `O_NOFOLLOW` (`LinkOption.NOFOLLOW_LINKS`, as [dev.locus.core.model.NoFollowFiles]
 * does on the JVM), and a new one with `O_CREAT|O_EXCL` (`CREATE_NEW`): a link or a file planted at
 * the name is never written through. The caller moves the finished file into place atomically.
 */
class GgufTransfer(private val http: ModelHttp) {

    /** What's being fetched. [sizeBytes] and [sha256] are null for an unverified URL. */
    data class Target(val url: String, val sizeBytes: Long?, val sha256: String?)

    /**
     * Downloads [target] into [part], from where [part] ends. [freeBytes] is the space left on the
     * volume; [onProgress] gets (bytes on disk, total when known). Returns the file's SHA-256.
     */
    suspend fun download(target: Target, part: File, freeBytes: () -> Long, onProgress: suspend (Long, Long?) -> Unit): String {
        val path = part.toPath()
        if (Files.isSymbolicLink(path)) Files.delete(path) // never a link at our own name: start over
        var have = if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) Files.size(path) else 0L
        val expected = target.sizeBytes
        if (expected != null && have > expected) {
            Files.delete(path)
            have = 0
        }
        if (expected == null || have < expected) {
            fetch(target, part, have, freeBytes, onProgress)
        }
        val size = Files.size(path)
        if (expected != null && size != expected) {
            throw DownloadException("The download stopped early (${mb(size)} of ${mb(expected)} MB). Resume to carry on.")
        }
        val actual = sha256(part)
        if (target.sha256 != null && !actual.equals(target.sha256, ignoreCase = true)) {
            Files.deleteIfExists(path)
            throw DownloadException(
                "The file's checksum doesn't match the one Hugging Face publishes, so it was deleted. It may have been damaged on the way; download it again.",
                retryable = true,
            )
        }
        return actual
    }

    private suspend fun fetch(target: Target, part: File, start: Long, freeBytes: () -> Long, onProgress: suspend (Long, Long?) -> Unit) {
        http.open(target.url, from = start) { r -> withContext(Dispatchers.IO) {
            var have = start
            val total: Long?
            when (r.status) {
                206 -> {
                    val range = parseContentRange(r.header("Content-Range"))
                    if (range == null || range.first != start) {
                        // An answer for other bytes than asked: start over rather than splice.
                        Files.deleteIfExists(part.toPath())
                        throw DownloadException("The server resumed at the wrong place. Try again to start over.")
                    }
                    total = range.second
                }
                200 -> {
                    have = 0 // the server ignored the range: the whole file comes again
                    total = r.header("Content-Length")?.toLongOrNull()
                }
                416 -> {
                    Files.deleteIfExists(part.toPath())
                    throw DownloadException("The server couldn't resume this download. Try again to start over.")
                }
                429 -> throw DownloadException(HfError.RateLimited(HfHub.retryAfterSeconds(r.header("RateLimit"), r.header("Retry-After"))).message.orEmpty())
                401, 403 -> throw DownloadException(
                    if (r.header("X-Error-Code") == "GatedRepo") "This model is gated: Hugging Face only lets accounts that accepted its terms download it." else "The server refused the download (HTTP ${r.status}).",
                    retryable = false,
                )
                404, 410 -> throw DownloadException("The file isn't there any more (HTTP ${r.status}).", retryable = false)
                else -> throw DownloadException("The download failed (HTTP ${r.status}).")
            }
            val expected = target.sizeBytes
            if (expected != null && total != null && total != expected) {
                throw DownloadException("The server's file is ${mb(total)} MB, not the ${mb(expected)} MB published for it.", retryable = false)
            }
            val limit = expected ?: total
            val need = (limit ?: 0) - have + STORAGE_MARGIN
            if (freeBytes() < need) {
                throw DownloadException("Not enough free storage: the download needs ${gb(need)} GB more and ${gb(freeBytes())} GB is free.", retryable = false)
            }
            val input = r.body
            openPart(part, have).use { out ->
                val buf = ByteArray(BUFFER)
                var reported = have
                var lastCheck = have
                onProgress(have, limit)
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    if (limit != null && have + n > limit) throw DownloadException("The server sent more than the file's size.", retryable = false)
                    val bb = ByteBuffer.wrap(buf, 0, n)
                    try {
                        while (bb.hasRemaining()) out.write(bb)
                    } catch (e: IOException) {
                        throw DownloadException("Couldn't write the download: ${e.message}", retryable = false)
                    }
                    have += n
                    if (have - lastCheck >= SPACE_CHECK_EVERY) {
                        lastCheck = have
                        if (freeBytes() < STORAGE_MARGIN / 2) throw DownloadException("The phone ran out of storage. Free some space and resume.", retryable = false)
                    }
                    if (have - reported >= REPORT_EVERY) {
                        reported = have
                        onProgress(have, limit)
                    }
                }
                out.force(false)
                onProgress(have, limit)
            }
        } }
    }

    /**
     * The `.part` file positioned at [from]: a new file (`CREATE_NEW`) when starting from 0, else the
     * existing one, which must be exactly [from] bytes long. Neither follows a link at the name.
     */
    private fun openPart(part: File, from: Long): FileChannel {
        val path = part.toPath()
        return try {
            if (from == 0L) {
                Files.deleteIfExists(path) // removes a link itself, never its target
                FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
            } else {
                FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).also { ch ->
                    if (ch.size() != from) {
                        ch.close()
                        throw DownloadException("The partial download changed on disk. Try again to start over.")
                    }
                    ch.position(from)
                }
            }
        } catch (e: DownloadException) {
            throw e
        } catch (e: IOException) {
            throw DownloadException("Couldn't open the download file: ${e.message}", retryable = false)
        }
    }

    companion object {
        /** Room left for the rest of the phone after a download (as the file import keeps). */
        const val STORAGE_MARGIN = 200L * 1024 * 1024
        private const val BUFFER = 256 * 1024
        private const val REPORT_EVERY = 1L * 1024 * 1024
        private const val SPACE_CHECK_EVERY = 64L * 1024 * 1024

        /** `bytes 100-199/1000` → (100, 1000); the total is null for `*`. */
        fun parseContentRange(header: String?): Pair<Long, Long?>? {
            val m = Regex("""^\s*bytes\s+(\d+)-(\d+)/(\d+|\*)\s*$""").find(header ?: return null) ?: return null
            return m.groupValues[1].toLong() to m.groupValues[3].toLongOrNull()
        }

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileChannel.open(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { ch ->
                val buf = ByteBuffer.allocate(1 shl 20)
                while (ch.read(buf) >= 0) {
                    buf.flip()
                    digest.update(buf)
                    buf.clear()
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun mb(bytes: Long) = bytes / 1_000_000
        private fun gb(bytes: Long) = "%.1f".format(java.util.Locale.ROOT, bytes / 1e9)
    }
}
