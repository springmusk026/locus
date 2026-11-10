package dev.locus.feature.settings

import dev.locus.core.designsystem.InlineCode
import dev.locus.core.model.ApprovalScopeKind
import dev.locus.core.model.RememberedApproval
import org.junit.Test
import kotlin.test.assertEquals
import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** How Settings → Permissions → Remembered names each rule and how long it lasts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RememberedRowsTest {
    private val res: Resources get() = ApplicationProvider.getApplicationContext<Context>().resources

    private val day = 24L * 60 * 60 * 1000

    @Test
    fun `titles say what the rule allows in words`() {
        assertEquals("Run ./gradlew test\u00A0…", rememberedTitle(res, RememberedApproval("1", "run_command", ApprovalScopeKind.COMMAND, "./gradlew test")).text)
        assertEquals("Write files in src/main/", rememberedTitle(res, RememberedApproval("2", "write_file", ApprovalScopeKind.FOLDER, "src/main")).text)
        assertEquals("Write files in the top folder", rememberedTitle(res, RememberedApproval("3", "write_file", ApprovalScopeKind.FOLDER, "")).text)
        assertEquals("Fetch api.github.com (GET)", rememberedTitle(res, RememberedApproval("4", "fetch_url", ApprovalScopeKind.SITE, "GET https://api.github.com")).text)
        assertEquals("Open github.com", rememberedTitle(res, RememberedApproval("5", "browser_open", ApprovalScopeKind.SITE, "github.com")).text)
        assertEquals("Add events to Personal", rememberedTitle(res, RememberedApproval("6", "calendar_add", ApprovalScopeKind.CALENDAR, "12:Personal")).text)
        assertEquals(
            "Change events in your calendar",
            rememberedTitle(res, RememberedApproval("7", dev.locus.core.model.DeviceIntegrations.CALENDAR_UPDATE, ApprovalScopeKind.CALENDAR, "12")).text,
        )
    }

    @Test
    fun `a command is set in the code font, without backticks`() {
        val title = rememberedTitle(res, RememberedApproval("1", "run_command", ApprovalScopeKind.COMMAND, "npm test"))
        val code = title.spanStyles.single { it.item == InlineCode }
        assertEquals("npm test\u00A0…", title.text.substring(code.start, code.end))
        // A folder's name is shown as it is, a backtick included.
        val folder = rememberedTitle(res, RememberedApproval("2", "write_file", ApprovalScopeKind.FOLDER, "odd`name`"))
        assertEquals("Write files in odd`name`/", folder.text)
        assertEquals(emptyList(), folder.spanStyles)
    }

    @Test
    fun `subtitles say where and how long`() {
        val always = RememberedApproval("1", "web_read", ApprovalScopeKind.SITE, "https://x.io", expiresAt = 12 * day + 5)
        assertEquals("Always · 12 days left", rememberedSubtitle(res, always, now = 0))
        assertEquals("Always · 1 day left", rememberedSubtitle(res, always, now = 11 * day))
        assertEquals("Always · ends today", rememberedSubtitle(res, always, now = 12 * day))
        assertEquals("This chat only · in its project", rememberedSubtitle(res, always.copy(conversationId = "c", projectId = "p", expiresAt = null), now = 0))
    }
}
