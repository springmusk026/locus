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
| The vault key check | The Android Keystore key behind the vault was invalidated | "The key protecting saved keys was invalidated" |

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

## An invalidated Keystore key

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

Keys are known to be invalidated after some OS updates and on some OEM builds. When that happens the
keyset build throws inside Hilt's `@Provides`, and every launch crashes.

`openVaultKey` wraps the build:
1. Try it. On a `GeneralSecurityException`, `ProviderException` or `IOException`, wait 200 ms and try
   once more. The Keystore can fail transiently, and resetting throws away every saved key, so one
   failure isn't enough.
2. If it fails again: delete the keyset entry, the Keystore alias and `secrets.enc`, which is
   unreadable without the key. Record the cause in Diagnostics, leave the "Enter your saved keys again"
   notice, and build again. That makes a fresh master key and keyset.
3. A failure after the reset is thrown. It isn't a key problem Locus can fix, and hiding it would hide
   a broken Keystore.

Other exceptions (programming errors) are never treated as key loss.

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
  - a vault encrypted under another key starts empty and keeps a copy;
  - the vault key opens without a reset, retries a transient failure, resets an invalidated key once
    and tells the user;
  - other errors, and a failure after the reset, are thrown.

## Not covered

- No self-test or storage screen yet (the rest of "Reliability tools").
- `ApplicationStartInfo` (API 35) and Android 17's `getPendingJobReasonStats()` aren't read.
- A native crash's tombstone isn't read (`REASON_CRASH_NATIVE` traces are protobuf); the entry has
  Android's description only.
