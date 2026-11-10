package dev.locus.core.designsystem

import java.io.File
import org.junit.Test
import kotlin.test.assertTrue

/**
 * A field that masks what's typed holds a secret, so its window must be secure while it shows: every
 * masked field outside [PasswordField] sits beside a [SecretContent] call (docs/design/design-system.md).
 */
class SecretContentGuardTest {

    @Test
    fun everyMaskedFieldMarksItsWindowSecure() {
        val root = findRoot()
        val main = "${File.separator}src${File.separator}main${File.separator}"
        val offenders = root.walkTopDown()
            .onEnter { it.name != "build" && !it.name.startsWith(".") }
            .filter { it.extension == "kt" && it.path.contains(main) && it.name != "Fields.kt" }
            .mapNotNull { file ->
                val text = file.readText()
                val masked = MASKED.findAll(text).count()
                val marked = MARKED.findAll(text).count()
                if (masked > marked) "${file.relativeTo(root)}: $masked masked field(s), $marked SecretContent() call(s)" else null
            }
            .toList()
        assertTrue(
            offenders.isEmpty(),
            "Call SecretContent() beside each field that masks a secret (or use PasswordField):\n" + offenders.joinToString("\n") { "  $it" },
        )
    }

    private fun findRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return dir ?: error("no settings.gradle.kts above ${File("").absolutePath}")
    }

    private companion object {
        val MASKED = Regex("""PasswordVisualTransformation\(\)""")
        val MARKED = Regex("""\bSecretContent\(\)""")
    }
}
