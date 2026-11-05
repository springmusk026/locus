package dev.locus.core.ondevice.gguf

import dev.locus.core.ondevice.DeviceMemory
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The three sources turned into offers, against recorded Hub answers (src/test/resources/hf). */
class GgufSourcesTest {
    private val gib = 1024L * 1024 * 1024
    private val phone = DeviceMemory(8 * gib, null, 4 * gib, false)
    private val commit = "f6d5376be1edb4d416d56da11e5397a961aca8ae"

    private fun fixture(name: String) = GgufSourcesTest::class.java.getResourceAsStream("/hf/$name")!!.readBytes()

    /** A GGUF header for a small qwen35 model whose template takes tools. */
    private val header: ByteArray = run {
        val o = ByteArrayOutputStream()
        fun u32(v: Long) = o.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v.toInt()).array())
        fun u64(v: Long) = o.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array())
        fun str(s: String) = s.toByteArray().let { u64(it.size.toLong()); o.write(it) }
        val kvs = listOf<Pair<String, Any>>(
            "general.architecture" to "qwen35", "general.name" to "Qwen3.5 2B", "general.size_label" to "2B",
            "qwen35.context_length" to 262_144L, "qwen35.block_count" to 24L, "qwen35.embedding_length" to 2048L,
            "qwen35.attention.head_count" to 8L, "qwen35.attention.head_count_kv" to 2L, "general.file_type" to 15L,
            "tokenizer.chat_template" to "{% if tools %}{{ tools | tojson }}{% endif %}",
        )
        u32(GgufReader.MAGIC.toLong()); u32(3); u64(0); u64(kvs.size.toLong())
        kvs.forEach { (k, v) ->
            str(k)
            when (v) {
                is String -> { u32(8); str(v) }
                is Long -> { u32(4); u32(v) }
            }
        }
        o.toByteArray()
    }

    private lateinit var network: FakeModelNetwork
    private val requests get() = network.requests

    private fun sources(handler: (SeenRequest) -> Pair<Int, ByteArray>, headers: Map<String, String> = emptyMap()): GgufSources {
        network = FakeModelNetwork { req -> handler(req).let { (status, body) -> FakeResponse(status, body, headers) } }
        return GgufSources(ModelHttp(network))
    }

    /** The Hub as recorded: repo info, revision, tree, and the file's header. */
    private fun hub(req: SeenRequest): Pair<Int, ByteArray> {
        val path = req.url.encodedPath
        return when {
            path == "/api/models/unsloth/Qwen3.5-2B-GGUF" -> 200 to fixture("repo.json")
            path.startsWith("/api/models/unsloth/Qwen3.5-2B-GGUF/revision/") -> 200 to fixture("revision.json")
            path.startsWith("/api/models/unsloth/Qwen3.5-2B-GGUF/tree/$commit") -> 200 to fixture("tree.json")
            path.startsWith("/unsloth/Qwen3.5-2B-GGUF/resolve/$commit/") -> 200 to header
            else -> 404 to ByteArray(0)
        }
    }

    @Test
    fun aPastedHubLinkIsPinnedAndVerified() = runTest {
        val offer = sources(::hub).offer("https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/blob/main/Qwen3.5-2B-Q4_K_M.gguf", phone)
        assertTrue(offer.verified)
        assertEquals("aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223", offer.sha256)
        assertEquals(1_280_835_840, offer.sizeBytes)
        assertEquals("https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/$commit/Qwen3.5-2B-Q4_K_M.gguf", offer.url)
        assertEquals("unsloth/Qwen3.5-2B-GGUF@f6d5376", offer.source)
        assertEquals("apache-2.0", offer.license)
        assertEquals(true, offer.tools)
        assertEquals("qwen35", offer.architecture)
        assertNull(offer.problem)
        assertTrue((offer.ramBytes ?: 0) > offer.sizeBytes!!)
        assertTrue(requests.all { it.url.host == "huggingface.co" && "Authorization" !in it.headers })
    }

    @Test
    fun aSplitPartOrProjectorIsRefused() = runTest {
        val e = assertFailsWith<DownloadException> {
            sources(::hub).offer("https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/$commit/mmproj-F16.gguf", phone)
        }
        assertTrue("projector" in e.message.orEmpty())
        assertTrue(GgufSources.kindProblem(HfFileKind.Split(1, 3))!!.contains("split into 3 files"))
    }

    @Test
    fun aGatedRepoIsRefusedBeforeDownloading() = runTest {
        val s = sources({ 200 to fixture("repo-gated.json") })
        assertFailsWith<HfError.Gated> { s.offer(HfFileRef("meta-llama/Llama-3.2-1B-Instruct", "main", "x.gguf"), phone) }
    }

    @Test
    fun rateLimitIsReported() = runTest {
        val s = sources({ 429 to ByteArray(0) }, mapOf("RateLimit" to "\"api\";r=0;t=42"))
        val e = assertFailsWith<HfError.RateLimited> { s.search("qwen", HfHub.Sort.Downloads) }
        assertEquals(42, e.retryAfterSeconds)
    }

    @Test
    fun searchParsesResults() = runTest {
        val repos = sources({ 200 to fixture("search.json") }).search("qwen3.5 2b", HfHub.Sort.Downloads)
        assertEquals("unsloth/Qwen3.5-2B-GGUF", repos.first().id)
        assertTrue(requests.single().url.toString().contains("filter=gguf"))
    }

    @Test
    fun anotherHttpsHostIsUnverifiedButItsHeaderIsRead() = runTest {
        val offer = sources({ 200 to header }).offer("https://files.example/models/my-model-Q4_K_M.gguf", phone)
        assertFalse(offer.verified)
        assertEquals("files.example", offer.source)
        assertEquals(true, offer.tools)
    }

    @Test
    fun plainHttpAndPrivateAddressesAreRefused() = runTest {
        val s = sources({ 200 to header })
        listOf("http://files.example/m.gguf", "https://192.168.1.1/m.gguf", "https://localhost/m.gguf", "ftp://x/m.gguf", "m.gguf").forEach { url ->
            assertFailsWith<DownloadException>(url) { s.offer(url, phone) }
        }
        assertTrue(!::network.isInitialized || requests.isEmpty(), "nothing was requested")
    }

    @Test
    fun aFileThatIsNotGgufIsRefused() = runTest {
        val e = assertFailsWith<DownloadException> {
            sources({ 200 to "<html>login</html>".toByteArray() }).offer("https://files.example/m.gguf", phone)
        }
        assertTrue("GGUF" in e.message.orEmpty())
    }

    @Test
    fun aDownloadSurvivesARestart() {
        val offer = sources(::hub).offer(GgufCatalog.models.first(), phone)
        assertEquals(offer, GgufOfferJson.decode(GgufOfferJson.encode(offer)))
        assertTrue(offer.verified)
        // Only https downloads are ever read back.
        assertNull(GgufOfferJson.decode(GgufOfferJson.encode(offer.copy(url = "http://x/m.gguf"))))
        val id = GgufDownloads.idFor(offer)
        assertTrue(Regex("^[a-z0-9._-]+-[0-9a-f]{8}$").matches(id), id)
    }
}
