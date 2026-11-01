package dev.locus.core.agent.home

import dev.locus.core.agent.PromptBudget
import dev.locus.core.model.ChatScopeSpec
import dev.locus.core.model.MemoryScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
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
 *   skills-drafts/<name>/       skills the agent proposed or the user imported, waiting for review
 *     .locus-source.json        where an imported skill came from (GitHub link and ref, or zip name)
 *   connectors/<id>.json        active connectors (HTTP APIs, remote and local MCP servers)
 *   connectors-drafts/<id>.json connectors the agent proposed, waiting for review
 *   agents/<name>/AGENT.md      helper (sub-agent) definitions: frontmatter + system prompt body
 *   agents-drafts/<name>/       helper definitions the agent proposed, waiting for review
 *   history/                    every replaced or removed version, for rollback
 * ```
 *
 * It lives outside the workspace on purpose: the agent can only change skills and connectors
 * through tools that produce drafts, never by editing files directly.
 */
class AgentHome(
    val dir: File,
    /** Seals which connectors the user approved; null (tests) trusts the files as they are. */
    private val connectorSeal: ConnectorSeal? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val memoryDir = File(dir, "memory")
    val skillsDir = File(dir, "skills")
    val skillDraftsDir = File(dir, "skills-drafts")
    val connectorsDir = File(dir, "connectors")
    val connectorDraftsDir = File(dir, "connectors-drafts")
    val agentsDir = File(dir, "agents")
    val agentDraftsDir = File(dir, "agents-drafts")
    private val historyDir = File(dir, "history")

    init {
        listOf(memoryDir, skillsDir, skillDraftsDir, connectorsDir, connectorDraftsDir, agentsDir, agentDraftsDir, historyDir).forEach { it.mkdirs() }
    }

    // ---- Memory ---------------------------------------------------------------------------------

    data class MemoryTopic(
        val name: String,
        val summary: String,
        val content: String,
        val modified: Long,
        /** Who saved the current version, from which chat, and whether the run had read outside content. Null for older notes. */
        val provenance: MemoryProvenance? = null,
    )

    /** The user's general memory, `memory/`. */
    val globalMemory = MemoryStore(memoryDir, "memory", ::archive, clock)

    /** Projects' own files: `projects/<id>/AGENTS.md` and `projects/<id>/memory/`. */
    val projectsDir = File(dir, "projects")

    /** A project's folder in agent-home; ids are Locus's own UUIDs, anything else is refused. */
    fun projectDir(projectId: String): File {
        require(PROJECT_ID.matches(projectId)) { "Not a project id: $projectId" }
        return File(projectsDir, projectId)
    }

    /** A project's own memory. */
    fun projectMemory(projectId: String): MemoryStore =
        MemoryStore(File(projectDir(projectId), "memory"), "projects/$projectId/memory", ::archive, clock)

    /** The project's instructions (its AGENTS.md); empty when there are none. */
    fun projectInstructions(projectId: String): String =
        File(projectDir(projectId), PROJECT_INSTRUCTIONS).takeIf { it.isFile }?.readText()?.trim().orEmpty()

    /** Replaces the project's instructions; the old version goes to history. Applies to new chats only. */
    fun writeProjectInstructions(projectId: String, content: String) {
        val file = File(projectDir(projectId).apply { mkdirs() }, PROJECT_INSTRUCTIONS)
        if (file.exists()) archive(file, "projects/$projectId")
        if (content.isBlank()) file.delete() else file.writeText(content.trim() + "\n")
    }

    /** Removes a project's instructions and memory (kept in history). */
    fun removeProject(projectId: String) {
        val dir = projectDir(projectId)
        if (dir.exists()) {
            archive(dir, "projects")
            dir.deleteRecursively()
        }
    }

    /**
     * The memory a chat's tools use: global memory outside projects; in a project, the project's
     * memory, falling back to global memory for reads unless the scope is project-only.
     */
    fun memory(scope: ChatScopeSpec?): MemoryView = when {
        scope == null -> MemoryView(globalMemory, null)
        scope.memory == MemoryScope.PROJECT_ONLY -> MemoryView(projectMemory(scope.projectId), null)
        else -> MemoryView(projectMemory(scope.projectId), globalMemory)
    }

    fun memoryIndex(): String = globalMemory.memoryIndex()

    fun memoryTopics(): List<MemoryTopic> = globalMemory.memoryTopics()

    fun readMemory(topic: String): String? = globalMemory.readMemory(topic)

    fun saveMemory(
        topic: String,
        summary: String,
        content: String,
        by: String? = null,
        chatId: String? = null,
        taintedBy: Collection<String> = emptyList(),
    ): Boolean = globalMemory.saveMemory(topic, summary, content, by, chatId, taintedBy)

    fun forgetMemory(topic: String): Boolean = globalMemory.forgetMemory(topic)

    fun searchMemory(query: String, limit: Int = 8): List<Pair<String, String>> = globalMemory.searchMemory(query, limit)

    // ---- Skills ---------------------------------------------------------------------------------

    data class Skill(
        val name: String,
        val description: String,
        val body: String,
        val files: List<String>,
        val draft: Boolean,
        /** Set for imported skills: where they came from. */
        val source: SkillSource? = null,
        /** The whole SKILL.md as written, frontmatter included, for review. */
        val raw: String = "",
    )

    fun skills(): List<Skill> = loadSkills(skillsDir, draft = false)

    fun skillDrafts(): List<Skill> = loadSkills(skillDraftsDir, draft = true)

    fun skill(name: String): Skill? = loadSkill(File(skillsDir, name), draft = false)

    fun skillFile(name: String, path: String): String? {
        val root = File(skillsDir, name).canonicalFile
        val file = File(root, path).canonicalFile
        if (!file.path.startsWith(root.path + File.separator) || !file.isFile) return null
        return file.readText()
    }

    fun hasSkill(name: String): Boolean = File(File(skillsDir, name), "SKILL.md").isFile

    fun hasSkillDraft(name: String): Boolean = File(File(skillDraftsDir, name), "SKILL.md").isFile

    /** The bytes of a file in an active skill or a draft, for review; null outside the skill's folder. */
    fun skillFileBytes(name: String, path: String, draft: Boolean): ByteArray? {
        if (!SKILL_NAME.matches(name)) return null
        val root = File(if (draft) skillDraftsDir else skillsDir, name).canonicalFile
        val file = File(root, path).canonicalFile
        if (!file.path.startsWith(root.path + File.separator) || !file.isFile) return null
        return file.readBytes()
    }

    /**
     * Saves an imported skill as a draft named [name] (the bundle's own name, or a new one the user
     * chose to avoid a clash). It is never activated here: the user approves it in Library → Skills,
     * and approving over an active skill of the same name keeps the old one in history.
     */
    fun draftImportedSkill(bundle: SkillBundle, name: String = bundle.name) {
        require(SKILL_NAME.matches(name)) { "Skill names use lowercase letters, digits and single hyphens (max 64), e.g. \"weekly-report\"." }
        val dir = File(skillDraftsDir, name)
        dir.deleteRecursively()
        dir.mkdirs()
        val root = dir.canonicalPath + File.separator
        bundle.files.forEach { (path, bytes) ->
            val target = File(dir, path).canonicalFile
            require(target.path.startsWith(root) && path != "SKILL.md" && path != SkillImport.SOURCE_FILE) { "Invalid file path $path" }
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
        }
        File(dir, "SKILL.md").writeText(if (name == bundle.name) bundle.skillMd else SkillFrontmatter.rename(bundle.skillMd, name))
        File(dir, SkillImport.SOURCE_FILE).writeText(json.encodeToString(SkillSource.serializer(), bundle.source))
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
        val files = dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).path }
            .filter { it != "SKILL.md" && it != SkillImport.SOURCE_FILE }.sorted().toList()
        val source = File(dir, SkillImport.SOURCE_FILE).takeIf { it.isFile }
            ?.let { runCatching { json.decodeFromString(SkillSource.serializer(), it.readText()) }.getOrNull() }
        // Block-scalar descriptions span lines; the catalog in the system prompt keeps one line per skill.
        val description = front["description"].orEmpty().replace(Regex("\\s+"), " ").trim()
        return Skill(name, description, body.trim(), files, draft, source, md)
    }

    // ---- Connectors -----------------------------------------------------------------------------

    fun connectors(): List<Connector> {
        checkConnectorSeal()
        return loadConnectors(connectorsDir)
    }

    fun connectorDrafts(): List<Connector> = loadConnectors(connectorDraftsDir)

    fun connector(id: String): Connector? {
        checkConnectorSeal()
        return File(connectorsDir, "$id.json").takeIf { it.isFile }?.let(::parseConnector)
    }

    /** Adds a connector the user set up themselves; it is active immediately. */
    @Synchronized
    fun addConnector(connector: Connector) {
        connector.validate()
        checkConnectorSeal()
        val target = File(connectorsDir, "${connector.id}.json")
        if (target.exists()) archive(target, "connectors")
        target.writeText(json.encodeToString(Connector.serializer(), connector))
        connectorSeal?.write(connectorsDir)
    }

    fun draftConnector(connector: Connector) {
        connector.validate()
        File(connectorDraftsDir, "${connector.id}.json").writeText(json.encodeToString(Connector.serializer(), connector))
    }

    @Synchronized
    fun approveConnector(id: String) {
        checkConnectorSeal()
        val draft = File(connectorDraftsDir, "$id.json")
        val target = File(connectorsDir, "$id.json")
        if (target.exists()) archive(target, "connectors")
        target.delete()
        check(draft.renameTo(target)) { "Could not activate connector $id" }
        connectorSeal?.write(connectorsDir)
    }

    /**
     * Sends active connectors the user didn't approve (added or changed outside Locus: they fail the
     * [ConnectorSeal]) back to drafts, where Library offers them for review, and reports them. Runs
     * before every read and write of active connectors, so a write never seals what it didn't check.
     */
    @Synchronized
    private fun checkConnectorSeal() {
        val seal = connectorSeal ?: return
        val unapproved = seal.unapproved(connectorsDir)
        if (unapproved.isEmpty()) return
        unapproved.forEach { id ->
            val file = File(connectorsDir, "$id.json")
            val draft = File(connectorDraftsDir, "$id.json")
            archive(file, "connectors")
            if (draft.exists() || !file.renameTo(draft)) file.delete()
        }
        seal.write(connectorsDir)
        seal.report(unapproved)
    }

    fun rejectConnector(id: String) {
        File(connectorDraftsDir, "$id.json").delete()
    }

    @Synchronized
    fun removeConnector(id: String) {
        checkConnectorSeal()
        val file = File(connectorsDir, "$id.json")
        if (file.exists()) archive(file, "connectors")
        file.delete()
        connectorSeal?.write(connectorsDir)
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
     * changes mid-chat. Each part is held to its share of [budget]; what doesn't fit is replaced by a
     * note naming the tool that reaches the rest (memory_search, skill_use, …).
     */
    fun snapshot(budget: PromptBudget = PromptBudget.STANDARD, project: ProjectPrompt? = null): String = buildString {
        PersonaFile.entries.forEach { file ->
            val text = readPersona(file).take(PERSONA_BUDGET).let { full ->
                PromptBudget.fit(full, budget.persona) { "[… cut to fit this model's prompt budget; the full ${file.fileName} is in the app]" }
            }
            if (text.isNotBlank()) {
                appendLine("<${file.tag} file=\"${file.fileName}\">")
                appendLine(text)
                appendLine("</${file.tag}>")
                appendLine()
            }
        }
        if (project != null) appendProject(project, budget)
        val view = memory(project?.scope)
        // A project chat with general memory too splits the memory share between the two indexes.
        val share = if (view.fallback != null) budget.memory / 2 else budget.memory
        fun index(tag: String, store: MemoryStore) {
            val memory = store.memoryIndex()
            appendLine("<$tag>")
            appendLine(
                if (memory.isBlank()) "(empty)"
                else PromptBudget.fit(memory, share) { n -> "[… $n more topics not shown: use memory_search to find them, then memory_read]" },
            )
            appendLine("</$tag>")
            appendLine()
        }
        if (project == null) {
            index("memory_index", view.primary)
        } else {
            index("project_memory_index", view.primary)
            view.fallback?.let { index("memory_index", it) }
        }
        appendLine("<available_skills>")
        val skills = skills()
        appendLine(
            if (skills.isEmpty()) "(none yet)"
            else PromptBudget.fit(skills.joinToString("\n") { "- ${it.name}: ${it.description}" }, budget.skills) { n ->
                "[… $n more skills not shown: call skill_use with a likely name; an unknown name lists every skill]"
            },
        )
        appendLine("</available_skills>")
        appendLine()
        appendLine("<available_connectors>")
        val connectors = connectors().filter { project == null || project.allowedConnectors == null || it.id in project.allowedConnectors }
        appendLine(
            if (connectors.isEmpty()) "(none yet)"
            else PromptBudget.fit(
                connectors.joinToString("\n") { c ->
                    if (c.kind == "mcp") "- ${c.id} (${if (c.local != null) "local MCP server, runs in Linux" else "MCP server"}):${c.description} Use mcp_list_tools then mcp_call."
                    else "- ${c.id}: ${c.description} Operations: ${c.operations.joinToString { it.name }}"
                },
                budget.connectors,
            ) { n -> "[… $n more connectors not shown: connector_call with an unknown id lists every connector]" },
        )
        append("</available_connectors>")
        // Only the catalogue (names and descriptions): the definitions are loaded when a helper starts.
        agents().takeIf { it.isNotEmpty() }?.let { list ->
            appendLine()
            appendLine()
            appendLine("<available_agents> (helper types the user defined, for agent_spawn)")
            appendLine(
                PromptBudget.fit(list.joinToString("\n") { "- ${it.name}: ${it.description}" }, budget.agents) { n ->
                    "[… $n more helper types not shown: agent_spawn with an unknown type lists them]"
                },
            )
            append("</available_agents>")
        }
    }

    /** The `<project>` block of a project chat's prompt: where its files are, which memory it uses, its instructions. */
    private fun StringBuilder.appendProject(project: ProjectPrompt, budget: PromptBudget) {
        appendLine("<project>")
        appendLine(
            "This chat belongs to one of the user's projects. Your workspace is the project's folder: every file path, and " +
                "/workspace in Linux, is inside it, and files outside it are out of reach. If the user needs a file from elsewhere, " +
                "ask them to copy it into the project from the Workspace screen.",
        )
        appendLine(
            when (project.scope.memory) {
                MemoryScope.PROJECT_ONLY -> "Memory: only this project's memory (project_memory_index below). Save what you learn here to it."
                MemoryScope.PROJECT_AND_GLOBAL -> "Memory: this project's memory (project_memory_index) comes first, then the user's general memory (memory_index). memory_save writes to the project's memory."
            },
        )
        val instructions = projectInstructions(project.scope.projectId)
        if (instructions.isNotBlank()) {
            appendLine("<project_instructions file=\"AGENTS.md\">")
            appendLine(PromptBudget.fit(instructions.take(PROJECT_INSTRUCTIONS_MAX), budget.instructions) { "[… cut to fit this model's prompt budget; the full project instructions are in the app]" })
            appendLine("</project_instructions>")
        }
        appendLine("</project>")
        appendLine()
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

        /** The most of a project's instructions that goes into a prompt, before the budget. */
        const val PROJECT_INSTRUCTIONS_MAX = 16_000

        /** A project's instructions file in agent-home. */
        const val PROJECT_INSTRUCTIONS = "AGENTS.md"

        /** Project ids are UUIDs; checked before any path is built from one. */
        val PROJECT_ID = Regex("^[0-9a-fA-F-]{8,64}$")

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

        /** Splits `---` YAML frontmatter from the Markdown body; see [SkillFrontmatter] for what is understood. */
        fun splitFrontmatter(md: String): Pair<Map<String, String>, String> {
            SkillFrontmatter.parse(md)?.let { return it }
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
    /**
     * "http" (operations defined here) or "mcp" (a Model Context Protocol server: at [baseUrl] over
     * Streamable HTTP, or, with [local], a program started inside Locus's Linux system over stdio).
     */
    val kind: String = "http",
    val name: String,
    val description: String,
    /** Empty for a local MCP server. */
    val baseUrl: String = "",
    val auth: Auth = Auth(),
    val operations: List<Operation> = emptyList(),
    val createdBy: String = "agent",
    /**
     * Optional JavaScript run in an isolated sandbox with no network, files or secrets. It may define
     * `request(operation, args)` returning overrides `{method, path, query, body}` and
     * `response(operation, status, text)` returning the text to give the agent.
     */
    val script: String = "",
    /** Set for a local MCP server (stdio) run inside Linux; [baseUrl] is then empty and [auth] none. */
    val local: Local? = null,
) {
    /**
     * A local MCP server: [command] (plus [args]) run with `/bin/sh -c` in /workspace inside Locus's
     * Linux system, speaking MCP over its stdin and stdout.
     */
    @Serializable
    data class Local(
        /** e.g. `npx -y @modelcontextprotocol/server-filesystem /workspace`. */
        val command: String,
        /** Extra arguments, each passed as one shell word (quoted), after [command]. */
        val args: List<String> = emptyList(),
        /** Environment variables whose values come from the vault, injected at launch. */
        val env: List<EnvVar> = emptyList(),
    )

    /** An environment variable [name] set to the vault secret named [secret]. The value is never stored here. */
    @Serializable
    data class EnvVar(val name: String, val secret: String)

    val isLocalMcp: Boolean get() = kind == "mcp" && local != null

    /** Every vault secret this connector needs: its auth secret, or its local server's env secrets. */
    val secretNames: List<String> get() = when {
        local != null -> local.env.map { it.secret }.distinct()
        auth.type == "none" || auth.type == "oauth" -> emptyList()
        else -> listOf(auth.secret)
    }

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

    val host: String get() = if (local != null) "Linux on this phone" else baseUrl.substringAfter("://").substringBefore('/').substringBefore(':')

    fun validate() {
        require(Regex("^[a-z0-9]+(-[a-z0-9]+)*$").matches(id) && id.length <= 48) { "Connector id must look like \"todoist\" or \"home-assistant\"." }
        require(kind in setOf("http", "mcp")) { "kind must be http or mcp." }
        if (local != null) {
            validateLocal(local)
            return
        }
        require(baseUrl.startsWith("https://") || baseUrl.startsWith("http://")) { "baseUrl must be http(s)." }
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

    private fun validateLocal(local: Local) {
        require(kind == "mcp") { "Only MCP servers can run locally (kind \"mcp\")." }
        require(baseUrl.isBlank()) { "A local MCP server has no baseUrl." }
        require(auth.type == "none") { "A local MCP server gets its secrets through env, not auth." }
        require(operations.isEmpty() && script.isBlank()) { "A local MCP server has no operations or script." }
        require(local.command.isNotBlank() && local.command.length <= 1_000) { "Give the command that starts the server." }
        require('\n' !in local.command && '\r' !in local.command && '\u0000' !in local.command) { "The command must be one line." }
        require(local.args.size <= 64 && local.args.none { '\u0000' in it || it.length > 1_000 }) { "Too many or too long args." }
        require(local.env.size <= 32) { "Too many environment variables." }
        local.env.forEach { e ->
            require(Regex("^[A-Za-z_][A-Za-z0-9_]{0,63}$").matches(e.name)) { "Environment variable name “${e.name}” isn't valid." }
            require(e.name.uppercase() !in RESERVED_ENV && !e.name.uppercase().startsWith("PROOT_")) { "${e.name} is set by Locus and can't be overridden." }
            require(e.secret.isNotBlank()) { "Name the vault secret for ${e.name}." }
        }
        require(local.env.map { it.name }.distinct().size == local.env.size) { "Each environment variable can be set once." }
    }

    companion object {
        /** Set by Locus for proot and the shell; a connector can't replace them (e.g. to load other code). */
        private val RESERVED_ENV = setOf("PATH", "HOME", "USER", "LD_LIBRARY_PATH", "LD_PRELOAD", "LANG", "TERM", "TMPDIR", "TZ")
    }
}

/** Who saved a memory topic: [MemoryProvenance.by]. */
object MemoryAuthor {
    const val USER = "user"
    const val AGENT = "agent"

    /** The automatic pass after a long task (Settings → "Learn from tasks"). */
    const val REFLECTION = "reflection"
}

/** Where a memory topic's current version came from (see docs/design/taint.md). */
@Serializable
data class MemoryProvenance(
    /** A [MemoryAuthor]. */
    val by: String,
    val savedAt: Long,
    /** The chat it was saved in; null when the user saved it in Library. */
    val chatId: String? = null,
    /** The outside content the run had read when it saved this (tool names); empty when it had read none. */
    val taintedBy: List<String> = emptyList(),
) {
    val tainted: Boolean get() = taintedBy.isNotEmpty()
}

private val provenanceJson = Json { ignoreUnknownKeys = true }

/**
 * One memory: `MEMORY.md` (an index, one line per topic) and `<topic>.md` files, with each version's
 * provenance. The global memory is `agent-home/memory/`; each project has its own under
 * `agent-home/projects/<id>/memory/` (docs/design/projects.md).
 */
class MemoryStore internal constructor(
    val dir: File,
    /** Where a replaced or removed version goes, under agent-home's history. */
    private val archiveKind: String,
    private val archive: (File, String) -> Unit,
    private val clock: () -> Long,
) {
    private val index = File(dir, "MEMORY.md")

    init {
        dir.mkdirs()
    }

    fun memoryIndex(): String = if (index.exists()) index.readText().trim() else ""

    fun memoryTopics(): List<AgentHome.MemoryTopic> {
        val origins = provenance()
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".md") && f != index }
            .orEmpty()
            .map { f -> AgentHome.MemoryTopic(f.nameWithoutExtension, summaryFor(f.nameWithoutExtension), f.readText(), f.lastModified(), origins[f.nameWithoutExtension]) }
            .sortedByDescending { it.modified }
    }

    fun readMemory(topic: String): String? = memoryFile(topic).takeIf { it.exists() }?.readText()

    /**
     * Saves a topic and its one-line summary in the index. Returns true if it replaced an older version.
     * [by] (a [MemoryAuthor]), [chatId] and [taintedBy] are recorded as the version's provenance; with no
     * [by], nothing is known about it and any earlier record is dropped.
     */
    fun saveMemory(
        topic: String,
        summary: String,
        content: String,
        by: String? = null,
        chatId: String? = null,
        taintedBy: Collection<String> = emptyList(),
    ): Boolean {
        val file = memoryFile(topic)
        val existed = file.exists()
        if (existed) archive(file, archiveKind)
        file.writeText(content.trim() + "\n")
        writeIndex(indexEntries() + (AgentHome.slug(topic) to summary.lineSequence().first().take(150)))
        recordProvenance(AgentHome.slug(topic), by?.let { MemoryProvenance(it, clock(), chatId, taintedBy.sorted().distinct()) })
        return existed
    }

    fun forgetMemory(topic: String): Boolean {
        val file = memoryFile(topic)
        if (!file.exists()) return false
        archive(file, archiveKind)
        file.delete()
        writeIndex(indexEntries() - AgentHome.slug(topic))
        recordProvenance(AgentHome.slug(topic), null)
        return true
    }

    /** Where each topic's current version came from, by topic slug. Kept beside the notes, never shown to the model. */
    private val provenanceFile = File(dir, ".provenance.json")

    private fun provenance(): Map<String, MemoryProvenance> = runCatching {
        if (!provenanceFile.exists()) emptyMap() else provenanceJson.decodeFromString<Map<String, MemoryProvenance>>(provenanceFile.readText())
    }.getOrDefault(emptyMap())

    @Synchronized
    private fun recordProvenance(slug: String, entry: MemoryProvenance?) {
        val all = provenance().toMutableMap()
        if (entry == null) all.remove(slug) else all[slug] = entry
        if (all.isEmpty()) provenanceFile.delete() else provenanceFile.writeText(provenanceJson.encodeToString(all))
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

    private fun memoryFile(topic: String) = File(dir, "${AgentHome.slug(topic)}.md")

    private fun summaryFor(topic: String) = indexEntries()[topic].orEmpty()

    private fun indexEntries(): Map<String, String> = memoryIndex().lineSequence()
        .mapNotNull { Regex("""^- \[([^\]]+)]\([^)]*\)\s*[—-]\s*(.*)$""").find(it.trim()) }
        .associate { it.groupValues[1] to it.groupValues[2] }

    private fun writeIndex(entries: Map<String, String>) {
        index.writeText(entries.toSortedMap().entries.joinToString("\n") { (k, v) -> "- [$k]($k.md) — $v" } + "\n")
    }
}

/**
 * What a chat's memory tools see ([AgentHome.memory]): [primary] is where saves and forgets go, and is
 * read first; [fallback] (global memory, for a project chat that includes it) is only read.
 */
class MemoryView(val primary: MemoryStore, val fallback: MemoryStore?) {
    fun read(topic: String): String? = primary.readMemory(topic) ?: fallback?.readMemory(topic)

    /** Matches in [primary] first; matches from [fallback] are marked as general memory. */
    fun search(query: String, limit: Int = 8): List<Pair<String, String>> {
        val own = primary.searchMemory(query, limit)
        val general = fallback?.searchMemory(query, limit).orEmpty()
            .filter { (name, _) -> own.none { it.first == name } }
            .map { (name, line) -> name to "(general memory) $line" }
        return (own + general).take(limit)
    }

    fun save(topic: String, summary: String, content: String, by: String? = null, chatId: String? = null, taintedBy: Collection<String> = emptyList()) =
        primary.saveMemory(topic, summary, content, by, chatId, taintedBy)

    fun forget(topic: String): Boolean = primary.forgetMemory(topic)
}

/** What a project chat's prompt needs from its project, fixed when the chat is created. */
data class ProjectPrompt(
    val scope: ChatScopeSpec,
    /** Connectors the project allows; null for all. Only these are listed in the prompt. */
    val allowedConnectors: Set<String>?,
)
