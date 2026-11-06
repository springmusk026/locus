package dev.locus.core.agent.home

import dev.locus.core.agent.PromptBudget
import dev.locus.core.model.FileTrees
import dev.locus.core.model.ChatScopeSpec
import dev.locus.core.model.MemoryScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files

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
 *   persona-drafts/<FILE>.md    persona files changed outside Locus, held for the user's review
 *   history/                    every replaced or removed version, for rollback
 *   history/approved/           a copy of each approved skill, helper and persona file ([InstructionSeal])
 * ```
 *
 * It lives outside the workspace on purpose: the agent can only change skills and connectors
 * through tools that produce drafts, never by editing files directly.
 */
class AgentHome(
    val dir: File,
    /** Seals which connectors the user approved; null (tests) trusts the files as they are. */
    private val connectorSeal: ConnectorSeal? = null,
    /**
     * Seals which skills, helper definitions and persona files the user approved; null (tests) trusts
     * the files as they are. See docs/design/linux-isolation.md, "Sealed instructions".
     */
    private val instructionSeal: InstructionSeal? = null,
    /**
     * SQLite for the memory search index (the bundled driver in the app). Null (most tests) searches
     * by scanning the notes instead. See docs/design/memory-search.md.
     */
    private val sqlite: androidx.sqlite.SQLiteDriver? = null,
    /** Where the search indexes live: derived data, outside agent-home so backups skip it. */
    private val indexDir: File? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val memoryDir = File(dir, "memory")
    val skillsDir = File(dir, "skills")
    val skillDraftsDir = File(dir, "skills-drafts")
    val connectorsDir = File(dir, "connectors")
    val connectorDraftsDir = File(dir, "connectors-drafts")
    val agentsDir = File(dir, "agents")
    val agentDraftsDir = File(dir, "agents-drafts")
    val personaDraftsDir = File(dir, "persona-drafts")
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

    /** One search index per memory, kept open for the process. */
    private val indexes = java.util.concurrent.ConcurrentHashMap<String, MemorySearchIndex>()

    private fun indexFor(kind: String): MemorySearchIndex? {
        val driver = sqlite ?: return null
        val dir = indexDir ?: return null
        return indexes.getOrPut(kind) { MemorySearchIndex(driver, File(dir, kind.replace('/', '_') + ".db")) }
    }

    /** The user's general memory, `memory/`. */
    val globalMemory = MemoryStore(memoryDir, "memory", ::archive, clock, indexFor("memory"))

    /** Opens the general memory's search index, rebuilding it if it's missing or stale (at app start, off the main thread). */
    fun openMemoryIndex() {
        globalMemory.syncIndex()
    }

    /** Projects' own files: `projects/<id>/AGENTS.md` and `projects/<id>/memory/`. */
    val projectsDir = File(dir, "projects")

    /** A project's folder in agent-home; ids are Locus's own UUIDs, anything else is refused. */
    fun projectDir(projectId: String): File {
        require(PROJECT_ID.matches(projectId)) { "Not a project id: $projectId" }
        return File(projectsDir, projectId)
    }

    /** A project's own memory. */
    fun projectMemory(projectId: String): MemoryStore =
        MemoryStore(File(projectDir(projectId), "memory"), "projects/$projectId/memory", ::archive, clock, indexFor("projects/$projectId/memory"))

    /** A project's memory topics, newest first; empty (and nothing created) when it has saved none. */
    fun projectMemoryTopics(projectId: String): List<MemoryTopic> =
        if (File(projectDir(projectId), "memory").isDirectory) projectMemory(projectId).memoryTopics() else emptyList()

    /**
     * Moves [topic] from a project's memory to general memory, keeping its summary and provenance
     * (a note saved while reading outside content stays flagged). A general note of the same name is
     * replaced; both old versions go to history. False when the project has no such topic.
     */
    fun moveProjectMemoryToGlobal(projectId: String, topic: String): Boolean {
        val from = projectMemoryTopics(projectId).firstOrNull { it.name == topic } ?: return false
        val p = from.provenance
        globalMemory.saveMemory(from.name, from.summary, from.content, p?.by, p?.chatId, p?.taintedBy.orEmpty())
        projectMemory(projectId).forgetMemory(from.name)
        return true
    }

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
        // Its search index is derived from the notes: closed and deleted with them (also one left by an earlier run).
        indexFor("projects/$projectId/memory")?.delete()
        indexes.remove("projects/$projectId/memory")
        if (dir.exists()) {
            archive(dir, "projects")
            FileTrees.delete(dir)
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

    fun searchMemory(query: String, limit: Int = 8): List<MemoryHit> = globalMemory.searchMemory(query, limit)

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
        /** Its security manifest; [SkillManifest.declared] false for a skill that doesn't declare one. */
        val manifest: SkillManifest = SkillManifest.UNDECLARED,
        /** Why the manifest on disk didn't parse (it then declares nothing at all: fail closed). */
        val manifestProblem: String? = null,
        /** Locus inferred the manifest of this agent-drafted skill from its text; the user should check it. */
        val manifestInferred: Boolean = false,
        /**
         * A draft held for review because the active skill was changed outside Locus ([InstructionSeal]).
         * Locus doesn't use it; the last approved version, when one was kept, is active instead.
         */
        val changedOutside: Boolean = false,
    )

    fun skills(): List<Skill> {
        verify(InstructionSet.SKILLS)
        return loadSkills(skillsDir, draft = false)
    }

    /** Drafts, including skills held here because they were changed outside Locus ([Skill.changedOutside]). */
    fun skillDrafts(): List<Skill> {
        verify(InstructionSet.SKILLS)
        return loadSkills(skillDraftsDir, draft = true)
    }

    fun skill(name: String): Skill? {
        verify(InstructionSet.SKILLS)
        return namedDir(skillsDir, name)?.let { loadSkill(it, draft = false) }
    }

    /** A file bundled with active skill [name] (skill_read_file); null for a bad name or a path outside the skill's folder. */
    fun skillFile(name: String, path: String): String? {
        verify(InstructionSet.SKILLS)
        val root = namedDir(skillsDir, name) ?: return null
        val file = File(root, path).canonicalFile
        if (!file.path.startsWith(root.path + File.separator) || !file.isFile) return null
        return file.readText()
    }

    fun hasSkill(name: String): Boolean {
        verify(InstructionSet.SKILLS)
        return namedDir(skillsDir, name)?.let { File(it, "SKILL.md").isFile } == true
    }

    fun hasSkillDraft(name: String): Boolean = namedDir(skillDraftsDir, name)?.let { File(it, "SKILL.md").isFile } == true

    /** The bytes of a file in an active skill or a draft, for review; null outside the skill's folder. */
    fun skillFileBytes(name: String, path: String, draft: Boolean): ByteArray? {
        verify(InstructionSet.SKILLS)
        val root = namedDir(if (draft) skillDraftsDir else skillsDir, name) ?: return null
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
        val dir = requireNamedDir(skillDraftsDir, name, "skill")
        FileTrees.delete(dir)
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

    /**
     * Writes a draft. It does nothing until the user approves it with [approveSkill]. Its manifest is
     * inferred from what its text uses ([SkillManifestInference]) and marked as inferred, so the user
     * checks it in Library → Skills along with the rest.
     */
    fun draftSkill(name: String, description: String, body: String, files: Map<String, String>) {
        require(SKILL_NAME.matches(name)) { "Skill names use lowercase letters, digits and single hyphens (max 64), e.g. \"weekly-report\"." }
        require(description.isNotBlank() && description.length <= 1024) { "Description must be 1–1024 characters." }
        files.keys.forEach { require(!it.startsWith(".locus-") && !it.startsWith("./.locus-")) { "Invalid file path $it" } }
        val dir = requireNamedDir(skillDraftsDir, name, "skill")
        FileTrees.delete(dir)
        dir.mkdirs()
        val manifest = SkillManifestInference.infer(body, files)
        File(dir, "SKILL.md").writeText(skillMarkdown(name, description, body, manifest))
        File(dir, INFERRED_FILE).writeText("Locus inferred this skill's manifest from its text.\n")
        files.forEach { (path, content) ->
            val target = File(dir, path).canonicalFile
            require(target.path.startsWith(dir.canonicalPath + File.separator) && !path.equals("SKILL.md", true)) { "Invalid file path $path" }
            target.parentFile?.mkdirs()
            target.writeText(content)
        }
    }

    /** Activates a draft (one held because it was changed outside Locus too: approving it is the user's choice). */
    fun approveSkill(name: String) = changing(InstructionSet.SKILLS) {
        val draft = requireNamedDir(skillDraftsDir, name, "skill")
        val target = requireNamedDir(skillsDir, name, "skill")
        check(draft.isDirectory) { "There's no draft “$name” any more." }
        File(draft, InstructionSeal.CHANGED_FILE).delete()
        if (FileTrees.exists(target)) archive(target, "skills")
        FileTrees.delete(target)
        check(draft.renameTo(target)) { "Could not activate skill $name" }
    }

    fun rejectSkill(name: String) {
        FileTrees.delete(requireNamedDir(skillDraftsDir, name, "skill"))
    }

    fun removeSkill(name: String) = changing(InstructionSet.SKILLS) {
        val dir = requireNamedDir(skillsDir, name, "skill")
        if (FileTrees.exists(dir)) archive(dir, "skills")
        FileTrees.delete(dir)
    }

    private fun loadSkills(root: File, draft: Boolean) =
        root.listFiles { f -> f.isDirectory && !Files.isSymbolicLink(f.toPath()) }.orEmpty().mapNotNull { loadSkill(it, draft) }.sortedBy { it.name }

    private fun loadSkill(dir: File, draft: Boolean): Skill? {
        val md = File(dir, "SKILL.md").takeIf { it.isFile }?.readText() ?: return null
        val (front, body) = splitFrontmatter(md)
        val name = front["name"] ?: dir.name
        val files = dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).path }
            .filter { it != "SKILL.md" && it != SkillImport.SOURCE_FILE && it != INFERRED_FILE && it != InstructionSeal.CHANGED_FILE }.sorted().toList()
        val source = File(dir, SkillImport.SOURCE_FILE).takeIf { it.isFile }
            ?.let { runCatching { json.decodeFromString(SkillSource.serializer(), it.readText()) }.getOrNull() }
        // Block-scalar descriptions span lines; the catalog in the system prompt keeps one line per skill.
        val description = front["description"].orEmpty().replace(Regex("\\s+"), " ").trim()
        val (manifest, problem) = SkillManifest.read(md)
        return Skill(
            name, description, body.trim(), files, draft, source, md, manifest, problem, File(dir, INFERRED_FILE).isFile,
            changedOutside = draft && File(dir, InstructionSeal.CHANGED_FILE).isFile,
        )
    }

    /** [SkillManifest.contentHash] of a skill's files as they are now (read on demand: it reads every file). */
    fun skillContentHash(name: String, draft: Boolean): String? {
        if (!draft) verify(InstructionSet.SKILLS)
        val dir = namedDir(if (draft) skillDraftsDir else skillsDir, name) ?: return null
        val md = File(dir, "SKILL.md").takeIf { it.isFile }?.readText() ?: return null
        val files = dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).path }
            .filter { it != "SKILL.md" && !it.startsWith(".locus-") }.associateWith { File(dir, it).readBytes() }
        return SkillManifest.contentHash(md, files)
    }

    /** The manifest of the active skill [name], for [dev.locus.core.agent.SkillScope]; null when there's no such skill. */
    fun skillManifest(name: String): SkillManifest? {
        verify(InstructionSet.SKILLS)
        val md = File(namedDir(skillsDir, name) ?: return null, "SKILL.md").takeIf { it.isFile }?.readText() ?: return null
        return SkillManifest.read(md).first
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

    /**
     * Saves a connector the agent proposed, for the user's review. It may only name keys of its own:
     * not another connector's (active or drafted), and when it replaces a connector of the same id,
     * that connector's key only for the same address. Otherwise approving it would send a key the
     * user saved for one server to another (docs/design/mcp-connectors.md, "Keys belong to an address").
     */
    fun draftConnector(connector: Connector) {
        connector.validate()
        val others = (connectors() + connectorDrafts()).filter { it.id != connector.id }
        connector.secretNames.forEach { name ->
            others.firstOrNull { name in it.secretNames }?.let {
                throw IllegalArgumentException("“$name” is the key of the connector “${it.id}”. Name a new secret, e.g. “${connector.id}-token”; the user enters its value in Library → Connectors.")
            }
        }
        connector(connector.id)?.takeIf { it.local == null && connector.local == null }?.let { current ->
            val reused = connector.secretNames.intersect(current.secretNames.toSet())
            require(reused.isEmpty() || ConnectorKeys.origin(current.baseUrl) == ConnectorKeys.origin(connector.baseUrl)) {
                "“${reused.first()}” was saved for ${current.host}, so it can't be sent to ${connector.host}. Name a new secret, e.g. “${connector.id}-key”."
            }
        }
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
        /** A draft held for review because the active definition was changed outside Locus ([InstructionSeal]). */
        val changedOutside: Boolean = false,
    )

    fun agents(): List<AgentDefinition> {
        verify(InstructionSet.HELPERS)
        return loadAgents(agentsDir, draft = false)
    }

    /** Drafts, including definitions held here because they were changed outside Locus. */
    fun agentDrafts(): List<AgentDefinition> {
        verify(InstructionSet.HELPERS)
        return loadAgents(agentDraftsDir, draft = true)
    }

    fun agent(name: String): AgentDefinition? {
        verify(InstructionSet.HELPERS)
        return namedDir(agentsDir, name)?.let { loadAgent(it, draft = false) }
    }

    /** Writes a draft; it does nothing until the user approves it with [approveAgent]. */
    fun draftAgent(definition: AgentDefinition) {
        validateAgent(definition)
        val dir = requireNamedDir(agentDraftsDir, definition.name, "helper")
        FileTrees.delete(dir)
        dir.mkdirs()
        File(dir, "AGENT.md").writeText(agentMarkdown(definition))
    }

    /** Adds or replaces a definition the user wrote themselves; active immediately. */
    fun saveAgent(definition: AgentDefinition) = changing(InstructionSet.HELPERS) {
        validateAgent(definition)
        val dir = requireNamedDir(agentsDir, definition.name, "helper")
        if (FileTrees.exists(dir)) archive(dir, "agents")
        FileTrees.delete(dir)
        dir.mkdirs()
        File(dir, "AGENT.md").writeText(agentMarkdown(definition))
    }

    /** Activates a draft (one held because it was changed outside Locus too: approving it is the user's choice). */
    fun approveAgent(name: String) = changing(InstructionSet.HELPERS) {
        val draft = requireNamedDir(agentDraftsDir, name, "helper")
        val target = requireNamedDir(agentsDir, name, "helper")
        check(draft.isDirectory) { "There's no draft “$name” any more." }
        File(draft, InstructionSeal.CHANGED_FILE).delete()
        if (FileTrees.exists(target)) archive(target, "agents")
        FileTrees.delete(target)
        check(draft.renameTo(target)) { "Could not activate helper $name" }
    }

    fun rejectAgent(name: String) {
        FileTrees.delete(requireNamedDir(agentDraftsDir, name, "helper"))
    }

    fun removeAgent(name: String) = changing(InstructionSet.HELPERS) {
        val dir = requireNamedDir(agentsDir, name, "helper")
        if (FileTrees.exists(dir)) archive(dir, "agents")
        FileTrees.delete(dir)
    }

    private fun loadAgents(root: File, draft: Boolean) =
        root.listFiles { f -> f.isDirectory && !Files.isSymbolicLink(f.toPath()) }.orEmpty().mapNotNull { loadAgent(it, draft) }.sortedBy { it.name }

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
            changedOutside = draft && File(dir, InstructionSeal.CHANGED_FILE).isFile,
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

    /** A persona file as the user last approved it; a version changed outside Locus is never returned ([personaChanges]). */
    fun readPersona(file: PersonaFile): String {
        verify(InstructionSet.PERSONA)
        return File(dir, file.fileName).takeIf { it.isFile && !FileTrees.isLink(it) }?.readText()?.trim().orEmpty()
    }

    /** Writes one persona file; blank content removes it. The old version goes to history. */
    fun writePersona(file: PersonaFile, content: String) = changing(InstructionSet.PERSONA) {
        val target = File(dir, file.fileName)
        if (target.isFile && target.readText().trim() == content.trim()) return@changing
        if (FileTrees.exists(target)) archive(target, "persona")
        if (content.isBlank()) FileTrees.delete(target) else target.writeText(content.trim() + "\n")
    }

    /**
     * Persona files that were changed outside Locus, with the changed text, held in `persona-drafts/`
     * until the user keeps or discards the change. Meanwhile Locus uses the version the user approved
     * (or none, when no copy of it was kept).
     */
    fun personaChanges(): Map<PersonaFile, String> {
        verify(InstructionSet.PERSONA)
        return PersonaFile.entries.mapNotNull { f ->
            File(personaDraftsDir, f.fileName).takeIf { it.isFile && !FileTrees.isLink(it) }?.let { f to it.readText() }
        }.toMap()
    }

    /** The user read a persona change made outside Locus and keeps it: it becomes the approved version. */
    fun keepPersonaChange(file: PersonaFile) = synchronized(instructionLock) {
        val held = File(personaDraftsDir, file.fileName)
        check(held.isFile && !FileTrees.isLink(held)) { "That change isn't waiting for review any more." }
        writePersona(file, held.readText())
        FileTrees.delete(held)
    }

    /** The user discards a persona change made outside Locus; the approved version stays. */
    fun discardPersonaChange(file: PersonaFile) {
        val held = File(personaDraftsDir, file.fileName)
        if (FileTrees.exists(held)) archive(held, "persona-changes")
        FileTrees.delete(held)
    }

    /** The name the user gave the agent in IDENTITY.md, or "Locus". */
    fun agentName(): String = nameIn(readPersona(PersonaFile.IDENTITY)) ?: DEFAULT_NAME

    // ---- Snapshot for new conversations ---------------------------------------------------------

    /**
     * The block added to a new conversation's system prompt: persona files, memory index, skill
     * catalog and connector catalog ([withMemory] false leaves the memory index out: a temporary chat).
     * Taken once per conversation so the prompt prefix never
     * changes mid-chat. Each part is held to its share of [budget]; what doesn't fit is replaced by a
     * note naming the tool that reaches the rest (memory_search, skill_use, …).
     */
    fun snapshot(budget: PromptBudget = PromptBudget.STANDARD, project: ProjectPrompt? = null, withMemory: Boolean = true): String = buildString {
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
        // A temporary chat leaves memory out entirely (docs/design/temporary-chats.md).
        when {
            !withMemory -> Unit
            project == null -> index("memory_index", view.primary)
            else -> {
                index("project_memory_index", view.primary)
                view.fallback?.let { index("memory_index", it) }
            }
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

    // ---- Named folders --------------------------------------------------------------------------

    /**
     * The folder of the skill or helper [name] in [base], or null when [name] isn't a valid name
     * ([SKILL_NAME]), the folder is a symbolic link, or it doesn't resolve to a direct child of [base].
     * Every entry point that takes a skill or helper name goes through here: a name like "../.." would
     * otherwise reach Locus's other private files (skill_read_file is a READ tool that runs without asking).
     */
    private fun namedDir(base: File, name: String): File? {
        if (!SKILL_NAME.matches(name)) return null
        val dir = File(base, name)
        if (Files.isSymbolicLink(dir.toPath())) return null
        val canonical = dir.canonicalFile
        return canonical.takeIf { it.parentFile == base.canonicalFile }
    }

    private fun requireNamedDir(base: File, name: String, kind: String): File =
        requireNotNull(namedDir(base, name)) { "Invalid $kind name \"$name\"" }

    // ---- History --------------------------------------------------------------------------------

    private fun archive(file: File, kind: String) {
        val dest = File(historyDir, "$kind/${clock()}-${file.name}")
        dest.parentFile?.mkdirs()
        // Links are kept as links: a tree changed outside Locus may hold one, and it's never followed.
        FileTrees.copy(file, dest, overwrite = true)
    }

    // ---- Sealed instructions ------------------------------------------------------------------------

    private val instructionLock = Any()

    /**
     * Checks [set] against its [InstructionSeal]. Each item changed outside Locus is held for review
     * (skills and helpers as drafts marked [Skill.changedOutside], persona files in `persona-drafts/`),
     * the approved copy is put back when one was kept and still matches the seal, and the user is told.
     * Runs before every read and write of the set, so a write never seals what it didn't check.
     */
    private fun verify(set: InstructionSet) {
        val seal = instructionSeal ?: return
        synchronized(instructionLock) {
            val check = seal.check(set, dir)
            if (check.changed.isEmpty()) return
            check.changed.forEach { name ->
                // Fail closed: if it can't be held for review, it's removed rather than left in use.
                runCatching { holdChanged(set, name, check.approved[name]) }
                    .onFailure { FileTrees.delete(File(set.base(dir), name)) }
            }
            // If the Keystore can't seal right now, what's left is still safe (nothing changed is in
            // use); the next check holds anything new again. Reads must not fail because of it.
            runCatching { seal.write(set, dir) }
            seal.report(set, check.changed)
        }
    }

    private fun holdChanged(set: InstructionSet, name: String, approvedHash: String?) {
        val live = File(set.base(dir), name)
        if (FileTrees.exists(live)) {
            if (set.holdable(live) && (set == InstructionSet.PERSONA || SKILL_NAME.matches(name))) {
                val review = set.review(dir).apply { mkdirs() }
                val held = File(review, name)
                if (FileTrees.exists(held)) {
                    archive(held, review.name)
                    FileTrees.delete(held)
                }
                // A rename within agent-home: the changed version moves as it is, links unfollowed.
                Files.move(live.toPath(), held.toPath())
                if (set != InstructionSet.PERSONA) File(held, InstructionSeal.CHANGED_FILE).writeText("Changed outside Locus; found at ${clock()}.\n")
            } else {
                // A link, or the wrong kind of entry: nothing to review, and never followed.
                archive(live, "${set.base(dir).name}-changed")
                FileTrees.delete(live)
            }
        }
        val copy = File(set.approved(dir), name)
        if (approvedHash != null && TreeHash.of(copy) == approvedHash) FileTrees.copy(copy, live)
    }

    /** Runs [block], which changes [set], after checking the set, and seals the result. */
    private fun <T> changing(set: InstructionSet, block: () -> T): T = synchronized(instructionLock) {
        verify(set)
        val result = block()
        instructionSeal?.write(set, dir)
        result
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

        private val NOT_WORD = Regex("[^\\p{L}\\p{N}\\p{M}]+")

        /**
         * A memory topic's file name: like [slug], but letters and digits of every script are kept, so
         * "नेपाली खाना" and "東京の店" get their own files instead of all becoming "note". ASCII names
         * give the same result as [slug].
         */
        fun memorySlug(s: String): String {
            val folded = java.text.Normalizer.normalize(s.trim(), java.text.Normalizer.Form.NFC).lowercase(java.util.Locale.ROOT)
            val joined = NOT_WORD.replace(folded, "-").trim('-')
            // At most 60 characters, never cutting a surrogate pair.
            val end = if (joined.codePointCount(0, joined.length) > 60) joined.offsetByCodePoints(0, 60) else joined.length
            return joined.substring(0, end).trim('-').ifEmpty { "note" }
        }

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

        /** The draft's marker that its manifest was inferred by Locus, not written by anyone. */
        const val INFERRED_FILE = ".locus-inferred"

        fun skillMarkdown(name: String, description: String, body: String, manifest: SkillManifest = SkillManifest.UNDECLARED) = buildString {
            append("---\nname: $name\ndescription: ${description.replace("\n", " ").trim()}\n")
            if (manifest.declared) {
                append("# Inferred by Locus from what the instructions use; check it before approving.\n")
                manifest.version?.let { append("version: $it\n") }
                append("capabilities: [${manifest.capabilities.joinToString(", ")}]\n")
                append("network: [${manifest.network.joinToString(", ") { if (it.startsWith("*")) "\"$it\"" else it }}]\n")
                val deps = manifest.dependencies
                if (!deps.isEmpty) {
                    append("dependencies:\n")
                    listOf("apk" to deps.apk, "npm" to deps.npm, "pip" to deps.pip, "skills" to deps.skills).filter { it.second.isNotEmpty() }
                        .forEach { (kind, items) -> append("  $kind: [${items.joinToString(", ")}]\n") }
                }
            }
            append("---\n\n${body.trim()}\n")
        }

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
        require(secretNames.none(ConnectorKeys::reserved)) { "Built-in integration keys (integration-…) can't be used by a connector. Name a new secret, e.g. \"$id-token\"." }
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
        // A sign-in belongs to one connector: its session is stored under this name, never another's.
        require(auth.type != "oauth" || auth.secret == oauthSecret(id)) { "An oauth connector's auth.secret must be \"${oauthSecret(id)}\"." }
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
        /** The vault name of connector [id]'s OAuth session: the only one it may use. */
        fun oauthSecret(id: String) = "$id-oauth"

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
    /** The full-text index of these notes; null scans them instead (docs/design/memory-search.md). */
    private val searchIndex: MemorySearchIndex? = null,
) {
    private val index = File(dir, "MEMORY.md")

    init {
        dir.mkdirs()
    }

    fun memoryIndex(): String = if (index.exists()) index.readText().trim() else ""

    fun memoryTopics(): List<AgentHome.MemoryTopic> {
        val origins = provenance()
        val summaries = indexEntries()
        return topicFiles()
            .map { f -> AgentHome.MemoryTopic(f.nameWithoutExtension, summaries[f.nameWithoutExtension].orEmpty(), f.readText(), f.lastModified(), origins[f.nameWithoutExtension]) }
            .sortedByDescending { it.modified }
    }

    /** Whether [topic] is saved here (by its name as the model or the index gives it). */
    fun hasMemory(topic: String): Boolean = memoryFile(topic).exists()

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
        val key = file.nameWithoutExtension
        val existed = file.exists()
        if (existed) archive(file, archiveKind)
        file.writeText(content.trim() + "\n")
        val entries = indexEntries() + (key to summary.lineSequence().first().take(150))
        writeIndex(entries)
        recordProvenance(key, by?.let { MemoryProvenance(it, clock(), chatId, taintedBy.sorted().distinct()) })
        searchIndex?.put(doc(file, entries[key].orEmpty()))
        return existed
    }

    fun forgetMemory(topic: String): Boolean {
        val file = memoryFile(topic)
        if (!file.exists()) return false
        val key = file.nameWithoutExtension
        archive(file, archiveKind)
        file.delete()
        writeIndex(indexEntries() - key)
        recordProvenance(key, null)
        searchIndex?.remove(key)
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

    /** Brings the search index up to date with the notes (a no-op without one); true when the index is usable. */
    fun syncIndex(): Boolean {
        val index = searchIndex ?: return false
        val summaries = indexEntries()
        return index.sync(topicFiles().map { doc(it, summaries[it.nameWithoutExtension].orEmpty()) })
    }

    /**
     * Topics matching [query], best first: ranked by the full-text index (bm25 over words, plus
     * trigrams for scripts without spaces), or by a scan of the notes when there's no index.
     */
    fun searchMemory(query: String, limit: Int = 8): List<MemoryHit> {
        val parsed = MemoryQuery.parse(query)
        if (parsed.isEmpty) return emptyList()
        // The index is checked against the notes first: cheap (a directory listing), and it picks up
        // anything changed outside these methods, such as a restored backup.
        val ranked = searchIndex?.takeIf { syncIndex() }?.search(parsed, limit)
            ?: MemoryIndexFallback.search(parsed, memoryTopics(), limit)
        val summaries = indexEntries()
        return ranked.map { r ->
            val snippet = r.snippet?.let(::oneLine) ?: readMemory(r.topic)?.let { memorySnippet(it, parsed) }
            MemoryHit(r.topic, summaries[r.topic].orEmpty(), snippet)
        }
    }

    private fun oneLine(s: String) = s.replace(Regex("\\s+"), " ").trim().takeIf { it.isNotEmpty() }

    private fun topicFiles(): List<File> = dir.listFiles { f -> f.isFile && f.name.endsWith(".md") && f != index }.orEmpty().toList()

    /** A note as the index sees it: its name, index line and text, and what tells whether it changed. */
    private fun doc(file: File, summary: String) = MemoryDoc(
        topic = file.nameWithoutExtension,
        fingerprint = "${file.lastModified()}:${file.length()}:${summary.hashCode()}",
        load = { summary to runCatching { file.readText() }.getOrDefault("") },
    )

    /**
     * The file for [topic]. Names are [AgentHome.memorySlug]s; a note saved before non-Latin names
     * were kept is still found under its older [AgentHome.slug] name.
     */
    private fun memoryFile(topic: String): File {
        val file = File(dir, "${AgentHome.memorySlug(topic)}.md")
        if (file.exists()) return file
        val legacy = File(dir, "${AgentHome.slug(topic)}.md")
        return if (legacy.exists()) legacy else file
    }

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

    /** Matches in [primary] first; matches from [fallback] are marked [MemoryHit.general]. */
    fun search(query: String, limit: Int = 8): List<MemoryHit> {
        val own = primary.searchMemory(query, limit)
        val general = fallback?.searchMemory(query, limit).orEmpty()
            .filter { hit -> own.none { it.topic == hit.topic } }
            .map { it.copy(general = true) }
        return (own + general).take(limit)
    }

    fun save(topic: String, summary: String, content: String, by: String? = null, chatId: String? = null, taintedBy: Collection<String> = emptyList()) =
        primary.saveMemory(topic, summary, content, by, chatId, taintedBy)

    fun forget(topic: String): Boolean = primary.forgetMemory(topic)

    /**
     * Forgets a topic the chat read (the steps card's Forget): from general memory when the search
     * said so; otherwise from where `memory_read` would have found it, this chat's memory first.
     */
    fun forgetRead(ref: MemoryRef): Boolean = when {
        ref.general && fallback != null -> fallback.forgetMemory(ref.topic)
        primary.hasMemory(ref.topic) -> primary.forgetMemory(ref.topic)
        else -> fallback?.forgetMemory(ref.topic) ?: false
    }

    /** Whether a topic the chat read is still saved where [forgetRead] would look. */
    fun hasRead(ref: MemoryRef): Boolean =
        if (ref.general && fallback != null) fallback.hasMemory(ref.topic) else primary.hasMemory(ref.topic) || fallback?.hasMemory(ref.topic) == true
}

/** What a project chat's prompt needs from its project, fixed when the chat is created. */
data class ProjectPrompt(
    val scope: ChatScopeSpec,
    /** Connectors the project allows; null for all. Only these are listed in the prompt. */
    val allowedConnectors: Set<String>?,
)
