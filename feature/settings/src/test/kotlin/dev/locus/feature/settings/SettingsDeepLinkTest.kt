package dev.locus.feature.settings

import dev.locus.core.model.SettingsDestination
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** "Settings → X" links: which page they open, and where back goes from it. */
class SettingsDeepLinkTest {
    @Test
    fun `every link opens its own page`() {
        assertEquals(SettingsPage.Hub, settingsPageFor(null))
        SettingsDestination.entries.forEach { d ->
            // Same names on both sides: a destination without a page would silently open the hub.
            assertEquals(d.name, settingsPageFor(d).name)
        }
        assertEquals(SettingsPage.Ssh, settingsPageFor(SettingsDestination.Ssh))
        assertEquals(SettingsPage.Diagnostics, settingsPageFor(SettingsDestination.Diagnostics))
    }

    @Test
    fun `from the hub, back goes through the hub as before`() {
        val start = SettingsPage.Hub
        assertNull(settingsBack(SettingsPage.Hub, start))
        assertEquals(SettingsPage.Hub, settingsBack(SettingsPage.Models, start))
        assertEquals(SettingsPage.Hub, settingsBack(SettingsPage.About, start))
        assertEquals(SettingsPage.About, settingsBack(SettingsPage.Diagnostics, start))
        assertEquals(SettingsPage.Diagnostics, settingsBack(SettingsPage.SelfTest, start))
        assertEquals(SettingsPage.Diagnostics, settingsBack(SettingsPage.Storage, start))
    }

    @Test
    fun `back from a linked page returns to the link, not to a hub never seen`() {
        assertNull(settingsBack(SettingsPage.Models, start = SettingsPage.Models))
        assertNull(settingsBack(SettingsPage.Ssh, start = SettingsPage.Ssh))
        assertNull(settingsBack(SettingsPage.Diagnostics, start = SettingsPage.Diagnostics))
        // Opened on About, Diagnostics is still a step below it.
        assertEquals(SettingsPage.About, settingsBack(SettingsPage.Diagnostics, start = SettingsPage.About))
        assertNull(settingsBack(SettingsPage.About, start = SettingsPage.About))
        // Self-test and Storage sit below Diagnostics; opened by a link, back leaves Settings.
        assertNull(settingsBack(SettingsPage.SelfTest, start = SettingsPage.SelfTest))
        assertNull(settingsBack(SettingsPage.Storage, start = SettingsPage.Storage))
        assertEquals(SettingsPage.Diagnostics, settingsBack(SettingsPage.Storage, start = SettingsPage.Diagnostics))
    }
}
