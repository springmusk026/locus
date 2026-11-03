package dev.locus.feature.library

import dev.locus.core.agent.home.Connector
import dev.locus.core.data.repository.ConnectorEntry
import dev.locus.core.data.repository.KeyState
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What a connector card says about its key: a draft is never "Signed in" or "Saved" because some other credential exists. */
class ConnectorKeyNoteTest {
    private fun entry(auth: Connector.Auth, draft: Boolean, key: KeyState, shared: List<String> = emptyList()) = ConnectorEntry(
        Connector("issues", kind = "mcp", name = "Issues", description = "d", baseUrl = "https://attacker.example/mcp", auth = auth),
        draft = draft, hasSecret = key == KeyState.Ready, key = key, sharedWith = shared,
    )

    private val bearer = Connector.Auth("bearer", secret = "issues-token")
    private val oauth = Connector.Auth("oauth", secret = "issues-oauth")

    @Test
    fun `a draft says whose key it would use, never that it is saved or signed in`() {
        for (key in listOf(KeyState.Ready, KeyState.Unconfirmed, KeyState.Elsewhere(listOf("api.githubcopilot.com")), KeyState.Missing)) {
            for (auth in listOf(bearer, oauth)) {
                val note = connectorKeyNote(entry(auth, draft = true, key = key, shared = listOf("GitHub"))).orEmpty()
                assertFalse("Signed in" in note || "Saved" in note, note)
            }
        }
        val elsewhere = connectorKeyNote(entry(bearer, draft = true, key = KeyState.Elsewhere(listOf("api.githubcopilot.com")))).orEmpty()
        assertTrue("api.githubcopilot.com" in elsewhere && "won't send" in elsewhere, elsewhere)
        val shared = connectorKeyNote(entry(bearer, draft = true, key = KeyState.Ready, shared = listOf("GitHub"))).orEmpty()
        assertTrue("GitHub" in shared, shared)
    }

    @Test
    fun `an approved connector with its own key or sign-in says so`() {
        assertTrue("Saved." in connectorKeyNote(entry(bearer, draft = false, key = KeyState.Ready)).orEmpty())
        assertTrue("Signed in." in connectorKeyNote(entry(oauth, draft = false, key = KeyState.Ready)).orEmpty())
        assertTrue("confirm" in connectorKeyNote(entry(bearer, draft = false, key = KeyState.Unconfirmed)).orEmpty())
    }
}
