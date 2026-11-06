# Opt-in update check

Status: built 2026-11-03 (roadmap "Opt-in update check", [product D4](../research/product-platform-2026.md#d-distribution)). Not yet tried on the phone. Code: `core/data/.../updates/`, the About section in `feature/settings/UpdatesSection.kt`, the setup toggle in `feature/onboarding/OnboardingScreen.kt` (privacy step).

Locus is sideloaded from GitHub Releases, so nothing tells a user that a new version is out. This adds a check the user opts into, and an install path that ends in Android's own installer prompt.

## What the user sees

- **Setup**, privacy step ("What leaves your phone"): one switch, **Check GitHub for new versions?**, off. Onboarding gains no step. The subtitle says it bypasses app-store checks, which is the consent F-Droid and IzzyOnDroid ask for (below).
- **Settings → About & privacy → Updates**, first on the page: the same switch, and **Check now**, which works with the switch off. The status line says "Up to date (0.1.4) · checked 5 minutes ago", "0.1.5 available", "Waiting for Wi-Fi to check", and so on. "What leaves this phone" gains an "Update check" row.
- **A newer release**: one low-importance notification per version (channel "App updates"; tapping it opens About), and an **Update available: 0.1.5** card with **Download and verify**, **Skip this version** and **Release page**. The release notes are rendered by the app's Markdown renderer (`core/markdown`, `documentTypography(compact = true)`) under "What's new", expanded.
- **Download**: a progress meter with "12.3 of 17.1 MB" and **Cancel**, then "Checking the download's SHA-256 and signature…", then **Install update**. That hands the APK to Android, which asks the user to confirm. If they confirm, the app is replaced and restarts.
- **Skip this version**: the card is replaced by "You skipped 0.1.5…" with **Show 0.1.5 again**. No notification is posted for it. The next version is offered normally.

States: off, idle (never checked), checking, up to date (including "no release published yet"), available, skipped, downloading, verifying, ready to install, installing (waiting on Android), and each failure below. They are `UpdateState` and `UpdateProblem` in `Updates.kt`.

| Failure | What it says | Action |
|---|---|---|
| No connection | Couldn't reach GitHub | Try again |
| Rate limited | GitHub's limit for this network is used up, try after 14:05 | Dismiss (no request is made before the reset) |
| GitHub error or unreadable answer | HTTP code | Try again |
| No APK for the phone's ABIs | Lists the ABIs | Release page |
| No checksum, or the checksum file and GitHub's digest disagree | Locus won't install it | Release page |
| Checksum mismatch | The download was deleted | Try again |
| Signed with another key | Android won't install it over this Locus. Back up, uninstall, install, restore | Open Backup & restore (`SettingsActionFor`) |
| Not a newer Locus | The download was deleted | Release page |
| "Install unknown apps" off | Allow Locus to install apps | Open setting (comes back to Ready when allowed) |
| Cancelled in the installer | The verified download is kept | Install again |
| Not enough space | Free some up | Try again |
| Any other installer failure | Android's message | Try again |

## Choices and sources

**One unauthenticated GET to the latest-release endpoint.** `GET https://api.github.com/repos/springmusk026/locus/releases/latest` returns "the most recent non-prerelease, non-draft release" ([GitHub REST: releases](https://docs.github.com/en/rest/releases/releases#get-the-latest-release)). Drafts and pre-releases are also ignored on our side, in case that ever changes. The request sends `Accept: application/vnd.github+json`, `X-GitHub-Api-Version: 2022-11-28` and a fixed `User-Agent: Locus-update-check` (GitHub requires one). It sends no token, no cookies and no version or device details. GitHub sees the IP address, as it would for any request. No Locus server is involved.

**ETag and rate limits.** Unauthenticated REST calls get 60 requests an hour per IP address. When they run out, GitHub answers 403 or 429 with `x-ratelimit-remaining: 0`. Clients should wait for `retry-after` seconds when it's sent, or else until `x-ratelimit-reset`, and wait at least a minute otherwise ([rate limits](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api)). `ReleaseSource.rateLimit` follows that order, and `UpdateCache.rateLimitedUntil` stops any request, Check now included, before the reset. The last `ETag` goes out as `If-None-Match`, and a 304 reuses the cached release ([best practices](https://docs.github.com/en/rest/using-the-rest-api/best-practices-for-using-the-rest-api)). GitHub says a 304 doesn't count against the limit only for authorized requests. So for Locus the ETag saves bandwidth, not quota. Once a day is far below the limit either way.

**Daily, unmetered, through WorkManager.** `UpdateSchedule`: periodic work every 24 hours with a 4-hour flex window, `NetworkType.UNMETERED` ("Wi-Fi (UNMETERED)") and battery-not-low ([define work](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)). It's unique work enqueued with `ExistingPeriodicWorkPolicy.UPDATE`, which "preserves enqueue time" ([ExistingPeriodicWorkPolicy](https://developer.android.com/reference/androidx/work/ExistingPeriodicWorkPolicy)). So `Updates.start()` at every app start can re-assert the schedule from the setting without pushing the next check back, and a restored backup or onboarding's toggle takes effect. The worker always succeeds: a failed check waits for the next day.

**Comparing versions.** Tags are `v<versionName>` (the release workflow enforces it; [RELEASING.md](../RELEASING.md)), so the tag is compared with the installed `versionName` as a semantic version ([semver §11](https://semver.org/#spec-item-11)): numbers part by part, a pre-release below its release, build metadata ignored. Unreadable names are never newer. The release API carries no versionCode, so the code is checked on the downloaded APK instead (below).

**The phone's APK.** Each release has one APK per ABI, `locus-v<version>-<abi>.apk` ([RELEASING.md](../RELEASING.md#version-codes-and-apks)). `ReleaseFiles.apkFor` walks `Build.SUPPORTED_ABIS`, "an ordered list… the most preferred ABI is the first element" ([Build](https://developer.android.com/reference/android/os/Build#SUPPORTED_ABIS)), and takes the first ABI the release has. A 32-bit-only phone gets "no APK for this phone".

**SHA-256.** The release workflow already attaches `SHA256SUMS.txt` (`sha256sum *.apk *.txt`), so `release.yml` needed no new step, only a comment saying the app reads that file by name. Since June 2025 GitHub also shows a `digest` (`sha256:<hex>`) for every uploaded asset. Older assets weren't backfilled, so it can be null ([changelog](https://github.blog/changelog/2025-06-03-releases-now-expose-digests-for-release-assets/)). The expected hash is the checksum file's entry, or GitHub's digest when the file doesn't list the asset. If both exist and disagree, nothing is trusted. If neither exists, Locus won't install. The hash is computed while the file streams in, compared in constant time, and on a mismatch the file is deleted.

Both checksums come from the same GitHub release as the APK. They catch corruption, truncation and a swapped asset, but not a compromised release account. The defence against that is Android's signature rule.

**Signature, checked before Android does.** "When the system is installing an update to an app, it compares the certificate(s) in the new version with those in the existing version. The system allows the update if the certificates match" ([app signing](https://developer.android.com/studio/publish/app-signing)). If they don't, the install fails with `STATUS_FAILURE_CONFLICT`, which users see as an unhelpful "App not installed". So after the checksum, `ApkCheck.verdict` reads the APK with `getPackageArchiveInfo(…, GET_SIGNING_CERTIFICATES)`. It compares the package name, the signers (or a rotated key whose history includes the installed one) and the versionCode with the installed app, and explains a mismatch before the installer is involved. The common case is a locally built or debug-key release installed over the official one.

**PackageInstaller, not ACTION_VIEW.** `Intent.ACTION_INSTALL_PACKAGE` was "deprecated in API level 29. use android.content.pm.PackageInstaller instead" ([Intent](https://developer.android.com/reference/android/content/Intent#ACTION_INSTALL_PACKAGE)). `ACTION_VIEW` on an APK also needs a FileProvider and gives no result back. The session API:
- `SessionParams(MODE_FULL_INSTALL)` with `setAppPackageName(packageName)`, so Android rejects any APK that isn't Locus.
- `setRequireUserAction(USER_ACTION_REQUIRED)` on Android 12+, so the user always confirms. Silent self-updates would need `UPDATE_PACKAGES_WITHOUT_USER_ACTION` and target-SDK floors ([SessionParams](https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams)).
- The APK streamed into `openWrite`, then `commit` with a **mutable** broadcast `PendingIntent`. The installer fills in the status extras, and an immutable one throws for apps targeting 35+ ([Session.commit](https://developer.android.com/reference/android/content/pm/PackageInstaller.Session)).
- `UpdateInstallReceiver` (not exported) gets `EXTRA_STATUS`. On `STATUS_PENDING_USER_ACTION` it starts `EXTRA_INTENT`; the user just tapped Install in Locus, so Locus is in front, which is when the docs say to launch it directly ([PackageInstaller](https://developer.android.com/reference/android/content/pm/PackageInstaller)). `ABORTED`, `CONFLICT`, `STORAGE`, `BLOCKED` and the rest map to the failures above.

The download is streamed by the app's own Ktor/OkHttp client into `noBackupFilesDir/updates`, then into the session. DownloadManager would post its own notification, and its default destination is shared storage "where the system might delete your file" ([DownloadManager.Request](https://developer.android.com/reference/android/app/DownloadManager.Request)). Neither helps here, and a FileProvider isn't needed.

**"Install unknown apps".** The manifest declares `REQUEST_INSTALL_PACKAGES`. On Android 8+ the user must also allow Locus as a source. Locus checks `PackageManager.canRequestPackageInstalls()` before installing. When it's off, Locus shows "Allow Locus to install apps" with **Open setting**, which opens `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` with `package:dev.locus` ("the Intent's data URI can specify the application package name", [Settings](https://developer.android.com/reference/android/provider/Settings#ACTION_MANAGE_UNKNOWN_APP_SOURCES)). When the user comes back (`LifecycleResumeEffect`), it re-checks and offers Install again.

**Prior art.**
- Signal's website build (`ApkUpdateJob`, `ApkUpdateInstaller` in [Signal-Android](https://github.com/signalapp/Signal-Android)) fetches a manifest, checks a SHA-256 digest, and installs with a PackageInstaller session, `setAppPackageName` and a mutable PendingIntent. Locus does the same, but always asks the user, and adds the signer pre-check.
- NewPipe's `NewVersionWorker` ([NewPipe](https://github.com/TeamNewPipe/NewPipe)) only notifies, and runs only when the APK's signing certificate is NewPipe's own, so F-Droid and debug builds never check.
- [Obtainium](https://github.com/ImranR98/Obtainium) tracks a GitHub repo's releases and installs the matching APK (with an APK filter regex).
- Telegram FOSS wasn't looked at.

## Beta channel

Added 2026-11-06 (comparable-projects research, item 11). Settings → About & privacy → Updates has **Include beta versions** under the daily-check switch, off by default. It's shown wherever Check now is (release and debug builds), not for store installs: Obtainium, F-Droid and the rest keep updating Locus their own way (Obtainium has its own "include pre-releases" option).

- **Which release.** Off: GitHub's latest release, as before, which is never a pre-release or a draft. On: `GET /repos/springmusk026/locus/releases?per_page=20`, the recent releases with pre-releases included ([List releases](https://docs.github.com/en/rest/releases/releases#list-releases): published releases are visible to everyone, drafts only with push access). `ReleaseChannel.newest` picks the highest version by semantic version, not by date or list order, so `0.3.0-beta.10` beats `0.3.0-beta.2` and `0.3.0` beats both. Drafts are dropped on both channels in case one ever shows up.
- **Never a downgrade.** Whatever is picked is offered only if `Versions.isNewer` than the installed `versionName` (`ReleaseChannel.offers`). Someone on `0.3.0-beta.3` who turns beta off isn't offered `0.2.9`; they get `0.3.0` when it's out. The APK check still refuses a versionCode that isn't higher.
- **Same checks.** A beta goes through the same path: `SHA256SUMS.txt` or GitHub's digest, the signer compared with the installed app, Android's installer prompt. Pre-releases are cut by the same release workflow (a tag with a `-` suffix, such as `v0.2.0-beta.1`, is published as a pre-release; [RELEASING.md](../RELEASING.md)), signed with the same key.
- **The card says Beta.** "Beta available: 0.3.0-beta.1" with a **Beta** chip and one line: a pre-release for trying changes early, checked like any update. The status line says "Beta 0.3.0-beta.1 available".
- **Switching.** The cached release and ETag belong to the channel they came from (`UpdateCache.beta`), so the stable request never sends the list's ETag. Turning the switch either way drops the release in hand (unless it's downloading or installing) and fetches nothing; the next check (daily, or Check now) asks the new channel.

Claude Code's `autoUpdatesChannel` (`latest` or `stable`) and Gemini CLI's preview and nightly tags work the same way: opt in to newer builds, with stable as the default.

## Builds that don't check

- **`-Plocus.updateCheck=false`** sets `BuildConfig.UPDATE_CHECK_ENABLED = false` (`app/build.gradle.kts`, default `true`). `Updates.availability` is then `DisabledInBuild`. Setup shows no switch, About says "This build doesn't check for updates", nothing is scheduled, and Check now and install aren't offered. The `REQUEST_INSTALL_PACKAGES` declaration stays in the manifest but is never used.
- **Debug builds** (`dev.locus.debug`) are `DebugBuild`. A release APK is `dev.locus`, so it would install beside the debug app, not over it. About says "Debug build: updates come from your own builds", there's no switch or daily check, and Check now still shows the latest release and its notes, so the UI can be tried, but there's no Download.
- **Installed by a store that updates Locus itself**: F-Droid, Droid-ify, Neo Store, Obtainium, or Play (`StoreInstallers`, read from `getInstallSourceInfo`, or `getInstallerPackageName` before Android 11). About says "Installed from F-Droid, which keeps Locus up to date", and nothing is scheduled.

**F-Droid and IzzyOnDroid.**
- F-Droid's Inclusion Policy allows auto-updates only with "explicit user consent… opt-in… structured in a way that clearly explains to users that they're choosing to bypass F-Droid's checks" ([Inclusion Policy](https://f-droid.org/docs/Inclusion_Policy/)).
- F-Droid's Tracking anti-feature exempts update checks that are "opt-in… and disabled by default" ([Anti-Features](https://f-droid.org/docs/Anti-Features/)).
- IzzyOnDroid's "Self-Updaters & Co" asks for the same opt-in and explanation ([inclusion policy](https://izzyondroid.org/docs/general/AppInclusionPolicy/)), and its scanner flags `REQUEST_INSTALL_PACKAGES`.

Locus's check is off by default, and its setup text says it bypasses app-store checks. There's a catch with reproducible builds: when F-Droid publishes Locus's own signed APK, F-Droid's build must match the GitHub APK byte for byte, so it can't set a different `locus.updateCheck` ([FDROID.md](../FDROID.md#reproducible-builds)). IzzyOnDroid also ships the GitHub APK as is. In both cases the flag stays on and the installer check turns it off at run time, because those installs come through an F-Droid client. If F-Droid instead builds and signs Locus itself, its recipe adds `locus.updateCheck=false` to `gradleprops`. FDROID.md has both variants.

**Play.** Play only allows `REQUEST_INSTALL_PACKAGES` for a short list of core uses, and forbids self-updating "using any method other than Google Play's update mechanism" ([Play policy](https://support.google.com/googleplay/android-developer/answer/12085295)). A Play build (D3) must build with `locus.updateCheck=false`, and should also remove the permission, for example with a manifest overlay that uses `tools:node="remove"`.

## Not sealed

`AppSettings.updateCheck` isn't covered by `PolicySeal`. The seal protects settings that grant the agent or another app power: tool policies, project remotes, automation callers, Telegram remote, remembered approvals. Another process running as Locus's uid (a Linux program) could flip this switch, but that would only cause a request to GitHub, which that process can already make itself. The update source is a constant in code, not a setting, so tampering can't point Locus at another server. Installing still needs the user's confirmation in Android's installer and a matching signing key. Sealing it would add an alert path for no protection. `updateBeta` isn't sealed for the same reason: flipping it changes only which GitHub release is offered, and the user still confirms the install.

## State kept

`updates.json`, its own DataStore (not in backups), holds the ETag, the last release and which channel it came from, when GitHub last answered, the rate-limit reset, the skipped version, and the version last notified. The notified version is why each version notifies only once. The switches themselves (`updateCheck`, `updateBeta`) live in `settings.json` and so are backed up.

## Tests

- `ReleaseFilesTest`: version comparison, release JSON parsing against `src/test/resources/updates/latest-release.json`, ABI choice, `SHA256SUMS.txt` parsing and the checksum-or-digest rule, the install pre-check, and the installer list.
- `UpdatesTest`, over a scripted GitHub:
  - the request's headers;
  - the ETag and 304 path;
  - rate limits, which make no request before the reset;
  - up to date, pre-releases and drafts;
  - the setting gating the daily check;
  - scheduling, and the worker's interval and unmetered constraint;
  - disabled, store and debug builds;
  - notify-once and skip;
  - download, checksum mismatch, no checksum, another key, no ABI, a dropped connection, "Install unknown apps", the installer's results, and cancel.
- `BetaChannelTest`: stable only, a beta newer than stable, a beta older than stable, a draft ignored, never a downgrade, and the check over a scripted GitHub (the list endpoint, the Beta mark, switching back without the list's ETag).
- `UpdatesWordsTest`: About's status line and messages.
- `OnboardingViewModelTest`: the setup switch.
