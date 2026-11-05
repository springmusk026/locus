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

/** A turn's intermediate work: tool calls, narration, reasoning between steps, and build and test runs. */
private fun ChatItem.isStep() =
    this is ChatItem.ToolCall || this is ChatItem.Note || (this is ChatItem.Thought && inSteps) || this is ChatItem.Verification

/**
 * [items] as rows: a turn's consecutive work shares one section (verification runs included, so a
 * build-fix-rerun loop stays one card), and a receipt its reply carries shows no row of its own.
 */
internal fun groupSteps(items: List<ChatItem>): List<TranscriptRow> {
    val rows = mutableListOf<TranscriptRow>()
    val run = mutableListOf<ChatItem>()
    fun flush() {
        if (run.isNotEmpty()) rows += TranscriptRow.Steps(run.toList())
        run.clear()
    }
    items.forEach {
        when {
            it.isStep() -> run += it
            it is ChatItem.Receipt && it.attached -> Unit
            else -> { flush(); rows += TranscriptRow.Single(it) }
        }
    }
    flush()
    return rows
}

/** One build, test or check run in a turn's work: its call, and how it went (null while it runs, or when it never finished). */
internal data class CheckRun(val call: ChatItem.ToolCall, val outcome: dev.locus.core.model.Verification.Outcome?)

/**
 * Every run of one kind of check (tests, a build, a lint or type check) in a turn's work, shown as one
 * row: the latest result, a dot per run, the earlier runs a tap away. Grouped by kind rather than by
 * exact command, so `npm test`, `npm test -- -u` and `npx vitest run` in a fix-and-rerun loop are one
 * row; a chained command counts as the check it ends with (`npm run test:browser && npm run lint` is a check).
 */
internal data class CheckGroup(val kind: dev.locus.core.model.Verification.Kind, val runs: List<CheckRun>) {
    val key: String get() = "checks:" + runs.first().call.key
    val latest: CheckRun get() = runs.last()
}

/** The command a run_command call runs, from its arguments; null for anything else. */
private fun commandOf(call: ChatItem.ToolCall): String? =
    if (call.name != "run_command") null
    else runCatching {
        (kotlinx.serialization.json.Json.parseToJsonElement(call.argumentsJson) as kotlinx.serialization.json.JsonObject)["command"]
            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
    }.getOrNull()

/**
 * A step that belongs to a [CheckGroup]: a finished verification, or a build or test run_command
 * still going (or cut off), so a rerun updates the group's row in place instead of adding a step.
 */
internal fun checkRunOf(step: ChatItem): CheckRun? = when (step) {
    is ChatItem.Verification -> CheckRun(step.call, step.outcome)
    is ChatItem.ToolCall -> if (step.result == null && commandOf(step)?.let(dev.locus.core.model.Verification::detect) != null) CheckRun(step, null) else null
    else -> null
}

/** [steps]' check runs grouped by kind, in the order each kind first ran. */
internal fun checkGroups(steps: List<ChatItem>): List<CheckGroup> {
    val byKind = linkedMapOf<dev.locus.core.model.Verification.Kind, MutableList<CheckRun>>()
    steps.forEach { step ->
        val run = checkRunOf(step) ?: return@forEach
        val kind = run.outcome?.command?.kind ?: commandOf(run.call)?.let(dev.locus.core.model.Verification::detect)?.kind ?: return@forEach
        byKind.getOrPut(kind) { mutableListOf() } += run
    }
    return byKind.map { (kind, runs) -> CheckGroup(kind, runs) }
}

/** How a run went, for its dot and icon: [running] is whether the run with no result is still going. */
internal enum class RunState { PASSED, FAILED, UNCLEAR, TIMED_OUT, RUNNING, STOPPED }

internal fun runState(run: CheckRun, running: Boolean): RunState = when (run.outcome?.verdict) {
    null -> if (running) RunState.RUNNING else RunState.STOPPED
    dev.locus.core.model.Verification.Verdict.PASSED -> RunState.PASSED
    dev.locus.core.model.Verification.Verdict.FAILED -> RunState.FAILED
    dev.locus.core.model.Verification.Verdict.UNCERTAIN -> RunState.UNCLEAR
    dev.locus.core.model.Verification.Verdict.TIMED_OUT -> RunState.TIMED_OUT
}

/**
 * The group's row in words: "Tests passed" for a single run; "Tests · passed on run 5 of 5",
 * "Build · failed on run 2 of 2", "Tests · finished on run 3 of 3" (exit status unclear),
 * "Tests · running (run 4)" when there were several.
 */
internal fun checkGroupLabel(group: CheckGroup, running: Boolean): String {
    val what = when (group.kind) {
        dev.locus.core.model.Verification.Kind.TEST -> "Tests"
        dev.locus.core.model.Verification.Kind.BUILD -> "Build"
        dev.locus.core.model.Verification.Kind.CHECK -> "Check"
    }
    val n = group.runs.size
    val word = when (runState(group.latest, running)) {
        RunState.PASSED -> "passed"
        RunState.FAILED -> "failed"
        RunState.UNCLEAR -> "finished"
        RunState.TIMED_OUT -> "timed out"
        RunState.RUNNING -> return if (n == 1) "$what running" else "$what · running (run $n)"
        RunState.STOPPED -> "stopped"
    }
    return if (n == 1) "$what $word" else "$what · $word on run $n of $n"
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
