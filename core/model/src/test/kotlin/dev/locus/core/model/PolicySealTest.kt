package dev.locus.core.model

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** A software HMAC key standing in for the Keystore one. */
class TestKey(private val secret: ByteArray = ByteArray(32) { it.toByte() }, var new: Boolean = false, var broken: Boolean = false) : IntegrityKey {
    override fun mac(data: ByteArray): ByteArray {
        if (broken) throw IllegalStateException("keystore unavailable")
        return Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }.doFinal(data)
    }

    override fun createdThisRun(): Boolean {
        if (broken) throw IllegalStateException("keystore unavailable")
        return new
    }
}

class PolicySealTest {
    private val key = TestKey()
    private val granted = AppSettings(
        toolPolicies = mapOf("shell" to ToolPolicy.ALLOW, "send_email" to ToolPolicy.DENY, "write_file" to ToolPolicy.ASK),
        reviewerMode = ReviewerMode.REVIEW_ASKS,
        reviewerApprovesOutward = true,
    )

    @Test
    fun `sealed settings check out, and unrelated settings don't affect the seal`() {
        val sealed = PolicySeal.sealed(granted, key)
        assertEquals(SealCheck.VALID, PolicySeal.check(sealed, key))
        assertEquals(SealCheck.VALID, PolicySeal.check(sealed.copy(customInstructions = "be brief", autoTitle = false), key))
    }

    @Test
    fun `the seal doesn't depend on map order`() {
        val reordered = granted.copy(toolPolicies = granted.toolPolicies.entries.reversed().associate { it.key to it.value })
        assertEquals(PolicySeal.seal(granted, key), PolicySeal.seal(reordered, key))
    }

    @Test
    fun `granting anything without the key breaks the seal`() {
        val sealed = PolicySeal.sealed(granted, key)
        listOf(
            sealed.copy(toolPolicies = sealed.toolPolicies + ("delete_file" to ToolPolicy.ALLOW)),
            sealed.copy(toolPolicies = sealed.toolPolicies + ("send_email" to ToolPolicy.ALLOW)),
            sealed.copy(reviewerMode = ReviewerMode.REVIEW_ALL_BUT_READS),
            sealed.copy(reviewerApprovesOutward = false),
            sealed.copy(policySeal = ""),
            sealed.copy(policySeal = "v1:" + "0".repeat(64)),
        ).forEach { assertEquals(SealCheck.TAMPERED, PolicySeal.check(it, key), it.toString()) }
    }

    @Test
    fun `a seal made with another key doesn't count`() {
        val other = TestKey(ByteArray(32) { 7 })
        assertEquals(SealCheck.TAMPERED, PolicySeal.check(PolicySeal.sealed(granted, other), key))
    }

    @Test
    fun `defaults need no seal`() {
        assertEquals(SealCheck.DEFAULTS, PolicySeal.check(AppSettings(), key))
        assertEquals(SealCheck.DEFAULTS, PolicySeal.check(AppSettings(policySeal = "garbage"), key))
    }

    @Test
    fun `unsealed settings are trusted once, only when the key is new`() {
        assertEquals(SealCheck.FIRST_USE, PolicySeal.check(granted, TestKey(new = true)))
        assertEquals(SealCheck.TAMPERED, PolicySeal.check(granted, TestKey(new = false)))
        // A wrong seal is never "first use", even with a new key.
        assertEquals(SealCheck.TAMPERED, PolicySeal.check(granted.copy(policySeal = "v1:00"), TestKey(new = true)))
    }

    @Test
    fun `a key that can't be used fails closed`() {
        val sealed = PolicySeal.sealed(granted, key)
        assertEquals(SealCheck.TAMPERED, PolicySeal.check(sealed, TestKey(broken = true)))
    }

    @Test
    fun `restricted keeps only what restricts`() {
        val r = PolicySeal.restricted(granted)
        assertEquals(mapOf("send_email" to ToolPolicy.DENY, "write_file" to ToolPolicy.ASK), r.toolPolicies)
        assertEquals(ReviewerMode.OFF, r.reviewerMode)
        assertFalse(r.reviewerApprovesOutward)
        assertEquals("", r.policySeal)
        assertNotEquals(granted, r)
    }

    @Test
    fun `connector seals cover ids and contents`() {
        val approved = mapOf("todoist" to ConnectorSealFormat.sha256("{}".toByteArray()), "github" to "ab")
        val mac = ConnectorSealFormat.mac(approved, key)
        assertTrue(ConnectorSealFormat.matches(approved.entries.reversed().associate { it.key to it.value }, mac, key))
        assertFalse(ConnectorSealFormat.matches(approved + ("evil" to "cd"), mac, key))
        assertFalse(ConnectorSealFormat.matches(approved + ("github" to "ac"), mac, key))
        assertFalse(ConnectorSealFormat.matches(approved, mac, TestKey(broken = true)))
    }
}
