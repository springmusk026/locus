package dev.locus.feature.chat

import dev.locus.core.data.agent.WaitingChat
import dev.locus.core.data.agent.WaitingKind
import dev.locus.core.model.AppLockSettings
import dev.locus.core.model.AppLockStatus
import dev.locus.core.model.LockSession
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The "questions waiting" banner: who is counted, who is named, and which chat a tap opens. */
class WaitingBannerTest {
    private val chats = listOf(
        WaitingChat("trip", WaitingKind.QUESTION, since = 100),
        WaitingChat("secret", WaitingKind.APPROVAL, since = 200),
        WaitingChat("new", WaitingKind.BUDGET, since = 300),
    )
    private val titles = mapOf("trip" to "Trip plan", "secret" to "Salary talk", "new" to "")

    private fun lock(locked: Set<String> = emptySet(), unlocked: Boolean = false, enabled: Boolean = false) =
        AppLockStatus(AppLockSettings(enabled = enabled, lockedChats = locked), available = true, session = LockSession(unlocked = unlocked))

    @Test
    fun `titles show for unlocked chats and blank stays blank`() {
        val entries = waitingEntries(chats, titles, lock())
        assertEquals(listOf("Trip plan", "Salary talk", ""), entries.map { it.title })
        assertEquals(listOf("trip", "secret", "new"), entries.map { it.chatId })
        assertEquals(listOf(WaitingKind.QUESTION, WaitingKind.APPROVAL, WaitingKind.BUDGET), entries.map { it.kind })
    }

    @Test
    fun `a locked chat is counted but never named, even once the session is unlocked`() {
        for (status in listOf(lock(setOf("secret")), lock(setOf("secret"), unlocked = true), lock(setOf("secret"), unlocked = true, enabled = true))) {
            val entries = waitingEntries(chats, titles, status)
            assertNull(entries.single { it.chatId == "secret" }.title)
            assertEquals("Trip plan", entries.single { it.chatId == "trip" }.title)
            assertEquals(3, waitingBanner(entries, exclude = emptySet())!!.count)
        }
    }

    @Test
    fun `until the lock's settings are read, no chat is named`() {
        assertEquals(listOf(null, null, null), waitingEntries(chats, titles, null).map { it.title })
    }

    @Test
    fun `the banner leaves out the chat on screen and opens the one waiting longest`() {
        val entries = waitingEntries(chats, titles, lock())
        val all = waitingBanner(entries, exclude = emptySet())!!
        assertEquals(3, all.count)
        assertEquals("trip", all.oldest.chatId)

        // In "trip" itself: the two others, the oldest of them first.
        val inTrip = waitingBanner(entries, exclude = setOf("trip"))!!
        assertEquals(2, inTrip.count)
        assertEquals("secret", inTrip.oldest.chatId)

        // Home under its featured card says "more" only when it left that chat out; the chat screen never does.
        assertEquals(true, waitingBanner(entries, exclude = setOf("secret"), sayMore = true)!!.more)
        assertEquals(false, waitingBanner(entries, exclude = setOf("elsewhere"), sayMore = true)!!.more)
        assertEquals(false, inTrip.more)

        // Only the chat on screen waits: no banner.
        assertNull(waitingBanner(entries.take(1), exclude = setOf("trip")))
        assertNull(waitingBanner(emptyList(), exclude = emptySet()))
    }
}
