package dev.locus.feature.chat.voice

import dev.locus.core.agent.ApprovalFlag
import dev.locus.core.agent.ApprovalOrigin
import dev.locus.core.agent.ApprovalRequest
import dev.locus.core.model.ToolRisk
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceApprovalsTest {

    private fun request(flags: Set<ApprovalFlag>? = emptySet(), note: String? = null, summary: String = "Write `notes.md`") =
        ApprovalRequest("chat", "c1", "write_file", ToolRisk.WRITE, summary, "{}", reviewerNote = note, flags = flags)

    @Test
    fun `only a plain Ask request may be answered by voice`() {
        assertNull(VoiceApprovals.tapReason(request()))
        assertTrue(VoiceApprovals.voiceAllowed(request()))
    }

    @Test
    fun `taint, the reviewer, payment-like confirmation, scope and unknown flags refuse voice`() {
        assertTrue("outside content" in VoiceApprovals.tapReason(request(setOf(ApprovalFlag.TAINTED)))!!)
        assertTrue("reviewer" in VoiceApprovals.tapReason(request(note = "Risky"))!!)
        assertTrue("payment" in VoiceApprovals.tapReason(request(setOf(ApprovalFlag.MUST_CONFIRM)))!!)
        assertNotNull(VoiceApprovals.tapReason(request(setOf(ApprovalFlag.SKILL_SCOPE))))
        assertNotNull(VoiceApprovals.tapReason(request(setOf(ApprovalFlag.ESCALATED))))
        assertNotNull(VoiceApprovals.tapReason(request(flags = null)))
        // The reviewer wins even on an otherwise plain request.
        assertFalse(VoiceApprovals.voiceAllowed(request(emptySet(), note = "Looks odd")))
    }

    @Test
    fun `the prompt reads the summary without markdown, and names a helper`() {
        assertEquals("Locus wants to: Write notes.md. Say allow, or deny.", VoiceApprovals.askPrompt(request()))
        val helper = request().copy(origin = ApprovalOrigin("a1", "Researcher #2"))
        assertTrue(VoiceApprovals.spokenSummary(helper).startsWith("Researcher #2 wants to:"))
        // Only the first line: the confirmation reasons under it aren't read as the action.
        assertEquals("Locus wants to: Send it", VoiceApprovals.spokenSummary(request(summary = "Send it\nTainted run: read web_read")))
    }

    @Test
    fun `allow and deny phrases`() {
        for (yes in listOf("yes", "Allow", "yeah allow it", "OK", "go ahead", "approve", "sure.")) {
            assertEquals(VoiceReply.ALLOW, VoiceApprovals.parse(yes, confirming = false), yes)
        }
        for (no in listOf("no", "deny", "Don't allow", "do not allow it", "cancel", "nope", "never allow that", "don’t")) {
            assertEquals(VoiceReply.DENY, VoiceApprovals.parse(no, confirming = false), no)
        }
    }

    @Test
    fun `mixed, empty or long answers are unclear`() {
        assertEquals(VoiceReply.UNCLEAR, VoiceApprovals.parse("yes no", confirming = false))
        assertEquals(VoiceReply.UNCLEAR, VoiceApprovals.parse("", confirming = false))
        assertEquals(VoiceReply.UNCLEAR, VoiceApprovals.parse("banana", confirming = false))
        assertEquals(VoiceReply.UNCLEAR, VoiceApprovals.parse("yes I was wondering whether you could explain what that does", confirming = false))
    }

    @Test
    fun `the confirm step needs the word confirm`() {
        assertEquals(VoiceReply.CONFIRM, VoiceApprovals.parse("confirm", confirming = true))
        assertEquals(VoiceReply.CONFIRM, VoiceApprovals.parse("Yes, confirmed.", confirming = true))
        assertEquals(VoiceReply.UNCLEAR, VoiceApprovals.parse("yes", confirming = true))
        assertEquals(VoiceReply.UNCLEAR, VoiceApprovals.parse("allow", confirming = true))
        assertEquals(VoiceReply.DENY, VoiceApprovals.parse("no", confirming = true))
        assertEquals(VoiceReply.DENY, VoiceApprovals.parse("do not confirm", confirming = true))
    }

    @Test
    fun `barge-in needs two words that aren't Locus's own`() {
        assertFalse(BargeIn.interrupts("allow", "Say allow, or deny."))
        assertFalse(BargeIn.interrupts("sunny today", "It is sunny today."))
        assertFalse(BargeIn.interrupts("okay", "It is sunny today."))
        assertTrue(BargeIn.interrupts("wait stop", "It is sunny today."))
    }
}
