package dev.locus.core.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The credential shapes every scrubber shares ([SecretScrubber.TOKEN_SHAPES]). */
class SecretScrubberTest {
    private val scrubber = SecretScrubber()

    // Built from pieces so a secret scanner doesn't flag the test file.
    private val formats = mapOf(
        "Telegram bot token" to "123456789" + ":AAH" + "k3j4h5g6f7d8s9a0qwertyuiopasdfghjk",
        "Groq" to "gsk_" + "aB3dE5gH7jK9mN1pQ3sT5vW7yZ9bD1fH3jL5",
        "Hugging Face" to "hf_" + "aBcDeFgHiJkLmNoPqRsTuVwXyZ0123456789",
        "GitLab" to "glpat-" + "aB3dE5gH7jK9mN1pQ3sT",
        "Slack app token" to "xapp-" + "1-A0123456789-1234567890123-abcdef0123456789",
        "Slack webhook" to "https://hooks.slack.com/services/" + "T00000000/B00000000/XXXXXXXXXXXXXXXXXXXXXXXX",
        "Google OAuth" to "ya29." + "a0AfH6SMBxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
        "PEM private key" to "-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASC\n-----END PRIVATE KEY-----",
        "JWT" to "eyJhbGciOiJIUzI1NiJ9" + ".eyJzdWIiOiIxMjM0NTY3ODkwIn0" + ".SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV",
        "AWS access key id" to "AKIA" + "IOSFODNN7EXAMPLE",
        "publishable key" to "pk-" + "live-aB3dE5gH7jK9mN1pQ3sT",
    )

    @Test
    fun `each credential format is removed`() {
        for ((name, token) in formats) {
            val out = scrubber.scrub("before $token after")
            assertFalse(token in out, "$name was not removed: $out")
            assertEquals("before ${SecretScrubber.SECRET} after", out, name)
        }
    }

    @Test
    fun `a Telegram token inside an API address is removed`() {
        val token = formats.getValue("Telegram bot token")
        val out = scrubber.scrub("GET https://api.telegram.org/bot$token/getMe")
        assertFalse(token in out, out)
        assertFalse("AAHk3j4h5g6f7d8s9a0" in out, out)
    }

    @Test
    fun `ordinary text with colons and hyphens is left alone`() {
        val text = "At 12:34:56 the task-assessment-for-the-project ran; ratio 1234567:2 and risk-management-plan-final-version"
        assertEquals(text, scrubber.scrub(text))
    }
}
