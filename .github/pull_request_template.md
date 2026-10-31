## What and why

## How it was tested
- [ ] `./gradlew assembleDebug testDebugUnitTest :core:agent:test :core:llm:test :core:model:test lintDebug` passes
- [ ] Tried on a device or emulator (screenshots for UI changes)

## Checklist
- [ ] No secrets can reach the model, logs or other apps
- [ ] New tools have a sensible default policy, and outward actions ask first
- [ ] New dependencies are researched and noted in docs/ARCHITECTURE.md
