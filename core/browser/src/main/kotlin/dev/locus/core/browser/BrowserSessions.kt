package dev.locus.core.browser

/**
 * Who owns which browser tabs, and what each owner may do: the pure bookkeeping behind
 * [AgentBrowser]'s per-conversation sessions (docs/design/browser.md, "One browser per chat").
 *
 * A session is keyed by conversation id. A helper (sub-agent) runs as its own conversation, so it gets
 * its own session: its navigation can't move its parent's page, and two helpers working side by side
 * don't fight over one tab. Cookies and storage are shared by every session (one WebView profile), so a
 * login the user made in one chat's tab still counts in another's.
 *
 * Thread-safe: tool calls read and update it from any dispatcher; the WebViews themselves stay on the
 * main thread in [AgentBrowser].
 */
class BrowserSessions(
    private val maxLiveTabs: Int = MAX_LIVE_TABS,
    private val idleMs: Long = IDLE_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Entry {
        var lastUsed = 0L
        var tabs = 0
        var busy = 0
        var readOnly: Boolean? = null
        var scope: RefScope? = null

        /** The user had the browser since the latest snapshot (Take over), so its refs are void. */
        var takenOver = false
    }

    /** Where the refs the model holds came from: the tab and the document of the latest snapshot. */
    data class RefScope(val tabId: Long, val doc: String?)

    /** Whether refs may be used in the session's active tab; [doc] is what the page must still be. */
    sealed interface RefCheck {
        data class Ok(val doc: String?) : RefCheck
        data class Stale(val message: String) : RefCheck
    }

    private val entries = HashMap<String, Entry>()

    private fun entry(key: String) = entries.getOrPut(key) { Entry().also { it.lastUsed = clock() } }

    @Synchronized fun touch(key: String) {
        entry(key).lastUsed = clock()
    }

    /** A call from [key] started ([begin]) or finished ([end]); a busy session is never evicted. */
    @Synchronized fun begin(key: String) {
        entry(key).apply { busy++; lastUsed = clock() }
    }

    @Synchronized fun end(key: String) {
        entry(key).apply { busy = (busy - 1).coerceAtLeast(0); lastUsed = clock() }
    }

    // ---- Downloads ----------------------------------------------------------------------------------

    /**
     * The owning conversation's run policy, set from each of its tool calls and kept after the call
     * ends: a download a page starts later (a timer, a slow redirect) is judged by the run that drove
     * the page there, not by whichever chat happens to be calling the browser then.
     */
    @Synchronized fun setReadOnly(key: String, readOnly: Boolean) {
        entry(key).readOnly = readOnly
    }

    /** Downloads write into the workspace: only for a session whose latest run could write. Unknown: no. */
    @Synchronized fun downloadsAllowed(key: String): Boolean = entries[key]?.readOnly == false

    // ---- Refs ---------------------------------------------------------------------------------------

    /** The model was just shown a snapshot of document [doc] in tab [tabId]: its refs belong there. */
    @Synchronized fun snapshotTaken(key: String, tabId: Long, doc: String?) {
        entry(key).apply { scope = RefScope(tabId, doc); takenOver = false }
    }

    /**
     * The user took the browser (Take over): whatever the model's refs pointed at may have changed, even
     * within one document (a single-page app), so they are void until the next snapshot.
     */
    @Synchronized fun userTookOver(key: String) {
        entry(key).apply { scope = null; takenOver = true }
    }

    /**
     * Whether refs from the latest snapshot may be used now that [activeTabId] is the session's active
     * tab. The document check happens in the page, in the same script call as the action ([RefCheck.Ok.doc]).
     */
    @Synchronized fun checkRefs(key: String, activeTabId: Long?): RefCheck {
        val e = entries[key]
        val scope = e?.scope ?: return RefCheck.Stale(if (e?.takenOver == true) AFTER_TAKEOVER else NO_SNAPSHOT)
        if (activeTabId == null) return RefCheck.Stale(NO_SNAPSHOT)
        if (scope.tabId != activeTabId) return RefCheck.Stale(OTHER_TAB)
        return RefCheck.Ok(scope.doc)
    }

    // ---- Tabs and memory ----------------------------------------------------------------------------

    @Synchronized fun tabOpened(key: String) {
        entry(key).tabs++
    }

    @Synchronized fun tabClosed(key: String) {
        entry(key).apply {
            tabs = (tabs - 1).coerceAtLeast(0)
            if (tabs == 0) scope = null
        }
    }

    @Synchronized fun tabs(key: String): Int = entries[key]?.tabs ?: 0

    @Synchronized fun liveTabs(): Int = entries.values.sumOf { it.tabs }

    /**
     * Sessions to close so [requester] can open [adding] tabs: first any idle longer than [idleMs], then
     * the least recently used until the live tabs fit [maxLiveTabs]. Never the requester, a busy session
     * (a call running, a handoff waiting) or the one on screen ([shown]). Null when that isn't enough.
     */
    @Synchronized fun evictFor(requester: String, adding: Int, shown: String?): List<String>? {
        val now = clock()
        val candidates = entries.filter { (k, e) -> k != requester && k != shown && e.busy == 0 && e.tabs > 0 }
        val evict = candidates.filter { now - it.value.lastUsed > idleMs }.keys.toMutableList()
        var live = liveTabs() - evict.sumOf { entries.getValue(it).tabs }
        candidates.entries.filter { it.key !in evict }.sortedBy { it.value.lastUsed }.forEach { (k, e) ->
            if (live + adding <= maxLiveTabs) return@forEach
            evict += k
            live -= e.tabs
        }
        return if (live + adding <= maxLiveTabs) evict else null
    }

    /** Idle sessions (not busy, not shown) to close even when nobody needs room. */
    @Synchronized fun idle(shown: String?): List<String> {
        val now = clock()
        return entries.filter { (k, e) -> k != shown && e.busy == 0 && e.tabs > 0 && now - e.lastUsed > idleMs }.keys.toList()
    }

    /** Everything about [key] except its download policy, which belongs to the conversation, not its tabs. */
    @Synchronized fun closed(key: String) {
        entries[key]?.apply { tabs = 0; scope = null }
    }

    companion object {
        /** WebViews alive across all chats; each holds a renderer's worth of memory. */
        const val MAX_LIVE_TABS = 8

        /** A chat's tabs are closed after this long unused, when another chat needs room or opens a tab. */
        const val IDLE_MS = 30 * 60_000L

        const val NO_SNAPSHOT = "There's no snapshot of this tab yet, so its refs are unknown. Take a new snapshot (browser_snapshot) first."
        const val AFTER_TAKEOVER = "The user had the browser since your last snapshot, so its refs may point at changed elements. Take a new snapshot (browser_snapshot) first."
        const val OTHER_TAB = "Those refs are from a snapshot of another tab. Take a new snapshot of this one first."
    }
}
