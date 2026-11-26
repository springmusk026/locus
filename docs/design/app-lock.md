# App lock and locked chats

Status: built 2026-11-21. Not yet tried on the phone. Code: `AppLock.kt` and `LockedNotifications.kt` (core/data, `lock`), `AppLock.kt` (core/model), `DeviceAuth.kt`, `LockScreen.kt` and `LockGates.kt` (core/ui), `AppLockSettings.kt` (feature/settings), `ChatLock.kt` (feature/chat), `MainActivity` and `AutomationActivity` (app).

Idea #1 of the [November feature research](../research/feature-opportunities-2026-11.md): ChatGPT shipped locked chats on 2026-10-20. Locus holds chats, files, a vault and SSH keys; someone handed an unlocked phone shouldn't be able to read them.

## What the user gets

- **Settings → About & privacy → Lock → Lock Locus.** Off by default. While it's on, Locus shows its lock screen (the app's mark, "Locus is locked", **Unlock**) instead of anything else, and asks with Android's own prompt: a fingerprint or face (Class 3), or the phone's PIN, pattern or password. Locus keeps no PIN of its own.
- **Lock after**: Now, 1 min, 5 min or 15 min in the background (default Now). Shown only while the lock is on.
- **Lock this chat**: in a chat's long-press menu on home and All chats, and the padlock button in the chat's top bar. Works with or without Lock Locus. A locked chat asks for authentication when it's opened, and its name and text are hidden elsewhere.
- **No screen lock**: the switch is disabled with "Needs a screen lock on this phone", and a caution banner explains why with **Open security settings** (`Settings.ACTION_SECURITY_SETTINGS`). Coming back re-checks.

## Decisions

- **Android's own lock, no app PIN.** Nothing to store, forget or brute-force inside Locus, and it works the same way as the phone's lock screen. The cost: it needs a secure screen lock.
- **No screen lock means the lock is suspended, not a lock-out.** If the user removes the phone's screen lock (which itself needs the old PIN), there is nothing left to authenticate with. Locus then opens without asking and Settings says so in a caution banner ("This phone has no screen lock now…"). The alternative, refusing to open, would only lock the owner out: anyone holding a phone without a screen lock can add one.
- **One unlock per session.** Authenticating at the lock screen, or at a locked chat, opens Locus and every locked chat until the session ends. The session ends when Locus has been in the background for the **Lock after** time, and when the process ends (it's never saved). With Lock Locus off, Lock after still sets how long locked chats stay open.
- **Locking a chat from inside it doesn't close it.** The chat screen remembers the session it was let into (`AppLockStatus.sessionKey`: a per-process id and the session's number), so it stays open until the session ends.
- **Turning the lock off asks first**; turning it on doesn't (and counts as this session's unlock, so the user isn't locked out the moment they switch it on). Removing a chat's lock from home while its name is hidden asks first too.
- **Class 3 biometrics only.** `BIOMETRIC_STRONG | DEVICE_CREDENTIAL`, not `BIOMETRIC_WEAK`: a phone whose only biometric is Class 2 face unlock asks for the PIN instead, as the Keystore would.
- **Stored in the settings DataStore**, as `AppSettings.appLock` (`enabled`, `lockAfterMinutes`, `lockedChats`: a set of chat ids). No Room change. Minutes rather than an enum, so an old or hand-edited value can't fail to parse. Locus backups include the settings file, so a restore brings the lock and the locked chats back; Android backup and device transfer carry nothing (`data_extraction_rules.xml`). Not sealed like permissions: the lock guards against a person holding the phone, not code running as Locus, and a tampered file falling back to the default ("off") would be no safer. A deleted chat leaves the set.
- **The model and tools are unchanged.** No prompt, tool list or tool behaviour changes, so prompt caches are untouched. A locked chat still appears in a new chat's `<recent_chats>` like any other: what the model reads is the model's business, the lock is about what the screen shows.

## Platform calls (no androidx.biometric)

Only framework APIs; no new dependency, no AppCompat or FragmentActivity. `DeviceAuthPath.forSdk` (core/model) picks the path, `PlatformDeviceAuthenticator` (core/ui) makes the call:

| Android | API | Call |
|---|---|---|
| 11 and later | 30+ | `BiometricPrompt.Builder.setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)`. No negative button: with the credential allowed, setting one is an error, and the prompt offers "Use PIN" itself. |
| 10 | 29 | `setDeviceCredentialAllowed(true)` (added in 29, deprecated in 30). Same rule about the negative button. |
| 8 to 9 | 26–28 | `KeyguardManager.createConfirmDeviceCredentialIntent(title, description)`, started for a result through an `ActivityResultLauncher`; `RESULT_OK` means confirmed, null means no secure lock screen. API 28's BiometricPrompt has no credential fallback and requires a negative button, so Android 9 uses the intent too. |

`KeyguardManager.isDeviceSecure()` (API 23) decides whether the lock is available; the app re-reads it each time Locus comes to the foreground and when Settings resumes. `USE_BIOMETRIC` is declared (a normal permission; it reads no biometric data).

Outcomes the lock screen shows (`DeviceAuthPlatform.outcomeForError`): a cancel, a timeout or `BIOMETRIC_ERROR_CANCELED` say nothing (the Unlock button is the way back); `BIOMETRIC_ERROR_LOCKOUT` (5 failed attempts, 30 seconds) says to wait or use the PIN; `BIOMETRIC_ERROR_LOCKOUT_PERMANENT` says biometrics are off until the PIN is used; `BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL` re-checks the screen lock (and suspends the lock); anything else says "Couldn't check that it's you". A single unrecognised finger is the prompt's own business (`onAuthenticationFailed`).

The prompt opens by itself once each time the lock screen appears (per session, or per chat in a session) when the activity is resumed; never again after a cancel, a rotation or a trip away.

Sources (read 2026-11-21):
- [BiometricPrompt.Builder](https://developer.android.com/reference/android/hardware/biometrics/BiometricPrompt.Builder): `setAllowedAuthenticators` API 30, "making it an error to also call setNegativeButton"; `setDeviceCredentialAllowed` added in 29, deprecated in 30; `setNegativeButton` "required" in API 36 and earlier unless the credential is allowed.
- [BiometricPrompt](https://developer.android.com/reference/android/hardware/biometrics/BiometricPrompt): API 28; `authenticate(CancellationSignal, Executor, AuthenticationCallback)`; the error codes above, `LOCKOUT` "after 5 failed attempts, and lasts for 30 seconds"; requires `USE_BIOMETRIC`.
- [BiometricManager.Authenticators](https://developer.android.com/reference/android/hardware/biometrics/BiometricManager.Authenticators): API 30; `BIOMETRIC_STRONG` 0x0f (Class 3), `BIOMETRIC_WEAK` 0xff (a superset), `DEVICE_CREDENTIAL` 0x8000.
- [KeyguardManager](https://developer.android.com/reference/android/app/KeyguardManager): `createConfirmDeviceCredentialIntent` added in 21, deprecated in 29, "null if no password is required"; `isDeviceSecure` API 23, PIN, pattern or password, "as opposed to swipe or none".
- [Notification.Action.Builder.setAuthenticationRequired](https://developer.android.com/reference/android/app/Notification.Action.Builder#setAuthenticationRequired(boolean)): API 31, "If this is true and the device is locked when the action is invoked, the OS will show the keyguard and require successful authentication before invoking the intent."
- [Notification](https://developer.android.com/reference/android/app/Notification#VISIBILITY_PRIVATE): `VISIBILITY_PRIVATE` "conceal sensitive or private information on secure lockscreens"; `publicVersion` is shown "atop a secure keyguard". [Create a notification, lock screen visibility](https://developer.android.com/develop/ui/views/notifications/build-notification).
- [Activity.setRecentsScreenshotEnabled](https://developer.android.com/reference/android/app/Activity#setRecentsScreenshotEnabled(boolean)): API 33; affects only the Recents picture, unlike FLAG_SECURE.

## Every way in goes through the lock

The lock wraps `MainActivity`'s content (`AppLockGate`), so everything that opens it is covered: the launcher, Share to Locus (text, links, files, Direct Share targets), "Ask Locus" on selected text, the assistant gesture, launcher shortcuts (New chat, Voice, Workspace), the quick settings tile, widget taps, notification taps (a chat, a task result, a reminder, Updates), and the browser sign-in return. The intent waits (the activity's replayed `SharedFlow`) and is handled after the unlock, so a share still lands in its chat.

While locked, nothing behind the lock screen is composed. Its saved state (the back stack, scroll positions, drafts) is kept by a `SaveableStateHolder` and comes back on unlock; screen view models may be rebuilt.

Locked chats are gated at the chat route (`ChatLockGate`), so a chat opened from home, search, a notification, a shortcut, a share, a project or Analytics asks. Activity opened on one chat (from a task receipt) is gated the same way.

**Automation intents** (`AutomationActivity`): a request that needs the user (confirm, "turn automation on", always/never) shows the lock screen first, because the sheet changes settings; its back button declines. A request that runs without asking (the caller is allowed) shows nothing and keeps working while Locus is locked, and its result still goes back through the caller's PendingIntent. A START_CHAT that asks to open the chat opens `MainActivity`, which shows the lock.

The lock protects what Locus shows on the phone, not background work the user configured: runs keep going (AgentService is unaffected), scheduled tasks and triggers run, Telegram remote keeps answering in the bot chat the user set up, and automation that runs without a screen keeps running.

## Recents and screenshots

While the lock screen shows, the window is secure (`SecretContent()`, the [secrets on screen](design-system.md#secrets-on-screen) mechanism): no screenshots, nothing in Recents.

The Recents picture is taken as Locus leaves the screen, before the lock engages, so with Lock Locus on:
- Android 13+: `setRecentsScreenshotEnabled(false)`. Recents shows the window background; the user's own screenshots still work.
- Android 8 to 12: no such call, so the whole window is FLAG_SECURE while the lock is on (screenshots inside Locus are blocked too). Turning the lock off restores them.

A trip out that Locus starts itself and expects back from (its own credential prompt, the photo, contact and file pickers, a permission prompt) doesn't lock it with "Now", if it's back within 2 minutes (`LockAfter.EXPECTED_RETURN_GRACE_MS`). Other trips (a browser sign-in, opening a file in another app, a share sheet) count as leaving.

## Notifications

`LockedNotifications` holds the rules, used by AgentService (status, approval and question notifications), TaskWorker (task results) and reminders:

- **With Lock Locus on**, each notification is `VISIBILITY_PRIVATE` with a public version that says only "Locus" and "Unlock your phone to see details". On a secure lock screen that is all that shows. The shade on an unlocked phone shows the full text: Locus's own lock covers its screens, not the phone's shade.
- **With Lock Locus on**, every action (Deny as well as Allow, answers, Reply, Stop and Continue) has `setAuthenticationRequired(true)` on Android 12+, so the keyguard comes first on a locked phone. Below Android 12 that call does nothing, so the actions are left off: the tap opens Locus, which shows its lock.
- **Allow and Continue, lock on or off,** have `setAuthenticationRequired(true)`, and are offered only on Android 12+ (`LockedNotifications.actionOffered`). Below Android 12 nothing can make the system ask for the unlock first ([`NotificationCompat.Action.Builder.setAuthenticationRequired`](https://developer.android.com/reference/androidx/core/app/NotificationCompat.Action.Builder#setAuthenticationRequired(boolean)): "From API 31"), so anyone holding a locked phone could approve from the lock screen. There the approval keeps Deny, a budget pause keeps Stop, questions keep their answers (they aren't permissions), and the tap opens Locus to allow or continue.
- **Needs you's** one notification counts a locked chat's proposals but never describes them; it has no actions ([needs you](needs-you.md)).
- **A locked chat's** approval, question, budget pause or scheduled result is never described, lock on or off: the notification says "A locked chat is waiting for you. Open Locus to see it." and offers no actions. A reminder set in a locked chat says "Reminder from a locked chat". Until the lock's settings have been read in a fresh process, a reminder from any chat errs on the side of saying less.
- The widget never named chats (counts and fixed phrases only). With Lock Locus on it also drops the latest task result line, a model's words on the home screen. The usage widget shows only amounts and token counts, the same with the lock on or off ([usage widget](android-bridge.md#the-usage-widget)).
- The "questions waiting" banner inside Locus counts a locked chat but never names it, even once the session is unlocked ([approvals waiting](approvals-waiting.md)).
- **Launcher and Direct Share shortcuts**: a locked chat is never one, and with Lock Locus on there are no chat shortcuts at all (the launcher's long-press menu and the share sheet would show chat names to anyone). The static ones (New chat, Voice, Workspace) stay; they open Locus, which asks.

## Inside Locus

A locked chat this session hasn't unlocked:
- **Needs you** on home: a locked chat's approval, question, proposal or result keeps its place as "Locked chat" with a padlock and its state, nothing else; the tap opens the chat, which asks ([needs you](needs-you.md)).
- **Home, All chats, Recent chats, the active-chat card, projects**: keeps its place and state (working, waiting), shown as "Locked chat" with a padlock instead of its model's logo. The view model blanks the title, so nothing can show it by mistake. Rename is hidden; Delete says "Locked chat".
- **Search**: left out entirely, even a match on its name, so a search can't reveal that a locked chat contains a word.
- **Activity**: its steps (and its helpers') are left out of the list. **Analytics**: listed as "Locked chat".
- Once the session is unlocked it shows normally, still with the padlock.

## Not covered

- What the agent itself can read: a run in another chat can still look up recent chats or memory as before (point 5 above: no tool changes).
- Telegram remote messages, set up by the user to go to their own bot chat.
- Scheduled tasks' own titles and their latest result on home and in Tasks belong to the task, not to a chat; a task's run chat can be locked, and then its result notification is hidden.
- The shade on an unlocked phone (see Notifications), and Telegram approvals.

## Tests

- `AppLockTest` (core/model): Lock after timing (now, 1, 5, 15 min, a clock going backwards, an expected trip), what's locked and redacted, settings round trip, the authenticator path per API level.
- `AppLockTest` (core/data): saving through the settings store, a new process starting locked, background past the limit locking, an expected trip, no screen lock suspending and setting one re-enabling; notification action rules, the status notification's locked-chat redaction, shortcuts, the widget.
- `DeviceAuthTest` (core/ui): the authenticators constant and the error mapping. `LockScreenScreenshots`: app, prompting, lockout, permanent lockout, failure, cancelled, locked chat.
- `LockedChatsListTest` (feature/chat): redaction in the list, search leaving locked chats out, no screen lock, delete. `HomeScreenshots`: locked rows on home and in All chats (a row's menu is a popup the screenshot steps can't settle, so the Lock and Unlock items aren't drawn).
- `LockedActivityTest` (feature/activity), `AppLockSettingsTest` (feature/settings: the switch disabled without a screen lock) and `SettingsScreenshots` (the Lock section off, on, without a screen lock, suspended, locked out).

On the phone, still to check: the prompt on Android 10, 11+ and 8–9; Recents on 13+ and below; notification actions on a locked phone; a share and a notification tap while locked; the photo picker with "Now".
