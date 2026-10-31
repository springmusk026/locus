package dev.locus.core.agent.home

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * What the agent learns and builds over time, kept in plain files the user can inspect:
 *
 * ```
 * home/
 *   IDENTITY.md, SOUL.md,       persona files the user writes (name, personality, who they are,
 *   USER.md, AGENTS.md          house rules), loaded into every new chat
 *   memory/MEMORY.md           index, one line per topic, loaded into every new chat
 *   memory/<topic>.md           details, read on demand
 *   skills/<name>/SKILL.md      active skills (Agent Skills format, agentskills.io)
 *   skills-drafts/<name>/       skills the agent proposed, waiting for the user's review
 *   connectors/<id>.json        active HTTP API connectors
 *   connectors-drafts/<id>.json connectors the agent proposed, waiting for review
 *   agents/<name>/AGENT.md      helper (sub-agent) definitions: frontmatter + system prompt body
 *   agents-drafts/<name>/       helper definitions the agent proposed, waiting for review
 *   history/                    every replaced or removed version, for rollback
 * ```
 *
 * It lives outside the workspace on purpose: the agent can only change skills and connectors
 * through tools that produce drafts, never by editing files directly.
 */
class AgentHome(val dir: File, private val clock: () -> Long = System::currentTimeMillis) {
    val memoryDir = File(dir, "memory")
    val skillsDir = File(dir, "skills")
    val skillDraftsDir = File(dir, "skills-drafts")
    val connectorsDir = File(dir, "connectors")
    val connectorDraftsDir = File(dir, "connectors-drafts")
    val agentsDir = File(dir, "agents")
    val agentDraftsDir = File(dir, "agents-drafts")
    private val historyDir = File(dir, "history")
    private val index = File(memoryDir, "MEMORY.md")

    init {
        listOf(memoryDir, skillsDir, skillDraftsDir, connectorsDir, connectorDraftsDir, agentsDir, agentDraftsDir, historyDir).forEach { it.mkdirs() }
    }

    // ---- Memory ---------------------------------------------------------------------------------

    data class MemoryTopic(val name: String, val summary: String, val content: String, val modified: Long)

    fun memoryIndex(): String = if (index.exists()) index.readText().trim() else ""

    fun memoryTopics(): List<MemoryTopic> = memoryDir.listFiles { f -> f.isFile && f.name.endsWith(".md") && f != index }
        .orEmpty()
        .map { f -> MemoryTopic(f.nameWithoutExtension, summaryFor(f.nameWithoutExtension), f.readText(), f.lastModified()) }
        .sortedByDescending { it.modified }

    fun readMemory(topic: String): String? = memoryFile(topic).takeIf { it.exists() }?.readText()

    /** Saves a topic and its one-line summary in the index. Returns true if it replaced an older version. */
    fun saveMemory(topic: String, summary: String, content: String): Boolean {
        val file = memoryFile(topic)
        val existed = file.exists()
        if (existed) archive(file, "memory")
        file.writeText(content.trim() + "\n")
        writeIndex(indexEntries() + (slug(topic) to summary.lineSequence().first().take(150)))
        return existed
    }

    fun forgetMemory(topic: String): Boolean {
        val file = memoryFile(topic)
        if (!file.exists()) return false
        archive(file, "memory")
        file.delete()
        writeIndex(indexEntries() - slug(topic))
        return true
    }

    /** Case-insensitive keyword search over topic names, summaries and contents. */
    fun searchMemory(query: String, limit: Int = 8): List<Pair<String, String>> {
        val words = query.lowercase().split(Regex("\\W+")).filter { it.length > 2 }
        if (words.isEmpty()) return emptyList()
        return memoryTopics()
            .map { t ->
                val hay = (t.name + " " + t.summary + " " + t.content).lowercase()
                t to words.sumOf { w -> Regex(Regex.escape(w)).findAll(hay).count() }
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { (t, _) ->
                val line = t.content.lineSequence().firstOrNull { l -> words.any { it in l.lowercase() } } ?: t.summary
                t.name to line.take(200)
            }
    }

    private fun memoryFile(topic: String) = File(memoryDir, "${slug(topic)}.md")

    private fun summaryFor(topic: String) = indexEntries()[topic].orEmpty()

    private fun indexEntries(): Map<String, String> = memoryIndex().lineSequence()
        .mapNotNull { Regex("""^- \[([^\]]+)]\([^)]*\)\s*[—-]\s*(.*)$""").find(it.trim()) }
        .associate { it.groupValues[1] to it.groupValues[2] }

    private fun writeIndex(entries: Map<String, String>) {
        index.writeText(entries.toSortedMap().entries.joinToString("\n") { (k, v) -> "- [$k]($k.md) — $v" } + "\n")
    }

    // ---- Skills ---------------------------------------------------------------------------------

    data class Skill(val name: String, val description: String, val body: String, val files: List<String>, val draft: Boolean)

    fun skills(): List<Skill> = loadSkills(skillsDir, draft = false)

    fun skillDrafts(): List<Skill> = loadSkills(skillDraftsDir, draft = true)

    fun skill(name: String): Skill? = loadSkill(File(skillsDir, name), draft = false)

    fun skillFile(name: String, path: String): String? {
        val root = File(skillsDir, name).canonicalFile
        val file = File(root, path).canonicalFile
        if (!file.path.startsWith(root.path + File.separator) || !file.isFile) return null
        return file.readText()
    }

    /** Writes a draft. It does nothing until the user approves it with [approveSkill]. */
    fun draftSkill(name: String, description: String, body: String, files: Map<String, String>) {
        require(SKILL_NAME.matches(name)) { "Skill names use lowercase letters, digits and single hyphens (max 64), e.g. \"weekly-report\"." }
        require(description.isNotBlank() && description.length <= 1024) { "Description must be 1–1024 characters." }
        val dir = File(skillDraftsDir, name)
        dir.deleteRecursively()
        dir.mkdirs()
        File(dir, "SKILL.md").writeText(skillMarkdown(name, description, body))
        files.forEach { (path, content) ->
            val target = File(dir, path).canonicalFile
            require(target.path.startsWith(dir.canonicalPath + File.separator) && !path.equals("SKILL.md", true)) { "Invalid file path $path" }
            target.parentFile?.mkdirs()
            target.writeText(content)
        }
    }

    fun approveSkill(name: String) {
        val draft = File(skillDraftsDir, name)
        val target = File(skillsDir, name)
        if (target.exists()) archive(target, "skills")
        target.deleteRecursively()
        check(draft.renameTo(target)) { "Could not activate skill $name" }
    }

    fun rejectSkill(name: String) {
        File(skillDraftsDir, name).deleteRecursively()
    }

    fun removeSkill(name: String) {
        val dir = File(skillsDir, name)
        if (dir.exists()) archive(dir, "skills")
        dir.deleteRecursively()
    }

    private fun loadSkills(root: File, draft: Boolean) =
        root.listFiles { f -> f.isDirectory }.orEmpty().mapNotNull { loadSkill(it, draft) }.sortedBy { it.name }

    private fun loadSkill(dir: File, draft: Boolean): Skill? {
        val md = File(dir, "SKILL.md").takeIf { it.isFile }?.readText() ?: return null
        val (front, body) = splitFrontmatter(md)
        val name = front["name"] ?: dir.name
        val files = dir.walkTopDown().filter { it.isFile && it.name != "SKILL.md" }.map { it.relativeTo(dir).path }.sorted().toList()
        return Skill(name, front["description"].orEmpty(), body.trim(), files, draft)
    }

    // ---- Connectors -----------------------------------------------------------------------------

    fun connectors(): List<Connector> = loadConnectors(connectorsDir)

    fun connectorDrafts(): List<Connector> = loadConnectors(connectorDraftsDir)

    fun connector(id: String): Connector? = File(connectorsDir, "$id.json").takeIf { it.isFile }?.let(::parseConnector)

    /** Adds a connector the user set up themselves; it is active immediately. */
    fun addConnector(connector: Connector) {
        connector.validate()
        val target = File(connectorsDir, "${connector.id}.json")
        if (target.exists()) archive(target, "connectors")
        target.writeText(json.encodeToString(Connector.serializer(), connector))
    }

    fun draftConnector(connector: Connector) {
        connector.validate()
        File(connectorDraftsDir, "${connector.id}.json").writeText(json.encodeToString(Connector.serializer(), connector))
    }

    fun approveConnector(id: String) {
        val draft = File(connectorDraftsDir, "$id.json")
        val target = File(connectorsDir, "$id.json")
        if (target.exists()) archive(target, "connectors")
        target.delete()
        check(draft.renameTo(target)) { "Could not activate connector $id" }
    }

    fun rejectConnector(id: String) {
        File(connectorDraftsDir, "$id.json").delete()
    }

    fun removeConnector(id: String) {
        val file = File(connectorsDir, "$id.json")
        if (file.exists()) archive(file, "connectors")
        file.delete()
    }

    private fun loadConnectors(root: File) =
        root.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().mapNotNull(::parseConnector).sortedBy { it.id }

    private fun parseConnector(file: File) = runCatching { json.decodeFromString(Connector.serializer(), file.readText()) }.getOrNull()

    // ---- Helper (sub-agent) definitions ------------------------------------------------------------

    /**
     * A helper the user (or the agent, as a draft) defined: `agents/<name>/AGENT.md` with frontmatter
     * (name, description, tools, model, maxSteps, effort, background) and the helper's instructions as
     * the body. Definitions only narrow what a helper may do: tools outside [tools] are denied, and the
     * user's own per-tool settings still apply.
     */
    data class AgentDefinition(
        val name: String,
        val description: String,
        /** Tool names, or prefixes ending in `*` (`browser_*`). */
        val tools: List<String>,
        /** "inherit" (the chat's model), "helper" (the helper model) or a model id on the chat's provider. */
        val model: String = "inherit",
        val maxSteps: Int? = null,
        /** off, low, medium, high or max; null follows the provider. */
        val effort: String? = null,
        val background: Boolean = false,
        val body: String,
        val draft: Boolean = false,
    )

    fun agents(): List<AgentDefinition> = loadAgents(agentsDir, draft = false)

    fun agentDrafts(): List<AgentDefinition> = loadAgents(agentDraftsDir, draft = true)

    fun agent(name: String): AgentDefinition? = loadAgent(File(agentsDir, name), draft = false)

    /** Writes a draft; it does nothing until the user approves it with [approveAgent]. */
    fun draftAgent(definition: AgentDefinition) {
        validateAgent(definition)
        val dir = File(agentDraftsDir, definition.name)
        dir.deleteRecursively()
        dir.mkdirs()
        File(dir, "AGENT.md").writeText(agentMarkdown(definition))
    }

    /** Adds or replaces a definition the user wrote themselves; active immediately. */
    fun saveAgent(definition: AgentDefinition) {
        validateAgent(definition)
        val dir = File(agentsDir, definition.name)
        if (dir.exists()) archive(dir, "agents")
        dir.deleteRecursively()
        dir.mkdirs()
        File(dir, "AGENT.md").writeText(agentMarkdown(definition))
    }

    fun approveAgent(name: String) {
        val draft = File(agentDraftsDir, name)
        val target = File(agentsDir, name)
        if (target.exists()) archive(target, "agents")
        target.deleteRecursively()
        check(draft.renameTo(target)) { "Could not activate helper $name" }
    }

    fun rejectAgent(name: String) {
        File(agentDraftsDir, name).deleteRecursively()
    }

    fun removeAgent(name: String) {
        val dir = File(agentsDir, name)
        if (dir.exists()) archive(dir, "agents")
        dir.deleteRecursively()
    }

    private fun loadAgents(root: File, draft: Boolean) =
        root.listFiles { f -> f.isDirectory }.orEmpty().mapNotNull { loadAgent(it, draft) }.sortedBy { it.name }

    private fun loadAgent(dir: File, draft: Boolean): AgentDefinition? {
        val md = File(dir, "AGENT.md").takeIf { it.isFile }?.readText() ?: return null
        val (front, body) = splitFrontmatter(md)
        return AgentDefinition(
            name = front["name"]?.takeIf { SKILL_NAME.matches(it) } ?: dir.name,
            description = front["description"].orEmpty(),
            tools = front["tools"].orEmpty().trim('[', ']').split(',').map { it.trim().trim('"', '\'') }.filter { it.isNotEmpty() },
            model = front["model"]?.takeIf { it.isNotBlank() } ?: "inherit",
            maxSteps = front["maxSteps"]?.toIntOrNull(),
            effort = front["effort"]?.lowercase()?.takeIf { it in AGENT_EFFORTS },
            background = front["background"]?.lowercase() == "true",
            body = body.trim(),
            draft = draft,
        )
    }

    // ---- Persona --------------------------------------------------------------------------------

    /**
     * Who the agent is and who it works for, as files the user writes (onboarding, then any time).
     * Names and roles follow OpenClaw's workspace files (docs.openclaw.ai/concepts/agent-workspace):
     * IDENTITY.md (name and vibe), SOUL.md (persona, tone, boundaries), USER.md (the user) and
     * AGENTS.md (operating rules). Every new conversation reads them through [snapshot].
     */
    enum class PersonaFile(val fileName: String, val tag: String) {
        IDENTITY("IDENTITY.md", "identity"),
        SOUL("SOUL.md", "soul"),
        USER("USER.md", "user_profile"),
        AGENTS("AGENTS.md", "operating_rules"),
    }

    fun readPersona(file: PersonaFile): String = File(dir, file.fileName).takeIf { it.isFile }?.readText()?.trim().orEmpty()

    /** Writes one persona file; blank content removes it. The old version goes to history. */
    fun writePersona(file: PersonaFile, content: String) {
        val target = File(dir, file.fileName)
        if (target.isFile && target.readText().trim() == content.trim()) return
        if (target.exists()) archive(target, "persona")
        if (content.isBlank()) target.delete() else target.writeText(content.trim() + "\n")
    }

    /** The name the user gave the agent in IDENTITY.md, or "Locus". */
    fun agentName(): String = nameIn(readPersona(PersonaFile.IDENTITY)) ?: DEFAULT_NAME

    // ---- Snapshot for new conversations ---------------------------------------------------------

    /**
     * The block added to a new conversation's system prompt: persona files, memory index, skill
     * catalog and connector catalog. Taken once per conversation so the prompt prefix never
     * changes mid-chat.
     */
    fun snapshot(): String = buildString {
        PersonaFile.entries.forEach { file ->
            val text = readPersona(file).take(PERSONA_BUDGET)
            if (text.isNotBlank()) {
                appendLine("<${file.tag} file=\"${file.fileName}\">")
                appendLine(text)
                appendLine("</${file.tag}>")
                appendLine()
            }
        }
        val memory = memoryIndex()
        appendLine("<memory_index>")
        appendLine(memory.ifBlank { "(empty)" })
        appendLine("</memory_index>")
        appendLine()
        appendLine("<available_skills>")
        skills().ifEmpty { null }?.forEach { appendLine("- ${it.name}: ${it.description}") } ?: appendLine("(none yet)")
        appendLine("</available_skills>")
        appendLine()
        appendLine("<available_connectors>")
        connectors().ifEmpty { null }?.forEach { c ->
            if (c.kind == "mcp") appendLine("- ${c.id} (MCP server): ${c.description} Use mcp_list_tools then mcp_call.")
            else appendLine("- ${c.id}: ${c.description} Operations: ${c.operations.joinToString { it.name }}")
        } ?: appendLine("(none yet)")
        append("</available_connectors>")
        // Only the catalogue (names and descriptions): the definitions are loaded when a helper starts.
        agents().takeIf { it.isNotEmpty() }?.let { list ->
            appendLine()
            appendLine()
            appendLine("<available_agents> (helper types the user defined, for agent_spawn)")
            list.forEach { appendLine("- ${it.name}: ${it.description}") }
            append("</available_agents>")
        }
    }

    // ---- History --------------------------------------------------------------------------------

    private fun archive(file: File, kind: String) {
        val dest = File(historyDir, "$kind/${clock()}-${file.name}")
        dest.parentFile?.mkdirs()
        file.copyRecursively(dest, overwrite = true)
    }

    companion object {
        val SKILL_NAME = Regex("^(?=.{1,64}$)[a-z0-9]+(-[a-z0-9]+)*$")
        val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

        const val DEFAULT_NAME = "Locus"

        /** Each persona file's share of the prompt; OpenClaw gives USER.md the same 4,000 characters. */
        const val PERSONA_BUDGET = 4_000

        private val NAME_LINE = Regex("""^\s*[-*]?\s*\**name\**\s*:\s*\**\s*(.+?)\s*\**\s*$""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))

        /** The `Name: …` line of an IDENTITY.md (or of a snapshot that contains one). */
        fun nameIn(identity: String): String? = NAME_LINE.find(identity)?.groupValues?.get(1)?.take(40)?.takeIf { it.isNotBlank() }

        fun identityMarkdown(name: String, vibe: String) = buildString {
            appendLine("# Identity")
            appendLine()
            appendLine("- Name: ${name.trim().ifBlank { DEFAULT_NAME }}")
            if (vibe.isNotBlank()) appendLine("- Vibe: ${vibe.trim().replace("\n", " ")}")
        }

        fun slug(s: String) = s.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(60).ifEmpty { "note" }

        val AGENT_EFFORTS = setOf("off", "low", "medium", "high", "max")
        private val TOOL_PATTERN = Regex("^[a-z0-9_]+\\*?$")

        fun validateAgent(d: AgentDefinition) {
            require(SKILL_NAME.matches(d.name)) { "Helper names use lowercase letters, digits and single hyphens (max 64), e.g. \"price-checker\"." }
            require(d.description.isNotBlank() && d.description.length <= 1024) { "Description must be 1–1024 characters." }
            require(d.tools.isNotEmpty()) { "List the tools the helper may use, e.g. \"web_search, web_read\" or \"browser_*\"." }
            d.tools.forEach { t ->
                require(TOOL_PATTERN.matches(t)) { "Invalid tool name \"$t\": use a tool name or a prefix ending in *." }
                require(!t.startsWith("agent_") && t != "agent*" && t != "*") { "Helpers can't use agent_* tools (only one level of helpers)." }
            }
            require(d.maxSteps == null || d.maxSteps in 1..25) { "maxSteps must be between 1 and 25." }
            require(d.effort == null || d.effort in AGENT_EFFORTS) { "effort must be off, low, medium, high or max." }
            require(d.model.isNotBlank() && !d.model.contains('\n')) { "model must be inherit, helper or a model id." }
            require(d.body.isNotBlank()) { "Write the helper's instructions (its system prompt)." }
        }

        fun agentMarkdown(d: AgentDefinition) = buildString {
            appendLine("---")
            appendLine("name: ${d.name}")
            appendLine("description: ${d.description.replace("\n", " ").trim()}")
            appendLine("tools: ${d.tools.joinToString(", ")}")
            appendLine("model: ${d.model.trim()}")
            d.maxSteps?.let { appendLine("maxSteps: $it") }
            d.effort?.let { appendLine("effort: $it") }
            if (d.background) appendLine("background: true")
            appendLine("---")
            appendLine()
            appendLine(d.body.trim())
        }

        fun skillMarkdown(name: String, description: String, body: String) =
            "---\nname: $name\ndescription: ${description.replace("\n", " ").trim()}\n---\n\n${body.trim()}\n"

        /** Splits `---` YAML frontmatter (simple `key: value` lines) from the Markdown body. */
        fun splitFrontmatter(md: String): Pair<Map<String, String>, String> {
            if (!md.startsWith("---")) return emptyMap<String, String>() to md
            val end = md.indexOf("\n---", 3)
            if (end < 0) return emptyMap<String, String>() to md
            val front = md.substring(3, end).lineSequence()
                .mapNotNull { line -> line.indexOf(':').takeIf { it > 0 }?.let { line.substring(0, it).trim() to line.substring(it + 1).trim().trim('"') } }
                .toMap()
            return front to md.substring(end + 4)
        }
    }
}

/** An HTTP API the agent can call. Credentials are referenced by name and injected at call time. */
@Serializable
data class Connector(
    val id: String,
    /** "http" (operations defined here) or "mcp" (a Model Context Protocol server at baseUrl). */
    val kind: String = "http",
    val name: String,
    val description: String,
    val baseUrl: String,
    val auth: Auth = Auth(),
    val operations: List<Operation>,
    val createdBy: String = "agent",
    /**
     * Optional JavaScript run in an isolated sandbox with no network, files or secrets. It may define
     * `request(operation, args)` returning overrides `{method, path, query, body}` and
     * `response(operation, status, text)` returning the text to give the agent.
     */
    val script: String = "",
) {
    @Serializable
    data class Auth(
        /** none, bearer, header or query. */
        val type: String = "none",
        /** Header or query parameter name for `header` / `query`. */
        val name: String = "",
        /** Name of the secret in the vault. The model never sees its value. */
        val secret: String = "",
    )

    @Serializable
    data class Operation(
        val name: String,
        val description: String,
        val method: String = "GET",
        /** Path appended to baseUrl; `{param}` placeholders are filled from arguments. */
        val path: String,
        val params: List<Param> = emptyList(),
        /** read, write or destructive. Anything but read asks the user before each call. */
        val sideEffect: String = "read",
    )

    @Serializable
    data class Param(
        val name: String,
        val description: String = "",
        /** path, query or body. */
        val location: String = "query",
        val required: Boolean = false,
    )

    val host: String get() = baseUrl.substringAfter("://").substringBefore('/').substringBefore(':')

    fun validate() {
        require(Regex("^[a-z0-9]+(-[a-z0-9]+)*$").matches(id) && id.length <= 48) { "Connector id must look like \"todoist\" or \"home-assistant\"." }
        require(baseUrl.startsWith("https://") || baseUrl.startsWith("http://")) { "baseUrl must be http(s)." }
        require(kind in setOf("http", "mcp")) { "kind must be http or mcp." }
        require(kind == "mcp" || operations.isNotEmpty()) { "Add at least one operation." }
        require(auth.type in setOf("none", "bearer", "header", "query") || (auth.type == "oauth" && kind == "mcp")) {
            "auth.type must be none, bearer, header or query (oauth only for MCP servers the user signs in to)."
        }
        require(auth.type == "none" || auth.secret.isNotBlank()) { "Name the secret the user should store (auth.secret)." }
        operations.forEach { op ->
            require(op.method.uppercase() in setOf("GET", "POST", "PUT", "PATCH", "DELETE")) { "Unsupported method ${op.method}" }
            require(op.sideEffect in setOf("read", "write", "destructive")) { "sideEffect must be read, write or destructive" }
            require(!op.path.contains("://")) { "Operation paths are relative to baseUrl." }
        }
    }
}
