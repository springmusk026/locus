package dev.locus.feature.analytics

import androidx.lifecycle.SavedStateHandle
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

    private companion object {
        /** One provider with one priced model, in models.dev's shape. */
        val CATALOG = """
            {"anthropic": {"id": "anthropic", "models": {"claude-test": {"id": "claude-test", "name": "Claude Test",
              "limit": {"context": 200000}, "cost": {"input": 3, "output": 15}}}}}
        """.trimIndent()
    }
}
