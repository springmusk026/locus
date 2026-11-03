package dev.locus.core.designsystem

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesignGuardRulesTest {

    @Test
    fun `raw colours and dark checks are found, comments are not`() {
        val code = """
            val a = Color(0xFF112233)
            val b = Color( 0x80FFFFFF )
            // Color(0xFF000000) in a comment is fine
            val dark = isSystemInDarkTheme()
            val alsoDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
        """.trimIndent()
        val counts = DesignGuard.countText(code)
        assertEquals(2, counts["raw-colour"])
        assertEquals(2, counts["system-dark-check"])
    }

    @Test
    fun `raw dp and sp count everything but zero, and token arithmetic is not a literal`() {
        val code = """
            Modifier.padding(0.dp).size(36.dp).height(0.5.dp)
            val x = Spacing.lg + Spacing.xs
            val y = 10.dp
            val z = TextStyle(fontSize = 12.sp, lineHeight = 0.sp)
        """.trimIndent()
        assertEquals(4, DesignGuard.countText(code)["raw-dp"])
    }

    @Test
    fun `alpha literals and named colours are counted`() {
        val code = "color.copy(alpha = 0.6f); tint = Color.White; x.copy(alpha = Emphasis.muted); Color.Transparent"
        val counts = DesignGuard.countText(code)
        assertEquals(1, counts["alpha-literal"])
        assertEquals(1, counts["named-colour"])
    }

    @Test
    fun `material components with a Locus counterpart are counted, the Locus ones are not`() {
        val code = """
            AlertDialog(onDismissRequest = {}, confirmButton = {})
            BasicAlertDialog(onDismissRequest = {}) {}
            Switch(checked = on, onCheckedChange = set)
            SwitchRow(title = "On", checked = on, onCheckedChange = set)
            TopAppBar(title = {})
            CenterAlignedTopAppBar(title = {})
            LocusTopBar(title = "Tasks")
            SnackbarHost(hostState)
            Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
            LinearProgressIndicator(progress = { 0.5f })
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            FilterChip(selected = true, onClick = {}, label = {})
            AssistChip(onClick = {}, label = {})
            LocusChip("Done", tone = Tone.Live)
            RoundedCornerShape(50)
            Modifier.clip(RoundedCornerShape(topStart = 12.dp))
            Modifier.clip(MaterialTheme.shapes.medium)
        """.trimIndent()
        val counts = DesignGuard.countText(code)
        assertEquals(2, counts["material-dialog"])
        assertEquals(1, counts["material-switch"])
        assertEquals(2, counts["material-top-bar"])
        assertEquals(2, counts["material-feedback"])
        assertEquals(2, counts["material-progress"])
        assertEquals(2, counts["material-chip"])
        assertEquals(2, counts["raw-shape"])
    }

    @Test
    fun `type slots with a LocusType role and hand-rolled muted text are counted`() {
        val code = """
            Text(t, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(t, style = LocusType.supporting)
            Text(t, style = MaterialTheme.typography.headlineSmall)
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = Emphasis.muted))
            SupportingText(t)
        """.trimIndent()
        val counts = DesignGuard.countText(code)
        assertEquals(1, counts["material-type"])
        assertEquals(2, counts["muted-text"])
    }

    @Test
    fun `a design exception with a reason excuses its line or the next one, without a reason it excuses nothing`() {
        val code = """
            val paper = Color.White // design-exception: PDF paper is white in both themes
            // design-exception: the composer's shadow is a real shadow
            val shadow = Color.Black
            val stray = Color.White // design-exception:
            // design-exception:
            val alsoStray = Color.Black
            val afterwards = Color.White
        """.trimIndent()
        assertEquals(3, DesignGuard.countText(code)["named-colour"])
    }

    @Test
    fun `only growth past the baseline is a regression, and an unlisted file must be clean`() {
        val a = "raw-dp" to "feature/a/src/main/A.kt"
        val b = "raw-dp" to "feature/b/src/main/B.kt"
        val baseline = DesignGuard.parseBaseline(DesignGuard.formatBaseline(mapOf(a to 2)))
        assertEquals(2, baseline[a])
        assertTrue(DesignGuard.regressions(mapOf(a to 1), baseline).isEmpty())
        assertTrue(DesignGuard.regressions(mapOf(a to 2), baseline).isEmpty())
        assertEquals(1, DesignGuard.regressions(mapOf(a to 3), baseline).size)
        assertEquals(1, DesignGuard.regressions(mapOf(b to 1), baseline).size)
        assertNull(baseline[b])
    }

    @Test
    fun `the failure names the fix for each rule that regressed`() {
        val problems = DesignGuard.regressions(
            mapOf(("raw-dp" to "feature/a/src/main/A.kt") to 1, ("material-dialog" to "feature/a/src/main/A.kt") to 1),
            emptyMap(),
        )
        val hints = DesignGuard.hints(problems)
        assertTrue("raw-dp → use Spacing" in hints, hints)
        assertTrue("material-dialog → use ConfirmSheet" in hints, hints)
    }

    @Test
    fun `a baseline above the code is reported as slack, not a failure`() {
        val a = "raw-dp" to "feature/a/src/main/A.kt"
        val baseline = mapOf(a to 5)
        assertTrue(DesignGuard.regressions(mapOf(a to 3), baseline).isEmpty())
        assertTrue("A.kt: raw-dp 3 (baseline 5)" in DesignGuard.formatSlack(mapOf(a to 3), baseline))
        assertEquals("", DesignGuard.formatSlack(mapOf(a to 5), baseline))
    }
}
