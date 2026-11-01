package dev.locus.core.agent.home

import dev.locus.core.agent.SkillScope

/**
 * The manifest Locus drafts for a skill the agent proposed with `skill_create`: what its instructions
 * and files visibly use. Tool names it mentions give their groups; links give network hosts; scripts,
 * shell words and `apk add` / `pip install` / `npm install` lines give `linux` and the packages.
 *
 * It is a starting point for the user's review, not a proof: the draft is marked as inferred and the
 * user approves it like any other change. A skill in which nothing is found stays undeclared, because
 * an empty manifest would make every call ask while the skill is active.
 */
object SkillManifestInference {

    fun infer(body: String, files: Map<String, String>): SkillManifest {
        val text = (listOf(body) + files.values).joinToString("\n")
        val lower = text.lowercase()
        val caps = linkedSetOf<String>()
        // Tool names written out, e.g. "call run_command" or `fetch_url`.
        SkillScope.knownTools().filter { !SkillScope.isBaseline(it) && Regex("(?<![a-z0-9_])${Regex.escape(it)}(?![a-z0-9_])").containsMatchIn(lower) }
            .sorted().forEach { tool -> caps += SkillScope.groupOf(tool) ?: tool }
        PHRASES.forEach { (group, pattern) -> if (pattern.containsMatchIn(lower)) caps += group }
        if (files.keys.any(::isScript)) caps += "linux"

        val hosts = URL.findAll(text).mapNotNull { m -> dev.locus.core.model.LocalNetwork.hostOf(m.value)?.lowercase()?.trimEnd('.') }
            .filter { it != "localhost" && !it.endsWith(".localhost") && SkillManifest.isHost(it) }.toSortedSet()
        // A link the skill opens is covered by its host; fetch_url and web_read need no group of their own.
        if (hosts.isNotEmpty() && !Regex("web_search|wikipedia|weather|search the web|web search").containsMatchIn(lower)) caps -= "web"

        val deps = SkillDependencies(
            apk = packages(lower, APK_ADD),
            pip = packages(lower, PIP_INSTALL).filter { !it.endsWith(".txt") },
            npm = packages(lower, NPM_INSTALL),
        )
        if (!deps.isEmpty) caps += "linux"
        if (caps.isEmpty() && hosts.isEmpty() && deps.isEmpty) return SkillManifest.UNDECLARED
        return SkillManifest(declared = true, capabilities = caps.toList(), network = hosts.toList(), dependencies = deps)
    }

    private val PHRASES = listOf(
        "files.read" to Regex("\\b(read|open|list|search|look at)( the| a| each| all)? (files?|folders?|workspace)\\b"),
        "files.write" to Regex("\\b(write|save|create|edit|update|delete|rename)( it| them| the| a| each)?( to| into)?( the| a)? (files?|folders?)\\b"),
        "linux" to Regex("\\b(linux|shell|terminal|bash|python3?|node|apk add|pip install|npm install|npx)\\b"),
        "web" to Regex("\\b(search the web|web search|look (it |this |them )?up online)\\b"),
        "browser" to Regex("\\b(in the browser|open the browser|browser)\\b"),
        "ssh" to Regex("\\bssh\\b"),
    )

    private val URL = Regex("https?://[^\\s<>\"'()\\[\\]`]+", RegexOption.IGNORE_CASE)
    private val APK_ADD = Regex("\\bapk add\\s+([^\\n;&|`]+)")
    private val PIP_INSTALL = Regex("\\bpip3? install\\s+([^\\n;&|`]+)")
    private val NPM_INSTALL = Regex("\\bnpm (?:install|i)\\s+([^\\n;&|`]+)")

    /** Package names after an install command, without flags or shell punctuation. */
    private fun packages(text: String, command: Regex): List<String> =
        command.findAll(text).flatMap { m -> m.groupValues[1].trim().trimEnd('`', '.', ',').split(Regex("\\s+")) }
            .map { it.trim('`', '"', '\'', ',') }
            .filter { it.isNotEmpty() && !it.startsWith("-") && PACKAGE.matches(it) }
            .distinct().take(32).toList()

    private val PACKAGE = Regex("^(@[a-z0-9._-]+/)?[a-z0-9][a-z0-9._+-]*([=@<>~!]=?[0-9a-z.*+-]+)?$")

    private fun isScript(path: String) =
        path.startsWith("scripts/") || path.substringAfterLast('.', "").lowercase() in setOf("sh", "bash", "py", "js", "mjs", "ts", "rb", "pl", "php", "lua")
}
