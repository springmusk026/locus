## What and why

<!-- What changed, and the problem it solves. Link the issue: "Closes #123". -->

## How it was tested

<!-- Commands you ran, and what you tried on a device or emulator. -->

- [ ] `./gradlew assembleDebug testDebugUnitTest :core:agent:test :core:llm:test :core:model:test lintDebug` passes
- [ ] New logic has unit tests (tool arguments, policies, parsing, adapters)
- [ ] Tried on a device or emulator, or says here why not

## UI changes

<!-- Skip if there are none. -->

- [ ] Built from `core/designsystem` tokens and components ([docs/design/design-system.md](https://github.com/springmusk026/locus/blob/main/docs/design/design-system.md), "Rules"); `:core:designsystem:testDebugUnitTest` passes without raising the baseline, and any `design-exception` says why
- [ ] New screens, sheets and states have screenshot tests, and `recordRoborazziDebug` passes the render guard
- [ ] Renders reviewed locally at 2x, light and dark and at 200% font (loading, empty and error states too); described below, not attached
- [ ] No PNGs or other screenshots committed or attached

## Checklist

- [ ] No secrets can reach the model, logs or other apps
- [ ] New tools have a sensible default policy, and outward actions ask first
- [ ] New dependencies are researched and noted in docs/ARCHITECTURE.md, and `gradle/verification-metadata.xml` is regenerated
- [ ] User-visible changes are in `CHANGELOG.md` under [Unreleased]
- [ ] Docs updated where behaviour changed (docs/design/, README.md, SECURITY.md)
