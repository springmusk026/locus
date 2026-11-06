# Quick start in onboarding

Status: built 2026-11-06 ([ROADMAP](../ROADMAP.md), "Quick start in onboarding"; [Product U4](../research/product-platform-2026.md#u-ux-patterns)). Code: `SetupItem` and `AppSettings.setupLater` in `core/model`, `SettingsRepository.deferSetup` / `clearSetup` and `core/data/.../setup/SetupChecklist.kt`, `SetupCard` in `core/designsystem`, the model step's buttons in `feature/onboarding/.../OnboardingScreen.kt` and `OnboardingViewModel.quickStart`, and `HomeSetupCard` in `feature/chat/.../ConversationsScreen.kt`. Tests: `OnboardingViewModelTest` (Quick start section), `ConversationsViewModelTest` (setup cards section), `ComponentScreenshots.setupCard`, `HomeScreenshots.homeSetup*`, `OnboardingScreenshots.providerQuickStart`.

## The problem

Setup has nine steps. Only the model is needed to chat; identity, Linux, SSH machines and connectors are useful but optional, and each one asks for a decision the user may not be ready to make on minute one. Competing apps reach a first answer in one screen.

## What the guidance says (researched 2026-11-06)

- **Material's onboarding models.** Material describes a *Quickstart* model, where users land straight in the UI with no onboarding beyond sign-in and setup, next to *self-select* and *top user benefits* ([Material Design, onboarding](https://m2.material.io/design/communication/onboarding.html); [Material 1, onboarding](https://m1.material.io/growth-communications/onboarding.html)). Material 3 hasn't republished this page; the guidance is from M1 and M2.
- **NN/g: skip onboarding when possible.** Instructions users must digest before they can use a product reduce usability, many people skip tutorials, and contextual help in the moment works better than a front-loaded flow ([Mobile-app onboarding](https://www.nngroup.com/articles/mobile-app-onboarding/), [Onboarding: skip it when possible](https://www.nngroup.com/videos/onboarding-skip-it-when-possible/), [Mobile tutorials](https://www.nngroup.com/articles/mobile-tutorials/)).
- **Progressive disclosure** (Nielsen, 1995): defer secondary features to where they're needed, to keep the first screens simple ([summary](https://en.wikipedia.org/wiki/Progressive_disclosure)).
- **Android itself defers setup.** After a skipped part of Pixel setup, a "Finish setup" item sits at the top of Settings for a few days ([Pixel user guide](https://fcc.report/FCC-ID/A4RGGX8B/7308622.pdf)); Settings' suggestion cards work the same way ([9to5Google](https://9to5google.com/2018/10/11/google-pixel-settings-suggestions/)).
- **Comparable agent apps** sign in and open a chat; personalisation and connectors are found later in settings. ChatGPT's custom instructions live in Settings → Personalization ([OpenAI Academy](https://openai.com/academy/personalization)); Claude's connectors are added from Settings → Connectors ([Claude help](https://support.claude.com/en/articles/10168395-set-up-claude-integrations)). Neither brings the skipped setup back on its own. Locus does, because its optional steps (Linux, SSH) are what make it an agent rather than a chat app.
- **Cards and actions.** One primary action per card, in an explicit button; a card that holds buttons shouldn't also be tappable as a whole (third-party summaries of M3 card guidance, [example](https://cdn.jsdelivr.net/npm/@hegemonart/get-design-done@1.60.4/reference/components/card.md); M2 puts card actions at the bottom, [cards](https://m2.material.io/components/cards)).

## Design

**The model step.** Once a model is saved, the bottom bar offers **Quick start** (filled, the primary action) and **Continue full setup** (text button). A line under "Your models" says what Quick start skips and where it goes. Without a model, the step looks as before ("Set up a model to continue").

**Quick start** stores the skipped steps that aren't already done in `AppSettings.setupLater` (identity unless persona files exist, Linux unless it's installed or can't run on this phone, SSH unless a machine is saved, connectors unless a key or connector is), then marks onboarding done and opens Home. Home, not a new chat: the first setup card is in view next to the ask bar, so the user sees at once where the rest went.

**Home: one card at a time.** The first pending item is a `SetupCard` after the projects strip and before a task result and the chat groups, so it never pushes the active chat or the launcher down. Its eyebrow reads "Finish setting up · 3 left". The action opens the screen where that step is done, and the close button dismisses it.

| Item | Card | Opens |
|---|---|---|
| Identity | Name your agent | Settings → Agent identity (`PersonaRoute`) |
| Linux | Install Linux | Workspace, whose Linux card installs it |
| SSH | Add a machine | Settings → SSH machines |
| Connectors | Connect your services | Settings → Integrations |

**Why one card rather than a "Finish setting up · 3 left" row that expands.** Both keep Home calm. The row needs an expand pattern on Home that nothing else uses, and once expanded it shows a four-row checklist: the same wizard, moved. One card asks one thing at a time, with its own reason and one button. It also matches the anatomy of Home's other cards (tinted tile, card title, body text), and its eyebrow still says how many are left. The cost is that the user can't pick which step comes next. The order (identity first, the cheapest and the one every chat reads) and dismissing make up for that.

**When a card goes.**
- **Set up anywhere**, including Settings, the Workspace, or the agent adding a connector: `SetupChecklist` sees it and the card drops at once. The item is also cleared from settings, so deleting that machine later doesn't bring the card back. Identity has no live signal, because the persona files aren't observed, so saving it (from onboarding or Settings → Agent identity) clears the item directly.
- **Dismissed:** cleared from settings, so it stays gone after a restart. The next item takes its place. With all dismissed or done, Home has no card.
- **Linux can't run** on this phone (no runtime for its ABI): counts as done, so its card is never shown.

**Upgrades.** `setupLater` defaults to empty, and an older version's settings decode without it, so existing users get no cards. So does anyone who went through the full setup. Re-running onboarding from Settings offers Quick start too; there it defers only what isn't set up.

**Storage.** One `Set<SetupItem>` in the existing settings DataStore; no Room change. It isn't sealed: it grants nothing, and editing it outside Locus only shows or hides a card.

## The component

`SetupCard(title, body, icon, action, onAction, onDismiss, eyebrow)` in `core/designsystem`, with a render in `ComponentScreenshots` ("setup-card").
- A plain `LocusCard`, with no glow or tone outline, because nothing is wrong. That separates it from a `Banner`, which is for problems.
- A primary-tinted `IconTile`, the eyebrow in `LocusType.chip`, and the title in `LocusType.cardTitle`.
- The dismiss button sits at the header's end, as on a `Banner`. TalkBack hears it as "Dismiss: <title>".
- Body text in `rowSupporting`, never cut.
- One `TextButton` in an `ActionRow`. The card itself isn't tappable, so there are two targets, and each says what it does.

## Not done

- No "Remind me later" (snooze). Dismissing is final for that card, and every item stays one tap away in Settings or the Workspace.
- The card doesn't show progress (for example, Linux installing). The Workspace's own card does.
