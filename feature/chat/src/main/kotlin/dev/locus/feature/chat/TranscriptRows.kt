package dev.locus.feature.chat

/** A transcript row: one item, or a run of a turn's work (tool calls and the narration between them). */
sealed interface TranscriptRow {
    val key: String

    /**
     * What kind of composable the row becomes, for `LazyColumn`'s `contentType`: Compose reuses a
     * scrolled-away row's composition only for a row of the same type.
     */
    val contentType: Any

    data class Single(val item: ChatItem) : TranscriptRow {
        override val key get() = item.key
        override val contentType: Any get() = item::class
    }

    /** Keyed by its first step, so the group keeps its identity (and expanded state) as steps are added. */
    data class Steps(val steps: List<ChatItem>) : TranscriptRow {
        override val key get() = "steps:" + steps.first().key
        override val contentType: Any get() = Steps::class
    }

    /** Whether this row shows the message with [key] (its own item, or one of its steps). */
    fun containsKey(key: String): Boolean = when (this) {
        is Single -> item.key == key
        is Steps -> steps.any { it.key == key }
    }
}

private fun ChatItem.isStep() = this is ChatItem.ToolCall || this is ChatItem.Note || (this is ChatItem.Thought && inSteps)

/** [items] as rows: consecutive steps share one card. */
internal fun groupSteps(items: List<ChatItem>): List<TranscriptRow> {
    val rows = mutableListOf<TranscriptRow>()
    val run = mutableListOf<ChatItem>()
    fun flush() {
        if (run.isNotEmpty()) rows += TranscriptRow.Steps(run.toList())
        run.clear()
    }
    items.forEach { if (it.isStep()) run += it else { flush(); rows += TranscriptRow.Single(it) } }
    flush()
    return rows
}

/**
 * Keeps instances across transcript emissions (docs/design/transcript-reads.md). The transcript is
 * derived again whenever the chat changes, which makes new objects for rows that didn't change. Rows
 * are compared by identity when their type isn't stable to Compose (lists, classes from non-Compose
 * modules), so new objects would recompose every visible row. [keep] hands back the previous instance
 * for each element equal to the one it had under the same key, and the previous list itself when
 * nothing changed, so unchanged rows skip recomposition.
 *
 * Not thread-safe: use it from one coroutine (the transcript flow).
 */
internal class InstanceKeeper<T : Any>(private val key: (T) -> String) {
    private var last: List<T> = emptyList()
    private var byKey: Map<String, T> = emptyMap()

    fun keep(next: List<T>): List<T> {
        var same = next.size == last.size
        val kept = next.mapIndexed { i, item ->
            val old = byKey[key(item)]
            val chosen = if (old != null && (old === item || old == item)) old else item
            if (same && last[i] !== chosen) same = false
            chosen
        }
        if (same) return last
        last = kept
        byKey = kept.associateBy(key)
        return kept
    }
}

/** While a run is going, no plan is waiting on the user. The same list when nothing changes. */
internal fun List<ChatItem>.markWaitingPlan(waiting: Boolean): List<ChatItem> =
    if (waiting || none { it is ChatItem.Plan && it.status == PlanStatus.Waiting }) this
    else map { if (it is ChatItem.Plan && it.status == PlanStatus.Waiting) it.copy(status = PlanStatus.Answered) else it }

/** [markWaitingPlan] for rows (plans are always rows of their own). */
internal fun List<TranscriptRow>.markWaitingPlanRows(waiting: Boolean): List<TranscriptRow> =
    if (waiting || none { it is TranscriptRow.Single && it.item is ChatItem.Plan && it.item.status == PlanStatus.Waiting }) this
    else map { row ->
        val item = (row as? TranscriptRow.Single)?.item
        if (item is ChatItem.Plan && item.status == PlanStatus.Waiting) TranscriptRow.Single(item.copy(status = PlanStatus.Answered)) else row
    }
