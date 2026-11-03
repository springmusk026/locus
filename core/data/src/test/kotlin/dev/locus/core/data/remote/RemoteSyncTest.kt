package dev.locus.core.data.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The real [RemoteSync] against a machine that answers whatever it likes ([FakeFs]): names from the
 * machine and links on the phone never make it read or write outside the project folder; each sync has
 * its own download folder; and the secret check reads files as a stream.
 */
class RemoteSyncTest {
    @get:Rule val tmp = TemporaryFolder()

    private val project by lazy { tmp.newFolder("workspace", "app") }
    private val outside by lazy { tmp.newFolder("outside") }
    private val tmpParent by lazy { tmp.newFolder("cache", "remote-sync") }

    /** A machine's folder in memory. Names are taken as given, so it can send "..", "a/b" or a NUL. */
    private class FakeFs : RemoteFs {
        /** Directory path to its entries. */
        val dirs = linkedMapOf<String, MutableList<RemoteEntry>>()
        val content = mutableMapOf<String, ByteArray>()
        val uploads = mutableListOf<String>()
        var onDownload: suspend (String) -> Unit = {}

        fun file(dir: String, name: String, text: String, mtime: Long = 1) {
            dirs.getOrPut(dir) { mutableListOf() }.removeAll { it.name == name }
            dirs.getValue(dir) += RemoteEntry(name, RemoteEntry.Kind.FILE, RemoteStat(text.length.toLong(), mtime, false))
            content["$dir/$name"] = text.toByteArray()
        }

        fun dir(parent: String, name: String) {
            dirs.getOrPut(parent) { mutableListOf() } += RemoteEntry(name, RemoteEntry.Kind.DIR, RemoteStat(0, 0, false))
            dirs.getOrPut("$parent/$name") { mutableListOf() }
        }

        override suspend fun list(dir: String) = dirs[dir]?.toList()
        override suspend fun stat(path: String) = content[path]?.let { RemoteStat(it.size.toLong(), 1, false) }
        override suspend fun upload(path: String, source: () -> InputStream, executable: Boolean): RemoteStat {
            val bytes = source().use { it.readBytes() }
            uploads += path
            content[path] = bytes
            return RemoteStat(bytes.size.toLong(), 1, executable)
        }
        override suspend fun download(path: String, sink: OutputStream, maxBytes: Long): Long {
            onDownload(path)
            val bytes = content.getValue(path)
            sink.write(bytes)
            return bytes.size.toLong()
        }
        override suspend fun readFrom(path: String, offset: Long, maxBytes: Int) = ByteArray(0)
        override suspend fun mkdirs(path: String) = Unit
        override suspend fun remove(path: String) {
            content.remove(path)
        }
    }

    private fun sync(fs: RemoteFs, secrets: Collection<String> = emptyList()) =
        RemoteSync(fs, project, REMOTE, SyncRules(null), secrets, tmpParent)

    /** Applies changes the way RemoteExecution does, minus the trash: write the download over the file, or delete it. */
    private val written = mutableListOf<File>()
    private val apply = { _: String, target: File, downloaded: File?, _: PulledChange.Kind ->
        written += target
        if (downloaded == null) {
            target.delete()
        } else {
            target.parentFile?.mkdirs()
            Files.copy(downloaded.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        Unit
    }

    private fun link(at: File, to: File) = Files.createSymbolicLink(at.toPath(), to.toPath())

    private fun everythingUnder(dir: File) = dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).path }.toSet()

    @Test
    fun `names from the machine that leave the folder are refused`() = runBlocking {
        val fs = FakeFs()
        fs.dirs[REMOTE] = mutableListOf()
        fs.file(REMOTE, "ok.txt", "fine")
        for (name in listOf("..", "../escape.txt", "/etc/passwd", "a\u0000b", ".")) {
            fs.dirs.getValue(REMOTE) += RemoteEntry(name, RemoteEntry.Kind.FILE, RemoteStat(4, 1, false))
            fs.content["$REMOTE/$name"] = "evil".toByteArray()
        }
        val (state, report) = sync(fs).down(SyncState(), apply)

        assertEquals(setOf("ok.txt"), everythingUnder(project))
        assertEquals(emptySet(), everythingUnder(outside))
        assertFalse(File(project.parentFile, "escape.txt").exists())
        assertEquals(setOf("ok.txt"), state.files.keys)
        assertEquals(setOf("..", "../escape.txt", "/etc/passwd", "a\u0000b", "."), report.refused.toSet())
        assertEquals(listOf("ok.txt"), report.pulled.map { it.path })
    }

    @Test
    fun `a folder on the phone that is a link is never written through`() = runBlocking {
        link(File(project, "docs"), outside)
        val fs = FakeFs()
        fs.dirs[REMOTE] = mutableListOf()
        fs.dir(REMOTE, "docs")
        fs.file("$REMOTE/docs", "pwned.txt", "evil")
        val (_, report) = sync(fs).down(SyncState(), apply)

        assertFalse(File(outside, "pwned.txt").exists())
        assertEquals(listOf("docs/pwned.txt"), report.refused)
        assertTrue(written.isEmpty())
    }

    @Test
    fun `a file on the phone that is a link is neither read, overwritten nor deleted`() = runBlocking {
        val secret = File(outside, "secret.txt").apply { writeText("outside content") }
        link(File(project, "notes.txt"), secret)
        val fs = FakeFs()
        fs.dirs[REMOTE] = mutableListOf()
        fs.file(REMOTE, "notes.txt", "from the machine")
        val (_, report) = sync(fs).down(SyncState(), apply)
        assertEquals("outside content", secret.readText())
        assertEquals(listOf("notes.txt"), report.refused)

        // Deleted on the machine, agreed earlier with the outside file's hash: it stays.
        fs.dirs.getValue(REMOTE).clear()
        val agreed = SyncState(files = mapOf("notes.txt" to SyncState.Synced(RemoteSync.sha256(secret), 15, 1, 15, 1)))
        val (after, deleted) = sync(fs).down(agreed, apply)
        assertTrue(secret.exists())
        assertEquals(listOf("notes.txt"), deleted.refused)
        assertTrue(after.files.isEmpty())
    }

    @Test
    fun `a delete under a linked folder never reaches outside`() = runBlocking {
        val victim = File(outside, "keep.txt").apply { writeText("keep me") }
        link(File(project, "lib"), outside)
        val fs = FakeFs().apply { dirs[REMOTE] = mutableListOf() }
        val agreed = SyncState(files = mapOf("lib/keep.txt" to SyncState.Synced(RemoteSync.sha256(victim), 7, 1, 7, 1)))
        sync(fs).down(agreed, apply)
        assertTrue(victim.exists())
        assertTrue(written.isEmpty())
    }

    @Test
    fun `sync up doesn't follow links, even a file swapped for one after the scan`() = runBlocking {
        File(outside, "id_ed25519_copy").writeText("PRIVATE KEY")
        link(File(project, "elsewhere"), outside)
        File(project, "main.py").writeText("print(1)")
        val swapped = File(project, "config.txt").apply { writeText("harmless") }
        val fs = FakeFs()
        val s = sync(fs)
        val scan = s.scanLocal()
        assertEquals(setOf("main.py", "config.txt"), scan.files.keys)

        // Swapped for a link to a file outside between the scan and the upload.
        swapped.delete()
        link(swapped, File(outside, "id_ed25519_copy"))
        val (state, report) = s.up(SyncState(), scan)

        assertEquals(listOf("$REMOTE/main.py"), fs.uploads)
        assertEquals(listOf("config.txt"), report.refused)
        assertEquals(setOf("main.py"), state.files.keys)
        assertTrue(fs.content.values.none { String(it).contains("PRIVATE KEY") })
    }

    @Test
    fun `a stored path with dot-dot is forgotten, not deleted on the machine`() = runBlocking {
        val fs = FakeFs()
        fs.content["$REMOTE/../other-project/main.c"] = "theirs".toByteArray()
        val old = SyncState(files = mapOf("../other-project/main.c" to SyncState.Synced("h", 6, 1, 6, 1)))
        val (state, _) = sync(fs).up(old)
        assertTrue(fs.content.containsKey("$REMOTE/../other-project/main.c"))
        assertTrue(state.files.isEmpty())
    }

    @Test
    fun `each sync downloads into its own folder, and stale ones are cleaned`() = runBlocking {
        val legacy = File(tmpParent, "pull").apply { mkdirs() }
        File(legacy, "pull123.part").writeText("old")
        val stale = File(tmpParent, "pull-dead").apply { mkdirs(); setLastModified(System.currentTimeMillis() - 7 * 3_600_000L) }
        val other = tmp.newFolder("workspace", "web")

        val first = FakeFs()
        first.dirs[REMOTE] = mutableListOf()
        first.file(REMOTE, "a.txt", "first project's file")
        val inDownload = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        first.onDownload = { inDownload.complete(Unit); release.await() }

        val second = FakeFs()
        second.dirs[REMOTE] = mutableListOf()
        second.file(REMOTE, "b.txt", "second project's file")

        withContext(Dispatchers.Default) {
            val a = async { sync(first).down(SyncState(), apply) }
            inDownload.await()
            // Another project syncs to completion while the first one's download is open.
            RemoteSync(second, other, REMOTE, SyncRules(null), emptyList(), tmpParent).down(SyncState()) { _, t, d, _ -> d?.copyTo(t, overwrite = true) }
            release.complete(Unit)
            val (_, report) = a.await()
            assertEquals(listOf("a.txt"), report.pulled.map { it.path })
        }
        assertEquals("first project's file", File(project, "a.txt").readText())
        assertEquals("second project's file", File(other, "b.txt").readText())
        assertFalse(legacy.exists(), "the old shared folder is removed")
        assertFalse(stale.exists(), "a folder left by a sync that died is removed")
        assertEquals(emptyList(), tmpParent.listFiles()!!.toList(), "each sync removes its own folder")
    }

    @Test
    fun `the secret check streams a large file and finds a value at its end or across chunks`() = runBlocking {
        val key = "sk-live-0123456789abcdef"
        // 150 MB generated on the fly, the key in its last bytes: never held in memory at once.
        val size = 150L * 1024 * 1024
        val tail = key.toByteArray()
        val stream = object : InputStream() {
            var at = 0L
            override fun read(): Int = if (at >= size) -1 else (if (at >= size - tail.size) tail[(at - (size - tail.size)).toInt()].toInt() else 'x'.code).also { at++ }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (at >= size) return -1
                val n = minOf(len.toLong(), size - at).toInt()
                for (i in 0 until n) b[off + i] = read().toByte()
                return n
            }
        }
        assertTrue(SyncRules.containsSecret(stream, listOf(key)))

        // A value split across the 64 KB chunk boundary.
        val split = ByteArray(64 * 1024 - 5) { 'y'.code.toByte() } + tail + ByteArray(100)
        assertTrue(SyncRules.containsSecret(split.inputStream(), listOf(key)))
        assertFalse(SyncRules.containsSecret(ByteArray(300_000).inputStream(), listOf(key)))

        // Through the sync: a 20 MB file (binary zeros, then the key) stays on the phone.
        val big = File(project, "dump.bin")
        RandomAccessFile(big, "rw").use { it.setLength(20L * 1024 * 1024); it.seek(it.length()); it.write(tail) }
        File(project, "small.txt").writeText("fine")
        val fs = FakeFs()
        val (_, report) = sync(fs, listOf(key)).up(SyncState())
        assertEquals(listOf("$REMOTE/small.txt"), fs.uploads)
        assertEquals(1, report.skippedSecrets)
    }

    @Test
    fun `safe names`() {
        assertTrue(RemoteSync.isSafeRelative("src/main.kt"))
        assertTrue(RemoteSync.isSafeRelative(".github/workflows/ci.yml"))
        assertTrue(RemoteSync.isSafeRelative("..hidden"))
        for (bad in listOf("", "/abs", "a/../b", "..", "./a", "a//b", "a/", "a\u0000b")) assertFalse(RemoteSync.isSafeRelative(bad), bad)
    }

    private companion object {
        const val REMOTE = "/home/me/locus/app"
    }
}
