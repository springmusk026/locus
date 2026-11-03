# Diagnostics: local crash and exit log, storage recovery

Locus keeps a small local log of why it crashed, stopped or had to reset something, so "it closed by
itself" can be explained without any cloud crash reporting. It is the start of the roadmap's
"Reliability tools" and comes from the November audit ([#10](../research/reliability-security-audit-2026-11.md#10-no-crash-or-exit-history-uncaught-paths)).
Nothing leaves the phone unless the user taps Share.

## What is recorded

`DiagnosticsLog` (core/datastore) writes one text file per entry to `noBackupFilesDir/diagnostics/`. That
folder is app-private, never in a backup, and capped at the 30 newest entries of 32,000 characters
each. Writes are plain synchronous file I/O, so they work from a dying process and before Hilt exists.

| Source | When | Entry |
|---|---|---|
| `CrashRecorder` | The default uncaught-exception handler, installed first thing in `LocusApplication.attachBaseContext` | "Crash: IllegalStateException": app and Android version, device, thread, the full stack trace with causes |
| `DiagnosticsRepository.recordExitsSinceLastStart` | Each start, off the main thread (API 30+) | ANRs (with the first 24 KB of the trace), native crashes, kills for low memory or excessive resource use, start-up failures, from `ActivityManager.getHistoricalProcessExitReasons`, newer than the last check |
| The app scope's `CoroutineExceptionHandler` | An exception no coroutine in the app scope caught | "Background error: …" with the stack. Logged to logcat too; the process keeps running |
| DataStore corruption handlers | The settings or vault file couldn't be read | "Settings couldn't be read and was reset", with the cause |
| `ProviderErrorLog` (wraps every model from `ProviderAccess.chatModel`) | A model request failed with a body the chat doesn't show whole: a gateway's HTML page, a long dump, a JSON error the message quotes | "Model provider error: OpenAI HTTP 504": provider, model, status, the line the chat showed, and the body as received (up to 2,000 characters). The same body from the same provider is kept once per 10 minutes, so retries don't fill the log. Also written to logcat. The chat shows plain words instead (`ProviderErrorText`). Responses-stream failures (`response.failed`, `error`, an empty `response.incomplete`) are kept too, as a scrubbed copy of the event with the error code, request id and response id (docs/design/provider-failures.md) |
| The vault key check (`VaultKeys`) | The vault's Keystore key failed (once per start), the user reset it, or a set-aside copy was restored | "Saved keys couldn't be opened", "Saved keys were reset", "Saved keys were recovered" |

`CrashRecorder` writes, then always calls the previous handler, which shows the system dialog and ends
the process as before. Java crashes aren't copied again from the exit history, since the recorder
already has them with the stack. Exits the user or Locus asked for are left out.

## Scrubbing

There are two passes:
1. **At write time**, crash reports, coroutine errors and exit texts go through `SecretScrubber`'s
   credential patterns: `Bearer …`, `x-api-key: …`, `api_key=…`, `password=…`, `sk-…`, GitHub, Slack and
   Google keys. The vault isn't read from a dying process.
2. **At show and share time**, `DiagnosticsRepository` scrubs every entry again with a `SecretScrubber`
   built from the vault's values. That covers the parts of structured values too, such as an MCP
   session's tokens or the password in a URL. A key that doesn't look like one is still caught here.

Share hands the scrubbed text to the system share sheet, and only when the user taps it. Clear
deletes every entry.

## The app scope

`DataModule.applicationScope` is `SupervisorJob() + Dispatchers.Default + appScopeErrors { … }`.
Before, a throw in startup recovery, a helper's report or a resume ended the process. Now it is logged
and recorded, and the scope keeps running its other work.

## DataStore corruption

Both DataStores have a `ReplaceFileCorruptionHandler` (`corruptionHandler` in `StoreRecovery.kt`):
- **Settings:** an unparsable `settings.json` is copied to `settings.json.corrupt` and replaced with
  the defaults. Providers then need adding again; their keys are still in the vault.
- **Vault:** a `secrets.enc` that doesn't decrypt (the datastore-tink `AeadSerializer` turns a
  `GeneralSecurityException` into a `CorruptionException`, see
  [AeadSerializer.kt](https://raw.githubusercontent.com/androidx/androidx/androidx-main/datastore/datastore-tink/src/commonMain/kotlin/androidx/datastore/tink/AeadSerializer.kt))
  is copied to `secrets.enc.corrupt` and replaced with an empty vault.

Each one records a Diagnostics entry and leaves a notice. The next time Locus is on screen it shows the
notice once ("Enter your saved keys again" or "Settings were reset"). Before, DataStore's
`CorruptionException` reached every reader of the store.

## An unusable Keystore key

The vault's Tink keyset is stored in SharedPreferences (`locus_tink`), wrapped by the Android Keystore
key `locus_secrets_master`. Tink's `AndroidKeysetManager` ([tink-java 1.24.0](https://github.com/tink-crypto/tink-java/blob/v1.24.0/src/main/java/com/google/crypto/tink/integration/android/AndroidKeysetManager.java))
behaves like this:
- If the keyset can't be decrypted, "a `GeneralSecurityException` would be thrown. This is an
  irrecoverable error. You'd have to delete the keyset in Shared Preferences and all existing data
  encrypted with it."
- If the master key "exists but is unusable", it throws a `KeyStoreException`, and does not make a new
  key, "because there might be existing encrypted data under the unusable key".
- If getting the master key fails, it tries to read the keyset as cleartext and rethrows when the
  keyset is encrypted. If the master key doesn't exist yet and can't be made, it quietly turns
  Keystore off and stores a new keyset in cleartext. That is Tink's choice for unreliable Keystores;
  Locus doesn't change it.
- The class itself warns that "Android Keystore is unreliable" on some devices.

Keys are known to be invalidated after some OS updates and on some OEM builds. The Keystore can also
fail for a while and then work again: before the first unlock after a restart, during a keystore
daemon hiccup, or because of a vendor bug. Tink's own `AndroidKeystoreAesGcm` waits and retries once
"a potentially transient KeyStore error" for that reason.

A first version (#77) retried once after 200 ms and then deleted the keyset, the Keystore key and
`secrets.enc`. A passing failure could therefore wipe every saved key for good. `VaultKeys`
(core/datastore) replaces it, and **never deletes anything on its own**.

### What the platform allows

- **Tink.** `AndroidKeysetManager.Builder.withSharedPref(context, keysetName, prefFileName)` reads the
  keyset as a hex string from one SharedPreferences entry (`readKeysetFromPrefs`: `getString`, then
  `Hex.decode`). So a keyset can be set aside by copying that string to another entry name, and opened
  again later by building with that name. If the entry is missing, `build()` makes a new keyset (and a
  master key if the alias has none), so Locus only builds a set-aside copy whose entry exists.
  `AndroidKeystoreKmsClient` has `deleteKey` and no rename.
- **Android Keystore.** `java.security.KeyStore` has no rename, and an Android Keystore key can't be
  exported to import it again under another name: its material never leaves secure hardware (Android
  Keystore system docs). So the old alias is **kept as it is**, and the new key gets a new alias
  (`locus_secrets_master.<ms>`). The current alias is stored in `locus_tink` (`locus_vault_master_alias`).
- **Lock state.** `UserManager.isUserUnlocked()`: "On devices with direct boot, a user is unlocked only
  after they've entered their credentials … When a user is locked, only device-protected data storage
  is available" (AOSP). `Intent.ACTION_USER_UNLOCKED` is sent when that changes.

### Telling temporary from permanent

`classifyKeyFailure` looks at the exception and its causes:

| Exception | Meaning |
|---|---|
| `KeyPermanentlyInvalidatedException` (extends `InvalidKeyException`) | "The key can no longer be used because it has been permanently invalidated" (AOSP javadoc): the secure lock screen was removed or reset, or biometrics changed for an auth-bound key. **Permanent** |
| `AEADBadTagException` / `BadPaddingException` | The keyset doesn't decrypt under the master key, so another key wrapped it. tink-java 1.24.0's `AndroidKeystoreAesGcm` rethrows it without a retry: "ciphertext is invalid. There is no point in retrying." **Permanent** |
| The keyset exists but `KeyStore.containsAlias(alias)` is false | The Keystore key is gone (a restore onto another phone, a wiped Keystore). **Permanent** |
| `android.security.KeyStoreException` (API 33+) with `isTransientFailure()` or `requiresUserAuthentication()` | "Performing the same operation again at a later time is likely to succeed", or "the device being locked". **Temporary** |
| The same with `ERROR_KEY_DOES_NOT_EXIST` or `ERROR_KEY_CORRUPTED` ("could not be recovered") | **Permanent** |
| `UserNotAuthenticatedException` | **Temporary** |
| Anything else: Tink's "the master key … exists but is unusable" `KeyStoreException`, a `ProviderException`, a vendor error | **Unknown** |

Exceptions that aren't `GeneralSecurityException`, `ProviderException` or `IOException` (programming
errors) are thrown, never treated as key loss.

### Opening

`VaultKeys` opens the key lazily, on the first vault read or write, and at start from the app (off the
main thread):
1. **Before the first unlock** (`isUserUnlocked` false), nothing is tried or counted, and a reset is
   refused. The state is `Locked`, and a receiver for `ACTION_USER_UNLOCKED` tries again.
2. A finished-but-interrupted reset is completed (see below).
3. Build the keyset. A key failure is retried once after 200 ms.
4. If that fails, the vault is **closed and nothing changes**. One Diagnostics entry and one count are
   recorded per start (`locus_vault_failed_starts`). A permanent failure, or failures on **3 separate
   starts**, ask the user (`NeedsReset`). Otherwise the state is `Unavailable`, and the next vault use
   tries again (at most every 30 s). A success clears the count.

While the vault is closed:
- `SecretStore.get`, `put` and `exportAll` throw `VaultUnavailableException`. It is an `IOException`,
  not DataStore's `CorruptionException`, so the vault's corruption handler never runs over the file.
  `SecretStore` checks the key before it touches the DataStore, so nothing is read or cached.
- `names` is empty and follows the state, so "key saved" comes back once the vault opens.
  `valuesForScrubbing` is empty, since nothing can be read.
- `ProviderAccess.missing` returns the reason: "Locus can't open your saved keys right now…", or
  "…open Locus to reset them". A chat, helper, sub-agent or onboarding check shows that, not "no API
  key". Nothing crashes.

### Asking, and resetting

When the state is `NeedsReset`, the app shows a sheet ("Can't open saved keys"): "Locus can't open your
saved keys. This can happen after changing the lock screen or restoring the phone. Reset and re-enter
keys? (A copy is kept in case it can be recovered.)" It has **Reset** and **Not now**. Not now, or
swiping the sheet away, leaves the vault closed for this session. The sheet comes back at the next
start.

`VaultKeys.reset()` runs only from Reset:
1. In one SharedPreferences commit:
   - the keyset entry moves to `locus_secrets_keyset.invalid.<ms>`;
   - the new alias is recorded;
   - the copy is added to `locus_vault_set_aside` as "`<ms> <old alias> pending`";
   - the failure count is cleared;
   - the vault file's move is marked pending.
2. `secrets.enc` is renamed to `secrets.enc.invalid.<ms>`, and the pending mark is cleared. If the app
   dies between steps 1 and 2, the next start finishes the move before it opens anything. If the
   rename fails, the vault stays closed, so the old file is never read with the new key.
3. The old Keystore alias is kept.
4. Only the newest **3** copies are kept. Older ones lose their keyset entry, their vault file, and
   their alias if nothing else uses it. This happens only inside a reset the user agreed to.
5. The key is opened again, which makes a new keyset under the new alias, and the vault starts empty.

### Recovering a copy

At each start, once the current key is open, every copy still `pending` is tried, newest first. Each
is opened with its own keyset entry and its old alias, and its vault file is decrypted. If that works
(the failure was temporary after all):
- its keys are merged into the current vault (keys entered since the reset win);
- the file is written through a temporary file and a rename;
- the copy is marked `recovered`, so it isn't merged again;
- an entry and a notice are recorded ("…restored them. Keys you entered since were kept").

Locus merges into the new key rather than switching back to the old one. A key that failed once may
fail again, and merging keeps what the user entered after the reset.

## Tests

- `DiagnosticsTest` (core:data):
  - a crash report has no bearer token, `sk-` key, password or `x-api-key` value, but keeps the stack
    and causes, and the previous handler runs, even when the log can't be written;
  - Diagnostics hides a vault value that matches no pattern, both shown and shared, and Clear empties
    the log;
  - exits are filtered by time and kind;
  - the app scope records an error and stays usable;
  - the log keeps only the newest 30 entries, and notices stay until dismissed.
- `StoreRecoveryTest` (core:datastore):
  - corrupt settings reset to the defaults, keep a copy, record an entry and leave a notice, and can be
    written again;
  - a vault encrypted under another key starts empty and keeps a copy.
- `VaultKeysTest` (core:datastore), with a fake Keystore, SharedPreferences and keyset manager:
  - a working key opens, and a passing failure is retried within the start;
  - a failure closes the vault and changes or deletes nothing:
    - the keyset, the aliases and the vault bytes are unchanged;
    - `SecretStore` reads and writes throw `VaultUnavailableException`, with no names and nothing to
      scrub;
  - before the first unlock nothing is tried, counted or reset, and the vault opens at unlock;
  - a key that's gone for good (a bad tag, or a missing alias) asks the user at once and changes
    nothing; unexplained failures ask only on the third separate start, and more tries in one start
    count once;
  - the confirmed reset:
    - moves the keyset entry and the vault file aside with the same bytes;
    - keeps the old alias and makes a new one;
    - leaves a working, empty vault;
  - after four resets, three copies are left, and only the oldest copy's alias was deleted;
  - a copy whose old key works again is merged back at the next start (keys entered since win), with
    a notice, and only once;
  - programming errors are thrown, and failures are classified from their causes.
- `VaultFileTest` (core:datastore): the self-test decrypts the vault file afresh and reports counts,
  never names or values; a file another key wrote fails even though the store's cached copy reads;
  a closed vault is reported and left alone.
- `SelfTestAndStorageTest` (core:data): each check's pass, warning and failure with its fix; sizes
  don't count what a link in the workspace or rootfs points at; Clear caches keeps Chromium's folders
  and anything touched in the last 10 minutes, and removes a link without following it; Empty trash
  deletes every entry and nothing a link inside one points at.
- `FileTreesTest` (core:model): `size` counts regular files only and never follows a link (a root
  that is a link is 0).

## Self-test

Settings → About & privacy → Diagnostics → Self-test (`SelfTest` and `SelfTestRules` in core/data,
`SelfTestSettings` in feature/settings). The local checks run as the page opens, one after another,
each row showing waiting, checking, then passed, warning, failed or skipped with its fix. Run again
repeats them; a fix's Try again repeats one. Nothing is recorded, shared or given to a model.

| Check | How | Pass | Warning | Failure | Fix |
|---|---|---|---|---|---|
| Keystore and saved keys | `SecretStore.selfCheck`: opens the key (`VaultKeys`), encrypts and decrypts a probe in memory with the vault's own `VaultFormat`, then decrypts `secrets.enc` afresh (the DataStore's copy is cached, so it wouldn't notice a file that no longer opens). Only counts come back | Opens, and N saved values decrypt | Before the first unlock (`Locked`) | `Unavailable`, `NeedsReset`, the probe fails, or the file doesn't decrypt | Try again; Reset keys for `NeedsReset` (the existing confirmed reset, in a confirmation sheet; a copy is kept) |
| Database | `DatabaseCheck.quickCheck`: `PRAGMA quick_check(20)` on a reader connection. sqlite.org: quick_check "does most of the checking of the full integrity_check pragma but runs much faster", and returns "ok" or one row per problem | "ok" | | Any problem (the first three are shown, with a nudge to save a backup), or the check couldn't run | Try again when it couldn't run |
| Free storage | `StatFs` on the files directory, with Linux health's thresholds | 2 GB or more | Under 2 GB | Under 500 MB | Free space (the Storage screen) |
| Notifications | `areNotificationsEnabled`, and `POST_NOTIFICATIONS` on Android 13+ | On | Off: approvals and questions wait unseen while Locus isn't on screen | | Turn on: `ACTION_APP_NOTIFICATION_SETTINGS`, falling back to the app's details page |
| Linux | When installed: `LinuxProbe.report()` (host-side reads, a disk walk without following links, one `echo` through proot) and `LinuxHealth.checks`, the same as the Linux health screen, summed up as its worst level and headline | All of its checks pass | Its worst is a warning | Its worst is a problem, or the probe couldn't run | Open Linux (its screen has a fix per check). Skipped when Linux isn't installed |
| Each cloud provider (opt-in) | `ProviderAccess.missing`, then `ProviderAccess.listModels` (20 s limit) | Lists its models | It answered but has no model list (404, 405): chats may still work | No key or sign-in (nothing is sent), the key refused (401, 403), or no answer | Open Models; Try again |

**Battery optimisation isn't checked.** Locus doesn't rely on an exemption: a user's task runs in a
`specialUse` foreground service started while Locus is on screen, and scheduled runs are regular
WorkManager jobs designed to fit their quota without one ([scheduled runs](scheduled-runs.md)). A
check that nags about a setting nothing needs would be noise.

**The provider check is opt-in** (a switch, off each time the page opens) because it reaches the
network. It sends exactly what Settings → Models' model list sends: one request to the provider's own
base URL, through that provider's own HTTP client (its proxy, CAs and headers), with its own key or
ChatGPT sign-in. A provider whose address was changed outside Locus, or that has no key, is reported
without sending anything. Error text is scrubbed with the vault's values and the credential patterns
(`SecretScrubber`) before it's shown, so a key echoed in a URL or body never reaches the screen. No
key goes anywhere but its provider.

## Storage

Settings → About & privacy → Diagnostics → Storage (`StorageUsage` and `StorageMeter` in core/data,
`StorageSettings` in feature/settings) lists each part of Locus with its size, against the phone's
free and total space:

| Area | What's measured |
|---|---|
| Chats and history | `databases/locus.db` with its `-wal` and `-shm` |
| Workspace | `files/workspace` |
| Workspace trash | `files/workspace-trash` |
| Linux | `files/linux` (the distro, its `/tmp`, service logs and records) |
| On-device models | `files/models` |
| Browser data | `app_webview` (the agent browser's profile) and Chromium's folders in the cache |
| Caches | the rest of the cache folder |
| Logs | `no_backup/diagnostics` |
| Memory, skills and settings | `files/agent-home`, `no_backup/memory-index`, `files/datastore` |

**Never following links.** Every size is `FileTrees.size` (core/model): `Files.walkFileTree` without
`FOLLOW_LINKS`, so a link reads as a link (it counts as nothing) and is never entered. The workspace
and the rootfs are written by programs Locus doesn't control, and a link there can name any host path
([linux-isolation](linux-isolation.md)); without this, `/workspace/x -> /data/data/dev.locus` would
make the workspace look as big as Locus and walk Locus's private files. Measuring runs on the IO
dispatcher and checks for cancellation between files, so leaving the page stops the walk.

**Clean-ups, each confirmed in a `ConfirmSheet` first:**

- **Clear caches** deletes the cache folder's entries except Chromium's own (they're browser data,
  managed by the WebView) and anything modified in the last 10 minutes, which may be in use (a
  backup's scratch copy, a photo being taken, a remote sync). Deletion is `FileTrees.delete`. What's
  there is re-made when needed: exported chats, the models.dev catalogue, LiteRT's compiled caches.
- **Empty trash** deletes every workspace trash entry with `FileTrees.delete` (a trashed folder can
  hold links Linux made). The sheet says it can't be undone and that Undo stops working for earlier
  changes whose old version was there; `ChangeRepository` already reports those as "no longer in the
  workspace trash".
- **Linux** and **On-device models** open their managers (the Linux health screen, with its own cache
  clean-up; Settings → Models). Logs are cleared on the Diagnostics page.

The page shows a spinner while it first measures, an error with Try again if measuring failed, and
"Measuring again…" with the previous figures while it re-measures after a clean-up.

## Deep links

`SettingsDestination.SelfTest` and `Storage` are "Settings → About & privacy → Diagnostics →
Self-test" and "… → Storage" in text; back from them goes to Diagnostics.

## Not covered

- `ApplicationStartInfo` (API 35) and Android 17's `getPendingJobReasonStats()` aren't read.
- A native crash's tombstone isn't read (`REASON_CRASH_NATIVE` traces are protobuf); the entry has
  Android's description only.
