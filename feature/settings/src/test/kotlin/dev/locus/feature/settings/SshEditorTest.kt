package dev.locus.feature.settings

import dev.locus.core.model.SshAuth
import dev.locus.core.model.SshHost
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SshEditorTest {
    private fun host(auth: SshAuth) = SshHost("h", "box", "10.0.0.5", 22, "me", auth = auth.id, fingerprint = "SHA256:abc")

    @Test
    fun `no authentication never asks for a secret`() {
        assertFalse(sshNeedsSecret(SshAuth.NONE, SshAuth.KEY, isNew = true))
        assertFalse(sshNeedsSecret(SshAuth.NONE, SshAuth.PASSWORD, isNew = false))
    }

    @Test
    fun `new machines and method changes ask for a secret`() {
        assertTrue(sshNeedsSecret(SshAuth.KEY, SshAuth.KEY, isNew = true))
        assertTrue(sshNeedsSecret(SshAuth.PASSWORD, SshAuth.NONE, isNew = false))
        assertTrue(sshNeedsSecret(SshAuth.KEY, SshAuth.PASSWORD, isNew = false))
        assertFalse(sshNeedsSecret(SshAuth.KEY, SshAuth.KEY, isNew = false))
    }

    @Test
    fun `saving as no authentication clears stored credentials`() {
        val save = sshEditorSave(host(SshAuth.NONE), secret = "leftover", passphrase = "x")
        assertEquals(SshEditorSave(host(SshAuth.NONE), "", "", ""), save)
    }

    @Test
    fun `password and key saves keep what the user did not retype`() {
        assertEquals(SshEditorSave(host(SshAuth.PASSWORD), "pw", null, null), sshEditorSave(host(SshAuth.PASSWORD), "pw", "ignored"))
        assertEquals(SshEditorSave(host(SshAuth.PASSWORD), null, null, null), sshEditorSave(host(SshAuth.PASSWORD), "", ""))
        assertEquals(SshEditorSave(host(SshAuth.KEY), null, "KEY", "phrase"), sshEditorSave(host(SshAuth.KEY), "KEY", "phrase"))
        // Editing other fields of a key host keeps its saved key and passphrase.
        assertEquals(SshEditorSave(host(SshAuth.KEY), null, null, null), sshEditorSave(host(SshAuth.KEY), "", ""))
    }
}
