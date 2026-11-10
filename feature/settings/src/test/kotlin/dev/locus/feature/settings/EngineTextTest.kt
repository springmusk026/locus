package dev.locus.feature.settings

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import dev.locus.core.data.diagnostics.ProviderError
import dev.locus.core.data.diagnostics.SelfTestFix
import dev.locus.core.data.diagnostics.SelfTestRules
import dev.locus.core.data.diagnostics.StorageArea
import dev.locus.core.linux.EngineText
import dev.locus.core.linux.LinuxHealth
import dev.locus.core.linux.LinuxReport
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.ProviderConfig
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * The text Settings shows from core/data (the self-test and Storage), rendered from resources in English,
 * reads exactly as the English core/data wrote before it moved into resources (docs/design/localization.md).
 * The saved-keys check, the start-up notices and the rest are checked in core/data's EngineEnglishTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EngineTextTest {
    private val r: Resources get() = ApplicationProvider.getApplicationContext<Context>().resources
    private fun EngineText.en() = asString(r)
    private val gb = 1024L * 1024 * 1024
    private val mb = 1024L * 1024

    @Test
    fun `storage areas read as before`() {
        val before = mapOf(
            StorageArea.Database to ("Chats and history" to "The database: chats, messages, activity and usage."),
            StorageArea.Workspace to ("Workspace" to "Files you and the agent keep."),
            StorageArea.Trash to ("Workspace trash" to "Deleted and overwritten files, kept so changes can be undone."),
            StorageArea.Linux to ("Linux" to "Alpine Linux, its packages and its caches."),
            StorageArea.Models to ("On-device models" to "LiteRT and GGUF models, and unfinished downloads."),
            StorageArea.Browser to ("Browser data" to "The agent browser's cookies, sign-ins and page cache."),
            StorageArea.Caches to ("Caches" to "Temporary files Locus makes again when needed."),
            StorageArea.Logs to ("Logs" to "Diagnostics entries on this phone."),
            StorageArea.AgentHome to ("Memory, skills and settings" to "What the agent learned, your skills and connectors, settings and saved keys."),
        )
        assertEquals(StorageArea.entries.toSet(), before.keys)
        StorageArea.entries.forEach { a ->
            assertEquals(before.getValue(a).first, r.getString(a.titleRes))
            assertEquals(before.getValue(a).second, r.getString(a.noteRes))
        }
    }

    @Test
    fun `self-test checks read as before`() {
        val fixes = mapOf(
            SelfTestFix.RunAgain to "Try again", SelfTestFix.ResetSavedKeys to "Reset keys", SelfTestFix.NotificationSettings to "Turn on",
            SelfTestFix.OpenStorage to "Free space", SelfTestFix.OpenLinux to "Open Linux", SelfTestFix.OpenModels to "Open Models",
        )
        assertEquals(SelfTestFix.entries.toSet(), fixes.keys)
        SelfTestFix.entries.forEach { assertEquals(fixes[it], r.getString(it.labelRes)) }
        assertEquals("Keystore and saved keys", SelfTestRules.VAULT_TITLE.en())
        assertEquals("Database", SelfTestRules.DATABASE_TITLE.en())
        assertEquals("Free storage", SelfTestRules.STORAGE_TITLE.en())
        assertEquals("Notifications", SelfTestRules.NOTIFICATIONS_TITLE.en())
        assertEquals("Linux", SelfTestRules.LINUX_TITLE.en())

        assertEquals("The integrity check couldn't run (SQLiteException).", SelfTestRules.database(null, "SQLiteException").detail.en())
        assertEquals("The integrity check couldn't run.", SelfTestRules.database(null, null).detail.en())
        assertEquals("The integrity check (quick_check) found no problems.", SelfTestRules.database(emptyList(), null).detail.en())
        val problems = listOf("row 3 missing from index idx_messages", "wrong # of entries")
        assertEquals(
            "The integrity check found 2 problems: ${problems.joinToString("; ")}. Save a backup in Settings → Backup & restore while Locus still reads your chats.",
            SelfTestRules.database(problems, null).detail.en(),
        )
        assertEquals(
            "The integrity check found 1 problem: x. Save a backup in Settings → Backup & restore while Locus still reads your chats.",
            SelfTestRules.database(listOf("x"), null).detail.en(),
        )

        assertEquals("Android didn't say how much space is free.", SelfTestRules.storage(null, null).detail.en())
        listOf(100 * mb, gb, 10 * gb).forEach { free ->
            val line = "${LinuxHealth.bytes(free)} free of ${LinuxHealth.bytes(128 * gb)}."
            val expected = when {
                free < LinuxHealth.STORAGE_PROBLEM_BYTES -> "$line Saving chats, files and downloads may fail."
                free < LinuxHealth.STORAGE_WARNING_BYTES -> "$line Large downloads (an on-device model, Linux tools) may not fit."
                else -> line
            }
            assertEquals(expected, SelfTestRules.storage(free, 128 * gb).detail.en())
        }

        assertEquals("On. Approvals, questions and finished tasks can reach you.", SelfTestRules.notifications(true, true).detail.en())
        assertEquals(
            "Off. While Locus isn't on screen, approvals and questions wait unseen and scheduled tasks can't tell you they're done.",
            SelfTestRules.notifications(false, true).detail.en(),
        )

        assertEquals("Not installed, so not checked.", SelfTestRules.linux(false, null).detail.en())
        assertEquals("Linux's health check couldn't run.", SelfTestRules.linux(true, null).detail.en())
        val healthy = LinuxReport(takenAt = 0, roundTrip = LinuxReport.RoundTrip(ok = true, millis = 200))
        assertEquals("Linux is working: 2 checks passed.", SelfTestRules.linux(true, healthy).detail.en())
        val broken = healthy.copy(runtime = LinuxReport.Runtime(prootPresent = false))
        assertEquals("Linux runtime is missing. 1 of 3 checks need attention; each has its fix on the Linux screen.", SelfTestRules.linux(true, broken).detail.en())

        val p = ProviderConfig("p1", "OpenAI", ApiFormat.OPENAI_CHAT, "https://api.openai.com/v1", "gpt-5")
        assertEquals("OpenAI", SelfTestRules.provider(p, null, 12, null).title.en())
        assertEquals("Not checked: OpenAI has no API key.", SelfTestRules.provider(p, "OpenAI has no API key.", null, null).detail.en())
        assertEquals("Reachable with its key: 12 models listed.", SelfTestRules.provider(p, null, 12, null).detail.en())
        assertEquals("Reachable with its key: 1 model listed.", SelfTestRules.provider(p, null, 1, null).detail.en())
        assertEquals("The key was refused (HTTP 401). Unauthorized", SelfTestRules.provider(p, null, null, ProviderError(EngineText.Plain("Unauthorized"), 401)).detail.en())
        assertEquals("The key was refused (HTTP 403).", SelfTestRules.provider(p, null, null, ProviderError(EngineText.Plain(""), 403)).detail.en())
        assertEquals("It answered but doesn't list models (HTTP 404); chats may still work.", SelfTestRules.provider(p, null, null, ProviderError(EngineText.Plain("Not found"), 404)).detail.en())
        assertEquals("Couldn't reach https://api.openai.com/v1: Connection refused", SelfTestRules.provider(p, null, null, ProviderError(EngineText.Plain("Connection refused"), null)).detail.en())
        assertEquals("Couldn't reach https://api.openai.com/v1: no answer", SelfTestRules.provider(p, null, null, null).detail.en())
        assertEquals(
            "Couldn't reach https://api.openai.com/v1: No answer in 20 seconds.",
            SelfTestRules.provider(p, null, null, ProviderError(EngineText.Plural(dev.locus.core.data.R.plurals.data_selftest_provider_timeout, 20), null)).detail.en(),
        )
        assertEquals("The check itself failed (IOException).", EngineText.Res(R.string.settings_selftest_check_failed, "IOException").en())
    }
}
