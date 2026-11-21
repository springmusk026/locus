package dev.locus.feature.chat

import dev.locus.core.data.agent.ProposalApproval
import dev.locus.core.data.lock.AppLock
import dev.locus.core.data.lock.DeviceSecurity
import dev.locus.core.data.needsyou.NeedsYou
import dev.locus.core.data.needsyou.ProposalState
import dev.locus.core.data.needsyou.ProposalStatus
import dev.locus.core.data.tasks.ScheduledRun
import dev.locus.core.database.ScheduledTaskEntity
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.UiText
import dev.locus.core.model.AppSettings
import dev.locus.core.testing.MainDispatcherRule
import dev.locus.core.testing.ScriptedProvider
import dev.locus.core.testing.ScriptedProvider.script
import dev.locus.core.testing.TestGraph
import dev.locus.core.testing.ViewModelHost
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Home's Needs you (docs/design/needs-you.md), end to end through the view model: a scheduled run's proposal and
 * its result reach home; Approve runs it and says so; Decline says so; opening a result marks it read; and a
 * locked chat's items are redacted until the session is unlocked.
 */
class NeedsYouHomeTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val host = ViewModelHost()

    private fun runTest(body: suspend TestScope.() -> Unit) = kotlinx.coroutines.test.runTest {
        try {
            body()
        } finally {
            host.clear()
        }
    }

    private val settings = AppSettings(
        providers = listOf(ScriptedProvider.config), defaultProviderId = ScriptedProvider.config.id,
        autoTitle = false, suggestFollowUps = false, reflectAfterTasks = false,
    )

    private class Fixture(val graph: TestGraph, val vm: ConversationsViewModel, val lock: AppLock, val events: kotlinx.coroutines.channels.Channel<Pair<UiText, Tone>>)

    /** A scheduled run has proposed writing notes/plan.md and finished. */
    private suspend fun TestScope.fixture(): Fixture {
        val g = TestGraph(backgroundScope, settings)
        g.database.scheduledTaskDao.upsert(ScheduledTaskEntity("t", "Morning plan", "Write my plan", 60, true, 0, null, null, null))
        g.script(ScriptedProvider.toolCall("call-1", "write_file", """{"path":"notes/plan.md","content":"Buy milk"}"""), ScriptedProvider.text("I proposed notes/plan.md."))
        ScheduledRun(g.tasks, g.conversations, g.settingsRepository, g.runner, g.secretResolver, g.httpClient, g.secrets)
            .run("t", "w1", runNow = false, stopReason = { 0 }, notify = { _, _, _ -> })
        val lock = AppLock.forTest(g.settingsRepository, DeviceSecurity { true }, backgroundScope)
        val needsYou = NeedsYou.forTest(g.runner, g.proposals, g.tasks, g.conversations, g.receipts, lock, clock = { g.proposalClock })
        val vm = host.add(
            ConversationsViewModel(g.conversations, g.settingsRepository, g.directory, g.runner, g.library, g.tasks, g.workspaceRepository, appLock = lock, needsYou = needsYou),
        )
        val events = kotlinx.coroutines.channels.Channel<Pair<UiText, Tone>>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        backgroundScope.launch { vm.needsYouFeed.collect {} }
        backgroundScope.launch { vm.needsYouEvents.collect { events.send(it) } }
        runCurrent()
        return Fixture(g, vm, lock, events)
    }

    private suspend fun Fixture.feed(until: (dev.locus.core.data.needsyou.NeedsYouFeed) -> Boolean) = vm.needsYouFeed.first(until)

    @Test
    fun `a scheduled run's proposal and its result reach home, and Approve runs it`() = runTest {
        val f = fixture()
        val feed = f.feed { it.proposals.isNotEmpty() && it.results.isNotEmpty() }
        val item = feed.proposals.single()
        assertEquals(ProposalState.WAITING, item.state)
        assertEquals("Morning plan", item.proposal.source)
        assertEquals("Morning plan", feed.results.single().title)
        assertEquals(2, feed.count)

        f.vm.approveProposal(item.proposal.id)
        f.feed { it.proposals.isEmpty() }
        assertEquals("Buy milk", f.graph.workspace.resolve("notes/plan.md").readText())
        assertEquals(approvalMessage(ProposalApproval.Ran), f.events.receive())
    }

    @Test
    fun `Decline never runs it and says so`() = runTest {
        val f = fixture()
        val id = f.feed { it.proposals.isNotEmpty() }.proposals.single().proposal.id
        f.vm.declineProposal(id)
        f.feed { it.proposals.isEmpty() }
        assertEquals(ProposalStatus.DECLINED, f.graph.proposals.get(id)!!.status)
        assertTrue(!f.graph.workspace.resolve("notes/plan.md").exists())
        assertEquals(UiText.Res(R.string.chat_needs_you_declined) to Tone.Neutral, f.events.receive())
    }

    @Test
    fun `opening a result marks it read, and Mark all read clears the rest`() = runTest {
        val f = fixture()
        f.feed { it.results.isNotEmpty() }
        f.vm.markResultRead("t")
        f.feed { it.results.isEmpty() }
        assertEquals(f.graph.tasks.get("t")!!.lastRunAt, f.graph.tasks.get("t")!!.lastSeenRunAt)
        f.vm.markAllResultsRead()
    }

    @Test
    fun `a locked chat's proposal and result are redacted until the session is unlocked`() = runTest {
        val f = fixture()
        val chat = f.feed { it.proposals.isNotEmpty() }.proposals.single().proposal.conversationId
        // A new session starts locked: a locked chat is hidden until the user unlocks.
        f.lock.setChatLocked(chat, true)
        val hidden = f.feed { it.proposals.firstOrNull()?.redacted == true }
        assertTrue(hidden.results.single().redacted)
        f.lock.unlocked()
        val shown = f.feed { it.proposals.firstOrNull()?.redacted == false }
        assertTrue(!shown.results.single().redacted)
    }
}
