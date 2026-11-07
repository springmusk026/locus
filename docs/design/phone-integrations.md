# Phone integrations: calendar writes, reminders, contacts, Save as, clipboard

Status: unreleased, *not yet tried on the phone*. Builds on the read-only integrations in [android-bridge.md](android-bridge.md#phone-integrations-as-tools). Research: the November integrations research, items 1 (calendar write), 5 (reminders) and 8 (contacts, save, clipboard).

Code:
- Calendar: `core/data/.../device/CalendarWriteTools.kt` (tools, parsing, the one-calendar rule), `AndroidCalendar.kt` (CalendarContract), `AndroidPhone.calendarWriteAccess` and `PhoneRequest.Permissions` (the rationale and Android's prompt), `PhoneRequestHost` in `app/.../MainActivity.kt`.
- Reminders: `core/data/.../device/Reminders.kt` (store, tools), `AndroidReminders.kt` (AlarmManager, the notification, `ReminderReceiver`), `RemindersUi.kt` in feature/tasks.
- Hand-offs: `core/data/.../device/PhoneHandoffTools.kt`, `PhoneIntents.kt`.
- Settings → Phone: `feature/settings/.../PhoneSettings.kt`.
- Approval card fields: `Tool.preview` and `ApprovalPreview` (core/agent), `ApprovalPreviewRows` in `feature/chat/.../ChatScreen.kt`. "This calendar": `Tool.rememberScope`, `ApprovalMemory.matchScope`, `ApprovalScopeKind.CALENDAR`.

Tests: `CalendarWriteToolsTest`, `RemindersTest`, `PhoneHandoffToolsTest` (JVM, fakes), `PhoneAndroidTest` (Robolectric, in core/ui where Robolectric already is, since its BouncyCastle breaks core/data's in-process SSH tests: the intents, the clip flag, the provider's ContentValues through a stand-in provider, the alarms). Renders: `settings-phone*`, `tasks-reminders*`, `chat-approval-calendar-*`.

## Sources (read 2026-11-06)

- **Calendar provider.** [Calendar provider guide](https://developer.android.com/guide/topics/providers/calendar-provider): "You must include CALENDAR_ID and DTSTART", "You must include an EVENT_TIMEZONE", "For non-recurring events, you must include DTEND", "For recurring events, you must include a DURATION in addition to RRULE or RDATE"; "An application delete sets the *deleted* column to 1 … this deletion should be propagated to the server". [CalendarContract.Events](https://developer.android.com/reference/android/provider/CalendarContract.Events): "If allDay is set to 1 eventTimezone must be "UTC" and the time must correspond to a midnight boundary"; "Updating an event must respect the same rules as inserting"; `CONTENT_EXCEPTION_URI` "Insertions require an appended event ID". [CalendarContract.Calendars](https://developer.android.com/reference/android/provider/CalendarContract.Calendars): `CAL_ACCESS_CONTRIBUTOR` "Full access to modify the calendar, but not the access control settings"; `CALENDAR_ACCESS_LEVEL` is "writable only by a sync adapter". [Attendees](https://developer.android.com/reference/android/provider/CalendarContract.Attendees) and the guide's Reminders table (EVENT_ID, MINUTES, METHOD).
- **Permissions.** [Manifest.permission](https://developer.android.com/reference/android/Manifest.permission): READ_CALENDAR and WRITE_CALENDAR are both "Protection level: dangerous". [Android 8.0 changes](https://developer.android.com/about/versions/oreo/android-8.0-changes): "The app is granted only the permissions it has explicitly requested". [Requesting permissions](https://developer.android.com/training/permissions/requesting): "Your app must not rely on permissions being within or outside of a specific permission group"; when `shouldShowRequestPermissionRationale()` is true "show an educational UI"; "if the user taps Deny for a specific permission more than once … the user will no longer see the system permissions dialog".
- **Exact alarms.** [Android 14: exact alarms denied by default](https://developer.android.com/about/versions/14/changes/schedule-exact-alarms): SCHEDULE_EXACT_ALARM "is no longer being pre-granted to most newly installed apps targeting Android 13 and higher"; USE_EXACT_ALARM is for calendar and alarm-clock apps and "Apps will not be able to publish a version of their app with this permission in the manifest unless they qualify". [Schedule alarms](https://developer.android.com/develop/background-work/services/alarms): "windowLengthMillis parameter values under 600000 are typically clipped to 600000"; inexact `set()`/`setAndAllowWhileIdle()` alarms may come "within one hour"; "When the SCHEDULE_EXACT_ALARM permission is revoked for your app, your app stops, and all future exact alarms are canceled"; "By default, all alarms are canceled when a device shuts down" (RECEIVE_BOOT_COMPLETED to set them again). [AlarmManager](https://developer.android.com/reference/android/app/AlarmManager): alarms "will be cleared if it is turned off and rebooted"; ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED is sent on a grant, not on a revoke. [Play policy](https://support.google.com/googleplay/android-developer/answer/16558241) (Permissions and APIs that Access Sensitive Information): exact-alarm permission only where "core functionality supports the need", for alarm/timer and calendar apps.
- **WorkManager**, considered: "WorkManager tasks don't run" in Doze ([Doze](https://developer.android.com/training/monitoring-device-state/doze-standby)), though work does persist across reboots ([persistent work](https://developer.android.com/develop/background-work/background-tasks/persistent)).
- **Contacts.** [Modify contacts with intents](https://developer.android.com/training/contacts-provider/modify-data): "Your app doesn't need permission to write to the Contacts Provider, because it delegates modifications to the contacts app"; "Set the MIME type to ContactsContract.RawContacts.CONTENT_TYPE"; the user can cancel. Extras: [ContactsContract.Intents.Insert](https://developer.android.com/reference/android/provider/ContactsContract.Intents.Insert).
- **Save as.** [Documents and other files](https://developer.android.com/training/data-storage/shared/documents-files): "this mechanism doesn't require any system permissions"; "ACTION_CREATE_DOCUMENT cannot overwrite an existing file … the system appends a number in parentheses".
- **Clipboard.** [Copy and paste](https://developer.android.com/develop/ui/views/touch-and-input/copy-paste): mark sensitive content with `ClipDescription.EXTRA_IS_SENSITIVE` ("All apps must do this, regardless of the targeted API level"; the plain key `android.content.extra.IS_SENSITIVE` below API 33); "we strongly recommend removing toasts or snackbars shown after an in-app copy for Android 13 and higher".
- **Notifications.** [Create a notification](https://developer.android.com/develop/ui/views/notifications/build-notification): action buttons through a `PendingIntent` to a `BroadcastReceiver` (Snooze is the example); PendingIntents need a mutability flag on Android 12+; a receiver can't start an activity after a notification tap (Android 12 trampolines).

Couldn't verify in official docs: the recipe for editing or cancelling one occurrence through `CONTENT_EXCEPTION_URI` (which values it needs), whether inserting attendees makes every sync adapter send invitations (Google's and Exchange's do in practice; the card says "may"), and whether `HAS_ALARM` is set by the provider (Locus sets it).

## Calendar writes

| Tool | What it does | Capability | Default |
|---|---|---|---|
| `calendar_create(title, start, end?, duration_minutes?, all_day?, location?, notes?, recurrence?, guests?, reminder_minutes?)` | Adds an event to the allowed calendar | OUTWARD | Ask |
| `calendar_update(event_id, series?, title?, start?, end?, duration_minutes?, all_day?, location?, notes?, recurrence?, add_guests?)` | Changes the given fields | OUTWARD | Ask |
| `calendar_delete(event_id, series?)` | Deletes an event (the whole series for a repeating one) | OUTWARD | Ask, always confirmed |

- **One calendar.** Settings → Phone lists the calendars Locus could write to (contributor access or more, visible, synced) and the user picks one; until then the first write uses the primary calendar and saves it as the choice. Events in any other calendar are read-only to Locus: update and delete refuse them by name. A picked calendar that has gone (account removed, made read-only) is reported, never silently replaced. `calendar_read` now shows each event's id and "(repeats)".
- **Access.** READ_CALENDAR and WRITE_CALENDAR are asked for together (Android 8+ grants only what is asked, and the docs say not to rely on groups), only while Locus is on screen, after Locus's own sheet ("Let Locus change your calendar?", Continue / Not now). When Android won't show its prompt any more, a second sheet says so and opens App info. From the background the tool says Locus isn't on screen. The prompt comes from `precheck`, before the approval card, so the card can show the event being changed or deleted.
- **Times.** ISO local date-times in the phone's zone (`2026-11-10T15:30`), or with an offset or `Z`. The event stores the phone's zone; all-day events are UTC midnights with an exclusive end, as the provider requires. End must be after start; at most 14 days (31 for all-day); a start over a year ago or ten years ahead is refused as a likely mistake, with today's date in the message.
- **Repeating events.** `recurrence` takes simple RRULEs: FREQ (daily to yearly), INTERVAL, COUNT or UNTIL, BYDAY, BYMONTHDAY, BYMONTH; anything else is refused rather than half-understood. A series is stored with RRULE and DURATION (never DTEND). Changing or deleting a repeating event needs `series: true` and applies to every occurrence; without it the tool refuses and says to change a single occurrence in the calendar app. Locus doesn't make single-occurrence exceptions, because the exception API's required values aren't documented (see above) and a wrong exception corrupts a series on the server. A one-off can't be turned into a series by update.
- **The approval card** lists the fields being written (calendar, title, when, repeats, where, notes, reminder, invites) or, for update, the event and each new value, and for delete the event itself. It shows exactly what a tainted run writes.
- **Approvals.**
  - Create and update without guests: Ask, with "Don't ask again for events in *Personal*" (this chat or always; a new `CALENDAR` scope, its value the calendar's id and name, so a different calendar is never covered).
  - **Guests** (create's `guests`, update's `add_guests`) are outward: the calendar's server emails invitations. The call always confirms (`mustConfirm`), names every guest in the card's reason and in a caution-toned "Invites" row, and is never remembered.
  - Delete always confirms and shows the event; never remembered.
- **Taint.** The three are OUTWARD, so a run that has read outside content asks for each one even when the user set the tool to Allow or remembered the calendar (taint makes a remembered approval not apply), and a read-only background run refuses them.

## Reminders

| Tool | What it does | Capability | Default |
|---|---|---|---|
| `remind_me(text, at? \| in_minutes?)` | A Locus notification at a time | LOCAL | Allow |
| `reminders_list()` | Reminders to come, with ids | READ | Allow |
| `reminder_cancel(id)` | Cancels one (an 8-character id prefix is enough) | LOCAL | Ask |

- **No model run.** Firing posts a notification (title: the text; line: "Reminder from Locus · 17:00", or "Late: it was due at 17:00" when the phone was off or asleep), with **Snooze 10 min** and **Done**. Swiping it away doesn't delete it: the notification has no delete intent, because Android sends one for removals the user didn't make (clearing a bundle, Clear all, a regroup), and Android 16+ bundles a reminder with Locus's other notifications. A reminder whose notification went away stays under Scheduled tasks as shown for a day. Tapping it opens the chat it was set in. Nothing else runs. The reminder never reaches a model again.
- **Timing: AlarmManager, not WorkManager.** WorkManager doesn't run during Doze, which would hold a reminder for hours. Locus uses `setExactAndAllowWhileIdle` when the user has granted "Alarms & reminders" (SCHEDULE_EXACT_ALARM, user-granted, denied by default on Android 14+), and otherwise `setWindow` with a 10-minute window, the fallback Android's own sample uses (shorter windows are clipped to 10 minutes anyway). Locus doesn't declare USE_EXACT_ALARM: it's for alarm-clock and calendar apps, and Play blocks it otherwise. This is the F-Droid- and Play-friendly choice: nothing restricted is declared, and exact timing is one switch in Settings → Phone.
- **Storage: the settings store, no Room change.** `AppSettings.reminders` (and `AppSettings.phone` for the calendar choice), in the DataStore Locus already has. At most 100; reminders shown and not answered leave after a day.
- **Surviving reboot.** Android clears alarms on reboot, and when a revoked exact-alarm permission stops the app. `ReminderReceiver` (not exported) sets every alarm again on `BOOT_COMPLETED` (RECEIVE_BOOT_COMPLETED), `MY_PACKAGE_REPLACED` and `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`, and the app does the same at start. A reminder whose time passed while the phone was off fires at once, marked late.
- **Where they show.** Scheduled tasks lists reminders above the tasks: each one to come with Cancel ("Today 17:00 · in 2 h", "snoozed"), and ones shown in the last day with Clear. With reminders but no tasks, the list replaces the empty state.
- **Notifications off.** remind_me's result says the reminder won't show and points to Settings → Phone, which shows "Reminders won't show" with Turn on.

## Hand-offs

| Tool | What it does | Capability | Default |
|---|---|---|---|
| `device_contact_add(name, phones?, emails?, company?, note?)` | Opens Contacts' new-contact screen filled in; the user saves there | OUTWARD | Allow |
| `device_save_file(path, name?)` | The system Save-as picker for a workspace file; the user picks folder and name | OUTWARD | Allow |
| `device_copy(text)` | Copies text to the clipboard, scrubbed and marked sensitive | OUTWARD | Allow |

- **Contacts:** ContactsContract.Intents.Insert (`ACTION_INSERT`, `RawContacts.CONTENT_TYPE`), up to three numbers and addresses, checked first. No contacts permission (this also avoids Play's contacts-permission change of 2027-01-27).
- **Save as:** `ACTION_CREATE_DOCUMENT` through `PhoneRequests` (on screen only), then the file is copied to the picked document. It never overwrites; the result names the file the provider created. Default Allow because the system picker is the confirmation (as `device_*` hand-offs are), and taint still asks in a tainted run.
- **Clipboard:** the text goes through `SecretScrubber` with the vault's values (as chat history and long outputs do), so vault secrets and credential-looking tokens become `[secret]`, and the result tells the agent when something was removed. The clip is always flagged sensitive (`EXTRA_IS_SENSITIVE` on 33+, the plain key before), so Android's copy preview and keyboards don't show it; Locus shows no toast of its own. It's OUTWARD because any app can paste it, so a tainted run asks (a copied link is a paste-jacking vector). There is no clipboard read tool: that would read other apps' data.

## Tool lists and the prompt cache

All twelve phone integrations are in `DeviceIntegrations.ALL`, which is `ToolDiscovery.DISCOVERY_ONLY`:

- **Chats from before tool discovery** (declaring every tool) don't get them: their declared list, and so their cached prefix, is unchanged.
- **Discovery chats (v1 and v2)** get them as deferred tools, found with `find_tools` and run with `call_tool`. They join the existing "phone apps" group, so find_tools' description (part of the cached prefix) is byte-identical, and the core sets didn't change, so there is no v3. On OpenAI Responses they join the `device` namespace, whose description is unchanged.
- **What does change:** on Anthropic and OpenAI with native tool search, the deferred definitions sent with each request grow by these nine tools. Anthropic keeps deferred tools out of the system-prompt prefix ("The prefix is untouched, so prompt caching is preserved"), and OpenAI injects discovered tools at the end of the context, so neither breaks the cached prefix. `calendar_read`'s description is unchanged; only its output gained ids.
- The frozen system prompt is untouched.

## Permissions added

| Permission | Why | Kind |
|---|---|---|
| WRITE_CALENDAR | calendar_create / update / delete | runtime, asked on first use with a rationale |
| RECEIVE_BOOT_COMPLETED | set reminder alarms again after a reboot | normal |
| SCHEDULE_EXACT_ALARM | on-the-minute reminders, only if the user grants "Alarms & reminders" | special access, denied by default on 14+ |

No Play services, no new library, no network. The F-Droid metadata and the privacy text should mention calendar writes and reminders when this ships.

## Not done

- Editing or deleting a single occurrence of a repeating event (exceptions); the user does that in the calendar app.
- Repeating reminders, and `device_alarm` with `SKIP_UI`.
- A "created from outside content" line on reminders set in a tainted run.
- Choosing a calendar per call: Locus writes to the one in Settings → Phone.
