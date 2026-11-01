package dev.locus.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A project's permissions overlay and its seal (docs/design/projects.md, "Permissions overlay"). */
class ProjectPolicyTest {
    private val key = TestKey()

    @Test
    fun `without an override the global policy applies`() {
        val p = ProjectPolicy()
        ToolPolicy.entries.forEach { assertEquals(it, p.effective("run_command", it)) }
    }

    @Test
    fun `an override only narrows`() {
        val p = ProjectPolicy(toolPolicies = mapOf("run_command" to ToolPolicy.ASK, "write_file" to ToolPolicy.ALLOW, "email_send" to ToolPolicy.DENY))
        assertEquals(ToolPolicy.ASK, p.effective("run_command", ToolPolicy.ALLOW))
        assertEquals(ToolPolicy.DENY, p.effective("run_command", ToolPolicy.DENY))
        // Allow in the project doesn't loosen a global Ask or Deny...
        assertEquals(ToolPolicy.ASK, p.effective("write_file", ToolPolicy.ASK))
        assertEquals(ToolPolicy.DENY, p.effective("write_file", ToolPolicy.DENY))
        assertEquals(ToolPolicy.DENY, p.effective("email_send", ToolPolicy.ALLOW))
    }

    @Test
    fun `an explicitly widened tool takes the project's value`() {
        val p = ProjectPolicy(toolPolicies = mapOf("write_file" to ToolPolicy.ALLOW, "run_command" to ToolPolicy.ASK), widened = setOf("write_file", "run_command"))
        assertEquals(ToolPolicy.ALLOW, p.effective("write_file", ToolPolicy.ASK))
        assertEquals(ToolPolicy.ALLOW, p.effective("write_file", ToolPolicy.DENY))
        assertEquals(ToolPolicy.ASK, p.effective("run_command", ToolPolicy.DENY))
        // Widened without an override of its own: nothing to apply, the global policy stands.
        assertEquals(ToolPolicy.DENY, ProjectPolicy(widened = setOf("x")).effective("x", ToolPolicy.DENY))
    }

    @Test
    fun `the connector allow-list`() {
        assertTrue(ProjectPolicy().allowsConnector("anything"))
        val p = ProjectPolicy(allowedConnectors = setOf("linear"))
        assertTrue(p.allowsConnector("linear"))
        assertFalse(p.allowsConnector("github-mcp"))
        assertFalse(ProjectPolicy(allowedConnectors = emptySet()).allowsConnector("linear"))
    }

    @Test
    fun `restricted keeps only what narrows`() {
        val p = ProjectPolicy(
            toolPolicies = mapOf("a" to ToolPolicy.ALLOW, "b" to ToolPolicy.ASK, "c" to ToolPolicy.DENY),
            widened = setOf("a", "b"),
            allowedConnectors = setOf("linear"),
        )
        assertEquals(ProjectPolicy(mapOf("b" to ToolPolicy.ASK, "c" to ToolPolicy.DENY), emptySet(), setOf("linear")), p.restricted())
    }

    @Test
    fun `project policies are sealed`() {
        val s = AppSettings(projectPolicies = mapOf("p1" to ProjectPolicy(mapOf("run_command" to ToolPolicy.DENY))))
        assertFalse(PolicySeal.isDefault(s))
        val sealed = PolicySeal.sealed(s, key)
        assertEquals(SealCheck.VALID, PolicySeal.check(sealed, key))
        // Something outside Locus turns the Deny into a widened Allow: the seal no longer matches.
        val tampered = sealed.copy(projectPolicies = mapOf("p1" to ProjectPolicy(mapOf("run_command" to ToolPolicy.ALLOW), widened = setOf("run_command"))))
        assertEquals(SealCheck.TAMPERED, PolicySeal.check(tampered, key))
        // Dropping the allow-list or adding a project changes the seal too.
        assertNotEquals(PolicySeal.seal(s, key), PolicySeal.seal(s.copy(projectPolicies = s.projectPolicies + ("p2" to ProjectPolicy(allowedConnectors = emptySet()))), key))
    }

    @Test
    fun `seals made before projects still match`() {
        val old = AppSettings(toolPolicies = mapOf("shell" to ToolPolicy.ALLOW))
        val sealedBefore = PolicySeal.sealed(old, key)
        // Empty project policies leave the payload as it was.
        assertEquals(SealCheck.VALID, PolicySeal.check(sealedBefore.copy(projectPolicies = mapOf("p" to ProjectPolicy())), key))
        assertEquals(sealedBefore.policySeal, PolicySeal.seal(old.copy(projectPolicies = emptyMap()), key))
    }

    @Test
    fun `a failed seal drops project Allow overrides and widenings`() {
        val s = AppSettings(projectPolicies = mapOf("p1" to ProjectPolicy(mapOf("a" to ToolPolicy.ALLOW, "b" to ToolPolicy.DENY), widened = setOf("a"))))
        val safe = PolicySeal.restricted(s)
        assertEquals(ProjectPolicy(mapOf("b" to ToolPolicy.DENY)), safe.projectPolicies["p1"])
    }

    @Test
    fun `folders`() {
        assertEquals("projects/garden-app", ProjectPolicy.defaultFolder("Garden App!"))
        assertEquals("projects/project", ProjectPolicy.defaultFolder("  "))
        assertEquals("work/site", ProjectPolicy.cleanFolder("/work/site/"))
        listOf("..", "a/../b", "a/./b", ".locus", "a/.git", "a//b", "", "a\\b").forEach { assertNull(ProjectPolicy.cleanFolder(it), it) }
    }
}
