package dev.locus.core.agent.home

import dev.locus.core.agent.SkillScope
import kotlinx.serialization.Serializable
import java.security.MessageDigest

/** What a skill says it needs to install before it works. Every list is empty when nothing is declared. */
@Serializable
data class SkillDependencies(
    /** Alpine packages for Locus's Linux system (`apk add`), e.g. "poppler-utils" or "jq=1.7.1-r0". */
    val apk: List<String> = emptyList(),
    /** npm packages, e.g. "marked@12.0.2". */
    val npm: List<String> = emptyList(),
    /** pip packages, e.g. "pypdf==4.2.0". */
    val pip: List<String> = emptyList(),
    /** Other skills this one uses, by name. */
    val skills: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = apk.isEmpty() && npm.isEmpty() && pip.isEmpty() && skills.isEmpty()

    /** "apk:jq", "npm:marked@12.0.2", … for diffs. */
    fun all(): List<String> = apk.map { "apk:$it" } + npm.map { "npm:$it" } + pip.map { "pip:$it" } + skills.map { "skill:$it" }

    /** Packages without an exact version: they install whatever is newest the day they're installed. */
    fun unpinned(): List<String> =
        apk.filter { '=' !in it }.map { "apk:$it" } + npm.filter { !NPM_PINNED.matches(it) }.map { "npm:$it" } + pip.filter { "==" !in it }.map { "pip:$it" }

    private companion object {
        /** name@1.2.3 or @scope/name@1.2.3: an exact version, not a range or a tag. */
        val NPM_PINNED = Regex("^(@[a-z0-9._-]+/)?[a-z0-9._-]+@\\d+\\.\\d+\\.\\d+([-+][0-9A-Za-z.-]+)?$")
    }
}

/**
 * A skill's security manifest: optional SKILL.md frontmatter fields that say what the skill needs.
 * See docs/design/skill-manifests.md.
 *
 * ```yaml
 * version: 1.2.0
 * capabilities: [files.read, files.write, linux]
 * network:
 *   - api.example.com
 *   - "*.githubusercontent.com"
 * dependencies:
 *   apk: [poppler-utils]
 *   pip: [pypdf==4.2.0]
 * source: https://github.com/acme/skills/tree/v1.2.0/pdf
 * hash: sha256:<64 hex digits over the skill's other files, see [filesHash]>
 * ```
 *
 * A skill with none of `capabilities`, `network` or `dependencies` is **undeclared** ([declared] false)
 * and keeps the user's normal permissions. A declared one is held to what it lists while it's active
 * in a chat ([SkillScope]): anything else asks first.
 */
@Serializable
data class SkillManifest(
    val declared: Boolean = false,
    /** Tool groups ([SkillScope.GROUPS]), tool names, or `mcp:<connector>` / `connector:<id>` / `ssh:<host>`. */
    val capabilities: List<String> = emptyList(),
    /** Hosts it may contact: "api.example.com", "*.example.com" (any subdomain) or "*" (any host). */
    val network: List<String> = emptyList(),
    val dependencies: SkillDependencies = SkillDependencies(),
    val version: String? = null,
    /** Where the author publishes it (an https link). */
    val source: String? = null,
    /** "sha256:<hex>" over every file except SKILL.md ([filesHash]); checked when it's imported. */
    val hash: String? = null,
    /** The Agent Skills `allowed-tools` field, kept as written. Locus shows it but never grants anything from it. */
    val allowedTools: String? = null,
) {
    companion object {
        val UNDECLARED = SkillManifest()

        /** The frontmatter keys that make a skill declared. */
        val DECLARING_KEYS = listOf("capabilities", "network", "dependencies")

        private val DEPENDENCY_KINDS = listOf("apk", "npm", "pip", "skills")
        private const val MAX_ITEMS = 64

        private val VERSION = Regex("^v?\\d+(\\.\\d+){0,2}([-+][0-9A-Za-z.-]+)?$")
        private val HASH = Regex("^sha256:[0-9a-f]{64}$")
        private val HOST_LABEL = "[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?"
        private val HOST = Regex("^(\\*\\.)?($HOST_LABEL\\.)*$HOST_LABEL$")
        private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
        private val ID = Regex("^[a-z0-9]+([-_][a-z0-9]+)*$")
        private val APK = Regex("^[a-z0-9][a-z0-9._+-]*(=[0-9A-Za-z._-]+)?$")
        private val NPM = Regex("^(@[a-z0-9._-]+/)?[a-z0-9._-]+(@[0-9A-Za-z.^~<>=*|+ -]+)?$")
        private val PIP = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*(\\[[A-Za-z0-9,._-]+])?((==|>=|<=|~=|!=|>|<)[0-9A-Za-z.*+!-]+(,(==|>=|<=|~=|!=|>|<)[0-9A-Za-z.*+!-]+)*)?$")

        /**
         * Reads and checks the manifest in [md]'s frontmatter. Throws [SkillImportException] with a
         * message for the user when a field is malformed; a skill without these fields gets [UNDECLARED]
         * (with any `version`, `source` and `hash` it gives).
         */
        fun parse(md: String): SkillManifest {
            val entries = SkillFrontmatter.entries(md) ?: return UNDECLARED
            val declared = DECLARING_KEYS.any { it in entries }
            val capabilities = entries["capabilities"]?.let { list("capabilities", it) }.orEmpty().map { it.lowercase() }
            capabilities.forEach(::checkCapability)
            val network = entries["network"]?.let { list("network", it) }.orEmpty().map(::checkHost)
            val dependencies = entries["dependencies"]?.let(::dependencies) ?: SkillDependencies()
            val version = entries["version"]?.let { scalar("version", it) }?.also {
                if (!VERSION.matches(it)) fail("The version “${it.take(40)}” isn't valid. Use a version number like 1.2.0.")
            }
            val source = entries["source"]?.let { scalar("source", it) }?.also {
                if (!it.startsWith("https://") || it.length > 500 || it.any { c -> c.isWhitespace() }) fail("source must be an https:// link to where the skill is published.")
            }
            val hash = entries["hash"]?.let { scalar("hash", it) }?.lowercase()?.also {
                if (!HASH.matches(it)) fail("hash must be sha256: followed by 64 hex digits.")
            }
            val allowed = entries["allowed-tools"]?.let { e -> (listOf(e.rest) + e.following.map { it.trim() }).filter { it.isNotEmpty() }.joinToString(" ") }
            return SkillManifest(
                declared = declared,
                capabilities = capabilities.distinct(),
                network = network.distinct(),
                dependencies = dependencies,
                version = version,
                source = source,
                hash = hash,
                allowedTools = allowed?.takeIf { it.isNotEmpty() },
            )
        }

        /** [parse], but a malformed manifest on disk (only possible if it was edited outside Locus) declares nothing: fail closed. */
        fun read(md: String): Pair<SkillManifest, String?> = try {
            parse(md) to null
        } catch (e: SkillImportException) {
            SkillManifest(declared = true) to e.message
        }

        /**
         * The hash a manifest's `hash` field pins: SHA-256 over each file except SKILL.md (which holds
         * the hash itself) as "path NUL sha256(bytes) LF", sorted by path. Locus's own files are left out.
         */
        fun filesHash(files: Map<String, ByteArray>): String = digest(files.filterKeys { it != "SKILL.md" && !it.startsWith(".locus-") })

        /** The same over every file, SKILL.md included: what Locus pins for an imported skill. */
        fun contentHash(skillMd: String, files: Map<String, ByteArray>): String =
            digest(files.filterKeys { it != "SKILL.md" && !it.startsWith(".locus-") } + ("SKILL.md" to skillMd.toByteArray()))

        private fun digest(files: Map<String, ByteArray>): String {
            val outer = MessageDigest.getInstance("SHA-256")
            files.toSortedMap().forEach { (path, bytes) ->
                outer.update(path.toByteArray())
                outer.update(0)
                outer.update(hex(MessageDigest.getInstance("SHA-256").digest(bytes)).toByteArray())
                outer.update('\n'.code.toByte())
            }
            return "sha256:" + hex(outer.digest())
        }

        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

        private fun fail(message: String): Nothing = throw SkillImportException(message)

        private fun checkCapability(c: String) {
            val (kind, id) = c.substringBefore(':') to c.substringAfter(':', "")
            when {
                ':' in c && kind in SkillScope.SCOPED_GROUPS -> if (!ID.matches(id) || id.length > 64) fail("“${c.take(80)}” names an invalid $kind id.")
                ':' in c -> fail("Unknown capability “${c.take(80)}”. Only ${SkillScope.SCOPED_GROUPS.joinToString { "$it:<id>" }} take an id.")
                c in SkillScope.GROUPS || c in SkillScope.knownTools() -> Unit
                else -> fail("Unknown capability “${c.take(80)}”. Use a tool group (${SkillScope.GROUPS.keys.joinToString()}), a tool name, or mcp:<server>.")
            }
        }

        /** Whether [host] is a valid `network` entry as written. */
        fun isHost(host: String): Boolean = runCatching { checkHost(host) == host }.getOrDefault(false)

        private fun checkHost(raw: String): String {
            val h = raw.lowercase().trimEnd('.')
            if ("://" in h || '/' in h) fail("network lists hosts, not links: write “${h.substringAfter("://").substringBefore('/').take(60)}” instead of “${raw.take(60)}”.")
            if (h == "*") return h
            if (':' in h) fail("network host “${raw.take(60)}” has a port; list just the host.")
            if (!(HOST.matches(h) && '.' in h.removePrefix("*.")) && !IPV4.matches(h) && h != "localhost") {
                fail("“${raw.take(60)}” isn't a host. Use a name like api.example.com, *.example.com for its subdomains, or * for any host.")
            }
            return h
        }

        private fun dependencies(entry: SkillFrontmatter.Entry): SkillDependencies {
            if (entry.rest.isNotEmpty()) fail("dependencies is a map: put apk:, npm:, pip: or skills: on the lines under it.")
            val nested = nestedMap(entry.following)
            nested.keys.firstOrNull { it !in DEPENDENCY_KINDS }?.let { fail("Unknown dependency kind “${it.take(40)}”. Use ${DEPENDENCY_KINDS.joinToString()}.") }
            fun of(kind: String, pattern: Regex, example: String) = nested[kind]?.let { list("dependencies.$kind", it) }.orEmpty().onEach {
                if (!pattern.matches(it) || it.length > 200) fail("The $kind dependency “${it.take(60)}” isn't valid. Write it like “$example”.")
            }
            val skills = of("skills", AgentHome.SKILL_NAME, "pdf-basics")
            return SkillDependencies(
                apk = of("apk", APK, "poppler-utils"),
                npm = of("npm", NPM, "marked@12.0.2"),
                pip = of("pip", PIP, "pypdf==4.2.0"),
                skills = skills,
            )
        }

        /** Two-level maps: `key:` lines at the smallest indent, each with its own value lines. */
        private fun nestedMap(lines: List<String>): Map<String, SkillFrontmatter.Entry> {
            val content = lines.filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
            if (content.isEmpty()) return emptyMap()
            val indent = content.minOf { l -> l.indexOfFirst { !it.isWhitespace() } }
            val out = LinkedHashMap<String, SkillFrontmatter.Entry>()
            var key: String? = null
            var rest = ""
            val following = mutableListOf<String>()
            fun flush() {
                key?.let { out[it] = SkillFrontmatter.Entry(rest, following.toList()) }
                following.clear()
            }
            content.forEach { line ->
                val at = line.indexOfFirst { !it.isWhitespace() }
                val trimmed = line.trim()
                if (at == indent && !trimmed.startsWith("- ") && trimmed != "-") {
                    val colon = trimmed.indexOf(':').takeIf { it > 0 } ?: fail("dependencies has a line that isn't “kind: …”: “${trimmed.take(60)}”.")
                    flush()
                    val k = trimmed.substring(0, colon).trim()
                    if (k in out) fail("dependencies lists $k twice.")
                    key = k
                    rest = trimmed.substring(colon + 1).trim()
                } else {
                    if (key == null) fail("dependencies has an item before any kind (apk:, npm:, pip:, skills:).")
                    following += line
                }
            }
            flush()
            return out
        }

        /** A YAML scalar: plain or quoted, on one line. */
        private fun scalar(field: String, entry: SkillFrontmatter.Entry): String {
            if (entry.following.any { it.isNotBlank() }) fail("$field must be a single value on its line.")
            val v = SkillFrontmatter.unquote(entry.rest.substringBefore(" #")).trim()
            if (v.isEmpty()) fail("$field is empty.")
            return v
        }

        /**
         * A YAML list: flow (`[a, b]`), block (`- a` lines), or empty (`[]`). A bare scalar is refused, so
         * a typo such as `capabilities: linux web` can't quietly become one odd entry.
         */
        private fun list(field: String, entry: SkillFrontmatter.Entry): List<String> {
            val rest = entry.rest.substringBefore(" #").trim()
            val items = when {
                rest.startsWith("[") -> {
                    if (!rest.endsWith("]") || entry.following.any { it.isNotBlank() }) fail("$field: write the list on one line, like [a, b], or as - items under it.")
                    rest.substring(1, rest.length - 1).split(',').map { it.trim() }.filter { it.isNotEmpty() }
                }
                rest.isEmpty() -> entry.following.filter { it.isNotBlank() && !it.trimStart().startsWith("#") }.map { line ->
                    val t = line.trim()
                    if (!t.startsWith("- ")) fail("$field: each item goes on its own line starting with “- ”.")
                    t.removePrefix("- ").substringBefore(" #").trim()
                }
                else -> fail("$field must be a list, like [${rest.take(30)}] or - items on the lines under it.")
            }.map { SkillFrontmatter.unquote(it).trim() }
            if (items.any { it.isEmpty() }) fail("$field has an empty item.")
            if (items.size > MAX_ITEMS) fail("$field lists ${items.size} items; the limit is $MAX_ITEMS.")
            items.firstOrNull { it.length > 200 }?.let { fail("$field has an item longer than 200 characters.") }
            return items
        }
    }
}
