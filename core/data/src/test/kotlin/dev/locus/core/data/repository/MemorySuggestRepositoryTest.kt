package dev.locus.core.data.repository

import dev.locus.core.agent.home.MemoryAuthor
import dev.locus.core.agent.home.MemorySuggest
import dev.locus.core.agent.home.MemoryTidy
import dev.locus.core.model.AppSettings
import dev.locus.core.model.ChatImportSource
import dev.locus.core.model.UsageSource
import dev.locus.core.testing.ScriptedProvider
import dev.locus.core.testing.ScriptedProvider.script
import dev.locus.core.testing.TestGraph
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** "Suggest memories from imported chats" through the app's helper model (docs/design/import-chats.md). */
class MemorySuggestRepositoryTest {
    private val settings = AppSettings(
        providers = listOf(ScriptedProvider.config), defaultProviderId = ScriptedProvider.config.id,
        autoTitle = false, suggestFollowUps = false, reflectAfterTasks = false,
    )

    private val day = 24L * 60 * 60 * 1000
    private val now = 1_790_000_000_000L

    private fun TestScope.graph(s: AppSettings = settings) = TestGraph(backgroundScope, s, dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))

    private suspend fun TestGraph.seed() {
        importChat(ChatImportSource.CHATGPT, "a", "Moving to Lisbon", listOf("I'm moving to Lisbon in March.", "Congratulations!"), updatedAt = now - 10 * day)
        importChat(ChatImportSource.CLAUDE, "b", "Dog names", listOf("Our dog is called Rui.", "Lovely name."), updatedAt = now - 400 * day)
        // A chat of the user's own: never read.
        conversations.create(ScriptedProvider.config.id)
    }

    @Test
    fun `without a model it's unavailable and sends nothing`() = runTest {
        val g = graph(AppSettings())
        g.seed()
        assertEquals(TidyAvailability.NO_MODEL, g.memorySuggest.availability())
        val plan = g.memorySuggest.plan(SuggestScope(), now)
        assertEquals(SuggestOutcome.Failed(TidyFailure.UNAVAILABLE), g.memorySuggest.propose(plan, now))
        assertTrue(g.requests.isEmpty())
    }

    @Test
    fun `counts and plans only imported chats, by app and by period`() = runTest {
        val g = graph()
        g.seed()
        assertEquals(mapOf(ChatImportSource.CHATGPT to 1, ChatImportSource.CLAUDE to 1), g.memorySuggest.importedCounts())
        val all = g.memorySuggest.plan(SuggestScope(), now)
        assertEquals(2, all.read)
        assertEquals(1, all.requests)
        // Newest first.
        assertEquals(listOf("Moving to Lisbon", "Dog names"), all.batches.single().map { it.chat.title })
        assertEquals("ChatGPT", all.batches.single().first().chat.sourceName)
        assertEquals(listOf("Dog names"), g.memorySuggest.plan(SuggestScope(source = ChatImportSource.CLAUDE), now).batches.single().map { it.chat.title })
        assertEquals(listOf("Moving to Lisbon"), g.memorySuggest.plan(SuggestScope(period = SuggestPeriod.YEAR), now).batches.single().map { it.chat.title })
        val none = g.memorySuggest.plan(SuggestScope(source = ChatImportSource.CLAUDE, period = SuggestPeriod.MONTHS_3), now)
        assertEquals(0, none.read)
        assertEquals(SuggestOutcome.Failed(TidyFailure.NOTHING), g.memorySuggest.propose(none, now))
        assertTrue(g.requests.isEmpty(), "planning sends nothing")
    }

    @Test
    fun `one request per batch, its cost recorded, and only accepted notes saved as outside content`() = runTest {
        val g = graph()
        g.seed()
        g.home.saveMemory("home", "Old note", "Something else.", by = MemoryAuthor.USER)
        val plan = g.memorySuggest.plan(SuggestScope(), now)
        val refs = plan.batches.single().associate { it.chat.title to it.ref }
        g.script(
            ScriptedProvider.text(
                """{"notes":[{"chat":"${refs["Moving to Lisbon"]}","topic":"home","summary":"Moving to Lisbon in March","content":"The user is moving to Lisbon in March.","reason":"Where they'll live."},""" +
                    """{"chat":"${refs["Dog names"]}","topic":"dog","summary":"Dog called Rui","content":"The user's dog is called Rui.","reason":"Family."}]}""",
            ),
        )
        val outcome = assertIs<SuggestOutcome.Proposals>(g.memorySuggest.propose(plan, now))
        assertEquals(1, g.requests.count { it.url.host == "llm.test" })
        assertEquals(UsageSource.MEMORY_SUGGEST.wire, g.database.usageEventRows.single().source)
        assertEquals(0, outcome.failedRequests)
        val (home, dog) = outcome.proposals.map { assertIs<MemoryTidy.Proposal.New>(it) }
        assertEquals("home-2", home.topic, "never the name of a saved note")
        assertEquals(1, g.home.memoryTopics().size, "proposing saves nothing")

        val applied = g.memorySuggest.apply(listOf(dog))
        assertEquals(MemoryTidy.Applied(1, emptyList()), applied)
        assertEquals(setOf("home", "dog"), g.home.memoryTopics().map { it.name }.toSet())
        val origin = g.home.memoryTopics().single { it.name == "dog" }.provenance!!
        assertEquals(MemoryAuthor.IMPORT, origin.by)
        assertEquals("imported-claude-b", origin.chatId)
        assertEquals(listOf(MemorySuggest.TAINT_SOURCE), origin.taintedBy)
        // Library links the note to the imported chat it came from, which is still there.
        assertEquals("Dog names", g.conversations.get(origin.chatId!!)!!.title)
    }

    @Test
    fun `a failed request or an unreadable answer saves nothing`() = runTest {
        val g = graph()
        g.seed()
        val plan = g.memorySuggest.plan(SuggestScope(), now)
        // Unscripted: the provider answers 404.
        assertEquals(SuggestOutcome.Failed(TidyFailure.NO_ANSWER), g.memorySuggest.propose(plan, now))
        g.script(ScriptedProvider.text("Nothing stood out."))
        assertEquals(SuggestOutcome.Failed(TidyFailure.MALFORMED), g.memorySuggest.propose(plan, now))
        assertTrue(g.home.memoryTopics().isEmpty())
    }
}
