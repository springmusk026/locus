package dev.locus.bench

import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Verifies the hard tier's graders without a model. Each hard task has, next to its oracle, scripted
 * transcripts in `variants/`: `pass-*.json` are other valid solutions the checks must accept (a grader
 * once wrongly required one sort order), and `fail-*.json` are realistic wrong ones they must reject
 * (guessing instead of asking, ordering twice, following an injection, hard-coding an output…). Each
 * is replayed by the scripted fake model through the real agent stack, then graded like a real trial.
 * The oracles themselves are replayed by [FakeRunTest], which also checks that no task passes when the
 * agent does nothing.
 */
class HardTierTest {
    private val tasks = TaskLoader.load(File(System.getProperty("bench.tasks") ?: "tasks")).filter { it.tier == Tier.HARD }

    @Test
    fun `every variant transcript is graded as expected`() {
        val problems = mutableListOf<String>()
        var replayed = 0
        tasks.forEach { task ->
            File(task.dir, "variants").listFiles().orEmpty().filter { it.extension == "json" }.sortedBy { it.name }.forEach { file ->
                val script = Oracle.load(file)!!
                val shouldPass = file.name.startsWith("pass-")
                // A wrong transcript runs with a user who approves everything, so what it does reaches the end
                // state and the graders (not Locus's taint prompts) have to catch it.
                val runner = TrialRunner(ModelSetup.FAKE, oracleOverride = { script }, userOverride = { if (shouldPass) it else it.copy(attentive = false) })
                val result = runBlocking { runner.run(task, 1) }
                replayed++
                // In the test report, so a reviewer can see each wrong transcript failed for the intended reason.
                println("${task.id} ${file.name}: ${if (result.passed) "passed" else result.failure}")
                when {
                    result.outcome == "harness_error" -> problems += "${task.id} ${file.name}: ${result.failure}"
                    shouldPass && !result.passed -> problems += "${task.id} ${file.name} should pass: ${result.failure}"
                    !shouldPass && result.passed -> problems += "${task.id} ${file.name} should fail but passed"
                }
            }
        }
        assertTrue(replayed >= tasks.size, "only $replayed variants")
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }
}
