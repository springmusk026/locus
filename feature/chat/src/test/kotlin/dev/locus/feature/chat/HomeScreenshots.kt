package dev.locus.feature.chat

import androidx.compose.runtime.Composable
import dev.locus.core.agent.ApprovalFlag
import dev.locus.core.agent.ApprovalPreview
import dev.locus.core.agent.ApprovalRequest
import dev.locus.core.agent.BudgetLimit
import dev.locus.core.agent.tools.UserQuestion
import dev.locus.core.data.agent.BudgetPause
import dev.locus.core.data.needsyou.NeedsYouFeed
import dev.locus.core.data.needsyou.Proposal
import dev.locus.core.data.needsyou.ProposalItem
import dev.locus.core.data.needsyou.ProposalState
import dev.locus.core.data.needsyou.ProposalStatus
import dev.locus.core.data.needsyou.ResultItem
import dev.locus.core.data.needsyou.Waiting
import dev.locus.core.data.repository.WorkspaceEntry
import dev.locus.core.model.TaskRunOutcome
import dev.locus.core.model.ToolRisk
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
        provider: Pair<String, String> = "Anthropic" to "https://api.anthropic.com",
        model: String = "claude-opus-5-5",
        projectId: String? = null,
    ) = ConversationRow(
        Conversation(id, title, "a", "", now - ago - hour, now - ago, pinned = pinned, projectId = projectId),
        running = running, needsApproval = approval, providerName = provider.first, providerUrl = provider.second, model = model,
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
        row("c1", "Fix checkout tests", 2 * min, running = true, projectId = "p1"),
        row("c2", "Book a table at Lula's", 20 * min, approval = true, provider = "OpenRouter" to "https://openrouter.ai/api/v1", model = "openai/gpt-5"),
        row("c3", "Plan a 3-day trip to Lisbon", 3 * hour, pinned = true),
        row("c4", "Debounce a search field", 5 * hour),
        row("c5", "Summarise the March bank statement", day + 2 * hour, model = "gemini-3-pro", provider = "OpenRouter" to "https://openrouter.ai/api/v1"),
        row("c6", "Rename photos by date", 3 * day),
        row("c7", "Weekly groceries list", 6 * day),
    )

    /** The tool each running chat is using (the featured card's status line). */
    private val tools = mapOf("c1" to "run_command", "c2" to "browser_click")

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
    private fun Home(
        state: ConversationsUiState,
        projects: List<HomeProject> = emptyList(),
        remoteUntil: Long? = null,
        needsYou: NeedsYouFeed = NeedsYouFeed(),
        waiting: List<WaitingEntry> = emptyList(),
    ) = ConversationsScreen(state, actions, composer = HomeComposer(Fx.settings), projects = projects, remoteModeUntil = remoteUntil, needsYou = needsYou, now = now, waiting = waiting, activeTool = { tools[it] })

    @Test
    fun home() = snap("home") { Home(ready, projects) }

    // ---- "Questions waiting": chats beyond the featured one that wait for the user

    /** c2 waits on an approval (the featured card); the banner counts the two others, the oldest named. */
    @Test
    fun homeWaiting() = snap("home-waiting") {
        Home(
            ready,
            projects,
            waiting = listOf(
                WaitingEntry("c3", dev.locus.core.data.agent.WaitingKind.QUESTION, "Plan a 3-day trip to Lisbon"),
                WaitingEntry("c2", dev.locus.core.data.agent.WaitingKind.APPROVAL, "Book a table at Lula's"),
                WaitingEntry("c4", dev.locus.core.data.agent.WaitingKind.BUDGET, "Debounce a search field"),
            ),
        )
    }

    /** Chats imported from ChatGPT and Claude among the user's own (docs/design/import-chats.md): the source's badge and label, original dates. */
    @Test
    fun homeImported() = snap("home-imported", heightDp = 1500) {
        fun imported(source: dev.locus.core.model.ChatImportSource, id: String, title: String, ago: Long) = ConversationRow(
            Conversation(dev.locus.core.model.ImportedChats.chatId(source, id), title, null, "", now - ago - hour, now - ago),
            running = false, needsApproval = false, providerName = source.displayName, model = importedLogoHint(source),
        )
        Home(
            ready.copy(
                rows = rows.drop(2).take(3) + listOf(
                    imported(dev.locus.core.model.ChatImportSource.CHATGPT, "6789abcd", "Trip to Kyoto", 40 * day),
                    imported(dev.locus.core.model.ChatImportSource.CLAUDE, "c0ffee00", "Sourdough schedule", 260 * day),
                ),
            ),
        )
    }

    /** No chat featured, one locked chat waiting: counted, never named. */
    @Test
    fun homeWaitingLocked() = snap("home-waiting-locked") {
        Home(calm, waiting = listOf(WaitingEntry("c9", dev.locus.core.data.agent.WaitingKind.APPROVAL, title = null)))
    }

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

    // ---- Locked chats (docs/design/app-lock.md)

    /** The working chat and "Plan a 3-day trip to Lisbon" are locked chats this session hasn't unlocked. */
    private val locked = ready.copy(
        rows = rows.map { r ->
            if (r.conversation.id == "c1" || r.conversation.id == "c3") r.copy(locked = true, redacted = true, conversation = r.conversation.copy(title = "")) else r
        },
        chatLockAvailable = true,
    )

    @Test fun homeLockedChats() = snap("home-locked-chats") { Home(locked, projects) }

    /** All chats with the locked rows. (A row's menu is a popup the screenshot steps can't settle, so it isn't drawn here.) */
    @Test
    fun allChatsLocked() = snapAfter("home-all-chats-locked", listOf(Step.Tap("All chats")), heightDp = 1400) { Home(locked, projects) }

    // ---- Needs you (docs/design/needs-you.md)

    private fun proposal(
        id: String,
        source: String,
        tool: String,
        summary: String,
        args: String,
        ago: Long,
        preview: ApprovalPreview? = null,
        reason: String? = null,
    ) = Proposal(
        id = id, conversationId = "r-$id", taskId = "t-$id", source = source, callId = "call-$id", toolName = tool,
        argumentsJson = args, summary = summary, reason = reason, flags = setOfNotNull(ApprovalFlag.MUST_CONFIRM.takeIf { reason != null }),
        taintSources = emptySet(), preview = preview, createdAt = now - ago, expiresAt = now - ago + 7 * day, status = ProposalStatus.PENDING,
    )

    private val email = proposal(
        "mail", "Morning inbox", "email_send", "Send email to landlord@example.com: “Boiler” — The boiler is fixed, thank you for sending someone so quickly.",
        """{"to":"landlord@example.com","subject":"Boiler","body":"The boiler is fixed, thank you for sending someone so quickly."}""", 2 * hour,
        reason = "Sends a real email to landlord@example.com from the agent's mailbox.",
    )
    private val event = proposal(
        "cal", "Trip planner", "calendar_create", "Add “Lisbon flight” to Personal, Fri 14:05", """{"title":"Lisbon flight"}""", 5 * hour,
        preview = ApprovalPreview(
            listOf(ApprovalPreview.Row("Title", "Lisbon flight TP1351"), ApprovalPreview.Row("When", "Fri 14:05 – 16:20"), ApprovalPreview.Row("Guests", "ana@example.com", outward = true)),
        ),
    )
    private val draft = proposal("file", "Weekly review", "write_file", "Create notes/weekly-review.md (1240 characters)", """{"path":"notes/weekly-review.md"}""", day)
    private val expired = proposal("old", "Morning inbox", "email_send", "Send email to bank@example.com: “Statement” — Please send March's statement.", """{"to":"bank@example.com"}""", 8 * day)

    private val waiting = listOf(
        Waiting.Approval(ApprovalRequest("c2", "a1", "browser_click", ToolRisk.NETWORK, "Click “Reserve” on lulas.example", "{}"), "Book a table at Lula's", redacted = false, since = 3),
        Waiting.Question(UserQuestion("c4", "q1", "Should the search wait 300 ms or 500 ms after typing?", listOf("300 ms", "500 ms")), "Debounce a search field", redacted = false, since = 2),
        Waiting.Budget("c5", BudgetPause(BudgetLimit.TOKENS, "Paused: reached the 200k-token budget for this task."), "Summarise the March bank statement", redacted = false, since = 1),
    )

    private fun result(id: String, title: String, summary: String, ago: Long, outcome: TaskRunOutcome = TaskRunOutcome.DONE, event: Boolean = false, redacted: Boolean = false) =
        ResultItem(id, title, outcome, summary, null, now - ago, "r-$id", eventTriggered = event, redacted = redacted)

    private val results = listOf(
        result("news", "Morning news", "Three stories: the EU AI Act's first fines, a new Pixel update, and rain in Lisbon all weekend.", 40 * min),
        result("backup", "Backup watch", "The nightly backup failed: the disk on nas.local is full.", 3 * hour, TaskRunOutcome.FAILED, event = true),
        result("prices", "Flight prices", "Stopped: it took longer than 8 minutes.", 6 * hour, TaskRunOutcome.STOPPED),
        result("rent", "Rent reminder", "Rent is due on Monday.", 9 * hour),
    )

    private fun items(vararg p: Proposal, state: ProposalState = ProposalState.WAITING) = p.map { ProposalItem(it, state, "Scheduled: ${it.source}", redacted = false) }

    /** Nothing running, so the section sits right under the launcher. */
    private val calmHome = ready.copy(rows = rows.drop(2))

    @Test
    fun needsYouWaiting() = snap("home-needs-you-waiting", heightDp = 1600) { Home(ready, projects, needsYou = NeedsYouFeed(waiting = waiting)) }

    @Test
    fun needsYouProposals() = snap("home-needs-you-proposals", heightDp = 2400) {
        Home(calmHome, needsYou = NeedsYouFeed(proposals = items(email, event, draft, expired.copy(id = "more"))))
    }

    @Test
    fun needsYouResults() = snap("home-needs-you-results", heightDp = 1400) { Home(calmHome, projects, needsYou = NeedsYouFeed(results = results)) }

    /** Everything at once: a morning after a few scheduled runs. */
    @Test
    fun needsYouAll() = snap("home-needs-you-all", heightDp = 2400) {
        Home(ready, projects, needsYou = NeedsYouFeed(waiting = waiting.take(1), proposals = items(email), results = results.take(2)))
    }

    /** Nothing needs the user: home as it was (the latest-result card, the featured chat). */
    @Test
    fun needsYouEmpty() = snap("home-needs-you-empty") { Home(calmHome, projects, needsYou = NeedsYouFeed()) }

    @Test
    fun needsYouProposalCard() = snap("home-needs-you-proposal", heightDp = 1200) { Home(calmHome, needsYou = NeedsYouFeed(proposals = items(event))) }

    @Test
    fun needsYouExpired() = snap("home-needs-you-expired", heightDp = 1200) {
        Home(calmHome, needsYou = NeedsYouFeed(proposals = items(expired, state = ProposalState.EXPIRED)))
    }

    /** Approved and running, approved but failed, cut off by a restart, and back from a restored backup. */
    @Test
    fun needsYouSettled() = snap("home-needs-you-settled", heightDp = 1400) {
        Home(
            calmHome,
            needsYou = NeedsYouFeed(
                proposals = items(draft, state = ProposalState.RUNNING) + items(email.copy(id = "f"), state = ProposalState.FAILED) +
                    items(event.copy(id = "i"), state = ProposalState.INTERRUPTED) + items(expired.copy(id = "s"), state = ProposalState.STALE),
            ),
        )
    }

    /** A locked chat's approval, proposal and result keep their places and say nothing of what they are. */
    @Test
    fun needsYouLocked() = snap("home-needs-you-locked", heightDp = 1400) {
        Home(
            calmHome,
            needsYou = NeedsYouFeed(
                waiting = listOf((waiting[0] as Waiting.Approval).copy(redacted = true, chatTitle = "")),
                proposals = listOf(ProposalItem(email, ProposalState.WAITING, "", redacted = true)),
                results = listOf(result("diary", "Diary digest", "", 2 * hour, redacted = true), results[0]),
            ),
        )
    }
}
