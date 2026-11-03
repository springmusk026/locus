package dev.locus.core.ondevice.gguf

import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Model downloads against a scripted server: resume, checksums, redirects and the private-address refusal. */
class GgufTransferTest {
    @get:Rule val tmp = TemporaryFolder()

    private val content = ByteArray(3_000_000) { (it * 31 + 7).toByte() }
    private val sha = sha(content)
    private val url = "https://huggingface.co/org/repo/resolve/0123456789abcdef0123456789abcdef01234567/model-Q4_K_M.gguf"
    private lateinit var network: FakeModelNetwork
    private val requests get() = network.requests

    /** Serves [body], honouring a Range header with a 206 as the Hub's CDN does. */
    private fun serve(req: SeenRequest, body: ByteArray = content): FakeResponse {
        val range = req.headers["Range"] ?: return FakeResponse(200, body, mapOf("Content-Length" to "${body.size}"))
        val from = range.removePrefix("bytes=").removeSuffix("-").toInt()
        return FakeResponse(
            206,
            body.copyOfRange(from, body.size),
            mapOf("Content-Range" to "bytes $from-${body.size - 1}/${body.size}", "Content-Length" to "${body.size - from}"),
        )
    }

    private fun transfer(handler: (SeenRequest) -> FakeResponse): GgufTransfer {
        network = FakeModelNetwork(handler)
        return GgufTransfer(ModelHttp(network))
    }

    private val target = GgufTransfer.Target(url, content.size.toLong(), sha)
    private val plenty = { 100L * 1024 * 1024 * 1024 }
    private fun part() = File(tmp.root, "m.part")

    @Test
    fun downloadsAndChecksTheSha256() = runTest {
        val part = part()
        assertEquals(sha, transfer { serve(it) }.download(target, part, plenty) { _, _ -> })
        assertTrue(content.contentEquals(part.readBytes()))
        val req = requests.single()
        assertNull(req.headers["Range"])
        assertEquals(ModelHttp.USER_AGENT, req.headers["User-Agent"])
        assertEquals(setOf("User-Agent", "Accept-Encoding"), req.headers.keys, "no token, cookie or anything else is sent")
    }

    @Test
    fun resumesWithARangeRequest() = runTest {
        val part = part().apply { writeBytes(content.copyOfRange(0, 1_234_567)) }
        val progress = mutableListOf<Long>()
        assertEquals(sha, transfer { serve(it) }.download(target, part, plenty) { b, _ -> progress += b })
        assertEquals("bytes=1234567-", requests.single().headers["Range"])
        assertEquals(1_234_567, progress.first())
        assertEquals(content.size.toLong(), progress.last())
        assertTrue(content.contentEquals(part.readBytes()))
    }

    @Test
    fun aServerThatIgnoresTheRangeStartsOver() = runTest {
        val part = part().apply { writeBytes(ByteArray(1000) { 9 }) }
        assertEquals(sha, transfer { FakeResponse(200, content, mapOf("Content-Length" to "${content.size}")) }.download(target, part, plenty) { _, _ -> })
        assertTrue(content.contentEquals(part.readBytes()))
    }

    @Test
    fun aResumeAtTheWrongOffsetIsNotSpliced() = runTest {
        val part = part().apply { writeBytes(content.copyOfRange(0, 1000)) }
        assertFailsWith<DownloadException> {
            transfer { FakeResponse(206, content.copyOfRange(10, content.size), mapOf("Content-Range" to "bytes 10-${content.size - 1}/${content.size}")) }
                .download(target, part, plenty) { _, _ -> }
        }
        assertFalse(part.exists(), "the partial file is dropped, the next try starts over")
    }

    @Test
    fun aChecksumMismatchDeletesTheFile() = runTest {
        val part = part()
        val tampered = content.copyOf().also { it[100] = (it[100] + 1).toByte() }
        val e = assertFailsWith<DownloadException> { transfer { serve(it, tampered) }.download(target, part, plenty) { _, _ -> } }
        assertTrue("checksum" in e.message.orEmpty())
        assertFalse(part.exists(), "the mismatching file is deleted")
    }

    @Test
    fun aFileOfAnotherSizeIsRefused() = runTest {
        assertFailsWith<DownloadException> { transfer { serve(it, content.copyOf(content.size + 10)) }.download(target, part(), plenty) { _, _ -> } }
    }

    @Test
    fun followsAnHttpsRedirectToTheCdn() = runTest {
        val got = transfer { req ->
            if (req.url.host == "huggingface.co") FakeResponse(302, headers = mapOf("Location" to "https://cdn.example/file?sig=abc")) else serve(req)
        }.download(target, part(), plenty) { _, _ -> }
        assertEquals(sha, got)
        assertEquals(listOf("huggingface.co", "cdn.example"), requests.map { it.url.host })
    }

    @Test
    fun aRedirectToTheLanIsRefused() = runTest {
        val e = assertFailsWith<DownloadException> {
            transfer { FakeResponse(302, headers = mapOf("Location" to "https://192.168.1.1/admin")) }.download(target, part(), plenty) { _, _ -> }
        }
        assertTrue(e.message.orEmpty().startsWith("Blocked"), e.message)
        assertFalse(e.retryable)
        assertEquals(1, requests.size, "the LAN address is never requested")
    }

    @Test
    fun aDowngradeToHttpIsRefused() = runTest {
        assertFailsWith<DownloadException> {
            transfer { FakeResponse(302, headers = mapOf("Location" to "http://cdn.example/file")) }.download(target, part(), plenty) { _, _ -> }
        }
        assertEquals(1, requests.size)
    }

    @Test
    fun endlessRedirectsStop() = runTest {
        assertFailsWith<DownloadException> {
            transfer { FakeResponse(302, headers = mapOf("Location" to "https://cdn.example/again")) }.download(target, part(), plenty) { _, _ -> }
        }
        assertEquals(ModelHttp.MAX_REDIRECTS + 1, requests.size)
    }

    @Test
    fun aLinkAtThePartNameIsNeverWrittenThrough() = runTest {
        val outside = File(tmp.root, "outside.txt").apply { writeText("keep") }
        val part = part()
        Files.createSymbolicLink(part.toPath(), outside.toPath())
        transfer { serve(it) }.download(target, part, plenty) { _, _ -> }
        assertEquals("keep", outside.readText())
        assertFalse(Files.isSymbolicLink(part.toPath()))
    }

    @Test
    fun notEnoughStorageIsRefusedBeforeWriting() = runTest {
        val part = part()
        val e = assertFailsWith<DownloadException> { transfer { serve(it) }.download(target, part, { 1_000L }) { _, _ -> } }
        assertTrue("storage" in e.message.orEmpty())
        assertFalse(part.exists())
    }

    @Test
    fun aGatedFileSaysSo() = runTest {
        val e = assertFailsWith<DownloadException> {
            transfer { FakeResponse(401, headers = mapOf("X-Error-Code" to "GatedRepo")) }.download(target, part(), plenty) { _, _ -> }
        }
        assertTrue("gated" in e.message.orEmpty())
        assertFalse(e.retryable)
    }

    @Test
    fun anUnverifiedDownloadStillReportsItsSha256() = runTest {
        assertEquals(sha, transfer { serve(it) }.download(GgufTransfer.Target("https://files.example/m.gguf", null, null), part(), plenty) { _, _ -> })
    }

    @Test
    fun parsesContentRange() {
        assertEquals(100L to 1000L, GgufTransfer.parseContentRange("bytes 100-999/1000"))
        assertEquals(5L to null, GgufTransfer.parseContentRange("bytes 5-9/*"))
        assertEquals(null, GgufTransfer.parseContentRange("garbage"))
    }

    @Test
    fun rateAndTimeLeft() {
        val rate = TransferRate()
        rate.sample(0, 0, 10_000_000)
        val r = rate.sample(2_000_000_000L, 4_000_000, 10_000_000)
        assertEquals(2_000_000, r.bytesPerSecond)
        assertEquals(3, r.etaSeconds)
        val nb = GgufDownloadText.NBSP
        // One unit for both figures, and no figure split from its unit across lines.
        assertEquals("4$nb/${nb}10${nb}MB · 2.0${nb}MB/s · 3${nb}s${nb}left", GgufDownloadText.progress(r))
        assertEquals("0.8$nb/${nb}1.9${nb}GB", GgufDownloadText.amount(800_000_000, 1_915_305_312))
        assertEquals(null, GgufDownloadText.rate(null, null))
        assertEquals("Apache 2.0", Licences.name("apache-2.0"))
        assertEquals("Llama 3.2 licence", Licences.name("llama3.2"))
        assertEquals("some-new-licence", Licences.name("some-new-licence"))
        assertEquals(null, Licences.name(" "))
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
