# Reliability and security audit, November 2026

Third research round (2026-11-01). A review of reliability, performance, security and engineering practice. Docs read: STATE, ROADMAP, ARCHITECTURE, SECURITY, CHANGELOG and docs/design. Code sampled: chat UI, the runner, the database, the vault, the browser, MCP, proot, the manifests, the build and CI. Dependency CVEs checked against osv.dev and GitHub advisories. Gradle wasn't run, so test-to-code line ratios stand in for coverage.

Vulnerabilities are described by class, location and fix only.

Tags: **confirmed** (read in the code), **likely** (sound reasoning, not reproduced on a device), **unverified** (needs a check). Effort: S under a day, M 1–3 days, L more than 3 days.

## Top 12

| # | Finding | Kind | Severity | Effort |
|---|---|---|---|---|
| 1 | Release CI signs the debuggable APK with the release key and publishes it | confirmed | High | S |
| 2 | A failed restore leaves the vault already replaced, while the notice says nothing changed | confirmed | High | S–M |
| 3 | Linux (proot) processes run as the app's UID and can likely reach app-private data through `/proc`, including the tool policies | likely | High | M–L |
| 4 | The share target accepts `file://` URIs, so another app can make Locus copy its own private files into the workspace | confirmed | Medium-high | S |
| 5 | The browser's sensitive-action gate fails open and trusts facts from JS the page can tamper with | confirmed (logic), likely (exploit) | Medium | S–M |
| 6 | `fetch_url` and `mcp_call` results aren't marked untrusted; `fetch_url` reads unbounded bodies and has no private-address policy | confirmed | Medium | S |
| 7 | Memory poisoning: `memory_save` is Allow by default and reflection runs over transcripts with untrusted text | confirmed | Medium | S–M |
| 8 | Scheduled tasks: 10-minute WorkManager limit, no `setForeground`, and a stopped run is never recorded or notified | confirmed | Medium | M |
| 9 | Startup and memory: no baseline profile, all ABIs in one APK, a cold-start scan of every transcript, Keystore/Tink init on first inject, no idle unload of the on-device model | confirmed | Medium | M |
| 10 | No crash or exit history, no `CoroutineExceptionHandler` on the app scope, an unguarded background `startForegroundService`, no DataStore corruption handler | confirmed | Medium | S–M |
| 11 | Network drops mid-stream: 10-minute read timeout, infinite request timeout, no connectivity callback | confirmed | Medium | S–M |
| 12 | The transcript query loads `rawPayload` for every message on every change, with no paging | confirmed | Low-medium | M |

## Confirmed problems

### 1. Debuggable APK signed with the release key

- **Where.** `app/build.gradle.kts:39-41` signs the debug build with the `locus` signing config when the CI keystore is present; `.github/workflows/release.yml:70` builds `:app:assembleDebug` with the keystore set and publishes `locus-vX-debug.apk`.
- **Class.** That APK has the production `applicationId` (`dev.locus`), `debuggable=true` and the release signature, so it installs over the release build and keeps its data. A debuggable build exposes app-private storage and the in-process, Keystore-backed vault to anyone with ADB access. MASVS-RESILIENCE-4 / MASVS-CODE (MASTG "debuggable" tests).
- **Severity.** High: it undoes the Keystore protection of every saved key for anyone who installs the debug APK.
- **Fix.** Don't sign debug with the release key; or give debug `applicationIdSuffix = ".debug"` and its own label; or stop publishing the debug APK (`mapping.txt` is already published). S.

### 2. Failed restore leaves the vault replaced

- **Where.** `BackupManager.kt:171-174` (`restoreAndRestart`) calls `secrets.replaceAll(vault)` before `pending.markReady()` and the restart. The file swap happens later in `LocusApplication.attachBaseContext` → `PendingRestore.apply()`; if that rolls back, the database and settings return to their old state but the vault doesn't. `replaceAll` drops keys not in the backup. docs/design/backup.md:131 tells the user their data is as it was, which is false here.
- **Related silent failures.** `BackupManager.kt:171` wraps `cancelAllScheduled()` in `runCatching`; `BackupManager.kt:179` runs `rescheduleAll()` in a fire-and-forget `runCatching`, so a failure leaves scheduled tasks stopped with nobody told.
- **Severity.** High: key loss and a misleading message.
- **Fix.** Make the vault part of the journalled swap (stage the current `secrets.enc` as `vault-before` and restore it on rollback), or stage new entries as a Keystore-AEAD file applied in `onRestored()` after `COMMITTED`. Report a `rescheduleAll` failure through the restore notice. S–M.

### 3. proot is not a security boundary

- **Where.** `core/linux/.../LinuxEnvironment.kt:308-319` binds host `/proc`, `/dev` and `/sys` into the guest. Every Linux process runs as the app's UID in the `untrusted_app` SELinux domain.
- **Class.** Kernel-resolved `/proc` links (a process's `root` and open file descriptors) bypass proot's path translation, so guest processes can probably reach Locus's private files: `databases/locus.db`, `files/datastore/settings.json` (plaintext, holds the Allow/Ask/Deny policies), `files/agent-home/` (memory, skills, connector drafts, persona files) and `shared_prefs/locus_tink_*`. Any approved command, package install script or local MCP server could then change tool policies or approval state, edit persona files, or read chats and send them out directly, bypassing Locus's approvals. Upstream says the same: proot is path translation, not kernel isolation ([proot-distro, Limitations](https://github.com/termux/proot-distro)); guest programs can use host resources ([proot-me](https://proot-me.github.io/)).
- **Status.** Likely; not run on a device. To verify, in Locus's terminal: `ls /proc/*/root/data/data/dev.locus/files/datastore/` and `ls -l /proc/*/fd | grep locus.db`.
- **Update (2026-11-01).** Confirmed on a device through `/proc/<pid>/fd` (the `root` route didn't work: proot translates it). Fixed in layers: Locus's processes are non-dumpable, proot hides Locus's `/proc/<pid>` entries and binder, and tool policies, reviewer settings and connector approvals are sealed with a Keystore HMAC. Persona files aren't sealed. `/sys` stays bound: it holds no app data. See docs/design/linux-isolation.md.
- **Severity.** High for the integrity of the approval model (MASVS-PLATFORM-1, MASVS-STORAGE-1).
- **Fix, in layers.** (1) Bind a filtered `/proc` (proot can bind single entries; Locus already fakes some via `FAKE_PROC`) and drop `/sys`. (2) Move security-relevant state out of files any same-UID process can write: tool policies, connector approval state and persona files into the Tink-AEAD DataStore, or MAC them with a Keystore HMAC key checked on load. (3) Long term, run Linux in a separate `android:isolatedProcess` service (its own UID) that gets only the workspace through passed file descriptors. (4) Say in SECURITY.md that Linux commands run with the app's privileges. M for 1, 2 and 4; L for 3.

### 4. Share target copies `file://` URIs

- **Where.** `app/.../ShareViewModel.kt:30-36` accepts any `EXTRA_STREAM` URI; `WorkspaceRepository.kt:63` opens it with `contentResolver.openInputStream(uri)` with no scheme or authority check.
- **Class.** Share-target file theft (MASVS-PLATFORM-1; MASTG-TEST-0029/0364): another app can hand Locus a URI to Locus's own private files, which Locus reads with its own permissions and copies into `workspace/inbox`, where the agent, proot and the Files-app provider can read it. Also: no size limit (disk fill), and failed imports are dropped silently by `runCatching{}.getOrNull()`.
- **Severity.** Medium-high (needs a malicious app installed).
- **Fix.** Accept only `content://`; reject Locus's own authorities (`${applicationId}.workspace`, `.exports`); cap the size; show which files failed to import. S.

### 5. Browser sensitive-action gate fails open

- **Where.** `core/browser/.../BrowserTools.kt:152-155` (click) and `:178-182` (type and submit) return no confirmation when `browser.inspect(ref)` is null. `AgentBrowser.kt:235-240` gets those facts from `__locus.inspect(...)`, which runs in the page's own JS world (`locus-agent.js`, injected with `evaluateJavascript`).
- **Class.** Fail-open security check relying on data the untrusted page controls: a hostile page can make a payment, login or submit look benign, or make inspection fail, and the confirmation is skipped. It matters when browser tools are set to Allow or the reviewer auto-allows.
- **Severity.** Medium. Logic confirmed; exploitability likely.
- **Fix.** Fail closed (null or error means confirm). Capture DOM natives before page scripts run with `WebViewCompat.addDocumentStartJavaScript` (behind `WebViewFeature.DOCUMENT_START_SCRIPT`) and freeze the helper object. Cross-check natively, e.g. a cross-origin navigation or a POST after the click counts as sensitive. Set Safe Browsing explicitly (`WebSettingsCompat.setSafeBrowsingEnabled(true)`) and report `onSafeBrowsingHit` to the agent. The other WebView settings are already good: file and content access off, http(s)-only navigation, no JS bridge, `MIXED_CONTENT_NEVER_ALLOW`, a separate profile, permission requests denied, render-process loss handled. S (fail closed) to M.

### 6. `fetch_url` and `mcp_call`

- **Not marked untrusted.** `core/agent/.../tools/WebTools.kt:47-64` and `core/data/.../mcp/McpTools.kt:250` return raw third-party text, while web_read, web_search, email, GitHub and the browser wrap theirs with `UntrustedContent.wrap`.
- **Unbounded body.** `WebTools.kt:59` reads `bodyAsText()` before truncating to `max_chars`; a very large response can exhaust memory.
- **No private-address policy.** `fetch_url` reaches `127.0.0.1` (Linux services; the OAuth loopback listener during sign-in) and, once `ACCESS_LOCAL_NETWORK` is granted for Ollama, the whole LAN. It accepts `http://`, and `network_security_config.xml` permits cleartext globally, so a hostile network can inject into the agent's context. GET counts as an observation, so it runs in read-only and scheduled runs when set to Allow, which makes it a data-egress channel.
- **MCP risk from hints.** `McpToolRisk.of` is careful (destructive and write words; ignores read-only claims from non-vendor servers on write-named tools), but an untrusted server can still label a neutrally named tool `readOnlyHint` and have it run under `mcp_call`'s default Allow, including in background runs. Tool descriptions (up to 400 characters) reach the model unmarked (tool poisoning).
- **Severity.** Medium (prompt injection; MASVS-PLATFORM, MASVS-CODE-4).
- **Fix.** Wrap both results with `UntrustedContent.wrap`. Stream the body with a byte cap (`bodyAsChannel()`, up to `max_chars*4`). Resolve the host and refuse loopback, link-local and RFC1918/ULA addresses unless "allow local addresses" is on or the host is a configured provider. Require https for agent-originated requests except allowed local hosts. For non-vendor MCP servers, treat READ as ASK on the first call per tool, then remember. S.

### 7. Memory poisoning

- **Where.** `core/agent/.../home/HomeTools.kt:46` gives `memory_save` `ToolPolicy.ALLOW`. Reflection (`AgentRunner.kt:948, 976-1010`) runs after tasks with 6 or more tools, over the full transcript, with memory tools allowed.
- **Class.** Persistent prompt injection: text from a web page or email can become a memory that shapes every later chat.
- **Severity.** Medium.
- **Fix.** Skip reflection, or force `memory_save` to Ask, when the run consumed wrapped untrusted content; store provenance per memory entry and show it in Library. S–M. See also [agent-algorithms-2026.md](agent-algorithms-2026.md) #2 and #9.

### 8. Scheduled tasks fail silently

- **Where.** `core/data/.../tasks/TaskWorker.kt` is a plain `CoroutineWorker` with no `setForeground` or `getForegroundInfo`. "A ListenableWorker is given a maximum of ten minutes" ([ListenableWorker](https://developer.android.com/reference/androidx/work/ListenableWorker)), and scheduled runs allow 40 steps.
- **Problem.** When stopped (timeout, lost network, Android 16 quota), `doWork` is cancelled, so `tasks.recordRun` and `notify` never run and a half-finished "Scheduled: …" chat stays behind. A non-cancellation exception returns `Result.failure()` with no notification. Since Android 16, jobs running alongside a foreground service or started while the app is visible also count against quota ([Android 16 changes](https://developer.android.com/about/versions/16/behavior-changes-all)). `TaskWorker.kt:51` wraps a suspend call in `runCatching`, which also swallows `CancellationException`; the pattern appears at about 277 `runCatching` sites, so spot-check the suspend ones.
- **Severity.** Medium.
- **Fix.** `try/catch/finally`: on cancellation record "stopped by Android (`stopReason`)" under `NonCancellable`, notify, rethrow; on other exceptions record and notify. A time budget under 10 minutes; for longer tasks `setForeground(ForegroundInfo(…, SPECIAL_USE or DATA_SYNC))` (mind the 6 h/day `dataSync` cap). Show `WorkInfo.stopReason` in Tasks. M.

### 9. Startup and memory

- **No baseline or startup profile:** no `baseline-prof.txt` or `startup-prof.txt`, no `androidx.baselineprofile` plugin, no `:benchmark` module.
- **APK size and ABIs:** the debug APK is 53.8 MB (127 MB unpacked). `liblitertlm_jni.so` is 26.8 MB for x86_64 and 22.7 MB for arm64-v8a. The release APK also ships armeabi-v7a and x86 `libsqliteJni.so`, though proot exists only for arm64-v8a and x86_64. `assets/mermaid/mermaid.tiny.js` is 2.8 MB. `jniLibs.useLegacyPackaging = true` (`app/build.gradle.kts:60-62`) is needed for proot's executables but compresses and extracts every `.so` (about 50 MB more installed).
- **Cold-start scan:** `AgentRunner.kt:190` launches `recoverInterrupted()` (`:689-737`) at Hilt creation, loading `conversations.history()` for every chat (all messages, `rawPayload` included) on every cold start.
- **Keystore and Tink on first inject:** `DataStoreModule.kt:42-58` builds the keyset (SharedPreferences read plus Keystore unwrap) inside a Hilt `@Provides`, usually on main during ViewModel creation (timing unverified).
- **No idle unload** of the on-device model (`OnDeviceChatModel.kt:167`): no `onTrimMemory` or idle timeout, so Locus is an LMK target in the background and loses running tasks with it. Android 17 adds per-app memory limits (exit reason `REASON_OTHER`, "MemoryLimiter:AnonSwap").
- **Fix.** A `:baselineprofile` module (androidx.benchmark 1.5.0, `BaselineProfileRule.collect(includeInStartupProfile = true)`) for cold start, opening a long chat and streaming a reply. `abiFilters("arm64-v8a", "x86_64")` or ABI splits for release. Drive recovery from a persisted "runs in flight" marker instead of scanning every chat. Provide the `Aead` lazily or on `Dispatchers.IO`. `onTrimMemory` plus a 5-minute idle timer that unloads the engine when no run is active. M.

### 10. No crash or exit history; uncaught paths

- No `setDefaultUncaughtExceptionHandler`, no `ApplicationExitInfo` reader, no ACRA; the only log line in app code is `LocusApplication.kt:25`.
- The app scope (`DataModule.kt:39`, `SupervisorJob() + Dispatchers.Default`) has no `CoroutineExceptionHandler`, so a throw in recovery, helper reports or resume kills the process.
- `ContextCompat.startForegroundService` (`AgentRunner.kt:497`) is unguarded. When `start()` runs from the background (`continueWithHelperReports`, `:229-233`, or resume after a queued message) after the service stopped, Android 12+ throws `ForegroundServiceStartNotAllowedException` ([background start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)). Likely; reproduce with a helper finishing while the app is in the background.
- The settings and secrets DataStores (`DataStoreModule.kt:35`) have no `corruptionHandler`; `JsonSerializer`'s `CorruptionException` reaches every collector.
- If the Keystore master key is invalidated (seen after OS updates or restores on some OEMs), `AndroidKeysetManager.build()` throws inside Hilt and every launch crashes (behaviour with Tink 1.24 unverified).
- **Severity.** Medium: an app that promises never to fail silently has no way to learn why it died.
- **Fix.** On start, read `ActivityManager.getHistoricalProcessExitReasons` (API 30+, with ANR traces) into a small table, show it in Settings → About → Diagnostics and include it, scrubbed, in the diagnostic export. Optionally ACRA 5.14.2 (Apache-2.0) with a local-only `ReportSender` to `noBackupFilesDir`. A `CoroutineExceptionHandler` that records the error into the relevant `RunState.error`. Catch `ForegroundServiceStartNotAllowedException` and post a "tap to continue" notification. `ReplaceFileCorruptionHandler` for settings that keeps a `.corrupt` copy and tells the user. For secrets, catch `GeneralSecurityException`/`KeyStoreException` and offer "vault can't be unlocked, reset keys?" instead of a crash loop. S–M.

### 11. Network changes mid-stream

- **Where.** `DataModule.kt:46-58`: `readTimeout(10, MINUTES)`, `requestTimeoutMillis = INFINITE`. After a Wi-Fi to mobile handover the old socket often dies without a reset, so a stream or `fetch_url` can hang up to 10 minutes before `AgentLoop`'s retry starts. The MCP client (`McpTools.kt:46-49`) is a separate OkHttp without the per-provider proxy and CA settings.
- **Severity.** Medium for UX: it looks hung.
- **Fix.** An idle timeout for streams (~90–120 s; Anthropic sends `ping` events while thinking). `ConnectivityManager.registerDefaultNetworkCallback`: on loss or a default-network change, cancel in-flight streaming calls so the existing retry runs at once. A 30–60 s total timeout for `fetch_url`. Build the MCP client from the shared `OkHttpClient` (`newBuilder()`). S–M.

### 12. Transcript query and paging

- **Where.** `core/database/.../Daos.kt:94-104`: `observeTree` selects `messages.*`, including `rawPayload` (Responses stores the whole output array with encrypted reasoning, `OpenAiResponsesModel.kt:265`), for all branches; the UI never reads `rawPayload`. The query joins `conversations`, and Room invalidates per table, so every insert and every `touch`/`setActiveLeaf` on any chat re-runs it for every observer. `ChatViewModel.kt:302-304` rebuilds `toItems` over all messages, and `ChatScreen.kt:283, 361` run `groupSteps` and `panelToggles` on the main thread. No paging (ROADMAP "Large chats"). Within a run the loop keeps the transcript in memory (`AgentLoop.kt:190`), which is fine.
- **Severity.** Low-medium, growing with chat size; worst during runs.
- **Fix.** A UI projection DAO without `rawPayload`, `usageJson` parsed lazily. Observe `activeLeafId` separately so the message query doesn't depend on `conversations`. Room 3 paging (`androidx.room3:room3-paging` with `@DaoReturnTypeConverters(PagingSourceDaoReturnTypeConverter::class)`; [Room 3 releases](https://developer.android.com/jetpack/androidx/releases/room3)), paging the active branch path via a recursive CTE over `parentId` or a `pathIndex` column written at insert. `groupSteps` in the ViewModel on `Dispatchers.Default`. M.

### Smaller confirmed issues

| Issue | Where | Severity | Fix | Effort |
|---|---|---|---|---|
| A failed compaction is swallowed, so the next request may overflow with a cryptic provider error | `AgentRunner.kt:280` | Low-medium | A notice in `RunState.notice` ("Couldn't summarise: …") | S |
| Lazy list items have no `contentType`, so slots are reused poorly across ~9 row kinds | `ChatScreen.kt:384, 443` | Low | `items(rows, key = { it.key }, contentType = { it::class })` | S |
| Saved Markdown is parsed in composition (`immediate = true`, chosen to avoid jumps), which janks on flings through long replies | `ChatMarkdown.kt:48, 66` | Low | Pre-parse off main in the ViewModel, cached by message id; or `immediate` only for the last N items | M |
| `core:model` classes are unstable to Compose, so visible rows recompose on every DB emission | `core/model` | Low | `composeCompiler { stabilityConfigurationFiles }` listing `dev.locus.core.model.*`; check compiler reports | S |
| `EXTRA_OPEN_CHAT` is honoured from any external intent on the exported activity | `MainActivity.kt` (`LocusNavigation`) | Low | Accept it only from Locus's own `PendingIntent`s, e.g. a per-process token extra | S |
| Cleartext allowed globally (needed for LAN model servers; the config can't express IP ranges) | `network_security_config.xml` | Low-medium | Enforce https in code for agent, remote-MCP and integration requests; http only for user-configured providers and RFC1918/loopback (Android 17 already allows loopback cleartext) | S |
| No release supply-chain hardening: actions pinned by tag, `contents: write` for the whole job including the signing build, no Gradle dependency verification, no provenance | `.github/workflows/release.yml` | Medium | Pin actions by SHA (Dependabot updates them); split build and sign/publish so the keystore exists only in signing; enable Gradle dependency verification; `actions/attest` for the APK | M |

## Best-practice recommendations

### Performance measurement

1. **Macrobenchmark module** (androidx.benchmark 1.5.0, a `profileable`, non-debuggable `benchmark` build type): cold and warm startup (`StartupTimingMetric`); opening a seeded 2,000-message chat and flinging (`FrameTimingMetric`); streaming 1,500 tokens at 50 tokens/s from a fake provider (`FrameTimingMetric`, `TraceSectionMetric("ChatScreen")`). Targets: cold start p50 under 500 ms on the S25 Ultra; no frame overrun at p90 while streaming.
2. **Perfetto with Compose runtime tracing** (`androidx.compose.runtime:runtime-tracing`; recomposition tracing needs 1.13+): recomposition counts of `LiveText`, `StreamingAssistantMarkdown` and visible `AssistantText`. The streaming design is already good (streamed text split from `uiState`, `derivedStateOf`, 48 ms throttled appends to `StreamingMarkdownState`, `StreamCoalescer`); expect hot spots in `toItems` + `groupSteps` per insert and Markdown parsing on scroll.
3. **Memory:** `adb shell dumpsys meminfo dev.locus` around loading E2B and E4B; log `ApplicationExitInfo` reasons to catch LMK and MemoryLimiter kills.
4. **APK:** `apkanalyzer` or `diffuse` per release in CI, size per ABI.

### Reliability

- Process death mid-task is handled well (dangling tool calls closed, queue persisted, helpers marked interrupted, explicit Continue). Keep it, driven by a "runs in flight" marker (#9).
- `AgentService` uses `specialUse`, which has no 6-hour cap; `onTimeout` stops cleanly (`AgentService.kt:73`). A Play build would need the declaration in Play Console.
- `ModelDownloadWorker` uses `dataSync`, whose 6 h/24 h cap is shared by all the app's `dataSync` work; a 3.7 GB download on a slow link needs `onTimeout` handling and resume (check that `onStopped` keeps the partial file).

### Tink vault

The design is sound: AES256-GCM, keyset wrapped by `android-keystore://locus_secrets_master`, the file name bound as associated data. Leave `setUnlockedDeviceRequired(true)` off (it would block background runs while locked) and document why; handle Keystore invalidation (#10); move tool policies and approval state into an AEAD store (#3); add JVM tests for `DataStoreModule` wiring (0 own tests today).

### Dependency CVEs (checked 2026-11-01)

No open advisories at the pinned versions for Ktor 3.6.1 (with OkHttp 5.5.1), Tink 1.24.0, Angus Mail 2.0.5 (CVE-2025-7962 fixed in 2.0.4), sshlib 0.6.6 (CVE-2026-54700 and 54697 fixed in 0.3.1; strict KEX advertised), MCP kotlin-sdk 0.16.0 (CVE-2026-53937, 63657 and 63658 fixed by 0.14.0), litertlm 0.19.0, markdown-renderer 0.46.0, javascriptengine 1.1.1 and webkit 1.18.0.

Needs attention:
- **jsoup 1.23.2:** GHSA-65r4-943x-97jj / CVE-2026-75140, memory blow-up in `XmlTreeBuilder`. OSV says fixed in 1.23.2; the text says "through 1.23.2" (unverified). Locus uses `Jsoup.parse` (HTML), so probably not exposed; confirm commit 862ba2f is in 1.23.2.
- **sqlite-bundled 2.7.1:** likely bundles SQLite 3.50.1 (inferred from androidx-main moving to 3.53.4 on 2026-10-02; verify with `SELECT sqlite_version()`). That predates fixes for CVE-2025-6965, CVE-2025-7709 (corrupt FTS5 index), CVE-2025-70873 and CVE-2026-11822/11824 (FTS5, needing arbitrary SQL with DEFENSIVE off). Locus's exposure is the restore path, which installs a database from a `.locusbackup` while FTS5 is in use; a crafted backup still needs the password, so the risk is low. Take the next stable release, or run `PRAGMA quick_check` on a restored database before the swap.
- **proot:** not an isolation layer by design (#3). Alpine 3.24 is supported until 2028-06-01.

### OWASP MASVS v2.1

| MASVS | Status | Findings |
|---|---|---|
| STORAGE-1/2 | Mostly good: vault, no cloud backup, encrypted export | #2, #3, #4 |
| CRYPTO-1/2 | Good: Tink, PBKDF2 600k, Keystore | Keystore invalidation (#10) |
| AUTH (OAuth for MCP and ChatGPT) | Strong: PKCE S256, state, iss, RFC 8707, loopback 404s | — |
| NETWORK-1/2 | Global cleartext; no pinning (fine for BYOK) | Cleartext row above, #6 |
| PLATFORM-1 (IPC) | Receivers and services not exported; FileProvider not exported; DocumentsProvider behind `MANAGE_DOCUMENTS` with canonical path checks | #4, `EXTRA_OPEN_CHAT` |
| PLATFORM-2 (WebView) | Well hardened, no JS bridges | #5 |
| CODE-1/2/3/4 | Dependabot monthly; no advisories at current versions | #1, sqlite, supply chain, #6 |
| RESILIENCE | Debuggable build with the production signature | #1 |
| PRIVACY | Local-first, no analytics | #7 (memory provenance) |

Relevant MASTG v2 tests: WebView 0031–0033, 0250–0253, 0398/0400; deep links 0028, 0394; exported components 0029, 0364–0366; cleartext 0235.

### Engineering

**Coverage gaps** (test-to-code line ratios; no Kover XML locally):

| Module | Main lines | Test lines |
|---|---|---|
| `app` | 360 | 0 |
| `core:datastore` | 204 | 0 |
| `feature:browser` | 228 | 0 |
| `feature:tasks` | 216 | 0 |
| `feature:workspace` | 1,927 | 113 |
| `feature:settings` | 3,744 | 359 |
| `feature:library` | 1,512 | 84 |
| `feature:activity` | 862 | 59 |
| `core:browser` | 2,097 | 352 (the sensitive-action gate needs tests) |
| `core:linux` | 1,218 | 310 |

`core:agent` (11,992 / 6,950) and `core:llm` (1,722 / 1,586) are well covered. Next: run `./gradlew koverXmlReportUnit && python3 tools/coverage/summary.py` and set per-module Kover `verify` floors (current minus 2%, ratcheting; higher for `core:agent`, `core:data`, `core:browser`); Compose UI tests on the JVM (Robolectric + `createComposeRule`) for the composer and streaming bubble; a Room `MigrationTestHelper` on the bundled driver so migrations run on the phone's SQLite rather than the system `sqlite3` (Room 3 JVM support unverified).

**CI speed (#57).** #57 added the `KspSerialService` build service (`maxParallelUsages = 1`, `build-logic/.../Ksp.kt`) to work around google/ksp#3286; kotlinx.serialization is a compiler plugin and isn't involved. KSP runs in 17 modules. Options: drop the service once a KSP release contains ksp PR #3290 (latest 2.3.13 as of 2026-10-30), still bumping `KSP_SERIAL_CACHE_KEY`; drop Hilt/KSP from ~9 feature modules by exposing ViewModel factories from `core:data`; check the build cache is used (setup-gradle caches by default; consider a read-only remote cache for PRs; configuration cache is already on); split CI into parallel jobs (unit tests plus Kover, lint, `assembleDebug`); run lint only when Android modules change, or `lintRelease` for vital checks.

**Lint and static analysis.** Android lint runs with defaults (no `lint {}` block, baseline or `warningsAsErrors`), and the JVM modules holding the security-critical code (`core:agent`, `core:llm`, `core:model`) aren't linted. Add the `com.android.lint` plugin to them; detekt 1.23.8 (2.0 is alpha) or ktlint 1.8.0, plus `io.nlopez.compose.rules` 0.6.8; a rule banning `runCatching` around suspend calls, or a `suspendRunCatching` that rethrows `CancellationException`. Module boundaries are clean. Make Dependabot weekly for Gradle with a `security-updates` group, and add the `osv-scanner` action.
