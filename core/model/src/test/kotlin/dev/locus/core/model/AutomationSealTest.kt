package dev.locus.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which apps may start Locus tasks is sealed with the permissions (docs/design/android-bridge.md). */
class AutomationSealTest {
    @Test
    fun `automation settings are sealed, and a broken seal turns automation off`() {
        val key = TestKey()
        val on = AppSettings(automationEnabled = true, automationCallers = mapOf("net.dinglisch.android.taskerm" to true, "com.evil" to false))
        assertFalse(PolicySeal.isDefault(on))
        val sealed = PolicySeal.sealed(on, key)
        assertEquals(SealCheck.VALID, PolicySeal.check(sealed, key))
        // An app allowed from outside Locus (a Linux program editing the settings file) breaks the seal.
        val forged = sealed.copy(automationCallers = sealed.automationCallers + ("com.evil" to true))
        assertEquals(SealCheck.TAMPERED, PolicySeal.check(forged, key))
        val restricted = PolicySeal.restricted(forged)
        assertFalse(restricted.automationEnabled)
        // Only the "never" answers survive.
        assertEquals(emptyMap(), restricted.automationCallers)
        assertEquals(mapOf("com.evil" to false), PolicySeal.restricted(sealed).automationCallers)
        // Switching it on from outside breaks the seal too.
        assertEquals(SealCheck.TAMPERED, PolicySeal.check(PolicySeal.sealed(AppSettings(toolPolicies = mapOf("x" to ToolPolicy.ASK)), key).copy(automationEnabled = true), key))
        // Settings without automation seal as before, so older seals still match.
        assertTrue(PolicySeal.isDefault(AppSettings()))
        assertEquals(PolicySeal.seal(AppSettings(toolPolicies = mapOf("x" to ToolPolicy.ASK)), key), PolicySeal.seal(AppSettings(toolPolicies = mapOf("x" to ToolPolicy.ASK), automationEnabled = false), key))
    }
}
