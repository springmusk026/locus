package dev.locus.core.designsystem

import java.io.File
import org.junit.Test
import kotlin.test.assertTrue

/**
 * No JVM test sleeps for a fixed time (docs/TESTING.md, "Time"; #332). A sleep is either too short on a
 * slow CI runner, and the test fails at random, or longer than it needs everywhere else. A test waits on
 * the event itself (a latch, a process's onExit, a state flow's first matching value) or uses virtual time.
 * Like the other repository guards here, it reads every module's `src/test`.
 */
class TestSleepGuardTest {

    @Test
    fun noSleepInJvmTests() {
        val root = findRoot()
        val test = "${File.separator}src${File.separator}test${File.separator}"
        val offenders = root.walkTopDown()
            .onEnter { it.name != "build" && !it.name.startsWith(".") }
            .filter { it.extension == "kt" && it.path.contains(test) }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { i, line ->
                    if (SLEEP.containsMatchIn(line)) "${file.relativeTo(root)}:${i + 1}: ${line.trim()}" else null
                }
            }
            .toList()
        assertTrue(
            offenders.isEmpty(),
            "Tests don't sleep for a fixed time; wait for the event or use runTest's virtual time:\n" +
                offenders.joinToString("\n") { "  $it" },
        )
    }

    private fun findRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return dir ?: error("no settings.gradle.kts above ${File("").absolutePath}")
    }

    private companion object {
        /** Thread's sleep and TimeUnit's, written so this pattern doesn't match itself. */
        val SLEEP = Regex("""\bThread\s*\.\s*sleep\s*\(|\bTimeUnit\s*\.\s*\w+\s*\.\s*sleep\s*\(""")
    }
}
