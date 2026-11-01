package dev.locus.core.data.backup

import dev.locus.core.model.AppSettings
import dev.locus.core.model.ProjectPolicy
import dev.locus.core.model.ToolPolicy
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Backups with projects (docs/design/projects.md): content version 2 carries the project count, the
 * projects' instructions and memory (agent-home/projects/) and their sealed permissions (settings)
 * through a round trip, and a version-1 manifest from before projects still reads.
 */
class ProjectsBackupTest {
    @get:Rule val tmp = TemporaryFolder()

    private val password = "correct horse battery staple".toCharArray()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val projectId = "0b4e5a8e-2d0c-4a59-9a43-3f8b1c7d9e10"

    private fun fakeDatabase(file: File, userVersion: Int) {
        val bytes = ByteArray(4096)
        "SQLite format 3\u0000".encodeToByteArray().copyInto(bytes)
        bytes[63] = userVersion.toByte()
        file.parentFile.mkdirs()
        file.writeBytes(bytes)
    }

    private class Phone(val root: File) {
        val db = File(root, "databases/locus.db")
        val settings = File(root, "files/datastore/settings.json")
        val agentHome = File(root, "files/agent-home")
        val workspace = File(root, "files/workspace")
        val vault = File(root, "files/datastore/secrets.enc")
        val restore = PendingRestore(File(root, "no_backup/restore"), db, settings, agentHome, workspace, vault)
    }

    private val policy = ProjectPolicy(
        toolPolicies = mapOf("run_command" to ToolPolicy.DENY, "write_file" to ToolPolicy.ALLOW),
        widened = setOf("write_file"),
        allowedConnectors = setOf("linear"),
    )

    private fun phone(name: String, withProject: Boolean): Phone {
        val p = Phone(tmp.newFolder(name))
        fakeDatabase(p.db, 14)
        p.settings.parentFile.mkdirs()
        val settings = if (withProject) AppSettings(projectPolicies = mapOf(projectId to policy)) else AppSettings()
        p.settings.writeText(json.encodeToString(AppSettings.serializer(), settings))
        File(p.agentHome, "memory").mkdirs()
        if (withProject) {
            File(p.agentHome, "projects/$projectId/memory").mkdirs()
            File(p.agentHome, "projects/$projectId/AGENTS.md").writeText("Use metric units.\n")
            File(p.agentHome, "projects/$projectId/memory/MEMORY.md").writeText("- [beds](beds.md) — three raised beds\n")
            File(p.agentHome, "projects/$projectId/memory/beds.md").writeText("Three raised beds.\n")
            File(p.workspace, "projects/garden").mkdirs()
            File(p.workspace, "projects/garden/plan.md").writeText("# Plan\n")
        }
        return p
    }

    private fun backup(p: Phone, manifest: (List<BackupSection>) -> BackupManifest): ByteArray {
        val snapshot = File(tmp.newFolder(), "locus.db").also { p.db.copyTo(it) }
        val plan = BackupArchive.plan(BackupSources(snapshot, p.settings, p.agentHome, p.workspace, null))
        val out = ByteArrayOutputStream()
        BackupCrypto.encrypt(out, password, iterations = 10_000).use { BackupArchive.write(it, plan, manifest(plan.sections)) }
        return out.toByteArray()
    }

    private fun verify(bytes: ByteArray, staging: File): ExtractedBackup {
        val opened = BackupCrypto.open(ByteArrayInputStream(bytes))
        return BackupArchive.extract(BackupCrypto.decrypt(opened, BackupCrypto.unlock(opened, password)), staging, maxSchema = 14)
    }

    @Test
    fun `projects survive a round trip`() {
        val source = phone("source", withProject = true)
        val file = backup(source) { sections ->
            BackupManifest(
                appVersion = "0.2.0", appVersionCode = 2, schemaVersion = 14, createdAt = 1, sections = sections,
                contentVersion = BackupArchive.CONTENT_VERSION, projects = 1,
            )
        }
        val target = phone("target", withProject = false)

        val extracted = verify(file, target.restore.staged)
        assertEquals(2, extracted.manifest.contentVersion)
        assertEquals(1, extracted.manifest.projects)
        target.restore.markReady()
        assertTrue(target.restore.apply())

        val restored = json.decodeFromString(AppSettings.serializer(), target.settings.readText())
        assertEquals(policy, restored.projectPolicies[projectId])
        assertEquals("Use metric units.\n", File(target.agentHome, "projects/$projectId/AGENTS.md").readText())
        assertEquals("Three raised beds.\n", File(target.agentHome, "projects/$projectId/memory/beds.md").readText())
        assertEquals("# Plan\n", File(target.workspace, "projects/garden/plan.md").readText())
    }

    @Test
    fun `a manifest from before projects still reads as content version 1`() {
        val old = """{"format":1,"appVersion":"0.1.3","appVersionCode":13,"schemaVersion":13,"createdAt":1,"sections":[]}"""
        val manifest = BackupArchive.readManifest(old.encodeToByteArray(), maxSchema = 14)
        assertEquals(1, manifest.contentVersion)
        assertNull(manifest.projects)
    }

    @Test
    fun `settings from before projects read with no project permissions`() {
        val old = """{"toolPolicies":{"run_command":"ALLOW"},"policySeal":"v1:00"}"""
        assertEquals(emptyMap(), json.decodeFromString(AppSettings.serializer(), old).projectPolicies)
    }
}
