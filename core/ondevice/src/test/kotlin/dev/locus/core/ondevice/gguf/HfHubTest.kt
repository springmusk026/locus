package dev.locus.core.ondevice.gguf

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Hub API's answers, recorded from huggingface.co on 2026-11-03 (src/test/resources/hf), and link parsing. */
class HfHubTest {
    private fun fixture(name: String) = HfHubTest::class.java.getResourceAsStream("/hf/$name")!!.readBytes().decodeToString()

    @Test
    fun parsesSearchResults() {
        val repos = HfHub.parseSearch(fixture("search.json"))
        assertTrue(repos.isNotEmpty())
        val first = repos.first()
        assertEquals("unsloth/Qwen3.5-2B-GGUF", first.id)
        assertEquals("apache-2.0", first.license)
        assertFalse(first.gated)
        assertTrue(first.downloads > 0)
    }

    @Test
    fun parsesRepoInfoAndGating() {
        val info = HfHub.parseRepo(fixture("repo.json"))
        assertEquals("unsloth/Qwen3.5-2B-GGUF", info.id)
        assertEquals("f6d5376be1edb4d416d56da11e5397a961aca8ae", info.sha)
        assertFalse(info.gated)
        assertEquals("apache-2.0", info.license)
        val gated = HfHub.parseRepo(fixture("repo-gated.json"))
        assertTrue(gated.gated, "\"gated\": \"manual\" is gated")
    }

    @Test
    fun parsesTheRevision() {
        assertEquals("f6d5376be1edb4d416d56da11e5397a961aca8ae", HfHub.parseRevision(fixture("revision.json")))
        assertFailsWith<HfError.Malformed> { HfHub.parseRevision("""{"sha":"main"}""") }
    }

    @Test
    fun treeGivesSizeAndLfsSha256ForGgufFilesOnly() {
        val files = HfHub.parseTree(fixture("tree.json"))
        assertTrue(files.all { it.path.endsWith(".gguf") })
        val q4 = files.first { it.path == "Qwen3.5-2B-Q4_K_M.gguf" }
        assertEquals(1_280_835_840, q4.sizeBytes)
        assertEquals("aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223", q4.sha256)
        assertEquals("Q4_K_M", q4.quant)
        assertTrue(q4.usable)
        val projector = files.first { it.fileName.startsWith("mmproj") }
        assertEquals(HfFileKind.Projector, projector.kind)
        assertFalse(projector.usable)
        // Usable files first, smallest first.
        val usable = files.takeWhile { it.usable }
        assertEquals(usable.sortedBy { it.sizeBytes }, usable)
    }

    @Test
    fun splitAndDraftFilesAreLabelled() {
        val files = HfHub.parseTree(fixture("tree-splits.json"))
        val split = files.first { it.path.endsWith("-00001-of-00002.gguf") }
        assertEquals(HfFileKind.Split(1, 2), split.kind)
        assertFalse(split.usable)
        assertEquals(HfFileKind.Draft, files.first { it.path.startsWith("MTP/") }.kind)
        assertEquals(HfFileKind.Other, HfHub.classify("imatrix_unsloth.gguf"))
        assertEquals(HfFileKind.Model, HfHub.classify("model-00001-of-00001.gguf"))
    }

    @Test
    fun normalisesHuggingFaceFileLinks() {
        val sha = "f6d5376be1edb4d416d56da11e5397a961aca8ae"
        assertEquals(
            HfFileRef("unsloth/Qwen3.5-2B-GGUF", "main", "Qwen3.5-2B-Q4_K_M.gguf"),
            HfHub.parseFileUrl("https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/main/Qwen3.5-2B-Q4_K_M.gguf?download=true"),
        )
        assertEquals(
            HfFileRef("unsloth/Qwen3.5-2B-GGUF", sha, "Qwen3.5-2B-Q4_K_M.gguf"),
            HfHub.parseFileUrl("https://hf.co/unsloth/Qwen3.5-2B-GGUF/blob/$sha/Qwen3.5-2B-Q4_K_M.gguf"),
        )
        // A pull-request revision, URL-encoded, and a file in a folder.
        assertEquals(
            HfFileRef("org/repo", "refs/pr/3", "Q4/model q4.gguf"),
            HfHub.parseFileUrl("https://huggingface.co/org/repo/resolve/refs%2Fpr%2F3/Q4/model%20q4.gguf"),
        )
        assertTrue(HfFileRef("a/b", sha, "x.gguf").pinned)
        assertFalse(HfFileRef("a/b", "main", "x.gguf").pinned)
        assertEquals("https://huggingface.co/org/repo/resolve/refs%2Fpr%2F3/Q4/model%20q4.gguf", HfFileRef("org/repo", "refs/pr/3", "Q4/model q4.gguf").resolveUrl)
    }

    @Test
    fun refusesWhatIsNotAHubFileLink() {
        listOf(
            "http://huggingface.co/a/b/resolve/main/x.gguf", // plain http
            "https://huggingface.co.evil.example/a/b/resolve/main/x.gguf",
            "https://evil.example/huggingface.co/a/b/resolve/main/x.gguf",
            "https://huggingface.co/a/b", // a repo page
            "https://huggingface.co/datasets/a/b/resolve/main/x.gguf",
            "https://huggingface.co/a/b/tree/main/x.gguf",
            "https://huggingface.co/a/b/resolve/main/../../c/d/x.gguf",
            "https://huggingface.co/a/b/resolve/main/%2e%2e/x.gguf",
            "https://huggingface.co:8443/a/b/resolve/main/x.gguf",
            "not a url",
        ).forEach { assertNull(HfHub.parseFileUrl(it), it) }
    }

    @Test
    fun readsTheWaitAfterA429() {
        assertEquals(55, HfHub.retryAfterSeconds("\"api\";r=0;t=55", null))
        assertEquals(30, HfHub.retryAfterSeconds(null, "30"))
        assertNull(HfHub.retryAfterSeconds(null, null))
        assertNotNull(HfError.RateLimited(120).message?.takeIf { "2 min" in it })
    }

    @Test
    fun searchUrlAsksForGgufReposOnly() {
        val url = HfHub.searchUrl("qwen 2b", HfHub.Sort.Trending)
        assertTrue(url.startsWith("https://huggingface.co/api/models?search=qwen%202b&filter=gguf&sort=trendingScore&direction=-1"), url)
    }
}
