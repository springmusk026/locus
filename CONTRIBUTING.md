# Contributing to Locus

Thanks for helping. A few things keep the project coherent.

## Before you start

- For anything bigger than a small fix, open an issue first so we can agree on the approach.
- Read [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and [docs/STATE.md](docs/STATE.md).

## Principles

- **Local-first and private.** No telemetry, no Locus server, no analytics SDKs. Requests go only to providers and services the user configured.
- **Secrets never reach the model.** Keys and tokens live in the encrypted vault and are injected at call time.
- **The user stays in control.** New tools need a sensible default policy (Allow, Ask or Deny). Anything that changes things outside the workspace, or sends something on the user's behalf, asks first.
- **No AccessibilityService.** Locus doesn't read or operate other apps.
- **Prompt caching matters.** A conversation's tool list and system prompt stay fixed. Restrict tools with policies, not by removing them.
- **Research before adding a dependency.** Use the current stable version from Maven Central or Google Maven. Note why you chose it, with sources, in docs/ARCHITECTURE.md.

## Code

- Kotlin, Jetpack Compose, MVVM: one `uiState` StateFlow per ViewModel. Features depend on `core:data`, never on `core:database` entities.
- Build UI from `core/designsystem` (spacing, colours by meaning, `LocusCard`, `ListRow`, `LocusSheet`…), not one-off styles.
- Match the surrounding code: naming, comment density, idioms.
- Add unit tests for logic: tool arguments, policies, parsing, adapters. Mock HTTP with Ktor's `MockEngine`.

## Before opening a pull request

```sh
./gradlew assembleDebug testDebugUnitTest :core:agent:test :core:llm:test :core:model:test lintDebug
```

CI runs the same commands. If you changed UI, try it on a real device or emulator and add screenshots to the pull request.

Keep commits focused, one change each, with a message that says what changed and why.

## Licence

By contributing, you agree that your contributions are licensed under the Apache License 2.0.
