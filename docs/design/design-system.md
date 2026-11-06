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
- Write "go to Settings → X" as plain text. Use `SettingsLink` (or `DestinationLink` for somewhere else). A message from a lower layer may say "Settings → X" (`SettingsDestination` lists the pages); the screen that shows it adds `SettingsLinkIn` or `SettingsActionFor`.

## Rules

The design guard (`DesignGuardTest`, see [Guardrail](#guardrail)) enforces these in CI. Each is a count per file: a file may not go above its line in `core/designsystem/design-guard-baseline.txt`, and a new file must have none. The baseline only ever goes down.

| Rule | Flags | Use instead |
|---|---|---|
| `raw-dp` | `16.dp`, `12.sp` (anything but `0.dp`) | `Spacing`, `IconSize`, `Stroke`, `Sizes`, `Radius`; a `LocusType` role for text sizes |
| `alpha-literal` | `alpha = 0.6f` | an `Emphasis` level |
| `raw-colour`, `named-colour` | `Color(0xFF…)`, `Color.White` | `MaterialTheme.colorScheme`, `Locus.colors`, a `Tone`; `onPrimary` for an icon on the accent |
| `system-dark-check` | `isSystemInDarkTheme()`, `luminance() < 0.5` | `Locus.isDark` |
| `raw-shape` | `RoundedCornerShape(…)` | `MaterialTheme.shapes` (`small` chip, `medium` control, `large` card, `extraLarge` sheet) or `Radius.pill` |
| `material-dialog` | `AlertDialog` | `ConfirmSheet`, or `LocusSheet` for anything bigger |
| `material-switch` | a bare `Switch(` | `SwitchRow` |
| `material-top-bar` | `TopAppBar`, `CenterAlignedTopAppBar` | `LocusTopBar` (with `subtitle` and `status`) |
| `material-feedback` | `Snackbar`, `SnackbarHost`, `Toast.makeText` | `Locus.feedback.show(…)` for transient messages, `Banner` for persistent ones |
| `material-progress` | `LinearProgressIndicator`, `CircularProgressIndicator` | `Meter` for progress; `InlineSpinner`, `LoadingState` or a `busy` flag for waiting |
| `material-chip` | `AssistChip`, `FilterChip`, `SuggestionChip`, `InputChip` | `LocusChip` (Status, Filter or Count) |
| `material-type` | `MaterialTheme.typography.bodySmall` and the other slots a role covers | the `LocusType` role that says what the text is |
| `muted-text` | `color = …onSurfaceVariant` on a `Text`, `onSurfaceVariant.copy(alpha…)` | `SupportingText`; `Emphasis.muted` on `onSurface` for a faded icon |

A line that really needs the literal says why, on the line or the comment line above it:

```kotlin
val paper = Color.White // design-exception: PDF paper is white in both themes
```

A marker without a reason excuses nothing. Exceptions are for content that isn't UI (paper, a terminal's palette, a Glance widget that can't read the theme), not for a size the tokens don't have. If a size or component is missing, add it to `core/designsystem` with a preview and a row in this document, then use it.

Before opening a PR that touches UI: run `./gradlew :core:designsystem:testDebugUnitTest`. If it fails, fix the code, not the baseline. If your PR removes violations, lower the baseline (the test prints the entries that can go down) with:

```sh
LOCUS_DESIGN_GUARD_UPDATE=1 ./gradlew :core:designsystem:testDebugUnitTest --tests '*DesignGuardTest*'
```

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
| `dot` | 8 | A state dot (`StatusDot`) or a chart legend's dot | 8 |

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
| `series` | 0.4 | A chart's quieter series in the same hue as the main one (tokens in beside out), or neutral bars on `onSurface` |
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
| `body` | bodyLarge | Prose: the paragraphs of a message or a rendered document. `core/markdown`'s `documentTypography()` builds the Markdown renderer's whole scale from these roles (`compact = true` inside a card). |
| `rowSupporting` | bodyMedium | The line under a row's title, body text in cards |
| `supporting` | bodySmall | Notes, captions, help under a control |
| `label` | labelLarge | Buttons, links, a status line |
| `chip` | labelMedium | Chips |
| `mono` | `MonoCaption` | Times, sizes, paths, ids |
| `code` | `CodeTextStyle` | Code and command output |
| `figure` | titleLarge, mono | A figure in a row of figures on a card ("Requests 1.2k") |
| `heroFigure` | 40sp mono semibold | The one big number a screen is about (Insights' token total) |

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

`touchTarget` 48dp is the minimum for anything tappable. `row` 56dp is a `ListRow`'s minimum height. `readable` 320dp is the widest a paragraph gets in a centred state or info card (`EmptyState`, `ErrorState`, the file info card). `inlineScroll` 240dp is the tallest a list or preview gets inside a card or sheet before it scrolls on its own (the model suggestions under a field). `policySegmented` 156dp is the width of the compact Allow / Ask / Deny `Segmented` beside a tool's name. `contentMax` 840dp (Material's expanded breakpoint) is the widest the app's content gets on a tablet or in landscape; `MainActivity` centres it beyond that.

`TerminalSizes` (beside `TerminalColors`) holds the interactive terminal's denser sizes: a tab name's maximum width, the extra keys' 40×44dp minimum, their 6dp `keyShape` and 14sp label. The extra-keys row is a keyboard, so its keys are smaller than the 48dp rule, as in Termux. `TerminalColors.keyLatched` is a latched Ctrl or Alt.

`WidgetSizes` holds the home-screen widget's corner, padding, status dot and two text sizes. Glance draws with RemoteViews and can't read `LocusType` or `MaterialTheme.shapes`, so the widget takes its sizes from here and its colours from `LocusLightColors`/`LocusDarkColors`.

## Components

| Need | Use | Notes |
|---|---|---|
| A surface | `LocusCard` | A glow for the one card a screen is about, an outline for attention |
| A list in a card | `CardGroup` + `ListRow` + `GroupDivider` | `ListRow(enabled = false)` fades, can't be tapped, and TalkBack says it's disabled. A `SectionLabel` above it starts its text `Spacing.lg` in, where the rows' content starts (it was 20dp, 4dp off). Leading icons: one per kind of thing, the same on every screen; on-device models use `LocusIcons.Chip` (LiteRT) and `LocusIcons.Layers` (a GGUF file, llama.cpp). A row that reports how something went leads with the outcome, not its kind: `Check` (done, `Tone.Live`), `Skip` (skipped on purpose, neutral), `Error` (failed, `Tone.Critical`) or an `InlineSpinner` in an `IconSize.tile` box (running), as in a task's trigger history. `Calendar` and `Sunrise` mark calendar entries and the morning digest. |
| A switch beside a row that does something else when tapped | `InlineSwitch` | A scheduled task's on/off next to a row that expands. `contentDescription` is required ("Run “Backup” on its schedule"). Use `SwitchRow` whenever the whole row can be the switch. |
| A setting that's on or off | `SwitchRow` | The whole row toggles (TalkBack: a switch). `busy` shows a spinner, `error` shows a red line under it, `enabled` |
| A label and a value | `KeyValueRow` | Full row, or `dense` for receipts and details. `tone`, `mono`, `onClick` (with a chevron and a 48dp target). From 150% font a dense row puts its label over its value, so the label column never cuts a word. |
| A state or fact | `LocusChip` (Status) | Tone container with on-container text, an optional `dot` and `icon`. With `onClick` it's a small action. `StatusPill` is now this. |
| A filter option | `LocusChip(variant = Filter)` | Outlined when off, tinted with a check when on. TalkBack: a checkbox. `dotColor` puts a dot in what the chip is about (a tool `Accent`); the check replaces it when selected. |
| A row of filter options | `ChipRow` | One line that scrolls sideways when the chips don't fit, `Spacing.sm` apart, so a chip is never orphaned on a line of its own (at 200% font too). Under a `SectionLabel`, above the list it filters. |
| Chips under a row's title, or a sheet's facts | `ChipFlow` | Wraps onto more lines instead of overlapping or clipping, `Spacing.xs` apart both ways. Use it as a `ListRow`'s `meta`. |
| A card's actions | `ActionRow` | End-aligned text buttons that wrap to a second line at large font sizes. Its padding lines the buttons' text up under a `ListRow`'s inside a `CardGroup`. |
| A download or copy in progress | `TransferProgress` | Under the `ListRow` that names it, in a `CardGroup`: a `Meter`, the status in mono and an `ActionRow` (Pause, Resume, Cancel). The status is two lines, amount over speed ("0.8 / 1.9 GB" over "4.2 MB/s · 3 min left"), in one unit (`GgufDownloadText.amount`), with no-break spaces so a figure never splits from its unit at 200% font. `start = Insets.iconRow` lines the meter and status up with the text of a row that has a tile. `announcement` goes to TalkBack through a polite live region; change it in steps (every 10 %), not every byte. |
| Code, a diagram or an embedded preview | `CodeSurface` | Soft fill, hairline edge, control radius. Quieter than a card: it's content, not a thing to act on. Chat code blocks, Mermaid, artifact previews and Workspace's Markdown fences share it. |
| Several lines of text to edit | `TextArea` in a `LocusCard` | Borderless, so a Save or Reset row can share the card. `TextAreaSize.Regular` (96dp) for instructions, `Tall` (160dp) for a whole prompt; `mono` for prompts and templates; `placeholder` shows muted while it's empty. |
| Something floating over content | `Modifier.floatingSurface()` | Card colour, hairline edge, soft shadow; the composer. Its bar's sizes are `ComposerSizes`. |
| A count on an icon button's corner | `CountBadge` | "9+" past nine. Say what it counts in the button's description. |
| Voice mode's tap target | `VoiceOrb` | A halo and a solid core in the phase's colour, scaled with the voice. Sizes in `VoiceSizes`. |
| A browser's top chrome | `BrowserToolbar` + `AddressField` | One row: close, the address pill, then actions (tab count, overflow). The pill takes the row while editing; the bottom edge is a hairline that becomes the load progress, so the page never moves. Pass it as `LocusScreen(topBar = …)`, which then uses `title` as the TalkBack pane title. `AddressField(security = SiteSecurity.Secure/NotSecure/Unknown)` leads with a padlock, a caution triangle or a globe and says so to TalkBack; `trailing` puts reload inside the pill; a long host is cut from its start. |
| A few things to go back to, in one row | `Shelf` + `ShelfCard` (+ `ShelfAddCard`) | A row of small cards that scrolls sideways, every card as tall as the tallest; `bleed` runs it to the screen edges past a list's padding. A card is a title and one line: a muted `meta`, or a `status` in its tone with the state dot (caution also outlines it). `ShelfSizes.card` 148 dp, widening with the font up to 1.5×; `ShelfAddCard` (at least 72 dp) goes first. Give each card a `contentDescription` that says the name, the meta and the state. `shortRelativeTime` ("2h ago") fits the meta line; TalkBack gets `relativeTime`. Home's projects. |
| Search in a list | `SearchField` | The pill with a search icon, the query and Clear. Search IME action; `requestFocus` for a screen opened to search. |
| A count | `LocusChip(variant = Count)` | `chipCount(n)` caps it at "99+". Always give it a `contentDescription` ("3 tabs"). |
| A quiet action under a message | `CompactIconButton` | A 16dp icon in a 32dp button with the full 48dp touch target: copy, regenerate, a branch arrow, copy code. |
| A button floating over content | `FloatingIconButton` | Round, raised, hairline edge; "Jump to latest". `rotation` turns the icon. |
| The kinds of tools a run used | `AccentTileStack` | Small (20dp) `AccentTile`s side by side, 2dp apart, so each icon reads on its own. Decorative; the text beside it says what it means. |
| How each run of a repeated check went | `RunDots` | One 6dp dot per run, oldest first, in its result's tone (live passed, critical failed, caution unclear or timed out, brand running); past 8 the oldest fold into "+N". Decorative: the row's text says the result. |
| The band under the chat's composer | `BottomDock` | Solid background with a `DockFade` (16dp) fade above it, so the transcript fades out instead of showing between docked cards. Goes in the bottom bar; put the window insets inside it so the band reaches under the navigation bar. |
| Suggested follow-ups after a reply | `SuggestionRow` | One row of Brand `LocusChip`s that scrolls sideways, at most a touch target tall; a long one ellipsizes at 280dp so the next peeks in. TalkBack hears a list of "Suggested: …" buttons with the full text. No padding of its own above or below. |
| Pictures sent with a message | `ImageGrid` | One compact block, at most `ImageGridSize` (240dp) wide: one keeps its shape, two side by side, three as one large and two small, four or more as 2×2 with "+N" on the last. One `Radius.control` outline with a hairline edge, 2dp between tiles. Quiet fill while decoding, the image icon when the file is gone. TalkBack: "Photo 1 of 2, name". |
| Opening those pictures | `ImageGallery` | Full screen and black in both themes; swipe between pictures, pinch or double-tap to zoom, drag when zoomed; a counter and Close. |
| A non-picture file sent with a message | `FileChip` | Type icon, name (middle-ellipsized) and size in one compact chip; never a card per file. |
| The user's message | `UserBubbleShape` | The bubble with the corner nearest the edge tucked in. `ChatSizes` holds the chat's few fixed sizes (compact and floating buttons, the media card's width, an attachment thumbnail, the wide-layout breakpoint, the follow slack). |
| Values over time | `BarChart` | Stacked bars per day or week, `colors` bottom to top; tap a bar to select it. `ChartSize.Regular` 96dp, `Compact` 72dp. `ChartLegend` names a colour; `SplitBar` is one bar split by count (approvals). |
| Progress or usage | `Meter` | `fraction` 0..1, or null for indeterminate. The tone comes from the level by default (brand, then caution from 80%, critical from 95%). Sizes: `Hairline` 2, `Small` 4, `Medium` 6, `Large` 8. TalkBack reads it as a progress bar with a percentage. |
| Something working in place | `InlineSpinner` | 20dp in the content colour. Add a description if nothing beside it says what's loading. |
| A screen that's loading | `LoadingState` | Shows nothing for 300ms, then a spinner |
| A screen with nothing in it | `EmptyState` | Title, one sentence, an optional action |
| A screen that couldn't load | `ErrorState` | What went wrong and Try again. `retrying` shows a spinner. The message is announced. `tone = Caution` and an `action` for a wall the user can work around rather than a failure (a gated model: "Search for …"). |
| A problem that persists, inline | `Banner(tone = Critical/Caution)` | With actions |
| A step the user put off (Quick start's skipped setup) | `SetupCard` | Plain card, no tone: a tinted tile, an eyebrow ("Finish setting up · 3 left"), title, body, one `TextButton` and a dismiss button in the header. The card itself isn't tappable. One at a time on Home (docs/design/onboarding-quick-start.md). |
| A yes or no question | `ConfirmSheet` | Instead of `AlertDialog`. `tone = Critical` for destructive actions; `busy`, `error`, and `content` for a field. Swiping it away means Cancel. |
| A form or a picker | `LocusSheet` | From 150% font the header action moves under the title, so the title and subtitle keep the sheet's width. |
| "Saved", "Couldn't save", "Undo" | `Locus.feedback.show(message, tone, action, onAction)` | One snackbar channel. Long when there's an action or a problem. `MainActivity` hosts it app-wide; a screen with a bottom bar hosts its own above the bar. |
| "Go to Settings → Models" | `SettingsLink(SettingsDestination.Models, hint = …)` | A `DestinationLink` that opens the page. TalkBack reads "Settings, Models". `hint` is the sentence before it. Works anywhere under the app root, which provides the navigation (`ProvideSettingsLinks`); back returns to the link's screen. |
| An error or notice that may name a page | `SettingsLinkIn(message)`, or `SettingsActionFor(message)` in a `Banner`'s actions | Finds "Settings → X" in text from elsewhere (`SettingsDestination.inText`) and links it; nothing when it names none. The banner button says "Open X"; use `actionsBelow = hasSettingsAction(message)`. |
| Somewhere that isn't Settings | `DestinationLink(destinationPath("Library", "Connectors"), onNavigate)` | A button with a 48dp target. `openAppDetails(context)` for Android's App info page. |
| "On until 14:30" | `rememberBefore(deadline)` | True until the time passes, then false at that moment, so the text doesn't go stale. |
| A screen | `LocusScreen` | `LocusTopBar`, the background (a Surface, so text takes `onBackground`), and the status- and navigation-bar insets. `screenContentPadding(padding)` gives the standard inset: 16 at the sides, 8 under the bar, 32 above the navigation bar. Full-bleed content (a transcript, a terminal, a web page) uses the padding as it is. |
| A screen that is one list | `LocusListScreen` | `LocusScreen` with a `LazyColumn` at the standard inset: Settings pages, Insights, Tasks. |
| A titled group of rows or facts | `LocusSection` (or `locusSection` in a lazy list) | `SectionLabel`, an optional `note` and `action`, then the card. The label's text starts where the card rows' content does (16dp in), so header and card read as one block. |
| A screen's top bar | `LocusTopBar` | Drawn by `LocusScreen`; use it directly only inside the design system. `subtitle` for one muted line, `status` for a custom one. The title is a heading for TalkBack. |
| Tabs or a 2 to 3 way choice | `Segmented` | |
| A row that opens in place | `ExpandableRow` | |

### Feedback: one channel

- **Transient** ("Saved", "Copied", "Scheduled", "Couldn't save"): `Locus.feedback`. The message queues in the root snackbar host. An action ("Undo", "Retry") runs in the host's scope, so it still runs after the screen has gone.
- **Persistent** (the user must do something): a `Banner` on the screen it concerns.
- **The screen itself failed**: `ErrorState`.

A ViewModel never holds `LocusFeedback`. It exposes an event or a result, and the screen calls `show`. `MainActivity` wraps the app in `ProvideLocusFeedback` with `LocusFeedbackHost` at the root, above the navigation bar and keyboard. A screen with a bottom bar (chat, workspace, terminal, tasks, browser) hosts its own inside its frame, so its messages sit above the bar. Code that runs outside composition (an Intent helper, a menu callback) takes a `LocusFeedback` parameter and the composable passes `Locus.feedback`; a callback from a long-lived object (a terminal session) sends an event the screen collects. A Toast (`toastFeedback(context)`) is only for a message that fires as Locus goes to the background, such as right after handing off to another app, where a snackbar would never be seen; the call says why with `// toast: <reason>`. `ToastGuardTest` fails on any other Toast.

## Guardrail

`core/designsystem/src/test/.../DesignGuardTest.kt` runs with the module's unit tests (`testDebugUnitTest`), and so in CI. It scans every module's `src/main` Kotlin outside `core/designsystem`, counts each [rule](#rules) per file, and fails when any file is above its line in `core/designsystem/design-guard-baseline.txt` or a file that isn't listed has anything at all. Comment lines and lines with a `design-exception: <reason>` don't count.

The failure names the file, the rule, the count and the fix. The full picture (every rule by module, and the baseline entries that can now be lowered) is printed and written to `core/designsystem/build/reports/design-guard/report.txt`.

When it became enforced (2026-11-02) the baseline held 1,102 entries in 72 files: 396 Material type slots, 305 raw dp, 240 muted-text pairs, 53 corner shapes, 52 alphas, 14 progress indicators, 12 Toasts and snackbars, 10 switches, 6 dark checks, 5 named colours, 4 Material chips, 3 dialogs and 2 top bars. `feature/chat` holds a little over half of it.

Regenerate the baseline only to lower it, after removing violations:

```sh
LOCUS_DESIGN_GUARD_UPDATE=1 ./gradlew :core:designsystem:testDebugUnitTest --tests '*DesignGuardTest*'
```

Never regenerate it to let a new violation in. The other modules' sources are declared inputs of the test task, so a cached result can't hide one.

## Review gate

The design guard checks raw values. It can't see a screen. So a UI change also has to be looked at, as pictures, before it ships.

**There are no committed screenshots.** No golden images live in the repository, and CI uploads none: screenshots are never stored on GitHub. The checks that need no stored image run in CI; a person looks at the renders locally.

- Screenshot tests render every screen, sheet and design-system component in five variants: 360 and 411dp wide, light and dark, plus 360dp at 200% font.
- CI's `screenshots` job runs `recordRoborazziDebug`, beside the unit tests. Each render goes to the module's `build/screenshots` and is checked by the render guard (contrast and raw ids, against `src/test/render-guard-baseline.txt`), then thrown away with the runner. Its failure reports leave out Roborazzi's image folders. In the `unit-tests` job the screenshot tests run without drawing.
- `ContrastTest`, `ComponentCoverageTest` and the design guard need no images and run as ordinary unit tests.
- `.gitignore` covers `src/test/screenshots/`, `build/screenshots/`, Roborazzi's outputs and local render folders, so an image can't be committed by accident.
- A new screen, sheet or state gets a screenshot test in the same PR. A new design-system component gets one in `ComponentScreenshots`, and `ComponentCoverageTest` fails until it has.
- Before asking for review, render everything at 2x locally and look through it, in both themes and at 200% font. Describe what you checked in the PR; don't attach or commit the images. Check for:
  - one focus per screen;
  - one left edge;
  - spacing on the scale;
  - contrast;
  - truncation, wrapping and overlap;
  - raw ids;
  - decoration without meaning;
  - the loading, empty and error states.

```sh
./gradlew :feature:chat:recordRoborazziDebug     # draw one module's screens into its build/ and run the render guard
./gradlew recordRoborazziDebug                   # what CI checks
tools/render-screens.sh build/screens xhdpi      # every screen at 2x, for a person (stays local)
```

How it works:

- [Roborazzi](https://github.com/takahirom/roborazzi) draws the composables with Robolectric's native graphics inside the ordinary `testDebugUnitTest` task, so there is no emulator and no extra CI job.
- Tests extend `ScreenshotTest` (`core/screenshot`) and call `snap(name) { … }` with fake state, `snapPreview(…)` for an existing `@Preview`, or `snapAfter(name, steps) { … }` for a state only a tap reaches: the steps (`Step.Tap`, `Step.Type`, `Step.Act`) run on the rendered screen before each capture, so a sheet a row opens is in the picture. `PrivateComposable` calls a private sheet, `HiltHostActivity` hosts a route that calls `hiltViewModel()`, and `UtcRule` pins the time zone so clock times read the same everywhere.
- The harness gives the content `LocusTheme` and nothing else, no Surface. A screen that doesn't paint its own background shows as such.
- It renders with system animations off, so entrances have landed and spinners stand still.
- Without `recordRoborazziDebug` (or `-Proborazzi.test.record=true`), the tests run without drawing, so a local unit-test run stays fast.

Choice of tool (2026-11):

- **Roborazzi 1.76** works with AGP 9.5, Kotlin 2.4 and Robolectric 4.17, the first Robolectric with SDK 37. It runs inside the unit-test task, takes fake state and per-test qualifiers, and draws sheets and dialogs. Google's Now in Android uses it on AGP 9.3.
- **Google's Compose Preview Screenshot Testing** was rejected. Its AGP test-suite form (AGP 9.5+) is marked experimental, with "APIs and DSL syntax subject to change". It needs `@PreviewTest` functions in a separate source set and adds its own Gradle tasks.
- **Paparazzi** was rejected. Its AGP 9 support is only in 2.0.0-alpha05.1.

Sources: [Roborazzi releases](https://github.com/takahirom/roborazzi/releases), [Robolectric releases](https://github.com/robolectric/robolectric/releases), [Compose Preview Screenshot Testing with AGP test suites](https://developer.android.com/studio/preview/compose-screenshot-testing-with-testsuites), [Paparazzi releases](https://github.com/cashapp/paparazzi/releases), and [Now in Android's version catalogue](https://github.com/android/nowinandroid/blob/main/gradle/libs.versions.toml).

**JVM renders are close, not exact.**

- Robolectric draws with the host's Skia and Robolectric's fonts. The phone may differ slightly in font metrics, ripple and shadow, and the system bars aren't drawn.
- A render on macOS can differ from Linux CI by a few anti-aliased pixels, so a contrast finding near the threshold can differ between them; CI's result is the one that counts.
- Checking the screen on a real phone stays the last step before a UI change is called done.

**Screen and section rules** (enforced by the design guard, see [Rules](#rules)):

| Rule | Flags | Use instead |
|---|---|---|
| `screen-scaffold` | `Scaffold(` or `LocusTopBar(` in feature code | `LocusScreen` / `LocusListScreen`: one top bar, one set of insets, one content inset |
| `adhoc-insets` | `systemBarsPadding()`, `statusBarsPadding()`, `navigationBarsPadding()`, `safeDrawingPadding()` | `LocusScreen`, which handles the bars |
| `background-not-surface` | `.background(MaterialTheme.colorScheme.background)` or `.surface` | `Surface(color = …)` or `LocusScreen`. A painted background doesn't set the content colour, so `Text` falls back to black, which is invisible in the dark theme. |
| `adhoc-section-header` | a muted `cardTitle`/`titleSmall` text drawn by hand | `LocusSection` / `SectionLabel` |
| `raw-identifier` | a `gguf:`/`sha256:` key, or `${x.id}`, `.key`, `.hash`, `.digest` interpolated into a title, body or chip | Say what the thing is in words. An id belongs in a labelled, mono details row. |
| `named-colour` | `Color.White`, `Color.Black` and the other named colours, in feature code **and** in `core/designsystem` | A theme role. A vector mask, a shadow or a brand-mandated button says why with `// design-exception:` |
| `text-colour` | `Text(…, color = x)` where `x` isn't `MaterialTheme.colorScheme.*`, `Locus.*` or `LocalContentColor` | `SupportingText`, a `LocusType` role in the default content colour, or a theme role |

The guard can't tell every raw id from a word, so this one is also a review rule: no hashes, file keys, repo slugs or internal ids in titles or body text.

**The render guard checks the pictures.** Every `snap` that draws (record or verify) also inspects what it drew, through the semantics tree and the pixels (`core/screenshot/RenderGuard.kt`):

| Rule | Flags |
|---|---|
| `text-contrast` | text whose drawn colour, against the colour most of its box is painted, is below WCAG AA: 4.5:1, or 3:1 from 18sp (14sp bold). Disabled text is exempt. This catches text that fell back to black outside a Surface in the dark theme. |
| `raw-identifier` | text that shows a hash (16+ hex digits), a UUID, a provider call id (`call_…`, `toolu_…`) or a `gguf:`/`sha256:` key |

Findings are counted per screenshot and variant against `<module>/src/test/render-guard-baseline.txt`, the same ratchet as the source guard: a count may go down, never up, and a screenshot that isn't listed must be clean. Every finding, with its text, colours and ratio, is written to `<module>/build/reports/render-guard/findings.txt`. Lower the baseline when recording: `LOCUS_RENDER_GUARD_UPDATE=1 ./gradlew <module>:testDebugUnitTest -Proborazzi.test.record=true`.

Two tests in `core/designsystem` back the rest:

- `ContrastTest` checks every text role (`onBackground`, `onSurface`, `onSurfaceVariant`, `primary`, each tone's on-container colour, the button fills) against every surface (background, surface, card) in both themes, at WCAG AA, 4.5:1. Text that keeps to these roles, inside a Surface, can't be unreadable.
- `ComponentCoverageTest` makes sure every public composable here has a screenshot test.

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

   *Done: every page and sheet is on the system, and feature/settings has no baseline entries left. The local `SwitchRow` is gone (every switch, including the certificate's "Trust for every provider", the proxy sign-in and the key-header override, is the shared one in a `CardGroup`); "Delete?", "Remove?", "Sign out?" and "Clear?" are `ConfirmSheet`s; download, backup and restore progress are `Meter`s; the model suggestions sit in a `CardGroup`; the MCP cards are `LocusCard`s; the instruction and helper-prompt editors are the new `TextArea`. `SshAuthFields` and `TelegramSheet` in core/ui went with it. Two things are left: the shared `IntegrationKeySheet` (onboarding's has key validation and no remove confirmation, so merging them changes behaviour; it waits for the Onboarding PR), and the "Settings → Network" hints inside the provider's network sheet, which stay text because a link would leave an unsaved form.*
4. **Library**:
   - `LoadingState` and `ErrorState` per tab
   - `ImportBanner` → `Banner`
   - `ConfirmRemove` → `ConfirmSheet`
   - Toasts → `Locus.feedback`

   *After #114.*

   *The tabs are done (Skills, Agents, Connectors, local servers, Workflows): type roles and `SupportingText` throughout, `shapes.medium` for the code box, and the "Scheduled" Toast → `Locus.feedback`. `LibraryScreen.kt` itself waits for #121 and #127, which edit it.*
5. **Projects and project editor**: `ToolPolicyRow` shared with Settings and `ProjectChip` → `LocusChip`. (Its "Settings → SSH machines" and "Settings → Permissions" links landed with WP-3.)

   *Done: the projects list, a project's page, the editor and the chat's project sheet have no baseline entries left. Connector switches are `SwitchRow`s, "Allow … without asking?" and "Delete …?" are `ConfirmSheet`s, dividers use `Insets.iconRow`, and the list, the page and the editor show `LoadingState` instead of a blank screen while they load. Two items stay as they are, on purpose: `ToolPolicyRow` isn't shared, because the project's has a fourth option (Same) and a stacked layout, so one row would change one screen or the other; and `ProjectChip` keeps its plain look under the chat's title (now on tokens), because a `LocusChip` with its 48dp target doesn't fit the top bar's title slot. It moves with `RemoteChip` in the Chat PR.*
6. **Workspace and previews**:
   - `NoticeBanner` → `Banner`
   - its `SnackbarHost` and Toasts → `Locus.feedback` (Undo keeps working through `onAction`)
   - `isDark` → `Locus.isDark`, which takes CodeViewer and DocViewers out of the baseline

   *The previews are done: Info banners, `Locus.isDark`, `Emphasis`, `SupportingText`, `documentTypography()` for rendered Markdown, and `IconSize.hero` and `Sizes.readable` for the info card. The PDF page's white paper is the one `design-exception`. `WorkspaceScreen` and the terminal follow once the Linux-in-Workspace PRs land.*
7. **Insights**: `ShareBar` and the tool bar → `Meter`, `Figure` → `KeyValueRow`, and 19 raw dp → tokens.

   *Done: feature/analytics has no baseline entries left. The bar chart, its legend and the approvals bar moved into the design system as `BarChart`, `ChartLegend` and `SplitBar`; `ShareBar` is a `Meter`; the token total uses the new `LocusType.heroFigure` and the figures `LocusType.figure`; the chart's quieter series use `Emphasis.series`; loading and empty fill 60% of the list instead of a fixed 360dp. `Figure` stays three figures across, with label and value read as one item, rather than becoming a `KeyValueRow`: three stacked rows would change the cards' layout.*
8. **Activity**: `FilterPill`/`ChipRow` → `LocusChip(Filter)` and 17 raw dp → tokens.

   *Done: filter chips are `LocusChip(Filter, dotColor)`, the search pill is the shared `SearchField` (Home's copy follows in its PR), rows are 48dp, decision pills are `LocusChip`, and every caption is `SupportingText`. The timeline's fixed time and duration columns are the two `design-exception`s.*
9. **Onboarding**: spinners → `InlineSpinner`, the progress bar → `Meter`, and `IntegrationKeySheet` → the shared one.

   *Done: feature/onboarding has no baseline entries left. Install spinners are `InlineSpinner`s, the step bar and the model download are `Meter`s, the persona choices are `LocusChip(Filter)`, command output sits on `CodeSurface`, and the dividers in the radio and checkbox lists start where the text does (after the 48dp control). `IntegrationKeySheet` stays separate from Settings': onboarding's checks the key as you type and removes without asking, Settings' asks first and offers the vendor's MCP server, so one sheet would change one of them.*
10. **`core/ui` sheets** (`WorkflowSheets`, `TelegramSheet`): spinners → `InlineSpinner`, and switches → `SwitchRow`. `TelegramSheet`'s remote switches, duration chips and spinner moved with WP-3.
11. **Home (`ConversationsScreen`)**: `Pill` → `LocusChip`, 22 raw dp, and the raw `TopAppBar` → `LocusTopBar(subtitle)` if the greeting fits.

    *Done: `ConversationsScreen.kt` has no baseline entries left. The greeting fits: Home's top bar is `LocusTopBar("Locus", status = HomeStatus)`. `Pill` is gone (projects strip → `LocusChip`, the All chats project filter → `LocusChip(Filter)`), the All chats search is the shared `SearchField`, "Delete this chat?" is a `ConfirmSheet`, the launcher's attention mark is a `StatusDot`, the chat menu button has its full 48dp target, and every caption is `SupportingText`.*
12. **Chat, composer, voice and `core/markdown` cards: last.**

    *The composer and voice mode are done: `LocusComposer.kt` and `voice/` have no baseline entries left. The composer sits on `floatingSurface()` (its shadow is the design system's one allowed black, kept in `Composer.kt`), its bar uses `ComposerSizes` (36dp controls in 48dp targets) and the shared `CountBadge`, an active pill is `Tone.Brand.container()`, and disabled controls use `Emphasis.disabled`. The + sheet's rows are `ListRow`s in `CardGroup`s, and Plan mode is a `SwitchRow`. Voice mode's circle is the new `VoiceOrb` (sizes in `VoiceSizes`), "Microphone on" is a critical `LocusChip`, and the approval card's outline uses `Emphasis.toneOutline`. `BarPill` stays its own control rather than a `LocusChip`: it's a 36dp control that holds a provider logo, and a chip's 24dp height would shrink the bar.*
    - `SheetRow` → `ListRow`, and `SwitchRow` → the shared one
    - the chat chips (`RemoteChip`, `PausedChip`, `ReadingChip`, `BudgetMeterChip`, `LinkChip`, `BarPill`) → `LocusChip`
    - `SendWarningDialog`/`SteerDialog`/`ArtifactCard` dialogs → `ConfirmSheet`
    - about 190 raw dp → tokens
    - the raw `TopAppBar` → `LocusTopBar(subtitle)`
    - `Color.White` → `onPrimary`
    - the terminal's and Mermaid's dark checks → `Locus.isDark`

    *Chat's sheets and small parts are done (ChatControlsUi, ModelSheet, RemoteUi, SentSheet, SideQuestionUi, SendWarningUi, HeatUi, MemoryReadUi, ReviewerBlocks, ReadAloud, ShareChat): the tools sheet uses the shared `SwitchRow` in `CardGroup`s, `SendWarningDialog` is a `ConfirmSheet` with the same three ways out, "Forget …?" and "Run on this phone again?" are `ConfirmSheet`s, the model sheet's search is `SearchField`, remote progress is a `Meter`, the checklist ring is `ContextRing(color = live)`, `ReadingChip` is a `LocusChip`, and Toasts go through `Locus.feedback` (or `toastFeedback` outside composition). `ContextRing` takes an optional `color` for a ring that shows progress rather than a level.*

    *After the chat perf and reliability PRs. `core/markdown` is done ahead of it: Mermaid and the artifact card sit on `CodeSurface`, read `Locus.isDark`, use `SupportingText` and `LocusType`, the artifact's "Open in browser?" is a `ConfirmSheet`, and its Toasts go through `Locus.feedback`. The two fixed heights (a diagram's placeholder, the inline preview) and LaTeX's absolute fallback size are its `design-exception`s.*

    *Rows done: `ChatScreen`, the tool, helper, change, verification, receipt, question and file cards, `ChatCode`, `ChatMarkdown` and `TranscriptScroll` have no baseline entries left. The chat's top bar is `LocusTopBar(status = …)` with the project and remote chips under the title; follow-ups, answer options, the task budget and Activity links are `LocusChip`s (Other… is a filter chip); the context bar is a `Meter`; code blocks, tool input and command output sit on `CodeSurface`; the receipt's fields are dense `KeyValueRow`s; Steer is a `LocusSheet`; the file card's and Steer's Toasts go through `Locus.feedback`; the terminal panel and code highlighting read `Locus.isDark`. New in core/designsystem (`ChatParts.kt`): `CompactIconButton`, `FloatingIconButton`, `AccentTileStack`, `UserBubbleShape` and `ChatSizes`. The diff in a change's sheet now fills the sheet instead of stopping at 560dp.*

13. **The rest** (`WorkflowSheets`, Workspace, the terminal, Library, Tasks' switch, `MainActivity`, `AutomationActivity`, the widget).

    *Done: the baseline is empty. `WorkflowSheets`' spinner is an `InlineSpinner`, a yes/no input is a `SwitchRow`, and its captions are `SupportingText`. Workspace and the terminal host their own feedback (until step 1) and show "Moved to Trash … Undo" through `Locus.feedback`; Trash's "Delete permanently?" and the Library's `ConfirmRemove` are `ConfirmSheet`s; the close buttons on terminal tabs and Trash rows are `CompactIconButton`s. Tasks' on/off switch sits beside a row that expands, so it's the new `InlineSwitch` rather than a `SwitchRow`. The terminal's and the widget's fixed sizes are `TerminalSizes` and `WidgetSizes`, and `MainActivity`'s content width is `Sizes.contentMax`. One `design-exception` was added: the browser's tab-count glyph, whose corner scales with the icon. Step 1 (the app-root feedback host) is still open; Tasks, Browser, Workspace and the terminal host their own until it lands.*

Strings (`stringResource`) are extracted module by module after this migration, so each file is touched once.
