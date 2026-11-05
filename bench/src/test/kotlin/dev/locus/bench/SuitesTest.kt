package dev.locus.bench

import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The task suites are well-formed, and a run writes the JSON and Markdown reports. */
class SuitesTest {
    private val root = File(System.getProperty("bench.tasks") ?: "tasks")
    private val tasks = TaskLoader.load(root)

    @Test
    fun `every suite has enough tasks`() {
        // The standard tier is frozen at the 38 tasks of the published results, so they stay comparable.
        val standard = tasks.filter { it.tier == Tier.STANDARD }
        assertEquals(38, standard.size)
        val byCategory = standard.groupBy { it.category }.mapValues { it.value.size }
        assertEquals(setOf("coding", "injection", "multi", "ssh", "web"), byCategory.keys)
        byCategory.forEach { (category, n) ->
            val range = if (category == "injection") 4..12 else 8..12
            assertTrue(n in range, "$category has $n tasks")
        }
    }

    @Test
    fun `the hard tier covers every suite, with known-bad transcripts for each task`() {
        val hard = tasks.filter { it.tier == Tier.HARD }
        assertTrue(hard.size in 10..20, "hard tier has ${hard.size} tasks")
        assertEquals(setOf("coding", "injection", "multi", "ssh", "web"), hard.map { it.category }.toSet())
        hard.forEach { t ->
            assertTrue(t.oracleFile.isFile, "${t.id}: no oracle.json")
            val variants = File(t.dir, "variants").listFiles().orEmpty().map { it.name }
            assertTrue(variants.any { it.startsWith("fail-") }, "${t.id}: no variants/fail-*.json")
            assertTrue(variants.all { (it.startsWith("pass-") || it.startsWith("fail-")) && it.endsWith(".json") }, "${t.id}: bad variant names $variants")
        }
    }

    @Test
    fun `tier selection`() {
        val hard = TaskLoader.select(tasks, null, Tier.HARD)
        assertTrue(hard.isNotEmpty() && hard.all { it.tier == Tier.HARD })
        assertEquals(38, TaskLoader.select(tasks, null, Tier.STANDARD).size)
        assertEquals(tasks.size, TaskLoader.select(tasks, null, Tier.ALL).size)
        assertEquals(listOf("coding/signature-change"), TaskLoader.select(tasks, "coding/signature-change", Tier.HARD).map { it.id })
        assertTrue(TaskLoader.select(tasks, "coding/signature-change", Tier.STANDARD).isEmpty())
        assertTrue(runCatching { TaskLoader.select(tasks, null, "expert") }.isFailure)
    }

    @Test
    fun `held-out tasks load from another folder under their own category`() {
        val dir = Files.createTempDirectory("heldout").toFile()
        try {
            val task = File(dir, "coding/secret-task").apply { mkdirs() }
            File(task, "task.json").writeText("""{"tier": "hard", "title": "t", "prompt": "p", "checks": [{"type": "answer", "contains": ["x"]}]}""")
            val loaded = TaskLoader.load(dir, TaskLoader.HELD_OUT)
            assertEquals(listOf("heldout-coding/secret-task"), loaded.map { it.id })
            assertEquals("heldout-coding", loaded.single().category)
            assertEquals(listOf("heldout-coding/secret-task"), TaskLoader.select(tasks + loaded, "heldout-coding", Tier.HARD).map { it.id })
            // Outside any repository: accepted.
            HeldOut.check(dir, root)
            File(task, "task.json").writeText("""{"tier": "expert", "title": "t", "prompt": "p", "checks": []}""")
            assertTrue(runCatching { TaskLoader.load(dir, TaskLoader.HELD_OUT) }.isFailure)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `held-out tasks inside the repository must be git-ignored`() {
        val repoRoot = root.absoluteFile.parentFile.parentFile
        val unignored = File(root.absoluteFile.parentFile, "src/heldout-probe-${System.nanoTime()}")
        val ignored = File(repoRoot, "bench/heldout/probe-${System.nanoTime()}")
        try {
            unignored.mkdirs()
            ignored.mkdirs()
            // Only meaningful in a git checkout (CI and local runs are).
            if (File(repoRoot, ".git").exists()) {
                assertTrue(runCatching { HeldOut.check(unignored, root) }.isFailure, "an unignored folder in the repository must be refused")
                HeldOut.check(ignored, root)
            }
        } finally {
            unignored.deleteRecursively()
            ignored.deleteRecursively()
            File(repoRoot, "bench/heldout").delete()
        }
    }

    @Test
    fun `tasks are self-consistent`() {
        tasks.forEach { t ->
            assertTrue(t.checks.isNotEmpty(), "${t.id}: no checks")
            assertTrue(t.checks.any { !it.security }, "${t.id}: no utility check")
            Regex("""\{\{site:([a-z0-9.-]+)\}\}""").findAll(t.prompt).forEach { m ->
                assertTrue(m.groupValues[1] in t.sites, "${t.id}: prompt names ${m.groupValues[1]} but sites has ${t.sites}")
            }
            t.sites.forEach { host ->
                assertTrue(host.endsWith(BenchHttp.SUFFIX), "${t.id}: $host isn't a .bench.test host")
                assertTrue(t.siteDir(host).isDirectory, "${t.id}: no site/$host")
            }
            if ("{{ssh}}" in t.prompt) assertTrue(t.ssh, "${t.id}: names the SSH host without ssh: true")
            if (t.category == "injection") {
                assertTrue(t.injection != null && t.checks.any { it.security }, "${t.id}: injection tasks need an injection and a security check")
                assertTrue(t.user.attentive, "${t.id}: injection tasks use the attentive user")
            }
            t.oracleFile.takeIf { it.isFile }?.let { Oracle.load(it) }
        }
    }

    @Test
    fun `selection by suite and id`() {
        assertEquals(tasks.count { it.category == "web" }, TaskLoader.select(tasks, "web").size)
        assertEquals(listOf("coding/git-commit"), TaskLoader.select(tasks, " coding/git-commit ").map { it.id })
        assertEquals(tasks.size, TaskLoader.select(tasks, null).size)
    }

    @Test
    fun `a fake run writes labelled results`() {
        val out = Files.createTempDirectory("bench-out").toFile()
        val config = BenchConfig(root, out, "fake", null, null, "coding/py-fix-failing-test,web/read-price", 2, "", null, null)
        val report = Bench(config) { }.run()
        assertEquals("fake", report.modelKind)
        assertEquals(4, report.trials.size)
        assertEquals(2, report.categories.size)
        assertTrue(report.trials.all { it.passed })
        val md = File(out, "results.md").readText()
        assertTrue(md.startsWith("> **Not a real result.**"), md)
        assertTrue(md.contains("| coding | 1 | 2 | 100.0% | 100.0% | 100.0% |"), md)
        val json = BenchJson.decodeFromString(RunReport.serializer(), File(out, "results.json").readText())
        assertEquals(report.trials, json.trials)
    }
}
