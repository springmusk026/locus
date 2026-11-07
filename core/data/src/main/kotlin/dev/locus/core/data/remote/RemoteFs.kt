package dev.locus.core.data.remote

import dev.locus.core.agent.ToolException
import org.connectbot.sshlib.SftpAttributes
import org.connectbot.sshlib.SftpClient
import org.connectbot.sshlib.SftpOpenFlag
import org.connectbot.sshlib.SftpResult
import org.connectbot.sshlib.SftpStatusCode
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.InputStream
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

    /**
     * Writes what [source] opens to [path] through a temporary file renamed into place, so a reader never
     * sees half of it, and a failure at any step leaves the old file (or none) at [path], never a gap.
     */
    suspend fun upload(path: String, source: () -> InputStream, executable: Boolean): RemoteStat

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

    override suspend fun upload(path: String, source: () -> InputStream, executable: Boolean): RemoteStat =
        upload(path, source, if (executable) 0b111_101_101 else 0b110_100_100)

    /** [upload] with the file's permission bits given: [mode] (e.g. a replaced file's own). */
    suspend fun upload(path: String, source: () -> InputStream, mode: Int): RemoteStat {
        val dir = path.substringBeforeLast('/')
        val name = path.substringAfterLast('/')
        val tmp = "$dir/$PART_PREFIX$name"
        try {
            val handle = sftp.open(tmp, setOf(SftpOpenFlag.WRITE, SftpOpenFlag.CREATE, SftpOpenFlag.TRUNCATE)).orThrow(tmp)
            try {
                source().use { input -> SftpTransfer.write(sftp, handle, input) { fail(it, tmp) } }
            } finally {
                withContext(NonCancellable) { sftp.close(handle) }
            }
            sftp.setstat(tmp, SftpAttributes(null, null, null, mode and 0b111_111_111, null, null)).orThrow(tmp)
            replace(tmp, path, "$dir/$OLD_PREFIX$name")
        } catch (e: Throwable) {
            // Whatever failed, the half-written or unplaced copy doesn't stay behind. The old file is untouched.
            withContext(NonCancellable) { sftp.remove(tmp) }
            throw e
        }
        return stat(path) ?: throw ToolException("SFTP: $path is missing right after it was written.")
    }

    /** What [path] is, following a link (the path the user named), or null when it doesn't exist. */
    suspend fun kind(path: String): RemoteEntry.Kind? = when (val r = sftp.stat(path)) {
        is SftpResult.Success -> when ((r.value.permissions ?: 0) and S_IFMT) {
            S_IFDIR -> RemoteEntry.Kind.DIR
            S_IFREG -> RemoteEntry.Kind.FILE
            else -> RemoteEntry.Kind.OTHER
        }
        else -> null
    }

    /** [path]'s permission bits, or null when it doesn't exist. */
    suspend fun mode(path: String): Int? = (sftp.stat(path) as? SftpResult.Success)?.value?.permissions?.and(0b111_111_111)

    /**
     * Puts [tmp] at [path], never leaving [path] missing on a failure. sshlib has no `posix-rename@openssh.com`
     * (its SFTP client sends only v3 requests), and a v3 rename refuses an existing target. So: try the plain
     * rename (a new file); otherwise move the old file to [backup], rename [tmp] into place, and only then
     * delete the backup. If the second rename fails, the backup goes back.
     */
    private suspend fun replace(tmp: String, path: String, backup: String) {
        if (sftp.rename(tmp, path) is SftpResult.Success) return
        // A leftover backup from an earlier failure would make the next rename fail.
        if (sftp.lstat(backup) is SftpResult.Success) sftp.remove(backup).orThrow(backup)
        sftp.rename(path, backup).orThrow(path)
        when (val r = sftp.rename(tmp, path)) {
            is SftpResult.Success -> Unit
            else -> {
                val back = withContext(NonCancellable) { sftp.rename(backup, path) }
                if (back !is SftpResult.Success) {
                    throw ToolException("SFTP: couldn't put the new $path in place (${describe(r)}), nor the old one back; it is at $backup.")
                }
                throw fail(r, path)
            }
        }
        // The new file is in place; a backup that won't go is only clutter, which the sync skips.
        sftp.remove(backup)
    }

    override suspend fun download(path: String, sink: OutputStream, maxBytes: Long): Long {
        val handle = sftp.open(path, setOf(SftpOpenFlag.READ)).orThrow(path)
        try {
            val size = (sftp.fstat(handle) as? SftpResult.Success)?.value?.size ?: 0L
            return SftpTransfer.read(sftp, handle, 0, maxBytes, size, { fail(it, path) }) { sink.write(it) }
        } finally {
            withContext(NonCancellable) { sftp.close(handle) }
        }
    }

    override suspend fun readFrom(path: String, offset: Long, maxBytes: Int): ByteArray {
        val handle = when (val r = sftp.open(path, setOf(SftpOpenFlag.READ))) {
            is SftpResult.Success -> r.value
            else -> return ByteArray(0)
        }
        val out = java.io.ByteArrayOutputStream()
        try {
            val size = (sftp.fstat(handle) as? SftpResult.Success)?.value?.size ?: 0L
            SftpTransfer.read(sftp, handle, offset, maxBytes.toLong(), size, { fail(it, path) }) { out.write(it) }
        } finally {
            withContext(NonCancellable) { sftp.close(handle) }
        }
        return out.toByteArray()
    }

    /**
     * Makes [path] and any missing folders above it. The common cases cost one round trip: the folder
     * exists, or only it is missing. Otherwise it walks up to the nearest folder that exists and makes
     * the rest from there, never stat-ing every folder from `/` down.
     */
    override suspend fun mkdirs(path: String) {
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty() || stat(path) != null) return
        var have = parts.size - 1
        while (have > 0 && stat("/" + parts.take(have).joinToString("/")) == null) have--
        for (i in have + 1..parts.size) {
            val at = "/" + parts.take(i).joinToString("/")
            when (val r = sftp.mkdir(at)) {
                is SftpResult.Success -> Unit
                // Made meanwhile (a parallel upload into the same folder): fine.
                else -> if (stat(at) == null) throw fail(r, at)
            }
        }
    }

    override suspend fun remove(path: String) {
        sftp.remove(path)
    }

    private fun SftpAttributes.toStat() = RemoteStat(size ?: 0, (mtime ?: 0).toLong(), ((permissions ?: 0) and 0b001_001_001) != 0)

    private fun <T> SftpResult<T>.orThrow(path: String): T = when (this) {
        is SftpResult.Success -> value
        else -> throw fail(this, path)
    }

    internal fun fail(r: SftpResult<*>, path: String) = ToolException(
        when (r) {
            is SftpResult.ServerError -> "SFTP: $path: ${r.message.ifBlank { r.statusCode.toString() }}"
            is SftpResult.ProtocolError -> "SFTP protocol error on $path: ${r.message}"
            else -> "SFTP failed on $path: $r"
        },
    )

    private fun describe(r: SftpResult<*>) = when (r) {
        is SftpResult.ServerError -> r.message.ifBlank { r.statusCode.toString() }
        else -> r.toString()
    }

    companion object {
        /** An upload's temporary file, next to its target until it's renamed into place. */
        const val PART_PREFIX = ".locus-part-"

        /** The old file while an upload replaces it; deleted once the new one is in place. */
        const val OLD_PREFIX = ".locus-old-"

        private const val S_IFMT = 0xF000
        private const val S_IFDIR = 0x4000
        private const val S_IFREG = 0x8000
    }
}
