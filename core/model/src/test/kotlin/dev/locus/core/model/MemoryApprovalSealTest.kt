package dev.locus.core.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** "Ask before saving to memory" (docs/design/memory-approval.md) is sealed with the permissions. */
class MemoryApprovalSealTest {
    private val key = TestKey()

    @Test
    fun `the default is today's behaviour and keeps older seals valid`() {
        assertEquals(MemoryApproval.OUTSIDE_CONTENT, AppSettings().memoryApproval)
        assertTrue(PolicySeal.isDefault(AppSettings()))
        // A seal made before the setting existed still checks out: the default isn't part of the payload.
        val granted = AppSettings(toolPolicies = mapOf("write_file" to ToolPolicy.ALLOW))
        val sealed = PolicySeal.sealed(granted, key)
        assertEquals(SealCheck.VALID, PolicySeal.check(sealed.copy(memoryApproval = MemoryApproval.OUTSIDE_CONTENT), key))
    }

    @Test
    fun `Always is sealed, so switching it off outside Locus breaks the seal`() {
        val always = AppSettings(memoryApproval = MemoryApproval.ALWAYS)
        assertFalse(PolicySeal.isDefault(always))
        val sealed = PolicySeal.sealed(always, key)
        assertEquals(SealCheck.VALID, PolicySeal.check(sealed, key))
        val tampered = sealed.copy(memoryApproval = MemoryApproval.OUTSIDE_CONTENT, toolPolicies = mapOf("write_file" to ToolPolicy.ALLOW))
        assertEquals(SealCheck.TAMPERED, PolicySeal.check(tampered, key))
        // Like every grant, a broken seal falls back no further than the defaults: the user is told and can switch it back on.
        assertEquals(MemoryApproval.OUTSIDE_CONTENT, PolicySeal.restricted(tampered).memoryApproval)
    }

    @Test
    fun `settings files without the field read as the default`() {
        val s = Json { ignoreUnknownKeys = true }.decodeFromString(AppSettings.serializer(), """{"autoTitle":false}""")
        assertEquals(MemoryApproval.OUTSIDE_CONTENT, s.memoryApproval)
        val round = Json.decodeFromString(AppSettings.serializer(), Json.encodeToString(AppSettings.serializer(), AppSettings(memoryApproval = MemoryApproval.ALWAYS)))
        assertEquals(MemoryApproval.ALWAYS, round.memoryApproval)
    }
}
