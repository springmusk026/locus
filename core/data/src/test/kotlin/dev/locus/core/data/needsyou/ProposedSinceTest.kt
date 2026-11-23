package dev.locus.core.data.needsyou

import dev.locus.core.agent.ProposalDraft
import dev.locus.core.testing.TestGraph
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What TaskWorker asks before folding a run's result notification into Needs you's summary (docs/design/needs-you.md). */
class ProposedSinceTest {
    private fun draft(chat: String, n: Int) = ProposalDraft(chat, "call-$n", "write_file", """{"path":"notes/$n.md","content":"x"}""", "Write notes/$n.md", null, emptySet(), emptySet(), null)

    @Test
    fun `only waiting proposals made at or after the run's start count`() = runTest {
        val g = TestGraph(backgroundScope)
        val chat = g.conversations.create(null).id
        g.proposalClock = 1_000L
        g.proposals.record(ProposalOrigin("t", "Morning plan"), draft(chat, 1))
        // A run that started after that proposal proposed nothing itself.
        assertFalse(g.proposals.proposedSince(2_000L))

        g.proposalClock = 3_000L
        g.proposals.record(ProposalOrigin("t", "Morning plan"), draft(chat, 2))
        assertTrue(g.proposals.proposedSince(2_000L))

        // Expired by the time the run ends: nothing to announce.
        g.proposalClock = 3_000L + dev.locus.core.agent.Proposals.EXPIRY_MS + 1
        assertFalse(g.proposals.proposedSince(2_000L))
    }
}
