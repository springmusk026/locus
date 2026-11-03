package dev.locus.core.linux

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import dev.locus.core.model.NoFollowOpener
import java.io.FileDescriptor
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Path

/**
 * [NoFollowOpener] on `android.system.Os` (public since API 21): `open(2)` with `O_NOFOLLOW`, and the
 * opened descriptor's real path from `/proc/self/fd/<n>`, which Locus may always read for itself.
 * Installed at startup ([dev.locus.core.model.NoFollowFiles.opener]); see
 * docs/design/linux-isolation.md, "Closing the write race".
 */
object AndroidNoFollowOpener : NoFollowOpener {
    /** rw-rw-rw- before the umask, as `File.writeText` creates files. */
    private const val MODE = 438

    override fun open(path: Path, create: Boolean): NoFollowOpener.Opened {
        val flags = OsConstants.O_WRONLY or OsConstants.O_NOFOLLOW or (if (create) OsConstants.O_CREAT or OsConstants.O_EXCL else 0)
        val fd = try {
            Os.open(path.toString(), flags, MODE)
        } catch (e: ErrnoException) {
            throw when (e.errno) {
                OsConstants.EEXIST -> FileAlreadyExistsException(path.toString())
                OsConstants.ELOOP -> IOException("$path is a link", e)
                else -> IOException("Couldn't open $path: ${e.message}", e)
            }
        }
        return Opened(fd)
    }

    override fun kernelPath(dir: Path): String? = runCatching {
        val fd = Os.open(dir.toString(), OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        try {
            realPath(fd)
        } finally {
            Os.close(fd)
        }
    }.getOrNull()

    /** The kernel's path of [fd]: `ParcelFileDescriptor.dup` gives a descriptor number through public API. */
    private fun realPath(fd: FileDescriptor): String? = runCatching {
        ParcelFileDescriptor.dup(fd).use { Os.readlink("/proc/self/fd/${it.fd}") }
    }.getOrNull()

    private class Opened(private val fd: FileDescriptor) : NoFollowOpener.Opened {
        override val realPath: String? = realPath(fd)

        override fun replaceContent(bytes: ByteArray) {
            try {
                Os.ftruncate(fd, 0)
                var off = 0
                while (off < bytes.size) off += Os.write(fd, bytes, off, bytes.size - off)
            } catch (e: ErrnoException) {
                throw IOException("Couldn't write: ${e.message}", e)
            }
        }

        override fun close() {
            runCatching { Os.close(fd) }
        }
    }
}
