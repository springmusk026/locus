package dev.locus.core.data.remote

import dev.locus.core.agent.ToolException
import org.connectbot.sshlib.SftpAttributes
import org.connectbot.sshlib.SftpClient
import org.connectbot.sshlib.SftpOpenFlag
import org.connectbot.sshlib.SftpResult
import org.connectbot.sshlib.SftpStatusCode
import java.io.File
import java.io.OutputStream

/** A file on the machine: size, modification time (seconds) and permission bits. */
data class RemoteStat(val size: Long, val mtime: Long, val executable: Boolean)

/** One entry of a remote folder listing. */
data class RemoteEntry(val name: String, val kind: Kind, val stat: RemoteStat) {
    enum class Kind { FILE, DIR, OTHER }
}

/** The file operations a project sync needs on the machine. Absolute paths. */
interface RemoteFs {
    /** The folder's entries, or null when it doesn't exist. */
    suspend fun list(dir: String): List<RemoteEntry>?

    suspend fun stat(path: String): RemoteStat?

    /** Writes [source] to [path] through a temporary file renamed into place, so a reader never sees half of it. */
    suspend fun upload(path: String, source: File, executable: Boolean): RemoteStat

    /** Copies [path] into [sink], at most [maxBytes]; returns how many bytes were read. */
    suspend fun download(path: String, sink: OutputStream, maxBytes: Long): Long

    /** Reads [path] from [offset]: the bytes added since a previous read (a growing log). */
    suspend fun readFrom(path: String, offset: Long, maxBytes: Int): ByteArray

    suspend fun mkdirs(path: String)

    suspend fun remove(path: String)
}

/** [RemoteFs] over an SFTP session (sshlib), on a connection [dev.locus.core.data.ssh.SshService] verified. */
class SftpFs(private val sftp: SftpClient) : RemoteFs {
    override suspend fun list(dir: String): List<RemoteEntry>? {
        val entries = when (val r = sftp.listdir(dir)) {
            is SftpResult.Success -> r.value
            is SftpResult.ServerError -> if (r.statusCode == SftpStatusCode.NO_SUCH_FILE) return null else throw fail(r, dir)
            else -> throw fail(r, dir)
        }
        return entries.filter { it.filename != "." && it.filename != ".." }.map { e ->
            val perms = e.attrs.permissions ?: 0
            val kind = when (perms and S_IFMT) {
                S_IFDIR -> RemoteEntry.Kind.DIR
                S_IFREG -> RemoteEntry.Kind.FILE
                // No type bits: the long name says (some servers leave them out).
                0 -> if (e.longname.startsWith("d")) RemoteEntry.Kind.DIR else if (e.longname.startsWith("-")) RemoteEntry.Kind.FILE else RemoteEntry.Kind.OTHER
                else -> RemoteEntry.Kind.OTHER
            }
            RemoteEntry(e.filename, kind, e.attrs.toStat())
        }
    }

    override suspend fun stat(path: String): RemoteStat? = when (val r = sftp.lstat(path)) {
        is SftpResult.Success -> r.value.toStat()
        else -> null
    }

    override suspend fun upload(path: String, source: File, executable: Boolean): RemoteStat {
        val tmp = path.substringBeforeLast('/') + "/.locus-part-" + path.substringAfterLast('/')
        val handle = sftp.open(tmp, setOf(SftpOpenFlag.WRITE, SftpOpenFlag.CREATE, SftpOpenFlag.TRUNCATE)).orThrow(tmp)
        try {
            source.inputStream().use { input ->
                val buffer = ByteArray(CHUNK)
                var offset = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    sftp.write(handle, offset, if (n == buffer.size) buffer.copyOf() else buffer.copyOf(n)).orThrow(tmp)
                    offset += n
                }
            }
        } finally {
            sftp.close(handle)
        }
        sftp.setstat(tmp, SftpAttributes(null, null, null, if (executable) 0b111_101_101 else 0b110_100_100, null, null))
        // SFTP v3 rename refuses an existing target, so the old file goes first.
        sftp.remove(path)
        sftp.rename(tmp, path).orThrow(path)
        return stat(path) ?: throw ToolException("SFTP: $path is missing right after it was written.")
    }

    override suspend fun download(path: String, sink: OutputStream, maxBytes: Long): Long {
        val handle = sftp.open(path, setOf(SftpOpenFlag.READ)).orThrow(path)
        var offset = 0L
        try {
            while (offset < maxBytes) {
                val chunk = readChunk(handle, offset, CHUNK, path) ?: break
                if (chunk.isEmpty()) break
                sink.write(chunk)
                offset += chunk.size
            }
        } finally {
            sftp.close(handle)
        }
        return offset
    }

    override suspend fun readFrom(path: String, offset: Long, maxBytes: Int): ByteArray {
        val handle = when (val r = sftp.open(path, setOf(SftpOpenFlag.READ))) {
            is SftpResult.Success -> r.value
            else -> return ByteArray(0)
        }
        val out = java.io.ByteArrayOutputStream()
        try {
            var at = offset
            while (out.size() < maxBytes) {
                val chunk = readChunk(handle, at, minOf(CHUNK, maxBytes - out.size()), path) ?: break
                if (chunk.isEmpty()) break
                out.write(chunk)
                at += chunk.size
            }
        } finally {
            sftp.close(handle)
        }
        return out.toByteArray()
    }

    override suspend fun mkdirs(path: String) {
        var at = ""
        for (part in path.split('/').filter { it.isNotEmpty() }) {
            at += "/$part"
            if (stat(at) == null) sftp.mkdir(at).orThrow(at)
        }
    }

    override suspend fun remove(path: String) {
        sftp.remove(path)
    }

    private fun SftpAttributes.toStat() = RemoteStat(size ?: 0, (mtime ?: 0).toLong(), ((permissions ?: 0) and 0b001_001_001) != 0)

    /** A chunk, or null at the end of the file. */
    private suspend fun readChunk(handle: org.connectbot.sshlib.SftpFileHandle, offset: Long, length: Int, path: String): ByteArray? =
        when (val r = sftp.read(handle, offset, length)) {
            is SftpResult.Success -> r.value
            is SftpResult.ServerError -> if (r.statusCode == SftpStatusCode.EOF) null else throw fail(r, path)
            else -> throw fail(r, path)
        }

    private fun <T> SftpResult<T>.orThrow(path: String): T = when (this) {
        is SftpResult.Success -> value
        else -> throw fail(this, path)
    }

    private fun fail(r: SftpResult<*>, path: String) = ToolException(
        when (r) {
            is SftpResult.ServerError -> "SFTP: $path: ${r.message.ifBlank { r.statusCode.toString() }}"
            is SftpResult.ProtocolError -> "SFTP protocol error on $path: ${r.message}"
            else -> "SFTP failed on $path: $r"
        },
    )

    private companion object {
        const val CHUNK = 32 * 1024
        const val S_IFMT = 0xF000
        const val S_IFDIR = 0x4000
        const val S_IFREG = 0x8000
    }
}
