package dev.locus.core.data.remote

import dev.locus.core.agent.ToolException
import kotlinx.coroutines.runBlocking
import org.connectbot.sshlib.SftpAttributes
import org.connectbot.sshlib.SftpClient
import org.connectbot.sshlib.SftpDirectoryEntry
import org.connectbot.sshlib.SftpFileHandle
import org.connectbot.sshlib.SftpOpenFlag
import org.connectbot.sshlib.SftpResult
import org.connectbot.sshlib.SftpStatusCode
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [SftpFs.upload] replacing a file on a v3 server (rename refuses an existing target) never leaves the
 * target missing: each step is made to fail in turn, and the old file is still there (or the new one is).
 */
class SftpFsUploadTest {
    /** An SFTP v3 server in memory. [fail] names the step to fail: "open", "write", "setstat", "rename:<from>", "remove:<path>". */
    private class FakeSftp(var fail: String? = null) : SftpClient {
        val files = linkedMapOf<String, ByteArray>()
        private val handles = mutableMapOf<SftpFileHandle, String>()
        private var next = 0

        private fun err(): SftpResult.ServerError = SftpResult.ServerError(SftpStatusCode.FAILURE, "injected")
        private fun <T> ok(v: T): SftpResult<T> = SftpResult.Success(v)

        override val protocolVersion = 3
        override val extensions: Set<String> = emptySet()
        override val isOpen = true

        override suspend fun open(path: String, flags: Set<SftpOpenFlag>, attrs: SftpAttributes): SftpResult<SftpFileHandle> {
            if (fail == "open") return err()
            // sshlib's constructor is Kotlin-internal; a real server hands these out.
            val handle = SftpFileHandle::class.java.getConstructor(ByteArray::class.java).newInstance("h${next++}".toByteArray())
            handles[handle] = path
            if (SftpOpenFlag.TRUNCATE in flags || path !in files) files[path] = ByteArray(0)
            return ok(handle)
        }
        override suspend fun close(handle: SftpFileHandle): SftpResult<Unit> = ok(Unit)
        override suspend fun read(handle: SftpFileHandle, offset: Long, length: Int): SftpResult<ByteArray> = err()
        override suspend fun write(handle: SftpFileHandle, offset: Long, data: ByteArray): SftpResult<Unit> {
            if (fail == "write") return err()
            val path = handles.getValue(handle)
            val old = files.getValue(path)
            val out = old.copyOf(maxOf(old.size, (offset + data.size).toInt()))
            data.copyInto(out, offset.toInt())
            files[path] = out
            return ok(Unit)
        }
        override suspend fun stat(path: String) = lstat(path)
        override suspend fun lstat(path: String): SftpResult<SftpAttributes> =
            files[path]?.let { ok(SftpAttributes(it.size.toLong(), null, null, 0x81a4, null, 1)) } ?: SftpResult.ServerError(SftpStatusCode.NO_SUCH_FILE, "no such file")
        override suspend fun fstat(handle: SftpFileHandle): SftpResult<SftpAttributes> = err()
        override suspend fun setstat(path: String, attrs: SftpAttributes): SftpResult<Unit> = if (fail == "setstat") err() else ok(Unit)
        override suspend fun fsetstat(handle: SftpFileHandle, attrs: SftpAttributes): SftpResult<Unit> = ok(Unit)
        override suspend fun opendir(path: String): SftpResult<SftpFileHandle> = err()
        override suspend fun readdir(handle: SftpFileHandle): SftpResult<List<SftpDirectoryEntry>> = err()
        override suspend fun mkdir(path: String, attrs: SftpAttributes): SftpResult<Unit> = ok(Unit)
        override suspend fun rmdir(path: String): SftpResult<Unit> = ok(Unit)
        override suspend fun remove(path: String): SftpResult<Unit> {
            if (fail == "remove:$path") return err()
            return if (files.remove(path) != null) ok(Unit) else SftpResult.ServerError(SftpStatusCode.NO_SUCH_FILE, "no such file")
        }
        override suspend fun rename(oldPath: String, newPath: String): SftpResult<Unit> {
            if (fail == "rename:$oldPath") return err()
            // SFTP v3 (OpenSSH's sftp-server): an existing target is refused.
            if (newPath in files || oldPath !in files) return err()
            files[newPath] = files.remove(oldPath)!!
            return ok(Unit)
        }
        override suspend fun copyData(readHandle: SftpFileHandle, readOffset: Long, length: Long, writeHandle: SftpFileHandle, writeOffset: Long, extra: Long): SftpResult<Unit> = err()
        override suspend fun realpath(path: String): SftpResult<String> = ok(path)
        override suspend fun readlink(path: String): SftpResult<String> = err()
        override suspend fun symlink(linkPath: String, targetPath: String): SftpResult<Unit> = err()
        override fun close() = Unit
    }

    private val path = "/srv/app/main.py"
    private val part = "/srv/app/.locus-part-main.py"
    private val old = "/srv/app/.locus-old-main.py"

    private fun upload(sftp: FakeSftp) = runBlocking { SftpFs(sftp).upload(path, { "new".byteInputStream() }, executable = false) }

    private fun server(fail: String? = null) = FakeSftp(fail).apply { files[path] = "old".toByteArray() }

    @Test
    fun `replacing an existing file leaves only the new one`() {
        val sftp = server()
        upload(sftp)
        assertEquals("new", String(sftp.files.getValue(path)))
        assertEquals(setOf(path), sftp.files.keys)
    }

    @Test
    fun `a new file is renamed straight into place`() {
        val sftp = FakeSftp()
        upload(sftp)
        assertEquals(mapOf(path to "new"), sftp.files.mapValues { String(it.value) })
    }

    @Test
    fun `a failure at any step before the swap keeps the old file and no leftovers`() {
        for (step in listOf("open", "write", "setstat", "rename:$path")) {
            val sftp = server(step)
            assertFailsWith<ToolException>(step) { upload(sftp) }
            assertEquals("old", String(sftp.files.getValue(path)), step)
            assertEquals(setOf(path), sftp.files.keys, step)
        }
    }

    @Test
    fun `when the new file can't be put in place, the old one goes back`() {
        // The first rename of the part (to a free target) is attempted and refused because the target exists;
        // after the old file moves aside, the second fails by injection: the old one is restored.
        val sftp = server()
        var renamesOfPart = 0
        val failing = object : SftpClient by sftp {
            override suspend fun rename(oldPath: String, newPath: String): SftpResult<Unit> {
                if (oldPath == part && ++renamesOfPart == 2) return SftpResult.ServerError(SftpStatusCode.FAILURE, "disk full")
                return sftp.rename(oldPath, newPath)
            }
        }
        val e = assertFailsWith<ToolException> { runBlocking { SftpFs(failing).upload(path, { "new".byteInputStream() }, false) } }
        assertTrue("disk full" in e.message.orEmpty(), e.message)
        assertEquals("old", String(sftp.files.getValue(path)))
        assertEquals(setOf(path), sftp.files.keys)
    }

    @Test
    fun `when even the rollback fails, the error says where the old file is`() {
        val sftp = server()
        var renamesOfPart = 0
        val failing = object : SftpClient by sftp {
            override suspend fun rename(oldPath: String, newPath: String): SftpResult<Unit> {
                if (oldPath == part && ++renamesOfPart == 2) return SftpResult.ServerError(SftpStatusCode.FAILURE, "disk full")
                if (oldPath == old) return SftpResult.ServerError(SftpStatusCode.FAILURE, "gone")
                return sftp.rename(oldPath, newPath)
            }
        }
        val e = assertFailsWith<ToolException> { runBlocking { SftpFs(failing).upload(path, { "new".byteInputStream() }, false) } }
        assertTrue(old in e.message.orEmpty(), e.message)
        assertEquals("old", String(sftp.files.getValue(old)))
        assertTrue(part !in sftp.files)
    }

    @Test
    fun `a backup that won't delete doesn't fail the upload, and a stale one is cleared first`() {
        val sftp = server("remove:$old")
        upload(sftp)
        assertEquals("new", String(sftp.files.getValue(path)))
        assertEquals("old", String(sftp.files.getValue(old)), "left behind; the sync skips .locus-old- files")

        // The next upload clears it before moving the current file aside.
        sftp.fail = null
        runBlocking { SftpFs(sftp).upload(path, { "newer".byteInputStream() }, false) }
        assertEquals("newer", String(sftp.files.getValue(path)))
        assertEquals(setOf(path), sftp.files.keys)
    }
}
