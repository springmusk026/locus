package dev.locus.feature.settings.ondevice

import androidx.work.WorkInfo
import dev.locus.core.ondevice.gguf.GgufDownloadState
import dev.locus.core.ondevice.gguf.GgufDownloads
import dev.locus.core.ondevice.gguf.GgufErrorText
import dev.locus.core.ondevice.gguf.GgufModels
import dev.locus.core.ondevice.gguf.GgufOffer
import dev.locus.core.ondevice.gguf.GgufReader
import dev.locus.core.ondevice.gguf.ModelNetwork
import dev.locus.core.testing.TestGraph
import io.ktor.http.Url
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Found on the phone: a download whose install threw stayed on "Checking…" forever, and retrying it
 * stored a second copy ("-2"). A failure of any kind must end in a visible failed state, and the same
 * file is never kept twice.
 */
class GgufInstallTest {
    /** A small valid GGUF: a qwen2 header with no tensors, padded. */
    private val gguf: ByteArray = run {
        val o = ByteArrayOutputStream()
        fun u32(v: Long) = o.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v.toInt()).array())
        fun u64(v: Long) = o.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array())
        fun str(s: String) = s.toByteArray().let { u64(it.size.toLong()); o.write(it) }
        val kvs = listOf("general.architecture" to "qwen2", "general.name" to "Tiny", "tokenizer.chat_template" to "{% if tools %}{{ tools }}{% endif %}")
        u32(GgufReader.MAGIC.toLong()); u32(3); u64(0); u64(kvs.size.toLong())
        kvs.forEach { (k, v) -> str(k); u32(8); str(v) }
        o.write(ByteArray(4096))
        o.toByteArray()
    }
    private val sha = MessageDigest.getInstance("SHA-256").digest(gguf).joinToString("") { "%02x".format(it) }

    /** Its own scope, so the graph's background work never holds the test open. */
    private fun graph(@Suppress("UNUSED_PARAMETER") caller: kotlinx.coroutines.CoroutineScope) =
        TestGraph(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default))

    private fun ggufDir(g: TestGraph) = File(g.context.filesDir, "gguf")
    private fun ggufFiles(g: TestGraph) = ggufDir(g).listFiles().orEmpty().filter { it.name.endsWith(".gguf") }.map { it.name }.sorted()

    private fun part(g: TestGraph, name: String) = File(g.context.filesDir, name).apply { writeBytes(gguf) }

    @Test
    fun theSameFileTwiceIsKeptOnce() = runBlocking {
        val g = graph(this)
        val first = g.ggufModels.adopt(part(g, "a.part"), "tiny-Q4_K_M.gguf", "a/b@1234567", sha, verified = true)
        val secondPart = part(g, "b.part")
        val second = g.ggufModels.adopt(secondPart, "tiny-Q4_K_M.gguf", "a/b@1234567", sha, verified = true)
        assertEquals(first.id, second.id)
        assertEquals(listOf("tiny-q4_k_m.gguf"), ggufFiles(g), "no -2 copy")
        assertFalse(secondPart.exists(), "the second download is dropped")
        assertEquals(1, g.ggufModels.models.value.size)
    }

    @Test
    fun anUnrecordedFileIsRecoveredNotCopied() = runBlocking {
        val g = graph(this)
        // What the bug left: the file moved into files/gguf/, its record never written.
        ggufDir(g).mkdirs()
        File(ggufDir(g), "tiny-q4_k_m.gguf").writeBytes(gguf)
        g.ggufModels.recoverOrphans() // also run at start; whichever runs first records it
        val recovered = g.ggufModels.models.value.single()
        assertEquals(sha, recovered.sha256)
        assertFalse(recovered.verified, "unverified until a matching download vouches for it")
        // Retrying the download installs nothing new and vouches for the file.
        val again = g.ggufModels.adopt(part(g, "c.part"), "tiny-Q4_K_M.gguf", "a/b@1234567", sha, verified = true)
        assertEquals(recovered.id, again.id)
        assertTrue(g.ggufModels.model(again.id)!!.verified)
        assertEquals(listOf("tiny-q4_k_m.gguf"), ggufFiles(g))
    }

    @Test
    fun aDuplicateLeftByTheBugIsFreed() = runBlocking {
        val g = graph(this)
        ggufDir(g).mkdirs()
        File(ggufDir(g), "tiny-q4_k_m.gguf").writeBytes(gguf)
        File(ggufDir(g), "tiny-q4_k_m-2.gguf").writeBytes(gguf)
        g.ggufModels.recoverOrphans()
        assertEquals(1, g.ggufModels.models.value.size)
        assertEquals(listOf("tiny-q4_k_m.gguf"), ggufFiles(g), "the -2 copy is deleted")
    }

    @Test
    fun aFileThatFailsItsChecksStaysOutOfTheModels() = runBlocking {
        val g = graph(this)
        val bad = File(g.context.filesDir, "bad.part").apply { writeBytes(gguf.copyOf(8)) }
        runCatching { g.ggufModels.adopt(bad, "bad.gguf", "x", "00", verified = false) }
        assertTrue(ggufFiles(g).isEmpty(), "nothing is moved before the checks pass")
        assertTrue(bad.exists(), "left for the caller to delete or retry")
    }

    private val offline = object : ModelNetwork {
        override suspend fun <T> get(url: Url, headers: Map<String, String>, block: suspend (ModelNetwork.Response) -> T): T = throw java.net.UnknownHostException(url.host)
        override suspend fun refusal(url: Url): String? = null
    }

    /** Like the ICU crash: an Error, not an Exception, from deep inside the install. */
    private val crashing = object : ModelNetwork {
        override suspend fun <T> get(url: Url, headers: Map<String, String>, block: suspend (ModelNetwork.Response) -> T): T =
            throw ExceptionInInitializerError(IllegalArgumentException("U_REGEX_RULE_SYNTAX near index 12 in /data/app/x/base.apk"))
        override suspend fun refusal(url: Url): String? = null
    }

    private fun offer(sha256: String? = sha) = GgufOffer(
        name = "Tiny", fileName = "tiny-Q4_K_M.gguf",
        url = "https://huggingface.co/a/b/resolve/0123456789abcdef0123456789abcdef01234567/tiny-Q4_K_M.gguf",
        sizeBytes = gguf.size.toLong(), sha256 = sha256, source = "a/b@0123456", license = null,
    )

    /** A download left in downloads.json, as after a restart. */
    private fun pending(g: TestGraph, o: GgufOffer) {
        val dir = File(g.context.filesDir, "gguf-downloads").apply { mkdirs() }
        File(dir, "downloads.json").writeText(
            """[{"offer":{"name":"${o.name}","fileName":"${o.fileName}","url":"${o.url}","sizeBytes":${o.sizeBytes},"sha256":"${o.sha256}","source":"${o.source}"},"unmeteredOnly":true}]""",
        )
    }

    @Test
    fun anErrorDuringTheDownloadIsAFailureNotASpinner() = runBlocking {
        val g = graph(this)
        pending(g, offer())
        val downloads = GgufDownloads(g.context, crashing, g.ggufModels)
        val id = GgufDownloads.idFor(offer())
        assertFalse(downloads.run(id) {})
        val failed = downloads.download(id)!!.state as GgufDownloadState.Failed
        assertTrue(failed.retryable)
        assertTrue(failed.message.contains("U_REGEX_RULE_SYNTAX"), failed.message)
        assertFalse(failed.message.contains("/data/app"), "paths are scrubbed")
    }

    @Test
    fun aJobThatEndsWhileTheRowSaysWorkingBecomesAFailure() = runBlocking {
        val g = graph(this)
        pending(g, offer())
        val downloads = GgufDownloads(g.context, offline, g.ggufModels)
        val id = GgufDownloads.idFor(offer())
        // After a restart with no job running, nothing shows as in progress.
        assertTrue(downloads.download(id)!!.state is GgufDownloadState.Paused)
        downloads.failed(id, IllegalStateException("x"))
        assertTrue(downloads.download(id)!!.state is GgufDownloadState.Failed)
        downloads.settleFinished(id, WorkInfo.State.FAILED) // already final: left as it is
        assertTrue(downloads.download(id)!!.state is GgufDownloadState.Failed)
    }

    @Test
    fun aDownloadOfAFileAlreadyHereSaysSo() = runBlocking {
        val g = graph(this)
        val installed = g.ggufModels.adopt(part(g, "a.part"), "tiny-Q4_K_M.gguf", "a/b@1234567", sha, verified = true)
        val downloads = GgufDownloads(g.context, offline, g.ggufModels)
        downloads.start(offer(), unmeteredOnly = true) // no job is enqueued: it returns before WorkManager
        val state = downloads.download(GgufDownloads.idFor(offer()))!!.state as GgufDownloadState.Installed
        assertEquals(installed.id, state.modelId)
        assertTrue(state.existing)
        assertEquals(listOf("tiny-q4_k_m.gguf"), ggufFiles(g))
    }

    @Test
    fun aPendingDownloadOfARecoveredFileIsDoneAtStart() = runBlocking {
        val g = graph(this)
        ggufDir(g).mkdirs()
        File(ggufDir(g), "tiny-q4_k_m.gguf").writeBytes(gguf)
        pending(g, offer())
        val models = GgufModels(g.context, g.onDeviceModels) // a fresh start
        val downloads = GgufDownloads(g.context, offline, models)
        val id = GgufDownloads.idFor(offer())
        val done = withTimeout(10_000) { downloads.downloads.first { list -> list.any { it.id == id && it.state is GgufDownloadState.Installed } } }
        assertTrue((done.single { it.id == id }.state as GgufDownloadState.Installed).existing)
        assertTrue(models.models.value.single().verified, "the verified download vouches for the recovered file")
        assertEquals(listOf("tiny-q4_k_m.gguf"), ggufFiles(g))
    }

    @Test
    fun reasonsAreScrubbed() {
        assertEquals("IllegalArgumentException: bad (path)", GgufErrorText.scrub(ExceptionInInitializerError(IllegalArgumentException("bad /data/user/0/dev.locus/files/x"))))
        assertEquals("IOException: see (address)", GgufErrorText.scrub(java.io.IOException("see https://cdn.example/sig?x=1")))
    }
}
