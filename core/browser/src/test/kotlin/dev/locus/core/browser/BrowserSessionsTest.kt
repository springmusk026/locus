package dev.locus.core.browser

import dev.locus.core.browser.BrowserSessions.RefCheck
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrowserSessionsTest {
    private var now = 1_000_000L
    private fun book(maxLiveTabs: Int = 8, idleMs: Long = 60_000L) = BrowserSessions(maxLiveTabs, idleMs) { now }

    @Test
    fun twoChatsCrossNavigatingKeepTheirOwnRefs() {
        val b = book()
        // Chat A opens a shop in its tab 1 and takes a snapshot; its next step is a click (or an upload).
        b.tabOpened("A")
        b.snapshotTaken("A", tabId = 1, doc = "a-shop")
        // Meanwhile chat B, in its own tab 2, opens a page and navigates on.
        b.tabOpened("B")
        b.snapshotTaken("B", tabId = 2, doc = "b-news")
        b.snapshotTaken("B", tabId = 2, doc = "b-article")
        // A's click and upload still target A's tab and A's document.
        assertEquals(RefCheck.Ok("a-shop"), b.checkRefs("A", activeTabId = 1))
        assertEquals(RefCheck.Ok("b-article"), b.checkRefs("B", activeTabId = 2))
        // Refs never cross: A's refs used while A's active tab is some other tab are refused.
        assertEquals(RefCheck.Stale(BrowserSessions.OTHER_TAB), b.checkRefs("A", activeTabId = 2))
    }

    @Test
    fun sharingOneSessionIsWhatBrokeIt() {
        // The old single browser, modelled as one key: B's navigation replaced the scope A's refs came from,
        // so A's click is now checked against B's document and the page refuses it ("take a new snapshot")
        // instead of clicking whatever B's page has at that ref.
        val b = book()
        b.tabOpened("shared")
        b.snapshotTaken("shared", 1, "a-shop")
        b.snapshotTaken("shared", 1, "b-article")
        assertEquals(RefCheck.Ok("b-article"), b.checkRefs("shared", 1))
    }

    @Test
    fun refsNeedASnapshotOfTheActiveTab() {
        val b = book()
        assertEquals(RefCheck.Stale(BrowserSessions.NO_SNAPSHOT), b.checkRefs("A", 1))
        b.tabOpened("A")
        b.snapshotTaken("A", 1, "d1")
        // A pop-up became the active tab: the model hasn't seen it yet.
        b.tabOpened("A")
        assertEquals(RefCheck.Stale(BrowserSessions.OTHER_TAB), b.checkRefs("A", 2))
        assertEquals(RefCheck.Stale(BrowserSessions.NO_SNAPSHOT), b.checkRefs("A", null))
        // An unverified page has no document id: only the tab is checked.
        b.snapshotTaken("A", 2, null)
        assertEquals(RefCheck.Ok(null), b.checkRefs("A", 2))
        // All its tabs closed: nothing left to refer to.
        b.tabClosed("A")
        b.tabClosed("A")
        assertEquals(RefCheck.Stale(BrowserSessions.NO_SNAPSHOT), b.checkRefs("A", 2))
    }

    @Test
    fun aDelayedDownloadIsJudgedByItsOwnChatsPolicy() {
        val b = book()
        // Unknown: refused.
        assertFalse(b.downloadsAllowed("A"))
        // A read-only (scheduled) run drives the page; its call ends, and the page starts a download later.
        b.setReadOnly("A", true)
        b.begin("A")
        b.end("A")
        assertFalse(b.downloadsAllowed("A"))
        // Another chat's full run in between doesn't open A's downloads (the old global flag reset to true).
        b.setReadOnly("B", false)
        assertFalse(b.downloadsAllowed("A"))
        assertTrue(b.downloadsAllowed("B"))
        // A's next run can write: its policy follows.
        b.setReadOnly("A", false)
        assertTrue(b.downloadsAllowed("A"))
        // Closing A's tabs to free memory doesn't forget the policy.
        b.tabOpened("A")
        b.closed("A")
        assertTrue(b.downloadsAllowed("A"))
    }

    @Test
    fun parallelReadOnlyAndFullRuns() {
        val b = book()
        // Interleaved calls from both runs, as two chats' tasks running at once.
        repeat(3) {
            b.setReadOnly("scheduled", true)
            b.begin("scheduled")
            b.setReadOnly("chat", false)
            b.begin("chat")
            assertFalse(b.downloadsAllowed("scheduled"))
            assertTrue(b.downloadsAllowed("chat"))
            b.end("chat")
            b.end("scheduled")
        }
        assertFalse(b.downloadsAllowed("scheduled"))
        assertTrue(b.downloadsAllowed("chat"))
    }

    @Test
    fun theLeastRecentlyUsedIdleChatMakesRoom() {
        val b = book(maxLiveTabs = 3)
        b.tabOpened("old"); b.tabOpened("old")
        now += 10
        b.tabOpened("recent")
        now += 10
        // Full (3 of 3): "new" needs one tab, so the least recently used chat's tabs go.
        assertEquals(listOf("old"), b.evictFor("new", 1, shown = null))
        // Room already: nothing goes.
        b.closed("old")
        assertEquals(emptyList(), b.evictFor("new", 1, shown = null))
    }

    @Test
    fun busyShownAndRequestingChatsAreNeverEvicted() {
        val b = book(maxLiveTabs = 2)
        b.tabOpened("working")
        b.begin("working")
        b.tabOpened("onScreen")
        // Neither can go: the call is refused rather than pull a page from under a running task.
        assertNull(b.evictFor("new", 1, shown = "onScreen"))
        // The requester's own tabs don't count as room.
        assertNull(b.evictFor("working", 1, shown = "onScreen"))
        b.end("working")
        assertEquals(listOf("working"), b.evictFor("new", 1, shown = "onScreen"))
    }

    @Test
    fun idleChatsAreClosedEvenWithRoom() {
        val b = book(maxLiveTabs = 8, idleMs = 60_000L)
        b.tabOpened("stale")
        b.tabOpened("fresh")
        now += 61_000
        b.touch("fresh")
        assertEquals(listOf("stale"), b.idle(shown = null))
        assertEquals(listOf("stale"), b.evictFor("new", 1, shown = null))
        // The one on screen stays, however long it has been.
        assertEquals(emptyList(), b.idle(shown = "stale"))
        assertEquals(2, b.liveTabs())
    }
}
