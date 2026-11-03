package dev.locus.core.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettingsDestinationTest {
    private fun opens(expected: SettingsDestination, text: String) = assertEquals(expected, SettingsDestination.inText(text), text)
    private fun opensNothing(text: String?) = assertNull(SettingsDestination.inText(text), text)

    @Test
    fun `chat errors open the page they name`() {
        opens(SettingsDestination.Models, "OpenAI has no API key. Add it in Settings → Models.")
        opens(SettingsDestination.Models, "OpenAI isn't signed in. Continue with ChatGPT in Settings → Models.")
        opens(SettingsDestination.Models, "Add a model provider in Settings → Models first.")
        opens(SettingsDestination.Integrations, "GitHub rejected the token. Ask the user to check it in Settings → Integrations → GitHub.")
        opens(SettingsDestination.Ssh, "build's host key hasn't been confirmed yet. Ask the user to confirm it in Settings → SSH machines.")
        opens(SettingsDestination.Permissions, "Deny settings were kept. Check Settings → Permissions.")
        opens(SettingsDestination.Automation, "You chose never to let Tasker start Locus tasks (Settings → Automation).")
        opens(SettingsDestination.Battery, "Locus holds a run while the phone is hot (Settings → Battery & heat).")
    }

    @Test
    fun `older names and sub-pages resolve`() {
        opens(SettingsDestination.Models, "Mistral needs an API key. Add one in Settings → Providers.")
        opens(SettingsDestination.Diagnostics, "The reset didn't open new keys. Details are in Settings → About & privacy → Diagnostics.")
        opens(SettingsDestination.About, "Run it again from Settings → About & privacy.")
        opens(SettingsDestination.Integrations, "Turn it on again in Locus → Settings → Integrations → Telegram.")
        opens(SettingsDestination.Backup, "Try again from Settings → Backup & restore.")
    }

    @Test
    fun `other apps' settings and plain mentions open nothing`() {
        opensNothing(null)
        opensNothing("")
        opensNothing("The model didn't answer.")
        opensNothing("Everything can be changed later in Settings.")
        opensNothing("Allow it in Settings → Apps → Locus → Permissions → Nearby devices, then try again.")
        opensNothing("Review it or raise the limit in ChatGPT Settings → Usage (https://chatgpt.com).")
        opensNothing("Create one in Stitch → Settings → API Keys")
        opensNothing("Turn on Settings → System → Developer options → Disable child process restrictions.")
    }

    @Test
    fun `every page has a path and a unique name`() {
        SettingsDestination.entries.forEach { d ->
            assertEquals(d, SettingsDestination.fromName(d.name))
            // A page's own words lead back to it.
            assertEquals(d, SettingsDestination.inText("See Settings → ${d.path.joinToString(" → ")}."), d.name)
        }
        assertNull(SettingsDestination.fromName("Hub"))
        assertNull(SettingsDestination.fromName(null))
    }

    @Test
    fun `a destination survives being saved in a route`() {
        SettingsDestination.entries.forEach { d ->
            assertEquals(d, Json.decodeFromString(SettingsDestination.serializer(), Json.encodeToString(SettingsDestination.serializer(), d)))
        }
    }
}
