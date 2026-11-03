package dev.locus.core.agent

import dev.locus.core.model.ApprovalDecision
import dev.locus.core.model.AuditEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Activity log shows calls without their secrets, saying what kind of value was taken out. */
class AuditRedactorTest {
    private val vaultValue = "vault-value-7f3a9c2e"
    private val redactor = AuditRedactor(listOf(vaultValue))

    private fun label(kind: String) = AuditRedactor.label(kind)

    @Test
    fun `vault values are redacted wherever they appear`() {
        val out = redactor.arguments("""{"command":"curl -u me:$vaultValue https://api.example","note":"$vaultValue"}""")
        assertFalse(vaultValue in out, out)
        assertTrue(label(AuditRedactor.VAULT) in out, out)
    }

    @Test
    fun `secret-shaped keys say what they held`() {
        val out = redactor.arguments(
            """{"url":"https://api.example/v1","headers":{"Authorization":"Bearer abc.def.ghi","X-Api-Key":"k-123456789","Cookie":"sid=1"},""" +
                """"password":"hunter2","access_token":"tok_1","max_tokens":4000,"query":"weather"}""",
        )
        assertTrue(""""Authorization":"${label(AuditRedactor.AUTHORIZATION)}"""" in out, out)
        assertTrue(""""X-Api-Key":"${label(AuditRedactor.TOKEN)}"""" in out, out)
        assertTrue(""""Cookie":"${label(AuditRedactor.COOKIE)}"""" in out, out)
        assertTrue(""""password":"${label(AuditRedactor.PASSWORD)}"""" in out, out)
        assertTrue(""""access_token":"${label(AuditRedactor.TOKEN)}"""" in out, out)
        // Ordinary values stay readable.
        assertTrue(""""max_tokens":4000""" in out && """"query":"weather"""" in out && "https://api.example/v1" in out, out)
        listOf("abc.def.ghi", "k-123456789", "hunter2", "tok_1").forEach { assertFalse(it in out, it) }
    }

    @Test
    fun `password form fields are redacted, other fields are not`() {
        val out = redactor.arguments(
            """{"fields":[{"name":"Email","value":"sam@example.com"},{"name":"Password","value":"hunter2"},{"label":"One-time code","value":"123456"}]}""",
        )
        assertTrue("sam@example.com" in out, out)
        assertFalse("hunter2" in out || "123456" in out, out)
        assertEquals(2, Regex(Regex.escape(label(AuditRedactor.FORM_FIELD))).findAll(out).count(), out)
    }

    @Test
    fun `URL credentials and secret query values are redacted`() {
        val out = redactor.arguments("""{"url":"https://sam:s3cretpass@db.example/x?page=2&token=abcdef123&sig=zz9&q=kotlin"}""")
        assertTrue("https://sam:${label(AuditRedactor.URL_CREDENTIAL)}@db.example" in out, out)
        assertTrue("token=${label(AuditRedactor.QUERY)}" in out && "sig=${label(AuditRedactor.QUERY)}" in out, out)
        assertTrue("page=2" in out && "q=kotlin" in out, out)
        assertFalse("s3cretpass" in out || "abcdef123" in out, out)
    }

    @Test
    fun `credential patterns in free text say so`() {
        val out = redactor.text("export OPENAI_API_KEY=sk-abcdefghijklmnop1234 and ghp_abcdefghijklmnopqrstuvwxyz0123")
        assertFalse("sk-abcdefghijklmnop1234" in out || "ghp_abcdefghijklmnopqrstuvwxyz0123" in out, out)
        assertTrue(label(AuditRedactor.CREDENTIAL) in out, out)
    }

    @Test
    fun `an entry keeps everything but its secrets`() {
        val entry = AuditEntry(
            "1", "c", "k", "fetch_url", """{"url":"https://x.example/?api_key=abcdef12"}""", ApprovalDecision.AUTO_ALLOWED,
            "Authorization: Bearer abcdefghijk", false, 1, 2,
        )
        val shown = redactor.entry(entry)
        assertEquals(entry.copy(argumentsJson = shown.argumentsJson, resultPreview = shown.resultPreview), shown)
        assertFalse("abcdef12" in shown.argumentsJson, shown.argumentsJson)
        assertFalse("abcdefghijk" in shown.resultPreview, shown.resultPreview)
    }

    @Test
    fun `text that isn't JSON still goes through`() {
        assertEquals("not json ${label(AuditRedactor.VAULT)}", redactor.arguments("not json $vaultValue"))
    }
}
