package dev.locus.feature.activity

import dev.locus.core.data.repository.ActivityRepository
import dev.locus.core.database.AuditEntity
import dev.locus.core.database.ConversationEntity
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.AppSettings
import dev.locus.core.model.ApprovalDecision
import dev.locus.core.model.ProviderConfig
import dev.locus.core.screenshot.ScreenshotTest
import dev.locus.core.screenshot.UtcRule
import dev.locus.core.testing.MainDispatcherRule
import dev.locus.core.testing.TestGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test

/** Activity over the real view model and repositories, seeded with a realistic audit log. */
class ActivityScreenshots : ScreenshotTest() {

    @get:Rule
    val main = MainDispatcherRule()

    private val scope = CoroutineScope(SupervisorJob())

    @After
    fun tearDown() = scope.cancel()

    @get:org.junit.Rule
    val utc = UtcRule()

    /** A fixed clock: dates and clock times on the screen don't change with the day the test runs. */
    private val now = 1_793_000_000_000L
    private val minute = 60_000L
    private val day = 24 * 60 * minute

    private val settings = AppSettings(
        providers = listOf(
            ProviderConfig("anthropic", "Anthropic", ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.com", "claude-sonnet-4-5"),
            ProviderConfig("openrouter", "OpenRouter", ApiFormat.OPENAI_CHAT, "https://openrouter.ai/api/v1", "deepseek/deepseek-chat-v3.1"),
        ),
        defaultProviderId = "anthropic",
    )

    private fun graph(seed: Boolean): TestGraph {
        val g = TestGraph(scope, settings)
        if (!seed) return g
        runBlocking {
            val chats = g.database.conversationDao
            chats.upsert(ConversationEntity("c1", "Refactor the invoice parser to streaming", "anthropic", "", now - 2 * day, now - 5 * minute))
            chats.upsert(ConversationEntity("c2", "Plan the Lisbon trip in March", "openrouter", "", now - 3 * day, now - day))
            chats.upsert(ConversationEntity("h1", "Research", "anthropic", "", now - 40 * minute, now - 30 * minute, parentConversationId = "c1", agentType = "research", hidden = true))
            var n = 0
            suspend fun audit(chat: String, tool: String, args: String, decision: ApprovalDecision, result: String, at: Long, error: Boolean = false, ms: Long = 1_200) =
                g.database.auditDao.insert(AuditEntity("a${n++}", chat, "call${n}", tool, args, decision.name, result, error, at, at + ms))

            // Today, in the parser chat: a run of file work, a test run that failed, a declined push.
            audit("c1", "read_file", """{"path":"projects/invoice-parser/src/Parser.kt"}""", ApprovalDecision.AUTO_ALLOWED, "412 lines", now - 50 * minute, ms = 80)
            audit("c1", "search_files", """{"pattern":"BufferedReader","path":"projects/invoice-parser"}""", ApprovalDecision.AUTO_ALLOWED, "7 matches in 3 files", now - 49 * minute, ms = 140)
            audit("c1", "edit_file", """{"path":"projects/invoice-parser/src/Parser.kt"}""", ApprovalDecision.USER_APPROVED, "Replaced 38 lines", now - 47 * minute, ms = 9_000)
            audit("c1", "run_command", """{"command":"./gradlew test"}""", ApprovalDecision.REMEMBERED_ALLOWED, "BUILD FAILED: 2 tests failed in ParserTest", now - 45 * minute, error = true, ms = 48_000)
            audit("h1", "web_search", """{"query":"kotlin sequence vs flow streaming csv"}""", ApprovalDecision.AUTO_ALLOWED, "8 results", now - 40 * minute, ms = 2_100)
            audit("h1", "web_read", """{"url":"https://kotlinlang.org/docs/sequences.html"}""", ApprovalDecision.AUTO_ALLOWED, "Read 2,340 words", now - 39 * minute, ms = 3_400)
            audit("c1", "run_command", """{"command":"./gradlew test"}""", ApprovalDecision.REMEMBERED_ALLOWED, "BUILD SUCCESSFUL in 41s", now - 20 * minute, ms = 41_000)
            audit("c1", "git_commit", """{"message":"Stream invoices instead of loading them whole"}""", ApprovalDecision.REVIEWER_ALLOWED, "Committed 3 files", now - 12 * minute, ms = 600)
            audit("c1", "run_command", """{"command":"git push origin main"}""", ApprovalDecision.USER_DENIED, "You declined", now - 10 * minute, ms = 15_000)
            audit("c1", "memory_save", """{"text":"The invoice parser lives in projects/invoice-parser and uses Gradle."}""", ApprovalDecision.AUTO_ALLOWED, "Saved", now - 6 * minute, ms = 40)
            // Earlier, in the trip chat.
            audit("c2", "web_search", """{"query":"Lisbon March weather average"}""", ApprovalDecision.AUTO_ALLOWED, "10 results", now - day - 60 * minute, ms = 1_900)
            audit("c2", "browser_open", """{"url":"https://www.booking.com/city/pt/lisbon.html"}""", ApprovalDecision.USER_APPROVED, "Opened", now - day - 55 * minute, ms = 6_000)
            audit("c2", "email_send", """{"to":"ines@example.pt","subject":"March dates"}""", ApprovalDecision.POLICY_DENIED, "Blocked by your Permissions settings", now - day - 50 * minute, ms = 10)
            audit("c2", "write_file", """{"path":"notes/lisbon-itinerary.md"}""", ApprovalDecision.AUTO_ALLOWED, "Wrote 1.4 KB", now - day - 45 * minute, ms = 30)
        }
        return g
    }

    private fun viewModel(g: TestGraph) = ActivityViewModel(ActivityRepository(g.database.auditDao, g.secrets), g.conversations, g.settingsRepository)

    private fun ActivityViewModel.ready(until: (ActivityUiState) -> Boolean = { true }): ActivityUiState =
        runBlocking { withTimeout(10_000) { uiState.first { !it.loading && until(it) } } }

    @Test
    fun populated() {
        val vm = viewModel(graph(seed = true))
        vm.start(null)
        vm.ready { it.days.size == 2 }
        snap("activity", heightDp = 1400) { ActivityRoute(onBack = {}, viewModel = vm) }
    }

    @Test
    fun expandedRun() {
        val vm = viewModel(graph(seed = true))
        vm.start(null)
        val state = vm.ready { it.days.size == 2 }
        vm.toggle(state.days.first().runs.first().key)
        vm.ready { it.expanded.isNotEmpty() }
        snap("activity-run-expanded", heightDp = 1600) { ActivityRoute(onBack = {}, viewModel = vm) }
    }

    @Test
    fun oneChat() {
        val vm = viewModel(graph(seed = true))
        vm.start("c1")
        vm.ready { it.days.isNotEmpty() && it.scopeTitle != null }
        snap("activity-chat", heightDp = 1200) { ActivityRoute(onBack = {}, conversationId = "c1", viewModel = vm) }
    }

    @Test
    fun filteredFailed() {
        val vm = viewModel(graph(seed = true))
        vm.start(null)
        vm.ready { it.days.isNotEmpty() }
        vm.setStatus(StatusFilter.Failed)
        vm.ready { it.filters.status == StatusFilter.Failed && it.days.sumOf { d -> d.runs.size } == 1 }
        snap("activity-filter-failed") { ActivityRoute(onBack = {}, viewModel = vm) }
    }

    @Test
    fun noMatches() {
        val vm = viewModel(graph(seed = true))
        vm.start(null)
        vm.ready { it.days.isNotEmpty() }
        vm.setKind(KindFilter.Phone)
        vm.ready { it.filters.kind == KindFilter.Phone && it.days.isEmpty() }
        snap("activity-no-matches") { ActivityRoute(onBack = {}, viewModel = vm) }
    }

    @Test
    fun empty() {
        val vm = viewModel(graph(seed = false))
        vm.start(null)
        vm.ready()
        snap("activity-empty") { ActivityRoute(onBack = {}, viewModel = vm) }
    }
}
