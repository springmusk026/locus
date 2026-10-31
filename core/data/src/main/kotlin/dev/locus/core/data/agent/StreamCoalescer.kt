package dev.locus.core.data.agent

import dev.locus.core.agent.appendReasoning

/**
 * Collects streamed text and reasoning deltas and hands out a snapshot at most every [intervalMs],
 * so a fast token stream becomes ~15 UI state updates a second instead of one per token. Appends go
 * to StringBuilders, so a long reply costs linear, not quadratic, copying. Thread-safe: the agent's
 * event collector and a flush ticker may call it concurrently.
 */
internal class StreamCoalescer(
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val reasoningLimit: Int = REASONING_LIMIT,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    /** Fields to publish; null means unchanged since the last snapshot. */
    data class Snapshot(val text: String?, val reasoning: String?)

    private val text = StringBuilder()
    private val reasoning = StringBuilder()
    private var textDirty = false
    private var reasoningDirty = false
    private var lastPublish: Long? = null

    @Synchronized
    fun text(delta: String): Snapshot? {
        text.append(delta)
        textDirty = true
        return due()
    }

    @Synchronized
    fun reasoning(delta: String): Snapshot? {
        reasoning.appendReasoning(delta)
        // Only the tail is ever shown; trim in batches so the buffer stays small without copying per token.
        if (reasoning.length > reasoningLimit * 2) reasoning.delete(0, reasoning.length - reasoningLimit)
        reasoningDirty = true
        return due()
    }

    /** Whatever hasn't been published yet, or null. */
    @Synchronized
    fun flush(): Snapshot? = if (textDirty || reasoningDirty) snapshot() else null

    /** Drops the streamed text (the message was stored, or the step restarted). */
    @Synchronized
    fun clearText() {
        text.setLength(0)
        textDirty = false
    }

    /** Starts over with [reasoningSeed] as the shown progress line, which new reasoning is appended to. */
    @Synchronized
    fun reset(reasoningSeed: String = "") {
        clearText()
        reasoning.setLength(0)
        reasoning.append(reasoningSeed)
        reasoningDirty = false
    }

    private fun due(): Snapshot? {
        val last = lastPublish
        return if (last == null || now() - last >= intervalMs) snapshot() else null
    }

    private fun snapshot(): Snapshot {
        val out = Snapshot(
            text = if (textDirty) text.toString() else null,
            reasoning = if (reasoningDirty) reasoning.substring(maxOf(0, reasoning.length - reasoningLimit)) else null,
        )
        textDirty = false
        reasoningDirty = false
        lastPublish = now()
        return out
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 66L
        /** The live thinking block shows the tail; this much is plenty and keeps each snapshot cheap. */
        const val REASONING_LIMIT = 6_000
    }
}
