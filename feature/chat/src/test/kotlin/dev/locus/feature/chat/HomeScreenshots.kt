package dev.locus.feature.chat

import androidx.compose.runtime.Composable
import dev.locus.core.data.repository.WorkspaceEntry
import dev.locus.core.model.Conversation
import dev.locus.core.model.Project
import dev.locus.core.data.search.ChatSearchHit
import dev.locus.core.data.search.Highlighted
import dev.locus.core.data.search.MessageMatch
import dev.locus.core.model.Role
import dev.locus.core.model.SetupItem
import dev.locus.core.screenshot.ScreenshotTest
import dev.locus.core.screenshot.Step
import dev.locus.core.screenshot.UtcRule
import org.junit.Rule
import org.junit.Test

/** Home (the conversations screen): with chats, before a model is connected, and loading. */
class HomeScreenshots : ScreenshotTest() {

    /** Remote mode's end shows as a clock time: a fixed one, read in UTC. */
    @get:Rule val utc = UtcRule()

    /**
     * Relative times ("3 hours ago") count back from the real clock so they read the same on every run;
     * every row stays under a week old, where the label would turn into a date.
     */
    private val now = System.currentTimeMillis()
    private val min = 60_000L
    private val hour = 60 * min
    private val day = 24 * hour

    private val actions = HomeActions(
        onStart = {}, onNewChat = {}, onOpenConversation = {}, onOpenMessage = { _, _ -> }, onDelete = {}, onPin = { _, _ -> },
        onRename = { _, _ -> }, onSearch = {}, onOpenSettings = {}, onOpenLibrary = {}, onOpenTasks = {}, onOpenWorkspace = {},
        onOpenActivity = {}, onOpenAnalytics = {},
    )

    private fun row(
        id: String,
        title: String,
        ago: Long,
        running: Boolean = false,
        approval: Boolean = false,
        pinned: Boolean = false,
        tool: String? = null,
        provider: Pair<String, String> = "Anthropic" to "https://api.anthropic.com",
        model: String = "claude-opus-5-5",
        projectId: String? = null,
    ) = ConversationRow(
        Conversation(id, title, "a", "", now - ago - hour, now - ago, pinned = pinned, projectId = projectId),
        running = running, needsApproval = approval, providerName = provider.first, providerUrl = provider.second, activeTool = tool, model = model,
    )

    private fun project(id: String, name: String, chats: Int, ago: Long, running: Int = 0, approvals: Int = 0) =
        HomeProject(Project(id, name, "projects/$id"), chats, now - ago, running, approvals)

    /** Eight projects in home's order (homeProjects): one working first, then the most recently active. */
    private val many = listOf(
        project("p1", "garden-app", 12, 2 * min, running = 1),
        project("p2", "Portfolio site", 4, 3 * hour),
        project("p3", "Tax return 2026", 7, day + 2 * hour),
        project("p4", "Lisbon trip", 3, 2 * day),
        project("p5", "Thesis, chapter 3: literature review and every source", 21, 3 * day),
        project("p6", "Home automation", 1, 4 * day),
        project("p7", "Recipes", 0, 5 * day),
        project("p8", "Job applications", 9, 6 * day),
    )

    private val projects = many.take(2)

    private val rows = listOf(
        row("c1", "Fix checkout tests", 2 * min, running = true, tool = "run_command", projectId = "p1"),
        row("c2", "Book a table at Lula's", 20 * min, approval = true, tool = "browser_click", provider = "OpenRouter" to "https://openrouter.ai/api/v1", model = "openai/gpt-5"),
        row("c3", "Plan a 3-day trip to Lisbon", 3 * hour, pinned = true),
        row("c4", "Debounce a search field", 5 * hour),
        row("c5", "Summarise the March bank statement", day + 2 * hour, model = "gemini-3-pro", provider = "OpenRouter" to "https://openrouter.ai/api/v1"),
        row("c6", "Rename photos by date", 3 * day),
        row("c7", "Weekly groceries list", 6 * day),
    )

    private val ready = ConversationsUiState.Ready(
        rows = rows,
        hasProvider = true,
        defaultModel = DefaultModel("Anthropic", "https://api.anthropic.com", "claude-opus-5-5", onDevice = false),
        counts = HomeCounts(
            memories = 42, skills = 7, toReview = 2, tasksActive = 3, tasksTotal = 5,
            recentFiles = listOf(
                WorkspaceEntry("garden-app/dist/report.pdf", "report.pdf", false, 248_000, now - 30 * min),
                WorkspaceEntry("notes/lisbon.md", "lisbon.md", false, 4_200, now - 3 * hour),
                WorkspaceEntry("photos/2026-10", "2026-10", true, 0, now - 3 * day),
            ),
            latestResult = TaskResult("Morning news", "Three stories: the EU AI Act's first fines, a new Pixel update, and rain in Lisbon all weekend.", now - 4 * hour),
        ),
    )

    @Composable
    private fun Home(state: ConversationsUiState, projects: List<HomeProject> = emptyList(), remoteUntil: Long? = null) =
        ConversationsScreen(state, actions, composer = HomeComposer(Fx.settings), projects = projects, remoteModeUntil = remoteUntil)

    @Test
    fun home() = snap("home") { Home(ready, projects) }

    // ---- The projects shelf: none, a few, many, and a project with a chat working or waiting

    /** Home with no chat featured, so the shelf sits right under the launcher. */
    private val calm = ready.copy(rows = rows.drop(2))

    @Test fun projectsNone() = snap("home-projects-0") { Home(calm) }

    @Test fun projectsOne() = snap("home-projects-1") { Home(calm, listOf(many[1])) }

    @Test fun projectsThree() = snap("home-projects-3") { Home(calm, many.drop(1).take(3)) }

    @Test fun projectsEight() = snap("home-projects-8") { Home(calm, listOf(many[0].copy(running = 0)) + many.drop(1)) }

    /** A chat in garden-app is working: it comes first with the live dot. */
    @Test fun projectsRunning() = snap("home-projects-running") { Home(ready, many.take(4)) }

    /** A chat in Portfolio site waits for an approval: first, outlined, in the caution tone. */
    @Test
    fun projectsApproval() = snap("home-projects-approval") {
        Home(ready, listOf(many[1].copy(approvals = 1), many[0], many[2], many[3]))
    }

    @Test
    fun homeQuiet() = snap("home-quiet") {
        Home(ready.copy(rows = rows.drop(2), counts = ready.counts.copy(latestResult = null, tasksActive = 0)), remoteUntil = Fx.T0 + 2 * hour)
    }

    @Test
    fun homeNoChats() = snap("home-no-chats") {
        Home(ConversationsUiState.Ready(rows = emptyList(), hasProvider = true, defaultModel = ready.defaultModel))
    }

    /** Right after Quick start: no chats yet, and the first of four skipped steps as a setup card. */
    @Test
    fun homeSetupFresh() = snap("home-setup-fresh") {
        Home(ConversationsUiState.Ready(rows = emptyList(), hasProvider = true, defaultModel = ready.defaultModel, setup = SetupItem.entries))
    }

    /** Later: chats and a task result, with the last skipped step left. */
    @Test
    fun homeSetupLast() = snap("home-setup-last") { Home(ready.copy(setup = listOf(SetupItem.Connectors)), projects) }

    @Test
    fun homeNoProvider() = snap("home-no-provider") {
        Home(ConversationsUiState.Ready(rows = emptyList(), hasProvider = false))
    }

    @Test
    fun homeLoading() = snap("home-loading") { Home(ConversationsUiState.Loading) }

    // ---- All chats and search (opened from the top bar's search button)

    @Test
    fun allChats() = snapAfter("home-all-chats", listOf(Step.Tap("All chats"), Step.Tap("garden-app")), heightDp = 1800) { Home(ready, projects) }

    private fun hit(row: ConversationRow, role: Role, text: String, word: String, ago: Long, shown: Boolean = true): ChatSearchHit {
        val at = text.indexOf(word)
        return ChatSearchHit(row.conversation, MessageMatch("m-${row.conversation.id}", role, now - ago, Highlighted(text, listOf(at until at + word.length)), shown))
    }

    @Test
    fun search() {
        val matches = listOf(rows[0], rows[3], rows[4])
        val hits = mapOf(
            "c1" to hit(rows[0], Role.ASSISTANT, "…Stripe v18 renamed the payment_method_types option. Updating the call sites in payment.ts and refund.ts…", "Stripe", 3 * min),
            "c4" to hit(rows[3], Role.USER, "…the search box calls Stripe's price lookup on every keystroke, can we debounce it?", "Stripe", 5 * hour),
            "c5" to hit(rows[4], Role.ASSISTANT, "…a payment of €42.00 to Stripe Payments Europe on 14 March, marked as Lula's Bistro…", "Stripe", day + 2 * hour, shown = false),
        )
        val state = ready.copy(rows = matches, query = "stripe", hits = hits.mapValues { it.value })
        snapAfter("home-search", listOf(Step.Tap("Search chats"))) { Home(state, projects) }
    }

    /** Search opened from home, then the results for what was typed arriving as [then] (reset for each variant). */
    private fun searchThen(name: String, then: ConversationsUiState.Ready) {
        val state = androidx.compose.runtime.mutableStateOf<ConversationsUiState>(ready)
        snapAfter(name, listOf(Step.Act { state.value = ready }, Step.Tap("Search chats"), Step.Act { state.value = then })) { Home(state.value, projects) }
    }

    @Test
    fun searchNoMatches() = searchThen("home-search-no-matches", ready.copy(rows = emptyList(), query = "kubernetes ingress", hits = emptyMap()))

    @Test
    fun searchTitlesOnly() = searchThen("home-search-titles-only", ready.copy(rows = emptyList(), query = "q", hits = emptyMap()))

    @Test
    fun searchPending() = searchThen("home-search-pending", ready.copy(rows = emptyList(), query = "lisbon", hits = emptyMap(), searchPending = true))
}
