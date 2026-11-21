package dev.locus.feature.library

import dev.locus.core.agent.home.MemoryAuthor
import dev.locus.core.agent.home.MemoryTidy
import dev.locus.core.data.repository.TidyAvailability
import dev.locus.core.data.repository.TidyFailure
import dev.locus.core.model.AppSettings
import dev.locus.core.testing.MainDispatcherRule
import dev.locus.core.testing.ScriptedProvider
import dev.locus.core.testing.ScriptedProvider.script
import dev.locus.core.testing.TestGraph
import dev.locus.core.testing.ViewModelHost
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Tidy up's screen states: nothing changes until the user accepts, only what they accept, and nothing on cancel. */
class MemoryTidyViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val host = ViewModelHost()

    private val settings = AppSettings(
        providers = listOf(ScriptedProvider.config), defaultProviderId = ScriptedProvider.config.id,
        autoTitle = false, suggestFollowUps = false, reflectAfterTasks = false,
    )

    private fun TestScope.graph(s: AppSettings = settings) = TestGraph(backgroundScope, s, dispatcher = StandardTestDispatcher(testScheduler)).also { g ->
        g.home.saveMemory("coffee", "Flat white", "Flat white with oat milk.", by = MemoryAuthor.AGENT)
        g.home.saveMemory("trip", "Trip in May 2025", "Flights for 3 May 2025.", by = MemoryAuthor.USER)
    }

    /** Scripts an answer that deletes both notes, by the ids the tidy-up will give them. */
    private fun TestGraph.scriptDeleteAll() {
        val ids = MemoryTidy.snapshot(listOf(MemoryTidy.Group(null, null, home.memoryTopics()))).entries.associate { it.topic.name to it.id }
        script(ScriptedProvider.text("""{"changes":[{"action":"delete","id":"${ids["coffee"]}","reason":"a"},{"action":"delete","id":"${ids["trip"]}","reason":"b"}]}"""))
    }

    private suspend fun MemoryTidyViewModel.review(): TidyUiState.Review = state.first { it is TidyUiState.Review } as TidyUiState.Review

    @Test
    fun `availability says why it can't run`() = runTest {
        try {
            val vm = host.add(MemoryTidyViewModel(graph(AppSettings()).memoryTidy))
            vm.checkAvailability()
            assertEquals(TidyAvailability.NO_MODEL, vm.availability.first { it != null })
        } finally {
            host.clear()
        }
    }

    @Test
    fun `applies only the accepted proposals`() = runTest {
        try {
            val g = graph()
            g.scriptDeleteAll()
            val vm = host.add(MemoryTidyViewModel(g.memoryTidy))
            vm.start()
            val review = vm.review()
            assertEquals(2, review.proposals.size)
            assertEquals(emptySet(), review.accepted, "nothing is accepted until the user ticks it")
            val trip = review.proposals.single { it.sources.single().topic.name == "trip" }
            vm.toggle(trip.id)
            vm.apply()
            val applied = vm.applied.first { it != null }
            assertEquals(MemoryTidy.Applied(1, emptyList()), applied)
            assertEquals(TidyUiState.Closed, vm.state.value)
            assertEquals(listOf("coffee"), g.home.memoryTopics().map { it.name })
            vm.consumeApplied()
            assertNull(vm.applied.value)
        } finally {
            host.clear()
        }
    }

    @Test
    fun `cancelling applies nothing, even with proposals ticked`() = runTest {
        try {
            val g = graph()
            g.scriptDeleteAll()
            val vm = host.add(MemoryTidyViewModel(g.memoryTidy))
            vm.start()
            val review = vm.review()
            review.proposals.forEach { vm.toggle(it.id) }
            vm.cancel()
            assertEquals(TidyUiState.Closed, vm.state.value)
            assertEquals(setOf("coffee", "trip"), g.home.memoryTopics().map { it.name }.toSet())
            assertNull(vm.applied.value)
        } finally {
            host.clear()
        }
    }

    @Test
    fun `a failed request shows the error and can be tried again`() = runTest {
        try {
            val g = graph()
            val vm = host.add(MemoryTidyViewModel(g.memoryTidy))
            vm.start()
            assertEquals(TidyUiState.Failed(TidyFailure.NO_ANSWER), vm.state.first { it is TidyUiState.Failed })
            g.script(ScriptedProvider.text("""{"changes":[]}"""))
            vm.start()
            val review = assertIs<TidyUiState.Review>(vm.state.first { it is TidyUiState.Review })
            assertEquals(emptyList(), review.proposals)
            assertEquals(2, review.read)
        } finally {
            host.clear()
        }
    }
}
