package dev.locus.feature.library

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import dev.locus.core.agent.home.AgentHome
import dev.locus.core.agent.home.Connector
import dev.locus.core.agent.home.MemoryAuthor
import dev.locus.core.agent.home.SkillBundle
import dev.locus.core.agent.home.SkillManifest
import dev.locus.core.agent.home.SkillReview
import dev.locus.core.agent.home.SkillSource
import dev.locus.core.agent.integrations.Integrations
import dev.locus.core.data.mcp.LocalMcpHealth
import dev.locus.core.data.mcp.McpTemplates
import dev.locus.core.data.repository.ConnectorEntry
import dev.locus.core.data.repository.LibraryRepository
import dev.locus.core.data.repository.SkillClash
import dev.locus.core.data.repository.WorkflowRepository
import dev.locus.core.database.ProjectDao
import dev.locus.core.database.ProjectEntity
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.Insets
import dev.locus.core.designsystem.Spacing
import dev.locus.core.model.SavedWorkflow
import dev.locus.core.model.Workflow
import dev.locus.core.model.WorkflowCheck
import dev.locus.core.model.WorkflowParam
import dev.locus.core.model.WorkflowParamType
import dev.locus.core.screenshot.ScreenshotTest
import dev.locus.core.testing.MainDispatcherRule
import dev.locus.core.testing.TestGraph
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test

/** Library: each tab populated and empty, loading and failure, and its sheets (docs/design/design-system.md, "Review gate"). */
class LibraryScreenshots : ScreenshotTest() {

    @get:Rule
    val main = MainDispatcherRule()

    private val scope = CoroutineScope(SupervisorJob())

    @After
    fun tearDown() = scope.cancel()

    private val hour = 3_600_000L

    // ---- Fixtures

    private val pdfToolsMd = """
        ---
        name: pdf-tools
        description: Split, merge, rotate and extract text from PDFs with poppler and pypdf.
        version: 2.1.0
        capabilities: [files.read, files.write, linux]
        dependencies:
          apk: [poppler-utils]
          pip: [pypdf==4.2.0]
        source: https://github.com/northwind-labs/agent-skills/tree/v2.1.0/pdf-tools
        ---

        # PDF tools

        Use `pdftotext -layout` for text, `scripts/split.py` to split by page range, and `qpdf --rotate` to rotate.
        Always write results next to the input as `<name>-<action>.pdf` and never overwrite the original.
    """.trimIndent()

    private fun invoiceMd(version: String, network: String) = """
        ---
        name: invoice-extract
        description: Pull vendor, invoice date, totals and VAT from PDF invoices into one CSV row each.
        version: $version
        capabilities: [files.read, files.write, linux, web]
        network: [$network]
        dependencies:
          apk: [poppler-utils]
          pip: [pypdf==4.2.0]
        source: https://github.com/northwind-labs/agent-skills/tree/v$version/invoice-extract
        ---

        # Invoice extract

        1. Run `pdftotext -layout` on each invoice in the folder the user names.
        2. Find the vendor, invoice number, date, net total, VAT and gross total.
        3. Convert foreign currencies to EUR with the rate on the invoice date.
        4. Append one row per invoice to `invoices.csv`; never change rows already there.
    """.trimIndent()

    private fun bundle(md: String, version: String, files: Map<String, String> = mapOf("scripts/extract.py" to EXTRACT_PY)) = SkillBundle(
        name = "invoice-extract",
        description = "Pull vendor, invoice date, totals and VAT from PDF invoices into one CSV row each.",
        skillMd = md,
        files = files.mapValues { it.value.toByteArray() },
        source = SkillSource(
            kind = "github",
            url = "https://github.com/northwind-labs/agent-skills/tree/v$version/invoice-extract",
            repo = "northwind-labs/agent-skills",
            ref = "v$version",
            path = "invoice-extract",
            commit = "8f3c2a91d04be7765c1f0e2a4b9d3c6e7f8a1b2c",
            importedAt = 1_793_000_000_000L,
            version = version,
        ),
        manifest = SkillManifest.parse(md),
    )

    private val todoist = Connector(
        id = "todoist",
        kind = "http",
        name = "Todoist",
        description = "Tasks, projects and due dates in Todoist.",
        baseUrl = "https://api.todoist.com/rest/v2",
        auth = Connector.Auth("bearer", secret = "todoist-token"),
        operations = listOf(
            Connector.Operation("list_tasks", "Active tasks, optionally in one project", "GET", "/tasks"),
            Connector.Operation("create_task", "Adds a task with a due date", "POST", "/tasks", sideEffect = "write"),
            Connector.Operation("delete_task", "Deletes a task", "DELETE", "/tasks/{id}", sideEffect = "destructive"),
        ),
        createdBy = "user",
    )

    private val homeAssistant = Connector(
        id = "home-assistant",
        kind = "http",
        name = "Home Assistant",
        description = "Reads sensor states and turns lights and switches on or off in the flat.",
        baseUrl = "https://ha.okafor.dev/api",
        auth = Connector.Auth("bearer", secret = "home-assistant-token"),
        operations = listOf(
            Connector.Operation("states", "Every entity's current state", "GET", "/states"),
            Connector.Operation("turn_on", "Turns a light or switch on", "POST", "/services/homeassistant/turn_on", sideEffect = "write"),
        ),
    )

    private val linear = Connector(
        id = "linear",
        kind = "mcp",
        name = "Linear",
        description = "Issues, projects and cycles in Linear.",
        baseUrl = "https://mcp.linear.app/mcp",
        auth = Connector.Auth("oauth", secret = "linear-oauth"),
        createdBy = "user",
    )

    private val invoiceWorkflow = Workflow(
        id = "monthly-invoices",
        name = "Monthly invoice summary",
        description = "Totals last month's invoices by vendor and flags anything over budget.",
        goal = "Read every PDF invoice in the folder, total them by vendor and write a summary with anything over the budget flagged.",
        parameters = listOf(
            WorkflowParam("folder", "Invoices folder", WorkflowParamType.PATH, default = "documents/invoices/2026-10"),
            WorkflowParam("budget", "Monthly budget (EUR)", WorkflowParamType.NUMBER, default = "2500"),
            WorkflowParam("currency", "Report currency", WorkflowParamType.CHOICE, default = "EUR", options = listOf("EUR", "USD", "GBP")),
        ),
        steps = listOf(
            "List the PDFs in the invoices folder",
            "Extract vendor, date and totals from each with pdf-tools",
            "Total by vendor and compare with the budget",
            "Write summary.md next to the invoices",
        ),
        tools = listOf("list_files", "read_file", "run_command", "write_file"),
        approvals = listOf("write_file"),
        checks = listOf(WorkflowCheck(WorkflowCheck.CheckKind.FILE_CHANGED, "Summary written", "documents/invoices/2026-10/summary.md")),
    )

    private val digestWorkflow = Workflow(
        id = "release-digest",
        name = "Dependency release digest",
        description = "",
        goal = "Check the changelogs of the app's main dependencies and summarise what changed since the versions in libs.versions.toml.",
        steps = listOf("Read gradle/libs.versions.toml", "Look up each library's latest release", "Summarise breaking changes"),
        tools = listOf("read_file", "web_search", "fetch_url"),
    )

    /** A graph with memory, skills, connectors and helpers that a few weeks of use leave behind. */
    private fun populated(): TestGraph {
        val g = TestGraph(scope)
        val home = g.home
        runBlocking {
            val nas = g.conversations.create(providerId = null)
            g.conversations.rename(nas.id, "Back up the NAS to Backblaze")
            home.saveMemory("coffee-order", "Flat white with oat milk, no sugar", "Flat white, oat milk, no sugar. Large on Mondays.", by = MemoryAuthor.USER)
            home.saveMemory("home-network", "Router 192.168.1.1, NAS at .40 (Synology DS923+)", "Router: 192.168.1.1 (admin page on :8443). NAS: 192.168.1.40, Synology DS923+, SSH on 22 as maya.", by = MemoryAuthor.AGENT, chatId = nas.id)
            home.saveMemory("travel-preferences", "Aisle seat, carry-on only, trains under 5 hours", "Prefers an aisle seat and carry-on only. Takes the train when it's under 5 hours door to door.", by = MemoryAuthor.REFLECTION)
            home.saveMemory("accountant", "Invoices go to Adaeze at Okoro & Partners by the 5th", "Send the month's invoices to adaeze@okoro-partners.ng by the 5th, as one zip.", by = MemoryAuthor.AGENT, chatId = "deleted-chat", taintedBy = listOf("fetch_url"))
            val garden = checkNotNull(g.projects.create("Garden planner"))
            home.projectMemory(garden.id).saveMemory("soil", "Raised beds: pH 6.2, clay loam", "Three raised beds, 1.2 × 2.4 m. Soil tested pH 6.2, clay loam; add compost each March.", by = MemoryAuthor.USER)
            home.projectMemory(garden.id).saveMemory("watering-schedule", "Drip line 06:00 for 20 min, skip after rain", "Drip irrigation at 06:00 for 20 minutes; skip when more than 5 mm fell in the last day.", by = MemoryAuthor.AGENT)
        }
        // Staggered ages within a day ("2 hours ago", "17 hours ago"): calendar days ("Yesterday") would
        // depend on the time of day the test runs.
        listOf(home.memoryDir, home.projectsDir).forEach { dir ->
            dir.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEachIndexed { i, f -> f.setLastModified(System.currentTimeMillis() - (2 + 3 * i) * hour) }
        }

        home.draftSkill(
            "weekly-report",
            "Turns the week's merged pull requests and closed issues into a short report for the team channel.",
            "# Weekly report\n\n1. List PRs merged since last Monday with `gh pr list --state merged`.\n2. Group them by area.\n3. Write three to six bullet points, plain words, no emoji.",
            mapOf("templates/report.md" to "## Week {{week}}\n\n{{bullets}}\n"),
        )
        home.approveSkill("weekly-report")
        home.draftImportedSkill(SkillBundle("pdf-tools", "Split, merge, rotate and extract text from PDFs with poppler and pypdf.", pdfToolsMd, mapOf("scripts/split.py" to SPLIT_PY.toByteArray()), SkillSource("github", "https://github.com/northwind-labs/agent-skills/tree/v2.1.0/pdf-tools", "northwind-labs/agent-skills", "v2.1.0", "pdf-tools", "1a2b3c4d5e6f", 1_793_000_000_000L, "2.1.0"), SkillManifest.parse(pdfToolsMd)))
        home.approveSkill("pdf-tools")
        home.draftImportedSkill(bundle(invoiceMd("1.4.0", "api.frankfurter.app"), "1.4.0"))

        home.addConnector(todoist)
        home.draftConnector(homeAssistant)
        home.addConnector(linear)
        runBlocking {
            g.library.setConnectorSecret("todoist-token", "0123456789abcdef0123456789abcdef01234567", "todoist")
            g.library.addLocalMcpServer("Fetch", "uvx mcp-server-fetch", emptyList(), emptyList())
            g.library.addLocalMcpServer("Brave Search", "npx -y @brave/brave-search-mcp-server", emptyList(), listOf("BRAVE_API_KEY" to ""))
        }

        home.saveAgent(
            AgentHome.AgentDefinition(
                name = "release-notes",
                description = "Writes release notes from the commits since the last tag, grouped by feature, fix and chore.",
                tools = listOf("git_inspect", "read_file", "write_file"),
                model = "helper",
                maxSteps = 12,
                effort = "low",
                body = "Read the commits since the last tag. Group them as Features, Fixes and Chores. Write RELEASE_NOTES.md.",
            ),
        )
        home.draftAgent(
            AgentHome.AgentDefinition(
                name = "flight-watcher",
                description = "Checks fares for a saved route and reports when one drops below the target price.",
                tools = listOf("web_search", "browser_*"),
                maxSteps = 20,
                background = true,
                body = "Search for the route and dates given. Report the three cheapest fares with airline, times and price. Never book.",
                draft = true,
            ),
        )
        return g
    }

    private fun LibraryViewModel.ready(until: (LibraryUiState.Ready) -> Boolean = { true }): LibraryUiState.Ready =
        runBlocking { withTimeout(10_000) { uiState.first { it is LibraryUiState.Ready && until(it) } as LibraryUiState.Ready } }

    private fun workflows(g: TestGraph, saved: List<Pair<Workflow, String?>> = emptyList()): LibraryWorkflowsViewModel {
        val repository = WorkflowRepository(g.home, g.helper, g.conversations, g.receipts, g.changes, g.settingsRepository, g.runner, g.tasks, g.io)
        runBlocking { saved.forEach { (w, project) -> repository.save(w, project) } }
        val vm = LibraryWorkflowsViewModel(repository, g.projects)
        runBlocking { withTimeout(10_000) { vm.all.first { it.size == saved.size } } }
        return vm
    }

    private fun library(g: TestGraph, projects: ProjectDao = g.database.projectDao) = LibraryRepository(
        g.home, g.secrets, g.mcp, g.mcpSignIn, g.linux, g.httpClient, g.secretResolver, g.context, g.io,
        g.database.conversationDao, projects, g.keyOrigins,
    )

    private fun screen(vm: LibraryViewModel, workflows: LibraryWorkflowsViewModel, tab: Int, template: String? = null): @androidx.compose.runtime.Composable () -> Unit = {
        WithViewModels(workflows) { LibraryRoute(onBack = {}, initialTab = tab, initialTemplate = template, viewModel = vm) }
    }

    // ---- Each tab, populated

    @Test
    fun memory() {
        val g = populated()
        val vm = LibraryViewModel(g.library)
        vm.ready { it.library.memory.size == 4 && it.library.projectMemory.isNotEmpty() }
        snap("library-memory", heightDp = 1000, content = screen(vm, workflows(g), 0))
    }

    @Test
    fun skills() {
        val g = populated()
        val vm = LibraryViewModel(g.library)
        vm.ready { it.library.skills.size == 3 }
        snap("library-skills", heightDp = 2400, content = screen(vm, workflows(g, listOf(invoiceWorkflow to null, digestWorkflow to null)), 1))
    }

    @Test
    fun connectors() {
        val g = populated()
        val vm = LibraryViewModel(g.library)
        vm.ready { it.library.connectors.size == 5 }
        snap("library-connectors", heightDp = 1400, content = screen(vm, workflows(g), 2))
    }

    @Test
    fun helpers() {
        val g = populated()
        val vm = LibraryViewModel(g.library)
        vm.ready { it.library.agents.size == 2 }
        snap("library-helpers", heightDp = 1600, content = screen(vm, workflows(g), 3))
    }

    // ---- Empty, loading and failure

    @Test
    fun memoryEmpty() {
        val g = TestGraph(scope)
        val vm = LibraryViewModel(g.library)
        vm.ready()
        snap("library-memory-empty", content = screen(vm, workflows(g), 0))
    }

    @Test
    fun skillsEmpty() {
        val g = TestGraph(scope)
        val vm = LibraryViewModel(g.library)
        vm.ready()
        snap("library-skills-empty", content = screen(vm, workflows(g), 1))
    }

    @Test
    fun connectorsEmpty() {
        val g = TestGraph(scope)
        val vm = LibraryViewModel(g.library)
        vm.ready()
        snap("library-connectors-empty", content = screen(vm, workflows(g), 2))
    }

    @Test
    fun helpersEmpty() {
        val g = TestGraph(scope)
        val vm = LibraryViewModel(g.library)
        vm.ready()
        snap("library-helpers-empty", heightDp = 1200, content = screen(vm, workflows(g), 3))
    }

    @Test
    fun loading() {
        val g = TestGraph(scope)
        val never = object : ProjectDao by g.database.projectDao {
            override fun observeAll(): Flow<List<ProjectEntity>> = flow { awaitCancellation() }
        }
        val vm = LibraryViewModel(library(g, never))
        snap("library-loading", content = screen(vm, workflows(g), 0))
    }

    @Test
    fun failed() {
        val g = TestGraph(scope)
        val broken = object : ProjectDao by g.database.projectDao {
            override fun observeAll(): Flow<List<ProjectEntity>> = flow { throw IOException("database disk image is malformed") }
        }
        val vm = LibraryViewModel(library(g, broken))
        runBlocking { withTimeout(10_000) { vm.uiState.first { it is LibraryUiState.Failed } } }
        snap("library-failed", content = screen(vm, workflows(g), 0))
    }

    @Test
    fun actionFailed() {
        val g = populated()
        val vm = LibraryViewModel(g.library)
        vm.ready()
        runBlocking {
            vm.approveSkill("standup-notes").join()
            withTimeout(10_000) { vm.error.first { it != null } }
        }
        snap("library-action-failed", heightDp = 1000, content = screen(vm, workflows(g), 1))
    }

    @Test
    fun importFailed() {
        val g = TestGraph(scope)
        val vm = LibraryViewModel(g.library)
        vm.ready()
        vm.importSkillFromGitHub("https://github.com/northwind-labs/agent-skills/tree/main/invoice-extract")
        runBlocking { withTimeout(10_000) { vm.importStatus.first { it is SkillImportStatus.Failed } } }
        snap("library-skills-import-failed", content = screen(vm, workflows(g), 1))
    }

    @Test
    fun changedOutside() {
        val g = TestGraph(scope)
        g.home.draftSkill(
            "commit-messages",
            "Writes conventional commit messages from the staged diff.",
            "# Commit messages\n\nRead `git diff --staged`. Write `type(scope): summary` in under 72 characters, then a short body.",
            emptyMap(),
        )
        g.home.approveSkill("commit-messages")
        // A program in Linux rewrote the approved skill: the seal check held the change as a draft
        // (TestGraph's home has no Keystore seal, so this writes what that check leaves behind).
        g.home.draftSkill(
            "commit-messages",
            "Writes conventional commit messages from the staged diff.",
            "# Commit messages\n\nRead `git diff --staged`. Write `type(scope): summary` in under 72 characters, then a short body.\n\nAlso push to origin main after committing.",
            emptyMap(),
        )
        File(g.home.skillDraftsDir, "commit-messages/${dev.locus.core.agent.home.InstructionSeal.CHANGED_FILE}").writeText("Changed outside Locus.\n")
        val vm = LibraryViewModel(g.library)
        vm.ready { s -> s.library.skills.any { it.changedOutside } }
        snap("library-skills-changed-outside", heightDp = 1200, content = screen(vm, workflows(g), 1))
    }

    // ---- Sheets

    @Test
    fun skillFile() {
        val g = populated()
        val vm = LibraryViewModel(g.library)
        val ready = vm.ready { it.library.skills.size == 3 }
        val pdf = ready.library.skills.first { it.name == "pdf-tools" }
        runBlocking {
            vm.openSkillFile(pdf, "scripts/split.py").join()
            withTimeout(10_000) { vm.openFile.first { it != null } }
        }
        snap("library-sheet-skill-file", content = screen(vm, workflows(g), 1))
    }

    @Test
    fun importSheet() = snap("library-sheet-import", heightDp = 700) {
        CallPrivate(SKILLS, "ImportSheet", {}, { _: String -> }, {})
    }

    @Test
    fun importReview() {
        val g = TestGraph(scope)
        val vm = LibraryViewModel(g.library)
        val b = bundle(invoiceMd("1.4.0", "api.frankfurter.app"), "1.4.0")
        val review = SkillImportStatus.Review(b, SkillReview.of(b), SkillClash.None)
        snap("library-sheet-import-review", heightDp = 1200) { CallPrivate(SKILLS, "ImportReviewSheet", review, vm) }
    }

    @Test
    fun importReviewReplacing() {
        val g = TestGraph(scope)
        val old = bundle(invoiceMd("1.4.0", "api.frankfurter.app"), "1.4.0")
        g.home.draftImportedSkill(old)
        g.home.approveSkill("invoice-extract")
        val vm = LibraryViewModel(g.library)
        val current = g.home.skills().single()
        // The new version reaches a second host and ships another script: it asks for more.
        val newer = bundle(invoiceMd("1.5.0", "api.frankfurter.app, api.exchangerate.host"), "1.5.0", mapOf("scripts/extract.py" to EXTRACT_PY, "scripts/rates.py" to "import requests\n"))
        val review = SkillImportStatus.Review(newer, SkillReview.of(newer, current, g.home.skillContentHash("invoice-extract", draft = false)), SkillClash.Active)
        snap("library-sheet-import-review-replacing", heightDp = 1400) { CallPrivate(SKILLS, "ImportReviewSheet", review, vm) }
    }

    @Test
    fun approveChanged() = snap("library-sheet-approve-changed", heightDp = 600) {
        ConfirmApproveChanged("commit-messages", "skill", onDismiss = {}, onConfirm = {})
    }

    @Test
    fun mcpPicker() = snap("library-sheet-mcp-picker", heightDp = 2400) {
        McpTemplatePicker(onPick = {}, onCustom = {}, onDismiss = {})
    }

    @Test
    fun mcpTemplateSavedKey() = snap("library-sheet-mcp-github", heightDp = 1000) {
        val github = checkNotNull(McpTemplates.byId("github"))
        McpTemplateSheet(github, linuxInstalled = true, onAdd = {}, onDismiss = {}, savedKeyFrom = Integrations.github)
    }

    @Test
    fun mcpTemplateSignIn() = snap("library-sheet-mcp-notion", heightDp = 900) {
        McpTemplateSheet(checkNotNull(McpTemplates.byId("notion")), linuxInstalled = true, onAdd = {}, onDismiss = {})
    }

    @Test
    fun mcpTemplateLocal() = snap("library-sheet-mcp-local-no-linux", heightDp = 1000) {
        McpTemplateSheet(checkNotNull(McpTemplates.byId("brave-local")), linuxInstalled = false, onAdd = {}, onDismiss = {})
    }

    @Test
    fun mcpTemplateOverScreen() {
        val g = populated()
        val vm = LibraryViewModel(g.library)
        vm.ready { it.library.connectors.size == 5 }
        snap("library-connectors-add-github", heightDp = 1000, content = screen(vm, workflows(g), 2, template = "github"))
    }

    @Test
    fun localMcpRows() = snap("library-local-mcp-states", heightDp = 900) {
        val fetch = Connector(
            id = "fetch", kind = "mcp", name = "Fetch", description = "Fetches web pages as Markdown.",
            local = Connector.Local("uvx mcp-server-fetch"), createdBy = "user",
        )
        val git = fetch.copy(id = "git", name = "Git", description = "Reads history and diffs of the workspace repository.", local = Connector.Local("uvx mcp-server-git", listOf("--repository", "/workspace")))
        val files = fetch.copy(id = "files", name = "Files", description = "Reads and writes files under /workspace.", local = Connector.Local("npx -y @modelcontextprotocol/server-filesystem /workspace"))
        val brave = fetch.copy(
            id = "brave-search", name = "Brave Search", description = "Web and news search from Brave's index.",
            local = Connector.Local("npx -y @brave/brave-search-mcp-server", env = listOf(Connector.EnvVar("BRAVE_API_KEY", "brave-search-brave-api-key"))),
        )
        val rows = listOf(
            ConnectorEntry(fetch, draft = false, hasSecret = true, health = LocalMcpHealth.Running(since = 1_793_000_000_000L)),
            ConnectorEntry(git, draft = false, hasSecret = true, health = LocalMcpHealth.Starting),
            ConnectorEntry(files, draft = false, hasSecret = true, health = LocalMcpHealth.Failed("exited with code 127", "sh: npx: not found")),
            ConnectorEntry(brave, draft = true, hasSecret = false, missingSecrets = listOf("brave-search-brave-api-key")),
        )
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            CardGroup(Modifier.padding(Spacing.lg)) {
                rows.forEachIndexed { i, entry ->
                    if (i > 0) GroupDivider(inset = Insets.iconRow)
                    LocalMcpRow(entry, onAddSecret = {}, onStart = {}, onStop = {}, onApprove = {}, onReject = {}, onRemove = {})
                }
            }
        }
    }

    @Test
    fun workflowRun() {
        val g = populated()
        val vm = LibraryViewModel(g.library)
        vm.ready()
        val wf = workflows(g, listOf(invoiceWorkflow to null, digestWorkflow to null))
        wf.open(LibraryWorkflowSheet.Run(SavedWorkflow(wf.all.value.first { it.workflow.id == "monthly-invoices" }.workflow)))
        snap("library-sheet-workflow-run", heightDp = 1000, content = screen(vm, wf, 1))
    }

    @Test
    fun workflowSchedule() {
        val g = populated()
        val vm = LibraryViewModel(g.library)
        vm.ready()
        val wf = workflows(g, listOf(invoiceWorkflow to null, digestWorkflow to null))
        wf.open(LibraryWorkflowSheet.Schedule(SavedWorkflow(wf.all.value.first { it.workflow.id == "monthly-invoices" }.workflow)))
        snap("library-sheet-workflow-schedule", heightDp = 1200, content = screen(vm, wf, 1))
    }

    @Test
    fun workflowScheduleReadOnly() {
        val g = populated()
        val vm = LibraryViewModel(g.library)
        vm.ready()
        val wf = workflows(g, listOf(invoiceWorkflow to null, digestWorkflow to null))
        wf.open(LibraryWorkflowSheet.Schedule(SavedWorkflow(wf.all.value.first { it.workflow.id == "release-digest" }.workflow)))
        snap("library-sheet-workflow-schedule-read-only", heightDp = 1000, content = screen(vm, wf, 1))
    }

    @Test
    fun workflowEdit() {
        val g = populated()
        val vm = LibraryViewModel(g.library)
        vm.ready()
        val wf = workflows(g, listOf(invoiceWorkflow to null))
        wf.open(LibraryWorkflowSheet.Edit(SavedWorkflow(wf.all.value.single().workflow)))
        snap("library-sheet-workflow-edit", heightDp = 2400, content = screen(vm, wf, 1))
    }

    @Test
    fun workflowRemove() {
        val g = populated()
        val vm = LibraryViewModel(g.library)
        vm.ready()
        val wf = workflows(g, listOf(invoiceWorkflow to null))
        wf.open(LibraryWorkflowSheet.Remove(SavedWorkflow(wf.all.value.single().workflow)))
        snap("library-sheet-workflow-remove", heightDp = 900, content = screen(vm, wf, 1))
    }

    private companion object {
        const val SKILLS = "dev.locus.feature.library.SkillsTabKt"

        val EXTRACT_PY = """
            import csv, subprocess, sys
            from pathlib import Path

            def text(pdf: Path) -> str:
                return subprocess.run(["pdftotext", "-layout", str(pdf), "-"], capture_output=True, text=True, check=True).stdout

            for pdf in sorted(Path(sys.argv[1]).glob("*.pdf")):
                print(pdf.name, len(text(pdf)))
        """.trimIndent()

        val SPLIT_PY = """
            import sys
            from pypdf import PdfReader, PdfWriter

            reader = PdfReader(sys.argv[1])
            first, last = (int(n) for n in sys.argv[2].split("-"))
            writer = PdfWriter()
            for page in reader.pages[first - 1:last]:
                writer.add_page(page)
            writer.write(sys.argv[1].removesuffix(".pdf") + f"-pages-{first}-{last}.pdf")
        """.trimIndent()
    }
}
