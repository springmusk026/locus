package dev.locus.core.agent.home

import dev.locus.core.model.ConnectorSealFormat
import dev.locus.core.model.FileTrees
import dev.locus.core.model.InstructionSealFormat
import dev.locus.core.model.IntegrityKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap

/**
 * The sets of agent-home files that tell the model what to do, each sealed on its own
 * (docs/design/linux-isolation.md, "Sealed instructions"). An item is a skill's folder, a helper's
 * folder, or one persona file.
 */
enum class InstructionSet(internal val context: String) {
    /** `skills/<name>/`: active skills. Changed ones go back to `skills-drafts/`. */
    SKILLS("locus-skills-v1"),

    /** `agents/<name>/`: active helper definitions. Changed ones go back to `agents-drafts/`. */
    HELPERS("locus-helpers-v1"),

    /** `IDENTITY.md`, `SOUL.md`, `USER.md`, `AGENTS.md`. Changed ones are held in `persona-drafts/`. */
    PERSONA("locus-persona-v1"),
    ;

    /** Where the active items live. */
    fun base(home: File): File = when (this) {
        SKILLS -> File(home, "skills")
        HELPERS -> File(home, "agents")
        PERSONA -> home
    }

    /** Where a changed item is held for the user's review. */
    fun review(home: File): File = when (this) {
        SKILLS -> File(home, "skills-drafts")
        HELPERS -> File(home, "agents-drafts")
        PERSONA -> File(home, "persona-drafts")
    }

    /** The manifest: the approved items' hashes and their HMAC. Never an item itself. */
    fun manifest(home: File): File = when (this) {
        SKILLS, HELPERS -> File(base(home), InstructionSeal.FILE)
        PERSONA -> File(home, ".persona${InstructionSeal.FILE}")
    }

    /** A copy of each approved item, to put back when the live one was changed or removed. */
    fun approved(home: File): File = File(home, "history/approved/${name.lowercase()}")

    /** The items now at [base]: names of entries (any kind, links included) that count. */
    fun names(home: File): List<String> = when (this) {
        PERSONA -> AgentHome.PersonaFile.entries.map { it.fileName }.filter { FileTrees.exists(File(home, it)) }
        else -> base(home).list().orEmpty().filter { !it.startsWith(InstructionSeal.FILE) }
    }

    /** Whether an entry of the wrong kind (a link, or a file where a folder belongs) is held for review or just removed. */
    internal fun holdable(file: File): Boolean = !FileTrees.isLink(file) && when (this) {
        PERSONA -> Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
        else -> Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)
    }
}

/**
 * A content hash of a file or a folder tree that never follows a link: a regular file by its bytes,
 * a link by its target text, a folder by every entry's relative path and kind. A link that appears in
 * place of a file therefore changes the hash, rather than standing in for its target.
 */
object TreeHash {
    fun of(file: File): String? {
        val root = file.toPath()
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return null
        val lines = sortedMapOf<String, String>()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                lines[rel(root, dir)] = "d"
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                lines[rel(root, path)] = when {
                    attrs.isSymbolicLink -> "l:" + Files.readSymbolicLink(path)
                    // O_NOFOLLOW: a file swapped for a link after the walk saw it is refused, not read through.
                    attrs.isRegularFile -> "f:" + Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { ConnectorSealFormat.sha256(it.readBytes()) }
                    else -> "o"
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(path: Path, exc: IOException): FileVisitResult {
                lines[rel(root, path)] = "x"
                return FileVisitResult.CONTINUE
            }
        })
        return ConnectorSealFormat.sha256(lines.entries.joinToString("\n") { (k, v) -> "$k\t$v" }.toByteArray())
    }

    private fun rel(root: Path, p: Path) = root.relativize(p).toString().ifEmpty { "." }
}

/**
 * Which skills, helper definitions and persona files the user approved, sealed with a Keystore key
 * the same way [ConnectorSeal] seals connectors. Each [InstructionSet] has a manifest listing each
 * item's [TreeHash], with an HMAC over the list.
 *
 * An item that isn't on the list, differs from it, or is missing from it was changed outside Locus.
 * [check] finds those; [AgentHome] holds the changed version for review, puts the approved copy back,
 * and reports it through [onChanged]. Nothing changed outside Locus is ever loaded into a prompt.
 */
class InstructionSeal(private val key: IntegrityKey, private val onChanged: (InstructionSet, List<String>) -> Unit = { _, _ -> }) {
    @Serializable
    private data class Manifest(val items: Map<String, String> = emptyMap(), val mac: String = "")

    /** The outcome of a check: the [changed] items, and the [approved] hashes when the manifest is genuine. */
    class Check(val changed: List<String>, val approved: Map<String, String>)

    /** The manifest text last found valid per set, so an unchanged manifest isn't checked against the Keystore again. */
    private val lastValid = ConcurrentHashMap<InstructionSet, String>()

    /** Each item's hash as it is now. */
    fun current(set: InstructionSet, home: File): Map<String, String> =
        set.names(home).mapNotNull { name -> TreeHash.of(File(set.base(home), name))?.let { name to it } }.toMap()

    /**
     * What changed since the user last approved [set]. Without a manifest, the items are trusted once
     * if the key is new (an update from a version without these seals) and sealed; an empty set is
     * sealed too, so an item planted later isn't taken for "first use". Otherwise none is approved.
     */
    fun check(set: InstructionSet, home: File): Check {
        val now = current(set, home)
        val text = runCatching { set.manifest(home).readText() }.getOrNull()
        if (text == null) {
            if (now.isEmpty() || runCatching { key.createdThisRun() }.getOrDefault(false)) {
                runCatching { write(set, home) }
                return Check(emptyList(), now)
            }
            return Check(now.keys.sorted(), emptyMap())
        }
        val manifest = runCatching { json.decodeFromString(Manifest.serializer(), text) }.getOrNull()
        val valid = manifest != null && (text == lastValid[set] || InstructionSealFormat.matches(set.context, manifest.items, manifest.mac, key))
        if (!valid) return Check(now.keys.sorted(), emptyMap())
        lastValid[set] = text
        val approved = manifest!!.items
        return Check((now.keys + approved.keys).filter { now[it] != approved[it] }.distinct().sorted(), approved)
    }

    /**
     * Seals the items now in [set] as approved, and keeps a copy of each for [AgentHome] to put back
     * if it's changed later. Copies of items that are gone are dropped.
     */
    fun write(set: InstructionSet, home: File) {
        val now = current(set, home)
        val copies = set.approved(home)
        copies.mkdirs()
        now.forEach { (name, hash) ->
            val copy = File(copies, name)
            if (TreeHash.of(copy) != hash) {
                FileTrees.delete(copy)
                FileTrees.copy(File(set.base(home), name), copy)
            }
        }
        copies.list().orEmpty().filter { it !in now }.forEach { FileTrees.delete(File(copies, it)) }
        val text = json.encodeToString(Manifest.serializer(), Manifest(now, InstructionSealFormat.mac(set.context, now, key)))
        val file = set.manifest(home)
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(text)
        check(tmp.renameTo(file)) { "Could not save the ${set.name.lowercase()} seal" }
        lastValid[set] = text
    }

    /** Seals every set in [home] as it is: a restored backup the user chose, with this phone's key. */
    fun writeAll(home: File) {
        InstructionSet.entries.forEach { set ->
            if (set.base(home).isDirectory) write(set, home)
        }
    }

    internal fun report(set: InstructionSet, names: List<String>) {
        if (names.isNotEmpty()) onChanged(set, names)
    }

    companion object {
        /** Not a skill or helper name (those never start with a dot), so never read as one. */
        const val FILE = ".seal"

        /** In a draft held for review: it was changed outside Locus (the file says when). */
        const val CHANGED_FILE = ".locus-changed"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
