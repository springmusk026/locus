package dev.locus.core.data.repository

import dev.locus.core.agent.home.MemoryAuthor
import dev.locus.core.agent.home.MemoryTidy
import dev.locus.core.model.AppSettings
import dev.locus.core.model.MemoryApproval
import dev.locus.core.model.UsageSource
import dev.locus.core.testing.ScriptedProvider
import dev.locus.core.testing.ScriptedProvider.script
import dev.locus.core.testing.TestGraph
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Library → Memory → Tidy up through the app's helper model (docs/design/memory-approval.md), and the
 * "Ask before saving to memory" setting as the tools read it.
 */
class MemoryTidyRepositoryTest {
    private val settings = AppSettings(
        providers = listOf(ScriptedProvider.config), defaultProviderId = ScriptedProvider.config.id,
        autoTitle = false, suggestFollowUps = false, reflectAfterTasks = false,
    )

    private fun TestScope.graph(s: AppSettings = settings) = TestGraph(backgroundScope, s, dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)).also { g ->
        g.home.saveMemory("coffee", "Flat white", "Flat white with oat milk.", by = MemoryAuthor.AGENT, chatId = "chat-a")
        g.home.saveMemory("coffee prefs", "No sugar", "No sugar.", by = MemoryAuthor.AGENT, chatId = "chat-b")
        g.home.saveMemory("trip", "Trip in May 2025", "Flights for 3 May 2025.", by = MemoryAuthor.USER)
    }

    @Test
    fun `without a model it's unavailable and asks nothing`() = runTest {
        val g = graph(AppSettings())
        assertEquals(TidyAvailability.NO_MODEL, g.memoryTidy.availability())
        assertEquals(TidyOutcome.Failed(TidyFailure.UNAVAILABLE), g.memoryTidy.propose())
        assertTrue(g.requests.isEmpty())
    }

    @Test
    fun `an on-device helper is unavailable`() = runTest {
        val onDevice = ScriptedProvider.config.copy(id = "phone", format = dev.locus.core.model.ApiFormat.ON_DEVICE)
        val g = graph(AppSettings(providers = listOf(onDevice), defaultProviderId = "phone"))
        assertEquals(TidyAvailability.HELPER_UNAVAILABLE, g.memoryTidy.availability())
    }

    @Test
    fun `proposes from one helper request, records its cost, and applies only what is accepted`() = runTest {
        val g = graph()
        assertEquals(TidyAvailability.READY, g.memoryTidy.availability())
        // The ids the repository will give each note: the same snapshot of the same notes.
        val ids = MemoryTidy.snapshot(listOf(MemoryTidy.Group(null, null, g.home.memoryTopics()))).entries.associate { it.topic.name to it.id }
        g.script(
            ScriptedProvider.text(
                """{"changes":[{"action":"merge","ids":["${ids["coffee"]}","${ids["coffee-prefs"]}"],"topic":"coffee","summary":"Flat white, no sugar","content":"Flat white with oat milk, no sugar.","reason":"Same topic."},""" +
                    """{"action":"delete","id":"${ids["trip"]}","reason":"Past trip."}]}""",
            ),
        )
        val outcome = assertIs<TidyOutcome.Proposals>(g.memoryTidy.propose())
        assertEquals(1, g.requests.count { it.url.host == "llm.test" })
        assertEquals(2, outcome.proposals.size)
        assertEquals(3, g.home.memoryTopics().size, "proposing changes nothing")
        assertEquals(UsageSource.MEMORY_TIDY.wire, g.database.usageEventRows.single().source)

        val delete = outcome.proposals.single { it is MemoryTidy.Proposal.Delete }
        val applied = g.memoryTidy.apply(listOf(delete))
        assertEquals(MemoryTidy.Applied(1, emptyList()), applied)
        assertEquals(setOf("coffee", "coffee-prefs"), g.home.memoryTopics().map { it.name }.toSet(), "the merge wasn't accepted")
    }

    @Test
    fun `a failed request or an unreadable answer changes nothing`() = runTest {
        val g = graph()
        // Unscripted: the provider answers 404.
        assertEquals(TidyOutcome.Failed(TidyFailure.NO_ANSWER), g.memoryTidy.propose())
        g.script(ScriptedProvider.text("Everything looks tidy to me!"))
        assertEquals(TidyOutcome.Failed(TidyFailure.MALFORMED), g.memoryTidy.propose())
        assertEquals(3, g.home.memoryTopics().size)
    }

    @Test
    fun `no notes, nothing to tidy`() = runTest {
        val g = TestGraph(backgroundScope, settings, dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        assertEquals(TidyOutcome.Failed(TidyFailure.NOTHING), g.memoryTidy.propose())
        assertTrue(g.requests.isEmpty())
    }

    @Test
    fun `the memory tools read the setting for each call, and it's saved`() = runTest {
        val g = graph()
        val writes = ToolCatalog.memoryWrites(g.settingsRepository, g.conversations)
        assertEquals(false, writes.alwaysAsk())
        g.settingsRepository.setMemoryApproval(MemoryApproval.ALWAYS)
        assertEquals(MemoryApproval.ALWAYS, g.settingsRepository.current().memoryApproval)
        assertEquals(true, writes.alwaysAsk())
        val save = assertNotNull(g.tools.registry.all.firstOrNull { it.spec.name == "memory_save" })
        assertEquals(true, save.asksFirst(kotlinx.serialization.json.buildJsonObject {}))
        g.settingsRepository.setMemoryApproval(MemoryApproval.OUTSIDE_CONTENT)
        assertEquals(false, save.asksFirst(kotlinx.serialization.json.buildJsonObject {}))
    }
}
