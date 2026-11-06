package dev.locus.feature.settings

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.locus.core.data.device.CalendarData
import dev.locus.core.data.device.Reminders
import dev.locus.core.data.device.WritableCalendar
import dev.locus.core.data.repository.SettingsRepository
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.ConfirmSheet
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.Insets
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.RowChevron
import dev.locus.core.designsystem.color
import dev.locus.core.designsystem.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Where Locus stands with calendar access. */
enum class CalendarAccess { ALLOWED, NOT_ASKED, BLOCKED }

/** What the phone tells this page: access, the calendars Locus could write to, and reminder delivery. */
data class PhoneProbe(
    val access: CalendarAccess = CalendarAccess.NOT_ASKED,
    val calendars: List<WritableCalendar> = emptyList(),
    val exactAlarms: Boolean = true,
    val notifications: Boolean = true,
    val loaded: Boolean = false,
)

data class PhoneUiState(
    val probe: PhoneProbe = PhoneProbe(),
    /** The calendar picked in Settings (id), or null before one is picked. */
    val chosenId: Long? = null,
    val chosenName: String = "",
    /** Reminders still to come. */
    val upcoming: Int = 0,
) {
    /** The calendar shown as selected: the picked one, else the one the first write would pick (primary, else first). */
    val selectedId: Long? get() = chosenId ?: (probe.calendars.firstOrNull { it.primary } ?: probe.calendars.firstOrNull())?.id
}

/** Settings → Phone: which calendar Locus may change, and how reminders arrive (docs/design/phone-integrations.md). */
@HiltViewModel
class PhoneViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val calendar: CalendarData,
    private val reminders: Reminders,
) : ViewModel() {
    private val probe = MutableStateFlow(PhoneProbe())

    val state: StateFlow<PhoneUiState> = combine(probe, settings.settings) { p, s ->
        PhoneUiState(p, s.phone.calendarId, s.phone.calendarName, s.reminders.count { it.upcoming })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PhoneUiState())

    /** Reads access, calendars and alarm and notification state again; [blocked] after Android declined without a prompt. */
    fun refresh(blocked: Boolean = false) = viewModelScope.launch {
        probe.value = withContext(Dispatchers.IO) {
            val allowed = calendar.hasWriteAccess()
            PhoneProbe(
                access = when {
                    allowed -> CalendarAccess.ALLOWED
                    blocked || probe.value.access == CalendarAccess.BLOCKED -> CalendarAccess.BLOCKED
                    else -> CalendarAccess.NOT_ASKED
                },
                calendars = if (allowed) runCatching { calendar.writableCalendars() }.getOrDefault(emptyList()) else emptyList(),
                exactAlarms = reminders.exactTiming(),
                notifications = reminders.canNotify(),
                loaded = true,
            )
        }
    }

    fun pick(c: WritableCalendar) = viewModelScope.launch {
        settings.setPhone { it.copy(calendarId = c.id, calendarName = c.name, calendarAccount = c.account) }
    }
}

@Composable
internal fun PhoneRoute(onBack: () -> Unit, viewModel: PhoneViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = androidx.activity.compose.LocalActivity.current
    var rationale by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = result.values.all { it }
        // Android shows no prompt again after two denials; then only App info can turn it on.
        val blocked = !granted && activity?.let { !ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.WRITE_CALENDAR) } == true
        viewModel.refresh(blocked)
    }
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    if (rationale) {
        CalendarRationaleSheet(
            onContinue = { rationale = false; launcher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) },
            onDismiss = { rationale = false },
        )
    }
    PhoneSettings(
        state,
        onBack = onBack,
        onAllowCalendar = { rationale = true },
        onPick = viewModel::pick,
        onAppInfo = { PhoneSystemPages.appInfo(context) },
        onExactAlarms = { PhoneSystemPages.exactAlarms(context) },
        onNotifications = { PhoneSystemPages.notifications(context) },
    )
}

/** Android's own pages this one sends the user to. */
object PhoneSystemPages {
    fun appInfo(context: Context) = open(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))

    /** "Alarms & reminders" for Locus (API 31+), with the package as Android's docs ask. */
    fun exactAlarms(context: Context) {
        if (Build.VERSION.SDK_INT >= 31) open(context, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri()))
    }

    fun notifications(context: Context) = open(context, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))

    private fun open(context: Context, intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            appInfoFallback(context)
        }
    }

    private fun appInfoFallback(context: Context) {
        runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/** Locus's explanation before Android's calendar prompt, here and when a calendar tool first needs it. */
@Composable
fun CalendarRationaleSheet(onContinue: () -> Unit, onDismiss: () -> Unit, title: String = CALENDAR_RATIONALE_TITLE, message: String = CALENDAR_RATIONALE_TEXT) {
    ConfirmSheet(title = title, confirmLabel = "Continue", onConfirm = onContinue, onDismiss = onDismiss, message = message, dismissLabel = "Not now")
}

/** When Android won't show its prompt again: what to do, with a way to App info. */
@Composable
fun PermissionBlockedSheet(message: String, onAppInfo: () -> Unit, onDismiss: () -> Unit) {
    ConfirmSheet(title = "Turned off in Android", confirmLabel = "Open App info", onConfirm = onAppInfo, onDismiss = onDismiss, message = message, dismissLabel = "Not now")
}

internal const val CALENDAR_RATIONALE_TITLE = "Let Locus change your calendar?"
internal const val CALENDAR_RATIONALE_TEXT =
    "Locus needs calendar access to add, change or delete events when you ask. It only writes to the one calendar you pick " +
        "here, and each change shows you the event first. Events sync to that calendar's account."

@Composable
internal fun PhoneSettings(
    state: PhoneUiState,
    onBack: () -> Unit,
    onAllowCalendar: () -> Unit,
    onPick: (WritableCalendar) -> Unit,
    onAppInfo: () -> Unit,
    onExactAlarms: () -> Unit,
    onNotifications: () -> Unit,
) {
    val probe = state.probe
    SettingsPageScaffold("Phone", onBack) {
        item { SectionLabel("Calendar Locus may change", SectionPadding) }
        item {
            CardGroup {
                when {
                    !probe.loaded -> ListRow("Checking calendar access…", leading = { IconTile(LocusIcons.Calendar) })
                    // Whole rows are the buttons (with a chevron): a text button beside the words squeezes them at a large font.
                    probe.access == CalendarAccess.NOT_ASKED -> ListRow(
                        title = "Allow calendar access",
                        subtitle = "Then pick the calendar Locus adds events to.",
                        titleMaxLines = 2,
                        subtitleMaxLines = Int.MAX_VALUE,
                        onClick = onAllowCalendar,
                        leading = { IconTile(LocusIcons.Calendar) },
                        trailing = { RowChevron() },
                    )
                    probe.access == CalendarAccess.BLOCKED -> ListRow(
                        title = "Calendar access is off",
                        subtitle = "Android won't ask again. Open App info → Permissions → Calendar to allow it.",
                        titleMaxLines = 2,
                        subtitleMaxLines = Int.MAX_VALUE,
                        onClick = onAppInfo,
                        leading = { IconTile(LocusIcons.Calendar, tint = Tone.Caution.color()) },
                        trailing = { RowChevron() },
                    )
                    probe.calendars.isEmpty() -> ListRow(
                        title = "No calendar can be changed",
                        subtitle = "Every calendar on this phone is read-only or not synced. Add an account in your calendar app, then come back.",
                        subtitleMaxLines = Int.MAX_VALUE,
                        leading = { IconTile(LocusIcons.Calendar) },
                    )
                    else -> probe.calendars.forEachIndexed { i, c ->
                        if (i > 0) GroupDivider(inset = Insets.iconRow)
                        val selected = c.id == state.selectedId
                        ListRow(
                            title = c.name,
                            subtitle = listOfNotNull(c.account.takeIf { it.isNotBlank() && it != c.name }, "default".takeIf { c.primary && state.chosenId == null }).joinToString(" · ").ifEmpty { null },
                            titleMaxLines = 2,
                            onClick = { onPick(c) },
                            leading = { IconTile(LocusIcons.Calendar) },
                            trailing = { RadioButton(selected = selected, onClick = { onPick(c) }) },
                        )
                    }
                }
            }
        }
        // A picked calendar that has gone (account removed, made read-only) is said plainly.
        if (probe.access == CalendarAccess.ALLOWED && state.chosenId != null && probe.calendars.none { it.id == state.chosenId }) {
            item {
                SectionNote("“${state.chosenName.ifBlank { "The calendar you picked" }}” can't be changed any more. Pick another, or Locus won't write to the calendar.", NotePadding)
            }
        }
        item {
            SectionNote(
                "Locus adds and changes events only in this calendar; others stay read-only to it. Adding or changing an event asks you " +
                    "first (you can say “don't ask again” for this calendar). Deleting an event or inviting guests always asks, because " +
                    "the calendar's server emails the guests.",
                NotePadding,
            )
        }
        item { SectionLabel("Reminders", SectionPadding) }
        item {
            CardGroup {
                // Below Android 12 exact alarms need no grant, so there's nothing to open.
                val canChangeExact = Build.VERSION.SDK_INT >= 31
                ListRow(
                    title = "Exact timing",
                    subtitle = if (probe.exactAlarms) "On: reminders show on the minute." else "Off: reminders can show up to 10 minutes late. Turn on Alarms & reminders for Locus.",
                    subtitleMaxLines = Int.MAX_VALUE,
                    onClick = onExactAlarms.takeIf { canChangeExact },
                    leading = { IconTile(LocusIcons.Clock) },
                    trailing = { if (canChangeExact) RowChevron() },
                )
                GroupDivider(inset = Insets.iconRow)
                ListRow(
                    title = "Notifications",
                    subtitle = if (probe.notifications) "On: reminders appear with Snooze and Done." else "Off: reminders won't show until you allow notifications for Locus.",
                    subtitleMaxLines = Int.MAX_VALUE,
                    onClick = onNotifications,
                    leading = { if (probe.notifications) IconTile(LocusIcons.Check) else IconTile(LocusIcons.Alert, tint = Tone.Critical.color()) },
                    trailing = { RowChevron() },
                )
            }
        }
        item {
            SectionNote(
                (if (state.upcoming > 0) "${state.upcoming} reminder${if (state.upcoming == 1) "" else "s"} to come; see and cancel them in Scheduled tasks. " else "") +
                    "A reminder is a notification from Locus: nothing runs when it shows. Exact timing uses Android's “Alarms & reminders” " +
                    "access; without it Android may hold a reminder for up to 10 minutes to save battery.",
                NotePadding,
            )
        }
    }
}
