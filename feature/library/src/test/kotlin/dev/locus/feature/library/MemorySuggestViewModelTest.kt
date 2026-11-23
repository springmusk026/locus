package dev.locus.feature.library

import dev.locus.core.agent.home.MemoryAuthor
import dev.locus.core.agent.home.MemorySuggest
import dev.locus.core.agent.home.MemoryTidy
import dev.locus.core.data.repository.SuggestPeriod
import dev.locus.core.data.repository.SuggestScope
import dev.locus.core.data.repository.TidyAvailability
import dev.locus.core.data.repository.TidyFailure
import dev.locus.core.model.AppSettings
import dev.locus.core.model.ChatImportSource
import dev.locus.core.model.UsageSource
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
import kotlin.test.assertTrue

/**
 * "Suggest memories from imported chats" on screen: it needs a helper model, shows an estimate before
 * anything is sent, lands in Tidy up's review with nothing ticked, and saves only what the user ticks.
 */
class MemorySuggestViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val host = ViewModelHost()

    private val settings = AppSettings(
        providers = listOf(ScriptedProvider.config), defaultProviderId = ScriptedProvider.config.id,
        autoTitle = false, suggestFollowUps = false, reflectAfterTasks = false,
    )

    private suspend fun TestScope.graph(s: AppSettings = settings) = TestGraph(backgroundScope, s, dispatcher = StandardTestDispatcher(testScheduler)).also { g ->
        g.importChat(ChatImportSource.CHATGPT, "a", "Moving to Lisbon", listOf("I'm moving to Lisbon in March.", "Congratulations!"), updatedAt = System.currentTimeMillis())
        g.importChat(ChatImportSource.CLAUDE, "b", "Dog names", listOf("Our dog is called Rui.", "Lovely name."), updatedAt = System.currentTimeMillis() - 1_000)
    }

    /** Answers with one note per chat, by the refs the run gives them (newest first: c1 is Lisbon). */
    private fun TestGraph.scriptTwoNotes() = script(
        ScriptedProvider.text(
            """{"notes":[{"chat":"c1","topic":"home","summary":"Moving to Lisbon","content":"The user is moving to Lisbon in March."},""" +
                """{"chat":"c2","topic":"dog","summary":"Dog called Rui","content":"The user's dog is called Rui."}]}""",
        ),
    )

    private suspend fun MemorySuggestViewModel.estimate(): SuggestUiState.Choosing =
        state.first { it is SuggestUiState.Choosing && it.plan != null } as SuggestUiState.Choosing

    private suspend fun MemorySuggestViewModel.review(): SuggestUiState.Review = state.first { it is SuggestUiState.Review } as SuggestUiState.Review

    @Test
    fun `without a helper model the entry is disabled with its reason`() = runTest {
        try {
            val vm = host.add(MemorySuggestViewModel(graph(AppSettings()).memorySuggest))
            vm.refresh()
            assertEquals(TidyAvailability.NO_MODEL, vm.availability.first { it != null })
            assertEquals(mapOf(ChatImportSource.CHATGPT to 1, ChatImportSource.CLAUDE to 1), vm.counts.first { it != null })
        } finally {
            host.clear()
        }
    }

    @Test
    fun `opening shows an estimate and sends nothing, and the scope changes it`() = runTest {
        try {
            val g = graph()
            val vm = host.add(MemorySuggestViewModel(g.memorySuggest))
            vm.open()
            val choosing = vm.estimate()
            assertEquals(SuggestScope(), choosing.scope)
            assertEquals(2, choosing.plan!!.read)
            assertEquals(1, choosing.plan!!.requests)
            assertTrue(choosing.plan!!.estimatedTokens > MemorySuggest.OUTPUT_TOKENS)
            vm.choose(SuggestScope(source = ChatImportSource.CLAUDE))
            val claude = vm.state.first { it is SuggestUiState.Choosing && it.plan != null && it.scope.source == ChatImportSource.CLAUDE } as SuggestUiState.Choosing
            assertEquals(listOf("Dog names"), claude.plan!!.batches.single().map { it.chat.title })
            vm.choose(SuggestScope(period = SuggestPeriod.MONTHS_3))
            vm.state.first { it is SuggestUiState.Choosing && it.plan != null && it.scope.period == SuggestPeriod.MONTHS_3 }
            assertTrue(g.requests.none { it.url.host == "llm.test" }, "nothing is sent until Suggest")
        } finally {
            host.clear()
        }
    }

    @Test
    fun `saves only the ticked suggestions, as outside content from their chat`() = runTest {
        try {
            val g = graph()
            g.scriptTwoNotes()
            val vm = host.add(MemorySuggestViewModel(g.memorySuggest))
            vm.open()
            vm.estimate()
            vm.start()
            val review = vm.review()
            assertEquals(2, review.review.proposals.size)
            assertEquals(emptySet(), review.review.accepted, "nothing is ticked until the user ticks it")
            assertEquals(UsageSource.MEMORY_SUGGEST.wire, g.database.usageEventRows.single().source)
            val dog = review.review.proposals.single { (it as MemoryTidy.Proposal.New).topic == "dog" }
            vm.toggle(dog.id)
            vm.apply()
            assertEquals(MemoryTidy.Applied(1, emptyList()), vm.applied.first { it != null })
            assertEquals(SuggestUiState.Closed, vm.state.value)
            val saved = g.home.memoryTopics().single()
            assertEquals("dog", saved.name)
            assertEquals(MemoryAuthor.IMPORT, saved.provenance!!.by)
            assertEquals("imported-claude-b", saved.provenance!!.chatId)
            assertTrue(saved.provenance!!.tainted)
            vm.consumeApplied()
            assertNull(vm.applied.value)
        } finally {
            host.clear()
        }
    }

    @Test
    fun `cancelling saves nothing, even with suggestions ticked`() = runTest {
        try {
            val g = graph()
            g.scriptTwoNotes()
            val vm = host.add(MemorySuggestViewModel(g.memorySuggest))
            vm.open()
            vm.estimate()
            vm.start()
            vm.review().review.proposals.forEach { vm.toggle(it.id) }
            vm.cancel()
            assertEquals(SuggestUiState.Closed, vm.state.value)
            assertTrue(g.home.memoryTopics().isEmpty())
            assertNull(vm.applied.value)
        } finally {
            host.clear()
        }
    }

    @Test
    fun `a failed request shows the error, and Try again goes back to the estimate`() = runTest {
        try {
            val g = graph()
            val vm = host.add(MemorySuggestViewModel(g.memorySuggest))
            vm.open()
            vm.estimate()
            vm.start()
            assertEquals(SuggestUiState.Failed(TidyFailure.NO_ANSWER), vm.state.first { it is SuggestUiState.Failed })
            vm.retry()
            assertIs<SuggestUiState.Choosing>(vm.estimate())
            assertTrue(g.home.memoryTopics().isEmpty())
        } finally {
            host.clear()
        }
    }
}
