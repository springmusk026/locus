package dev.locus.core.ui

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import dev.locus.core.agent.integrations.EmailAccount
import dev.locus.core.agent.integrations.EmailConnectionTest
import dev.locus.core.agent.integrations.EmailPresets
import dev.locus.core.agent.integrations.Integrations
import dev.locus.core.agent.integrations.MailKind
import dev.locus.core.data.connectors.ConnectorTemplates
import dev.locus.core.data.mcp.McpTemplate
import dev.locus.core.data.mcp.McpTemplates
import dev.locus.core.model.ProviderPresets
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Every catalogue entry the screens show has its text in resources, and in English it reads exactly as
 * the catalogue's own English (which stays, for the model and for what is saved). A new entry or label
 * without a resource fails here instead of showing English in every language.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CatalogTextTest {
    private val r: Resources get() = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun `integrations read as their catalogue`() {
        Integrations.all.forEach { i ->
            assertNotNull(CatalogRes.INTEGRATION_SUMMARY[i.id], i.id)
            assertNotNull(CatalogRes.INTEGRATION_DESCRIPTION[i.id], i.id)
            assertNotNull(CatalogRes.LABEL[i.keyLabel], i.keyLabel)
            assertNotNull(CatalogRes.LINK[i.linkLabel], i.linkLabel)
            if (i.keyHelp != null) assertNotNull(CatalogRes.INTEGRATION_KEY_HELP[i.id], i.id)
            assertEquals(i.name, i.displayName(r))
            assertEquals(i.summary, i.summaryText(r))
            assertEquals(i.description, i.descriptionText(r))
            assertEquals(i.keyLabel, i.keyLabelText(r))
            assertEquals(i.linkLabel, i.linkLabelText(r))
            assertEquals(i.keyHelp, i.keyHelpText(r))
        }
    }

    @Test
    fun `MCP templates read as their catalogue`() {
        McpTemplate.Category.entries.forEach { assertEquals(it.label, it.labelText(r)) }
        McpTemplates.all.forEach { t ->
            assertNotNull(CatalogRes.MCP_DESCRIPTION[t.id], t.id)
            if (t.note != null) assertNotNull(CatalogRes.MCP_NOTE[t.id], t.id)
            if (t.key?.scopes != null) assertNotNull(CatalogRes.MCP_SCOPES[t.id], t.id)
            if (t.selfHosted?.fullWarning != null) assertNotNull(CatalogRes.MCP_FULL_WARNING[t.id], t.id)
            assertEquals(t.description, t.descriptionText(r))
            assertEquals(t.note, t.noteText(r))
            assertEquals(t.key?.scopes, t.scopesText(r))
            assertEquals(t.selfHosted?.fullWarning, t.fullWarningText(r))
            assertEquals(t.byline, t.bylineText(r), t.id)
            assertEquals(t.authSummary, t.authSummaryText(r), t.id)
            t.key?.let { key ->
                assertNotNull(CatalogRes.LABEL[key.label], key.label)
                assertNotNull(CatalogRes.PASTE[key.label], key.label)
                assertNotNull(CatalogRes.INDEFINITE[key.label], key.label)
                assertEquals(key.label, key.labelText(r))
                assertEquals(key.label.lowercase(), key.pasteText(r))
                assertEquals(key.sentAs, key.sentAsText(r))
            }
            t.local?.let { local ->
                assertNotNull(CatalogRes.MCP_NEEDS[local.needs], local.needs)
                assertEquals(local.needs, local.needsText(r))
                local.env.forEach { name ->
                    assertNotNull(CatalogRes.MCP_ENV_PHRASE[local.label(name)], name)
                    assertNotNull(CatalogRes.MCP_ENV_FIELD[local.label(name)], name)
                    // How the sheet labelled the field before it moved into resources.
                    assertEquals(local.label(name).removePrefix("your ").removePrefix("a ").replaceFirstChar { it.uppercase() }, local.fieldLabel(r, name))
                }
            }
        }
    }

    @Test
    fun `service templates read as their catalogue`() {
        ConnectorTemplates.all.forEach { t ->
            assertNotNull(CatalogRes.SERVICE_DESCRIPTION[t.id], t.id)
            assertNotNull(CatalogRes.SERVICE_WHERE[t.id], t.id)
            assertNotNull(CatalogRes.LABEL[t.auth.label], t.auth.label)
            assertNotNull(CatalogRes.SENTENCE[t.auth.label], t.auth.label)
            if (t.note != null) assertNotNull(CatalogRes.SERVICE_NOTE[t.id], t.id)
            t.auth.usernameLabel?.let { assertNotNull(CatalogRes.LABEL[it], it) }
            assertEquals(t.description, t.descriptionText(r))
            assertEquals(t.note, t.noteText(r))
            assertEquals(t.hostHint, t.hostHintText(r))
            assertEquals(t.auth.where, t.whereText(r))
            assertEquals(t.auth.label, t.auth.labelText(r))
            assertEquals(t.auth.usernameLabel, t.auth.usernameLabelText(r))
            assertEquals(t.auth.sentAs, t.auth.sentAsText(r), t.id)
            // The picker's "Needs its address and your …" lowered the label, keeping an acronym's capitals.
            val first = t.auth.label.substringBefore(' ')
            val lowered = if (first.length > 1 && first.all { it.isUpperCase() }) t.auth.label else t.auth.label.replaceFirstChar { it.lowercase() }
            assertEquals(lowered, t.auth.sentenceLabel(r))
        }
    }

    @Test
    fun `provider presets and email presets read as their catalogue`() {
        ProviderPresets.all.forEach { p ->
            assertNotNull(CatalogRes.PRESET_NOTE[p.name], p.name)
            assertEquals(p.note, p.noteText(r))
        }
        EmailPresets.all.forEach { p ->
            listOfNotNull(p.passwordLabel, p.passwordHint, p.fieldLabel, p.note).forEach { english ->
                assertNotNull(CatalogRes.EMAIL[english], english)
                assertEquals(english, r.emailPresetText(english))
            }
        }
    }

    @Test
    fun `the email check reads as its English message`() {
        val account = EmailPresets.all.first().let { p -> EmailAccount(smtp = p.smtp, imap = p.imap, username = "a@example.com", password = "pw-123", address = "a@example.com", fromName = null) }
        val failures = listOf(
            jakarta.mail.AuthenticationFailedException("no"),
            java.net.UnknownHostException("imap.example.com"),
            javax.net.ssl.SSLException("handshake"),
            java.net.SocketTimeoutException("slow"),
            java.net.ConnectException("refused"),
            IllegalStateException("BAD pw-123 command"),
            IllegalStateException(),
        )
        MailKind.entries.forEach { kind ->
            val server = if (kind == MailKind.IMAP) account.imap!! else account.smtp
            failures.forEach { e ->
                val failed = EmailConnectionTest.explain(kind, server, e, account)
                assertEquals(failed.message, failed.messageText(r), "$kind $e")
                assertTrue("pw-123" !in failed.messageText(r))
            }
        }
    }

    @Test
    fun `lists join with the language's words`() {
        assertEquals("", r.catalogList(emptyList()))
        assertEquals("a", r.catalogList(listOf("a")))
        assertEquals("a and b", r.catalogList(listOf("a", "b")))
        assertEquals("a, b and c", r.catalogList(listOf("a", "b", "c")))
    }
}
