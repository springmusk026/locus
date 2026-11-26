package dev.locus.core.data.agent

import dev.locus.core.agent.BudgetReached

/**
 * A chat's progress line, notice or error from the runner, as data (docs/design/localization.md).
 * [english] is the [RunState] string it goes with ([RunState.progress], [RunState.notice] or
 * [RunState.error]); the chat words the same in the user's language (feature/chat), and shows the
 * string as it is when the two no longer match.
 */
sealed interface RunNote {
    val english: String

    /** "Summarising earlier messages", while the user's own summary runs. */
    data object Summarising : RunNote {
        override val english get() = "Summarising earlier messages"
    }

    /** "Summarising earlier messages…" before a step; [overflow] when the chat no longer fit. */
    data class Compacting(val overflow: Boolean) : RunNote {
        override val english get() = if (overflow) "Context full, summarising earlier messages…" else "Summarising earlier messages…"
    }

    /** "Summarised 12 earlier messages to free up context." */
    data class Compacted(val messages: Int) : RunNote {
        override val english get() = "Summarised $messages earlier messages to free up context."
    }

    /** The summary failed, and why ([reason] as the error said it). */
    data class CompactionFailed(val reason: String?) : RunNote {
        override val english get() = compactionFailedNotice(reason)

        /** The reason's first line, as the notice shows it. */
        val shownReason: String? get() = reason?.lineSequence()?.firstOrNull()?.take(200)?.ifBlank { null }
    }

    /** The user's own summary failed: "Couldn't summarise the chat: …". */
    data class CompactNowFailed(val reason: String?) : RunNote {
        override val english get() = "Couldn't summarise the chat: $reason"
    }

    /** "Provider busy, retrying in 30 s (2)…"; [dropped] for a dropped connection. */
    data class Retrying(val dropped: Boolean, val waitSeconds: Long?, val attempt: Int) : RunNote {
        override val english get() = "${if (dropped) "Connection dropped" else "Provider busy"}, retrying${waitSeconds?.let { " in $it s" }.orEmpty()} ($attempt)…"
    }

    /** The step moved to a backup model; [skipped] and [unconfirmed] are core/agent's own words. */
    data class FellBack(val from: String, val to: String, val skipped: List<String>, val unconfirmed: String?) : RunNote {
        override val english get() = buildString {
            append("$from couldn't answer, switching to $to")
            if (skipped.isNotEmpty()) append(" (skipped ${skipped.joinToString("; ")})")
            append("…")
            unconfirmed?.let { append(" Not confirmed: $it.") }
        }
    }

    /** "The model declined this request (…)." */
    data class Refused(val detail: String?) : RunNote {
        override val english get() = "The model declined this request${detail?.let { " ($it)" }.orEmpty()}."
    }

    /** "Stopped after 40 steps. Send a message to continue." */
    data class StepLimit(val steps: Int) : RunNote {
        override val english get() = "Stopped after $steps steps. Send a message to continue."
    }

    /** "Stopped: reached the 200k-token budget for this task. Send a message to go on." */
    data class BudgetStopped(val reached: BudgetReached) : RunNote {
        override val english get() = "${reached.english} Send a message to go on."
    }

    /** "This branch stopped before the agent finished. Continue where it left off?" */
    data object BranchUnfinished : RunNote {
        override val english get() = "This branch stopped before the agent finished. Continue where it left off?"
    }

    /** "This task stopped when Locus was closed. Continue where it left off?" */
    data object ClosedMidTask : RunNote {
        override val english get() = "This task stopped when Locus was closed. Continue where it left off?"
    }

    /** Android didn't let a turn start in the background: after a helper finished, else for a queued message. */
    data class BackgroundRefused(val helper: Boolean) : RunNote {
        override val english get() = (if (helper) BackgroundStart.HELPER else BackgroundStart.QUEUED).notice
    }

    /** An edit or a retry asked for while the chat was busy: nothing was sent (#339). */
    data object BusyNotStarted : RunNote {
        override val english get() = "Locus is still working in this chat, so that wasn't sent. Try again when it's done."
    }

    /** "Couldn't run it: that tool is no longer available." */
    data object ToolGone : RunNote {
        override val english get() = "Couldn't run it: that tool is no longer available."
    }

    /** "Add a model provider in Settings → Models first." */
    data object NoProvider : RunNote {
        override val english get() = "Add a model provider in Settings → Models first."
    }

    /** "Summarising from the chosen message", while Summarise from here writes its draft (docs/design/rewind.md). */
    data object SummarisingFrom : RunNote {
        override val english get() = "Summarising from the chosen message"
    }

    /** Summarise from here failed: "Couldn't write the summary: …". */
    data class SummariseFailed(val reason: String?) : RunNote {
        override val english get() = "Couldn't write the summary: ${shownReason ?: "unknown error"}"

        /** The reason's first line, as the error shows it. */
        val shownReason: String? get() = reason?.lineSequence()?.firstOrNull()?.take(200)?.ifBlank { null }
    }

    /** A reviewed summary wasn't used: the chat moved on after it was written. */
    data object SummaryStale : RunNote {
        override val english get() = "The chat changed after the summary was written, so it wasn't used. Summarise again."
    }

    /** "Summary saved. The model reads it from its next reply." (a reviewed or edited summary). */
    data object SummarySaved : RunNote {
        override val english get() = "Summary saved. The model reads it from its next reply."
    }

    /** "Rewound. 3 files put back. 1 file left as it was." ([restored] and [skipped] files). */
    data class Rewound(val restored: Int, val skipped: Int) : RunNote {
        override val english get() = buildString {
            append("Rewound.")
            if (restored > 0) append(" ").append(restored).append(if (restored == 1) " file put back." else " files put back.")
            if (skipped > 0) append(" ").append(skipped).append(if (skipped == 1) " file left as it was." else " files left as they were.")
        }
    }

    /** The user sent a message (or tapped Stop) in a scheduled run's chat: it stops at its next step (docs/design/scheduled-runs.md). */
    data object TakingOver : RunNote {
        override val english get() = "Stopping the scheduled run…"
    }

    /** The scheduled run stopped because the user took over; [message] when their message runs next as a normal turn. */
    data class TookOver(val message: Boolean) : RunNote {
        override val english get() =
            if (message) "You took over: the scheduled run stopped, and your message runs as a normal chat turn."
            else "You took over: the scheduled run stopped here. Send a message to carry on as a normal chat."
    }

    /**
     * Compare models: the second model couldn't answer ([model], its short name and provider; [reason] as the
     * error said it). The chat's own model answers anyway (docs/design/compare-models.md).
     */
    data class CompareFailed(val model: String, val reason: String?) : RunNote {
        override val english get() = "$model couldn't answer the comparison: ${shownReason ?: "unknown error"}"

        /** The reason's first line, as the notice shows it. */
        val shownReason: String? get() = reason?.lineSequence()?.firstOrNull()?.take(200)?.ifBlank { null }
    }

    /** "Saved 2 lessons to memory." */
    data class LessonsSaved(val count: Int) : RunNote {
        override val english get() = if (count == 1) "Saved 1 lesson to memory." else "Saved $count lessons to memory."
    }

    /**
     * Plan mode's "Run with" (docs/design/plan-run.md): the approved plan's task ended on [model] (its short name
     * and provider), and the chat is back on its own model [chat]: the next message, Continue and Try again go there.
     */
    data class PlanRunDone(val model: String, val chat: String) : RunNote {
        override val english get() = "The plan ran on $model. This chat is back on $chat for your next message."
    }

    /**
     * The plan couldn't start on [model]: its provider is gone, it has no key, or it can't call tools ([reason]).
     * Nothing was sent and the plan still waits; pick another model or this chat's own.
     */
    data class PlanRunUnavailable(val model: String, val reason: String?) : RunNote {
        override val english get() = "Couldn't run the plan on $model: ${shownReason ?: "unknown error"}. Pick another model, or run it on this chat's."

        /** The reason's first line, as the error shows it. */
        val shownReason: String? get() = reason?.lineSequence()?.firstOrNull()?.take(200)?.ifBlank { null }
    }
}

/** This state with [note] as its notice (or none). */
fun RunState.withNotice(note: RunNote?): RunState = copy(notice = note?.english, noticeNote = note)

/** This state with [note] as its progress line (or none). */
fun RunState.withProgress(note: RunNote?): RunState = copy(progress = note?.english.orEmpty(), progressNote = note)

/** This state with [note] as its error. */
fun RunState.withError(note: RunNote): RunState = copy(error = note.english, errorNote = note)
