package dev.locus.core.browser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What snapshots say about saved logins (docs/design/browser.md, "Saved logins"). */
class SavedLoginSnapshotTest {
    private fun parse(json: String) = PageSnapshot.parse(Json.parseToJsonElement(json).jsonObject)

    private val signIn = parse(
        """
        {"title":"Sign in","url":"https://accounts.example.com/login","login":true,"items":[
          {"ref":"e1","role":"textbox","name":"Email","type":"email","filled":true},
          {"ref":"e2","role":"textbox","name":"Password","type":"password","filled":true},
          {"ref":"e3","role":"textbox","name":"Code","value":"123456","filled":true}
        ]}
        """,
    )

    @Test
    fun `a saved login is announced by origin only, on a sign-in page`() {
        val head = SnapshotFormat.header(signIn, BrowserContext(savedLogin = "https://accounts.example.com"))
        val note = head.single { "Saved login available" in it }
        assertEquals(SnapshotFormat.savedLoginNote("https://accounts.example.com"), note)
        assertTrue("browser_login" in note && "\"https://accounts.example.com\"" in note)
        assertFalse(head.any { "browser_ask_user" in it })
        // A second step that asks only for a code counts too.
        val code = signIn.copy(loginForm = false, otpField = true)
        assertTrue(SnapshotFormat.header(code, BrowserContext(savedLogin = "https://accounts.example.com")).any { "Saved login available" in it })
    }

    @Test
    fun `without a saved login the page is handed to the user, and a CAPTCHA always is`() {
        val head = SnapshotFormat.header(signIn)
        assertTrue(head.any { "no saved login" in it && "browser_ask_user" in it })
        val captcha = SnapshotFormat.header(signIn.copy(captcha = true), BrowserContext(savedLogin = "https://accounts.example.com"))
        assertTrue(captcha.any { "CAPTCHA" in it })
        assertFalse(captcha.any { "Saved login available" in it })
    }

    @Test
    fun `fields Locus filled never show their values`() {
        val lines = SnapshotFormat.body(signIn)
        assertEquals(3, lines.count { "value=<filled by Locus>" in it })
        assertFalse(lines.any { "123456" in it })
    }
}
