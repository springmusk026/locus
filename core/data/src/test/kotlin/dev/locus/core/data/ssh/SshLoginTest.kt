package dev.locus.core.data.ssh

import dev.locus.core.model.SshAuth
import dev.locus.core.model.SshHost
import kotlinx.coroutines.test.runTest
import org.connectbot.sshlib.AuthPublicKey
import org.connectbot.sshlib.KeyboardInteractiveCallback
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SshLoginTest {
    private fun host(auth: SshAuth) = SshHost("h", "box", "10.0.0.5", 22, "me", auth = auth.id, fingerprint = "SHA256:abc")

    @Test
    fun `key hosts use the key, else fall back to a saved password`() {
        assertEquals(SshLogin.Key("KEY", "phrase"), SshLogin.choose(host(SshAuth.KEY), "pw", "KEY", "phrase"))
        assertEquals(SshLogin.Key("KEY", null), SshLogin.choose(host(SshAuth.KEY), null, "KEY", " "))
        assertEquals(SshLogin.Password("pw"), SshLogin.choose(host(SshAuth.KEY), "pw", null, null))
        assertNull(SshLogin.choose(host(SshAuth.KEY), null, "", null))
    }

    @Test
    fun `password hosts use only the password`() {
        assertEquals(SshLogin.Password("pw"), SshLogin.choose(host(SshAuth.PASSWORD), "pw", "KEY", null))
        assertNull(SshLogin.choose(host(SshAuth.PASSWORD), "", "KEY", null))
    }

    @Test
    fun `no-auth hosts never send saved credentials`() {
        assertEquals(SshLogin.NoCredentials, SshLogin.choose(host(SshAuth.NONE), null, null, null))
        assertEquals(SshLogin.NoCredentials, SshLogin.choose(host(SshAuth.NONE), "pw", "KEY", "phrase"))
    }

    @Test
    fun `no-credentials handler offers nothing and answers prompts with empty strings`() = runTest {
        val handler = NoCredentialsAuthHandler
        assertEquals(emptyList(), handler.onPublicKeysNeeded())
        assertNull(handler.onSignatureRequest(AuthPublicKey("ssh-ed25519", ByteArray(4)), ByteArray(8)))
        assertNull(handler.onPasswordNeeded())
        val prompts = listOf(
            KeyboardInteractiveCallback.Prompt("Password: ", echo = false),
            KeyboardInteractiveCallback.Prompt("Code: ", echo = true),
        )
        assertEquals(listOf("", ""), handler.onKeyboardInteractivePrompt("", "", prompts))
        // RFC 4256 allows an info request with no prompts; it gets an empty response list.
        assertEquals(emptyList(), handler.onKeyboardInteractivePrompt("", "", emptyList()))
    }
}
