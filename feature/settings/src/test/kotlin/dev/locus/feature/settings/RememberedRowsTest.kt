package dev.locus.feature.settings

import dev.locus.core.model.ApprovalScopeKind
import dev.locus.core.model.RememberedApproval
import org.junit.Test
import kotlin.test.assertEquals

/** How Settings → Permissions → Remembered names each rule and how long it lasts. */
class RememberedRowsTest {
    private val day = 24L * 60 * 60 * 1000

    @Test
    fun `titles say what the rule allows in words`() {
        assertEquals("Run `./gradlew test …`", rememberedTitle(RememberedApproval("1", "run_command", ApprovalScopeKind.COMMAND, "./gradlew test")))
        assertEquals("Write files in src/main/", rememberedTitle(RememberedApproval("2", "write_file", ApprovalScopeKind.FOLDER, "src/main")))
        assertEquals("Write files in the top folder", rememberedTitle(RememberedApproval("3", "write_file", ApprovalScopeKind.FOLDER, "")))
        assertEquals("Fetch api.github.com (GET)", rememberedTitle(RememberedApproval("4", "fetch_url", ApprovalScopeKind.SITE, "GET https://api.github.com")))
        assertEquals("Open github.com", rememberedTitle(RememberedApproval("5", "browser_open", ApprovalScopeKind.SITE, "github.com")))
    }

    @Test
    fun `subtitles say where and how long`() {
        val always = RememberedApproval("1", "web_read", ApprovalScopeKind.SITE, "https://x.io", expiresAt = 12 * day + 5)
        assertEquals("Always · 12 days left", rememberedSubtitle(always, now = 0))
        assertEquals("Always · 1 day left", rememberedSubtitle(always, now = 11 * day))
        assertEquals("Always · ends today", rememberedSubtitle(always, now = 12 * day))
        assertEquals("This chat only · in its project", rememberedSubtitle(always.copy(conversationId = "c", projectId = "p", expiresAt = null), now = 0))
    }
}
