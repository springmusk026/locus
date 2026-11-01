package dev.locus.core.agent.home

import kotlinx.serialization.Serializable
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/** Stops a skill import, with a message for the user. */
class SkillImportException(message: String) : Exception(message)

/**
 * How much an imported skill may hold. The Agent Skills spec sets no size limit; Anthropic's Skills
 * API accepts up to 30 MB uncompressed. A phone gets tighter defaults: skills are instructions,
 * scripts and a few assets, so anything larger is more likely a whole repository than one skill.
 */
data class SkillImportLimits(
    val maxFiles: Int = 100,
    val maxTotalBytes: Long = 10L * 1024 * 1024,
    val maxFileBytes: Long = 5L * 1024 * 1024,
    val maxSkillMdBytes: Long = 256L * 1024,
    /** Folder levels below the skill's root. */
    val maxDepth: Int = 8,
    /** Entries read from a zip, folders and skipped files included. */
    val maxZipEntries: Int = 2_000,
    /** Folder listings fetched from GitHub (each one is an API request). */
    val maxGitHubFolders: Int = 30,
)

/** Where an imported skill came from, kept beside it as `.locus-source.json`. */
@Serializable
data class SkillSource(
    /** "github" or "zip". */
    val kind: String,
    /** The GitHub link as the user gave it, or the zip's file name. */
    val url: String,
    /** owner/repo. */
    val repo: String? = null,
    /** Branch, tag or commit from the link; null means the repository's default branch. */
    val ref: String? = null,
    /** The skill's folder inside the repository ("" for the root). */
    val path: String = "",
    /** The latest commit that touched [path] at [ref] when it was imported, if GitHub said. */
    val commit: String? = null,
    val importedAt: Long = 0,
    /** The manifest's `version` when it was imported (the pin). */
    val version: String? = null,
    /** [SkillManifest.contentHash] of everything imported, SKILL.md included (the pin). */
    val contentHash: String? = null,
) {
    /** Whether [other] points at the same place: the same repository folder, or a zip of the same name. */
    fun sameOrigin(other: SkillSource): Boolean =
        kind == other.kind && if (kind == "github") repo.equals(other.repo, ignoreCase = true) && path == other.path else url == other.url

    /** One line for the review screen, e.g. "github.com/acme/skills · skills/pdf · main @ 1a2b3c4". */
    fun describe(): String = when (kind) {
        "github" -> buildString {
            append("github.com/").append(repo)
            if (path.isNotEmpty()) append(" · ").append(path)
            append(" · ").append(ref ?: "default branch")
            commit?.let { append(" @ ").append(it.take(7)) }
        }
        else -> "the file $url"
    }
}

/** A downloaded or unzipped skill that passed every check, held in memory until the user saves it as a draft. */
class SkillBundle(
    val name: String,
    val description: String,
    val skillMd: String,
    /** Every other file, by path relative to the skill's folder. */
    val files: Map<String, ByteArray>,
    val source: SkillSource,
    /** The checked manifest from SKILL.md's frontmatter ([SkillManifest.UNDECLARED] when it has none). */
    val manifest: SkillManifest = SkillManifest.UNDECLARED,
) {
    /** What Locus pins for it: [SkillManifest.contentHash] of SKILL.md and every file. */
    val contentHash: String get() = source.contentHash ?: SkillManifest.contentHash(skillMd, files)

    val totalBytes: Long get() = skillMd.toByteArray().size.toLong() + files.values.sumOf { it.size.toLong() }
}

object SkillImport {
    const val SOURCE_FILE = ".locus-source.json"

    /**
     * A safe relative path for an archive or repository entry, or a [SkillImportException] for one
     * that could land outside the skill folder (zip slip): absolute paths, drive letters, `..`
     * segments and NUL bytes are rejected. Backslashes count as separators, so `..\x` is caught too.
     */
    fun safePath(raw: String, limits: SkillImportLimits = SkillImportLimits()): String {
        val name = raw.replace('\\', '/')
        if ('\u0000' in name || name.startsWith("/") || DRIVE.containsMatchIn(name)) {
            throw SkillImportException("The import has a file with an unsafe path (${raw.take(80)}), so nothing was imported.")
        }
        val parts = name.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) throw SkillImportException("The import has a file with an unsafe path (${raw.take(80)}), so nothing was imported.")
        if (parts.size - 1 > limits.maxDepth) throw SkillImportException("The import has folders nested more than ${limits.maxDepth} levels deep.")
        return parts.joinToString("/")
    }

    /** OS and VCS clutter that is never part of a skill. */
    fun isJunk(path: String): Boolean = path.split('/').any { it == "__MACOSX" || it == ".DS_Store" || it == ".git" || it == "Thumbs.db" }

    /**
     * Reads a zip of one skill. SKILL.md may sit at the top, inside a single enclosing folder (how
     * GitHub and most file managers zip a folder), or anywhere if the zip holds only one skill.
     * Sizes are counted while inflating, so a zip bomb stops at the limit instead of filling storage.
     */
    fun readZip(input: InputStream, fileName: String, limits: SkillImportLimits = SkillImportLimits(), now: Long = System.currentTimeMillis()): SkillBundle {
        val all = LinkedHashMap<String, ByteArray>()
        var entries = 0
        var total = 0L
        try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (++entries > limits.maxZipEntries) throw SkillImportException("The zip has more than ${limits.maxZipEntries} entries. Zip just the skill's folder.")
                    val path = safePath(entry.name, limits)
                    if (entry.isDirectory || path.isEmpty() || isJunk(path)) continue
                    if (path in all) throw SkillImportException("The zip lists $path twice.")
                    val bytes = readCapped(zip, minOf(limits.maxFileBytes, limits.maxTotalBytes - total), path, limits)
                    total += bytes.size
                    all[path] = bytes
                }
            }
        } catch (e: ZipException) {
            throw SkillImportException("That file isn't a readable zip (${e.message ?: "corrupt archive"}).")
        }
        if (entries == 0) throw SkillImportException("That file isn't a zip, or it's empty.")
        val root = skillRoot(all.keys)
        val files = all.filterKeys { it.startsWith(root) }.mapKeys { it.key.removePrefix(root) }
        return bundle(files, SkillSource(kind = "zip", url = fileName, importedAt = now), limits)
    }

    private fun readCapped(input: InputStream, cap: Long, path: String, limits: SkillImportLimits): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var read = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            read += n
            if (read > cap) {
                throw SkillImportException(
                    if (cap < limits.maxFileBytes) "The import is larger than ${mb(limits.maxTotalBytes)} unpacked, the limit for one skill."
                    else "$path is larger than ${mb(limits.maxFileBytes)}, the limit for one file in a skill.",
                )
            }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** The folder prefix ("" or "dir/") whose SKILL.md is the skill being imported. */
    fun skillRoot(paths: Set<String>): String {
        if ("SKILL.md" in paths) return ""
        val tops = paths.map { it.substringBefore('/') }.toSet()
        if (tops.size == 1 && "${tops.single()}/SKILL.md" in paths) return "${tops.single()}/"
        val found = paths.filter { it.endsWith("/SKILL.md") }
        return when {
            found.size == 1 -> found.single().removeSuffix("SKILL.md")
            found.isEmpty() && paths.any { it.equals("SKILL.md", true) || it.endsWith("/skill.md", true) } ->
                throw SkillImportException("The skill file must be named SKILL.md in capitals.")
            found.isEmpty() -> throw SkillImportException("There is no SKILL.md in the import, so it isn't an Agent Skill.")
            else -> throw SkillImportException(
                "The import holds ${found.size} skills (${found.take(4).joinToString { it.removeSuffix("/SKILL.md").substringAfterLast('/') }}${if (found.size > 4) ", …" else ""}). Import one skill folder at a time.",
            )
        }
    }

    /**
     * Checks a skill's files (paths relative to its folder) against the limits and the spec and
     * builds the bundle. [SOURCE_FILE] is Locus's own and is dropped if the import carries one.
     */
    fun bundle(files: Map<String, ByteArray>, source: SkillSource, limits: SkillImportLimits = SkillImportLimits()): SkillBundle {
        val md = files["SKILL.md"] ?: throw SkillImportException("There is no SKILL.md in the skill's folder, so it isn't an Agent Skill.")
        if (md.size > limits.maxSkillMdBytes) throw SkillImportException("SKILL.md is larger than ${limits.maxSkillMdBytes / 1024} KB.")
        // Locus's own files (the pin, the inferred-manifest marker) are never taken from an import.
        val others = files.filterKeys { it != "SKILL.md" && !it.startsWith(".locus-") && !isJunk(it) }
        if (others.size + 1 > limits.maxFiles) throw SkillImportException("The skill has ${others.size + 1} files; the limit is ${limits.maxFiles}.")
        val total = files.values.sumOf { it.size.toLong() }
        if (total > limits.maxTotalBytes) throw SkillImportException("The skill is ${mb(total)}; the limit is ${mb(limits.maxTotalBytes)}.")
        others.forEach { (path, bytes) ->
            safePath(path, limits)
            if (bytes.size > limits.maxFileBytes) throw SkillImportException("$path is larger than ${mb(limits.maxFileBytes)}, the limit for one file in a skill.")
        }
        val text = md.toString(Charsets.UTF_8).removePrefix("﻿")
        val front = SkillFrontmatter.validate(text)
        val manifest = SkillManifest.parse(text)
        manifest.hash?.let { declared ->
            val actual = SkillManifest.filesHash(others)
            if (actual != declared) {
                throw SkillImportException(
                    "The skill's files don't match the hash in its SKILL.md (it says ${declared.take(19)}…, the files give ${actual.take(19)}…). " +
                        "They changed after the author pinned them, so nothing was imported.",
                )
            }
        }
        val pinned = source.copy(version = manifest.version, contentHash = SkillManifest.contentHash(text, others))
        return SkillBundle(front.getValue("name").trim(), front.getValue("description").trim(), text, others, pinned, manifest)
    }

    fun mb(bytes: Long): String = if (bytes < 1024 * 1024) "${(bytes + 1023) / 1024} KB" else String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))

    private val DRIVE = Regex("^[A-Za-z]:")
}
