## What and why

## How it was tested
- [ ] `./gradlew assembleDebug testDebugUnitTest :core:agent:test :core:llm:test :core:model:test lintDebug` passes
- [ ] Tried on a device or emulator (screenshots for UI changes, light and dark)
- [ ] UI changes use `core/designsystem` tokens and components (docs/design/design-system.md, "Rules"); `:core:designsystem:testDebugUnitTest` passes without raising the baseline, and any `design-exception` says why

## Checklist
- [ ] No secrets can reach the model, logs or other apps
- [ ] New tools have a sensible default policy, and outward actions ask first
- [ ] New dependencies are researched and noted in docs/ARCHITECTURE.md
