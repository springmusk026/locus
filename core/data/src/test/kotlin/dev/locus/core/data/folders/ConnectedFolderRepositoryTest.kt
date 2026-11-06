package dev.locus.core.data.folders

import dev.locus.core.agent.ToolCallContext
import dev.locus.core.agent.ToolRegistry
import dev.locus.core.agent.tools.ChangeOwner
import dev.locus.core.agent.tools.FileReads
import dev.locus.core.agent.tools.Workspace
import dev.locus.core.agent.tools.workspaceTools
import dev.locus.core.data.repository.ChangeRepository
import dev.locus.core.data.repository.TrackedChange
import dev.locus.core.database.ConversationEntity
import dev.locus.core.model.AppSettings
import dev.locus.core.model.ApprovalScopeKind
import dev.locus.core.model.RememberedApproval
import dev.locus.core.testing.FakeDatabase
import dev.locus.core.testing.FakeFolderGrants
import dev.locus.core.testing.InMemoryDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Connected folders end to end: connecting, the changes card's undo, permission loss and disconnecting (docs/design/connected-folders.md). */
class ConnectedFolderRepositoryTest {
    private val base: File = Files.createTempDirectory("locus-folders").toFile()
    private val vault = File(base, "phone/Documents/Obsidian/Vault").apply { mkdirs() }
    private val store = InMemoryDataStore(AppSettings())
    private val grants = FakeFolderGrants()
    private val folders = ConnectedFolderRepository(store, grants, scope = null)
    private val ws = Workspace(File(base, "workspace"), File(base, "trash"), connectedFolders = folders)
    private val db = FakeDatabase()
    private val reads = FileReads()
    private val changes = ChangeRepository(db.fileChangeDao, ws, reads, kotlinx.coroutines.Dispatchers.IO)
    private val tools = ToolRegistry(workspaceTools(ws, reads = reads, changes = changes))

    @After
    fun cleanup() {
        base.deleteRecursively()
    }

    private suspend fun call(tool: String, vararg args: Pair<String, String>) =
        withContext(ToolCallContext("c", "call-$tool", 0, null, changeOwner = ChangeOwner("c", "t1"))) {
            tools[tool]!!.execute(buildJsonObject { args.forEach { (k, v) -> put(k, JsonPrimitive(v)) } })
        }

    private suspend fun connect(name: String = "vault"): String {
        val uri = grants.uriOf(vault)
        val picked = folders.describe(uri)
        assertEquals("Vault", picked.displayName)
        return folders.connect(uri, name, readOnly = false, source = picked.source).id
    }

    @Test
    fun `undo puts a connected folder's file back from Locus's trash`() = runTest {
        db.conversationDao.upsert(ConversationEntity("c", "Chat", null, "", 0, 0))
        connect()
        File(vault, "daily.md").writeText("one\n")
        call("read_file", "path" to "folders/vault/daily.md")
        call("edit_file", "path" to "folders/vault/daily.md", "old_text" to "one", "new_text" to "two")
        call("write_file", "path" to "folders/vault/new.md", "content" to "fresh\n")
        val rows = changes.observe("c").first()
        val edited = rows.single { it.path == "folders/vault/daily.md" }
        assertEquals(TrackedChange.Kind.MODIFIED, edited.kind)
        assertEquals("--- a/folders/vault/daily.md\n+++ b/folders/vault/daily.md\n@@ -1 +1 @@\n-one\n+two\n", edited.diff)
        assertEquals(TrackedChange.Kind.CREATED, rows.single { it.path == "folders/vault/new.md" }.kind)

        assertEquals(setOf("folders/vault/daily.md", "folders/vault/new.md"), changes.undoTurn("c", "t1").toSet())
        assertEquals("one\n", File(vault, "daily.md").readText())
        assertFalse(File(vault, "new.md").exists())
        // The chat read the file before the undo, so it must read it again.
        val again = runCatching { call("edit_file", "path" to "folders/vault/daily.md", "old_text" to "one", "new_text" to "x") }
        assertTrue(again.getOrNull()?.isError == true || again.isFailure)
        assertEquals("one\n", File(vault, "daily.md").readText())
    }

    @Test
    fun `status follows the grant and the folder, and connecting checks the name`() = runTest {
        val id = connect()
        val folder = folders.folders().single { it.id == id }
        assertEquals(FolderStatus.OK, folders.status(folder))
        assertFailsWith<IllegalArgumentException> { folders.connect(grants.uriOf(File(base, "other").apply { mkdirs() }), "vault", false, "") }
        grants.revoke(folder.treeUri)
        assertEquals(FolderStatus.PERMISSION_LOST, folders.status(folder))
        val lost = runCatching { call("read_file", "path" to "folders/vault/x.md") }.exceptionOrNull()
        assertTrue("no longer has access" in lost?.message.orEmpty(), lost?.message)
        // Picking it again restores access under the same name.
        folders.reconnect(id, folder.treeUri)
        assertEquals(FolderStatus.OK, folders.status(folders.folders().single()))
        vault.deleteRecursively()
        assertEquals(FolderStatus.GONE, folders.status(folders.folders().single()))
    }

    @Test
    fun `a provider that only grants reading connects read-only`() = runTest {
        grants.readOnlyProvider += grants.uriOf(vault)
        val id = connect()
        val folder = folders.folders().single { it.id == id }
        assertTrue(folder.readOnly)
        assertEquals(FolderStatus.PROVIDER_READ_ONLY, folders.status(folder))
    }

    @Test
    fun `disconnecting releases the grant and forgets its folder rules`() = runTest {
        val id = connect()
        store.updateData {
            it.copy(
                rememberedApprovals = listOf(
                    RememberedApproval("a", "write_file", ApprovalScopeKind.FOLDER, "folders/vault/daily"),
                    RememberedApproval("b", "write_file", ApprovalScopeKind.FOLDER, "folders/vaults"),
                    RememberedApproval("c", "write_file", ApprovalScopeKind.FOLDER, "src"),
                ),
            )
        }
        folders.remove(id)
        assertTrue(folders.folders().isEmpty())
        assertTrue(grants.held.isEmpty())
        assertEquals(listOf("b", "c"), store.data.first().rememberedApprovals.map { it.id })
        // The files themselves are untouched.
        assertTrue(vault.isDirectory)
    }
}
