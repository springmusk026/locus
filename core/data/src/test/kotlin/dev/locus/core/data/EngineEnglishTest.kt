package dev.locus.core.data

import dev.locus.core.data.diagnostics.SelfTestRules
import dev.locus.core.data.diagnostics.StorageNotices
import dev.locus.core.data.integrity.PolicyIntegrity
import dev.locus.core.datastore.IntegrityAlert
import dev.locus.core.datastore.StoreNotices
import dev.locus.core.datastore.VaultCheck
import dev.locus.core.datastore.VaultState
import dev.locus.core.datastore.userMessage
import dev.locus.core.linux.EngineText
import dev.locus.core.model.SettingsDestination
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Text core/data makes for the screens reads, in English, exactly as the English it wrote before the
 * text moved into resources (docs/design/localization.md): the saved-keys check, settings changed
 * outside Locus and the stored-data notices. Rendered from the English strings with [EnglishResources].
 */
class EngineEnglishTest {
    private fun EngineText.en() = EnglishResources.render(this)

    @Test
    fun `the saved-keys check reads as before`() {
        val ready = "The Keystore key opens, encrypts and decrypts. "
        assertEquals(ready + "No keys are saved yet.", SelfTestRules.vault(VaultCheck(VaultState.Ready, entries = 0)).detail.en())
        assertEquals(ready + "1 saved value decrypts.", SelfTestRules.vault(VaultCheck(VaultState.Ready, entries = 1)).detail.en())
        assertEquals(ready + "3 saved values decrypt.", SelfTestRules.vault(VaultCheck(VaultState.Ready, entries = 3)).detail.en())
        assertEquals("bad file", SelfTestRules.vault(VaultCheck(VaultState.Ready, problem = "bad file")).detail.en())
        assertEquals(VaultState.Locked.userMessage(), SelfTestRules.vault(VaultCheck(VaultState.Locked)).detail.en())
        assertEquals(
            "Locus can't open your saved keys (gone). Resetting makes new keys; a copy of the old ones is kept in case they can be recovered.",
            SelfTestRules.vault(VaultCheck(VaultState.NeedsReset("gone", permanent = true))).detail.en(),
        )
        assertEquals(
            "Locus can't open your saved keys right now (KeyStoreException). Nothing was changed; this can pass on its own.",
            SelfTestRules.vault(VaultCheck(VaultState.Unavailable("KeyStoreException"))).detail.en(),
        )
        assertEquals("Locus can't open your saved keys right now. Nothing was changed; this can pass on its own.", SelfTestRules.vault(VaultCheck(VaultState.NotOpened)).detail.en())
    }

    @Test
    fun `settings changed outside Locus read as before, and link to the page they name`() {
        fun en(alert: IntegrityAlert) = PolicyIntegrity.describe(listOf(alert)).single().en()
        assertEquals(
            "Your tool permissions were changed outside Locus, so they were reset to safe settings: actions that change something ask you first, " +
                "and the AI reviewer is off. Deny settings were kept. Check Settings → Permissions.",
            en(IntegrityAlert.ToolPermissions),
        )
        assertEquals(
            "Model provider addresses, proxies or SSH machines were changed outside Locus. Locus won't send a provider its key until you check its " +
                "address and save it again (Settings → Models), proxies and imported certificates were removed, and each SSH machine's host key must be confirmed again.",
            en(IntegrityAlert.Endpoints),
        )
        assertEquals(
            "A connector was added or changed outside Locus (gh), so it was moved back to drafts. Review it in Library before approving again.",
            en(IntegrityAlert.Connectors(listOf("gh"))),
        )
        assertEquals(
            "Connectors were added or changed outside Locus (gh, render), so they were moved back to drafts. Review them in Library before approving again.",
            en(IntegrityAlert.Connectors(listOf("gh", "render"))),
        )
        assertEquals(
            "A skill was changed outside Locus (notes). Locus isn't using the changed version: it waits in Library → Skills as a draft marked " +
                "“Changed outside Locus”, and the version you approved is back where a copy was kept. Approve a change only if you made it.",
            en(IntegrityAlert.Skills(listOf("notes"))),
        )
        assertEquals(
            "Helpers were changed outside Locus (a, b). Locus isn't using the changed versions: they wait in Library → Helpers as drafts marked " +
                "“Changed outside Locus”, and the version you approved is back where a copy was kept. Approve a change only if you made it.",
            en(IntegrityAlert.Helpers(listOf("a", "b"))),
        )
        assertEquals(
            "Your agent's persona (IDENTITY.md, SOUL.md) was changed outside Locus. Locus isn't using the change: new chats get the version you saved. " +
                "Read the change in Settings → Agent → Agent identity, then keep or discard it.",
            en(IntegrityAlert.Persona(listOf("IDENTITY.md", "SOUL.md"))),
        )
        // Each one links to the page its English names, as the notice found it in the text before.
        val all = listOf(
            IntegrityAlert.ToolPermissions, IntegrityAlert.Endpoints, IntegrityAlert.Connectors(listOf("x")),
            IntegrityAlert.Skills(listOf("x")), IntegrityAlert.Helpers(listOf("x")), IntegrityAlert.Persona(listOf("x")),
        )
        all.forEach { alert -> assertEquals(SettingsDestination.inText(en(alert)), PolicyIntegrity.destination(alert), "$alert") }
    }

    @Test
    fun `stored-data notices read as stored, and unknown ones as they are`() {
        listOf(StoreNotices.VAULT_RESET, StoreNotices.SETTINGS_RESET, StoreNotices.KEYS_RECOVERED).forEach { notice ->
            assertTrue(StorageNotices.isKnown(notice))
            assertEquals(notice, StorageNotices.text(notice).en())
            assertEquals(SettingsDestination.Diagnostics, SettingsDestination.inText(notice))
        }
        assertEquals(EngineText.Plain("Something older."), StorageNotices.text("Something older."))
    }

    @Test
    fun `the heat banner and the connection checks read as before`() {
        (1..6).forEach { n ->
            val names = (1..n).map { "svc$it" }
            assertEquals(dev.locus.core.data.device.LinuxHeatAdvice.message(names), dev.locus.core.data.device.LinuxHeatAdvice.messageText(names).en())
        }
        assertEquals("Reached rss.example.com and it accepted the key (HTTP 200).", EngineText.Res(R.string.data_service_passed, "rss.example.com", 200).en())
        assertEquals(
            "rss.example.com refused the key (HTTP 401). Check it was copied whole and hasn't expired.",
            EngineText.Res(R.string.data_service_refused, "rss.example.com").en(),
        )
        assertEquals(
            "rss.example.com redirects to another address. Locus doesn't follow redirects with a key: use the address it redirects to.",
            EngineText.Res(R.string.data_service_redirect_elsewhere, "rss.example.com").en(),
        )
        assertEquals(
            "The secure connection to h failed: its certificate isn't trusted. A self-signed certificate isn't accepted.",
            EngineText.Res(R.string.data_service_tls_untrusted, "h").en(),
        )
    }
}
