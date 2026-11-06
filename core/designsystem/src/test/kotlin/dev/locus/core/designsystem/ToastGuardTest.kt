package dev.locus.core.designsystem

import java.io.File
import org.junit.Test
import kotlin.test.assertTrue

/**
 * Transient messages go through [LocusFeedback]'s snackbar host (docs/design/design-system.md, "Feedback").
 * A Toast is only for a message that fires as Locus goes to the background, through [toastFeedback] with
 * the reason on the line (`// toast: <reason>`). Only Feedback.kt may build a Toast itself.
 */
class ToastGuardTest {

    @Test
    fun noToastOutsideFeedback() {
        val root = findRoot()
        val main = "${File.separator}src${File.separator}main${File.separator}"
        val offenders = root.walkTopDown()
            .onEnter { it.name != "build" && !it.name.startsWith(".") }
            .filter { it.extension == "kt" && it.path.contains(main) && it.name != "Feedback.kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { i, line ->
                    val toast = TOAST.containsMatchIn(line)
                    val unexplained = FALLBACK.containsMatchIn(line) && !REASON.containsMatchIn(line)
                    if (toast || unexplained) "${file.relativeTo(root)}:${i + 1}: ${line.trim()}" else null
                }
            }
            .toList()
        assertTrue(
            offenders.isEmpty(),
            "Use Locus.feedback (or the screen's LocusFeedback passed down) instead of a Toast:\n" +
                offenders.joinToString("\n") { "  $it" } +
                "\nA message that fires as Locus goes to the background may use toastFeedback(context) with `// toast: <reason>`.",
        )
    }

    private fun findRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return dir ?: error("no settings.gradle.kts above ${File("").absolutePath}")
    }

    private companion object {
        val TOAST = Regex("""\bToast\.makeText\s*\(|import android\.widget\.Toast\b""")
        val FALLBACK = Regex("""\btoastFeedback\s*\(""")
        val REASON = Regex("""//\s*toast:\s*\S""")
    }
}
