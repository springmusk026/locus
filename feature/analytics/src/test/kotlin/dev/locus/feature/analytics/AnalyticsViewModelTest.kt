package dev.locus.feature.analytics

import androidx.lifecycle.SavedStateHandle
import dev.locus.core.data.repository.AnalyticsPeriod
import dev.locus.core.data.repository.AnalyticsRepository
import dev.locus.core.model.AppSettings
import dev.locus.core.testing.MainDispatcherRule
import dev.locus.core.testing.TestGraph
import dev.locus.core.testing.ViewModelHost
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Insights' "Turn on prices": the spend card's way out of "Unknown" (models.dev prices are opt-in). */
class AnalyticsViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val host = ViewModelHost()

    private fun runTest(body: suspend TestScope.() -> Unit) = kotlinx.coroutines.test.runTest {
        try {
            body()
        } finally {
            host.clear()
        }
    }

    private fun TestScope.graph() = TestGraph(backgroundScope, AppSettings(), dispatcher = StandardTestDispatcher(testScheduler))

    private fun TestScope.insights(g: TestGraph): AnalyticsViewModel {
        val repository = AnalyticsRepository(
            g.database.messageDao, g.database.auditDao, g.database.conversationDao, g.settingsRepository, g.tasks, g.database.usageEventDao, g.directory,
        )
        val vm = host.add(AnalyticsViewModel(repository, SavedStateHandle(), g.directory))
        backgroundScope.launch { vm.uiState.collect {} }
        return vm
    }

    /** Runs "Turn on prices" to the end and returns the state the card then shows. */
    private suspend fun AnalyticsViewModel.turnOnAndWait(expected: PricesSetup): AnalyticsUiState {
        assertNotNull(turnOnPrices(), "a tap while idle starts it").join()
        return uiState.first { it.prices == expected }
    }

    @Test
    fun `each period gets its own report after switching`() = runTest {
        val vm = insights(graph())
        assertEquals(AnalyticsPeriod.Week, vm.uiState.first { it.report != null }.report?.period)
        for (period in listOf(AnalyticsPeriod.Month, AnalyticsPeriod.All, AnalyticsPeriod.Week)) {
            vm.setPeriod(period)
            // The selection moves at once; the screen shows "Updating…" until the report for it arrives.
            assertEquals(period, vm.uiState.first { it.period == period }.period)
            assertEquals(period, vm.uiState.first { it.report?.period == period }.report?.period)
        }
    }

    @Test
    fun `turning prices on switches on the catalogue and loads it`() = runTest {
        val g = graph()
        g.http = { respond(CATALOG, HttpStatusCode.OK) }
        val vm = insights(g)
        assertFalse(g.settingsRepository.current().modelCatalog)

        val state = vm.turnOnAndWait(PricesSetup.Idle)

        assertEquals(PricesSetup.Idle, state.prices)
        assertTrue(g.settingsRepository.current().modelCatalog, "the same setting as Settings → Models")
        assertNotNull(vm.uiState.first { it.catalog != null }.catalog)
        assertTrue(g.requests.any { "models.dev" in it.url.host })
    }

    @Test
    fun `a download that fails says so, keeps prices on, and can be tried again`() = runTest {
        val g = graph() // every request gets a 404
        val vm = insights(g)

        val failed = vm.turnOnAndWait(PricesSetup.Failed)

        assertEquals(PricesSetup.Failed, failed.prices)
        assertTrue(g.settingsRepository.current().modelCatalog)
        assertEquals(CostAction.TryAgain, costAction(dev.locus.core.data.repository.CostReport(pricesOn = true), failed.catalog != null, failed.prices))

        // Try again runs the same action; it fails again here, and says so again rather than hanging.
        assertEquals(PricesSetup.Failed, vm.turnOnAndWait(PricesSetup.Failed).prices)
    }

    /**
     * The usage widget's "cost unknown" (docs/design/android-bridge.md, "The usage widget"): in a process
     * where nothing had loaded the catalogue (no chat screen, no Insights), today's figures had no prices.
     * Collecting them now loads it, so today's requests are priced without opening Locus.
     */
    @Test
    fun `today's usage loads the catalogue itself and prices the requests`() = runTest {
        val provider = dev.locus.core.model.ProviderConfig("a", "Anthropic", dev.locus.core.model.ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.com", "claude-test")
        val g = TestGraph(backgroundScope, AppSettings(providers = listOf(provider), defaultProviderId = "a", modelCatalog = true), dispatcher = StandardTestDispatcher(testScheduler))
        g.http = { respond(CATALOG, HttpStatusCode.OK) }
        val usage = kotlinx.serialization.json.Json.encodeToString(dev.locus.core.model.TokenUsage.serializer(), dev.locus.core.model.TokenUsage(input = 1_000_000, output = 0))
        g.database.usageEventDao.insert(dev.locus.core.database.UsageEventEntity(0, null, "title", System.currentTimeMillis(), "a", "claude-test", usage, null))
        val repository = AnalyticsRepository(
            g.database.messageDao, g.database.auditDao, g.database.conversationDao, g.settingsRepository, g.tasks, g.database.usageEventDao, g.directory,
        )
        assertNull(g.directory.catalog.value, "nothing else loaded the catalogue")

        val today = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            kotlinx.coroutines.withTimeout(20_000) { repository.today().first { it.cost.priced > 0 } }
        }

        assertEquals(1, today.requests)
        // $3 per million input tokens.
        assertEquals(3.0, today.cost.usd, 1e-9)
        assertEquals(7, today.week.size)
        assertEquals(1, today.week.last().requests)
    }

    /** Your picks: Keep this one in a chat updates Insights' table, read from the phone's settings. */
    @Test
    fun `picks kept in chats show in Insights`() = runTest {
        val g = graph()
        val repository = AnalyticsRepository(
            g.database.messageDao, g.database.auditDao, g.database.conversationDao, g.settingsRepository, g.tasks, g.database.usageEventDao, g.directory,
        )
        val vm = host.add(AnalyticsViewModel(repository, SavedStateHandle(), g.directory, settings = g.settingsRepository))
        backgroundScope.launch { vm.uiState.collect {} }
        val a = dev.locus.core.model.ModelRef("a", "claude-opus-5-5")
        val b = dev.locus.core.model.ModelRef("r", "openai/gpt-5")
        g.settingsRepository.recordCompare(dev.locus.core.model.CompareTurn("c1", "u1", a, b))
        assertTrue(g.settingsRepository.keepCompare("c1", "u1", b))
        val picks = vm.uiState.first { it.picks.isNotEmpty() }.picks.single()
        assertEquals(1, picks.total)
        assertEquals(1, if (picks.first == b) picks.firstKept else picks.secondKept)
    }

    private companion object {
        /** One provider with one priced model, in models.dev's shape. */
        val CATALOG = """
            {"anthropic": {"id": "anthropic", "models": {"claude-test": {"id": "claude-test", "name": "Claude Test",
              "limit": {"context": 200000}, "cost": {"input": 3, "output": 15}}}}}
        """.trimIndent()
    }
}
