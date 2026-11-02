package dev.locus.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Remembered approvals are sealed with the permissions (docs/design/remembered-approvals.md). */
class RememberedApprovalSealTest {
    private val rule = RememberedApproval("r1", "run_command", ApprovalScopeKind.COMMAND, "./gradlew test", createdAt = 1, expiresAt = 1_000)

    @Test
    fun `a rule made outside Locus breaks the seal and every rule is dropped`() {
        val key = TestKey()
        val withRule = AppSettings(rememberedApprovals = listOf(rule))
        assertFalse(PolicySeal.isDefault(withRule))
        val sealed = PolicySeal.sealed(withRule, key)
        assertEquals(SealCheck.VALID, PolicySeal.check(sealed, key))

        // Linux adding a rule, widening one, or making one last forever breaks the seal.
        val forged = sealed.copy(rememberedApprovals = sealed.rememberedApprovals + rule.copy(id = "r2", value = "sh"))
        assertEquals(SealCheck.TAMPERED, PolicySeal.check(forged, key))
        assertEquals(SealCheck.TAMPERED, PolicySeal.check(sealed.copy(rememberedApprovals = listOf(rule.copy(value = "./gradlew"))), key))
        assertEquals(SealCheck.TAMPERED, PolicySeal.check(sealed.copy(rememberedApprovals = listOf(rule.copy(expiresAt = null))), key))
        assertEquals(SealCheck.TAMPERED, PolicySeal.check(sealed.copy(rememberedApprovals = listOf(rule.copy(conversationId = null, projectId = "p"))), key))
        // Even a rule added to settings that were default (no seal yet) is caught once the key exists.
        assertEquals(SealCheck.TAMPERED, PolicySeal.check(AppSettings(rememberedApprovals = listOf(rule)), key))

        // The safe fallback keeps none: each one allows something.
        assertTrue(PolicySeal.restricted(forged).rememberedApprovals.isEmpty())
    }

    @Test
    fun `the order rules are stored in doesn't matter, and settings without rules seal as before`() {
        val key = TestKey()
        val other = rule.copy(id = "r0", tool = "write_file", kind = ApprovalScopeKind.FOLDER, value = "src")
        assertEquals(
            PolicySeal.seal(AppSettings(rememberedApprovals = listOf(rule, other)), key),
            PolicySeal.seal(AppSettings(rememberedApprovals = listOf(other, rule)), key),
        )
        val base = AppSettings(toolPolicies = mapOf("x" to ToolPolicy.ASK))
        assertEquals(PolicySeal.seal(base, key), PolicySeal.seal(base.copy(rememberedApprovals = emptyList()), key))
        // Revoking (removing a rule through Locus) is a normal sealed write.
        val revoked = PolicySeal.sealed(AppSettings(rememberedApprovals = listOf(rule, other)), key).let { it.copy(rememberedApprovals = it.rememberedApprovals - other) }
        assertEquals(SealCheck.TAMPERED, PolicySeal.check(revoked, key))
        assertEquals(SealCheck.VALID, PolicySeal.check(PolicySeal.sealed(revoked, key), key))
    }

    @Test
    fun `expiry`() {
        assertFalse(rule.expired(999))
        assertTrue(rule.expired(1_000))
        assertFalse(rule.copy(expiresAt = null).expired(Long.MAX_VALUE))
        assertEquals(30L * 24 * 60 * 60 * 1000, RememberedApproval.DEFAULT_TTL_MS)
    }
}
