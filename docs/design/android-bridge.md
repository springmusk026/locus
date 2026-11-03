# Android-native automation bridge: ways in, an intent API, phone integrations

Status: shipped (Unreleased), *not yet tried on the phone*. Roadmap: [Recommended next #7](../ROADMAP.md#recommended-next-nov-2026) and "Ways in"; research: [product P1, P2, P4, P5, P8](../research/product-platform-2026.md#p-android-platform).

Code:
- Ways in: `app/src/main/res/xml/shortcuts.xml`, `app/.../LaunchActions.kt`, `AskTileService.kt`, the `AskLocusText` alias in the app manifest, `ShareViewModel.kt` and `MainActivity.kt` (handling); `core/data/.../shortcuts/ChatShortcuts.kt` (dynamic and sharing shortcuts).
- Widget: `app/.../widget/LocusWidget.kt` (Glance UI and receiver), `LocusWidgetUpdater.kt`, `res/xml/locus_widget_info.xml`; `core/data/.../widget/WidgetFeed.kt` (state mapping and cleaning, tested in `WidgetFeedTest`).
- Default assistant: the `AssistLocus` alias in the app manifest, `LaunchActions.resolve`; `DefaultAssistant` in `feature/settings/.../AutomationSettings.kt`.
- Intent API: `app/.../AutomationActivity.kt`; `core/data/.../automation/` (`AutomationContract`, `AutomationRequest`, `AutomationAccess`, `AutomationBridge`); `ModeNote.external` (core/model); `Taint.sourcesIn` and `trustedUserMessages` (core/agent).
- Phone integrations: `core/data/.../device/DeviceIntegrationTools.kt`, `AndroidPhone.kt`, `PhoneRequests.kt`; `PhoneRequestHost` in `MainActivity.kt`.
- Composer: `feature/chat/.../LocusComposer.kt` (Photos, Camera, Files in the + sheet).

The hard rule stays: **no AccessibilityService**, no reading or driving other apps' screens. Everything here uses permissioned platform APIs, system pickers that share only what the user picks, and intents that start Locus.

## Research (read 2026-11-02)

Each item says what the source says and what Locus does with it. Quotes are from the linked pages.

### Shortcuts and Direct Share

- [Create shortcuts](https://developer.android.com/develop/ui/views/launch/shortcuts) and [Manage shortcuts](https://developer.android.com/develop/ui/views/launch/shortcuts/managing-shortcuts): static shortcuts live in `res/xml/shortcuts.xml` behind `android.app.shortcuts` meta-data; static and dynamic shortcuts together are capped by `getMaxShortcutCountPerActivity`, and "most launchers display a maximum of four shortcuts". Pinned shortcuts have no cap. Rate limiting resets when the app comes to the foreground. `pushDynamicShortcut` reports usage itself, so `reportShortcutUsed` is for the others. Never reuse shortcut ids.
- [Direct Share targets](https://developer.android.com/training/sharing/direct-share-targets): a `<share-target>` with `<data>` MIME types and an app-defined `<category>` goes in the same XML; `setCategories()` is "required for shortcuts meant to be used as share targets". "Starting in Android 11 (API level 30), you may provide Direct Share targets only through the Sharing Shortcuts API." `androidx.sharetarget` (1.2.0, 2022) is only for the old pre-API-29 DirectShare service.
- [`ShortcutManagerCompat`](https://developer.android.com/reference/androidx/core/content/pm/ShortcutManagerCompat) (androidx.core 1.19.1, already Locus's version): `EXTRA_SHORTCUT_ID` is the "key to get the shortcut ID from extras of a share intent"; `setLongLived(true)` lets the system cache a shortcut after it's unpublished and "can improve its ranking"; `removeLongLivedShortcuts` is "the preferred way to remove the shortcut regardless of whether it's cached".

Locus: three static shortcuts (New chat, Voice, Workspace); pinned chats first, then recent ones, as long-lived dynamic shortcuts in one share-target category, so the share sheet offers them and a share lands in that chat's composer. No `androidx.sharetarget`: Direct Share needs API 29+, which most phones Locus runs on have; on 26–28 the chats are still launcher shortcuts and sharing to Locus still works. A deleted chat's shortcut is removed with `removeLongLivedShortcuts`.

### Quick settings tile

- [Create a custom Quick Settings tile](https://developer.android.com/develop/ui/views/quicksettings-tiles): an exported service with `android:permission="android.permission.BIND_QUICK_SETTINGS_TILE"` and the `QS_TILE` intent filter; label at most 18 characters; a 24 dp solid white vector icon; `ACTIVE_TILE` meta-data for tiles that update only when asked (the recommended mode).
- [Android 14 behaviour changes](https://developer.android.com/about/versions/14/behavior-changes-14): "`TileService#startActivityAndCollapse(Intent)` is deprecated and now throws an exception when called … use `startActivityAndCollapse(PendingIntent)`."

Locus: `AskTileService`, an active tile that opens home with the ask bar focused, through an immutable `PendingIntent` on Android 14+, after `unlockAndRun` on a locked phone.

### Text selection and share

- [`Intent.ACTION_PROCESS_TEXT`](https://developer.android.com/reference/android/content/Intent#ACTION_PROCESS_TEXT): "`EXTRA_PROCESS_TEXT` contains the text to be processed. `EXTRA_PROCESS_TEXT_READONLY` states if the resulting text will be read-only." The activity's label is what the selection toolbar shows ([Ian Lake, Android Developers](https://medium.com/androiddevelopers/custom-text-selection-actions-with-action-process-text-191f792d2999)). Offering it needs no `<queries>` ([package visibility use cases](https://developer.android.com/training/package-visibility/use-cases)); only an app that lists other apps' actions does.

Locus: an `<activity-alias>` of MainActivity labelled "Ask Locus" with a `text/plain` PROCESS_TEXT filter. The selection becomes a new chat's draft (at most 20,000 characters); nothing is sent until the user taps Send, exactly like `ACTION_SEND`. Locus never returns text, so read-only selections behave the same.

### Pickers

- [Photo picker](https://developer.android.com/training/data-storage/shared/photo-picker) (androidx.activity 1.13.0, Locus's version): `PickVisualMedia` / `PickMultipleVisualMedia(n)`, no storage permission; on devices without the system picker the library falls back (to `ACTION_OPEN_DOCUMENT`, where the item limit is ignored). Access lasts "until the device is restarted or until your app stops", so Locus copies what is picked straight away.
- [`MediaStore.ACTION_IMAGE_CAPTURE`](https://developer.android.com/reference/android/provider/MediaStore#ACTION_IMAGE_CAPTURE): "if you app targets M and above and declares as using the `Manifest.permission.CAMERA` permission which is not granted, then attempting to use this action will result in a SecurityException." Locus doesn't declare CAMERA, so `TakePicture` needs none. [Android 17 behaviour changes](https://developer.android.com/about/versions/17/behavior-changes-all) say Android 18 stops granting URI permissions implicitly for IMAGE_CAPTURE; `TakePicture` passes a FileProvider URI the camera app is given access to by the activity-result library today, which is worth rechecking on Android 18.
- [Android 17 contact picker](https://developer.android.com/about/versions/17/features/contact-picker) and [`ContactsPickerSessionContract`](https://developer.android.com/reference/android/provider/ContactsPickerSessionContract): `ACTION_PICK_CONTACTS`, with `EXTRA_PICK_CONTACTS_REQUESTED_DATA_FIELDS` (MIME types such as `Phone.CONTENT_ITEM_TYPE`); "The calling application gains one-time read access to the user-selected contact data, without requiring READ_CONTACTS"; the result is a session URI queried with a `ContactsContract.Data`-style projection. Apps targeting API 37 also get `ACTION_PICK` upgraded to the new picker. Before Android 17, `ACTION_PICK` on a data type (`Phone.CONTENT_TYPE`) returns the picked row; that this grants a temporary read without READ_CONTACTS is long-standing behaviour but not stated on developer.android.com (*unverified there*).

Locus: `contacts_pick` uses `ACTION_PICK_CONTACTS` on Android 17 and `ACTION_PICK` on a data type before it. Locus declares no contacts permission at all.

### Calendar

- [`CalendarContract.Instances`](https://developer.android.com/reference/android/provider/CalendarContract.Instances): "An instance is a single occurrence of an event"; `Instances.query(cr, projection, begin, end)` "will cause an expansion of recurring events" and blocks, so it runs off the main thread. READ_CALENDAR is a runtime permission ([permissions overview](https://developer.android.com/guide/topics/permissions/overview)).

Locus: `calendar_read` asks for READ_CALENDAR the first time it's used, only while Locus is on screen, and queries `Instances` for the window asked.

### Automation conventions

- **Tasker** runs other apps' actions as plugins (an EditActivity plus a FireReceiver; bundle values are primitive types and Strings) or through its generic "Send Intent" action ([Tasker plugins](https://tasker.joaoapps.com/plugins.html)); other apps run Tasker's own tasks by broadcast with `net.dinglisch.android.tasker.PERMISSION_RUN_TASKS` and "Allow External Access" turned on ([invoking tasks](https://tasker.joaoapps.com/invoketasks.html)).
- **Termux `RUN_COMMAND`** ([manifest](https://github.com/termux/termux-app/blob/master/app/src/main/AndroidManifest.xml), [wiki](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent)): a custom permission `${TERMUX_PACKAGE_NAME}.permission.RUN_COMMAND` with `protectionLevel="dangerous"` protects an exported service; the user grants it in App info → Permissions → Additional permissions; `allow-external-apps=true` must also be set in Termux (off by default); results come back through a caller-supplied PendingIntent. Tasker could only use it once Tasker itself declared that permission (v5.9.3, [termux-tasker](https://github.com/termux/termux-tasker)): **a new app's custom permission is held by nobody until other developers add it.**
- **Custom permissions** ([risks](https://developer.android.com/privacy-and-security/risks/custom-permissions)): "Using a normal or dangerous protectionLevel on your permissions means most apps can request and get the permission … these protectionLevels provide little security. Use signature protection levels wherever possible." An app installed first can define the same name with a weaker level.
- **Who is calling**: `Activity.getCallingPackage()` is null unless the caller used `startActivityForResult`; `getLaunchedFromPackage()` (API 34) only when the caller opted in with `ActivityOptions.setShareIdentityEnabled(true)` (or other narrow cases) ([Activity](https://developer.android.com/reference/android/app/Activity)). `PendingIntent.getCreatorPackage()` is "supplied by the system, so that an application can not spoof its package", though "it does not tell you who handed the PendingIntent to you" ([PendingIntent](https://developer.android.com/reference/android/app/PendingIntent)). A broadcast sender is only known with its opt-in, API 34+ (`BroadcastReceiver.getSentFromPackage`).
- **PendingIntents** ([risks](https://developer.android.com/privacy-and-security/risks/pending-intent)): explicit, immutable, one-shot where possible. Filling extras into a caller's PendingIntent needs it to be mutable.
- **Background starts** ([restrictions](https://developer.android.com/guide/components/activities/background-starts), [Android 15](https://developer.android.com/about/versions/15/behavior-changes-15), [Android 17](https://developer.android.com/about/versions/17/behavior-changes-17)): an app in the background can start activities only with an exemption (a visible window, `SYSTEM_ALERT_WINDOW`, …); a sender of a PendingIntent must opt in to lend its own right, and Android 17 wants `ALLOW_IF_VISIBLE` instead of `ALLOWED`. A foreground service can't be started from the background on Android 12+, which is why Locus's own scheduled runs are plain workers (docs/design/scheduled-runs.md).

### Home-screen widget

- [Glance releases](https://developer.android.com/jetpack/androidx/releases/glance): `glance-appwidget` **1.2.0** is the newest stable (2026-08-26); 1.3.0-alpha02 (2026-07-01) is an alpha. Since 1.2.0-beta01 the default `minSdk` is 23 (Locus's is 26). "`provideGlance` runs in a worker" (the WorkManager session).
- [Android 17 behaviour changes](https://developer.android.com/about/versions/17/behavior-changes-17): for apps targeting 37 (Locus does) "the system enforces a strict memory limit on `RemoteViews` updates"; over it, the update is rejected with `IllegalArgumentException` ("RemoteViews for widget update exceeds maximum bitmap memory usage"). The budget is `6 * width * height` bytes of the display, and it counts in-memory `Bitmap`s and bitmap-backed `Icon`s; with `SizeMode.Responsive` or `Exact`, Glance composes once per size bucket.
- [App widgets, advanced](https://developer.android.com/develop/ui/views/appwidgets/advanced): "`updatePeriodMillis` doesn't support values of less than 30 minutes … to disable periodic updates, you can specify 0"; don't use `partiallyUpdateAppWidget` to swap images on 37+. [Glance app widget](https://developer.android.com/develop/ui/compose/glance/glance-app-widget): "Whenever the state or the data changes, it is the app's responsibility to notify and update the widget", with `update`/`updateAll`; "Avoid updating your widget every minute when the app isn't awake".
- How long a Glance session keeps recomposing from a collected flow after an update isn't documented; a Glance maintainer says "at least 45 seconds" ([kotlinlang Slack](https://slack-chats.kotlinlang.org/t/16197576/so-question-2-i-have-a-widget-that-is-showing-a-progress-bar), *unverified against the 1.2.0 source*). So Locus doesn't rely on it.

Locus: `LocusWidget`, one 3×2 layout (`SizeMode.Single`, so one composition), Locus's own light and dark palettes through `glance-material3`'s `ColorProviders`, and no bitmaps at all (a tinted vector dot and text), so the Android 17 limit can't be reached. `updatePeriodMillis` is 0: `LocusWidgetUpdater` collects `WidgetFeed` (built on `AgentRunner.states`, the flow the status notification uses, and the tasks table) and calls `updateAll` only when the widget's text changes, at most once a second, and only while a widget is placed. See [Ways in](#ways-in) for what it shows.

### Default digital assistant

- AOSP's [`roles.xml`](https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/main/PermissionController/res/xml/roles.xml) declares `android.app.role.ASSISTANT` with **`requestable="false"`**: `RoleManager.createRequestRoleIntent` can't ask for it; the user picks it in Settings → Default apps → Digital assistant app.
- [`AssistantRoleBehavior`](https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/main/PermissionController/role-controller/java/com/android/role/controller/behavior/AssistantRoleBehavior.java) qualifies a package that has **either** a `VoiceInteractionService` (guarded by `BIND_VOICE_INTERACTION`, with `sessionService`, `recognitionService` and `supportsAssist="true"` in its metadata, and not on low-RAM devices) **or** an exported activity that resolves `Intent.ACTION_ASSIST` with `MATCH_DEFAULT_ONLY`. So a `VoiceInteractionService` is *not* required.
- Holding power: `PhoneWindowManager`'s `LONG_PRESS_POWER_ASSISTANT` calls `launchAssistAction` with `INVOCATION_TYPE_POWER_BUTTON_LONG_PRESS` ([source](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/policy/PhoneWindowManager.java)); SystemUI's [`AssistManager`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SystemUI/src/com/android/systemui/assist/AssistManager.java) starts the voice interactor when the holder is a service and otherwise `startAssistActivity`: `ACTION_ASSIST` with `FLAG_ACTIVITY_NEW_TASK` to the holder's activity. Whether holding power invokes the assistant at all is a device setting (Pixel: System → Gestures → Press and hold power button), and OEM side-key handling (Samsung's) may differ (*unverified*). An activity-only assistant gets no voice session, no hotword and no overlay over the current app.

Locus: an exported `AssistLocus` alias of MainActivity with the `ACTION_ASSIST` filter, which makes Locus selectable; `LaunchActions.resolve` maps `ACTION_ASSIST` to the ask bar, like the tile. None of the assist extras is read, so Locus never sees the app or screen the gesture came from. Settings → Automation → Assistant shows whether Locus holds the role (`RoleManager.isRoleHeld`, re-read on resume) and opens Android's Default apps page (`ACTION_MANAGE_DEFAULT_APPS_SETTINGS`, falling back to voice input settings). There's no voice-mode screen on main yet; when there is, the assist gesture should open it instead. A full `VoiceInteractionService` (a session over the current app, hotword) is deferred: it's large, and an overlay over other apps sits close to the "never read other apps' screens" rule.

### App Actions and AppFunctions

- [App Actions](https://developer.android.com/develop/devices/assistant/overview) carry no deprecation banner (page updated 2026-07-16) but are tied to Google Assistant; Gemini's use of them isn't documented (*unverified*). Locus has no Google dependency and ships to F-Droid, so it doesn't add `capabilities` to `shortcuts.xml`.
- [AppFunctions](https://developer.android.com/ai/appfunctions): "Experimental … subject to change"; Android 16+; "Callers must have the EXECUTE_APP_FUNCTIONS permission"; "Only a limited number of apps and system agents can access the entire pipeline"; Gemini integration in private preview. `androidx.appfunctions` is 1.0.0-beta01 (2026-10-21, [releases](https://developer.android.com/jetpack/androidx/releases/appfunctions)). **Wait.** When it opens to third-party callers, expose the same three actions as the intent API below (start a chat, run a workflow, run a task), each through the same `AutomationAccess` decision and taint.

## Ways in

| Way in | What it does | Code |
|---|---|---|
| Static shortcut **New chat** | A new empty chat, opened. | `LaunchActions.NEW_CHAT` |
| Static shortcut **Voice** | A new chat with voice mode open ([voice mode](voice-mode.md)); it listens once the microphone is allowed. | `LaunchActions.VOICE` |
| Static shortcut **Workspace** | The workspace browser. | `LaunchActions.WORKSPACE` |
| Dynamic shortcuts | Pinned chats, then recent ones (helpers, hidden chats and scheduled runs never), at most four, ranked; tap opens the chat. | `ChatShortcutPlan`, `ChatShortcuts` |
| Direct Share | Each chat shortcut is a sharing shortcut (`dev.locus.category.SHARE_TO_CHAT`): sharing to it puts the text and files in that chat's composer. A chat that's gone falls back to a new chat. | `ShareViewModel` |
| Quick settings tile **Ask Locus** | Home with the ask bar focused and the keyboard up. | `AskTileService`, `LaunchActions.ASK` |
| Text selection **Ask Locus** | The selection as a new chat's draft. | `AskLocusText` alias, `ShareViewModel` |
| Home-screen **widget** | The status line, the latest scheduled-task result and an **Ask Locus** button (home with the ask bar focused). Tapping the status opens the waiting or working chat, otherwise Locus. | `LocusWidget`, `WidgetFeed` |
| **Default assistant** | Once picked in Android's Default apps, the assist gesture (and holding power, where set) opens home with the ask bar focused. | `AssistLocus` alias, `LaunchActions.resolve` |

None of them sends or runs anything by itself; each ends in a composer the user sends from, or in voice mode, which sends only what the user then says. The static shortcut intents name MainActivity explicitly; `targetPackage` comes from a `resValue` because the debug build's application id differs. A chat shortcut opens its chat through `OpenChatLink`'s per-install token, as notifications do, so another app can't open a chat of its choosing through MainActivity.

`ChatShortcuts` watches the chat list, debounced by a second, and replaces the dynamic shortcuts when the plan changes. The plan is pure (`ChatShortcutPlanTest`).

### The widget

- **Status line** (`widgetStatus`): "1 approval waiting", "2 approvals, 1 question waiting", "Paused at the task budget", "Working: running a command" (`toolActivity`'s fixed phrase for the active tool), "Working: 2 helpers", "Running a scheduled task", "Idle". Only counts and Locus's own labels: never a tool's arguments, an approval's summary or a question's text, since anyone holding the phone sees the home screen.
- **Approvals are never answered from the widget.** Tapping the status opens the waiting chat in Locus (through `OpenChatLink`, as notifications do), where the approval card is; Allow from outside the app stays with the unlocked notification.
- **Task line** (`taskLine`): "Title: first line of the answer", or "Title: failed" / "Title: stopped" with no detail. The answer is a model's text, possibly from web pages or email, so `WidgetText.clean` runs `SecretScrubber` (vault values and credential patterns) on the whole text first, keeps link labels and turns bare URLs into "[link]", strips Markdown and control, format (zero-width, bidi overrides), private-use and unassigned characters, keeps the first non-empty line and cuts it to 90 characters without splitting a surrogate pair. Home screen only (`widgetCategory="home_screen"`), not the lock screen.
- **Updates**: no polling and no periodic updates. The first state after the process starts replaces one left from before Android killed Locus mid-run; until Locus next starts, a widget can show "Working" from a run that died with the process (*accepted*).

## The intent API (for Tasker, Automate, scripts)

### Contract

Send an **activity** intent to Locus (package `dev.locus`; the debug build is `dev.locus.debug`), component `dev.locus.AutomationActivity`, or by action with the package set.

| Action | Extras | Runs |
|---|---|---|
| `dev.locus.action.START_CHAT` | `dev.locus.extra.PROMPT` (String, required, ≤ 20,000 chars); `dev.locus.extra.PROJECT` (project id or name, optional); `dev.locus.extra.SHOW_CHAT` (Boolean or "true", optional: also open the chat) | A new chat (in the project) with the prompt as its first message. |
| `dev.locus.action.RUN_WORKFLOW` | `dev.locus.extra.WORKFLOW` (id or name, required); inputs as `dev.locus.extra.PARAMS` (a JSON object of strings, numbers or booleans) and/or one `dev.locus.param.<name>` extra per input (these win on a clash); at most 50 inputs, 4,000 chars each | The saved workflow (#94) in a new chat in its project, with its declared tools and approval points. |
| `dev.locus.action.RUN_TASK` | `dev.locus.extra.TASK` (id or title, required) | The scheduled task now, read-only, as its schedule would run it. |

Every action also takes:

- `dev.locus.extra.REQUEST_ID` (String): echoed back.
- `dev.locus.extra.RESULT_INTENT` (PendingIntent): where the result goes.

Names match an id first, then a name only one item has (ignoring case); an ambiguous name matches nothing.

**Results.** When the task ends, Locus sends the caller's `RESULT_INTENT` with these extras filled in (the PendingIntent must be **mutable** for extras to arrive; an immutable one still fires, as a bare "it ended"):

| Extra | Value |
|---|---|
| `dev.locus.extra.STATUS` | `done`, `failed` or `refused` |
| `dev.locus.extra.RESULT` | The final answer, at most 32,000 characters |
| `dev.locus.extra.ERROR` | Why it failed or was refused |
| `dev.locus.extra.CHAT_ID` | The chat it ran in |
| `dev.locus.extra.REQUEST_ID` | As sent |

Locus sends it with background activity starts **denied** (`MODE_BACKGROUND_ACTIVITY_START_DENIED` on Android 14+), so the result can't be used to open screens; use a broadcast or service PendingIntent. Without a `RESULT_INTENT`, and only when Android named the calling package (it used `startActivityForResult`, or shared its identity), the result goes as a broadcast `dev.locus.action.RESULT` to that package only. Otherwise there is no result; the chat and its notification still show it. A request refused before it runs (bad extras, automation off, the user said no) gets `refused` the same way.

Example (adb, for testing; an app would build the same intent):

```
am start -n dev.locus/.AutomationActivity -a dev.locus.action.START_CHAT \
  --es dev.locus.extra.PROMPT "What's on my calendar tomorrow?"
```

### Who may ask

The research leaves no strong standard: a `signature` permission would shut out every automation app, a `dangerous` one "provide[s] little security" and is held by no app until its developers declare it (Termux's experience with Tasker), and Android rarely names the caller of an activity. So Locus layers what it can verify (`AutomationAccess.decide`, `AutomationTest`):

1. **Off by default.** Settings → Automation → "Let other apps start tasks", like Termux's `allow-external-apps`. While it's off, a request shows a sheet offering to turn it on (and then the confirmation below); nothing runs from the background.
2. **The caller is only ever who Android vouches for**: `getCallingPackage()` (callers using `startActivityForResult`) or `getLaunchedFromPackage()` (API 34+, callers that share their identity). Never a name written in the extras, and never the creator of the result PendingIntent: `getCreatorPackage()` says who made it, not who handed it over, so any app holding a mutable PendingIntent from an allowed app (automation apps give them out in notifications, widgets and plugin APIs) could otherwise run prompts as that app. The creator only decides where the result goes: with a named caller, a PendingIntent made by another app is never sent; with an unknown caller, the result goes back to the PendingIntent (which only ever reaches its creator) once the user has confirmed the request. A request with only a PendingIntent is an unknown caller: confirmed every time, never "Always allow" (`AutomationTest`, "a result PendingIntent's creator is never the caller").
3. **Confirm on first use.** A request from an app Locus can't name is shown in a sheet (who, what, the prompt or inputs) every time: Run once or Don't allow. A named app also gets **Always allow** and **Never**; the answers are kept per package in Settings → Automation, where Forget asks again next time.
4. **The permission** `dev.locus.permission.RUN_TASK` (`${applicationId}.permission.RUN_TASK`, `protectionLevel="dangerous"`, as Termux names its own) is the system-side grant: a named app that declares it and that the user granted it in App info → Permissions runs without the sheet. It is *not* required in the manifest, so apps that can't declare it (most automation apps today) can still ask, through the sheet. Locus checks at run time that it defined the permission itself (`getPermissionInfo(...).packageName`); if another app defined that name first, nothing runs.
5. **Sealed.** `automationEnabled` and the per-app answers are part of the policy seal (`PolicySeal`, docs/design/linux-isolation.md), so a Linux program editing the settings file can't allow an app; a broken seal turns automation off and keeps only the "Never" answers (`AutomationSealTest`).

"Never" wins over a granted permission; Locus refuses requests from itself.

Why an **activity** and not a broadcast receiver or a service: a run needs Locus's foreground service, which Android 12+ doesn't let Locus start while it's in the background (`AgentRunner.start`); the confirmation needs a screen; and a receiver's sender is rarely known. The activity is translucent, outside recents, with its own task affinity, so only its sheet shows over the calling app. The cost: an automation app in the background needs Android's permission to start activities (Tasker usually has "Display over other apps"; *unverified for Automate*). A Tasker plugin (EditActivity + FireReceiver) would avoid that, but its broadcast can be forged by any app, so it would need a per-configuration token checked by Locus; left for later.

### What runs, and taint

- Each request runs in a **new chat**, so another app never steers a conversation the user is in.
- The prompt (or the workflow's rendered goal, whose inputs came from the app) is the first user message, with a hidden note, `ModeNote.external(app, textFromApp = true)`: "Started by another app, “Tasker (net.dinglisch.android.taskerm)”, through Locus's automation intents. The text of this message came from that app, not from the user's typing…". It rides on the message, never the system prompt, so the cached prefix holds.
- That note **taints the run** (#72): `Taint.sourcesIn` adds "another app (…)" from the first call, so outward calls (`email_send`, `device_*`, posts, deploys) ask with "Tainted run: it has read outside content (from another app (Tasker…))", URL observations to hosts the user hasn't named ask, and memory, skill and connector saves ask. The rest of the chat stays tainted, as with any outside text in the history.
- The app's text is **never the user's words**: `trustedUserMessages` skips it, so its links don't become trusted hosts and the safety reviewer doesn't read it as the user's request. A compaction summary keeps it marked ("[sent by …, not typed by the user]") and carries the taint in `untrustedSources`.
- Approvals are the chat's normal ones (Allow / Ask / Deny, chat mode, project limits, the workflow's approval points), answered in the app or from the notification. An unanswered approval is a denial after 30 minutes, as always.
- `RUN_TASK` runs the user's own saved prompt, so its text stays the user's (`textFromApp = false`), but the run is still tainted: another app chose when it ran. It is read-only like every scheduled run, so tainted calls that would ask are refused instead.

Tests: `ExternalRunTaintTest` (core/agent): outward calls ask with the app named, the same text typed by the user doesn't, hosts in the app's text stay untrusted, read-only runs refuse, a saved task's text stays trusted, an app name can't close the note early, compaction keeps the mark.

## Phone integrations as tools

| Tool | What it does | Capability | Default |
|---|---|---|---|
| `calendar_read(days?, query?, limit?)` | Upcoming event occurrences from `CalendarContract.Instances` (recurring events expanded), up to 62 days and 200 events, in local time; all-day events by date. Asks for READ_CALENDAR on first use. | READ | Ask |
| `contacts_pick(field?, reason?)` | The system contact picker; returns the picked contact's name with the phones and/or emails it shares. No contacts permission. | READ | Ask |
| `photos_pick(max?, reason?)` | The Photo Picker (up to 20); copies the picked items into `inbox/` and returns their paths. | LOCAL (writes the workspace) | Ask |

- **Ask by default**: the user sees "Read your calendar for the next 7 days" or "Ask you to pick a contact: to text Ana the address" before anything opens. The pickers then share only what the user picks.
- **Only from the screen.** Permission prompts and pickers go through `PhoneRequests`, which `MainActivity`'s `PhoneRequestHost` shows while Locus is in front; from the background (a scheduled run, the screen off) the tool says "Locus isn't on screen" and nothing is asked. A request left open times out after five minutes.
- **Untrusted output.** Calendar text includes invitations other people wrote, so `calendar_read` wraps its list in `<untrusted_web_content>`, which taints the run. Contacts are the user's own address book and aren't marked.
- **Labels, accents, groups**: Phone apps group (switching it off for a chat covers them), the Device accent (clock icon for the calendar, image icon for photos), live activity lines ("Checking your calendar", "Waiting for you to pick photos"), and the `device` capability in skill manifests.
- **Found on demand, list fixed per chat.** They are deferred tools for chats with tool discovery (#87/#100) and `ToolDiscovery.DISCOVERY_ONLY`: a chat created before discovery keeps exactly the tools it had, so its declared list and cached prefix don't change. In discovery chats they join the "phone apps" group under its existing wording, and the `device` namespace on the OpenAI Responses API, so find_tools' description and the namespace lines are byte-identical; only the deferred definitions grow.

Tests: `DeviceIntegrationToolsTest` (core/data) with a fake phone: formatting and bounds, permission refused and not-on-screen, the picked contact per field, photos into `inbox/`, defaults and classification, the unchanged find_tools categories.

## Composer

The + sheet now offers **Photos** (the Photo Picker, up to 10), **Camera** (`TakePicture` into a file in `cache/camera/` shared through the existing FileProvider, then attached, which copies it into `inbox/` downscaled like any picked photo) and **Files** (`OpenMultipleDocuments`, as before). Old camera files are cleared after a day.

## Not done

- A full `VoiceInteractionService` assistant (session over the current app, hotword); the assist gesture opening voice mode once that exists.
- Widget: a lock-screen variant and generated previews (Android 15's `setWidgetPreview`).
- A Tasker/Locale plugin (needs a token per configuration, see above).
- `requestAddTileService` to offer the tile from inside Locus.
- Pinned shortcuts (`requestPinShortcut`) for a chat.
- AppFunctions, until callers outside Google's preview can use it.
