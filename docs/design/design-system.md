# Design system

Status: tokens and shared components built 2026-11-02 (WP-4a). Screens move over in the PRs listed under [Migration](#migration). Code: `core/designsystem`. Previews of every component, light and dark: `core/designsystem/.../Previews.kt` (open it in Android Studio's split view).

Locus should look like one deliberate thing: paper and ink neutrals, one moss accent, and colour only where it means a state. Everything a screen needs to look like that is in `core/designsystem`. A feature screen arranges these pieces. It doesn't invent sizes, colours or alphas.

## Rules for feature code

Do:

- Use the tokens: `Spacing`, `Radius`, `IconSize`, `Stroke`, `Emphasis`, `Motion`, `Sizes`.
- Take colours from `MaterialTheme.colorScheme`, `Locus.colors` or a `Tone` (`tone.color()`, `tone.container()`, `tone.onContainer()`).
- Ask `Locus.isDark` whether the theme is dark.
- Use the type roles in `LocusType`, and `SupportingText` for muted text.
- Use the shared component when one exists (the table below), even if a hand-made one looks a little closer to the mock-up. Change the shared one instead.
- Give every tappable thing a 48dp touch target (`Sizes.touchTarget`). Give every icon that isn't next to a label a content description.
- Handle each state: loading (`LoadingState`, `InlineSpinner`, `busy`), empty (`EmptyState`), error (`ErrorState` or a critical `Banner`), and disabled (`enabled = false`).

Don't:

- Write raw dp (`16.dp`) other than `0.dp`. If no token fits, the value is probably wrong. If it really is new, add a token here with a reason.
- Write colour literals (`Color(0xFF…)`) or named colours (`Color.White`) outside `core/designsystem`. The exceptions are content that isn't UI (PDF paper is white) and the composer's shadow.
- Write `copy(alpha = 0.N)`. Use an `Emphasis` level.
- Call `isSystemInDarkTheme()` or test `background.luminance() < 0.5f`. Both disagree with `LocusTheme(darkTheme = …)` as soon as there's an in-app theme setting.
- Use `AlertDialog`. Use `ConfirmSheet`, or `LocusSheet` for anything bigger.
- Use `Toast` or a screen's own `SnackbarHost`. Use `Locus.feedback`.
- Write "go to Settings → X" as plain text. Use `DestinationLink`.

The guardrail below enforces the colour and dark-mode rules. The others are counted so that each migration PR can show its numbers going down.

## Tokens

The values come from what the code already used most (counts from the design audit, main code outside `core/designsystem`).

### Spacing (`Spacing`)

| Token | dp | Use |
|---|---|---|
| `xxs` | 2 | Between a title and the line under it. 2.dp was written out 21 times. |
| `xs` | 4 | Related lines in a group, icon to label in a chip |
| `sm` | 8 | Items in a row, a section label to its card |
| `md` | 12 | Inside a card, between cards, a row's vertical padding |
| `lg` | 16 | Screen edges, a row's horizontal padding |
| `xl` | 24 | Between sections, sheet edges |
| `xxl` | 32 | Around an empty or error state |

There is no 6, although it was used 19 times. Use `xs` or `sm`. `Insets.iconRow` is where a divider starts in a list of rows with a leading tile.

### Radius (`Radius`)

| Token | Value | Use |
|---|---|---|
| `chip` | 8dp | Small controls, a link's press shape |
| `control` | 12dp | Tiles, fields, a feedback message |
| `card` | 16dp | Cards (`LocusCard`, `CardGroup`) |
| `sheet` | 28dp | Sheets, the composer |
| `pill` | 50% (a `Shape`) | Chips, the segmented control, meters. It replaces the 13 copies of `RoundedCornerShape(50)`. |

### Icon sizes (`IconSize`)

| Token | dp | Use | Replaces |
|---|---|---|---|
| `small` | 16 | Inline with text, a chip's icon, a dense chevron | 16 (23 uses), 14 (8), 15 |
| `medium` | 20 | A row's trailing icon, `InlineSpinner` | 20 (13), 18 (22), 22 |
| `large` | 24 | Default `Icon` size, every `IconButton` | 24 |
| `tileSmall` | 28 | `AccentTile` in step and tool rows | 28, 32 (7) |
| `tile` | 36 | A list row's leading `IconTile` | 36 (8) |
| `hero` | 56 | The tile above an empty or error state | 44, 48, 56 |

### Stroke (`Stroke`)

| Token | dp | Use |
|---|---|---|
| `hairline` | 0.5 | Card edges, dividers, an unselected filter chip. 0.5.dp was written out 12 times. |
| `regular` | 1 | An outline that means something: selection, attention |
| `bold` | 2 | Spinners and progress rings |

### Emphasis (alpha) (`Emphasis`)

These replace the 20 ad hoc alphas, which ranged from 0.035 to 0.85.

| Token | Alpha | Use |
|---|---|---|
| `subtleFill` | 0.06 | A neutral fill behind an icon or a track (`onSurface` at this alpha). 0.035 to 0.06 all become this. |
| `hairline` | 0.08 | A divider drawn in a tone's colour |
| `neutralContainer` | 0.10 | The neutral tone's chip fill |
| `toneContainer` | 0.14 | A tone's container (`Tone.container()`) |
| `toneOutline` | 0.35 | A tone's outline on a card that needs attention (`Banner`) |
| `disabled` | 0.38 | Content of a control that can't be used (Material's own value) |
| `muted` | 0.6 | De-emphasised content that must stay readable |

### Tones (`Tone`, `Locus.colors`)

| Tone | Means | Colour | Container | On container (text) |
|---|---|---|---|---|
| `Neutral` | Nothing in particular | `onSurfaceVariant` | at 0.10 | `onSurfaceVariant` |
| `Brand` | The accent: "Locus" or "your choice" | `primary` (moss) | at 0.14 | `primary` |
| `Live` | Running now, or done well (success) | `Locus.colors.live` | at 0.14 | `onLiveContainer` |
| `Caution` | Needs the user | `Locus.colors.caution` | at 0.14 | `onCautionContainer` |
| `Critical` | Went wrong | `Locus.colors.critical` | at 0.14 | `onCriticalContainer` |
| `Info` | Worth knowing, needs nothing | `Locus.colors.info` (the Files accent's blue, so it adds no new hue) | at 0.14 | `onInfoContainer` |

Text on a tone's container uses `tone.onContainer()`, never `tone.color()`. In the light theme the state colours fall under 4.5:1 on their own tint: live 3.3, caution 3.4, info 3.7 and critical 4.1. The on-container shades are a step deeper and reach 4.7 to 5.2. In the dark theme the state colours already pass (5.6 and up), so they're used as they are. `StatusPill` gets this fix for free, because it is now `LocusChip`.

### Dark mode

`LocusTheme(darkTheme)` decides, and everything reads it back as `Locus.isDark`. `Accent`, `LocusCard`'s glow, the file-type tile and the ChatGPT button now do this too, so the design system has no `isSystemInDarkTheme()` left apart from the theme's default argument. Six feature files still compute it themselves (the baseline lists them). They move over in their screens' migration PRs.

### Type roles (`LocusType`)

| Role | Style | Use |
|---|---|---|
| `screenTitle` | titleLarge | A screen's or sheet's title |
| `stateTitle` | titleMedium | An empty or error state's title |
| `cardTitle` | titleSmall | A card's or banner's title, a section label |
| `rowTitle` | bodyLarge | A list row's title |
| `rowSupporting` | bodyMedium | The line under a row's title, body text in cards |
| `supporting` | bodySmall | Notes, captions, help under a control |
| `label` | labelLarge | Buttons, links, a status line |
| `chip` | labelMedium | Chips |
| `mono` | `MonoCaption` | Times, sizes, paths, ids |
| `code` | `CodeTextStyle` | Code and command output |

`SupportingText(text, style = LocusType.supporting)` is the muted pair, a supporting role in `onSurfaceVariant`. It replaces the 98 hand-written pairs: 42 bodySmall, 21 bodyMedium and 35 MonoCaption, each with onSurfaceVariant.

### Motion (`Motion`)

| Token | Value | Use |
|---|---|---|
| `spring()` | damping 0.86, stiffness 420 | Things that move: sliding selections, reordering |
| `quick()` / `QUICK_MS` | 220ms | Presses, toggles, small state changes |
| `standard()` / `STANDARD_MS` | 320ms | Content appearing: entrances, expanding rows |
| `slow()` / `SLOW_MS` | 600ms | A value filling: meters, rings |
| `STAGGER_MS` | 40ms | Between items that enter one after another |
| `PULSE_MS` | 1400ms | The live dot's halo. It's the only looping motion, and it runs only while something is running. |

All of these use `FastOutSlowInEasing`. Compose scales them by the system's animation speed, so "Remove animations" turns them off.

### Sizes (`Sizes`)

`touchTarget` 48dp is the minimum for anything tappable. `row` 56dp is a `ListRow`'s minimum height.

## Components

| Need | Use | Notes |
|---|---|---|
| A surface | `LocusCard` | A glow for the one card a screen is about, an outline for attention |
| A list in a card | `CardGroup` + `ListRow` + `GroupDivider` | `ListRow(enabled = false)` fades, can't be tapped, and TalkBack says it's disabled |
| A setting that's on or off | `SwitchRow` | The whole row toggles (TalkBack: a switch). `busy` shows a spinner, `error` shows a red line under it, `enabled` |
| A label and a value | `KeyValueRow` | Full row, or `dense` for receipts and details. `tone`, `mono`, `onClick` (with a chevron and a 48dp target) |
| A state or fact | `LocusChip` (Status) | Tone container with on-container text, an optional `dot` and `icon`. With `onClick` it's a small action. `StatusPill` is now this. |
| A filter option | `LocusChip(variant = Filter)` | Outlined when off, tinted with a check when on. TalkBack: a checkbox. |
| A count | `LocusChip(variant = Count)` | `chipCount(n)` caps it at "99+". Always give it a `contentDescription` ("3 tabs"). |
| Progress or usage | `Meter` | `fraction` 0..1, or null for indeterminate. The tone comes from the level by default (brand, then caution from 80%, critical from 95%). Sizes: `Hairline` 2, `Small` 4, `Medium` 6, `Large` 8. TalkBack reads it as a progress bar with a percentage. |
| Something working in place | `InlineSpinner` | 20dp in the content colour. Add a description if nothing beside it says what's loading. |
| A screen that's loading | `LoadingState` | Shows nothing for 300ms, then a spinner |
| A screen with nothing in it | `EmptyState` | Title, one sentence, an optional action |
| A screen that couldn't load | `ErrorState` | What went wrong and Try again. `retrying` shows a spinner. The message is announced. |
| A problem that persists, inline | `Banner(tone = Critical/Caution)` | With actions |
| A yes or no question | `ConfirmSheet` | Instead of `AlertDialog`. `tone = Critical` for destructive actions; `busy`, `error`, and `content` for a field. Swiping it away means Cancel. |
| A form or a picker | `LocusSheet` | |
| "Saved", "Couldn't save", "Undo" | `Locus.feedback.show(message, tone, action, onAction)` | One snackbar channel. Long when there's an action or a problem. It falls back to a Toast until the app root provides a host. |
| "Go to Settings → Models" | `DestinationLink(destinationPath("Settings", "Models"), onNavigate)` | A button with a 48dp target. TalkBack reads "Settings, Models". `hint` is the sentence before it. |
| A screen's top bar | `LocusTopBar` | `subtitle` for one muted line, `status` for a custom one. The title is a heading for TalkBack. |
| Tabs or a 2 to 3 way choice | `Segmented` | |
| A row that opens in place | `ExpandableRow` | |

### Feedback: one channel

- **Transient** ("Saved", "Copied", "Scheduled", "Couldn't save"): `Locus.feedback`. The message queues in the root snackbar host. An action ("Undo", "Retry") runs in the host's scope, so it still runs after the screen has gone.
- **Persistent** (the user must do something): a `Banner` on the screen it concerns.
- **The screen itself failed**: `ErrorState`.

A ViewModel never holds `LocusFeedback`. It exposes an event or a result, and the screen calls `show`. Until `MainActivity` wraps the app in `ProvideLocusFeedback` and puts `LocusFeedbackHost` at the root (migration step 1), a screen can host its own the same way, as the browser does. Without any host, `show` falls back to a Toast and drops the action.

## Guardrail

`core/designsystem/src/test/.../DesignGuardTest.kt` runs with the module's unit tests, and so in CI. It scans every module's `src/main` Kotlin outside `core/designsystem`.

- **Enforced against a baseline** (`core/designsystem/design-guard-baseline.txt`): `Color(0x…)` literals and dark-mode checks (`isSystemInDarkTheme()`, `luminance() < 0.5`). A file may not gain one. When it started there were no colour literals and six dark checks.
- **Report-only**: raw dp, `alpha = 0.N` literals and named colours. The counts per module are written to `core/designsystem/build/reports/design-guard/report.txt` and printed in the test output. When it started: 296 raw dp in 48 files, 53 alpha literals and 5 named colours.

When a migration removes violations, regenerate the baseline so they can't come back:

```sh
LOCUS_DESIGN_GUARD_UPDATE=1 ./gradlew :core:designsystem:testDebugUnitTest --tests '*DesignGuardTest*'
```

Never regenerate it to let a new violation in. The other modules' sources are declared inputs of the test task, so a cached result can't hide one.

## Migration

The order runs from least to most churn and conflict. This PR (WP-4a) added the tokens and components above, deleted the unused `SectionHeader`, `ThinkingDots` and `FactsLine`, and moved **Browser** over completely as the worked example:

- `AlertDialog` → `ConfirmSheet`
- clear confirmation → `ConfirmSheet` with busy, error and feedback
- the progress line → `Meter`
- the URL → `SupportingText(mono)`
- raw dp → tokens

Each remaining step below is one PR. It moves the screen completely, then lowers the guard baseline or report counts.

1. **App root**: `ProvideLocusFeedback` and `LocusFeedbackHost` in `MainActivity`. The browser's own host goes. *Needs MainActivity, so it lands after the open PRs that edit it.*
2. **Tasks**:
   - `ConditionRow` → `SwitchRow`
   - `TaskRow` → `ListRow`
   - Run-now feedback → `Locus.feedback`
   - `ErrorState` for a load failure

   *Done in WP-1 ("Scheduled tasks you can follow"), which also moved the delete confirmation to `ConfirmSheet`. Tasks hosts its own feedback until step 1. Alpha literals 53 → 52; feature/tasks has no raw dp, alphas or colours left.*
3. **Settings**:
   - `SettingsCommon.SwitchRow` → the shared `SwitchRow`
   - `PrivacyRow`/`DiagnosticRow` → `KeyValueRow` where they're key-value
   - `ModelStatePill` → `LocusChip`
   - `ModelsSettings` `Surface` → `LocusCard`
   - the shared `IntegrationKeySheet` into `core/ui`
   - the "Clear …?" sheets → `ConfirmSheet`

   *After WP-3.*
4. **Library**:
   - `LoadingState` and `ErrorState` per tab
   - `ImportBanner` → `Banner`
   - `ConfirmRemove` → `ConfirmSheet`
   - Toasts → `Locus.feedback`

   *After #114.*
5. **Projects and project editor**: `ToolPolicyRow` shared with Settings, `ProjectChip` → `LocusChip`, and `DestinationLink` for "Settings → SSH machines".
6. **Workspace and previews**:
   - `NoticeBanner` → `Banner`
   - its `SnackbarHost` and Toasts → `Locus.feedback` (Undo keeps working through `onAction`)
   - `isDark` → `Locus.isDark`, which takes CodeViewer and DocViewers out of the baseline
7. **Insights**: `ShareBar` and the tool bar → `Meter`, `Figure` → `KeyValueRow`, and 19 raw dp → tokens.
8. **Activity**: `FilterPill`/`ChipRow` → `LocusChip(Filter)` and 17 raw dp → tokens.
9. **Onboarding**: spinners → `InlineSpinner`, the progress bar → `Meter`, and `IntegrationKeySheet` → the shared one.
10. **`core/ui` sheets** (`WorkflowSheets`, `TelegramSheet`): spinners → `InlineSpinner`, and switches → `SwitchRow`.
11. **Home (`ConversationsScreen`)**: `Pill` → `LocusChip`, 22 raw dp, and the raw `TopAppBar` → `LocusTopBar(subtitle)` if the greeting fits.
12. **Chat, composer, voice and `core/markdown` cards: last.**
    - `SheetRow` → `ListRow`, and `SwitchRow` → the shared one
    - the chat chips (`RemoteChip`, `PausedChip`, `ReadingChip`, `BudgetMeterChip`, `LinkChip`, `BarPill`) → `LocusChip`
    - `SendWarningDialog`/`SteerDialog`/`ArtifactCard` dialogs → `ConfirmSheet`
    - about 190 raw dp → tokens
    - the raw `TopAppBar` → `LocusTopBar(subtitle)`
    - `Color.White` → `onPrimary`
    - the terminal's and Mermaid's dark checks → `Locus.isDark`

    *After the chat perf and reliability PRs.*

Strings (`stringResource`) are extracted module by module after this migration, so each file is touched once.
