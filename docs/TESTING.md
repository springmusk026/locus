# Testing

Two suites:

- **JVM unit tests** (most of them): no emulator, no Robolectric, no network. About 460 of them run
  in a couple of minutes, on every PR.
- **Instrumentation tests** (`app/src/androidTest`): the real app on a phone or emulator, for what
  only shows up there (startup, Hilt at runtime, the foreground service, process death, WorkManager,
  notifications). See [Instrumentation tests](#instrumentation-tests).

## Running

```sh
./gradlew testDebugUnitTest :core:agent:test :core:llm:test :core:model:test
```

`:core:agent`, `:core:llm` and `:core:model` are plain Kotlin modules (`test`); the rest are Android
modules (`testDebugUnitTest`). One test class: `./gradlew :core:data:testDebugUnitTest --tests '*VaultIsolationTest*'`.

## Coverage

Coverage comes from [Kover](https://kotlin.github.io/kotlinx-kover/gradle-plugin/) 0.9.11, applied
by the convention plugins in `build-logic/`. Every module adds its unit tests to a report variant
called `unit` (Android modules their `debug` build type, Kotlin modules their `jvm` target), and the
root project merges them:

```sh
./gradlew :koverHtmlReportUnit        # build/reports/kover/htmlUnit/index.html
./gradlew koverXmlReportUnit          # every module's XML, plus the merged one
python3 tools/coverage/summary.py     # the tables below, from those XML files
```

Use the `Unit` tasks: plain `koverHtmlReport` also runs the release unit tests. Generated Hilt, Room
and Compose classes are left out (`build-logic/.../Kover.kt`). The HTML report shows lines; branch
coverage is in the XML and the summary. There are no thresholds yet. CI builds the report from its
test run and uploads it as the `coverage` artifact.

### Numbers

Line and branch coverage, own tests only (each module's tests on that module's code). "Before" is
the suite as of a909e35, "after" is this branch.

| Module | Lines before | Lines after | Branches before | Branches after |
|---|---:|---:|---:|---:|
| :app | 0.0% | 0.0% | 0.0% | 0.0% |
| :core:agent | 85.8% | 86.1% | 47.6% | 48.5% |
| :core:browser | 26.2% | 26.2% | 26.2% | 26.2% |
| :core:data | 15.6% | 40.7% | 15.1% | 23.1% |
| :core:database | 0.0% | 0.0% | n/a | n/a |
| :core:datastore | 0.0% | 0.0% | 0.0% | 0.0% |
| :core:designsystem | 26.3% | 26.3% | 14.5% | 14.5% |
| :core:linux | 0.0% | 0.0% | 0.0% | 0.0% |
| :core:llm | 88.0% | 91.2% | 58.3% | 65.0% |
| :core:model | 32.6% | 56.4% | 21.0% | 36.0% |
| :core:ondevice | 22.4% | 22.4% | 28.3% | 28.3% |
| :feature:activity | 6.5% | 6.5% | 4.3% | 4.3% |
| :feature:analytics | 0.0% | 0.0% | 0.0% | 0.0% |
| :feature:browser | 0.0% | 0.0% | 0.0% | 0.0% |
| :feature:chat | 7.8% | 17.2% | 7.2% | 14.4% |
| :feature:library | 0.0% | 0.0% | 0.0% | 0.0% |
| :feature:onboarding | 15.2% | 31.4% | 9.1% | 19.1% |
| :feature:settings | 0.0% | 8.1% | 0.0% | 1.3% |
| :feature:tasks | 0.0% | 0.0% | 0.0% | 0.0% |
| :feature:workspace | 6.6% | 6.6% | 10.4% | 10.4% |
| **All (merged)** | **33.4%** | **43.0%** | **24.1%** | **28.4%** |

The merged report also counts code run by another module's tests, which matters now that feature
and data tests run the real repositories: with it, `:core:database` is at 60.5% of lines,
`:core:datastore` 28.3%, `:core:browser` 42.1% and `:core:model` 79.5% (run the summary script for
the full table). Compose UI is most of what's uncovered in the feature modules.

## What's covered

- **Agent loop and policy** (`core:agent`): the order calls are let through or stopped (chat
  limits, Deny, read-only runs, forced confirmations, Ask/Allow), the safety reviewer and its
  fail-closed paths, plan mode, recovery of dangling calls, sub-agents, tool argument validation.
- **Secrets**: the vault's namespaces (connectors only ever reach `connector:` names, never
  provider keys, SSH credentials or `mcp-oauth:`/`chatgpt-oauth:` sessions), one provider's key never
  going to another provider, connector secrets scrubbed from replies and errors, integrations never
  echoing tokens.
- **Untrusted content**: every wrapper (browser, search, mail, GitHub, deploy logs) ends where Locus
  ended it, whatever the text contains.
- **Adapters** (`core:llm`): Anthropic, Responses and Chat Completions, including stream edge cases.
- **Repositories** (`core:data`): conversations and their branch tree, forks, search, workspace
  trash and path checks, the Library's connectors, settings and provider credentials.
- **ViewModels**: chat, home, settings and onboarding, end to end over the real data layer.
- **Database**: the v7→v8 migration and the full-text search SQL, on a real SQLite.

## How the tests are built

- **`:core:testing`** (test classpath only) holds the doubles: `FakeDatabase` (the DAOs in memory,
  following the SQL in `Daos.kt`), `InMemoryDataStore`, `TestContext` (a Context that only answers
  for directories), and `TestGraph`, which wires the real repositories, runner and tool catalog as
  Hilt does, over those doubles and a Ktor `MockEngine`. `ScriptedProvider` answers chat requests
  with canned streams, so a test can run a whole agent turn.
- **ViewModel tests** use `MainDispatcherRule` and `ViewModelHost`; the host clears view models at
  the end of each test so nothing they started reaches Main after it's reset.
- **Time**: use virtual time (`runTest`, `runCurrent`, `advanceTimeBy`). Pass `backgroundScope` as
  TestGraph's scope and drive it with `runCurrent()`; `advanceUntilIdle()` doesn't wait for
  background work. Where real time can't be avoided (real processes in `RemoteJobsTest`, real IO in
  onboarding), poll a condition with a deadline; never sleep for a fixed time.
- **sqlite3**: `SearchMigrationTest` runs SQL through the system `sqlite3` (with FTS5 and JSON).
  It skips where that's missing, except on CI (`CI=true`), where it fails so it can't silently stop
  running. GitHub's `ubuntu-latest` image ships sqlite3 3.45.1.
- HTTP is always a `MockEngine`; no test reaches the network.

## Instrumentation tests

They run the debug app, `dev.locus.debug`, on a connected phone or emulator, with a scripted model
instead of a real provider: no network, no API key, nothing of yours needed.

### Running on a phone

1. Turn on USB debugging and connect the phone (`adb devices` lists it). Android 13 or newer
   covers everything; older versions skip nothing but have no notification permission to grant.
2. Unlock it and leave it alone while the suite runs (about 10–20 minutes): the tests press Home,
   rotate the screen and open the notification shade. Keep the screen awake
   (Developer options → Stay awake) and turn off any lock that comes on by itself.
3. Run:

   ```sh
   ./gradlew :app:connectedDebugAndroidTest
   ```

   One class: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.locus.UndoTest`.
   The report is `app/build/reports/androidTests/connected/debug/index.html`.

**What it does to the phone.** It installs Locus Debug and the test APK, and Gradle uninstalls both
when it's done, so **any Locus Debug data on the phone is deleted** (the tests also wipe it before
each test). The release app, `dev.locus`, is a different app and is never touched. Set
`android.injected.androidTest.leaveApksInstalledAfterRun=true` to keep the APKs installed.

With several devices connected, every one runs the suite; set `ANDROID_SERIAL` to pick one.

### What's covered

| Test | What it checks |
|---|---|
| `ScreensOpenTest` | Every route of the navigation opens with its real Hilt view model (a missing generated Hilt module crashes here, not at build time). |
| `LaunchTest` | MainActivity starts on setup on a first run, and at home otherwise; home's doors open and come back through the real navigation. |
| `LongTaskTest` | 64 tool steps (real file writes) run to the end with no step cap; during the run Compose keeps reaching idle and the main thread answers in under a second. |
| `ApprovalTest` | A tool set to Ask shows the approval card; Allow runs it, Deny is recorded (audit and an error result). In the background the request is a notification with Deny/Allow, and tapping Allow in the shade runs it. |
| `LifecycleTest` | Home mid-task: the run goes on in the foreground service with its notification and finishes in the background. Rotation and an activity recreation mid-task: it carries on and nothing runs twice. |
| `ProcessDeathTest` | The process dies mid-task; on the next start the chat offers Continue, and continuing finishes without repeating a step or the user's message. |
| `MidTaskTest` | A note sent while the agent works (#55) joins the same task once, after a step; `/btw` (#52) is answered apart and never enters the chat or the task. |
| `RestoreTest` | A confirmed restore is applied at the next start; a swap that fails part-way (forced with file modes) is rolled back and the "Restore didn't finish" notice shows once. |
| `ScheduledRunTest` | A scheduled task through WorkManager's test driver: the real worker runs and its result is recorded; a worker Android stops is recorded as stopped. |
| `UndoTest` | Change tracking (#68): a scripted turn edits files, the card shows them, and Undo all puts them back (the agent's version goes to the trash). |

**Not validated on a device yet** (written against the code, compiled, never run when they were
added): all of the above. Most likely to need adjusting on a given phone: `ApprovalTest.allowFromTheNotification`
(it drives SystemUI's shade, whose layout differs by manufacturer), `LifecycleTest`'s rotation
(phones that ignore a forced orientation), and `RestoreTest.c_`/`d_` (they rely on Android enforcing
file modes for the app's own files, and fail early with a clear message if it doesn't).

### How it's built

- **Runner and application** (`testing/LocusTestRunner.kt`): `LocusTestRunner` runs every test in a
  Hilt test application (`@CustomTestApplication`) that starts like `LocusApplication`: a confirmed
  restore is applied in `attachBaseContext`, through the same `LocusStartup` code. The rest of
  startup runs once the test's Hilt graph exists.
- **Orchestrator**: each test runs in its own process (`testOptions.execution` in
  `app/build.gradle.kts`). Hilt builds a new graph per test, and two DataStores on one file in one
  process fail; it also gives the two-phase tests their real restart. There's no `clearPackageData`:
  `LocusTest` wipes Locus's data itself before each test (database, settings, workspace, agent home,
  queued messages, restore stage; not the vault or its Keystore key) and cleans up after.
- **Two-phase tests** (`ProcessDeathTest`, `RestoreTest`): the first test is `@KeepDataAfter`, the
  second `@KeepDataBefore`, and `@FixMethodOrder(NAME_ASCENDING)` keeps them in order. The
  orchestrator ends the first test's process with the task still running (or the restore staged),
  so the second test is a genuine next start. Run such a class whole, never one of its methods.
- **The model** (`testing/FakeModel.kt`): `FakeNetworkModule` replaces the app's `NetworkModule`
  (`@TestInstallIn`) with a Ktor `MockEngine`. Requests to `llm.test` are answered by the test's
  `FakeModel.Script`; anything else gets a 404. `FakeModel.steps(n)` is an n-step task that picks up
  from the tool results already in the chat; a `Gate` holds a reply until the test opens it, which is
  how tests act "mid-task" without timing. Settings (`LocusTest.seed`) turn off titles, suggestions
  and reflection, so nothing else calls the model.
- **WorkManager** gets `WorkManagerTestInitHelper` with Hilt's worker factory; `getTestDriver` runs work.
- **UI**: Compose's `v2` test rules; `testing/ChatUi.kt` types into the composer (tagged
  `COMPOSER_TAG`) and taps buttons by their labels. UiAutomator presses Home, rotates and drives the
  shade. `HiltTestActivity` (debug builds only) hosts single screens.

### Adding a test

1. A class in `app/src/androidTest/kotlin/dev/locus`, `@HiltAndroidTest`, extending `LocusTest`;
   add `@get:Rule(order = 3) val compose = createEmptyComposeRule()` (from `...junit4.v2`) if it
   uses the UI.
2. Script the model with `FakeModel.script { request -> ... }` (or `FakeModel.steps`), create a chat
   with `newChat()`, open it with `launchChat(id)`, then `compose.send(...)`, `compose.awaitText(...)`,
   `awaitRunEnd(id)`.
3. Assert on what's stored too (`conversations.history`, `workspace`, the audit), not only the screen.
4. Never sleep for a fixed time: wait for a condition (`waitUntil`, `compose.waitUntil`) or a `Gate`.
5. Override `toolPolicies` for tools that should ask; `seedProvider = false` to start unconfigured.

### CI

`.github/workflows/instrumentation.yml` runs the suite on an API 35 `google_apis` x86_64 emulator
with [reactivecircus/android-emulator-runner](https://github.com/ReactiveCircus/android-emulator-runner)
(v2.38.0), with KVM enabled on the Linux runner and a cached AVD snapshot. It runs **nightly and on
demand** (Actions → Instrumentation tests → Run workflow), not on PRs: booting an emulator and a
process per test takes far longer than the JVM suite, and emulators on shared runners are slower and
less predictable. It doesn't block PRs until it has been reliably green. Gradle Managed Devices was
the alternative; it needs the same KVM setup on GitHub's runners and adds AVD management to the build.

### Sources

- Hilt testing: https://developer.android.com/training/dependency-injection/hilt-testing, https://dagger.dev/hilt/testing
- Compose testing: https://developer.android.com/develop/ui/compose/testing (v2 rules: .../testing/migrate-v2)
- Orchestrator: https://developer.android.com/training/testing/instrumented-tests/androidx-test-libraries/runner
- WorkManager: https://developer.android.com/develop/background-work/background-tasks/testing/persistent/integration-testing
- UiAutomator: https://developer.android.com/training/testing/other-components/ui-automator
- Versions in `gradle/libs.versions.toml`, checked against Google Maven on 2026-11-01.

## Known gaps

- **UI on the JVM**: Compose screens have no JVM tests; the instrumentation suite opens every
  screen and drives the chat, but most feature-module UI lines still aren't counted by Kover.
- **Android glue** not covered yet, on the JVM or a device: `WorkspaceRepository` import/export
  (ContentResolver), `DeviceTools` (intents), `AgentBrowser`'s WebView, `LinuxEnvironment` (proot),
  SSH (`SshService` against a server), the JavaScript sandbox and the on-device engine.
  `AgentService`, WorkManager scheduling, restore at startup and process death are covered by the
  instrumentation tests.
- **Not yet tested** though testable with TestGraph: `ActivityRepository`, `AnalyticsRepository`'s
  flows, `ModelDirectory`, `McpClients`, `SubagentRunner` end to end, the Library, Tasks, Activity,
  Workspace and Analytics ViewModels.
- **`fetch_url`** returns page text without the untrusted marking the other web tools use; it's
  also the generic HTTP tool, so whether to wrap it is a design decision, not a test gap.
- **Trash ids** in `Workspace.restore`/`purgeTrash` aren't validated; they only ever come from the
  trash listing (the UI), never from the agent.
- No coverage thresholds yet; once the numbers settle, `kover { reports { verify { ... } } }` can
  hold the merged line coverage.
