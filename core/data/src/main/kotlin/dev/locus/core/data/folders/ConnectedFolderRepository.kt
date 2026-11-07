package dev.locus.core.data.folders

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.datastore.core.DataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.locus.core.agent.tools.DocTree
import dev.locus.core.agent.tools.DocTreeException
import dev.locus.core.agent.tools.ConnectedFolderSource
import dev.locus.core.model.AppSettings
import dev.locus.core.model.ApprovalScopeKind
import dev.locus.core.model.ConnectedFolder
import dev.locus.core.model.ConnectedFolders
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

/** What the picker returned, before the user names it. */
data class PickedFolder(val uri: String, val displayName: String, val source: String, val writable: Boolean)

/** How a connected folder is doing, for its row in Workspace → Connected folders. */
enum class FolderStatus {
    /** Readable, and the provider lets apps make and change files in it. */
    OK,

    /** Readable, but its provider doesn't let apps change anything (the agent can only read). */
    PROVIDER_READ_ONLY,

    /** The grant is gone: revoked in Settings → Apps, cleared data, or the folder moved. Reconnect it. */
    PERMISSION_LOST,

    /** The folder was deleted, or its storage (an SD card) removed. */
    GONE,
}

/**
 * Android's side of a connected folder: taking and releasing the persisted grant, naming what was
 * picked, and the tree itself. An interface so tests can use plain folders.
 */
interface FolderGrants {
    /** Takes a persisted grant for [uri]: read and write when the picker gave both. Returns whether writing was granted. */
    fun take(uri: String): Boolean

    fun release(uri: String)

    /** Whether Locus still holds a read grant for [uri]. */
    fun holds(uri: String): Boolean

    fun describe(uri: String): PickedFolder

    fun tree(uri: String): DocTree
}

/**
 * Folders the user connected through Android's folder picker (docs/design/connected-folders.md): kept in
 * the sealed settings ([AppSettings.connectedFolders]), with a persisted grant each. It is also the
 * [ConnectedFolderSource] the workspace's file layer reads, from a snapshot kept current here.
 */
class ConnectedFolderRepository(
    private val store: DataStore<AppSettings>,
    private val grants: FolderGrants,
    /** Keeps the snapshot current (the app's scope); null reads the settings on every call (tests). */
    scope: CoroutineScope?,
    /** Where the provider is queried: Dispatchers.IO in the app, the test's dispatcher in tests. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ConnectedFolderSource {
    val folders: Flow<List<ConnectedFolder>> = store.data.map { it.connectedFolders }

    @Volatile
    private var snapshot: List<ConnectedFolder>? = null

    private val trees = HashMap<String, DocTree>()

    init {
        scope?.launch { store.data.collect { snapshot = it.connectedFolders } }
    }

    private val live = scope != null

    override fun folders(): List<ConnectedFolder> =
        snapshot?.takeIf { live } ?: runBlocking { store.data.first().connectedFolders }.also { snapshot = it }

    override fun tree(folder: ConnectedFolder): DocTree = synchronized(trees) { trees.getOrPut(folder.treeUri) { grants.tree(folder.treeUri) } }

    /** Names what the picker returned, before the user confirms it. */
    suspend fun describe(uri: String): PickedFolder = withContext(io) { grants.describe(uri) }

    /**
     * Connects the picked [uri] as [name]: takes the persisted grant and saves it. A provider that only
     * granted reading makes the folder read-only. Throws with what to tell the user.
     */
    suspend fun connect(uri: String, name: String, readOnly: Boolean, source: String): ConnectedFolder = withContext(io) {
        val current = store.data.first().connectedFolders
        ConnectedFolders.nameProblem(name, current.map { it.name })?.let { throw IllegalArgumentException(it) }
        if (current.any { it.treeUri == uri }) throw IllegalArgumentException("That folder is already connected as ${current.first { it.treeUri == uri }.name}.")
        val writable = grants.take(uri)
        val folder = ConnectedFolder(
            id = UUID.randomUUID().toString(),
            name = name,
            treeUri = uri,
            source = source,
            readOnly = readOnly || !writable,
            addedAt = System.currentTimeMillis(),
        )
        update { s -> s.copy(connectedFolders = s.connectedFolders + folder) }
        folder
    }

    /** Saves [folder]'s switches and project links (its name and grant stay). */
    suspend fun save(folder: ConnectedFolder) = update { s ->
        s.copy(connectedFolders = s.connectedFolders.map { if (it.id == folder.id) it.copy(readOnly = folder.readOnly, hiddenWritable = folder.hiddenWritable, projectIds = folder.projectIds) else it })
    }

    /**
     * Points [id] at a newly picked [uri] after its grant was lost or the folder moved. Its name, switches,
     * trash entries and undo records stay.
     */
    suspend fun reconnect(id: String, uri: String) = withContext(io) {
        val old = store.data.first().connectedFolders.firstOrNull { it.id == id } ?: return@withContext
        val writable = grants.take(uri)
        if (old.treeUri != uri) runCatching { grants.release(old.treeUri) }
        synchronized(trees) { trees.remove(old.treeUri) }
        update { s -> s.copy(connectedFolders = s.connectedFolders.map { if (it.id == id) it.copy(treeUri = uri, readOnly = it.readOnly || !writable) else it }) }
    }

    /**
     * Disconnects [id]: releases the grant and forgets the folder, with every "Always for this folder" rule
     * under its path, so a folder connected later under the same name doesn't inherit them. Its files are
     * untouched; trash copies stay in Workspace → Trash.
     */
    suspend fun remove(id: String) = withContext(io) {
        val folder = store.data.first().connectedFolders.firstOrNull { it.id == id } ?: return@withContext
        val prefix = ConnectedFolders.pathOf(folder)
        update { s ->
            s.copy(
                connectedFolders = s.connectedFolders.filterNot { it.id == id },
                rememberedApprovals = s.rememberedApprovals.filterNot { r -> r.kind == ApprovalScopeKind.FOLDER && (r.value == prefix || r.value.startsWith("$prefix/")) },
            )
        }
        runCatching { grants.release(folder.treeUri) }
        synchronized(trees) { trees.remove(folder.treeUri) }
    }

    /** How [folder] is doing now: one provider query. */
    suspend fun status(folder: ConnectedFolder): FolderStatus = withContext(io) {
        if (!grants.holds(folder.treeUri)) return@withContext FolderStatus.PERMISSION_LOST
        try {
            val root = tree(folder).root()
            if (root.canCreate) FolderStatus.OK else FolderStatus.PROVIDER_READ_ONLY
        } catch (e: DocTreeException) {
            if (e.problem == DocTreeException.Problem.PERMISSION_LOST) FolderStatus.PERMISSION_LOST else FolderStatus.GONE
        } catch (e: SecurityException) {
            FolderStatus.PERMISSION_LOST
        } catch (e: Exception) {
            FolderStatus.GONE
        }
    }

    private suspend fun update(change: (AppSettings) -> AppSettings) {
        val saved = store.updateData(change)
        snapshot = saved.connectedFolders
    }
}

/** [FolderGrants] through the Storage Access Framework. */
class AndroidFolderGrants @Inject constructor(@param:ApplicationContext private val context: Context) : FolderGrants {
    private val resolver get() = context.contentResolver

    override fun take(uri: String): Boolean {
        val u = Uri.parse(uri)
        return try {
            resolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            true
        } catch (e: SecurityException) {
            // A provider that granted reading only.
            resolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            false
        }
    }

    override fun release(uri: String) {
        val u = Uri.parse(uri)
        val held = resolver.persistedUriPermissions.firstOrNull { it.uri == u } ?: return
        val flags = (if (held.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
            (if (held.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        if (flags != 0) resolver.releasePersistableUriPermission(u, flags)
    }

    override fun holds(uri: String): Boolean {
        val u = Uri.parse(uri)
        return resolver.persistedUriPermissions.any { it.uri == u && it.isReadPermission }
    }

    override fun describe(uri: String): PickedFolder {
        val u = Uri.parse(uri)
        val docId = runCatching { DocumentsContract.getTreeDocumentId(u) }.getOrNull().orEmpty()
        val name = runCatching { SafDocTree(resolver, u).root().name }.getOrNull()
            ?: docId.substringAfterLast('/').substringAfter(':').ifEmpty { "folder" }
        val writable = runCatching { SafDocTree(resolver, u).root().canCreate }.getOrDefault(false)
        return PickedFolder(uri, name, sourceLabel(u.authority.orEmpty(), docId, name), writable)
    }

    /** Where it is, in words: "Phone storage › Documents/Obsidian", "SD card › Notes", "Nextcloud › vault". */
    private fun sourceLabel(authority: String, docId: String, name: String): String = when (authority) {
        "com.android.externalstorage.documents" -> {
            val volume = docId.substringBefore(':')
            val path = docId.substringAfter(':', "").ifEmpty { name }
            "${if (volume == "primary") "Phone storage" else "SD card"} › $path"
        }
        else -> {
            val app = runCatching {
                context.packageManager.resolveContentProvider(authority, 0)?.loadLabel(context.packageManager)?.toString()
            }.getOrNull() ?: authority
            "$app › $name"
        }
    }

    override fun tree(uri: String): DocTree = SafDocTree(resolver, Uri.parse(uri))
}
