package dev.locus.core.data.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** What a backup holds; the first entry of the zip, shown as the preview before a restore. */
@Serializable
data class BackupManifest(
    val format: Int = BackupCrypto.FORMAT,
    val appVersion: String,
    val appVersionCode: Long,
    /** Room's schema version of the database snapshot (its `user_version`). */
    val schemaVersion: Int,
    val createdAt: Long,
    val sections: List<BackupSection>,
    /**
     * What the archive holds, separate from [format] (the encryption, unchanged). 1: the original
     * contents (missing in older backups means 1). 2: projects (docs/design/projects.md), in the
     * database, the sealed settings and `agent-home/projects/`. Both restore: Room migrates an older
     * database up, and an older app ignores this field and refuses the newer database on its own.
     */
    val contentVersion: Int = 1,
    /** How many projects the backup holds, for the restore preview; null in backups made before projects. */
    val projects: Int? = null,
) {
    fun section(name: String) = sections.firstOrNull { it.name == name }
    val includesWorkspace get() = section(BackupArchive.WORKSPACE) != null
    val includesVault get() = section(BackupArchive.VAULT) != null
}

/** One part of a backup: how many files (or vault entries) and how many bytes before compression. */
@Serializable
data class BackupSection(val name: String, val files: Int, val bytes: Long)

/** What goes into a backup. The database is a snapshot file, never the live one. */
class BackupSources(
    val databaseSnapshot: File,
    val settingsFile: File?,
    val agentHome: File,
    /** Null when the user left the workspace out. */
    val workspace: File?,
    /** Null unless the user explicitly included the vault. */
    val vault: Map<String, String>?,
)

/** A verified backup unpacked into a private staging folder. The vault is only ever in memory. */
class ExtractedBackup(val manifest: BackupManifest, val staged: File, val vault: Map<String, String>?)

/**
 * The plaintext inside a `.locusbackup`: a zip whose first entry is `manifest.json`, then
 * `database/locus.db`, `settings/settings.json`, `agent-home/…`, and optionally `workspace/…`
 * and `vault/secrets.json`.
 */
object BackupArchive {
    /** The [BackupManifest.contentVersion] this app writes. */
    const val CONTENT_VERSION = 2

    const val MANIFEST = "manifest.json"
    const val DATABASE = "database"
    const val SETTINGS = "settings"
    const val AGENT_HOME = "agent-home"
    const val WORKSPACE = "workspace"
    const val VAULT = "vault"

    const val DATABASE_ENTRY = "$DATABASE/locus.db"
    const val SETTINGS_ENTRY = "$SETTINGS/settings.json"
    private const val VAULT_ENTRY = "$VAULT/secrets.json"
    private const val MAX_VAULT_BYTES = 4 * 1024 * 1024
    private const val MAX_MANIFEST_BYTES = 1024 * 1024

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val vaultSerializer = MapSerializer(String.serializer(), String.serializer())

    internal class Item(val entry: String, val file: File?, val bytes: Long, val data: ByteArray? = null)

    /** The files a backup would contain, with a manifest describing them. */
    class Plan internal constructor(internal val items: List<Item>, val sections: List<BackupSection>) {
        val totalBytes: Long get() = sections.sumOf { it.bytes }
    }

    fun plan(sources: BackupSources): Plan {
        val items = mutableListOf<Item>()
        val sections = mutableListOf<BackupSection>()
        fun add(name: String, list: List<Item>, count: Int = list.count { !it.entry.endsWith("/") }) {
            items += list
            sections += BackupSection(name, count, list.sumOf { it.bytes })
        }
        add(DATABASE, listOf(Item(DATABASE_ENTRY, sources.databaseSnapshot, sources.databaseSnapshot.length())))
        sources.settingsFile?.takeIf { it.isFile }?.let { add(SETTINGS, listOf(Item(SETTINGS_ENTRY, it, it.length()))) }
        add(AGENT_HOME, tree(sources.agentHome, AGENT_HOME))
        sources.workspace?.let { add(WORKSPACE, tree(it, WORKSPACE)) }
        sources.vault?.let { vault ->
            val data = json.encodeToString(vaultSerializer, vault).encodeToByteArray()
            add(VAULT, listOf(Item(VAULT_ENTRY, null, data.size.toLong(), data)), count = vault.size)
        }
        return Plan(items, sections)
    }

    /** Total size of the files under [dir], for the workspace size estimate. Symbolic links aren't followed. */
    fun sizeOf(dir: File): Long = tree(dir, "x").sumOf { it.bytes }

    /** Writes the zip (manifest first) to [out]; [onBytes] gets the running count of bytes read from disk. */
    fun write(out: OutputStream, plan: Plan, manifest: BackupManifest, onBytes: (Long) -> Unit = {}) {
        val zip = ZipOutputStream(out)
        zip.putNextEntry(ZipEntry(MANIFEST))
        zip.write(json.encodeToString(BackupManifest.serializer(), manifest).encodeToByteArray())
        zip.closeEntry()
        var done = 0L
        val buffer = ByteArray(64 * 1024)
        for (item in plan.items) {
            val entry = ZipEntry(item.entry)
            item.file?.let { entry.time = it.lastModified() }
            zip.putNextEntry(entry)
            when {
                item.data != null -> zip.write(item.data)
                item.file != null && item.file.isFile -> item.file.inputStream().use { input ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        zip.write(buffer, 0, n)
                        done += n
                        onBytes(done)
                    }
                }
            }
            zip.closeEntry()
        }
        zip.finish()
    }

    /**
     * Unpacks the plaintext into [staging] (emptied first) and returns the manifest. Throws
     * [BackupException] for anything wrong. The plaintext is read to its very end, so with a
     * decrypting stream every segment, including the last, has been authenticated when this returns.
     */
    fun extract(plain: InputStream, staging: File, maxSchema: Int, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }): ExtractedBackup {
        staging.deleteRecursively()
        staging.mkdirs()
        val root = staging.canonicalFile
        val zip = ZipInputStream(plain)
        var manifest: BackupManifest? = null
        var vault: Map<String, String>? = null
        var done = 0L
        var total = 0L
        val buffer = ByteArray(64 * 1024)
        try {
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (manifest == null) {
                    if (name != MANIFEST) throw BackupException(BackupError.Damaged, message = "The manifest isn't first")
                    manifest = readManifest(zip.readCapped(MAX_MANIFEST_BYTES), maxSchema)
                    total = manifest.sections.filter { it.name != VAULT }.sumOf { it.bytes }
                    continue
                }
                if (!isSafe(name)) throw BackupException(BackupError.Damaged, message = "Unsafe path in backup: $name")
                val section = name.substringBefore('/')
                when {
                    name == VAULT_ENTRY -> vault = json.decodeFromString(vaultSerializer, zip.readCapped(MAX_VAULT_BYTES).decodeToString())
                    section !in setOf(DATABASE, SETTINGS, AGENT_HOME, WORKSPACE) -> Unit // From a newer format revision: ignored.
                    else -> {
                        val target = File(root, name).canonicalFile
                        if (!target.path.startsWith(root.path + File.separator)) throw BackupException(BackupError.Damaged, message = "Unsafe path in backup: $name")
                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            target.outputStream().use { out ->
                                while (true) {
                                    val n = zip.read(buffer)
                                    if (n < 0) break
                                    out.write(buffer, 0, n)
                                    done += n
                                    onProgress(done, total)
                                }
                            }
                        }
                        if (entry.time > 0) target.setLastModified(entry.time)
                    }
                }
            }
            // Past the zip's central directory: read to the end so the final segment is checked too.
            while (plain.read(buffer) >= 0) Unit
        } catch (e: BackupException) {
            throw e
        } catch (e: IOException) {
            // Tink reports a segment that fails to authenticate (or a cut-short file) as an IOException.
            throw BackupException(BackupError.Damaged, e)
        } catch (e: IllegalArgumentException) {
            throw BackupException(BackupError.Damaged, e)
        }
        val m = manifest ?: throw BackupException(BackupError.Damaged, message = "No manifest")
        val db = File(root, DATABASE_ENTRY)
        if (!db.isFile) throw BackupException(BackupError.Damaged, message = "No database in backup")
        val version = sqliteUserVersion(db) ?: throw BackupException(BackupError.Damaged, message = "The database isn't an SQLite file")
        if (version != m.schemaVersion) throw BackupException(BackupError.Damaged, message = "Database version $version doesn't match the manifest's ${m.schemaVersion}")
        if (m.includesVault && vault == null) throw BackupException(BackupError.Damaged, message = "The vault is missing")
        // A folder that was in the backup but empty still replaces the current one.
        listOf(AGENT_HOME, WORKSPACE).filter { m.section(it) != null }.forEach { File(root, it).mkdirs() }
        return ExtractedBackup(m, root, vault)
    }

    /** Parses a manifest and refuses one this app can't restore. */
    internal fun readManifest(bytes: ByteArray, maxSchema: Int): BackupManifest {
        val manifest = try {
            json.decodeFromString(BackupManifest.serializer(), bytes.decodeToString())
        } catch (e: IllegalArgumentException) {
            throw BackupException(BackupError.Damaged, e)
        }
        checkVersions(manifest, maxSchema)
        return manifest
    }

    /**
     * A newer file format or a newer database than this app knows is refused: Room can migrate an
     * older database up, never a newer one down.
     */
    fun checkVersions(manifest: BackupManifest, maxSchema: Int) {
        if (manifest.format > BackupCrypto.FORMAT) throw BackupException(BackupError.NewerFormat)
        if (manifest.schemaVersion > maxSchema) throw BackupException(BackupError.NewerSchema, message = "Database v${manifest.schemaVersion}, this app knows v$maxSchema")
        if (manifest.schemaVersion < 1 || manifest.format < 1) throw BackupException(BackupError.Damaged)
    }

    /** `user_version` from an SQLite file's header (offset 60, big-endian), or null if it isn't one. */
    fun sqliteUserVersion(file: File): Int? {
        val header = ByteArray(100)
        val read = file.inputStream().use { input ->
            var total = 0
            while (total < header.size) {
                val n = input.read(header, total, header.size - total)
                if (n < 0) break
                total += n
            }
            total
        }
        if (read < header.size || !header.copyOfRange(0, 16).contentEquals(SQLITE_MAGIC)) return null
        return ((header[60].toInt() and 0xff) shl 24) or ((header[61].toInt() and 0xff) shl 16) or
            ((header[62].toInt() and 0xff) shl 8) or (header[63].toInt() and 0xff)
    }

    private val SQLITE_MAGIC = "SQLite format 3\u0000".encodeToByteArray()

    /** Relative, forward-slash paths only, with no `.`, `..` or empty parts. */
    internal fun isSafe(name: String): Boolean {
        if (name.isEmpty() || name.startsWith("/") || '\\' in name || '\u0000' in name) return false
        val parts = name.removeSuffix("/").split('/')
        return parts.none { it.isEmpty() || it == "." || it == ".." }
    }

    private fun tree(dir: File, prefix: String): List<Item> {
        if (!dir.isDirectory) return emptyList()
        val out = mutableListOf<Item>()
        fun walk(d: File, path: String) {
            val children = d.listFiles().orEmpty().sortedBy { it.name }
            if (children.isEmpty() && path != prefix) out += Item("$path/", null, 0)
            for (child in children) {
                if (Files.isSymbolicLink(child.toPath())) continue // Never follow a link out of the folder.
                val childPath = "$path/${child.name}"
                when {
                    child.isDirectory -> walk(child, childPath)
                    child.isFile -> out += Item(childPath, child, child.length())
                }
            }
        }
        walk(dir, prefix)
        return out
    }

    private fun InputStream.readCapped(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > max) throw BackupException(BackupError.Damaged, message = "Entry too large")
        }
        return out.toByteArray()
    }
}
