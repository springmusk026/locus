package dev.locus.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Remote execution's settings (docs/design/remote-execution.md): the folder check, the seal, and the run_command note. */
class ProjectRemoteTest {
    private val home = "/home/me"

    @Test
    fun `remote folders are absolute, clean and never the home folder or root`() {
        assertEquals("/home/me/locus/app", ProjectRemote.cleanFolder("~/locus/app", home))
        assertEquals("/srv/build/app", ProjectRemote.cleanFolder(" /srv//build/app/ ", home))
        assertNull(ProjectRemote.cleanFolder("~", home))
        assertNull(ProjectRemote.cleanFolder("/home/me/", home))
        assertNull(ProjectRemote.cleanFolder("/", home))
        assertNull(ProjectRemote.cleanFolder("locus/app", home))
        assertNull(ProjectRemote.cleanFolder("/home/me/../other", home))
        assertNull(ProjectRemote.cleanFolder("/tmp/a\nb", home))
        assertEquals("/home/me/locus/garden-app", ProjectRemote.defaultFolder(home, "projects/garden-app"))
    }

    @Test
    fun `a project's remote is sealed, and a broken seal runs everything on the phone`() {
        val key = TestKey()
        val linked = AppSettings(projectRemotes = mapOf("p1" to ProjectRemote("h1", "/home/me/locus/app")))
        val sealed = PolicySeal.sealed(linked, key)
        assertEquals(SealCheck.VALID, PolicySeal.check(sealed, key))
        // Pointing the project at another folder (or machine) outside Locus breaks the seal.
        val moved = sealed.copy(projectRemotes = mapOf("p1" to ProjectRemote("h1", "/tmp/elsewhere")))
        assertEquals(SealCheck.TAMPERED, PolicySeal.check(moved, key))
        assertTrue(PolicySeal.restricted(moved).projectRemotes.isEmpty())
        // Settings without remotes seal as before, so older seals still match.
        assertEquals(PolicySeal.seal(AppSettings(toolPolicies = mapOf("x" to ToolPolicy.ASK)), key), PolicySeal.seal(AppSettings(toolPolicies = mapOf("x" to ToolPolicy.ASK)), key))
        assertNotEquals(PolicySeal.seal(linked, key), PolicySeal.seal(AppSettings(), key))
    }

    @Test
    fun `the verification card reads where a remote command ran`() {
        val result = "Exit code 1 (4.2 s).\n[Ran on build-server in /home/me/locus/app, not on the phone: …]\nFAILED tests/test_a.py::test_one - assert 1 == 2"
        val outcome = Verification.parse(Verification.Command(Verification.Kind.TEST, "pytest"), result, isError = true)
        assertEquals("build-server", outcome.host)
        assertEquals(false, outcome.passed)
        assertEquals(listOf("tests/test_a.py::test_one"), outcome.failures)
        assertNull(Verification.parse(Verification.Command(Verification.Kind.TEST, "pytest"), "Exit code 0 (1.0 s).\nok", false).host)
    }
}
