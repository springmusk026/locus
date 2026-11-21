package dev.locus.core.data.needsyou

import dev.locus.core.agent.Proposals
import dev.locus.core.data.agent.ProposalApproval
import dev.locus.core.data.tasks.ScheduledRun
import dev.locus.core.database.ProposalEntity
import dev.locus.core.database.ScheduledTaskEntity
import dev.locus.core.model.AppSettings
import dev.locus.core.model.ApprovalDecision
import dev.locus.core.model.ModeNote
import dev.locus.core.model.Role
import dev.locus.core.model.ToolPolicy
import dev.locus.core.testing.ScriptedProvider
import dev.locus.core.testing.ScriptedProvider.script
import dev.locus.core.testing.TestGraph
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A scheduled run proposes a write instead of refusing it, and Approve in Needs you runs exactly that call once
 * (docs/design/needs-you.md): through the normal checks with today's settings, its outcome added to the run's chat
 * as a note for the agent. Declined and expired proposals never run; one a dead process was running never runs again.
 */
class ProposalApprovalTest {
    private val settings = AppSettings(
        providers = listOf(ScriptedProvider.config), defaultProviderId = ScriptedProvider.config.id,
        autoTitle = false, suggestFollowUps = false, reflectAfterTasks = false,
    )

    private val args = """{"path":"notes/plan.md","content":"Buy milk"}"""

    private class Fixture(val graph: TestGraph) {
        val file: File get() = graph.workspace.resolve("notes/plan.md")
        suspend fun proposal(): Proposal = graph.proposals.get(graph.database.proposalRows.single().id)!!
        fun approvedAudits() = graph.database.auditRows.count { it.toolName == "write_file" && it.decision == ApprovalDecision.USER_APPROVED.name }
    }

    /** A scheduled task whose run writes a file: proposed, not written. */
    private suspend fun TestScope.proposed(): Fixture {
        val g = TestGraph(backgroundScope, settings)
        g.database.scheduledTaskDao.upsert(ScheduledTaskEntity("t", "Morning plan", "Write my plan", 60, true, 0, null, null, null))
        g.script(ScriptedProvider.toolCall("call-1", "write_file", args), ScriptedProvider.text("I proposed writing notes/plan.md."))
        val run = ScheduledRun(g.tasks, g.conversations, g.settingsRepository, g.runner, g.secretResolver, g.httpClient, g.secrets)
        assertEquals(ScheduledRun.Result.DONE, run.run("t", "w1", runNow = false, stopReason = { 0 }, notify = { _, _, _ -> }))
        return Fixture(g)
    }

    @Test
    fun `a scheduled run's write is recorded as a proposal with its exact call, and nothing is written`() = runTest {
        val f = proposed()
        val p = f.proposal()
        assertEquals("write_file", p.toolName)
        assertEquals(args, p.argumentsJson)
        assertEquals("call-1", p.callId)
        assertEquals("t", p.taskId)
        assertEquals("Morning plan", p.source)
        assertEquals(ProposalStatus.PENDING, p.status)
        assertEquals(p.createdAt + Proposals.EXPIRY_MS, p.expiresAt)
        assertFalse(f.file.exists())
        // The model read that it was proposed, in the run's own chat.
        val result = f.graph.conversations.history(p.conversationId).flatMap { it.toolResults }.single()
        assertEquals(ApprovalDecision.PROPOSED, result.decision)
        assertTrue("Needs you" in result.content)
    }

    @Test
    fun `approve runs it once, as the user's approval, and tells the run's chat`() = runTest {
        val f = proposed()
        val p = f.proposal()
        assertEquals(ProposalApproval.Ran, f.graph.runner.approveProposal(p.id))
        assertEquals("Buy milk", f.file.readText())
        assertEquals(ProposalStatus.DONE, f.proposal().status)
        assertEquals(1, f.approvedAudits())
        val note = f.graph.conversations.history(p.conversationId).last()
        assertEquals(Role.USER, note.role)
        assertTrue(ModeNote.isAgentNote(note))
        assertEquals(p.summary, ModeNote.proposalApproved(note))
        assertTrue(note.parts.any { "approved your proposed write_file call (call-1)" in (it as? dev.locus.core.model.Part.Text)?.text.orEmpty() })

        // A second tap (or the notification racing the card) finds it settled and runs nothing.
        f.file.writeText("changed by the user")
        assertEquals(ProposalApproval.Gone, f.graph.runner.approveProposal(p.id))
        assertEquals("changed by the user", f.file.readText())
        assertEquals(1, f.approvedAudits())
    }

    @Test
    fun `two taps at once run it once`() = runTest {
        val f = proposed()
        val id = f.proposal().id
        val results = listOf(async { f.graph.runner.approveProposal(id) }, async { f.graph.runner.approveProposal(id) }).awaitAll()
        assertEquals(1, results.count { it == ProposalApproval.Ran }, results.toString())
        assertTrue(results.all { it == ProposalApproval.Ran || it == ProposalApproval.Busy || it == ProposalApproval.Gone }, results.toString())
        assertEquals(1, f.approvedAudits())
    }

    @Test
    fun `a declined proposal never runs`() = runTest {
        val f = proposed()
        val id = f.proposal().id
        assertTrue(f.graph.runner.declineProposal(id))
        assertEquals(ProposalStatus.DECLINED, f.proposal().status)
        assertEquals(ProposalApproval.Gone, f.graph.runner.approveProposal(id))
        assertFalse(f.file.exists())
        assertFalse(f.graph.runner.declineProposal(id))
    }

    @Test
    fun `an expired proposal never runs`() = runTest {
        val f = proposed()
        val id = f.proposal().id
        f.graph.proposalClock += Proposals.EXPIRY_MS
        assertEquals(ProposalApproval.Expired, f.graph.runner.approveProposal(id))
        assertFalse(f.file.exists())
        assertEquals(ProposalStatus.PENDING, f.proposal().status)
        assertTrue(f.proposal().expired(f.graph.proposals.now()))
    }

    @Test
    fun `approving checks again, so a tool the user turned off since is not run, and the chat says why`() = runTest {
        val f = proposed()
        val p = f.proposal()
        f.graph.settingsRepository.setToolPolicy("write_file", ToolPolicy.DENY)
        val result = f.graph.runner.approveProposal(p.id)
        assertTrue(result is ProposalApproval.Blocked, result.toString())
        assertFalse(f.file.exists())
        assertEquals(ProposalStatus.FAILED, f.proposal().status)
        val note = f.graph.conversations.history(p.conversationId).last()
        assertTrue(note.parts.any { "but it didn't run" in (it as? dev.locus.core.model.Part.Text)?.text.orEmpty() })
    }

    @Test
    fun `nothing approved from a proposal is remembered`() = runTest {
        val f = proposed()
        f.graph.runner.approveProposal(f.proposal().id)
        assertTrue(f.graph.settingsRepository.current().rememberedApprovals.isEmpty())
    }

    @Test
    fun `a proposal a dead process was running is never run again`() = runTest {
        val f = proposed()
        val p = f.proposal()
        // As a process that died mid-run leaves it.
        f.graph.database.proposalDao.claim(p.id, f.graph.proposals.now())
        f.graph.proposals.recoverInterrupted()
        assertEquals(ProposalStatus.INTERRUPTED, f.proposal().status)
        assertEquals(ProposalApproval.Gone, f.graph.runner.approveProposal(p.id))
        assertFalse(f.file.exists())
    }

    @Test
    fun `the same call proposed again while one waits isn't recorded twice, and too many waiting are refused`() = runTest {
        val f = proposed()
        val p = f.proposal()
        val draft = dev.locus.core.agent.ProposalDraft(p.conversationId, "call-2", p.toolName, p.argumentsJson, p.summary, null, emptySet(), emptySet(), null)
        val again = f.graph.proposals.record(ProposalOrigin("t", "Morning plan"), draft)
        assertEquals(dev.locus.core.agent.ProposalReceipt.Recorded(p.id, duplicate = true), again)
        repeat(Proposals.MAX_PENDING - 1) { i -> f.graph.proposals.record(ProposalOrigin("t", "x"), draft.copy(argumentsJson = """{"n":$i}""")) }
        val refused = f.graph.proposals.record(ProposalOrigin("t", "x"), draft.copy(argumentsJson = """{"n":"last"}"""))
        assertTrue(refused is dev.locus.core.agent.ProposalReceipt.Refused)
        assertEquals(Proposals.MAX_PENDING, f.graph.database.proposalRows.size)
    }

    @Test
    fun `a proposal goes with its chat`() = runTest {
        val f = proposed()
        val p = f.proposal()
        f.graph.conversations.delete(p.conversationId)
        assertTrue(f.graph.database.proposalRows.isEmpty())
        assertNotNull(p)
    }

    @Test
    fun `stored rows read back with their flags, taint and preview`() {
        val row = ProposalEntity(
            "p", "c", "t", "Inbox", "k", "email_send", "{}", "Send email", "Tainted run", "MUST_CONFIRM\nTAINTED\nNOT_A_FLAG",
            "email_read\nweb_read", """[{"label":"To","value":"a@b.c","outward":true}]""", 1, 2, "PENDING",
        )
        val p = Proposal.of(row)
        assertEquals(setOf(dev.locus.core.agent.ApprovalFlag.MUST_CONFIRM, dev.locus.core.agent.ApprovalFlag.TAINTED), p.flags)
        assertEquals(setOf("email_read", "web_read"), p.taintSources)
        assertEquals(listOf(dev.locus.core.agent.ApprovalPreview.Row("To", "a@b.c", outward = true)), p.preview?.rows)
        val request = p.request()
        assertEquals("p", request.callId)
        assertEquals("Send email\nTainted run", request.summary)
        assertEquals(null, request.remember)
        assertEquals(ProposalStatus.STALE, ProposalStatus.parse("SOMETHING_NEW"))
    }
}
