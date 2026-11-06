package dev.locus.core.datastore

import androidx.datastore.core.DataStoreFactory
import dev.locus.core.model.AppSettings
import dev.locus.core.model.EffortDefaults
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The effort default's migration on a real settings file, as the previous release wrote it. */
class EffortDefaultsMigrationTest {
    private fun legacyFile(providers: String) = Files.createTempDirectory("settings").toFile().resolve("settings.json").apply {
        writeText("""{"providers":[$providers],"defaultProviderId":"a","policySeal":"","endpointSeal":"e1:abc"}""")
    }

    private val anthropic = """{"id":"a","name":"Anthropic","format":"ANTHROPIC_MESSAGES","baseUrl":"https://api.anthropic.com","model":"claude-opus-5-5","effort":"high"}"""
    private val gemini = """{"id":"g","name":"Gemini","format":"OPENAI_CHAT","baseUrl":"https://generativelanguage.googleapis.com/v1beta/openai","model":"gemini-3-flash","effort":"high"}"""

    @Test
    fun `the first read drops the unseen Chat Completions default and keeps the rest`() = runTest {
        val file = legacyFile("$anthropic,$gemini")
        val store = DataStoreFactory.create(
            serializer = JsonSerializer(AppSettings.serializer(), AppSettings()),
            migrations = listOf(EffortDefaultsMigration),
            scope = backgroundScope,
            produceFile = { file },
        )
        val read = store.data.first()
        assertEquals(listOf("high", ""), read.providers.map { it.effort })
        assertEquals(EffortDefaults.CURRENT, read.effortDefaults)
        // The seals are left as they were: neither covers the effort.
        assertEquals("e1:abc", read.endpointSeal)
        assertTrue("\"effortDefaults\":${EffortDefaults.CURRENT}" in file.readText())
    }

    @Test
    fun `settings with nothing to change aren't rewritten`() = runTest {
        val file = legacyFile(anthropic)
        val before = file.readText()
        val store = DataStoreFactory.create(
            serializer = JsonSerializer(AppSettings.serializer(), AppSettings()),
            migrations = listOf(EffortDefaultsMigration),
            scope = backgroundScope,
            produceFile = { file },
        )
        assertEquals("high", store.data.first().providers.single().effort)
        assertEquals(before, file.readText())
        assertFalse(EffortDefaultsMigration.shouldMigrate(AppSettings()))
    }
}
