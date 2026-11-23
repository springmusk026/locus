package dev.locus.core.agent.cachesim

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The developer's entry point: prices every checked-in sequence under the standard policy variants and prints the
 * comparison table (also written to `build/reports/cache-sim/<name>.md` in this module).
 *
 * To price a recording of your own, put its path in `LOCUS_CACHESIM_FILE` (a [SimSequence] as JSON: the bench writes
 * them, see docs/benchmarks/README.md) and run
 * `locus-gradle-lock.sh :core:agent:test --tests '*CacheSimReportTest*' --rerun -i`.
 */
class CacheSimReportTest {
    private val out = File("build/reports/cache-sim").apply { mkdirs() }

    private fun report(name: String, seq: SimSequence): String {
        val table = PolicyReport.table(seq)
        println(table)
        File(out, "$name.md").writeText(table)
        return table
    }

    @Test
    fun `prints a comparison table for every fixture`() {
        val names = listOf("opus-pauses", "haiku-server-compaction", "gpt-cold-clearing")
        for (name in names) {
            val seq = SimJson.decode(javaClass.getResource("/cachesim/$name.json")!!.readText())
            val table = report(name, seq)
            assertTrue("| Policy | Total |" in table, name)
            assertTrue("Check: the app's accounting priced the recorded usage at" in table, name)
            assertTrue(seq.recordedPolicy.label in table, name)
        }
    }

    @Test
    fun `prices a recording of your own when LOCUS_CACHESIM_FILE names one`() {
        val path = System.getenv("LOCUS_CACHESIM_FILE")?.takeIf { it.isNotBlank() } ?: return
        val seq = SimJson.decode(File(path).readText())
        val table = report(File(path).nameWithoutExtension, seq)
        assertTrue("| Policy | Total |" in table)
    }

    @Test
    fun `the variants skip what the provider can't do`() {
        val anthropic = SimJson.decode(javaClass.getResource("/cachesim/opus-pauses.json")!!.readText())
        val openai = SimJson.decode(javaClass.getResource("/cachesim/gpt-cold-clearing.json")!!.readText())
        val a = PolicyReport.variants(anthropic).map { it.label }
        val o = PolicyReport.variants(openai).map { it.label }
        assertTrue(a.any { "Conversation cache 5 min" in it } && a.none { "Cold clearing" in it }, a.toString())
        assertTrue(o.any { "Cold clearing off" in it } && o.none { "Conversation cache" in it }, o.toString())
        assertTrue(a.first().endsWith("(recorded)") && o.first().endsWith("(recorded)"))
    }
}
