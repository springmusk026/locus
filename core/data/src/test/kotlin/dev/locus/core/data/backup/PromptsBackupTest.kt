package dev.locus.core.data.backup

import dev.locus.core.agent.home.AgentHome
import dev.locus.core.agent.prompt.PromptStore
import dev.locus.core.model.SavedPrompt
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Saved prompts live in agent-home's prompts.json, so a backup carries them, in order (docs/design/saved-prompts.md). */
class PromptsBackupTest {
    @get:Rule val tmp = TemporaryFolder()

    private val password = "correct horse battery staple".toCharArray()

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
    fun `saved prompts survive a backup and restore`() {
        val source = Phone(tmp.newFolder("source"))
        val store = PromptStore(AgentHome(source.agentHome))
        val first = store.save(SavedPrompt("", "Translate", "Translate into {{field:Language|Spanish}}:\n\n{{selection}}"), now = 1)
        val second = store.save(SavedPrompt("", "Standup", "My standup for {{date}}: {{clipboard}}"), now = 2)
        store.move(second.id, -1)

        val snapshot = File(tmp.newFolder(), "locus.db").also { source.db.copyTo(it) }
        val plan = BackupArchive.plan(BackupSources(snapshot, source.settings, source.agentHome, source.workspace, null))
        assertTrue(plan.sections.isNotEmpty())
        val out = ByteArrayOutputStream()
        BackupCrypto.encrypt(out, password, iterations = 10_000).use {
            BackupArchive.write(it, plan, BackupManifest(appVersion = "0.2.0", appVersionCode = 2, schemaVersion = 15, createdAt = 1, sections = plan.sections))
        }

        val target = Phone(tmp.newFolder("target"))
        val opened = BackupCrypto.open(ByteArrayInputStream(out.toByteArray()))
        BackupArchive.extract(BackupCrypto.decrypt(opened, BackupCrypto.unlock(opened, password)), target.restore.staged, maxSchema = 15, checkDatabase = NO_DATABASE_CHECK)
        target.restore.markReady()
        assertTrue(target.restore.apply())

        assertTrue(File(target.agentHome, PromptStore.FILE).isFile)
        assertEquals(listOf(second, first), PromptStore(AgentHome(target.agentHome)).all())
    }
}
