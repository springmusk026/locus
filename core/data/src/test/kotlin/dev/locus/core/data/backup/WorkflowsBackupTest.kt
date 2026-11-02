package dev.locus.core.data.backup

import dev.locus.core.agent.home.AgentHome
import dev.locus.core.agent.workflow.WorkflowStore
import dev.locus.core.model.Workflow
import dev.locus.core.model.WorkflowParam
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Saved workflows live in agent-home, so a backup carries them, shared and per project (docs/design/workflows.md). */
class WorkflowsBackupTest {
    @get:Rule val tmp = TemporaryFolder()

    private val password = "correct horse battery staple".toCharArray()
    private val projectId = "0b4e5a8e-2d0c-4a59-9a43-3f8b1c7d9e10"

    private class Phone(val root: File) {
        val db = File(root, "databases/locus.db")
        val settings = File(root, "files/datastore/settings.json")
        val agentHome = File(root, "files/agent-home")
        val workspace = File(root, "files/workspace")
        val vault = File(root, "files/datastore/secrets.enc")
        val restore = PendingRestore(File(root, "no_backup/restore"), db, settings, agentHome, workspace, vault)

        init {
            val bytes = ByteArray(4096)
            "SQLite format 3\u0000".encodeToByteArray().copyInto(bytes)
            bytes[63] = 15
            db.parentFile.mkdirs()
            db.writeBytes(bytes)
            settings.parentFile.mkdirs()
            settings.writeText("{}")
            File(agentHome, "memory").mkdirs()
            workspace.mkdirs()
        }
    }

    @Test
    fun `workflows survive a backup and restore`() {
        val source = Phone(tmp.newFolder("source"))
        val store = WorkflowStore(AgentHome(source.agentHome))
        val shared = store.save(Workflow(id = "deploy", name = "Deploy", goal = "Deploy {site}", parameters = listOf(WorkflowParam("site"))), null, now = 1)
        val own = store.save(Workflow(id = "water", name = "Water plan", goal = "Plan watering", tools = listOf("write_file")), projectId, now = 2)

        val snapshot = File(tmp.newFolder(), "locus.db").also { source.db.copyTo(it) }
        val plan = BackupArchive.plan(BackupSources(snapshot, source.settings, source.agentHome, source.workspace, null))
        val out = ByteArrayOutputStream()
        BackupCrypto.encrypt(out, password, iterations = 10_000).use {
            BackupArchive.write(it, plan, BackupManifest(appVersion = "0.2.0", appVersionCode = 2, schemaVersion = 15, createdAt = 1, sections = plan.sections))
        }

        val target = Phone(tmp.newFolder("target"))
        val opened = BackupCrypto.open(ByteArrayInputStream(out.toByteArray()))
        BackupArchive.extract(BackupCrypto.decrypt(opened, BackupCrypto.unlock(opened, password)), target.restore.staged, maxSchema = 15)
        target.restore.markReady()
        assertTrue(target.restore.apply())

        val restored = WorkflowStore(AgentHome(target.agentHome))
        assertEquals(shared, restored.get("deploy", null))
        assertEquals(own, restored.get("water", projectId))
        assertEquals(listOf(null, projectId), restored.all().map { it.projectId })
    }
}
