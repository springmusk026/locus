# Testing

All tests are JVM unit tests: no emulator, no Robolectric, no network. About 460 of them run in a
couple of minutes.

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

## Known gaps

- **UI**: Compose screens have no tests (no Compose UI test setup on the JVM yet). Most uncovered
  lines in the feature modules are UI.
- **Android glue**: `AgentService`, `TaskWorker`/`TaskRepository` scheduling (WorkManager),
  `WorkspaceRepository` import/export (ContentResolver), `DeviceTools` (intents), `AgentBrowser`'s
  WebView, `LinuxEnvironment` (proot), SSH (`SshService` against a server), the JavaScript sandbox
  and the on-device engine. These need a device or Robolectric.
- **Not yet tested** though testable with TestGraph: `ActivityRepository`, `AnalyticsRepository`'s
  flows, `ModelDirectory`, `McpClients`, `SubagentRunner` end to end, the Library, Tasks, Activity,
  Workspace and Analytics ViewModels.
- **`fetch_url`** returns page text without the untrusted marking the other web tools use; it's
  also the generic HTTP tool, so whether to wrap it is a design decision, not a test gap.
- **Trash ids** in `Workspace.restore`/`purgeTrash` aren't validated; they only ever come from the
  trash listing (the UI), never from the agent.
- No coverage thresholds yet; once the numbers settle, `kover { reports { verify { ... } } }` can
  hold the merged line coverage.
