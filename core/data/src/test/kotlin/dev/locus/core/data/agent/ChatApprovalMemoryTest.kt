package dev.locus.core.data.agent

import dev.locus.core.agent.ApprovalScope
import dev.locus.core.model.AppSettings
import dev.locus.core.model.ApprovalScopeKind
import dev.locus.core.model.ProjectPolicy
import dev.locus.core.model.RememberedApproval
import dev.locus.core.model.ToolPolicy
import dev.locus.core.testing.TestGraph
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Remembered approvals as the app stores them (docs/design/remembered-approvals.md): in the settings,
 * bound to their chat and project, with expiry, revoke, and project restrictions that win.
 */
class ChatApprovalMemoryTest {
    private fun TestScope.graph(s: AppSettings = AppSettings()) = TestGraph(backgroundScope, s)

    private val gradle = buildJsonObject { put("command", "./gradlew test") }
    private val scope = ApprovalScope("run_command", ApprovalScopeKind.COMMAND, "./gradlew test", "./gradlew test …")

    @Test
    fun `always rules expire after 30 days and apply in every chat outside projects`() = runTest {
        val g = graph()
        var now = 1_000L
        ChatApprovalMemory(g.settingsRepository, "c1", null, clock = { now }, ids = { "r1" }).remember(scope, forChat = false)
        val rule = g.settingsRepository.current().rememberedApprovals.single()
        assertNull(rule.conversationId)
        assertEquals(1_000L + RememberedApproval.DEFAULT_TTL_MS, rule.expiresAt)
        assertNotNull(ChatApprovalMemory(g.settingsRepository, "c2", null, clock = { now }).match("run_command", gradle))
        // Not in a project's chats: those have their own rules.
        assertNull(ChatApprovalMemory(g.settingsRepository, "c3", "p1", clock = { now }).match("run_command", gradle))
        now += RememberedApproval.DEFAULT_TTL_MS
        assertNull(ChatApprovalMemory(g.settingsRepository, "c2", null, clock = { now }).match("run_command", gradle))
    }

    @Test
    fun `chat rules apply in their chat only and go with it`() = runTest {
        val g = graph()
        val chat = g.conversations.create(null)
        val other = g.conversations.create(null)
        ChatApprovalMemory(g.settingsRepository, chat.id, null).remember(scope, forChat = true)
        assertNotNull(ChatApprovalMemory(g.settingsRepository, chat.id, null).match("run_command", gradle))
        assertNull(ChatApprovalMemory(g.settingsRepository, other.id, null).match("run_command", gradle))
        assertNull(g.settingsRepository.current().rememberedApprovals.single().expiresAt)
        g.conversations.delete(chat.id)
        assertTrue(g.settingsRepository.current().rememberedApprovals.isEmpty())
    }

    @Test
    fun `remembering the same scope again replaces the old rule`() = runTest {
        val g = graph()
        var id = 0
        val memory = ChatApprovalMemory(g.settingsRepository, "c1", null, ids = { "r${id++}" })
        memory.remember(scope, forChat = false)
        memory.remember(scope, forChat = false)
        assertEquals(listOf("r1"), g.settingsRepository.current().rememberedApprovals.map { it.id })
    }

    @Test
    fun `revoke stops a rule at once`() = runTest {
        val g = graph()
        val memory = ChatApprovalMemory(g.settingsRepository, "c1", null, ids = { "r1" })
        memory.remember(scope, forChat = false)
        assertNotNull(memory.match("run_command", gradle))
        g.settingsRepository.revokeRememberedApproval("r1")
        assertNull(memory.match("run_command", gradle))
        assertTrue(g.settingsRepository.current().rememberedApprovals.isEmpty())
    }

    @Test
    fun `a project's own Ask or Deny wins over rules, which aren't offered there either`() = runTest {
        val g = graph(AppSettings(projectPolicies = mapOf("p1" to ProjectPolicy(toolPolicies = mapOf("run_command" to ToolPolicy.ASK)))))
        val memory = ChatApprovalMemory(g.settingsRepository, "c1", "p1")
        memory.remember(scope, forChat = false)
        assertNull(memory.match("run_command", gradle))
        assertFalse(memory.offers("run_command"))
        assertTrue(memory.offers("write_file"))
        // Another project's rules don't reach in, and a deleted project's rules are dropped.
        val other = ChatApprovalMemory(g.settingsRepository, "c2", "p2")
        other.remember(scope, forChat = false)
        assertNotNull(other.match("run_command", gradle))
        g.settingsRepository.forgetRememberedApprovals(projectId = "p2")
        assertNull(other.match("run_command", gradle))
    }
}
