package dev.locus.core.agent.home

/**
 * The `---` frontmatter of SKILL.md and AGENT.md files.
 *
 * Only the YAML that skill files use in practice is understood: top-level `key: value` lines (plain,
 * 'single' or "double" quoted), plain values continued on indented lines, and `|` / `>` block
 * scalars. Nested maps and lists (`metadata:`, `tools:` with `- item` lines) are skipped; their key
 * maps to an empty string. That is enough for the fields Locus reads and validates.
 */
object SkillFrontmatter {

    /** Frontmatter fields and the Markdown body, or null when [md] doesn't start with a frontmatter block. */
    fun parse(md: String): Pair<Map<String, String>, String>? {
        val text = md.removePrefix("﻿").replace("\r\n", "\n")
        val lines = text.split('\n')
        if (lines.firstOrNull()?.trimEnd() != "---") return null
        val end = (1 until lines.size).firstOrNull { lines[it].trimEnd() == "---" } ?: return null
        val body = lines.drop(end + 1).joinToString("\n")
        return fields(lines.subList(1, end)) to body
    }

    private fun fields(lines: List<String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            i++
            if (line.isBlank() || line.trimStart().startsWith("#") || line[0].isWhitespace() || line.startsWith("- ")) continue
            val colon = line.indexOf(':').takeIf { it > 0 } ?: continue
            val key = line.substring(0, colon).trim()
            val rest = line.substring(colon + 1).trim()
            // Indented lines that follow belong to this key: a block scalar, a continued value or a nested map.
            val start = i
            while (i < lines.size && (lines[i].isBlank() || lines[i][0].isWhitespace() || lines[i].startsWith("- "))) i++
            val following = lines.subList(start, i)
            out[key] = when {
                rest.startsWith("|") || rest.startsWith(">") -> block(following, folded = rest.startsWith(">"))
                rest.isEmpty() -> ""
                rest.startsWith("\"") || rest.startsWith("'") -> unquote((listOf(rest) + following.map { it.trim() }).joinToString(" ").trim())
                else -> (listOf(rest) + following.map { it.trim() }.filter { it.isNotEmpty() }).joinToString(" ")
            }
        }
        return out
    }

    private fun block(lines: List<String>, folded: Boolean): String {
        val content = lines.dropLastWhile { it.isBlank() }
        val indent = content.filter { it.isNotBlank() }.minOfOrNull { l -> l.indexOfFirst { !it.isWhitespace() } } ?: 0
        val dedented = content.map { if (it.isBlank()) "" else it.substring(indent) }
        if (!folded) return dedented.joinToString("\n")
        return dedented.joinToString("\n").split("\n\n").joinToString("\n") { p -> p.lines().joinToString(" ") { it.trim() } }
    }

    private fun unquote(v: String): String = when {
        v.length >= 2 && v.startsWith("\"") && v.endsWith("\"") -> v.substring(1, v.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
        v.length >= 2 && v.startsWith("'") && v.endsWith("'") -> v.substring(1, v.length - 1).replace("''", "'")
        else -> v.trim('"', '\'')
    }

    /**
     * Checks a SKILL.md against the Agent Skills specification (agentskills.io/specification):
     * `name` 1–64 lowercase letters, digits and single hyphens; `description` 1–1024 characters;
     * `compatibility` at most 500 characters. Anthropic's rule against XML tags in name and description
     * is also enforced, because the description is placed in Locus's own system prompt.
     * Returns the frontmatter fields, or throws [SkillImportException] with a message for the user.
     */
    fun validate(md: String): Map<String, String> {
        val (front, _) = parse(md)
            ?: throw SkillImportException("SKILL.md must start with a frontmatter block: a line with ---, then name: and description:, then another ---.")
        val name = front["name"]?.trim().orEmpty()
        if (name.isEmpty()) throw SkillImportException("SKILL.md has no name in its frontmatter.")
        if (!AgentHome.SKILL_NAME.matches(name)) {
            throw SkillImportException("The skill name “${name.take(80)}” isn't valid. Names use 1–64 lowercase letters, digits and single hyphens, like “pdf-tools”.")
        }
        val description = front["description"]?.trim().orEmpty()
        if (description.isEmpty()) throw SkillImportException("SKILL.md has no description in its frontmatter.")
        if (description.length > 1024) throw SkillImportException("The description is ${description.length} characters; the limit is 1024.")
        if (XML_TAG.containsMatchIn(description)) throw SkillImportException("The description contains XML tags, which skills may not use.")
        front["compatibility"]?.let { if (it.length > 500) throw SkillImportException("The compatibility field is ${it.length} characters; the limit is 500.") }
        return front
    }

    /** Sets the top-level `name:` line of a SKILL.md, keeping everything else as it was. */
    fun rename(md: String, newName: String): String {
        val text = md.removePrefix("﻿").replace("\r\n", "\n")
        val lines = text.split('\n').toMutableList()
        val end = (1 until lines.size).firstOrNull { lines[it].trimEnd() == "---" } ?: return text
        val at = (1 until end).firstOrNull { NAME_LINE.matches(lines[it]) }
        if (at != null) lines[at] = "name: $newName" else lines.add(1, "name: $newName")
        return lines.joinToString("\n")
    }

    private val NAME_LINE = Regex("^name\\s*:.*$")
    private val XML_TAG = Regex("</?[A-Za-z][A-Za-z0-9_:-]*(\\s[^<>]*)?/?>")
}
