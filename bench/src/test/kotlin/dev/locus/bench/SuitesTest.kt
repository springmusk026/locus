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
        val byCategory = tasks.groupBy { it.category }.mapValues { it.value.size }
        assertEquals(setOf("coding", "injection", "multi", "ssh", "web"), byCategory.keys)
        byCategory.forEach { (category, n) ->
            val range = if (category == "injection") 4..12 else 8..12
            assertTrue(n in range, "$category has $n tasks")
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
