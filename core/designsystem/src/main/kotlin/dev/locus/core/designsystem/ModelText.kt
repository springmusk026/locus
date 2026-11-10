package dev.locus.core.designsystem

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalResources
import dev.locus.core.model.ChatMode
import dev.locus.core.model.Effort
import dev.locus.core.model.HelperPrompt
import dev.locus.core.model.HelperTimeLimit
import dev.locus.core.model.MemoryScope
import dev.locus.core.model.PauseReason
import dev.locus.core.model.ScheduleInterval
import dev.locus.core.model.ScheduleIntervals
import dev.locus.core.model.SettingsDestination
import dev.locus.core.model.SubagentStatus
import dev.locus.core.model.ThermalLevel
import dev.locus.core.model.ToolGroup
import dev.locus.core.model.TriggerDecision
import dev.locus.core.model.Verification

/*
 * The words the screens show for core/model's labels, in the app's language (docs/design/localization.md).
 * core/model keeps its English `label`s: the model, exports, logs and stored values read those, and
 * must read the same bytes whatever language the user picked. Screens read these instead. The English
 * resources say exactly what the core labels say (ModelTextTest checks every value).
 */

@get:StringRes
val ChatMode.labelRes: Int
    get() = when (this) {
        ChatMode.READ_ONLY -> R.string.designsystem_chat_mode_read_only
        ChatMode.WORKSPACE -> R.string.designsystem_chat_mode_workspace
        ChatMode.FULL -> R.string.designsystem_chat_mode_full
    }

@get:StringRes
val ChatMode.descriptionRes: Int
    get() = when (this) {
        ChatMode.READ_ONLY -> R.string.designsystem_chat_mode_read_only_description
        ChatMode.WORKSPACE -> R.string.designsystem_chat_mode_workspace_description
        ChatMode.FULL -> R.string.designsystem_chat_mode_full_description
    }

@get:StringRes
val Effort.labelRes: Int
    get() = when (this) {
        Effort.OFF -> R.string.designsystem_effort_off
        Effort.LOW -> R.string.designsystem_effort_low
        Effort.MEDIUM -> R.string.designsystem_effort_medium
        Effort.HIGH -> R.string.designsystem_effort_high
        Effort.MAX -> R.string.designsystem_effort_max
    }

@get:StringRes
val Effort.descriptionRes: Int
    get() = when (this) {
        Effort.OFF -> R.string.designsystem_effort_off_description
        Effort.LOW -> R.string.designsystem_effort_low_description
        Effort.MEDIUM -> R.string.designsystem_effort_medium_description
        Effort.HIGH -> R.string.designsystem_effort_high_description
        Effort.MAX -> R.string.designsystem_effort_max_description
    }

@get:StringRes
val ToolGroup.labelRes: Int
    get() = when (this) {
        ToolGroup.WEB -> R.string.designsystem_tool_group_web
        ToolGroup.BROWSER -> R.string.designsystem_tool_group_browser
        ToolGroup.LINUX -> R.string.designsystem_tool_group_linux
        ToolGroup.SSH -> R.string.designsystem_tool_group_ssh
        ToolGroup.PHONE -> R.string.designsystem_tool_group_phone
        ToolGroup.EMAIL -> R.string.designsystem_tool_group_email
        ToolGroup.GITHUB -> R.string.designsystem_tool_group_github
        ToolGroup.MEMORY -> R.string.designsystem_tool_group_memory
    }

@get:StringRes
val ToolGroup.descriptionRes: Int
    get() = when (this) {
        ToolGroup.WEB -> R.string.designsystem_tool_group_web_description
        ToolGroup.BROWSER -> R.string.designsystem_tool_group_browser_description
        ToolGroup.LINUX -> R.string.designsystem_tool_group_linux_description
        ToolGroup.SSH -> R.string.designsystem_tool_group_ssh_description
        ToolGroup.PHONE -> R.string.designsystem_tool_group_phone_description
        ToolGroup.EMAIL -> R.string.designsystem_tool_group_email_description
        ToolGroup.GITHUB -> R.string.designsystem_tool_group_github_description
        ToolGroup.MEMORY -> R.string.designsystem_tool_group_memory_description
    }

@get:StringRes
val MemoryScope.labelRes: Int
    get() = when (this) {
        MemoryScope.PROJECT_AND_GLOBAL -> R.string.designsystem_memory_scope_project_and_general
        MemoryScope.PROJECT_ONLY -> R.string.designsystem_memory_scope_project_only
    }

/** The chip for work on hold: "Paused: phone is hot". */
@get:StringRes
val PauseReason.chipRes: Int
    get() = when (this) {
        PauseReason.HOT -> R.string.designsystem_pause_hot
        PauseReason.POWER_SAVE -> R.string.designsystem_pause_battery_saver
    }

@get:StringRes
val ThermalLevel.labelRes: Int
    get() = when (this) {
        ThermalLevel.NONE -> R.string.designsystem_thermal_normal
        ThermalLevel.LIGHT -> R.string.designsystem_thermal_light
        ThermalLevel.MODERATE -> R.string.designsystem_thermal_moderate
        ThermalLevel.SEVERE -> R.string.designsystem_thermal_severe
        ThermalLevel.CRITICAL -> R.string.designsystem_thermal_critical
        ThermalLevel.EMERGENCY, ThermalLevel.SHUTDOWN -> R.string.designsystem_thermal_overheating
    }

@get:StringRes
val TriggerDecision.labelRes: Int
    get() = when (this) {
        TriggerDecision.FIRED -> R.string.designsystem_trigger_decision_fired
        TriggerDecision.TEST -> R.string.designsystem_trigger_decision_test
        TriggerDecision.NO_MATCH -> R.string.designsystem_trigger_decision_no_match
        TriggerDecision.RATE_LIMITED -> R.string.designsystem_trigger_decision_rate_limited
        TriggerDecision.QUIET_HOURS -> R.string.designsystem_trigger_decision_quiet_hours
        TriggerDecision.DISABLED -> R.string.designsystem_trigger_decision_disabled
        TriggerDecision.BUSY -> R.string.designsystem_trigger_decision_busy
        TriggerDecision.NOT_ALLOWED -> R.string.designsystem_trigger_decision_not_allowed
        TriggerDecision.FAILED -> R.string.designsystem_trigger_decision_failed
    }

/** The same inside a sentence ("The test was recorded: didn't match."): [TriggerDecision.label] in lower case in English. */
@get:StringRes
val TriggerDecision.inlineRes: Int
    get() = when (this) {
        TriggerDecision.FIRED -> R.string.designsystem_trigger_decision_fired_inline
        TriggerDecision.TEST -> R.string.designsystem_trigger_decision_test_inline
        TriggerDecision.NO_MATCH -> R.string.designsystem_trigger_decision_no_match_inline
        TriggerDecision.RATE_LIMITED -> R.string.designsystem_trigger_decision_rate_limited_inline
        TriggerDecision.QUIET_HOURS -> R.string.designsystem_trigger_decision_quiet_hours_inline
        TriggerDecision.DISABLED -> R.string.designsystem_trigger_decision_disabled_inline
        TriggerDecision.BUSY -> R.string.designsystem_trigger_decision_busy_inline
        TriggerDecision.NOT_ALLOWED -> R.string.designsystem_trigger_decision_not_allowed_inline
        TriggerDecision.FAILED -> R.string.designsystem_trigger_decision_failed_inline
    }

@get:StringRes
val HelperPrompt.labelRes: Int
    get() = when (this) {
        HelperPrompt.TITLE -> R.string.designsystem_helper_prompt_title
        HelperPrompt.SUGGESTIONS -> R.string.designsystem_helper_prompt_suggestions
        HelperPrompt.COMPACTION -> R.string.designsystem_helper_prompt_compaction
    }

@get:StringRes
val SubagentStatus.labelRes: Int
    get() = when (this) {
        SubagentStatus.RUNNING -> R.string.designsystem_helper_status_running
        SubagentStatus.COMPLETED -> R.string.designsystem_helper_status_completed
        SubagentStatus.PARTIAL -> R.string.designsystem_helper_status_partial
        SubagentStatus.FAILED -> R.string.designsystem_helper_status_failed
        SubagentStatus.CANCELLED -> R.string.designsystem_helper_status_cancelled
        SubagentStatus.INTERRUPTED -> R.string.designsystem_helper_status_interrupted
    }

/** A check's outcome as a title: "Tests passed", "Build failed", "Check timed out" ([Verification.title] in English). */
@StringRes
fun Verification.Outcome.titleRes(): Int = when (command.kind) {
    Verification.Kind.TEST -> when (verdict) {
        Verification.Verdict.PASSED -> R.string.designsystem_check_tests_passed
        Verification.Verdict.FAILED -> R.string.designsystem_check_tests_failed
        Verification.Verdict.TIMED_OUT -> R.string.designsystem_check_tests_timed_out
        Verification.Verdict.UNCERTAIN -> R.string.designsystem_check_tests_finished
    }
    Verification.Kind.BUILD -> when (verdict) {
        Verification.Verdict.PASSED -> R.string.designsystem_check_build_passed
        Verification.Verdict.FAILED -> R.string.designsystem_check_build_failed
        Verification.Verdict.TIMED_OUT -> R.string.designsystem_check_build_timed_out
        Verification.Verdict.UNCERTAIN -> R.string.designsystem_check_build_finished
    }
    Verification.Kind.CHECK -> when (verdict) {
        Verification.Verdict.PASSED -> R.string.designsystem_check_check_passed
        Verification.Verdict.FAILED -> R.string.designsystem_check_check_failed
        Verification.Verdict.TIMED_OUT -> R.string.designsystem_check_check_timed_out
        Verification.Verdict.UNCERTAIN -> R.string.designsystem_check_check_finished
    }
}

// ---------------------------------------------------------------------------------------------
// Schedules
// ---------------------------------------------------------------------------------------------

/** "Every 6 hours" for a listed interval ([ScheduleInterval.label] in English). */
@get:StringRes
val ScheduleInterval.labelRes: Int
    get() = when (minutes) {
        ScheduleIntervals.HOURLY.minutes -> R.string.designsystem_schedule_hourly
        ScheduleIntervals.SIX_HOURS.minutes -> R.string.designsystem_schedule_six_hours
        ScheduleIntervals.DAILY.minutes -> R.string.designsystem_schedule_daily
        ScheduleIntervals.WEEKLY.minutes -> R.string.designsystem_schedule_weekly
        else -> error("Not a listed interval: $minutes min")
    }

/** "6 hours", for a segmented control ([ScheduleInterval.short] in English). */
@get:StringRes
val ScheduleInterval.shortRes: Int
    get() = if (minutes == ScheduleIntervals.SIX_HOURS.minutes) R.string.designsystem_schedule_six_hours_short else labelRes

/**
 * [ScheduleIntervals.label] as [UiText], for a line put together outside a composable: "Daily", else
 * "Every 2 days", "Every 3 hours", "Every 45 min".
 */
fun scheduleText(minutes: Long): UiText {
    ScheduleIntervals.all.firstOrNull { it.minutes == minutes }?.let { return UiText.Res(it.labelRes) }
    return when {
        minutes >= 1_440 && minutes % 1_440 == 0L -> (minutes / 1_440).toInt().let { n ->
            if (n == 1) UiText.Res(R.string.designsystem_schedule_every_day) else UiText.Plural(R.plurals.designsystem_schedule_every_days, n)
        }
        minutes >= 60 && minutes % 60 == 0L -> (minutes / 60).toInt().let { n ->
            if (n == 1) UiText.Res(R.string.designsystem_schedule_every_hour) else UiText.Plural(R.plurals.designsystem_schedule_every_hours, n)
        }
        else -> UiText.Res(R.string.designsystem_schedule_every_minutes, minutes.toInt())
    }
}

/** [scheduleText] in [resources]. */
fun scheduleLabel(resources: Resources, minutes: Long): String = scheduleText(minutes).asString(resources)

@Composable
@ReadOnlyComposable
fun scheduleLabel(minutes: Long): String = scheduleLabel(LocalResources.current, minutes)

/** A helper's time limit: "30 min", "2 h" ([HelperTimeLimit.label] in English). */
fun helperTimeLimitLabel(resources: Resources, minutes: Int): String = minutesLabel(resources, minutes)

/** "30 min", or whole hours as "2 h". */
internal fun minutesLabel(resources: Resources, minutes: Int): String =
    if (minutes >= 60 && minutes % 60 == 0) resources.getString(R.string.designsystem_amount_hours, minutes / 60)
    else resources.getString(R.string.designsystem_amount_minutes, minutes)

// ---------------------------------------------------------------------------------------------
// Settings pages
// ---------------------------------------------------------------------------------------------

/** The page's name parts after "Settings", as the hub's rows name them ([SettingsDestination.path] in English). */
fun SettingsDestination.pathRes(): List<Int> = when (this) {
    SettingsDestination.Models -> listOf(R.string.designsystem_settings_models)
    SettingsDestination.OnDeviceModels -> listOf(R.string.designsystem_settings_models, R.string.designsystem_settings_on_device_models)
    SettingsDestination.Extras -> listOf(R.string.designsystem_settings_extras)
    SettingsDestination.Network -> listOf(R.string.designsystem_settings_network)
    SettingsDestination.Agent -> listOf(R.string.designsystem_settings_agent)
    SettingsDestination.Permissions -> listOf(R.string.designsystem_settings_permissions)
    SettingsDestination.Battery -> listOf(R.string.designsystem_settings_battery)
    SettingsDestination.Voice -> listOf(R.string.designsystem_settings_voice)
    SettingsDestination.Automation -> listOf(R.string.designsystem_settings_automation)
    SettingsDestination.Phone -> listOf(R.string.designsystem_settings_phone)
    SettingsDestination.Integrations -> listOf(R.string.designsystem_settings_integrations)
    SettingsDestination.Ssh -> listOf(R.string.designsystem_settings_ssh)
    SettingsDestination.Logins -> listOf(R.string.designsystem_settings_logins)
    SettingsDestination.Backup -> listOf(R.string.designsystem_settings_backup)
    SettingsDestination.About -> listOf(R.string.designsystem_settings_about)
    SettingsDestination.Diagnostics -> listOf(R.string.designsystem_settings_about, R.string.designsystem_settings_diagnostics)
    SettingsDestination.SelfTest -> listOf(R.string.designsystem_settings_about, R.string.designsystem_settings_diagnostics, R.string.designsystem_settings_self_test)
    SettingsDestination.Storage -> listOf(R.string.designsystem_settings_about, R.string.designsystem_settings_diagnostics, R.string.designsystem_settings_storage)
}
