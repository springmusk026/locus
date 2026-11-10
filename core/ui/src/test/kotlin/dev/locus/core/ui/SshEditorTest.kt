package dev.locus.core.ui

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import dev.locus.core.model.SshAuth
import dev.locus.core.model.SshHost
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SshEditorTest {
    private val res: Resources = ApplicationProvider.getApplicationContext<Context>().resources
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
        val save = sshEditorSave(host(SshAuth.NONE), privateKey = "leftover", password = "leftover", passphrase = "x")
        assertEquals(SshEditorSave(host(SshAuth.NONE), "", "", ""), save)
    }

    @Test
    fun `password and key saves keep what the user did not retype`() {
        assertEquals(SshEditorSave(host(SshAuth.PASSWORD), "pw", null, null), sshEditorSave(host(SshAuth.PASSWORD), "", "pw", "ignored"))
        assertEquals(SshEditorSave(host(SshAuth.PASSWORD), null, null, null), sshEditorSave(host(SshAuth.PASSWORD), "", "", ""))
        assertEquals(SshEditorSave(host(SshAuth.KEY), null, "KEY", "phrase"), sshEditorSave(host(SshAuth.KEY), "KEY", "", "phrase"))
        // Editing other fields of a key host keeps its saved key and passphrase.
        assertEquals(SshEditorSave(host(SshAuth.KEY), null, null, null), sshEditorSave(host(SshAuth.KEY), "", "", ""))
    }

    @Test
    fun `text typed for one method never carries over to the other`() {
        // Typed a password, then switched to a key: the password isn't saved as the key.
        assertEquals(SshEditorSave(host(SshAuth.KEY), null, null, null), sshEditorSave(host(SshAuth.KEY), "", "hunter2", ""))
        assertFalse(sshSecretReady(SshAuth.KEY, SshAuth.KEY, isNew = true, privateKey = "", password = "hunter2"))
        // And a pasted key isn't sent as the password.
        assertEquals(SshEditorSave(host(SshAuth.PASSWORD), null, null, null), sshEditorSave(host(SshAuth.PASSWORD), "KEY", "", ""))
        assertFalse(sshSecretReady(SshAuth.PASSWORD, SshAuth.PASSWORD, isNew = true, privateKey = "KEY", password = ""))
        assertTrue(sshSecretReady(SshAuth.PASSWORD, SshAuth.PASSWORD, isNew = true, privateKey = "KEY", password = "pw"))
        assertTrue(sshSecretReady(SshAuth.NONE, SshAuth.KEY, isNew = true, privateKey = "", password = ""))
    }

    @Test
    fun `the host subtitle flags machines without authentication`() {
        assertEquals("me@10.0.0.5:22", sshHostSubtitle(res, host(SshAuth.KEY)))
        assertEquals("me@10.0.0.5:22 · no authentication", sshHostSubtitle(res, host(SshAuth.NONE)))
    }

    @Test
    fun `workflow tools read as plain verb phrases`() {
        // The English the sheets showed before the words moved to resources.
        val english = mapOf(
            "list_files" to "list files", "read_file" to "read files", "view_image" to "look at images",
            "write_file" to "write files", "edit_file" to "edit files", "multi_edit" to "edit files",
            "delete_path" to "delete files", "move_path" to "move files", "copy_path" to "copy files",
            "make_directory" to "make folders", "search_files" to "search files", "find_files" to "search files",
            "send_file" to "send you files", "run_command" to "run commands", "web_search" to "search the web",
            "web_read" to "read web pages", "fetch_url" to "read web pages", "git_commit" to "commit changes",
            "git_inspect" to "look at changes", "ask_user" to "ask you questions",
        )
        english.forEach { (tool, words) -> assertEquals(words, workflowToolName(res, tool), tool) }
        assertNull(workflowToolNameRes("linux_status"))
        assertEquals("linux status", workflowToolName(res, "linux_status"))
    }

    @Test
    @Config(qualifiers = "es")
    fun `in Spanish the words are Spanish`() {
        assertEquals("me@10.0.0.5:22 · sin autenticación", sshHostSubtitle(res, host(SshAuth.NONE)))
        assertEquals("ejecutar comandos", workflowToolName(res, "run_command"))
    }
}
