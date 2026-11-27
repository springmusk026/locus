package dev.locus.core.ui

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import dev.locus.core.agent.home.Connector
import dev.locus.core.data.mcp.McpTemplates
import dev.locus.core.data.repository.LibraryRepository
import dev.locus.core.linux.EngineInputException
import dev.locus.core.linux.EngineText
import dev.locus.core.model.ProviderPresets
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Catalogue names that are words, and the descriptions Locus writes for connectors, are saved in English
 * (a preset's name becomes the provider's, a template's the connector's); screens show them in the app's
 * language, and in English exactly as saved. A name the user typed shows as it is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CatalogNamesTest {
    private val r: Resources get() = ApplicationProvider.getApplicationContext<Context>().resources

    private fun spanish(): Resources {
        val config = Configuration(r.configuration).apply { setLocale(Locale.forLanguageTag("es")) }
        return ApplicationProvider.getApplicationContext<Context>().createConfigurationContext(config).resources
    }

    @Test
    fun `preset names read as saved in English, and translate where they are words`() {
        ProviderPresets.all.forEach { p -> assertEquals(p.name, p.displayName(r)) }
        CatalogRes.PRESET_NAME.forEach { (english, _) -> assertEquals(english, r.providerName(english)) }
        assertEquals("Personalizado", spanish().providerName("Custom"))
        assertEquals("En este teléfono", spanish().providerName("On this phone"))
        // Product names and names the user typed stay.
        assertEquals("Anthropic", spanish().providerName("Anthropic"))
        assertEquals("My custom box", spanish().providerName("My custom box"))
    }

    @Test
    fun `MCP template names read as the catalogue's, and connectors added from them as saved`() {
        McpTemplates.all.forEach { t -> assertEquals(t.name, t.displayName(r)) }
        val files = McpTemplates.byId("filesystem")!!
        assertNotEquals(files.name, files.displayName(spanish()))
        val local = files.local!!
        val added = Connector(id = "files", kind = "mcp", name = files.name, description = LibraryRepository.LOCAL_DESCRIPTION + local.command,
            local = Connector.Local(local.command, emptyList(), emptyList()), createdBy = "user")
        assertEquals("Files", added.displayName(r))
        assertEquals("Archivos", added.displayName(spanish()))
        assertEquals(added.description, added.descriptionText(r))
        assertEquals("Servidor MCP local: ${local.command}", added.descriptionText(spanish()))
        // Renamed by the user: their name.
        assertEquals("My files", added.copy(name = "My files").displayName(spanish()))
    }

    @Test
    fun `the descriptions Locus writes read as saved`() {
        fun c(description: String) = Connector(id = "x", kind = "mcp", name = "x", description = description, baseUrl = "https://x.example/mcp", createdBy = "user")
        listOf(
            LibraryRepository.REMOTE_DESCRIPTION + "x.example",
            LibraryRepository.HOME_ASSISTANT_FULL,
            LibraryRepository.HOME_ASSISTANT_ASSIST,
            "Something the agent wrote.",
        ).forEach { assertEquals(it, c(it).descriptionText(r)) }
        assertEquals("Something the agent wrote.", c("Something the agent wrote.").descriptionText(spanish()))
        assertNotEquals(LibraryRepository.HOME_ASSISTANT_FULL, c(LibraryRepository.HOME_ASSISTANT_FULL).descriptionText(spanish()))
    }

    @Test
    fun `an engine error shows its words, any other its message`() {
        val worded = EngineInputException(EngineText.Res(dev.locus.core.data.R.string.data_address_empty), "Enter your server's address.")
        assertEquals("Enter your server's address.", worded.userText(dev.locus.core.designsystem.UiText.Plain("fallback")).asString(r))
        assertEquals("Escribe la dirección de tu servidor.", worded.userText(dev.locus.core.designsystem.UiText.Plain("fallback")).asString(spanish()))
        assertEquals("server said no", IllegalStateException("server said no").userText(dev.locus.core.designsystem.UiText.Plain("fallback")).asString(r))
        assertEquals("fallback", IllegalStateException().userText(dev.locus.core.designsystem.UiText.Plain("fallback")).asString(r))
    }
}
