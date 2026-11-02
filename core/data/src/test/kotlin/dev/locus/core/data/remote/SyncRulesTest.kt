package dev.locus.core.data.remote

import dev.locus.core.agent.tools.RemoteJobs
import dev.locus.core.agent.tools.RemoteTarget
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncRulesTest {
    private val rules = SyncRules("# comment\n*.log\ndata/raw/\n/docs/big.pdf\n")

    @Test
    fun `secrets never sync, by name or by folder`() {
        listOf(".env", ".env.production", "app.env", "id_rsa", "id_ed25519", "server.pem", "release.jks", ".npmrc", ".netrc", "local.properties", "prod.tfvars")
            .forEach { assertEquals(SyncRules.Skip.SECRET, rules.skip("x/$it", false), it) }
        listOf(".ssh", ".aws", ".gnupg").forEach { assertEquals(SyncRules.Skip.SECRET, rules.skip(it, true), it) }
        listOf(".env.example", ".env.sample", "id_rsa.pub", "README.md", "main.kt").forEach { assertNull(rules.skip(it, false), it) }
    }

    @Test
    fun `git, dependency and build folders and the ignore file's patterns stay where they are`() {
        listOf(".git", "node_modules", "build", ".gradle", ".venv", "target", ".locus").forEach { assertEquals(SyncRules.Skip.STAYS, rules.skip("app/$it", true), it) }
        assertEquals(SyncRules.Skip.STAYS, rules.skip("logs/server.log", false))
        assertEquals(SyncRules.Skip.STAYS, rules.skip("data/raw", true))
        assertNull(rules.skip("data/raw", false), "a folder-only pattern doesn't match a file")
        assertEquals(SyncRules.Skip.STAYS, rules.skip("docs/big.pdf", false))
        assertNull(rules.skip("other/docs/big.pdf", false), "a pattern with a slash is anchored at the project folder")
        assertNull(rules.skip("src/build.gradle.kts", false))
    }

    @Test
    fun `a file holding a vault value is a secret`() {
        val vault = listOf("sk-live-abcdef123456", "short")
        assertTrue(SyncRules.containsSecret("key = \"sk-live-abcdef123456\"".toByteArray(), vault))
        assertFalse(SyncRules.containsSecret("a short note".toByteArray(), vault), "values under 8 characters are ignored")
        assertFalse(SyncRules.containsSecret("nothing here".toByteArray(), emptyList()))
    }

    @Test
    fun `job ids are per project, and cwd maps into the synced folder`() {
        val a = RemoteTarget("project-a", "h", "build", "/home/me/locus/a", "projects/a")
        val b = a.copy(projectId = "project-b")
        val web = RemoteExecution.serviceId(a, "Web Server!")
        assertTrue(RemoteJobs.isValidId(web) && web.endsWith("-s-web-server"), web)
        assertFalse(web == RemoteExecution.serviceId(b, "Web Server!"))
        assertEquals("/home/me/locus/a", RemoteExecution.remoteCwd(a.folder, "/workspace"))
        assertEquals("/home/me/locus/a/app", RemoteExecution.remoteCwd(a.folder, "/workspace/app/"))
        assertEquals("/home/me/locus/a/app", RemoteExecution.remoteCwd(a.folder, "app"))
        assertEquals("/tmp", RemoteExecution.remoteCwd(a.folder, "/tmp"))
    }

    @Test
    fun `a job probe is read back`() {
        val p = RemoteJobs.Probe.parse("done 3 1700000100 1700000000 new\n/home/me\n")!!
        assertTrue(p.done)
        assertEquals(3, p.exitCode)
        assertEquals(100, p.now - p.startedAt)
        assertFalse(p.seen)
        assertEquals("/home/me", p.home)
        assertNull(RemoteJobs.Probe.parse("garbage"))
    }
}
