package dev.locus.feature.activity

import dev.locus.core.model.AppLockSettings
import dev.locus.core.model.AppLockStatus
import dev.locus.core.model.ApprovalDecision
import dev.locus.core.model.AuditEntry
import dev.locus.core.model.LockSession
import org.junit.Test
import kotlin.test.assertEquals

/** Activity leaves out what a hidden locked chat did (docs/design/app-lock.md). */
class LockedActivityTest {
    private fun entry(id: String, chat: String) =
        AuditEntry(id, chat, "call-$id", "run_command", """{"command":"cat diary.txt"}""", ApprovalDecision.AUTO_ALLOWED, "", false, 0, 0)

    private val entries = listOf(entry("1", "diary"), entry("2", "helper-of-diary"), entry("3", "groceries"))
    private val parents = mapOf("helper-of-diary" to "diary")

    @Test
    fun `a locked chat's steps and its helpers' are left out until the session is unlocked`() {
        val locked = AppLockStatus(AppLockSettings(lockedChats = setOf("diary")), available = true)
        assertEquals(listOf("3"), visibleEntries(entries, locked) { parents[it] }.map { it.id })

        val unlocked = locked.copy(session = LockSession(unlocked = true))
        assertEquals(listOf("1", "2", "3"), visibleEntries(entries, unlocked) { parents[it] }.map { it.id })
    }

    @Test
    fun `nothing is left out when no chat is locked`() {
        assertEquals(entries, visibleEntries(entries, AppLockStatus(available = true)) { parents[it] })
    }
}
