# Contributing to Locus

Thanks for helping. A few things keep the project coherent. Everyone taking part is expected to follow the [Code of Conduct](CODE_OF_CONDUCT.md).

## Ways to help

- **Report a bug** or **suggest a feature** with the [issue forms](https://github.com/springmusk026/locus/issues/new/choose).
- **Try what hasn't been tried on a phone.** [docs/STATE.md](docs/STATE.md#tested-on-the-phone-vs-not-yet) lists features that are only unit-tested, and many design notes in [docs/design/](docs/design/) end with a phone checklist. A report that one works (or doesn't) on your device helps.
- **Send code or docs** as described below.
- **Report security problems privately**, as [SECURITY.md](SECURITY.md) describes, never in a public issue.

## Before you start

- For anything bigger than a small fix, open an issue first so we can agree on the approach.
- Read [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and [docs/STATE.md](docs/STATE.md). If the feature has a note in [docs/design/](docs/design/), read that too, and update it with your change.

## Principles

- **Local-first and private.** No telemetry, no Locus server, no analytics SDKs. Requests go only to providers and services the user configured.
- **Secrets never reach the model.** Keys and tokens live in the encrypted vault and are injected at call time.
- **The user stays in control.** New tools need a sensible default policy (Allow, Ask or Deny). Anything that changes things outside the workspace, or sends something on the user's behalf, asks first.
- **No AccessibilityService.** Locus doesn't read or operate other apps.
- **Prompt caching matters.** A conversation's tool list and system prompt stay fixed. Restrict tools with policies, not by removing them.
- **Research before adding a dependency.** Use the current stable version from Maven Central or Google Maven. Note why you chose it, with sources, in docs/ARCHITECTURE.md.
- **Finished, not half-built.** A feature is wired end to end: reachable from the UI, with its loading, empty and error states, built from the design system.

## Build and test

You need JDK 21 (Android Studio's bundled one works) and the Android SDK.

```sh
./gradlew :app:assembleDebug                                      # debug APKs, installable next to a release build
./gradlew testDebugUnitTest :core:agent:test :core:llm:test :core:model:test   # unit tests
./gradlew lintDebug
```

`:core:agent`, `:core:llm` and `:core:model` are plain Kotlin modules (`test`); the rest are Android modules (`testDebugUnitTest`). [docs/TESTING.md](docs/TESTING.md) covers running one test class, coverage, the instrumentation suite on a phone and the agent benchmarks.

## Code

- Kotlin, Jetpack Compose, MVVM: one `uiState` StateFlow per ViewModel. Features depend on `core:data`, never on `core:database` entities.
- Match the surrounding code: naming, comment density, idioms.
- Add unit tests for logic: tool arguments, policies, parsing, adapters. Mock HTTP with Ktor's `MockEngine`.

## UI: the design system and the review gate

- Build UI from `core/designsystem` (spacing, colours by meaning, `LocusCard`, `ListRow`, `LocusSheet`…), not one-off styles. The rules are in [docs/design/design-system.md](docs/design/design-system.md), "Rules", and `DesignGuardTest` enforces them: no raw dp, alpha or colour literals, no `AlertDialog`, `Toast`, bare `Switch` or `TopAppBar`, Material type slots or hand-rolled muted text outside the design system. Counts can only go down; a new file must be clean. If a size or component is missing, add it to the design system (with a preview and a row in the doc) rather than inlining it. A line that genuinely needs a literal says why with `// design-exception: <reason>`.
- A new screen, sheet or state gets a screenshot test in the same PR; a new design-system component gets one in `ComponentScreenshots`.
- The render guard checks contrast and raw ids in every render (CI runs `recordRoborazziDebug`). It can't judge a screen, so before asking for review, render the screens you touched and look at them, in light and dark and at 200% font. The checklist is in [docs/design/design-system.md](docs/design/design-system.md), "Review gate".

```sh
./gradlew :feature:chat:recordRoborazziDebug   # one module's screens, into its build/ folder
tools/render-screens.sh build/screens xhdpi    # every screen at 2x, for a person to look through
```

- **No screenshots in git.** Don't commit golden images, renders or other screenshots, and don't attach them to the pull request; describe what you checked instead. `.gitignore` covers the render folders. Images that are part of the product or its docs (icons, `docs/screenshots/`) change only in a PR about them.

## Before opening a pull request

```sh
./gradlew assembleDebug testDebugUnitTest :core:agent:test :core:llm:test :core:model:test lintDebug
```

CI runs the same commands, plus the screenshot render guard and a release build with R8, as parallel jobs behind one `build` check. Draft PRs, and PRs that change only `docs/` or top-level Markdown files, skip the Gradle jobs. See [docs/design/ci.md](docs/design/ci.md). If you changed behaviour, try it on a real device or emulator and say what you tried.

- Add a line for anything a user would notice to [CHANGELOG.md](CHANGELOG.md) under `[Unreleased]` ([Keep a Changelog](https://keepachangelog.com/en/1.1.0/) format). Say if it hasn't been tried on a phone.
- Fill in the pull request template's checklist.

## Commits and pull requests

- Keep commits focused, one change each, with a message that says what changed and why.
- Pull requests are usually squash-merged, so the PR title becomes the commit on `main`. Write it as a plain summary, optionally starting with the area: `Browser: reliable dropdowns`, `Fix flaky RemoteExecutionTest`.
- Don't force-push a branch that's under review; add commits instead.

## Dependency verification

Gradle checks every downloaded dependency, plugin and build-logic artifact against the SHA-256 in [gradle/verification-metadata.xml](gradle/verification-metadata.xml) ([Gradle's docs](https://docs.gradle.org/current/userguide/dependency_verification.html)). A changed or unknown artifact fails the build with "Dependency verification failed", and a report under `build/reports/dependency-verification/`. Sources and javadoc jars, which only IDEs download, are exempt.

After adding or updating a dependency or plugin, including a Dependabot PR (Dependabot doesn't update this file), regenerate the file and commit it with the change:

```sh
tools/update-verification-metadata.sh
```

The script starts from an empty file and an empty Gradle user home (as on CI), so checksums of versions no longer used drop out and nothing is missed because it was already cached. The first run downloads everything, so it takes a while. It runs every task CI and the release workflow run, resolves every configuration (`gradle/verification/resolve-all.init.gradle`), and adds aapt2 for Linux, macOS and Windows, because Gradle downloads it only for the machine it runs on. Then look at the diff. Each new checksum is a download you're vouching for, so check that the new entries are the versions you meant to add. If you can, compare one or two checksums with the ones the repository publishes (Maven Central serves `<artifact>.sha256` next to each file).

## Licence

By contributing, you agree that your contributions are licensed under the GNU Affero General Public License v3.0 or later (AGPL-3.0-or-later), the project's licence.
