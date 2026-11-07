package dev.locus.feature.workspace

import dev.locus.core.data.folders.FolderStatus
import dev.locus.core.data.folders.PickedFolder
import dev.locus.core.model.ConnectedFolder
import dev.locus.core.model.Project
import dev.locus.core.screenshot.ScreenshotTest
import dev.locus.core.testing.MainDispatcherRule
import dev.locus.core.testing.TestGraph
import dev.locus.feature.workspace.linux.LinuxViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Workspace → Connected folders, their sheets, and the browser inside one (docs/design/connected-folders.md). */
class ConnectedFolderScreenshots : ScreenshotTest() {
    @get:Rule
    val main = MainDispatcherRule()

    private val scope = CoroutineScope(SupervisorJob())

    @After
    fun tearDown() = scope.cancel()

    private fun <T> StateFlow<T>.await(until: (T) -> Boolean): T = runBlocking { withTimeout(10_000) { first(until) } }

    /** A vault and a notes folder, connected over plain directories; the notes folder lost its grant. IO runs in place, as in WorkspaceScreenshots. */
    private fun graph(): TestGraph {
        val g = TestGraph(scope, dispatcher = Dispatchers.Unconfined)
        val vault = File(g.root, "phone/Documents/Obsidian/Vault").apply { mkdirs() }
        File(vault, "Daily").mkdirs()
        File(vault, "Projects").mkdirs()
        File(vault, ".obsidian").mkdirs()
        File(vault, "Garden plan.md").writeText("# Garden plan\n\n- Raised beds by the fence\n- Tomatoes after the last frost\n")
        File(vault, "Reading list.md").writeText("# Reading list\n")
        File(vault, "Lisbon trip.md").writeText("# Lisbon\n")
        val notes = File(g.root, "phone/Documents/Notes").apply { mkdirs() }
        runBlocking {
            val v = g.connectedFolders.connect(g.folderGrants.uriOf(vault), "vault", false, "Phone storage › Documents/Obsidian/Vault")
            val n = g.connectedFolders.connect(g.folderGrants.uriOf(notes), "notes", true, "SD card › Notes")
            g.folderGrants.revoke(n.treeUri)
            v.id
        }
        File(g.workspace.base, "trip-lisbon.md").writeText("# Lisbon\n")
        File(g.workspace.base, "budget-2026.csv").writeText("a,b\n")
        return g
    }

    private fun vm(g: TestGraph): WorkspaceViewModel {
        val vm = WorkspaceViewModel(g.workspaceRepository, g.workspace, g.io, folders = g.connectedFolders)
        vm.uiState.await { !it.loading && it.connected.size == 2 && it.connected.all { r -> r.status != null } }
        return vm
    }

    private fun screen(g: TestGraph, vm: WorkspaceViewModel): @androidx.compose.runtime.Composable () -> Unit = {
        WithViewModels(LinuxViewModel(g.linuxSetup, g.linux, g.io) {}) { Settled { WorkspaceRoute(onBack = {}, onOpenHealth = {}, viewModel = vm) } }
    }

    @Test
    fun rootWithConnectedFolders() {
        val g = graph()
        snap("workspace-connected-folders", heightDp = 1500, content = screen(g, vm(g)))
    }

    @Test
    fun noConnectedFolders() {
        val g = TestGraph(scope, dispatcher = Dispatchers.Unconfined)
        val vm = WorkspaceViewModel(g.workspaceRepository, g.workspace, g.io, folders = g.connectedFolders)
        vm.uiState.await { !it.loading }
        snap("workspace-connected-folders-none", heightDp = 1300, content = screen(g, vm))
    }

    @Test
    fun browsingAConnectedFolder() {
        val g = graph()
        val vm = vm(g)
        vm.openConnected(vm.uiState.value.connected.first { it.folder.name == "vault" }.folder)
        vm.uiState.await { !it.loading && it.entries.isNotEmpty() }
        snap("workspace-connected-folder-browse", content = screen(g, vm))
    }

    @Test
    fun connectedFolderAccessLost() {
        val g = graph()
        val vm = vm(g)
        vm.openConnected(vm.uiState.value.connected.first { it.folder.name == "notes" }.folder)
        vm.uiState.await { !it.loading && it.folderError != null }
        snap("workspace-connected-folder-lost", content = screen(g, vm))
    }

    @Test
    fun connectSheet() = snap("workspace-connect-folder-sheet", heightDp = 1100) {
        ConnectFolderSheet(
            PendingConnect(PickedFolder("content://x", "Vault", "Phone storage › Documents/Obsidian/Vault", writable = true), "vault"),
            taken = listOf("notes"),
            onConnect = { _, _ -> },
            onDismiss = {},
        )
    }

    @Test
    fun connectSheetReadOnlyProvider() = snap("workspace-connect-folder-sheet-read-only", heightDp = 1100) {
        ConnectFolderSheet(
            PendingConnect(PickedFolder("content://y", "Shared", "Nextcloud › Shared", writable = false), "notes"),
            taken = listOf("notes"),
            onConnect = { _, _ -> },
            onDismiss = {},
        )
    }

    private val projects = listOf(Project("p1", "Garden app", "projects/garden-app"), Project("p2", "Thesis", "projects/thesis"))

    @Test
    fun folderSheet() = snap("workspace-connected-folder-sheet", heightDp = 1300) {
        FolderSheet(
            ConnectedFolderRow(ConnectedFolder("v", "vault", "content://x", "Phone storage › Documents/Obsidian/Vault", projectIds = listOf("p1")), FolderStatus.OK),
            projects, onSave = {}, onReconnect = {}, onDisconnect = {}, onDismiss = {},
        )
    }

    @Test
    fun folderSheetLost() = snap("workspace-connected-folder-sheet-lost", heightDp = 1300) {
        FolderSheet(
            ConnectedFolderRow(ConnectedFolder("n", "notes", "content://y", "SD card › Notes", readOnly = true), FolderStatus.PERMISSION_LOST),
            emptyList(), onSave = {}, onReconnect = {}, onDisconnect = {}, onDismiss = {},
        )
    }
}
