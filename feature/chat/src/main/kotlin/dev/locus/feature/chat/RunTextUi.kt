package dev.locus.feature.chat

import android.content.res.Resources
import dev.locus.core.agent.BudgetLimit
import dev.locus.core.agent.BudgetMeter
import dev.locus.core.agent.BudgetReached
import dev.locus.core.agent.ContextOverflowText
import dev.locus.core.agent.HelperSpend
import dev.locus.core.agent.LikelyCause
import dev.locus.core.agent.RunBudget
import dev.locus.core.agent.RunFailure
import dev.locus.core.agent.TaskReceipts
import dev.locus.core.data.agent.RunNote
import dev.locus.core.data.agent.RunState
import dev.locus.core.llm.ChatGptErrors
import dev.locus.core.llm.LlmErrorText
import dev.locus.core.model.ModelNames
import dev.locus.core.model.ReceiptModel
import dev.locus.core.model.TaskOutcome
import dev.locus.core.model.TaskReceipt
import dev.locus.core.model.Verification
import dev.locus.core.ondevice.OnDeviceFormat
import dev.locus.core.ondevice.TurnSpeed
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/*
 * What the chat shows while the agent works and when it stops, in the user's language
 * (docs/design/localization.md). core/llm, core/agent and core/data keep writing the English (the run's
 * strings, exports, logs and Diagnostics); they also hand over the same text as data (LlmErrorText,
 * ContextOverflowText, RunNote, BudgetReached, TaskReceipt), which these word from this module's
 * strings. The English strings are the core's words exactly (RunTextUiTest).
 */

// ---- Provider errors and run failures --------------------------------------------------------------

/**
 * A provider's error in the user's language. [withIds] keeps the request id that an unexplained failure
 * names; the banner leaves it to Details.
 */
internal fun llmErrorInWords(resources: Resources, text: LlmErrorText, withIds: Boolean = true): String {
    val v = text.values
    fun s(id: Int, vararg args: Any) = resources.getString(id, *args)
    return when (text.kind) {
        LlmErrorText.Kind.PROVIDER_WORDS -> s(R.string.chat_error_provider_words, v[0], v[1], v[2])
        LlmErrorText.Kind.BAD_REQUEST -> s(R.string.chat_error_bad_request, v[0])
        LlmErrorText.Kind.KEY_REFUSED -> s(R.string.chat_error_key_refused, v[0])
        LlmErrorText.Kind.FORBIDDEN -> s(R.string.chat_error_forbidden, v[0])
        LlmErrorText.Kind.NOT_FOUND -> s(R.string.chat_error_not_found, v[0])
        LlmErrorText.Kind.TIMED_OUT -> s(R.string.chat_error_timed_out, v[0])
        LlmErrorText.Kind.TOO_LARGE -> s(R.string.chat_error_too_large, v[0])
        LlmErrorText.Kind.RATE_LIMITED -> s(R.string.chat_error_rate_limited, v[0])
        LlmErrorText.Kind.INTERNAL_ERROR -> s(R.string.chat_error_internal, v[0])
        LlmErrorText.Kind.BAD_GATEWAY -> s(R.string.chat_error_bad_gateway, v[0])
        LlmErrorText.Kind.UNAVAILABLE -> s(R.string.chat_error_unavailable, v[0])
        LlmErrorText.Kind.GATEWAY_TIMEOUT -> s(R.string.chat_error_gateway_timeout, v[0])
        LlmErrorText.Kind.GATEWAY_UNREACHABLE -> s(R.string.chat_error_gateway_unreachable, v[0], v[1])
        LlmErrorText.Kind.GATEWAY_CONNECT_TIMEOUT -> s(R.string.chat_error_gateway_connect_timeout, v[0])
        LlmErrorText.Kind.GATEWAY_MODEL_TIMEOUT -> s(R.string.chat_error_gateway_model_timeout, v[0])
        LlmErrorText.Kind.OVERLOADED -> s(R.string.chat_error_overloaded, v[0])
        LlmErrorText.Kind.SERVER_ERROR -> s(R.string.chat_error_server_error, v[0], v[1])
        LlmErrorText.Kind.STATUS_TITLE -> s(R.string.chat_error_status_title, v[0], v[1], v[2])
        LlmErrorText.Kind.STATUS -> s(R.string.chat_error_status, v[0], v[1])
        LlmErrorText.Kind.BILLING -> s(R.string.chat_error_billing, v[0], v[1])
        LlmErrorText.Kind.BILLING_NO_DETAIL -> s(R.string.chat_error_billing, v[0], s(R.string.chat_error_out_of_credit))
        LlmErrorText.Kind.DAILY_QUOTA -> s(R.string.chat_error_daily_quota, v[0], v[1])
        LlmErrorText.Kind.DAILY_QUOTA_NO_DETAIL -> s(R.string.chat_error_daily_quota, v[0], s(R.string.chat_error_daily_quota_reached))
        LlmErrorText.Kind.CHATGPT_LIMIT -> {
            val limit = s(R.string.chat_error_chatgpt_limit, ChatGptErrors.USAGE_URL)
            resetTime(resources, text.resetsAt)?.let { limit + " " + s(R.string.chat_error_chatgpt_resets, it) } ?: limit
        }
        LlmErrorText.Kind.CHATGPT_NOT_INCLUDED -> s(R.string.chat_error_chatgpt_not_included)
        LlmErrorText.Kind.CHATGPT_OUT_OF_CREDIT -> s(R.string.chat_error_chatgpt_out_of_credit)
        LlmErrorText.Kind.CHATGPT_USAGE_UNAVAILABLE -> s(R.string.chat_error_chatgpt_usage_unavailable)
        LlmErrorText.Kind.CHATGPT_NOT_ELIGIBLE -> s(R.string.chat_error_chatgpt_not_eligible)
        LlmErrorText.Kind.CHATGPT_UNSUPPORTED -> s(R.string.chat_error_chatgpt_unsupported)
        LlmErrorText.Kind.CHATGPT_UNSUPPORTED_PARAM -> s(R.string.chat_error_chatgpt_unsupported_param, v[0])
        LlmErrorText.Kind.CHATGPT_UNSUPPORTED_MESSAGE -> s(R.string.chat_error_chatgpt_unsupported_message, v[0])
        LlmErrorText.Kind.CHATGPT_UNSUPPORTED_PARAM_MESSAGE -> s(R.string.chat_error_chatgpt_unsupported_param_message, v[0], v[1])
        LlmErrorText.Kind.CHATGPT_SIGN_IN_REFUSED -> s(R.string.chat_error_chatgpt_sign_in_refused)
        LlmErrorText.Kind.CHATGPT_SIGN_IN_REFUSED_DETAIL -> s(R.string.chat_error_chatgpt_sign_in_refused_detail, v[0])
        LlmErrorText.Kind.CHATGPT_ROUTE_NOT_SUPPORTED -> s(R.string.chat_error_chatgpt_route_not_supported)
        LlmErrorText.Kind.CHATGPT_OVERLOADED -> s(R.string.chat_error_chatgpt_overloaded)
        LlmErrorText.Kind.CHATGPT_OVERLOADED_MESSAGE -> s(R.string.chat_error_chatgpt_overloaded_message, v[0])
        LlmErrorText.Kind.CHATGPT_SERVER_ERROR -> s(R.string.chat_error_chatgpt_server_error)
        LlmErrorText.Kind.CHATGPT_TOO_MANY_REQUESTS -> s(R.string.chat_error_chatgpt_too_many_requests)
        LlmErrorText.Kind.RESPONSE_FAILED_CODE -> s(R.string.chat_error_response_failed_code, v[0], v[1])
        LlmErrorText.Kind.CHATGPT_REFUSED_DETAIL -> s(R.string.chat_error_chatgpt_refused_detail, v[0])
        LlmErrorText.Kind.CHATGPT_REFUSED -> s(R.string.chat_error_chatgpt_refused)
        LlmErrorText.Kind.CHATGPT_PLAN_UNAVAILABLE_DETAIL -> s(R.string.chat_error_chatgpt_plan_unavailable_detail, v[0])
        LlmErrorText.Kind.CHATGPT_PLAN_UNAVAILABLE -> s(R.string.chat_error_chatgpt_plan_unavailable)
        LlmErrorText.Kind.UNEXPLAINED -> s(R.string.chat_error_unexplained, v[0])
        LlmErrorText.Kind.UNEXPLAINED_WITH_ID ->
            if (withIds) s(R.string.chat_error_unexplained_with_id, v[0], v[1]) else s(R.string.chat_error_unexplained, v[0])
    }
}

/** When a ChatGPT usage limit resets, as core/llm says it (a time today, else a date and time), in the user's language. */
private fun resetTime(resources: Resources, resetsAt: Long?, zone: ZoneId = ZoneId.systemDefault(), now: Long = System.currentTimeMillis()): String? {
    val at = resetsAt?.takeIf { it > 0 }?.let(Instant::ofEpochSecond) ?: return null
    val time = at.atZone(zone)
    val sameDay = time.toLocalDate() == Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return time.format(DateTimeFormatter.ofPattern(if (sameDay) "HH:mm" else "d MMM, HH:mm", resources.configuration.locales[0]))
}

/** The run's message when the chat no longer fit the model, in the user's language. */
internal fun overflowInWords(resources: Resources, o: ContextOverflowText): String {
    val what = resources.getString(if (o.summarised) R.string.chat_error_overflow_after_summary else R.string.chat_error_overflow, o.model)
    return what + " " + resources.getString(R.string.chat_error_overflow_next) + "\n\n" + resources.getString(R.string.chat_error_overflow_provider_said, o.providerSaid)
}

/**
 * The run's error as the banner and Details say it: in the user's language when core gave it as data
 * ([RunFailure.text], [RunFailure.overflow], [RunState.errorNote]) and the error still starts with
 * its English; else as it came. Ids go to the Details table either way ([failureMessageInWords]).
 */
internal fun runErrorInWords(resources: Resources, error: String, failure: RunFailure?, note: RunNote? = null, inWords: Boolean = true): String {
    val worded = failure?.overflow?.let { it.english to overflowInWords(resources, it) }
        ?: failure?.text?.let { it.english to llmErrorInWords(resources, it, withIds = !inWords) }
        ?: note?.let { it.english to runNoteInWords(resources, it) }
    val shown = if (worded != null && error.startsWith(worded.first)) worded.second + error.removePrefix(worded.first) else error
    return if (inWords) failureMessageInWords(resources, shown) else shown
}

/** [RunFailure.advice] in the user's language: what a repeat or a usage limit probably means, and what to try. */
internal fun failureAdviceInWords(resources: Resources, f: RunFailure): String? = when {
    f.cause == LikelyCause.REQUEST -> {
        val times = if (f.occurrences == 2) resources.getString(R.string.chat_failure_advice_twice) else resources.getQuantityString(R.plurals.chat_failure_advice_times, f.occurrences, f.occurrences)
        val first = if (f.contextLarge) {
            val n = f.contextTokens ?: 0
            resources.getString(R.string.chat_failure_advice_repeat_large, times, if (n >= 1_000) "${n / 1_000}k" else "$n")
        } else {
            resources.getString(R.string.chat_failure_advice_repeat, times)
        }
        val next = resources.getString(
            when {
                f.suggestCompact && f.suggestSwitchModel -> R.string.chat_failure_advice_compact_or_switch
                f.suggestSwitchModel -> R.string.chat_failure_advice_switch
                else -> R.string.chat_failure_advice_details
            },
        )
        "$first $next"
    }
    f.cause == LikelyCause.USAGE_LIMIT -> resources.getString(R.string.chat_failure_advice_usage_limit)
    f.repeated && f.cause == LikelyCause.TRANSIENT -> resources.getString(R.string.chat_failure_advice_transient_repeat)
    else -> null
}

// ---- The runner's progress lines and notices --------------------------------------------------------

/** A [RunNote] in the user's language. */
internal fun runNoteInWords(resources: Resources, note: RunNote): String = when (note) {
    RunNote.Summarising -> resources.getString(R.string.chat_run_summarising)
    is RunNote.Compacting -> resources.getString(if (note.overflow) R.string.chat_run_compacting_overflow else R.string.chat_run_compacting)
    is RunNote.Compacted -> resources.getQuantityString(R.plurals.chat_run_compacted, note.messages, note.messages)
    is RunNote.CompactionFailed -> resources.getString(R.string.chat_run_compaction_failed, note.shownReason ?: resources.getString(R.string.chat_run_unknown_error))
    is RunNote.CompactNowFailed -> resources.getString(R.string.chat_run_compact_now_failed, "${note.reason}")
    is RunNote.Retrying -> when {
        note.waitSeconds != null -> resources.getString(
            if (note.dropped) R.string.chat_run_retrying_dropped_wait else R.string.chat_run_retrying_busy_wait,
            note.waitSeconds!!.toInt(),
            note.attempt,
        )
        else -> resources.getString(if (note.dropped) R.string.chat_run_retrying_dropped else R.string.chat_run_retrying_busy, note.attempt)
    }
    is RunNote.FellBack -> {
        val switching = if (note.skipped.isEmpty()) {
            resources.getString(R.string.chat_run_fell_back, note.from, note.to)
        } else {
            resources.getString(R.string.chat_run_fell_back_skipped, note.from, note.to, note.skipped.joinToString("; "))
        }
        note.unconfirmed?.let { switching + " " + resources.getString(R.string.chat_run_not_confirmed, it) } ?: switching
    }
    is RunNote.Refused -> note.detail?.let { resources.getString(R.string.chat_run_refused_detail, it) } ?: resources.getString(R.string.chat_run_refused)
    is RunNote.StepLimit -> resources.getQuantityString(R.plurals.chat_run_step_limit, note.steps, note.steps)
    is RunNote.BudgetStopped -> budgetReachedInWords(resources, note.reached) + " " + resources.getString(R.string.chat_run_budget_go_on)
    RunNote.BranchUnfinished -> resources.getString(R.string.chat_run_branch_unfinished)
    RunNote.ClosedMidTask -> resources.getString(R.string.chat_run_closed_mid_task)
    is RunNote.BackgroundRefused -> resources.getString(if (note.helper) R.string.chat_run_background_helper else R.string.chat_run_background_queued)
    RunNote.ToolGone -> resources.getString(R.string.chat_run_tool_gone)
    RunNote.NoProvider -> resources.getString(R.string.chat_run_no_provider)
    is RunNote.LessonsSaved -> resources.getQuantityString(R.plurals.chat_run_lessons_saved, note.count, note.count)
}

/** [text] in the user's language when [note] is its data, else as it came. */
private fun noteOr(resources: Resources, text: String?, note: RunNote?): String? =
    if (text != null && note != null && note.english == text) runNoteInWords(resources, note) else text

/** The run's notice in the user's language. */
internal fun runNotice(resources: Resources, run: RunState): String? = noteOr(resources, run.notice, run.noticeNote)

/** The run's progress line in the user's language. */
internal fun runProgress(resources: Resources, run: RunState): String = noteOr(resources, run.progress, run.progressNote).orEmpty()

// ---- Task budget ------------------------------------------------------------------------------------

/** "Paused: reached the 200k-token budget for this task." ([BudgetReached.english]) in the user's language. */
internal fun budgetReachedInWords(resources: Resources, r: BudgetReached): String =
    resources.getString(if (r.paused) R.string.chat_budget_paused_reached else R.string.chat_budget_stopped_reached, budgetSize(resources, r))

/** "Reached the 200k-token budget for this task.", for the pause card whose title says it paused. */
internal fun budgetReachedSentence(resources: Resources, r: BudgetReached): String =
    resources.getString(R.string.chat_budget_reached, budgetSize(resources, r))

private fun budgetSize(resources: Resources, r: BudgetReached): String = when (r.limit) {
    BudgetLimit.TOKENS -> resources.getString(R.string.chat_budget_size_tokens, RunBudget.compactTokens(r.tokens))
    BudgetLimit.COST -> resources.getString(R.string.chat_budget_size_cost, RunBudget.usd(r.costUsd))
    BudgetLimit.TIME -> resources.getQuantityString(R.plurals.chat_budget_size_minutes, r.minutes, r.minutes)
}

/** "6m 12s", "45s", "1h 05m" ([dev.locus.core.agent.BudgetText.duration]) in the user's language. */
internal fun durationInWords(resources: Resources, ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> resources.getString(R.string.chat_task_time_seconds, s.toInt())
        s < 3600 -> resources.getString(R.string.chat_task_time_minutes_seconds, (s / 60).toInt(), (s % 60).toInt())
        else -> resources.getString(R.string.chat_task_time_hours_minutes, (s / 3600).toInt(), ((s % 3600) / 60).toInt())
    }
}

/** "30m", "1h 05m": a budget's time. */
private fun minutesInWords(resources: Resources, ms: Long): String =
    if (ms < 3_600_000) {
        resources.getString(R.string.chat_task_time_minutes, (ms / 60_000).toInt())
    } else {
        resources.getString(R.string.chat_task_time_hours_minutes, (ms / 3_600_000).toInt(), ((ms % 3_600_000) / 60_000).toInt())
    }

/** The chip beside the context meter ([dev.locus.core.agent.BudgetText.chip]) in the user's language. */
internal fun budgetChip(resources: Resources, m: BudgetMeter): String? = when {
    m.tokenLimit != null -> "${RunBudget.compactTokens(m.tokens)} / ${RunBudget.compactTokens(m.tokenLimit!!)}"
    m.costLimit != null && m.costUsd != null -> "${RunBudget.usd(m.costUsd!!)} / ${RunBudget.usd(m.costLimit!!)}"
    m.timeLimitMs != null -> "${minutesInWords(resources, m.elapsedMs)} / ${minutesInWords(resources, m.timeLimitMs!!)}"
    else -> null
}

/** The chip read aloud ([dev.locus.core.agent.BudgetText.spokenChip]) in the user's language. */
internal fun budgetSpokenChip(resources: Resources, m: BudgetMeter): String? = when {
    m.tokenLimit != null -> resources.getString(R.string.chat_budget_spoken_tokens, RunBudget.compactTokens(m.tokens), RunBudget.compactTokens(m.tokenLimit!!))
    m.costLimit != null && m.costUsd != null -> resources.getString(R.string.chat_budget_spoken_used, RunBudget.usd(m.costUsd!!), RunBudget.usd(m.costLimit!!))
    m.timeLimitMs != null -> resources.getString(R.string.chat_budget_spoken_used, minutesInWords(resources, m.elapsedMs), minutesInWords(resources, m.timeLimitMs!!))
    else -> null
}

/** The task's limits ([dev.locus.core.agent.BudgetText.limits]) in the user's language; null when it has none. */
internal fun budgetLimits(resources: Resources, m: BudgetMeter): String? = listOfNotNull(
    m.tokenLimit?.let { resources.getString(R.string.chat_budget_tokens_count, RunBudget.compactTokens(it)) },
    m.costLimit?.let { RunBudget.usd(it) },
    m.timeLimitMs?.let { minutesInWords(resources, it) },
).takeIf { it.isNotEmpty() }?.joinToString(" · ")

/** What the task used ([dev.locus.core.agent.BudgetText.used]) in the user's language. */
internal fun budgetUsed(resources: Resources, m: BudgetMeter): String = listOfNotNull(
    resources.getString(R.string.chat_budget_tokens_count, RunBudget.compactTokens(m.tokens)),
    m.costUsd?.let { resources.getString(if (m.costPartial) R.string.chat_budget_about_or_more else R.string.chat_budget_about, RunBudget.usd(it)) },
    durationInWords(resources, m.elapsedMs),
).joinToString(" · ")

// ---- Task receipts ----------------------------------------------------------------------------------

/** "Done", "Stopped by you"… ([TaskReceipts.outcomeLabel]) in the user's language. */
internal fun receiptOutcomeLabel(resources: Resources, r: TaskReceipt): String = resources.getString(
    when (r.outcome) {
        TaskOutcome.DONE -> R.string.chat_receipt_outcome_done
        TaskOutcome.STOPPED -> if (r.detail == null) R.string.chat_receipt_outcome_stopped_by_you else R.string.chat_receipt_outcome_stopped
        TaskOutcome.BUDGET -> R.string.chat_receipt_outcome_budget
        TaskOutcome.FAILED -> R.string.chat_receipt_outcome_failed_label
        TaskOutcome.REFUSED -> R.string.chat_receipt_outcome_refused
        TaskOutcome.STEP_LIMIT -> R.string.chat_receipt_outcome_step_limit
        TaskOutcome.INTERRUPTED -> R.string.chat_receipt_outcome_interrupted
        TaskOutcome.TOOL_LOOP -> R.string.chat_receipt_outcome_stopped
    },
)

/** The outcome with its reason ([TaskReceipts.outcomeLine]); the reason is the run's own (stored) words. */
internal fun receiptOutcomeLine(resources: Resources, r: TaskReceipt): String {
    val detail = r.detail?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.removeSuffix(".")
        ?.removePrefix("Stopped: ")?.removePrefix("Paused: ")
    return if (detail.isNullOrEmpty()) receiptOutcomeLabel(resources, r) else resources.getString(R.string.chat_receipt_outcome_failed, receiptOutcomeLabel(resources, r), detail)
}

/** "≈$0.04", or "cost unknown" ([TaskReceipts.costLabel]); null with no requests. */
internal fun receiptCost(resources: Resources, r: TaskReceipt): String? {
    if (r.tokens.requests == 0) return null
    val c = r.cost ?: return resources.getString(R.string.chat_receipt_cost_unknown_short)
    return HelperSpend.costLabel(c.usd, c.partial)
}

/** "tests passed", "build failed" ([TaskReceipts.checksLabel]); null when nothing was checked. */
internal fun receiptChecks(resources: Resources, r: TaskReceipt): String? {
    if (r.checks.isEmpty()) return null
    val kind = r.checks.map { it.kind }.toSet().singleOrNull()
    val state = when {
        r.checks.any { it.passed == false } -> 0
        r.checks.any { it.passed == null } -> 1
        r.checks.any { it.uncertain } -> 2
        else -> 3
    }
    val ids = when (kind) {
        Verification.Kind.TEST -> intArrayOf(R.string.chat_receipt_tests_failed, R.string.chat_receipt_tests_timed_out, R.string.chat_receipt_tests_finished, R.string.chat_receipt_tests_passed)
        Verification.Kind.BUILD -> intArrayOf(R.string.chat_receipt_build_failed, R.string.chat_receipt_build_timed_out, R.string.chat_receipt_build_finished, R.string.chat_receipt_build_passed)
        else -> intArrayOf(R.string.chat_receipt_checks_failed, R.string.chat_receipt_checks_timed_out, R.string.chat_receipt_checks_finished, R.string.chat_receipt_checks_passed)
    }
    return resources.getString(ids[state])
}

/** "2 passed · 1 failed" ([TaskReceipts.checksDetail]). */
internal fun receiptChecksDetail(resources: Resources, r: TaskReceipt): String = listOfNotNull(
    r.checks.count { it.confirmed }.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.chat_receipt_checks_passed_count, it, it) },
    r.checks.count { it.passed == true && it.uncertain }.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.chat_receipt_checks_unclear_count, it, it) },
    r.checks.count { it.passed == false }.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.chat_receipt_checks_failed_count, it, it) },
    r.checks.count { it.passed == null }.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.chat_receipt_checks_timed_out_count, it, it) },
).joinToString(" · ")

/** Each model once, with its role ([TaskReceipts.modelsLabel]). */
internal fun receiptModels(resources: Resources, r: TaskReceipt): String? = r.models.takeIf { it.isNotEmpty() }?.joinToString(" · ") { m ->
    val name = ModelNames.short(m.name)
    when (m.kind) {
        ReceiptModel.Kind.CHAT -> name
        ReceiptModel.Kind.BACKUP -> resources.getString(R.string.chat_receipt_model_backup, name, "${m.standingInFor}")
        ReceiptModel.Kind.HELPER -> resources.getString(R.string.chat_receipt_model_helper, name)
        ReceiptModel.Kind.REVIEWER -> resources.getString(R.string.chat_receipt_model_reviewer, name)
        ReceiptModel.Kind.SUMMARY -> resources.getString(R.string.chat_receipt_model_summary, name)
    }
}

/** "120k in · 8.2k out · 64% cached" ([TaskReceipts.tokensLabel]). */
internal fun receiptTokens(resources: Resources, r: TaskReceipt): String = listOfNotNull(
    resources.getString(R.string.chat_reply_meta_in, RunBudget.compactTokens(r.tokens.input)),
    resources.getString(R.string.chat_reply_meta_out, RunBudget.compactTokens(r.tokens.output)),
    r.tokens.cachedPercent?.let { resources.getString(R.string.chat_reply_meta_cached, it) },
).joinToString(" · ")

/** Each helper with its cost and model ([TaskReceipts.helpersLabel]). */
internal fun receiptHelpers(resources: Resources, r: TaskReceipt, separator: String = "; "): String? {
    if (r.helperRuns.isEmpty()) return r.helpers.takeIf { it.isNotEmpty() }?.joinToString(", ")
    return r.helperRuns.joinToString(separator) { h ->
        val cost = h.cost?.let { HelperSpend.costLabel(it.usd, it.partial) }
        val model = h.model?.let { ModelNames.short(it) }
        when {
            cost != null && model != null -> resources.getString(R.string.chat_receipt_helper_cost_model, h.label, cost, model)
            cost != null -> resources.getString(R.string.chat_receipt_helper_cost, h.label, cost)
            model != null -> resources.getString(R.string.chat_receipt_helper_model, h.label, model)
            else -> h.label
        }
    }
}

/** "Locus asked it to finish 2 open items" ([TaskReceipts.completionCheckLabel]). */
internal fun receiptCompletionCheck(resources: Resources, r: TaskReceipt): String? =
    r.completionCheck?.let { n -> resources.getQuantityString(R.plurals.chat_receipt_completion_items, n, n) }

/** The outside content a task read, each kind in the user's language. */
internal fun receiptOutside(resources: Resources, r: TaskReceipt): String = r.outsideContent.joinToString(", ") { label ->
    when (TaskReceipts.OutsideSource.ofLabel(label)) {
        TaskReceipts.OutsideSource.WEB_SEARCH -> resources.getString(R.string.chat_receipt_source_web_search)
        TaskReceipts.OutsideSource.WEB_PAGES -> resources.getString(R.string.chat_receipt_source_web_pages)
        TaskReceipts.OutsideSource.WIKIPEDIA -> resources.getString(R.string.chat_receipt_source_wikipedia)
        TaskReceipts.OutsideSource.CONNECTORS -> resources.getString(R.string.chat_receipt_source_connectors)
        TaskReceipts.OutsideSource.EMAIL -> resources.getString(R.string.chat_receipt_source_email)
        TaskReceipts.OutsideSource.GITHUB -> resources.getString(R.string.chat_receipt_source_github)
        TaskReceipts.OutsideSource.COMMAND_OUTPUT -> resources.getString(R.string.chat_receipt_source_command_output)
        TaskReceipts.OutsideSource.REMOTE_MACHINES -> resources.getString(R.string.chat_receipt_source_remote_machines)
        TaskReceipts.OutsideSource.HELPER_REPORTS -> resources.getString(R.string.chat_receipt_source_helper_reports)
        null -> label
    }
}

/**
 * The receipt in one line ([TaskReceipts.summary]) in the user's language:
 * "Done · 4m 12s · 3 files · tests passed · 2 approvals · ≈$0.04".
 */
internal fun receiptSummary(resources: Resources, r: TaskReceipt): String = listOfNotNull(
    receiptOutcomeLabel(resources, r),
    durationInWords(resources, r.durationMs),
    r.files.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.chat_receipt_summary_files, it, it) },
    receiptChecks(resources, r),
    r.workflow?.takeIf { it.checks.isNotEmpty() }?.let { w -> resources.getQuantityString(R.plurals.chat_receipt_summary_workflow_checks, w.checks.size, w.passed, w.checks.size) },
    r.approvals.asked.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.chat_receipt_summary_approvals, it, it) },
    r.approvals.blocked.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.chat_receipt_summary_blocked, it, it) },
    receiptCost(resources, r),
).joinToString(" · ")

// ---- On-device speed --------------------------------------------------------------------------------

/** Under an on-device reply ([OnDeviceFormat.turnLine]): "GPU · ≈18 tok/s · prefill ≈240 tok/s". */
internal fun speedLine(resources: Resources, speed: TurnSpeed): String? {
    val decode = speed.decodePerSecond ?: return null
    val estimate = !speed.measured
    return buildString {
        append(speed.backend.name).append(" · ").append(OnDeviceFormat.rate(decode, estimate))
        speed.prefillPerSecond?.let { append(" · ").append(resources.getString(R.string.chat_reply_meta_prefill, OnDeviceFormat.rate(it, estimate))) }
    }
}
