package dev.locus.core.browser

import dev.locus.core.agent.ToolException
import dev.locus.core.agent.ToolResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Who drives a chat's browser: the agent, or the user after **Take over** (docs/design/browser.md,
 * "Take over and hand back"). Pure bookkeeping, keyed like [BrowserSessions] by conversation id, so the
 * pause and resume rules are unit-tested on the JVM.
 *
 * While the user has control, every browser call from that chat's run waits in [await] instead of
 * racing the user's taps, and nothing about the page reaches the model: no snapshot, no read, no logs.
 * That is OpenAI Operator's rule ("does not collect or screenshot information entered by the user"
 * while the user has taken over). A call waits at most [waitMs]; then it fails with a result that tells
 * the model what happened and what to do instead. **Hand back** releases the waiting call and leaves a
 * one-time notice ([takeNotice]) for the next browser result: the page may have changed, refs are gone.
 *
 * [onChange] runs after every change of a key's state (taken, waiting, handed back), for the UI.
 */
class BrowserControl(
    private val clock: () -> Long = System::currentTimeMillis,
    val waitMs: Long = WAIT_MS,
    private val onChange: (String) -> Unit = {},
) {
    private class Hold(val since: Long) {
        val released = CompletableDeferred<Unit>()
        var waiting = 0
    }

    private val holds = HashMap<String, Hold>()
    private val notices = HashMap<String, String>()

    /** The user takes [key]'s browser. False when they already have it. */
    fun takeOver(key: String): Boolean {
        synchronized(this) {
            if (key in holds) return false
            holds[key] = Hold(clock())
            notices.remove(key)
        }
        onChange(key)
        return true
    }

    /**
     * The user hands [key]'s browser back; [noted] says whether they left a note, which reaches the
     * agent as their own message (not here: a tool result is untrusted page territory). Returns how long
     * they had it, or null when they didn't.
     */
    fun handBack(key: String, noted: Boolean): Long? {
        val held = synchronized(this) {
            val hold = holds.remove(key) ?: return null
            val ms = (clock() - hold.since).coerceAtLeast(0)
            notices[key] = handedBack(ms, noted)
            hold.released.complete(Unit)
            ms
        }
        onChange(key)
        return held
    }

    @Synchronized fun inControl(key: String): Boolean = key in holds

    /** A call from [key]'s run is waiting for the user to hand back. */
    @Synchronized fun waiting(key: String): Boolean = (holds[key]?.waiting ?: 0) > 0

    /**
     * Returns at once when the agent has [key]'s browser. Otherwise waits until the user hands it back
     * (true), or throws after [waitMs] with what the model should do instead.
     */
    suspend fun await(key: String): Boolean {
        val hold = synchronized(this) { holds[key]?.also { it.waiting++ } } ?: return false
        onChange(key)
        try {
            withTimeoutOrNull(waitMs) { hold.released.await() } ?: throw ToolException(timedOut(waitMs))
            return true
        } finally {
            synchronized(this) { hold.waiting-- }
            onChange(key)
        }
    }

    /** The note about the last hand-back for [key]'s next browser result, once. */
    @Synchronized fun takeNotice(key: String): String? = notices.remove(key)

    companion object {
        /** How long a browser call waits for the user to hand back. Long enough for a login with a code. */
        const val WAIT_MS = 5 * 60_000L

        fun duration(ms: Long): String {
            val s = ms / 1000
            return when {
                s < 60 -> "${s.coerceAtLeast(1)} s"
                s < 3600 -> "${s / 60} min"
                else -> "${s / 3600} h ${s % 3600 / 60} min"
            }
        }

        fun handedBack(ms: Long, noted: Boolean): String =
            "The user took control of the browser for ${duration(ms)} and handed it back. The page may have changed: " +
                "refs from earlier snapshots no longer apply, so take a new snapshot before using any. " +
                "Fields the user typed into show value=<typed by the user, hidden>." +
                if (noted) " They left a note for you; it follows as their next message." else ""

        fun timedOut(ms: Long): String =
            "The user took control of the browser and hasn't handed it back after ${duration(ms)}, so this browser call " +
                "was not made and nothing on the page was read. Don't retry in a loop: do what you can without the " +
                "browser, or tell the user what you need from the page and wait for their reply."
    }
}

/**
 * The takeover rule around one browser tool call: wait while the user has the browser ([BrowserControl.await]),
 * then run the call ([afterWait] instead when it did wait and the call has a better answer then, e.g.
 * browser_ask_user, whose hand-over just happened), and put the hand-back notice, if any, in front of the
 * result or the error. BrowserTools and the tests share it.
 */
suspend fun takeoverGate(
    control: BrowserControl,
    key: String,
    afterWait: (suspend () -> ToolResult)? = null,
    run: suspend () -> ToolResult,
): ToolResult {
    val waited = control.await(key)
    val result = try {
        if (waited && afterWait != null) afterWait() else run()
    } catch (e: ToolException) {
        val notice = control.takeNotice(key) ?: throw e
        throw ToolException(notice + "\n\n" + e.message)
    }
    val notice = control.takeNotice(key) ?: return result
    return result.copy(content = notice + "\n\n" + result.content)
}
