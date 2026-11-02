package dev.locus.core.designsystem

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesignGuardRulesTest {
    private val all = DesignGuard.enforced + DesignGuard.reportOnly

    @Test
    fun `raw colours and dark checks are found, comments are not`() {
        val code = """
            val a = Color(0xFF112233)
            val b = Color( 0x80FFFFFF )
            // Color(0xFF000000) in a comment is fine
            val dark = isSystemInDarkTheme()
            val alsoDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
        """.trimIndent()
        val counts = DesignGuard.countText(code, DesignGuard.enforced)
        assertEquals(2, counts["raw-colour"])
        assertEquals(2, counts["system-dark-check"])
    }

    @Test
    fun `raw dp counts everything but zero, and token arithmetic is not a literal`() {
        val code = """
            Modifier.padding(0.dp).size(36.dp).height(0.5.dp)
            val x = Spacing.lg + Spacing.xs
            val y = 10.dp
        """.trimIndent()
        assertEquals(3, DesignGuard.countText(code, all)["raw-dp"])
    }

    @Test
    fun `alpha literals and named colours are counted`() {
        val code = "color.copy(alpha = 0.6f); tint = Color.White; x.copy(alpha = Emphasis.muted)"
        val counts = DesignGuard.countText(code, all)
        assertEquals(1, counts["alpha-literal"])
        assertEquals(1, counts["named-colour"])
    }

    @Test
    fun `only growth past the baseline is a regression`() {
        val baseline = DesignGuard.parseBaseline(
            DesignGuard.formatBaseline(mapOf(("raw-colour" to "feature/a/src/main/A.kt") to 2)),
        )
        assertEquals(2, baseline["raw-colour" to "feature/a/src/main/A.kt"])
        assertTrue(DesignGuard.regressions(mapOf(("raw-colour" to "feature/a/src/main/A.kt") to 1), baseline).isEmpty())
        assertTrue(DesignGuard.regressions(mapOf(("raw-colour" to "feature/a/src/main/A.kt") to 2), baseline).isEmpty())
        assertEquals(1, DesignGuard.regressions(mapOf(("raw-colour" to "feature/a/src/main/A.kt") to 3), baseline).size)
        assertEquals(1, DesignGuard.regressions(mapOf(("raw-colour" to "feature/b/src/main/B.kt") to 1), baseline).size)
    }
}
